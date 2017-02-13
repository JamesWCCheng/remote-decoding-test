package org.mozilla.remotedecoder;

import com.google.android.exoplayer2.BaseRenderer;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.FormatHolder;
import com.google.android.exoplayer2.decoder.DecoderInputBuffer;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.util.Assertions;

import java.util.ArrayList;
import java.util.List;

/**
 * Created by james on 2/13/17.
 */

// Put Audio and Video common code in this base class
public abstract class GeckoHlsBaseRenderer extends BaseRenderer {

    private final MediaCodecSelector mediaCodecSelector;
    private final DecoderInputBuffer buffer;
    private final FormatHolder formatHolder;
    private final List<Long> decodeOnlyPresentationTimestamps;

    private boolean codecIsAdaptive;
    private boolean codecNeedsDiscardToSpsWorkaround;
    private boolean codecNeedsFlushWorkaround;
    private boolean codecNeedsAdaptationWorkaround;
    private boolean codecNeedsEosPropagationWorkaround;
    private boolean codecNeedsEosFlushWorkaround;
    private boolean codecNeedsAdaptationWorkaroundBuffer;
    private boolean shouldSkipAdaptationWorkaroundOutputBuffer;
    private Format format;

    public GeckoHlsBaseRenderer(int trackType, MediaCodecSelector mediaCodecSelector) {
        super(trackType);
        this.mediaCodecSelector = (MediaCodecSelector) Assertions.checkNotNull(mediaCodecSelector);
        this.buffer = new DecoderInputBuffer(0);
        this.formatHolder = new FormatHolder();
        this.decodeOnlyPresentationTimestamps = new ArrayList();
    }
    protected void onInputFormatChanged(Format newFormat) throws ExoPlaybackException {
        Format oldFormat = this.format;
        this.format = newFormat;

        if(false) {
            this.codecReconfigured = true;
            this.codecReconfigurationState = 1;
            this.codecNeedsAdaptationWorkaroundBuffer = this.codecNeedsAdaptationWorkaround && this.format.width == oldFormat.width && this.format.height == oldFormat.height;
        } else if(this.codecReceivedBuffers) {
            this.codecReinitializationState = 1;
        } else {
            this.releaseRenderer();
            this.maybeInitRenderer();
        }
    }
}
