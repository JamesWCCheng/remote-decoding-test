package mozilla.org.remotedecoder;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.MediaExtractor;
import android.media.MediaFormat;
import android.os.Environment;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

import java.io.IOException;
import java.nio.ByteBuffer;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    private static final String LOG_TAG = MainActivity.class.getSimpleName();

    private SurfaceView mFrameView;
    private SurfaceHolder mHolder;
    private Messenger mFrameSource;
    private boolean mIsBound;

    private MediaExtractor mExtractor; // TODO: Racy
    private MediaFormat mFormat;
    private boolean mSentCSD;
    private int mInputFrameCount;
    private int mOutputFrameCount;

    private static int[] Colors = { 0xFF0000, 0x00FF00, 0xFF };
    private int mColorIndex = 0;

    private final Runnable mNextFrame = new Runnable() {
        @Override
        public void run() {
            mWorker.sendEmptyMessage(FrameSourceService.MSG_INPUT);
        }
    };

    private final Handler mWorker = new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case FrameSourceService.MSG_OUTPUT:
                    mOutputFrameCount++;
                case FrameSourceService.MSG_INPUT:
                    if (doFrame()) {
                        post(mNextFrame);
                    }
                    break;
                default:
                    super.handleMessage(msg);
                    break;
            }
        }
    };

    private final Messenger mMessenger = new Messenger(mWorker);

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mFrameSource = new Messenger(service);
            Message msg = Message.obtain();
            msg.what = FrameSourceService.MSG_SET_OUTPUT_SURFACE;
            msg.obj = mHolder.getSurface();
            msg.replyTo = mMessenger;
            try {
                mFrameSource.send(msg);
            } catch (RemoteException e) {
                e.printStackTrace();
            }
            startDecoding();
            //startDrawing();
        }

        @Override
        public void onServiceDisconnected(ComponentName name) {
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);
        mFrameView = (SurfaceView)findViewById(R.id.videoFrameView);
        mFrameView.getHolder().addCallback(this);
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        mHolder = holder;
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (!mIsBound) {
            bindService(new Intent(this, FrameSourceService.class), mConnection, Context.BIND_AUTO_CREATE);
            mIsBound = true;
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (mIsBound) {
            synchronized (mExtractor) {
                if (mExtractor != null) {
                    mWorker.removeCallbacks(mNextFrame);
                    mExtractor.release();
                    mExtractor = null;
                }
            }
            Message msg = Message.obtain();
            msg.what = FrameSourceService.MSG_SET_OUTPUT_SURFACE;
            msg.obj = null;
            try {
                mFrameSource.send(msg);
            } catch (RemoteException e) {
                e.printStackTrace();
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
            mExtractor.setDataSource("http://people.mozilla.org/~jolin/mozilla%20employee%20recruiting%20video%20.mp4");
        } catch (IOException e) {
            e.printStackTrace();
            return;
        }
        int vTrack = -1;
        for (int i = 0; i < mExtractor.getTrackCount(); i++) {
            mFormat = mExtractor.getTrackFormat(i);
            if (mFormat.getString(MediaFormat.KEY_MIME).startsWith("video/")) {
                vTrack = i;
                break;
            }
        }

        if (vTrack >= 0) {
            mExtractor.selectTrack(vTrack);
            mSentCSD = false;
            mInputFrameCount = 0;
            mOutputFrameCount = 0;
            mWorker.sendEmptyMessage(FrameSourceService.MSG_INPUT);
        }
    }

    private boolean sendFrame(Bundle inputData) {
        Message msg = Message.obtain();
        msg.what = FrameSourceService.MSG_INPUT;
        msg.setData(inputData);
        msg.replyTo = mMessenger;
        try {
            mFrameSource.send(msg);
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

        Bundle inputData = new Bundle();
        inputData.putString(MediaFormat.KEY_MIME, mime);
        if (!mSentCSD) {
            if (mFormat.containsKey("csd-0")) {
                inputData.putByteArray("csd-0", mFormat.getByteBuffer("csd-0").array());
            }
            if (mFormat.containsKey("csd-1")) {
                inputData.putByteArray("csd-1", mFormat.getByteBuffer("csd-1").array());
            }
            mSentCSD = true;
        }
        int w = mFormat.getInteger(MediaFormat.KEY_WIDTH), h = mFormat.getInteger(MediaFormat.KEY_HEIGHT);
        inputData.putInt(MediaFormat.KEY_WIDTH, w);
        inputData.putInt(MediaFormat.KEY_HEIGHT, h);
        ByteBuffer buf = ByteBuffer.allocate(w * h * 3 / 2);
        int len = mExtractor.readSampleData(buf, 0);
        if (len > 0) {
            byte[] bytes = new byte[len];
            buf.get(bytes, 0, len);
            inputData.putByteArray(FrameSourceService.KEY_SAMPLE_BYTES, bytes);
            inputData.putLong(FrameSourceService.KEY_SAMPLE_PTS_US, mExtractor.getSampleTime());
            hasNext = mExtractor.advance();
        } else {
            inputData.putLong(FrameSourceService.KEY_SAMPLE_PTS_US, -1);
        }
        boolean ok = sendFrame(inputData);
        boolean wantMore = (mInputFrameCount - mOutputFrameCount) < 5;

        return ok && hasNext && wantMore;
    }

    private void startDrawing() {
        mFrameView.postOnAnimation(new Runnable() {
            public void run() {
                if (!mFrameView.isAttachedToWindow()) {
                    return;
                }
                Message msg = Message.obtain();
                msg.what = FrameSourceService.MSG_TEST_DRAW_SURFACE;
                msg.arg1 = Colors[mColorIndex];
                try {
                    mFrameSource.send(msg);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
                mColorIndex = (mColorIndex + 1) % 3;
                MainActivity.this.mFrameView.postOnAnimation(this);
            }
        });
    }
}
