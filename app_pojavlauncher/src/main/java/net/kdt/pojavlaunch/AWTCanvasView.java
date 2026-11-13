package net.kdt.pojavlaunch;

import android.content.*;
import android.graphics.*;
import android.text.*;
import android.util.*;
import android.view.*;

import java.util.*;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import net.kdt.pojavlaunch.Tools;
import net.kdt.pojavlaunch.utils.*;

public class AWTCanvasView extends TextureView implements TextureView.SurfaceTextureListener, Runnable {
    public static final int AWT_CANVAS_WIDTH = 765;
    public static final int AWT_CANVAS_HEIGHT = 503;
    private static final double NANOS = 1000000000.0;
    private boolean mIsDestroyed = false;
    private final LinkedList<Long> mTimes = new LinkedList<Long>(){{add(System.nanoTime());}};

    // The Java runtime must not start until its Android window is registered with the native EGL bridge.
    private final CountDownLatch mBridgeWindowReady = new CountDownLatch(1);
    private volatile Throwable mBridgeWindowError;

    public AWTCanvasView(Context ctx) {
        this(ctx, null);
    }

    public AWTCanvasView(Context ctx, AttributeSet attrs) {
        super(ctx, attrs);
        setSurfaceTextureListener(this);
        post(this::refreshSize);
    }

    @Override
    public void onSurfaceTextureAvailable(SurfaceTexture texture, int w, int h) {
        getSurfaceTexture().setDefaultBufferSize(AWT_CANVAS_WIDTH, AWT_CANVAS_HEIGHT);
        mIsDestroyed = false;

        // Match the GLFW surface path: register the Surface before the JVM can call pojavInit().
        Surface bridgeSurface = new Surface(texture);
        try {
            JREUtils.setupBridgeWindow(bridgeSurface);
            Log.i("AWTCanvasView", "Native bridge window initialized");
        } catch (Throwable throwable) {
            mBridgeWindowError = throwable;
            Log.e("AWTCanvasView", "Failed to initialize native bridge window", throwable);
        } finally {
            bridgeSurface.release();
            mBridgeWindowReady.countDown();
        }

        new Thread(this, "AndroidAWTRenderer").start();
    }

    /** Wait until the native renderer has a valid Android window to acquire. */
    public void awaitBridgeWindowReady() throws InterruptedException {
        if (!mBridgeWindowReady.await(30, TimeUnit.SECONDS)) {
            throw new IllegalStateException("Timed out waiting for the Android rendering surface");
        }
        if (mBridgeWindowError != null) {
            throw new IllegalStateException("Native EGL bridge window initialization failed", mBridgeWindowError);
        }
    }

    @Override
    public boolean onSurfaceTextureDestroyed(SurfaceTexture texture) {
        mIsDestroyed = true;
        return true;
    }

    @Override
    public void onSurfaceTextureSizeChanged(SurfaceTexture texture, int w, int h) {
        getSurfaceTexture().setDefaultBufferSize(AWT_CANVAS_WIDTH, AWT_CANVAS_HEIGHT);
    }

    @Override
    public void onSurfaceTextureUpdated(SurfaceTexture texture) {
        getSurfaceTexture().setDefaultBufferSize(AWT_CANVAS_WIDTH, AWT_CANVAS_HEIGHT);
    }

    @Override
    public void run() {
        Canvas canvas;
        Surface surface = new Surface(getSurfaceTexture());
        Bitmap rgbArrayBitmap = Bitmap.createBitmap(AWT_CANVAS_WIDTH, AWT_CANVAS_HEIGHT, Bitmap.Config.ARGB_8888);
        Paint paint = new Paint();
        paint.setAntiAlias(false);
        paint.setDither(false);
        paint.setFilterBitmap(false);
        long frameEndNanos;
        long frameStartNanos;
        long sleepTime;
        long sleepMillis;
        int sleepNanos;
        int[] rgbArray;
        // define the frame rate limit
        final long frameTimeNanos = (long)(NANOS / 60); // Targeting 60 FPS
        long frameDuration;

        try {
            while (!mIsDestroyed && surface.isValid()) {
                frameStartNanos = System.nanoTime();
                canvas = surface.lockCanvas(null);
                canvas.drawRGB(0, 0, 0);
                rgbArray = JREUtils.renderAWTScreenFrame();
                if (rgbArray != null) {
                    canvas.save();
                    rgbArrayBitmap.setPixels(rgbArray, 0, AWT_CANVAS_WIDTH, 0, 0, AWT_CANVAS_WIDTH, AWT_CANVAS_HEIGHT);
                    canvas.drawBitmap(rgbArrayBitmap, 0, 0, paint);
                    canvas.restore();
                }
                surface.unlockCanvasAndPost(canvas);

                // frame rate limiting
                frameEndNanos = System.nanoTime();
                frameDuration = frameEndNanos - frameStartNanos;
                if (frameDuration < frameTimeNanos) {
                    sleepTime = frameTimeNanos - frameDuration;
                    sleepMillis = sleepTime / 1000000;
                    sleepNanos = (int)(sleepTime - sleepMillis * 1000000);
                    try {
                        Thread.sleep(sleepMillis, sleepNanos);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    }
                }
            }
        } catch (Throwable throwable) {
            Tools.showError(getContext(), throwable);
        }
        rgbArrayBitmap.recycle();
        surface.release();
    }

    /** Make the view fit the proper aspect ratio of the surface */
    private void refreshSize(){
        ViewGroup.LayoutParams layoutParams = getLayoutParams();


        /** Note: In the future this is a good way to stretch the aspect ratio too. Like for
         * 16:9 widescreen in SD mode like mudkip osrs videos */
        if(getHeight() < getWidth()){
            layoutParams.width = AWT_CANVAS_WIDTH * getHeight() / AWT_CANVAS_HEIGHT;
        }else{
            layoutParams.height = AWT_CANVAS_HEIGHT * getWidth() / AWT_CANVAS_WIDTH;
        }

        setLayoutParams(layoutParams);
    }

}
