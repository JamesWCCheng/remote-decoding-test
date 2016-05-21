package mozilla.org.remotedecoder;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.os.Handler;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.support.v7.app.AppCompatActivity;
import android.os.Bundle;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

public class MainActivity extends AppCompatActivity implements SurfaceHolder.Callback {
    private SurfaceView mFrameView;
    private SurfaceHolder mHolder;
    private Messenger mFrameSource;
    private boolean mIsBound;

    private static int[] Colors = { 0xFF0000, 0x00FF00, 0xFF };
    private int mColorIndex = 0;

    private final Messenger mMessenger = new Messenger(new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case FrameSourceService.MSG_OUTPUT:
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    });

    private ServiceConnection mConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            mFrameSource = new Messenger(service);
            Message msg = Message.obtain();
            msg.what = FrameSourceService.MSG_SET_OUTPUT_SURFACE;
            msg.obj = mHolder.getSurface();
            try {
                mFrameSource.send(msg);
            } catch (RemoteException e) {
                e.printStackTrace();
            }

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
}
