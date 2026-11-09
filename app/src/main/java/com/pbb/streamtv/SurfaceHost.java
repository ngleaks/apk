package com.pbb.streamtv;

import android.content.Context;
import android.graphics.Color;
import android.view.SurfaceHolder;
import android.view.SurfaceView;

/** A SurfaceView that reports its Surface to the engine, letterboxed on a black background. */
final class SurfaceHost extends SurfaceView implements SurfaceHolder.Callback {

    interface Listener {
        void onSurfaceReady(android.view.Surface surface);

        void onSurfaceGone();
    }

    private Listener listener;

    SurfaceHost(Context context) {
        super(context);
        setBackgroundColor(Color.BLACK);
        getHolder().addCallback(this);
        getHolder().setFormat(android.graphics.PixelFormat.RGBX_8888);
    }

    void setListener(Listener listener) {
        this.listener = listener;
    }

    @Override
    public void surfaceCreated(SurfaceHolder holder) {
        if (listener != null) {
            listener.onSurfaceReady(holder.getSurface());
        }
    }

    @Override
    public void surfaceChanged(SurfaceHolder holder, int format, int width, int height) {
        if (listener != null) {
            listener.onSurfaceReady(holder.getSurface());
        }
    }

    @Override
    public void surfaceDestroyed(SurfaceHolder holder) {
        if (listener != null) {
            listener.onSurfaceGone();
        }
    }
}
