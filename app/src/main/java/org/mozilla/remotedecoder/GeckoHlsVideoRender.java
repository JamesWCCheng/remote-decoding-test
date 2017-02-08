package org.mozilla.remotedecoder;

import android.content.Context;
import android.util.Log;

import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;
import com.google.android.exoplayer2.video.MediaCodecVideoRenderer;

/**
 * Created by kilikkuo on 2/6/17.
 */

public class GeckoHlsVideoRender extends MediaCodecVideoRenderer {
    private static final String TAG = "GeckoHlsVideoRender";

    public GeckoHlsVideoRender(Context context, MediaCodecSelector mediaCodecSelector) {
        super(context, mediaCodecSelector);
    }
}
