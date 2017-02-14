package org.mozilla.remotedecoder;

import android.annotation.TargetApi;
import android.media.MediaCodec;
import android.media.MediaFormat;
import android.media.PlaybackParams;
import android.os.Handler;

import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.audio.AudioCapabilities;
import com.google.android.exoplayer2.audio.AudioRendererEventListener;
import com.google.android.exoplayer2.audio.AudioTrack;
import com.google.android.exoplayer2.mediacodec.MediaCodecInfo;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;
import com.google.android.exoplayer2.util.MediaClock;
import com.google.android.exoplayer2.util.MimeTypes;
import com.google.android.exoplayer2.util.Util;

import java.nio.ByteBuffer;

/**
 * Created by kilikkuo on 2/8/17.
 */

@TargetApi(16)
public class GeckoHlsAudioRender extends GeckoHlsBaseRenderer implements AudioTrack.Listener, MediaClock {
    private static final String TAG = "GeckoHlsAudioRender";

    // Copy from MediaCodecAudioRenderer
    private final AudioRendererEventListener.EventDispatcher eventDispatcher;
    private final AudioTrack audioTrack;
    private boolean passthroughEnabled;
    private MediaFormat passthroughMediaFormat;
    private int pcmEncoding;
    private int audioSessionId;
    private long currentPositionUs;
    private boolean allowPositionDiscontinuity;
    //////////////////////////////////////////////

    public GeckoHlsAudioRender(MediaCodecSelector mediaCodecSelector) {
        super(C.TRACK_TYPE_AUDIO, mediaCodecSelector);
        this.audioTrack = new AudioTrack((AudioCapabilities)null, this);
        this.eventDispatcher
                = new AudioRendererEventListener.EventDispatcher((Handler)null, (AudioRendererEventListener)null);
    }

    // Copy from
    protected int supportsFormat(MediaCodecSelector mediaCodecSelector, Format format) throws MediaCodecUtil.DecoderQueryException {
        String mimeType = format.sampleMimeType;
        if(!MimeTypes.isAudio(mimeType)) {
            return 0;
        } else if(this.allowPassthrough(mimeType) && mediaCodecSelector.getPassthroughDecoderInfo() != null) {
            return 7;
        } else {
            MediaCodecInfo decoderInfo = mediaCodecSelector.getDecoderInfo(mimeType, false, false);
            if(decoderInfo == null) {
                return 1;
            } else {
                boolean decoderCapable = Util.SDK_INT < 21 || (format.sampleRate == -1 || decoderInfo.isAudioSampleRateSupportedV21(format.sampleRate)) && (format.channelCount == -1 || decoderInfo.isAudioChannelCountSupportedV21(format.channelCount));
                int formatSupport = decoderCapable?3:2;
                return 4 | formatSupport;
            }
        }
    }
    protected MediaCodecInfo getDecoderInfo(MediaCodecSelector mediaCodecSelector, Format format, boolean requiresSecureDecoder) throws MediaCodecUtil.DecoderQueryException {
        if(this.allowPassthrough(format.sampleMimeType)) {
            MediaCodecInfo passthroughDecoderInfo = mediaCodecSelector.getPassthroughDecoderInfo();
            if(passthroughDecoderInfo != null) {
                this.passthroughEnabled = true;
                return passthroughDecoderInfo;
            }
        }

        this.passthroughEnabled = false;
        return mediaCodecSelector.getDecoderInfo(format.sampleMimeType, requiresSecureDecoder, false);
    }

    protected boolean allowPassthrough(String mimeType) {
        return this.audioTrack.isPassthroughSupported(mimeType);
    }

    public MediaClock getMediaClock() {
        return this;
    }

    @Override
    protected void onRendererInitialized(String name, long initializedTimestampMs, long initializationDurationMs) {
        this.eventDispatcher.decoderInitialized(name, initializedTimestampMs, initializationDurationMs);
    }

    protected void onInputFormatChanged(Format newFormat) throws ExoPlaybackException {
        super.onInputFormatChanged(newFormat);
    }

    protected void onOutputFormatChanged(MediaCodec codec, MediaFormat outputFormat) {
        boolean passthrough = this.passthroughMediaFormat != null;
        String mimeType = passthrough?this.passthroughMediaFormat.getString("mime"):"audio/raw";
        MediaFormat format = passthrough?this.passthroughMediaFormat:outputFormat;
        int channelCount = format.getInteger("channel-count");
        int sampleRate = format.getInteger("sample-rate");
        this.audioTrack.configure(mimeType, channelCount, sampleRate, this.pcmEncoding, 0);
    }

    protected void onAudioSessionId(int audioSessionId) {
    }

    protected void onEnabled(boolean joining) throws ExoPlaybackException {
        super.onEnabled(joining);
        this.eventDispatcher.enabled(this.decoderCounters);
    }

    protected void onPositionReset(long positionUs, boolean joining) throws ExoPlaybackException {
        super.onPositionReset(positionUs, joining);
        this.audioTrack.reset();
        this.currentPositionUs = positionUs;
        this.allowPositionDiscontinuity = true;
    }

    protected void onStarted() {
        super.onStarted();
        this.audioTrack.play();
    }

    protected void onStopped() {
        this.audioTrack.pause();
        super.onStopped();
    }

    protected void onDisabled() {
        this.audioSessionId = 0;

        try {
            this.audioTrack.release();
        } finally {
            try {
                super.onDisabled();
            } finally {
                this.decoderCounters.ensureUpdated();
                this.eventDispatcher.disabled(this.decoderCounters);
            }
        }

    }

    public boolean isEnded() {
        return super.isEnded() && !this.audioTrack.hasPendingData();
    }

    public boolean isReady() {
        return this.audioTrack.hasPendingData() || super.isReady();
    }

    public long getPositionUs() {
        long newCurrentPositionUs = this.audioTrack.getCurrentPositionUs(this.isEnded());
        if(newCurrentPositionUs != -9223372036854775808L) {
            this.currentPositionUs = this.allowPositionDiscontinuity?newCurrentPositionUs:Math.max(this.currentPositionUs, newCurrentPositionUs);
            this.allowPositionDiscontinuity = false;
        }

        return this.currentPositionUs;
    }

    protected boolean processOutputBuffer(long positionUs, long elapsedRealtimeUs, MediaCodec codec, ByteBuffer buffer, int bufferIndex, int bufferFlags, long bufferPresentationTimeUs, boolean shouldSkip) throws ExoPlaybackException {
        if(this.passthroughEnabled && (bufferFlags & 2) != 0) {
            codec.releaseOutputBuffer(bufferIndex, false);
            return true;
        } else if(shouldSkip) {
            codec.releaseOutputBuffer(bufferIndex, false);
            ++this.decoderCounters.skippedOutputBufferCount;
            this.audioTrack.handleDiscontinuity();
            return true;
        } else {
            if(!this.audioTrack.isInitialized()) {
                try {
                    if(this.audioSessionId == 0) {
                        this.audioSessionId = this.audioTrack.initialize(0);
                        this.eventDispatcher.audioSessionId(this.audioSessionId);
                        this.onAudioSessionId(this.audioSessionId);
                    } else {
                        this.audioTrack.initialize(this.audioSessionId);
                    }
                } catch (AudioTrack.InitializationException var15) {
                    throw ExoPlaybackException.createForRenderer(var15, this.getIndex());
                }

                if(this.getState() == 2) {
                    this.audioTrack.play();
                }
            }

            int handleBufferResult;
            try {
                handleBufferResult = this.audioTrack.handleBuffer(buffer, bufferPresentationTimeUs);
            } catch (AudioTrack.WriteException var14) {
                throw ExoPlaybackException.createForRenderer(var14, this.getIndex());
            }

            if((handleBufferResult & 1) != 0) {
                this.handleAudioTrackDiscontinuity();
                this.allowPositionDiscontinuity = true;
            }

            if((handleBufferResult & 2) != 0) {
                codec.releaseOutputBuffer(bufferIndex, false);
                ++this.decoderCounters.renderedOutputBufferCount;
                return true;
            } else {
                return false;
            }
        }
    }

    protected void onOutputStreamEnded() {
        this.audioTrack.handleEndOfStream();
    }

    protected void handleAudioTrackDiscontinuity() {
    }

    public void handleMessage(int messageType, Object message) throws ExoPlaybackException {
        switch(messageType) {
            case 2:
                this.audioTrack.setVolume(((Float)message).floatValue());
                break;
            case 3:
                this.audioTrack.setPlaybackParams((PlaybackParams)message);
                break;
            case 4:
                int streamType = ((Integer)message).intValue();
                if(this.audioTrack.setStreamType(streamType)) {
                    this.audioSessionId = 0;
                }
                break;
            default:
                super.handleMessage(messageType, message);
        }

    }

    @Override
    protected void configRenderer(Format format) {
        if (passthroughEnabled) {
            // Override the MIME type used to configure the codec if we are using a passthrough decoder.
            passthroughMediaFormat = format.getFrameworkMediaFormatV16();
            passthroughMediaFormat.setString(MediaFormat.KEY_MIME, MimeTypes.AUDIO_RAW);
            passthroughMediaFormat.setString(MediaFormat.KEY_MIME, format.sampleMimeType);
        } else {
            passthroughMediaFormat = null;
        }
    }

    @Override
    protected ByteBuffer[] getInputBuffers() {
        ByteBuffer[] temp = new ByteBuffer[4];
        for (int i = 0; i < temp.length; i++) {
            // FIXME: Need an appropriate buffer size.
            byte[] bytes = new byte[22048];
            temp[i] = ByteBuffer.wrap(bytes);
        }
        return temp;
    }

    // override com.google.android.exoplayer2.audio.AudioTrack.Listener
    @Override
    public void onUnderrun(int bufferSize, long bufferSizeMs, long elapsedSinceLastFeedMs) {
        this.eventDispatcher.audioTrackUnderrun(bufferSize, bufferSizeMs, elapsedSinceLastFeedMs);
    }
    //////////////////////////////////////////////
}


