/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.media;

import android.app.Service;
import android.content.Intent;
import android.media.MediaCodec;
import android.media.MediaCodecList;
import android.media.MediaFormat;
import android.os.Binder;
import android.os.Build;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.IBinder;
import android.os.Looper;
import android.os.Message;
import android.os.RemoteException;
import android.support.annotation.NonNull;
import android.util.Log;
import android.view.Surface;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.util.LinkedList;
import java.util.Queue;
import java.util.concurrent.CountDownLatch;

public final class MediaService extends Service {
    private static final String LOG_TAG = MediaService.class.getSimpleName();

    private Binder mBinder = new IMediaService.Stub() {
        @Override
        public ICodec createCodec() throws RemoteException {
            return new Codec();
        }
    };

    @Override
    public IBinder onBind(Intent intent) {
        return mBinder;
    }

    private static MediaCodecList sCodecList = new MediaCodecList(MediaCodecList.ALL_CODECS);

    private static final class Codec extends ICodec.Stub  implements IBinder.DeathRecipient {
        private static final int CODEC_MSG_CONFIG = 1;
        private static final int CODEC_MSG_INPUT_SAMPLE = 2;
        private static final int CODEC_MSG_INPUT_BUFFER_AVAILABLE = 3;
        private static final int CODEC_MSG_REPORT_FORMAT_CHANGE = 4;

        private static final int ERROR_CODEC_NOT_READY = -1;
        private static final int ERROR_INPUT = -2;
        private static final int ERROR_UNKNOWN = -3;

        final class Config {
            final MediaFormat format;
            final Surface surface;
            final int flags;

            Config(MediaFormat format, Surface surface, int flags) {
                this.format = format;
                this.surface = surface;
                this.flags = flags;
            }
        }

        final class ImplWorker extends Handler {
            private Queue<Sample> mInputSamples = new LinkedList<>(); // Access only by mWorker.
            private Queue<Integer> mAvailableInputBuffers = new LinkedList<>(); // Access only by mWorker.

            private CountDownLatch mInitLock = new CountDownLatch(1);

            ImplWorker(Looper looper) {
                super(looper);
            }

            void waitForConfig() {
                try {
                    mInitLock.await();
                } catch (InterruptedException e) {
                    e.printStackTrace();
                }
            }

            @Override
            public void handleMessage(Message msg) {
                switch (msg.what) {
                    case CODEC_MSG_CONFIG:
                        Config config = (Config)msg.obj;
                        configImpl(config);
                        mInitLock.countDown();
                        break;
                    case CODEC_MSG_INPUT_SAMPLE:
                        Sample sample = (Sample)msg.obj;
                        //Log.v(LOG_TAG, "input sample=" + sample);
                        if (mInputSamples.offer(sample)) {
                            feedSampleToBuffer();
                        } else {
                            try {
                                mCallbacks.onError(ERROR_INPUT);
                            } catch (RemoteException e) {
                                e.printStackTrace();
                            }
                        }
                        break;
                    case CODEC_MSG_INPUT_BUFFER_AVAILABLE:
                        if (mAvailableInputBuffers.offer(msg.arg1)) {
                            feedSampleToBuffer();
                        } else {
                            try {
                                mCallbacks.onError(ERROR_INPUT);
                            } catch (RemoteException e) {
                                e.printStackTrace();
                            }
                        }
                        break;
                    default:
                        super.handleMessage(msg);
                }
            }

            private void feedSampleToBuffer() {
                while (true) {
                    if (mInputSamples.isEmpty() || mAvailableInputBuffers.isEmpty()) {
                        break;
                    }
                    int index = mAvailableInputBuffers.poll();
                    Sample sample = mInputSamples.poll();
                    //Log.v(LOG_TAG, "feed sample=" + sample + "to buffer#" + index);
                    int len = 0;
                    if (!sample.isEOS() && sample.bytes != null) {
                        len = sample.bytes.length;
                        ByteBuffer buf = mImpl.getInputBuffer(index);
                        buf.put(sample.bytes, 0, len);
                    }
                    mImpl.queueInputBuffer(index, 0, len, sample.presentationTimeUs, sample.flags);
                    try {
                        mCallbacks.onInputConsumed();
                    } catch (RemoteException e) {
                        e.printStackTrace();
                    }
                }
            }
        }

        private MediaCodec mImpl;
        private volatile ICodecCallbacks mCallbacks;

        private ImplWorker mWorker; // Do everything here!

        public void setCallbacks(ICodecCallbacks callbacks) throws RemoteException {
            mCallbacks = callbacks;
            callbacks.asBinder().linkToDeath(this, 0);
        }

        // IBinder.DeathRecipient
        @Override
        public void binderDied() {
            deinitWorker(true /* now */);
            mCallbacks = null;
        }

        @Override
        public boolean configure(FormatParam format, Surface surface, int flags) throws RemoteException {
            if (mCallbacks == null) {
                Log.e(LOG_TAG, "FAIL: callbacks must be set before calling configure().");
                return false;
            }

            if (mImpl != null) {
                Log.d(LOG_TAG, "shut down previous codec:" + mImpl.getName());
                shutdownImpl();
            }

            MediaFormat fmt = format.asFormat();
            String codecName = sCodecList.findDecoderForFormat(fmt);
            if (codecName == null) {
                Log.e(LOG_TAG, "FAIL: cannot find codec");
                return false;
            }

            try {
                mImpl = MediaCodec.createByCodecName(codecName);
            } catch (IOException e) {
                e.printStackTrace();
                Log.e(LOG_TAG, "FAIL: cannot create codec:" + codecName);
                return false;
            }

            return initWorker(new Config(fmt, surface, flags));
        }

        @Override
        public void start() throws RemoteException {
            if (!reportCodecNotReady()) {
                mWorker.post(new Runnable() {
                    public void run() { mImpl.start(); }
                });
            }
        }

        @Override
        public void stop() throws RemoteException {
            if (!reportCodecNotReady()) {
                mWorker.post(new Runnable() {
                    public void run() { mImpl.stop(); }
                });
            }
        }

        @Override
        public void flush() throws RemoteException {
            if (!reportCodecNotReady()) {
                mWorker.post(new Runnable() {
                    public void run() { mImpl.flush(); }
                });
            }
        }

        @Override
        public void reset() throws RemoteException {
            if (!reportCodecNotReady()) {
                mWorker.post(new Runnable() {
                    public void run() { mImpl.reset(); }
                });
            }
        }

        @Override
        public void release() throws RemoteException {
            if (!reportCodecNotReady()) {
                mWorker.post(new Runnable() {
                    public void run() { mImpl.release(); }
                });
            }
        }

        @Override
        public void inputSample(Sample sample) throws RemoteException {
            if (!reportCodecNotReady()) {
                Message msg = mWorker.obtainMessage(CODEC_MSG_INPUT_SAMPLE, sample);
                mWorker.sendMessage(msg);
            }
        }

        private boolean reportCodecNotReady() throws RemoteException {
            boolean notReady = mImpl == null;
            if (notReady && mCallbacks != null) {
                Log.e(LOG_TAG, "FAIL: codec not ready.");
                mCallbacks.onError(ERROR_CODEC_NOT_READY);
            }

            return notReady;
        }

        /** Use {@link MediaCodec.Callback} to process buffers asynchronously. */
        private boolean asynchronize() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.LOLLIPOP) {
                return false;
            }

            mImpl.setCallback(new MediaCodec.Callback() {
                @Override
                public void onInputBufferAvailable(@NonNull MediaCodec codec, int index) {
                    //Log.v(LOG_TAG, "available input buffer#" + index);
                    Message msg = mWorker.obtainMessage(CODEC_MSG_INPUT_BUFFER_AVAILABLE, index, 0);
                    mWorker.sendMessage(msg);
                }

                @Override
                public void onOutputBufferAvailable(@NonNull MediaCodec codec, int index, @NonNull MediaCodec.BufferInfo info) {
                    /*
                    StringBuffer str = new StringBuffer();
                    str.append("available output buffer#").append(index).append("=").
                            append("{ offset=").append(info.offset).
                            append(", size=").append(info.size).
                            append(", pts=").append(info.presentationTimeUs).
                            append(", flags=").append(info.flags).append(" }");
                    Log.v(LOG_TAG, str.toString());
                    */
                    mImpl.releaseOutputBuffer(index, true);
                    if ((info.flags & MediaCodec.BUFFER_FLAG_END_OF_STREAM) != 0) {
                        // TODO: EOS seen.
                    }
                }

                @Override
                public void onError(@NonNull MediaCodec codec, @NonNull MediaCodec.CodecException e) {
                    try {
                        mCallbacks.onError(ERROR_UNKNOWN);
                    } catch (RemoteException e1) {
                        e1.printStackTrace();
                    }
                }

                @Override
                public void onOutputFormatChanged(@NonNull MediaCodec codec, @NonNull MediaFormat format) {
                    Message msg = mWorker.obtainMessage(CODEC_MSG_REPORT_FORMAT_CHANGE, format);
                    mWorker.sendMessage(msg);
                }
            });
            return true;
        }

        private void configImpl(Config config) {
            asynchronize();
            mImpl.configure(config.format, config.surface, null, config.flags);
        }

        private void shutdownImpl() {
            mImpl.stop();
            mImpl.release();
            mImpl = null;
        }

        private synchronized boolean initWorker(Config config) {
            if (mWorker != null) {
                Log.w(LOG_TAG, "codec-worker already initialized.");
                return true;
            }

            HandlerThread thread = new HandlerThread("codec-worker");
            thread.start();
            Log.d(LOG_TAG, "start worker");
            mWorker = new ImplWorker(thread.getLooper());
            Message msg = mWorker.obtainMessage(CODEC_MSG_CONFIG, config);
            mWorker.sendMessage(msg);
            mWorker.waitForConfig();
            return mImpl != null;
        }

        private synchronized void deinitWorker(boolean now) {
            if (mWorker == null) {
                Log.w(LOG_TAG, "no initialized codec-worker.");
                return;
            }

            if (now) {
                mWorker.removeCallbacksAndMessages(null); // Clear the task queue.
            }
            mWorker.getLooper().quitSafely();

            mWorker = null;
            Log.d(LOG_TAG, "stop worker");
        }
    }
}
