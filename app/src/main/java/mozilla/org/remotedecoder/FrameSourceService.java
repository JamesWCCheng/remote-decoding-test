package mozilla.org.remotedecoder;

import android.app.Service;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Message;
import android.os.Messenger;
import android.os.RemoteException;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Queue;

public class FrameSourceService extends Service {
    // Message IDs.
    // Requests.
    public static final int MSG_SET_OUTPUT_SURFACE = 1;
    public static final int MSG_INPUT = 2;
    // Replys.
    public static final int MSG_OUTPUT = 3;
    public static final int MSG_SET_SHM_FD = 4;
    // Internal Ops.
    public static final int MSG_DO_FRAME = 5;

    public static final int MSG_TEST_DRAW_SURFACE = 100;

    // Result codes.
    enum Result {
        SUCCESS, BAD_MIME, TRY_AGAIN, END_OF_STREAM
    };
    // Data keys.
    public static final String KEY_SAMPLE_BYTES = "sample_bytes";
    public static final String KEY_SAMPLE_PTS_US = "sample_pts_us";

    private final Messenger mMessenger = new Messenger(new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SET_OUTPUT_SURFACE:
                    synchronized (FrameSourceService.this) {
                        mOutputSurface = (Surface)msg.obj;
                    }
                    break;
                case MSG_INPUT:
                    Result err = ensureDecoder(msg.getData());
                    if (err != Result.SUCCESS) {
                        Message reply = Message.obtain();
                        reply.what = err.ordinal();
                        try {
                            msg.replyTo.send(reply);
                        } catch (RemoteException e) {
                            e.printStackTrace();
                        }
                    } else {
                        doInput(msg.getData());
                    }
                    break;
                case MSG_DO_FRAME:
                    doFrame();
                    break;
                case MSG_TEST_DRAW_SURFACE:
                    if (mWorker != null) {
                        int color = msg.arg1;
                        int r = (color >> 16) & 0xFF, g = (color >> 8) & 0xFF, b = color & 0xFF;
                        mWorker.post(new DrawSurfaceRunnable(r, g, b));
                    }
                    break;
                default:
                    super.handleMessage(msg);
            }
        }
    });

    private static MediaCodecList sCodecList;

    private String mMimeType;
    private MediaCodec mDecoder;
    private Surface mOutputSurface;

    private HandlerThread mWorkerThread;
    private Handler mWorker;

    private static class Sample {
        final long mPresentationTimeUs;
        final byte[] mBytes;

        static final Sample EOS = new Sample(0, null);

        Sample(long pts, byte[] bytes) {
            mPresentationTimeUs = pts;
            mBytes = bytes;
        }

        boolean isEOS() {
            return this == EOS;
        }
    }

    private Queue<Sample> mInputSamples = new LinkedList<Sample>();

    public FrameSourceService() {
    }

    @Override
    public void onCreate() {
        if (mWorkerThread == null) {
            mWorkerThread = new HandlerThread("worker");
            mWorkerThread.start();
            mWorker = new Handler(mWorkerThread.getLooper());
        }
    }

    @Override
    public void onDestroy() {
        if (mWorkerThread != null) {
            mWorkerThread.quit();
            mWorker = null;
            mWorkerThread = null;
        }
    }

    @Override
    public IBinder onBind(Intent intent) {
        return mMessenger.getBinder();
    }

    private Result ensureDecoder(Bundle inputData) {
        String mime = inputData.getString(MediaFormat.KEY_MIME);
        if (mime == null || mime.isEmpty()) {
            return Result.BAD_MIME;
        }
        if (mMimeType != null) {
            if (mMimeType == mime) {
                return Result.SUCCESS;
            } else {
                return Result.BAD_MIME;
            }
        }

        if (sCodecList == null) {
            sCodecList = new MediaCodecList(MediaCodecList.ALL_CODECS);
        }
        MediaFormat fmt;
        if (mime.startsWith("video/")) {
            int w = inputData.getInt(MediaFormat.KEY_WIDTH);
            int h = inputData.getInt(MediaFormat.KEY_HEIGHT);
            fmt = MediaFormat.createVideoFormat(mime, w, h);
        } else if (mime.startsWith("audio/")) {
            int sr = inputData.getInt(MediaFormat.KEY_SAMPLE_RATE);
            int cc = inputData.getInt(MediaFormat.KEY_CHANNEL_COUNT);
            fmt = MediaFormat.createAudioFormat(mime, sr, cc);
        } else {
            return Result.BAD_MIME;
        }
        String name = sCodecList.findDecoderForFormat(fmt);
        try {
            mDecoder = MediaCodec.createByCodecName(name);
            mDecoder.configure(fmt, mOutputSurface, null, 0);
            mDecoder.start();
            mMimeType = mime;
            return Result.SUCCESS;
        } catch (IOException e) {
            e.printStackTrace();
            return Result.BAD_MIME;
        }
    }

    private void doInput(Bundle inputData) {
        long pts = inputData.getLong(KEY_SAMPLE_PTS_US);
        byte[] data = inputData.getByteArray(KEY_SAMPLE_BYTES);
        mInputSamples.add(new Sample(pts, data));
        Message msg = Message.obtain();
        msg.what = MSG_DO_FRAME;
        try {
            mMessenger.send(msg);
        } catch (RemoteException e) {
            e.printStackTrace();
        }
    }

    private void doFrame() {
        processOutput();
        processInput();
    }

    private Result processOutput() {
        while (true) {
            MediaCodec.BufferInfo info = new MediaCodec.BufferInfo();
            int result = mDecoder.dequeueOutputBuffer(info, 0);
            switch (result) {
                case MediaCodec.INFO_TRY_AGAIN_LATER:
                    // TODO
                    return Result.TRY_AGAIN;
                case MediaCodec.INFO_OUTPUT_FORMAT_CHANGED:
                    // TODO
                    break;
                default:
                    // TODO: assert surface?
                    mDecoder.releaseOutputBuffer(result, true /* render */);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        return Result.END_OF_STREAM;
                    }
            }
        }
    }

    private Result processInput() {
        while (true) {
            Sample input = mInputSamples.poll();
            if (input == null) {
                // No pending sample.
                break;
            }

            int index = mDecoder.dequeueInputBuffer(0);
            if (index == -1) {
                // Input buffer not available.
                break;
            }

            ByteBuffer buffer = mDecoder.getInputBuffer(index);
            int flags = 0;
            if (input.isEOS()) {
                flags |= MediaCodec.BUFFER_FLAG_END_OF_STREAM;
            } else if (buffer.capacity() < input.mBytes.length) {
                // TODO
                break;
            } else {
                buffer.clear();
                buffer.put(input.mBytes);
            }
            mDecoder.queueInputBuffer(index, 0, input.mBytes.length, input.mPresentationTimeUs, flags);
        }

        return Result.SUCCESS;
    }

    private class DrawSurfaceRunnable implements Runnable {
        private final int mRed, mGreen, mBlue;
        private DrawSurfaceRunnable(int r, int g, int b) {
            mRed = r;
            mGreen = g;
            mBlue = b;
        }

        @Override
        public void run() {
            synchronized (FrameSourceService.this) {
                try {
                    android.graphics.Canvas c = mOutputSurface.lockCanvas(null);

                    c.drawRGB(mRed, mGreen, mBlue);
                    mOutputSurface.unlockCanvasAndPost(c);
                } catch (RuntimeException e) {
                    e.printStackTrace();
                }
            }
        }
    }
}
