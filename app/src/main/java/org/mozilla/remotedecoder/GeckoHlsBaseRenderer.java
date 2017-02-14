package org.mozilla.remotedecoder;

import android.os.SystemClock;
import android.util.Log;

import com.google.android.exoplayer2.BaseRenderer;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.FormatHolder;
import com.google.android.exoplayer2.decoder.DecoderCounters;
import com.google.android.exoplayer2.decoder.DecoderInputBuffer;
import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecRenderer;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;
import com.google.android.exoplayer2.util.Assertions;
import com.google.android.exoplayer2.util.NalUnitUtil;
import com.google.android.exoplayer2.util.TraceUtil;
import com.google.android.exoplayer2.util.Util;

import java.nio.ByteBuffer;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

/**
 * Created by james on 2/13/17.
 */

// Put Audio and Video common code in this base class
public abstract class GeckoHlsBaseRenderer extends BaseRenderer {

    private static final String LOGTAG = "GeckoHlsBaseRenderer";
    // Code logic to maintain our own buffer.
    private boolean initialized = false;

    private ByteBuffer[] inputBuffers;
    private boolean[] inputBufferUsable;

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

    protected abstract ByteBuffer[] getInputBuffers();

    public void markInputBufferUsed(int index) {
        inputBufferUsable[index] = false;
    }

    public void markInputBufferAvailable(int index) {
        inputBufferUsable[index] = true;
    }
    ///////////////////////////////////////////////



    private final MediaCodecSelector mediaCodecSelector;
    private final DecoderInputBuffer buffer;
    private final FormatHolder formatHolder;
    private final List<Long> decodeOnlyPresentationTimestamps;

    private Format format;
    private boolean codecIsAdaptive;
    private boolean codecNeedsDiscardToSpsWorkaround;
    private boolean codecNeedsFlushWorkaround;
    private boolean codecNeedsAdaptationWorkaround;
    private boolean codecNeedsEosPropagationWorkaround;
    private boolean codecNeedsEosFlushWorkaround;
    private boolean codecNeedsAdaptationWorkaroundBuffer;
    private boolean shouldSkipAdaptationWorkaroundOutputBuffer;
    private long codecHotswapDeadlineMs;
    private int inputIndex;
    private int codecReinitializationState;
    private boolean codecReceivedBuffers;
    private boolean inputStreamEnded;
    private boolean outputStreamEnded;

    protected DecoderCounters decoderCounters;

    private static boolean codecNeedsDiscardToSpsWorkaround(String name, Format format) {
        return Util.SDK_INT < 21 && format.initializationData.isEmpty() && "OMX.MTK.VIDEO.DECODER.AVC".equals(name);
    }

    public GeckoHlsBaseRenderer(int trackType, MediaCodecSelector mediaCodecSelector) {
        super(trackType);
        this.mediaCodecSelector = (MediaCodecSelector) Assertions.checkNotNull(mediaCodecSelector);
        this.buffer = new DecoderInputBuffer(0);
        this.formatHolder = new FormatHolder();
        this.decodeOnlyPresentationTimestamps = new ArrayList();
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
    /**
     * Returns the extent to which the renderer is capable of supporting a given format.
     *
     * @param mediaCodecSelector The decoder selector.
     * @param format The format.
     * @return The extent to which the renderer is capable of supporting the given format. See
     *     {@link #supportsFormat(Format)} for more detail.
     * @throws MediaCodecUtil.DecoderQueryException If there was an error querying decoders.
     */
    protected abstract int supportsFormat(MediaCodecSelector mediaCodecSelector, Format format)
            throws MediaCodecUtil.DecoderQueryException;

    protected MediaCodecInfo getDecoderInfo(MediaCodecSelector mediaCodecSelector, Format format, boolean requiresSecureDecoder) throws MediaCodecUtil.DecoderQueryException {
        return mediaCodecSelector.getDecoderInfo(format.sampleMimeType, requiresSecureDecoder, false);
    }

    protected abstract void configRenderer(Format format);

    protected void maybeInitRenderer() throws ExoPlaybackException {
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
                long codecInitializingTimestamp = SystemClock.elapsedRealtime();
                TraceUtil.beginSection("initRenderer:" + codecName);
                configRenderer(this.format);
                long codecInitializedTimestamp = SystemClock.elapsedRealtime();
                onRendererInitialized(codecName, codecInitializedTimestamp,
                        codecInitializedTimestamp - codecInitializingTimestamp);
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
        Log.d(LOGTAG, this + ": positionUs = " + positionUs + ", elapsedRealtimeUs = "+ elapsedRealtimeUs);
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

    protected abstract void onRendererInitialized(String name, long initializedTimestampMs,
                                      long initializationDurationMs);
}
