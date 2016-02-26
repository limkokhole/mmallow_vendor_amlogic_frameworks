package com.droidlogic.app.tv;

import android.content.Context;
import android.media.tv.TvContentRating;
import android.media.tv.TvInputManager;
import android.media.tv.TvInputService;
import android.media.tv.TvStreamConfig;
import android.media.tv.TvInputManager.Hardware;
import android.media.tv.TvInputManager.HardwareCallback;
import android.net.Uri;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.Message;
import android.util.Log;
import android.view.Surface;

import com.droidlogic.app.tv.DroidLogicTvUtils;

public abstract class TvInputBaseSession extends TvInputService.Session implements Handler.Callback {
    private static final boolean DEBUG = true;
    private static final String TAG = "TvInputBaseSession";

    private String mInputId;
    private int mDeviceId;
    private Surface mSurface;
    private Hardware mHardware;
    private TvInputManager mTvInputManager;
    private TvStreamConfig[] mConfigs;
    private boolean isTuneNotReady = false;
    private Uri mChannelUri;
    private HandlerThread mHandlerThread;
    private Handler mSessionHandler;

    protected int ACTION_FAILED = -1;
    protected int ACTION_SUCCESS = 1;

    private HardwareCallback mHardwareCallback = new HardwareCallback(){
        @Override
        public void onReleased() {
            if (DEBUG)
                Log.d(TAG, "onReleased");

            mHardware = null;
        }

        @Override
        public void onStreamConfigChanged(TvStreamConfig[] configs) {
            if (DEBUG)
                Log.d(TAG, "onStreamConfigChanged");
            mConfigs = configs;
        }
    };

    public TvInputBaseSession(Context context, String inputId, int deviceId) {
        super(context);
        mInputId = inputId;
        mDeviceId = deviceId;
        mTvInputManager = (TvInputManager)context.getSystemService(Context.TV_INPUT_SERVICE);
        mHardware = mTvInputManager.acquireTvInputHardware(deviceId,
                mHardwareCallback, mTvInputManager.getTvInputInfo(inputId));
        initThread(mInputId);
    }

    public String getInputId() {
        return mInputId;
    }

    public int getDeviceId() {
        return mDeviceId;
    }

    public Hardware getHardware() {
        return mHardware;
    }

    private void initThread(String inputId) {
        mHandlerThread = new HandlerThread(inputId);
        mHandlerThread.start();
        mSessionHandler = new Handler(mHandlerThread.getLooper(), this);
    }

    private void releaseThread() {
        if (mHandlerThread != null) {
            mHandlerThread.quit();
            mHandlerThread = null;
            mSessionHandler = null;
        }
    }

    @Override
    public boolean handleMessage(Message msg) {
        if (DEBUG)
            Log.d(TAG, "handleMessage, msg.what=" + msg.what);
        switch (msg.what) {
            case DroidLogicTvUtils.SESSION_DO_RELEASE:
                doRelease();
                break;
            case DroidLogicTvUtils.SESSION_DO_SURFACE_CHANGED:
                doSurfaceChanged((Uri)msg.obj);
                break;
            case DroidLogicTvUtils.SESSION_DO_TUNE:
                doTune((Uri)msg.obj);
                break;
            case DroidLogicTvUtils.SESSION_DO_APP_PRIVATE:
                doAppPrivateCmd((String)msg.obj, msg.getData());
                break;
            case DroidLogicTvUtils.SESSION_UNBLOCK_CONTENT:
                doUnblockContent((TvContentRating)msg.obj);
                break;
            default:
                break;
        }
        return false;
    }

    public void doRelease() {
        Log.d(TAG, "doRelease");
        if (mHardware != null) {
            mHardware.setSurface(null, null);
            mTvInputManager.releaseTvInputHardware(mDeviceId, mHardware);
        }
        releaseThread();
    }

    public int doTune(Uri uri) {
        if (mHardware != null) {
            if (mSurface != null && mSurface.isValid()) {
                mHardware.setSurface(mSurface, mConfigs[0]);
                return ACTION_SUCCESS;
            } else {
                Log.d(TAG, "doTune fail for invalid surface "+ mSurface);
            }
        }
        return ACTION_FAILED;
    }

    public void doAppPrivateCmd(String action, Bundle bundle) {
        //do something
    }

    public int doSurfaceChanged(Uri uri) {
        if (mHardware != null) {
            if (mSurface != null && mSurface.isValid()) {
                mHardware.setSurface(mSurface, mConfigs[0]);
                return ACTION_SUCCESS;
            } else {
                Log.d(TAG, "SurfaceChanged to invalid native obj! Should we need to stop tv?");
            }
        }
        return ACTION_FAILED;
    }

    public void doUnblockContent(TvContentRating rating) {}

    public int stopTvPlay() {
        if (mHardware != null) {
            mHardware.setSurface(null, null);
            return ACTION_SUCCESS;
        }
        return ACTION_FAILED;
    }

    @Override
    public void onRelease() {
        if (mSessionHandler == null)
            return;
        mSessionHandler.obtainMessage(DroidLogicTvUtils.SESSION_DO_RELEASE).sendToTarget();
    }

    @Override
    public boolean onSetSurface(Surface surface) {
        if (DEBUG)
            Log.d(TAG, "onSetSurface "+ surface);
        if (mSurface != null && surface == null) {//TvView destroyed, or session need release
            stopTvPlay();
        } else if (mSurface == null && surface == null) {
            Log.d(TAG, "surface has been released.");
        } else {
            if (!surface.isValid()) {
                Log.d(TAG, "onSetSurface get invalid surface");
            }
        }

        mSurface = surface;
        return false;
    }

    @Override
    public void onSurfaceChanged(int format, int width, int height) {
        if (mSessionHandler == null || mChannelUri == null) {
            if (DEBUG)
                Log.d(TAG, "onsurfaceChanged mChannelUri=" + mChannelUri);
            return;
        }

        mSessionHandler.obtainMessage(
                DroidLogicTvUtils.SESSION_DO_SURFACE_CHANGED, mChannelUri).sendToTarget();
    }

    @Override
    public void onSetStreamVolume(float volume) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onAppPrivateCommand(String action, Bundle data) {
        if (DEBUG)
            Log.d(TAG, "onAppPrivateCommand");
        if (mSessionHandler == null)
            return;
        Message msg = mSessionHandler.obtainMessage(
                DroidLogicTvUtils.SESSION_DO_APP_PRIVATE);
        msg.setData(data);
        msg.obj = action;
        msg.sendToTarget();
    }

    @Override
    public boolean onTune(Uri channelUri) {
        if (DEBUG)
            Log.d(TAG, "onTune, channelUri=" + channelUri);
        mChannelUri = channelUri;
        if (mSurface != null && mSurface.isValid()) {//TvView is not ready
            if (mSessionHandler != null) {
                mSessionHandler.obtainMessage(
                        DroidLogicTvUtils.SESSION_DO_TUNE, mChannelUri).sendToTarget();
            }
        } else {
            if (DEBUG)
                Log.d(TAG, "onTune  with invalid surface "+ mSurface);
        }

        return false;
    }

    @Override
    public void onSetCaptionEnabled(boolean enabled) {
        // TODO Auto-generated method stub
    }

    @Override
    public void onUnblockContent(TvContentRating unblockedRating) {
        if (DEBUG)
            Log.d(TAG, "onUnblockContent");
        if (mSessionHandler == null)
            return;
        mSessionHandler.obtainMessage(
                DroidLogicTvUtils.SESSION_UNBLOCK_CONTENT, unblockedRating).sendToTarget();
    }

}
