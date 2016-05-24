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
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;

public class FrameSourceService extends Service {
    private static final String LOG_TAG = FrameSourceService.class.getSimpleName();
    // Message IDs.
    // Requests.
    public static final int MSG_SET_OUTPUT_SURFACE = 1;
    public static final int MSG_INPUT = 2;
    // Replys.
    public static final int MSG_OUTPUT = 3;
    public static final int MSG_SET_SHM_FD = 4;
    // Internal Ops.
    public static final int MSG_PROCESS_INPUT = 100;
    public static final int MSG_PROCESS_EOS_OUTPUT = 101;
    public static final int MSG_PROCESS_ERROR = 102;

    public static final int MSG_TEST_DRAW_SURFACE = 1000;

    // Result codes.
    enum Result {
        SUCCESS, BAD_MIME, TRY_AGAIN, END_OF_STREAM
    };
    // Data keys.
    public static final String KEY_SAMPLE_BYTES = "sample_bytes";
    public static final String KEY_SAMPLE_PTS_US = "sample_pts_us";

    private Messenger mClient;
    private final Messenger mMessenger = new Messenger(new Handler() {
        @Override
        public void handleMessage(Message msg) {
            switch (msg.what) {
                case MSG_SET_OUTPUT_SURFACE:
                    android.util.Log.d(LOG_TAG, "set output surface:" + msg.obj);
                    synchronized (FrameSourceService.this) {
                        mOutputSurface = (Surface)msg.obj;
                    }
                    break;
                case MSG_INPUT:
                    if (mClient == null) {
                        mClient = msg.replyTo;
                    }
                    Result err = ensureDecoder(msg.getData());
                    if (err != Result.SUCCESS) {
                        Message reply = Message.obtain();
                        reply.what = err.ordinal();
                        try {
                            mClient.send(reply);
                        } catch (RemoteException e) {
                            e.printStackTrace();
                        }
                    } else {
                        doInput(msg.getData());
                    }
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
                    break;
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

        public String toString() {
            if (isEOS()) {
                return "sample=EOS";
            } else {
                return "sample={ pts:" + mPresentationTimeUs + "; length:" + mBytes.length + " }";
            }
        }
    }

    private BlockingQueue<Sample> mInputSamples = new LinkedBlockingQueue<Sample>();
    private BlockingQueue<Integer> mAvailableInputBuffers = new LinkedBlockingQueue<Integer>();

    private class DecoderCallback extends MediaCodec.Callback {
        @Override
        public void onInputBufferAvailable(MediaCodec codec, int index) {
            try {
                mAvailableInputBuffers.put(index);
                android.util.Log.v(LOG_TAG, "in: available buffer#" + index);
            } catch (InterruptedException e) {
                e.printStackTrace();
            }
            if (mWorker != null) {
                mWorker.sendEmptyMessage(MSG_PROCESS_INPUT);
            }
        }

        @Override
        public void onOutputBufferAvailable(MediaCodec codec, int index, MediaCodec.BufferInfo info) {
            android.util.Log.v(LOG_TAG, "out: buffer#" + index +
                    ", pts:" + info.presentationTimeUs +
                    ", size:" + info.size +
                    ", flags:" + info.flags);
            // TODO: assert surface?
            mDecoder.releaseOutputBuffer(index, true /* render */);
            if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                mWorker.sendEmptyMessage(MSG_PROCESS_EOS_OUTPUT);
            }
        }

        @Override
        public void onError(MediaCodec codec, MediaCodec.CodecException e) {
            android.util.Log.e(LOG_TAG, "out: error");
            mWorker.sendEmptyMessage(MSG_PROCESS_ERROR);
        }

        @Override
        public void onOutputFormatChanged(MediaCodec codec, MediaFormat format) {
            android.util.Log.v(LOG_TAG, "out: format=" +
                    Integer.toHexString(format.getInteger(MediaFormat.KEY_COLOR_FORMAT)));
        }
    }

    public FrameSourceService() {
    }

    @Override
    public void onCreate() {
        if (mWorkerThread == null) {
            mWorkerThread = new HandlerThread("worker");
            mWorkerThread.start();
            mWorker = new Handler(mWorkerThread.getLooper()) {
                @Override
                public void handleMessage(Message msg) {
                    switch (msg.what) {
                        case MSG_PROCESS_INPUT:
                            processInput();
                            break;
                        case MSG_PROCESS_ERROR:
                        case MSG_PROCESS_EOS_OUTPUT:
                            shutdownDecoder();
                            break;
                        default:
                            super.handleMessage(msg);
                            break;
                    }
                }

            };
        }
    }

    @Override
    public void onDestroy() {
        mClient = null;
        mInputSamples.clear();
        mAvailableInputBuffers.clear();
        mWorker.sendEmptyMessage(MSG_PROCESS_EOS_OUTPUT);
        if (mWorkerThread != null) {
            mWorkerThread.quitSafely();
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
            android.util.Log.e(LOG_TAG, "empty MIME");
            return Result.BAD_MIME;
        }
        if (mMimeType != null) {
            if (mMimeType.equals(mime)) {
                return Result.SUCCESS;
            } else {
                android.util.Log.e(LOG_TAG, "MIME:" + mMimeType + " -> " + mime);
                return Result.BAD_MIME;
            }
        }

        // If needed...
        shutdownDecoder();

        android.util.Log.d(LOG_TAG, "mime:" + mime);
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
            android.util.Log.e(LOG_TAG, "unsupported MIME");
            return Result.BAD_MIME;
        }
        if (inputData.containsKey("csd-0")) {
            fmt.setByteBuffer("csd-0", ByteBuffer.wrap(inputData.getByteArray("csd-0")));
        }
        if (inputData.containsKey("csd-1")) {
            fmt.setByteBuffer("csd-1", ByteBuffer.wrap(inputData.getByteArray("csd-1")));
        }
        String name = sCodecList.findDecoderForFormat(fmt);
        try {
            mDecoder = MediaCodec.createByCodecName(name);
            mDecoder.setCallback(new DecoderCallback());
            mDecoder.configure(fmt, mOutputSurface, null, 0);
            mDecoder.start();
            mMimeType = mime;
            android.util.Log.d(LOG_TAG, "decoder:" + name);

            return Result.SUCCESS;
        } catch (IOException e) {
            e.printStackTrace();
            return Result.BAD_MIME;
        }
    }

    private void shutdownDecoder() {
        if (mDecoder != null) {
            mDecoder.stop();
            mDecoder.release();
            mDecoder = null;
            mMimeType = null;
        }
    }

    private void doInput(Bundle inputData) {
        long pts = inputData.getLong(KEY_SAMPLE_PTS_US);
        Sample sample;
        if (pts >= 0) {
            byte[] data = inputData.getByteArray(KEY_SAMPLE_BYTES);
            sample = new Sample(pts, data);
            android.util.Log.v(LOG_TAG, "receive " + sample);
        } else {
            sample = Sample.EOS;
        }

        try {
            mInputSamples.put(sample);
        } catch (InterruptedException e) {
            e.printStackTrace();
            return;
        }

        if (mWorker != null) {
            mWorker.sendEmptyMessage(MSG_PROCESS_INPUT);
        }
    }

    private Result processInput() {
        while (true) {
            if (mInputSamples.isEmpty() || mAvailableInputBuffers.isEmpty()) {
                android.util.Log.v(LOG_TAG, "in: empty");
                // No pending sample.
                break;
            }

            int index;
            Sample input;
            try {
                index = mAvailableInputBuffers.take().intValue();
                input = mInputSamples.take();
            } catch (InterruptedException e) {
                // Should never happen.
                e.printStackTrace();
                android.util.Log.d(LOG_TAG, "in: out of buffer");
                return Result.TRY_AGAIN;
            }

            ByteBuffer buffer = mDecoder.getInputBuffer(index);
            int len = 0, flags = 0;
            if (input.isEOS()) {
                flags |= MediaCodec.BUFFER_FLAG_END_OF_STREAM;
            } else if (buffer.capacity() < input.mBytes.length) {
                // TODO
                android.util.Log.e(LOG_TAG, "in: buffer too small");
                break;
            } else {
                len = input.mBytes.length;
                buffer.put(input.mBytes, 0, len);
            }
            mDecoder.queueInputBuffer(index, 0, len, input.mPresentationTimeUs, flags);
            android.util.Log.v(LOG_TAG, "in: feed " + input);
            if (mClient != null && len > 0) {
                Message msg = Message.obtain();
                msg.what = MSG_OUTPUT;
                try {
                    mClient.send(msg);
                } catch (RemoteException e) {
                    e.printStackTrace();
                }
            }
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
