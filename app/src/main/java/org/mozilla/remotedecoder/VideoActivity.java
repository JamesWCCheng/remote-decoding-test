/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.remotedecoder;

import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.support.v7.app.AppCompatActivity;
import android.util.Log;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import org.mozilla.gecko.GeckoAppShell;
import org.mozilla.gecko.media.CodecProxy;
import org.mozilla.gecko.media.Sample;

import java.io.IOException;
import java.nio.ByteBuffer;

public class VideoActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    private static final String LOG_TAG = VideoActivity.class.getSimpleName();

    private static final String VIDEO_URL = "http://people.mozilla.org/~jolin/mozilla%20employee%20recruiting%20video%20.mp4";

    private SurfaceHolder mHolder;

    private CodecProxy mDecoder;
    private IBinder.DeathRecipient mDecoderDeathWatcher = new IBinder.DeathRecipient() {
        @Override
        public void binderDied() {
            stopDecoding();
        }
    };
    private MediaExtractor mExtractor; // TODO: Racy
    private MediaFormat mFormat;
    private int mInputFrameCount;
    private int mOutputFrameCount;

    private static final int MSG_INPUT = 1;
    private static final int MSG_OUTPUT = 2;

    private CodecWorker mWorker;

    class CodecWorker extends Handler {
        public CodecWorker(Looper looper) { super(looper); }
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_OUTPUT:
                    mOutputFrameCount++;
                    break;
                case MSG_INPUT:
                    doFrame();
                    break;
                default:
                    super.handleMessage(msg);
                    break;
            }
        }
    };

    private final CodecProxy.Callbacks mCallbacks = new CodecProxy.Callbacks() {
        @Override
        public void onInputConsumed() {
            mWorker.sendEmptyMessage(MSG_INPUT);
        }

        @Override
        public void onOutputFormatChanged(MediaFormat format) {
            // TODO
        }

        @Override
        public void onOutput(Sample sample) {
            mWorker.sendEmptyMessage(MSG_OUTPUT);
        }

        @Override
        public void onError(CodecProxy.Error error) {
            switch (error) {
                case OK:
                    break;
                case RELEASED:
                case REMOTE_DEAD:
                case REMOTE_CODEC_NOT_READY:
                case REMOTE_INPUT:
                case REMOTE_UNKNOWN:
                    mWorker.removeCallbacksAndMessages(null);
                    stopDecoding();
                    break;
            }
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        // Init mock app shell.
        GeckoAppShell.setAppContext(getApplicationContext());
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        SurfaceView view = (SurfaceView) findViewById(R.id.videoFrameView);
        view.getHolder().addCallback(this);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mHolder = holder;
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (mWorker == null) {
            HandlerThread thread = new HandlerThread("codec-driver");
            thread.start();
            mWorker = new CodecWorker(thread.getLooper());
        }

        mWorker.post(new Runnable() {
            public void run() {
                startDecoding();
            }
        });
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        mWorker.post(new Runnable() {
            public void run() {
                stopDecoding();
            }
        });
    }

    private void startDecoding() {
        if (mExtractor == null) {
            mExtractor = new MediaExtractor();
        }
        try {
            mExtractor.setDataSource(VIDEO_URL);
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
        int vTrack = -1;
        for (int i = 0; i < mExtractor.getTrackCount(); i++) {
            MediaFormat fmt = mExtractor.getTrackFormat(i);
            if (fmt.getString(MediaFormat.KEY_MIME).startsWith("video/")) {
                vTrack = i;
                mFormat = fmt;
                android.util.Log.d(LOG_TAG, "media format: " + fmt.toString());
                break;
            }
        }

        if (vTrack >= 0) {
            mExtractor.selectTrack(vTrack);
            mInputFrameCount = 0;
            mOutputFrameCount = 0;
            if (mDecoder == null) {
                mDecoder = CodecProxy.create(mFormat, mHolder.getSurface(), mCallbacks);
            }
            mWorker.sendEmptyMessage(MSG_INPUT);
        }
    }

    private void stopDecoding() {
        if (mDecoder != null) {
            mDecoder.release();
            mDecoder = null;
        }
        if (mExtractor != null) {
            mExtractor.release();
            mExtractor = null;
        }
    }

    private boolean sendFrame(Sample sample) {
        if (mDecoder.input(sample) == CodecProxy.Error.OK) {
            mInputFrameCount++;
            return true;
        } else {
            Log.d(LOG_TAG, "send frame error");
        }
        return false;
    }

    private boolean doFrame() {
        if (mExtractor == null) {
            return false;
        }

        String mime = mFormat.getString(MediaFormat.KEY_MIME);
        if (mime == null) {
            return false; // No video.
        }
        boolean hasNext = false;

        int w = mFormat.getInteger(MediaFormat.KEY_WIDTH), h = mFormat.getInteger(MediaFormat.KEY_HEIGHT);

        ByteBuffer buf = ByteBuffer.allocate(w * h * 3 / 2);
        int len = mExtractor.readSampleData(buf, 0);

        Sample sample;
        if (len > 0) {
            byte[] bytes = new byte[len];
            buf.get(bytes, 0, len);
            sample = new Sample(bytes, mExtractor.getSampleTime(), 0);
            hasNext = mExtractor.advance();
        } else {
            sample = Sample.EOS;
        }

        boolean ok = sendFrame(sample);
        boolean wantMore = (mInputFrameCount - mOutputFrameCount) < 5;

        return ok && hasNext && wantMore;
    }
}
