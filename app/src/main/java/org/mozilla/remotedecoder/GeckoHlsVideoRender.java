package org.mozilla.remotedecoder;

import android.annotation.TargetApi;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaCrypto;
import android.media.MediaFormat;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;

import com.google.android.exoplayer2.BaseRenderer;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.FormatHolder;
import com.google.android.exoplayer2.decoder.DecoderCounters;
import com.google.android.exoplayer2.decoder.DecoderInputBuffer;
import com.google.android.exoplayer2.drm.DrmInitData;
import com.google.android.exoplayer2.drm.DrmSessionManager;
import com.google.android.exoplayer2.drm.FrameworkMediaCrypto;
import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.NalUnitUtil;
import com.google.android.exoplayer2.util.TraceUtil;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.video.MediaCodecVideoRenderer;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Created by kilikkuo on 2/6/17.
 */

@TargetApi(16)
public class GeckoHlsVideoRender extends BaseRenderer {
    private static final String TAG = "GeckoHlsVideoRender";

    private static final long MAX_CODEC_HOTSWAP_TIME_MS = 1000L;
    private static final int RECONFIGURATION_STATE_NONE = 0;
    private static final int RECONFIGURATION_STATE_WRITE_PENDING = 1;
    private static final int RECONFIGURATION_STATE_QUEUE_PENDING = 2;
    private static final int REINITIALIZATION_STATE_NONE = 0;
    private static final int REINITIALIZATION_STATE_SIGNAL_END_OF_STREAM = 1;
    private static final int REINITIALIZATION_STATE_WAIT_END_OF_STREAM = 2;
    private static final byte[] ADAPTATION_WORKAROUND_BUFFER = Util.getBytesFromHexString("0000016742C00BDA259000000168CE0F13200000016588840DCE7118A0002FBF1C31C3275D78");
    private static final int ADAPTATION_WORKAROUND_SLICE_WIDTH_HEIGHT = 32;
    private final MediaCodecSelector mediaCodecSelector;
    private final DecoderInputBuffer buffer;
    private final FormatHolder formatHolder;
    private final List<Long> decodeOnlyPresentationTimestamps;
    private final MediaCodec.BufferInfo outputBufferInfo;
    private Format format;
    private MediaCodec codec;

    private boolean codecIsAdaptive;
    private boolean codecNeedsDiscardToSpsWorkaround;
    private boolean codecNeedsFlushWorkaround;
    private boolean codecNeedsAdaptationWorkaround;
    private boolean codecNeedsEosPropagationWorkaround;
    private boolean codecNeedsEosFlushWorkaround;
    private boolean codecNeedsMonoChannelCountWorkaround;
    private boolean codecNeedsAdaptationWorkaroundBuffer;
    private boolean shouldSkipAdaptationWorkaroundOutputBuffer;
    private ByteBuffer[] inputBuffers;
    private ByteBuffer[] outputBuffers;
    private boolean[] inputBufferUsable;
    private boolean[] outputBufferUsable;

    private long codecHotswapDeadlineMs;
    private int inputIndex;
    private int outputIndex;
    private boolean codecReconfigured;
    private int codecReconfigurationState;
    private int codecReinitializationState;
    private boolean codecReceivedBuffers;
    private boolean codecReceivedEos;
    private boolean inputStreamEnded;
    private boolean outputStreamEnded;
    private boolean waitingForKeys;
    protected DecoderCounters decoderCounters;

    private Format[] streamFormats;
    private GeckoHlsVideoRender.CodecMaxValues codecMaxValues;

//    public GeckoHlsVideoRender(Context context, MediaCodecSelector mediaCodecSelector) {
//        super(C.TRACK_TYPE_VIDEO, mediaCodecSelector, null, false);
//    }

    public int getAvailableInputBufferIndex() {
        for (int i = 0; i < inputBufferUsable.length; i++) {
            if (inputBufferUsable[i])
                return i;
        }
        return -1;
    }

    public int getUsedInputBufferIndex() {
        for (int i = 0; i < inputBufferUsable.length; i++) {
            if (!inputBufferUsable[i])
                return i;
        }
        return -1;
    }

    public void markInputBufferUsed(int index) {
        inputBufferUsable[index] = false;
    }

    public void markInputBufferAvailable(int index) {
        inputBufferUsable[index] = true;
    }

    public GeckoHlsVideoRender(Context context, MediaCodecSelector mediaCodecSelector) {
        super(C.TRACK_TYPE_VIDEO);
        this.mediaCodecSelector = (MediaCodecSelector) Assertions.checkNotNull(mediaCodecSelector);
        this.buffer = new DecoderInputBuffer(0);
        this.formatHolder = new FormatHolder();
        this.decodeOnlyPresentationTimestamps = new ArrayList();
        this.outputBufferInfo = new MediaCodec.BufferInfo();
        this.codecReconfigurationState = 0;
        this.codecReinitializationState = 0;

        inputBufferUsable = new boolean[4];
        Arrays.fill(inputBufferUsable, Boolean.TRUE);
        outputBufferUsable = new boolean[4];
        Arrays.fill(outputBufferUsable, Boolean.TRUE);
    }

    public final int supportsMixedMimeTypeAdaptation() throws ExoPlaybackException {
        return 4;
    }

    public final int supportsFormat(Format format) throws ExoPlaybackException {
        try {
            return this.supportsFormat(this.mediaCodecSelector, format);
        } catch (MediaCodecUtil.DecoderQueryException var3) {
            throw ExoPlaybackException.createForRenderer(var3, this.getIndex());
        }
    }

    protected MediaCodecInfo getDecoderInfo(MediaCodecSelector mediaCodecSelector, Format format, boolean requiresSecureDecoder) throws MediaCodecUtil.DecoderQueryException {
        return mediaCodecSelector.getDecoderInfo(format.sampleMimeType, requiresSecureDecoder, false);
    }

    protected void onStreamChanged(Format[] formats) throws ExoPlaybackException {
        this.streamFormats = formats;
        super.onStreamChanged(formats);
    }

//    @Override
    protected void configureCodec(MediaCodec codec, Format format, MediaCrypto crypto) {
        codecMaxValues = getCodecMaxValues(format, streamFormats);
        MediaFormat mediaFormat = getMediaFormat(format, codecMaxValues, false);
        codec.configure(mediaFormat, null, crypto, 0);
    }

    private static int getMaxInputSize(Format format) {
        if (format.maxInputSize != Format.NO_VALUE) {
            // The format defines an explicit maximum input size.
            return format.maxInputSize;
        }

        if (format.width == Format.NO_VALUE || format.height == Format.NO_VALUE) {
            // We can't infer a maximum input size without video dimensions.
            return Format.NO_VALUE;
        }

        // Attempt to infer a maximum input size from the format.
        int maxPixels;
        int minCompressionRatio;
        switch (format.sampleMimeType) {
            case MimeTypes.VIDEO_H263:
            case MimeTypes.VIDEO_MP4V:
                maxPixels = format.width * format.height;
                minCompressionRatio = 2;
                break;
            case MimeTypes.VIDEO_H264:
                if ("BRAVIA 4K 2015".equals(Util.MODEL)) {
                    // The Sony BRAVIA 4k TV has input buffers that are too small for the calculated 4k video
                    // maximum input size, so use the default value.
                    return Format.NO_VALUE;
                }
                // Round up width/height to an integer number of macroblocks.
                maxPixels = ((format.width + 15) / 16) * ((format.height + 15) / 16) * 16 * 16;
                minCompressionRatio = 2;
                break;
            case MimeTypes.VIDEO_VP8:
                // VPX does not specify a ratio so use the values from the platform's SoftVPX.cpp.
                maxPixels = format.width * format.height;
                minCompressionRatio = 2;
                break;
            case MimeTypes.VIDEO_H265:
            case MimeTypes.VIDEO_VP9:
                maxPixels = format.width * format.height;
                minCompressionRatio = 4;
                break;
            default:
                // Leave the default max input size.
                return Format.NO_VALUE;
        }
        // Estimate the maximum input size assuming three channel 4:2:0 subsampled input frames.
        return (maxPixels * 3) / (2 * minCompressionRatio);
    }

    private static final class CodecMaxValues {
        public final int width;
        public final int height;
        public final int inputSize;

        public CodecMaxValues(int width, int height, int inputSize) {
            this.width = width;
            this.height = height;
            this.inputSize = inputSize;
        }

    }

    private static int getRotationDegrees(Format format) {
        return format.rotationDegrees == Format.NO_VALUE ? 0 : format.rotationDegrees;
    }

    private static boolean areAdaptationCompatible(Format first, Format second) {
        return first.sampleMimeType.equals(second.sampleMimeType)
                && getRotationDegrees(first) == getRotationDegrees(second);
    }

    private static GeckoHlsVideoRender.CodecMaxValues getCodecMaxValues(Format format, Format[] streamFormats) {
        int maxWidth = format.width;
        int maxHeight = format.height;
        int maxInputSize = getMaxInputSize(format);
        for (Format streamFormat : streamFormats) {
            if (areAdaptationCompatible(format, streamFormat)) {
                maxWidth = Math.max(maxWidth, streamFormat.width);
                maxHeight = Math.max(maxHeight, streamFormat.height);
                maxInputSize = Math.max(maxInputSize, getMaxInputSize(streamFormat));
            }
        }
        return new GeckoHlsVideoRender.CodecMaxValues(maxWidth, maxHeight, maxInputSize);
    }

    public ByteBuffer[] getInputBuffers() {
        ByteBuffer[] temp = new ByteBuffer[4];
        for (int i = 0; i < temp.length; i++) {
            byte[] bytes = new byte[1024000];
            temp[i] = ByteBuffer.wrap(bytes);
        }
        return temp;
    }

    protected final void maybeInitCodec() throws ExoPlaybackException {
        if(this.shouldInitCodec()) {
            String mimeType = this.format.sampleMimeType;
            MediaCrypto mediaCrypto = null;
            boolean drmSessionRequiresSecureDecoder = false;

            MediaCodecInfo decoderInfo1 = null;

            try {
                decoderInfo1 = this.getDecoderInfo(this.mediaCodecSelector, this.format, drmSessionRequiresSecureDecoder);
                if(decoderInfo1 == null && drmSessionRequiresSecureDecoder) {
                    decoderInfo1 = this.getDecoderInfo(this.mediaCodecSelector, this.format, false);
                    if(decoderInfo1 != null) {
                        Log.w("MediaCodecRenderer", "Drm session requires secure decoder for " + mimeType + ", but " + "no secure decoder available. Trying to proceed with " + decoderInfo1.name + ".");
                    }
                }
            } catch (MediaCodecUtil.DecoderQueryException var11) {
                this.throwDecoderInitError(new MediaCodecRenderer.DecoderInitializationException(this.format, var11, drmSessionRequiresSecureDecoder, -49998));
            }

            if(decoderInfo1 == null) {
                this.throwDecoderInitError(new MediaCodecRenderer.DecoderInitializationException(this.format, (Throwable)null, drmSessionRequiresSecureDecoder, -49999));
            }

            String codecName = decoderInfo1.name;
            this.codecIsAdaptive = decoderInfo1.adaptive;
            this.codecNeedsDiscardToSpsWorkaround = codecNeedsDiscardToSpsWorkaround(codecName, this.format);
            this.codecNeedsFlushWorkaround = codecNeedsFlushWorkaround(codecName);
            this.codecNeedsAdaptationWorkaround = codecNeedsAdaptationWorkaround(codecName);
            this.codecNeedsEosPropagationWorkaround = codecNeedsEosPropagationWorkaround(codecName);
            this.codecNeedsEosFlushWorkaround = codecNeedsEosFlushWorkaround(codecName);
            this.codecNeedsMonoChannelCountWorkaround = codecNeedsMonoChannelCountWorkaround(codecName, this.format);

            try {
                long e = SystemClock.elapsedRealtime();
                TraceUtil.beginSection("createCodec:" + codecName);
                this.codec = MediaCodec.createByCodecName(codecName);
                TraceUtil.endSection();
                TraceUtil.beginSection("configureCodec");
                this.configureCodec(this.codec, this.format, mediaCrypto);
                TraceUtil.endSection();
                TraceUtil.beginSection("startCodec");
                this.codec.start();
                TraceUtil.endSection();
                long codecInitializedTimestamp = SystemClock.elapsedRealtime();
                this.onCodecInitialized(codecName, codecInitializedTimestamp, codecInitializedTimestamp - e);
                this.inputBuffers = getInputBuffers(); //this.codec.getInputBuffers();
                this.outputBuffers = getInputBuffers();//this.codec.getOutputBuffers();
            } catch (Exception var10) {
                this.throwDecoderInitError(new MediaCodecRenderer.DecoderInitializationException(this.format, var10, drmSessionRequiresSecureDecoder, codecName));
            }

            this.codecHotswapDeadlineMs = this.getState() == 2?SystemClock.elapsedRealtime() + 1000L:-9223372036854775807L;
            this.inputIndex = -1;
            this.outputIndex = -1;
            ++this.decoderCounters.decoderInitCount;
        }
    }

    private void throwDecoderInitError(MediaCodecRenderer.DecoderInitializationException e) throws ExoPlaybackException {
        throw ExoPlaybackException.createForRenderer(e, this.getIndex());
    }

    protected boolean shouldInitCodec() {
        return this.codec == null && this.format != null;
    }

    protected void onPositionReset(long positionUs, boolean joining) throws ExoPlaybackException {
        this.inputStreamEnded = false;
        this.outputStreamEnded = false;
        if(this.codec != null) {
            this.flushCodec();
        }

    }

    protected void onDisabled() {
        this.format = null;

        try {
            this.releaseCodec();
        } finally {
        }

    }

    protected void releaseCodec() {
        if(this.codec != null) {
            this.codecHotswapDeadlineMs = -9223372036854775807L;
            this.inputIndex = -1;
            this.outputIndex = -1;
            this.waitingForKeys = false;
            this.decodeOnlyPresentationTimestamps.clear();
            this.inputBuffers = null;
            this.outputBuffers = null;
            this.codecReconfigured = false;
            this.codecReceivedBuffers = false;
            this.codecIsAdaptive = false;
            this.codecNeedsDiscardToSpsWorkaround = false;
            this.codecNeedsFlushWorkaround = false;
            this.codecNeedsAdaptationWorkaround = false;
            this.codecNeedsEosPropagationWorkaround = false;
            this.codecNeedsEosFlushWorkaround = false;
            this.codecNeedsMonoChannelCountWorkaround = false;
            this.codecNeedsAdaptationWorkaroundBuffer = false;
            this.shouldSkipAdaptationWorkaroundOutputBuffer = false;
            this.codecReceivedEos = false;
            this.codecReconfigurationState = 0;
            this.codecReinitializationState = 0;
            ++this.decoderCounters.decoderReleaseCount;

            try {
                this.codec.stop();
            } finally {
                try {
                    this.codec.release();
                } finally {
                    this.codec = null;
                }
            }
        }

    }

    protected void onStarted() {
//        super.onStarted();
//        this.droppedFrames = 0;
//        this.droppedFrameAccumulationStartTimeMs = SystemClock.elapsedRealtime();
    }

    protected void onStopped() {
//        this.joiningDeadlineMs = -9223372036854775807L;
//        this.maybeNotifyDroppedFrames();
//        super.onStopped();
    }

    public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
        if(!this.outputStreamEnded) {
            if(this.format == null) {
                this.readFormat();
            }

            this.maybeInitCodec();
            if(this.codec != null) {
                TraceUtil.beginSection("drainAndFeed");
                while (drainOutputBuffer(positionUs, elapsedRealtimeUs)) {}
                while (feedInputBuffer()) {}
                TraceUtil.endSection();
            } else if(this.format != null) {
                this.skipToKeyframeBefore(positionUs);
            }

            this.decoderCounters.ensureUpdated();
        }
    }

    private void readFormat() throws ExoPlaybackException {
        int result = this.readSource(this.formatHolder, (DecoderInputBuffer)null);
        if(result == -5) {
            this.onInputFormatChanged(this.formatHolder.format);
        }

    }

    protected void flushCodec() throws ExoPlaybackException {
        this.codecHotswapDeadlineMs = -9223372036854775807L;
        this.inputIndex = -1;
        this.outputIndex = -1;
        this.waitingForKeys = false;
        this.decodeOnlyPresentationTimestamps.clear();
        this.codecNeedsAdaptationWorkaroundBuffer = false;
        this.shouldSkipAdaptationWorkaroundOutputBuffer = false;
        if(this.codecNeedsFlushWorkaround || this.codecNeedsEosFlushWorkaround && this.codecReceivedEos) {
            this.releaseCodec();
            this.maybeInitCodec();
        } else if(this.codecReinitializationState != 0) {
            this.releaseCodec();
            this.maybeInitCodec();
        } else {
            this.codec.flush();
            this.codecReceivedBuffers = false;
        }

        if(this.codecReconfigured && this.format != null) {
            this.codecReconfigurationState = 1;
        }

    }


    private boolean feedInputBuffer() throws ExoPlaybackException {
        this.inputIndex = getAvailableInputBufferIndex();
        if (this.inputIndex < 0) {
            return false;
        }
        this.buffer.data = inputBuffers[this.inputIndex];
        this.buffer.clear();

//        int adaptiveReconfigurationBytes = this.buffer.data.position();
        int result = this.readSource(this.formatHolder, this.buffer);

        if(this.codec != null && this.codecReinitializationState != 2 && !this.inputStreamEnded) {
            if(this.codecReconfigurationState == 1) {
                for(int bufferEncrypted = 0; bufferEncrypted < this.format.initializationData.size(); ++bufferEncrypted) {
                    byte[] e = (byte[])this.format.initializationData.get(bufferEncrypted);
                    this.buffer.data.put(e);
                }
                this.codecReconfigurationState = 2;
            }

            if(result == -3) {
                return false;
            } else if(result == -5) {
                if(this.codecReconfigurationState == 2) {
                    this.buffer.clear();
                    this.codecReconfigurationState = 1;
                }

                this.onInputFormatChanged(this.formatHolder.format);
                return true;
            } else if(this.buffer.isEndOfStream()) {
                if(this.codecReconfigurationState == 2) {
                    this.buffer.clear();
                    this.codecReconfigurationState = 1;
                }

                this.inputStreamEnded = true;
                if(!this.codecReceivedBuffers) {
                    this.processEndOfStream();
                    return false;
                } else {
                    try {
                        if(!this.codecNeedsEosPropagationWorkaround) {
                            this.codecReceivedEos = true;
//                            this.codec.queueInputBuffer(this.inputIndex, 0, 0, 0L, 4);
                            this.inputIndex = -1;
                        }

                        return false;
                    } catch (MediaCodec.CryptoException var7) {
                        throw ExoPlaybackException.createForRenderer(var7, this.getIndex());
                    }
                }
            } else {
                if(this.codecNeedsDiscardToSpsWorkaround) {
                    NalUnitUtil.discardToSps(this.buffer.data);
                    if(this.buffer.data.position() == 0) {
                        return true;
                    }
                    this.codecNeedsDiscardToSpsWorkaround = false;
                }

                try {
                    long var10 = this.buffer.timeUs;
                    if(this.buffer.isDecodeOnly()) {
                        this.decodeOnlyPresentationTimestamps.add(Long.valueOf(var10));
                    }

                    this.buffer.flip();
                    this.onQueueInputBuffer(this.buffer);
                    markInputBufferUsed(this.inputIndex);
//                        this.codec.queueInputBuffer(this.inputIndex, 0, this.buffer.data.limit(), var10, 0);

                    this.inputIndex = -1;
                    this.codecReceivedBuffers = true;
                    this.codecReconfigurationState = 0;
                    ++this.decoderCounters.inputBufferCount;
                    return true;
                } catch (MediaCodec.CryptoException var8) {
                    throw ExoPlaybackException.createForRenderer(var8, this.getIndex());
                }
            }
        } else {
            return false;
        }
    }

    protected void onCodecInitialized(String name, long initializedTimestampMs, long initializationDurationMs) {
    }

    protected void onInputFormatChanged(Format newFormat) throws ExoPlaybackException {
        Format oldFormat = this.format;
        this.format = newFormat;
        boolean drmInitDataChanged = !Util.areEqual(this.format.drmInitData, oldFormat == null?null:oldFormat.drmInitData);
        if(drmInitDataChanged) {
            if(this.format.drmInitData != null) {
                throw ExoPlaybackException.createForRenderer(new IllegalStateException("Media requires a DrmSessionManager"), this.getIndex());
            } else {
            }
        }

        if(false) {
            this.codecReconfigured = true;
            this.codecReconfigurationState = 1;
            this.codecNeedsAdaptationWorkaroundBuffer = this.codecNeedsAdaptationWorkaround && this.format.width == oldFormat.width && this.format.height == oldFormat.height;
        } else if(this.codecReceivedBuffers) {
            this.codecReinitializationState = 1;
        } else {
            this.releaseCodec();
            this.maybeInitCodec();
        }

    }

    protected void onOutputFormatChanged(MediaCodec codec, MediaFormat outputFormat) {
    }

    protected void onOutputStreamEnded() {
    }

    protected void onQueueInputBuffer(DecoderInputBuffer buffer) {
    }

    public boolean isEnded() {
        return this.outputStreamEnded;
    }

    public boolean isReady() {
        return this.format != null && !this.waitingForKeys && (this.isSourceReady() || this.outputIndex >= 0 || this.codecHotswapDeadlineMs != -9223372036854775807L && SystemClock.elapsedRealtime() < this.codecHotswapDeadlineMs);
    }

    private boolean drainOutputBuffer(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
        int index = getUsedInputBufferIndex();
        if (index < 0) {
            return false;
        } else {
            inputBuffers[index].clear();
            markInputBufferAvailable(index);
        }

        return true;
    }


    private void processEndOfStream() throws ExoPlaybackException {
        if(this.codecReinitializationState == 2) {
            this.releaseCodec();
            this.maybeInitCodec();
        } else {
            this.outputStreamEnded = true;
            this.onOutputStreamEnded();
        }

    }

    private static boolean codecNeedsFlushWorkaround(String name) {
        return Util.SDK_INT < 18 || Util.SDK_INT == 18 && ("OMX.SEC.avc.dec".equals(name) || "OMX.SEC.avc.dec.secure".equals(name)) || Util.SDK_INT == 19 && Util.MODEL.startsWith("SM-G800") && ("OMX.Exynos.avc.dec".equals(name) || "OMX.Exynos.avc.dec.secure".equals(name));
    }

    private static boolean codecNeedsAdaptationWorkaround(String name) {
        return Util.SDK_INT < 24 && ("OMX.Nvidia.h264.decode".equals(name) || "OMX.Nvidia.h264.decode.secure".equals(name)) && ("flounder".equals(Util.DEVICE) || "flounder_lte".equals(Util.DEVICE) || "grouper".equals(Util.DEVICE) || "tilapia".equals(Util.DEVICE));
    }

    private static boolean codecNeedsDiscardToSpsWorkaround(String name, Format format) {
        return Util.SDK_INT < 21 && format.initializationData.isEmpty() && "OMX.MTK.VIDEO.DECODER.AVC".equals(name);
    }

    private static boolean codecNeedsEosPropagationWorkaround(String name) {
        return Util.SDK_INT <= 17 && ("OMX.rk.video_decoder.avc".equals(name) || "OMX.allwinner.video.decoder.avc".equals(name));
    }

    private static boolean codecNeedsEosFlushWorkaround(String name) {
        return Util.SDK_INT <= 23 && "OMX.google.vorbis.decoder".equals(name) || Util.SDK_INT <= 19 && "hb2000".equals(Util.DEVICE) && ("OMX.amlogic.avc.decoder.awesome".equals(name) || "OMX.amlogic.avc.decoder.awesome.secure".equals(name));
    }

    private static boolean codecNeedsMonoChannelCountWorkaround(String name, Format format) {
        return Util.SDK_INT <= 18 && format.channelCount == 1 && "OMX.MTK.AUDIO.DECODER.MP3".equals(name);
    }

    public static class DecoderInitializationException extends Exception {
        private static final int CUSTOM_ERROR_CODE_BASE = -50000;
        private static final int NO_SUITABLE_DECODER_ERROR = -49999;
        private static final int DECODER_QUERY_ERROR = -49998;
        public final String mimeType;
        public final boolean secureDecoderRequired;
        public final String decoderName;
        public final String diagnosticInfo;

        public DecoderInitializationException(Format format, Throwable cause, boolean secureDecoderRequired, int errorCode) {
            super("Decoder init failed: [" + errorCode + "], " + format, cause);
            this.mimeType = format.sampleMimeType;
            this.secureDecoderRequired = secureDecoderRequired;
            this.decoderName = null;
            this.diagnosticInfo = buildCustomDiagnosticInfo(errorCode);
        }

        public DecoderInitializationException(Format format, Throwable cause, boolean secureDecoderRequired, String decoderName) {
            super("Decoder init failed: " + decoderName + ", " + format, cause);
            this.mimeType = format.sampleMimeType;
            this.secureDecoderRequired = secureDecoderRequired;
            this.decoderName = decoderName;
            this.diagnosticInfo = Util.SDK_INT >= 21?getDiagnosticInfoV21(cause):null;
        }

        @TargetApi(21)
        private static String getDiagnosticInfoV21(Throwable cause) {
            return cause instanceof MediaCodec.CodecException ?((MediaCodec.CodecException)cause).getDiagnosticInfo():null;
        }

        private static String buildCustomDiagnosticInfo(int errorCode) {
            String sign = errorCode < 0?"neg_":"";
            return "com.google.android.exoplayer.MediaCodecTrackRenderer_" + sign + Math.abs(errorCode);
        }
    }

    private static MediaFormat getMediaFormat(Format format, GeckoHlsVideoRender.CodecMaxValues codecMaxValues,
                                              boolean deviceNeedsAutoFrcWorkaround) {
        MediaFormat frameworkMediaFormat = format.getFrameworkMediaFormatV16();
        // Set the maximum adaptive video dimensions.
        frameworkMediaFormat.setInteger(MediaFormat.KEY_MAX_WIDTH, codecMaxValues.width);
        frameworkMediaFormat.setInteger(MediaFormat.KEY_MAX_HEIGHT, codecMaxValues.height);
        // Set the maximum input size.
        if (codecMaxValues.inputSize != Format.NO_VALUE) {
            frameworkMediaFormat.setInteger(MediaFormat.KEY_MAX_INPUT_SIZE, codecMaxValues.inputSize);
        }
        // Set FRC workaround.
        if (deviceNeedsAutoFrcWorkaround) {
            frameworkMediaFormat.setInteger("auto-frc", 0);
        }
        return frameworkMediaFormat;
    }

    protected int supportsFormat(MediaCodecSelector mediaCodecSelector, Format format)
            throws MediaCodecUtil.DecoderQueryException {
        String mimeType = format.sampleMimeType;
        if (!MimeTypes.isVideo(mimeType)) {
            return FORMAT_UNSUPPORTED_TYPE;
        }
        boolean requiresSecureDecryption = false;
        DrmInitData drmInitData = format.drmInitData;
        if (drmInitData != null) {
            for (int i = 0; i < drmInitData.schemeDataCount; i++) {
                requiresSecureDecryption |= drmInitData.get(i).requiresSecureDecryption;
            }
        }
        MediaCodecInfo decoderInfo = mediaCodecSelector.getDecoderInfo(mimeType,
                requiresSecureDecryption, false);
        if (decoderInfo == null) {
            return FORMAT_UNSUPPORTED_SUBTYPE;
        }

        boolean decoderCapable = decoderInfo.isCodecSupported(format.codecs);
        if (decoderCapable && format.width > 0 && format.height > 0) {
            if (Util.SDK_INT >= 21) {
                if (format.frameRate > 0) {
                    decoderCapable = decoderInfo.isVideoSizeAndRateSupportedV21(format.width, format.height,
                            format.frameRate);
                } else {
                    decoderCapable = decoderInfo.isVideoSizeSupportedV21(format.width, format.height);
                }
            } else {
                decoderCapable = format.width * format.height <= MediaCodecUtil.maxH264DecodableFrameSize();
                if (!decoderCapable) {
                    Log.d(TAG, "FalseCheck [legacyFrameSize, " + format.width + "x" + format.height + "] ["
                            + Util.DEVICE_DEBUG_INFO + "]");
                }
            }
        }

        int adaptiveSupport = decoderInfo.adaptive ? ADAPTIVE_SEAMLESS : ADAPTIVE_NOT_SEAMLESS;
        int formatSupport = decoderCapable ? FORMAT_HANDLED : FORMAT_EXCEEDS_CAPABILITIES;
        return adaptiveSupport | formatSupport;
    }

    @Override
    protected void onEnabled(boolean joining) throws ExoPlaybackException {
        super.onEnabled(joining);
        this.decoderCounters = new DecoderCounters();
    }
}
