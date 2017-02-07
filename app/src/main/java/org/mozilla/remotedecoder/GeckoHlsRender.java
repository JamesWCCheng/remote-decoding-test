package org.mozilla.remotedecoder;

import android.util.Log;

import com.google.android.exoplayer2.BaseRenderer;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.Format;
import com.google.android.exoplayer2.mediacodec.MediaCodecUtil;

/**
 * Created by kilikkuo on 2/6/17.
 */

public class GeckoHlsRender extends BaseRenderer {
    private static final String TAG = "GeckoHlsRender";

    public GeckoHlsRender(int trackType) {
        super(trackType);
    }

    @Override
    public void render(long positionUs, long elapsedRealtimeUs) throws ExoPlaybackException {
        Log.d(TAG, "rendering");
    }

    @Override
    public boolean isEnded() {
        return false;
    }

    @Override
    public boolean isReady() {
        return false;
    }

    @Override
    public final int supportsMixedMimeTypeAdaptation() throws ExoPlaybackException {
        return ADAPTIVE_NOT_SEAMLESS;
    }

    @Override
    public final int supportsFormat(Format format) throws ExoPlaybackException {
        return 0;
//        try {
//            return supportsFormat(mediaCodecSelector, format);
//        } catch (MediaCodecUtil.DecoderQueryException e) {
//            throw ExoPlaybackException.createForRenderer(e, getIndex());
//        }
    }
}
