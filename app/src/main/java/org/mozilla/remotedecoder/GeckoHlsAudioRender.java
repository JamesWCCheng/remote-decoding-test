package org.mozilla.remotedecoder;

import android.util.Log;

import com.google.android.exoplayer2.audio.MediaCodecAudioRenderer;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;

/**
 * Created by kilikkuo on 2/8/17.
 */

public class GeckoHlsAudioRender extends MediaCodecAudioRenderer {
    private static final String TAG = "GeckoHlsAudioRender";

    public GeckoHlsAudioRender(MediaCodecSelector mediaCodecSelector) {
        super(mediaCodecSelector);
    }
}


