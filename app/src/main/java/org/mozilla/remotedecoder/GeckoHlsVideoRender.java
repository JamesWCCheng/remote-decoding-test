package org.mozilla.remotedecoder;

import android.annotation.TargetApi;
import android.content.Context;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.os.SystemClock;
import android.util.Log;

import com.google.android.exoplayer2.BaseRenderer;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.FormatHolder;
import com.google.android.exoplayer2.decoder.DecoderInputBuffer;
import com.google.android.exoplayer2.drm.DrmInitData;
import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.NalUnitUtil;
import com.google.android.exoplayer2.util.ParsableBitArray;
import com.google.android.exoplayer2.util.TraceUtil;
import com.google.android.exoplayer2.util.Util;

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

    private final MediaCodecSelector mediaCodecSelector;
    private final DecoderInputBuffer buffer;
    private final FormatHolder formatHolder;
    private Format format;

    private boolean codecIsAdaptive;
    private boolean codecNeedsDiscardToSpsWorkaround;

    private boolean initialized = false;
    private ByteBuffer[] inputBuffers;
    private boolean[] inputBufferUsable;

    private long codecHotswapDeadlineMs;
    private int inputIndex;
    private int codecReinitializationState;
    private boolean codecReceivedBuffers;
    private boolean inputStreamEnded;
    private boolean outputStreamEnded;

    private Format[] streamFormats;
    private GeckoHlsVideoRender.CodecMaxValues codecMaxValues;

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
        this.codecReinitializationState = 0;

        inputBufferUsable = new boolean[4];
        Arrays.fill(inputBufferUsable, Boolean.TRUE);
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
            byte[] bytes = new byte[codecMaxValues.inputSize];
            temp[i] = ByteBuffer.wrap(bytes);
        }
        return temp;
    }

    protected final void maybeInitRenderer() throws ExoPlaybackException {
        if(this.shouldInitRenderer()) {
            MediaCodecInfo decoderInfo1 = null;
            try {
                decoderInfo1 = this.getDecoderInfo(this.mediaCodecSelector, this.format, false);
            } catch (MediaCodecUtil.DecoderQueryException var11) {
                this.throwDecoderInitError(new MediaCodecRenderer.DecoderInitializationException(this.format, var11, false, -49998));
            }

            if(decoderInfo1 == null) {
                this.throwDecoderInitError(new MediaCodecRenderer.DecoderInitializationException(this.format, (Throwable)null, false, -49999));
            }

            String codecName = decoderInfo1.name;
            this.codecIsAdaptive = decoderInfo1.adaptive;
            this.codecNeedsDiscardToSpsWorkaround = codecNeedsDiscardToSpsWorkaround(codecName, this.format);

            try {
                long e = SystemClock.elapsedRealtime();
                TraceUtil.beginSection("initRenderer:" + codecName);
                codecMaxValues = getCodecMaxValues(this.format, this.streamFormats);
                this.initialized = true;
                this.inputBuffers = getInputBuffers();
                TraceUtil.endSection();
            } catch (Exception var10) {
                this.throwDecoderInitError(new MediaCodecRenderer.DecoderInitializationException(this.format, var10, false, codecName));
            }

            this.codecHotswapDeadlineMs = this.getState() == 2?SystemClock.elapsedRealtime() + 1000L:-9223372036854775807L;
            this.inputIndex = -1;
        }
    }

    private void throwDecoderInitError(MediaCodecRenderer.DecoderInitializationException e) throws ExoPlaybackException {
        throw ExoPlaybackException.createForRenderer(e, this.getIndex());
    }

    protected boolean shouldInitRenderer() {
        return !this.initialized && this.format != null;
    }

    protected void onPositionReset(long positionUs, boolean joining) throws ExoPlaybackException {
        this.inputStreamEnded = false;
        this.outputStreamEnded = false;
        if (this.initialized) {
            this.flushRenderer();
        }
    }

    protected void onDisabled() {
        this.format = null;

        try {
            this.releaseRenderer();
        } finally {
        }

    }

    protected void releaseRenderer() {
        if (this.initialized) {
            this.codecHotswapDeadlineMs = -9223372036854775807L;
            this.inputIndex = -1;
            this.inputBuffers = null;
            this.codecReceivedBuffers = false;
            this.codecIsAdaptive = false;
            this.codecNeedsDiscardToSpsWorkaround = false;
            this.codecReinitializationState = 0;

            this.initialized = false;
        }

    }

    protected void onStarted() {
    }

    protected void onStopped() {
    }

    public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
        if(!this.outputStreamEnded) {
            if(this.format == null) {
                this.readFormat();
            }

            this.maybeInitRenderer();
            if(this.initialized) {
                TraceUtil.beginSection("drainAndFeed");
                while (drainOutputBuffer(positionUs, elapsedRealtimeUs)) {}
                while (feedInputBuffer()) {}
                TraceUtil.endSection();
            } else if(this.format != null) {
                this.skipToKeyframeBefore(positionUs);
            }
        }
    }

    private void readFormat() throws ExoPlaybackException {
        int result = this.readSource(this.formatHolder, (DecoderInputBuffer)null);
        if (result == -5) {
            this.onInputFormatChanged(this.formatHolder.format);
        }
    }

    protected void flushRenderer() throws ExoPlaybackException {
        this.codecHotswapDeadlineMs = -9223372036854775807L;
        this.inputIndex = -1;
        if(this.codecReinitializationState != 0) {
            this.releaseRenderer();
            this.maybeInitRenderer();
        } else {
            this.codecReceivedBuffers = false;
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

        if(this.initialized && this.codecReinitializationState != 2 && !this.inputStreamEnded) {
            if(result == -3) {
                return false;
            } else if(result == -5) {
                this.onInputFormatChanged(this.formatHolder.format);
                return true;
            } else if(this.buffer.isEndOfStream()) {
                this.inputStreamEnded = true;
                if(!this.codecReceivedBuffers) {
                    this.processEndOfStream();
                    return false;
                } else {
                    return false;
                }
            } else {
                if(this.codecNeedsDiscardToSpsWorkaround) {
                    NalUnitUtil.discardToSps(this.buffer.data);
                    if(this.buffer.data.position() == 0) {
                        return true;
                    }
                    this.codecNeedsDiscardToSpsWorkaround = false;
                }

                this.buffer.flip();
                this.onQueueInputBuffer(this.buffer);
                markInputBufferUsed(this.inputIndex);

                this.inputIndex = -1;
                this.codecReceivedBuffers = true;
                return true;
            }
        } else {
            return false;
        }
    }

    protected void onInputFormatChanged(Format newFormat) throws ExoPlaybackException {
        Format oldFormat = this.format;
        this.format = newFormat;

        if(this.codecReceivedBuffers) {
            this.codecReinitializationState = 1;
        } else {
            this.releaseRenderer();
            this.maybeInitRenderer();
        }

    }

    protected void onOutputStreamEnded() {
    }

    protected void onQueueInputBuffer(DecoderInputBuffer buffer) {
    }

    public boolean isEnded() {
        return this.outputStreamEnded;
    }

    public boolean isReady() {
        return this.format != null && (this.isSourceReady() || this.codecHotswapDeadlineMs != -9223372036854775807L && SystemClock.elapsedRealtime() < this.codecHotswapDeadlineMs);
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
            this.releaseRenderer();
            this.maybeInitRenderer();
        } else {
            this.outputStreamEnded = true;
            this.onOutputStreamEnded();
        }

    }

    private static boolean codecNeedsDiscardToSpsWorkaround(String name, Format format) {
        return Util.SDK_INT < 21 && format.initializationData.isEmpty() && "OMX.MTK.VIDEO.DECODER.AVC".equals(name);
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
    }
}
