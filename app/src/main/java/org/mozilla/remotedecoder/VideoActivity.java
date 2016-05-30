/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.remotedecoder;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.RemoteException;
import android.support.v7.app.AppCompatActivity;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import org.mozilla.gecko.media.FormatParam;
import org.mozilla.gecko.media.ICodec;
import org.mozilla.gecko.media.ICodecCallbacks;
import org.mozilla.gecko.media.IMediaService;
import org.mozilla.gecko.media.MediaService;
import org.mozilla.gecko.media.Sample;

import java.io.IOException;
import java.nio.ByteBuffer;

public class VideoActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    private static final String LOG_TAG = VideoActivity.class.getSimpleName();

    private static final String VIDEO_URL = "http://people.mozilla.org/~jolin/mozilla%20employee%20recruiting%20video%20.mp4";

    private SurfaceHolder mHolder;

    private IMediaService mDecoderManager;
    private boolean mIsBound;
    private ICodec mDecoder;
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

    private final Handler mWorker = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_OUTPUT:
                    mOutputFrameCount++;
                    break;
                case MSG_INPUT:
                    if (doFrame()) {
                        sendEmptyMessage(MSG_INPUT);
                    }
                    break;
                default:
                    super.handleMessage(msg);
                    break;
            }
        }
    };

    private final ICodecCallbacks mCallbacks = new ICodecCallbacks.Stub() {
        @Override
        public void onInputConsumed() throws RemoteException {
            mWorker.sendEmptyMessage(MSG_INPUT);
        }

        @Override
        public void onOutputFormatChanged(FormatParam format) throws RemoteException {
            // TODO
        }

        @Override
        public void onOutput(Sample sample) throws RemoteException {
            mWorker.sendEmptyMessage(MSG_OUTPUT);
        }

        @Override
        public void onError(int error) throws RemoteException {
            // TODO
        }
    };

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mDecoderManager = IMediaService.Stub.asInterface(service);
            startDecoding();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
            stopDecoding();
            mDecoderManager = null;
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
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
        if (!mIsBound) {
            bindService(new Intent(this, MediaService.class), mConnection, Context.BIND_AUTO_CREATE);
            mIsBound = true;
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (mIsBound) {
            if (mExtractor != null) {
                mExtractor.release();
                mExtractor = null;
            }

            unbindService(mConnection);
            mIsBound = false;
        }
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
                try {
                    mDecoder = mDecoderManager.createCodec();
                    mDecoder.asBinder().linkToDeath(mDecoderDeathWatcher, 0);
                    mDecoder.setCallbacks(mCallbacks);
                    if (!mDecoder.configure(new FormatParam(mFormat), mHolder.getSurface(), 0)) {
                        android.util.Log.e(LOG_TAG, "FAIL: codec not created.");
                        mDecoder.asBinder().unlinkToDeath(mDecoderDeathWatcher, 0);
                       mDecoder = null;
                    } else {
                        mDecoder.start();
                    }
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
            mWorker.sendEmptyMessage(MSG_INPUT);
        }
    }

    private void stopDecoding() {
        if (mDecoder == null) {
            return;
        }

        try {
            mDecoder.stop();
            mDecoder.release();
        } catch (RemoteException e) {
            e.printStackTrace();
        }
        mDecoder.asBinder().unlinkToDeath(mDecoderDeathWatcher, 0);
        mDecoder = null;
    }

    private boolean sendFrame(Sample sample) {
        try {
            mDecoder.inputSample(sample);
            mInputFrameCount++;
            return true;
        } catch (RemoteException e) {
            e.printStackTrace();
            return false;
        }
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
