/* This Source Code Form is subject to the terms of the Mozilla Public
 * License, v. 2.0. If a copy of the MPL was not distributed with this
 * file, You can obtain one at http://mozilla.org/MPL/2.0/. */

package org.mozilla.gecko.media;

import android.content.ComponentName;
import android.content.Context;
import android.content.Intent;
import android.content.ServiceConnection;
import android.media.MediaFormat;
import android.os.IBinder;
import android.os.RemoteException;
import android.util.Log;
import android.view.Surface;

import org.mozilla.gecko.GeckoAppShell;

import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

/** Proxy class of ICodec binder. */
public final class CodecProxy {
    private static final String LOG_TAG = CodecProxy.class.getSimpleName();

    private final ICodec mRemote;
    private boolean mReleased;

    private static final int ERROR_REMOTE_BASE = -1000;
    public enum Error {
        OK(0), RELEASED(-1),
        REMOTE_DEAD(ERROR_REMOTE_BASE),
        REMOTE_CODEC_NOT_READY(ERROR_REMOTE_BASE + MediaService.Codec.ERROR_CODEC_NOT_READY),
        REMOTE_INPUT(ERROR_REMOTE_BASE + MediaService.Codec.ERROR_INPUT),
        REMOTE_UNKNOWN(ERROR_REMOTE_BASE + MediaService.Codec.ERROR_UNKNOWN);

        private static Error translateRemote(int code) {
            switch (code) {
                case MediaService.Codec.ERROR_CODEC_NOT_READY:
                    return REMOTE_CODEC_NOT_READY;
                case MediaService.Codec.ERROR_INPUT:
                    return REMOTE_INPUT;
                default:
                    return REMOTE_UNKNOWN;
            }
        }

        private Error(int code) { this.code = code; }
        public final int code;
    };

    public static interface Callbacks {
        void onInputConsumed();
        void onOutputFormatChanged(MediaFormat format);
        void onOutput(Sample sample);
        void onError(Error error);
    }

    private static class CallbacksForwarder extends ICodecCallbacks.Stub
            implements IBinder.DeathRecipient {
        private final Callbacks mCallbacks;

        CallbacksForwarder(Callbacks callbacks) {
            mCallbacks = callbacks;
        }

        @Override
        public void onInputConsumed() throws RemoteException {
            mCallbacks.onInputConsumed();
        }

        @Override
        public void onOutputFormatChanged(FormatParam format) throws RemoteException {
            mCallbacks.onOutputFormatChanged(format.asFormat());
        }

        @Override
        public void onOutput(Sample sample) throws RemoteException {
            mCallbacks.onOutput(sample);
        }

        @Override
        public void onError(int error) throws RemoteException {
            mCallbacks.onError(Error.translateRemote(error));
        }

        @Override
        public void binderDied() {
            Log.e(LOG_TAG, "remote codec is dead");
            mCallbacks.onError(Error.REMOTE_DEAD);
        }
    }

    private static IMediaService sCreator;
    private static CountDownLatch sCreatorInitLatch = new CountDownLatch(1);
    private static ServiceConnection sConnection = new ServiceConnection() {
        @Override
        public void onServiceConnected(ComponentName name, IBinder service) {
            Log.d(LOG_TAG, "service connected");
            sCreator = IMediaService.Stub.asInterface(service);
            sCreatorInitLatch.countDown();
        }

        /**
         * Called when a connection to the Service has been lost.  This typically
         * happens when the process hosting the service has crashed or been killed.
         * This does <em>not</em> remove the ServiceConnection itself -- this
         * binding to the service will remain active, and you will receive a call
         * to {@link #onServiceConnected} when the Service is next running.
         *
         * @param name The concrete component name of the service whose
         *             connection has been lost.
         */
        @Override
        public void onServiceDisconnected(ComponentName name) {
            sCreator = null;
        }
    };

    public static CodecProxy create(MediaFormat format, Surface surface, Callbacks callbacks) {
        if (!ensureCreator()) {
            return null;
        }

        ICodec remote = null;
        try {
            remote = sCreator.createCodec();
            remote.setCallbacks(new CallbacksForwarder(callbacks));
            remote.configure(new FormatParam(format), surface, 0);
            remote.start();
        } catch (RemoteException e) {
            e.printStackTrace();
            return null;
        }

        CodecProxy proxy = new CodecProxy(remote);
        return proxy;
    }

    private CodecProxy(ICodec remote) {
        mRemote = remote;
    }

    private static boolean ensureCreator() {
        synchronized (sConnection) {
            if (sCreator == null) {
                Context appCtxt = GeckoAppShell.getApplicationContext();
                appCtxt.bindService(new Intent(appCtxt, MediaService.class),
                        sConnection, Context.BIND_AUTO_CREATE);
                try {
                    while (true) {
                        sCreatorInitLatch.await(1, TimeUnit.SECONDS);
                        if (sCreatorInitLatch.getCount() == 0) {
                            break;
                        }
                        Log.e(LOG_TAG, "Creator not connected in 1s. Try again.");
                    }
                } catch (InterruptedException e) {
                    e.printStackTrace();
                    appCtxt.unbindService(sConnection);
                    return false;
                }
            }
            return true;
        }
    }

    public synchronized Error input(Sample sample) {
        if (mReleased) {
            Log.e(LOG_TAG, "cannot send input to an ended codec");
            return Error.RELEASED;
        }
        try {
            mRemote.inputSample(sample);
        } catch (RemoteException e) {
            e.printStackTrace();
            Log.e(LOG_TAG, "fail to input sample:" + sample);
            return Error.REMOTE_INPUT;
        }
        return Error.OK;
    }

    public synchronized Error flush() {
        if (mReleased) {
            Log.e(LOG_TAG, "cannot flush an ended codec");
            return Error.RELEASED;
        }
        try {
            mRemote.flush();
        } catch (RemoteException e) {
            e.printStackTrace();
            return Error.REMOTE_UNKNOWN;
        }
        return Error.OK;
    }

    public synchronized Error release() {
        if (mReleased) {
            Log.d(LOG_TAG, "codec already ended");
            return Error.OK;
        }
        try {
            mRemote.stop();
            mRemote.release();
            mReleased = true;
        } catch (RemoteException e) {
            e.printStackTrace();
            return Error.REMOTE_UNKNOWN;
        }
        return Error.OK;
    }
}
