package org.mozilla.remotedecoder;

import android.content.Intent;
import android.net.Uri;
import android.os.Handler;
import android.text.TextUtils;
import android.util.Log;
import android.view.Surface;
import com.google.android.exoplayer2.C;
import com.google.android.exoplayer2.DefaultLoadControl;
import com.google.android.exoplayer2.ExoPlaybackException;
import com.google.android.exoplayer2.ExoPlayer;
import com.google.android.exoplayer2.ExoPlayerFactory;
import com.google.android.exoplayer2.Renderer;
import com.google.android.exoplayer2.SimpleExoPlayer;
import com.google.android.exoplayer2.Timeline;
import com.google.android.exoplayer2.source.MediaSource;
import com.google.android.exoplayer2.source.TrackGroupArray;
import com.google.android.exoplayer2.source.hls.HlsMediaSource;
import com.google.android.exoplayer2.trackselection.AdaptiveVideoTrackSelection;
import com.google.android.exoplayer2.trackselection.DefaultTrackSelector;
import com.google.android.exoplayer2.trackselection.TrackSelection;
import com.google.android.exoplayer2.trackselection.TrackSelectionArray;
import com.google.android.exoplayer2.ui.SimpleExoPlayerView;
import com.google.android.exoplayer2.upstream.DataSource;
import com.google.android.exoplayer2.upstream.DefaultBandwidthMeter;
import com.google.android.exoplayer2.upstream.DefaultDataSourceFactory;
import com.google.android.exoplayer2.upstream.DefaultHttpDataSourceFactory;
import com.google.android.exoplayer2.upstream.HttpDataSource;
import com.google.android.exoplayer2.util.Util;
import com.google.android.exoplayer2.mediacodec.MediaCodecSelector;

import java.util.ArrayList;

public class GeckoHlsPlayer implements ExoPlayer.EventListener {

    private static final String TAG = "GeckoHlsPlayer";
    private static final String HLS_URL = "https://devimages.apple.com.edgekey.net/streaming/examples/bipbop_4x3/gear1/prog_index.m3u8";
    private static final DefaultBandwidthMeter BANDWIDTH_METER = new DefaultBandwidthMeter();
    private DataSource.Factory mediaDataSourceFactory;
    private Timeline.Window window;
    protected String userAgent;
    private Handler mainHandler;
    public final String extension = null;
    public static final String EXTENSION_EXTRA = "extension";
    private EventLogger eventLogger;

    private ExoPlayer player;
//    private SimpleExoPlayer player;

    private DefaultTrackSelector trackSelector;
    private boolean isTimelineStatic = false;
    private final Renderer[] renderers;
    private MediaSource mediaSource;

    private SimpleExoPlayerView simpleExoPlayerView;

    public DataSource.Factory buildDataSourceFactory(VideoActivity va, DefaultBandwidthMeter bandwidthMeter) {
        return new DefaultDataSourceFactory(va, bandwidthMeter,
                buildHttpDataSourceFactory(bandwidthMeter));
    }

    public HttpDataSource.Factory buildHttpDataSourceFactory(DefaultBandwidthMeter bandwidthMeter) {
        return new DefaultHttpDataSourceFactory(userAgent, bandwidthMeter);
    }

    private MediaSource buildMediaSource(Uri uri, String overrideExtension) {
        int type = Util.inferContentType(!TextUtils.isEmpty(overrideExtension) ? "." + overrideExtension
                : uri.getLastPathSegment());
        switch (type) {
            case C.TYPE_HLS:
                return new HlsMediaSource(uri, mediaDataSourceFactory, mainHandler, null);
            default: {
                throw new IllegalStateException("Unsupported type: " + type);
            }
        }
    }

    public void setSurface(Surface surface) {
        player.prepare(mediaSource);

        ExoPlayer.ExoPlayerMessage[] messages = new ExoPlayer.ExoPlayerMessage[1];
        int count = 0;
        for (Renderer renderer : renderers) {
            if (renderer.getTrackType() == C.TRACK_TYPE_VIDEO) {
                messages[count++] = new ExoPlayer.ExoPlayerMessage(renderer, C.MSG_SET_SURFACE, surface);
            }
        }

        if (surface != null) {
            player.blockingSendMessages(messages);
        }
//        if (this.surface != null && this.surface != surface) {
//            // If we created this surface, we are responsible for releasing it.
//            if (this.ownsSurface) {
//                this.surface.release();
//            }
//            // We're replacing a surface. Block to ensure that it's not accessed after the method returns.
//            player.blockingSendMessages(messages);
//        } else {
//            player.sendMessages(messages);
//        }
//        this.surface = surface;
//        this.ownsSurface = ownsSurface;

    }

    public GeckoHlsPlayer(VideoActivity va, Intent intent) {

//        simpleExoPlayerView = (SimpleExoPlayerView) va.findViewById(R.id.player_view);
//        simpleExoPlayerView.setControllerVisibilityListener(va);
//        simpleExoPlayerView.requestFocus();

        window = new Timeline.Window();
        TrackSelection.Factory videoTrackSelectionFactory =
                new AdaptiveVideoTrackSelection.Factory(BANDWIDTH_METER);
        trackSelector = new DefaultTrackSelector(videoTrackSelectionFactory);

        ArrayList<Renderer> renderersList = new ArrayList<>();
        renderersList.add(new GeckoHlsVideoRender(va, MediaCodecSelector.DEFAULT));
        renderersList.add(new GeckoHlsAudioRender(MediaCodecSelector.DEFAULT));
        renderers = renderersList.toArray(new Renderer[renderersList.size()]);

        player = ExoPlayerFactory.newInstance(renderers, trackSelector);
//        player = ExoPlayerFactory.newSimpleInstance(va, trackSelector, new DefaultLoadControl());
        player.addListener(this);

        eventLogger = new EventLogger(trackSelector);
        player.addListener(eventLogger);

        intent.setData(Uri.parse(HLS_URL));
        intent.putExtra(EXTENSION_EXTRA, extension);

        Uri[] uris = new Uri[]{intent.getData()};
        String[] extensions = new String[]{intent.getStringExtra(EXTENSION_EXTRA)};
        mainHandler = new Handler();
        userAgent = Util.getUserAgent(va, "RemoteDecoder");
        mediaDataSourceFactory = buildDataSourceFactory(va, null);

        MediaSource[] mediaSources = new MediaSource[1];
        mediaSources[0] = buildMediaSource(uris[0], extensions[0]);
        mediaSource = mediaSources[0];
//        player.prepare(mediaSource);

//        simpleExoPlayerView.setPlayer(player);

    }

    @Override
    public void onLoadingChanged(boolean isLoading) {
        Log.d(TAG, "loading [" + isLoading + "]");
    }

    @Override
    public void onPlayerStateChanged(boolean playWhenReady, int state) {
//        Log.d(TAG, "state [" + getSessionTimeString() + ", " + playWhenReady + ", "
//                + getStateString(state) + "]");
        if (state == ExoPlayer.STATE_READY) {
            player.setPlayWhenReady(true);
        }
    }

    @Override
    public void onPositionDiscontinuity() {
        Log.d(TAG, "positionDiscontinuity");
    }

    @Override
    public void onPlayerError(ExoPlaybackException e) {
//        Log.e(TAG, "playerFailed [" + getSessionTimeString() + "]", e);
    }

    @Override
    public void onTracksChanged(TrackGroupArray ignored, TrackSelectionArray trackSelections) {
    }

    @Override
    public void onTimelineChanged(Timeline timeline, Object manifest) {
        isTimelineStatic = !timeline.isEmpty()
                && !timeline.getWindow(timeline.getWindowCount() - 1, window).isDynamic;
    }
}