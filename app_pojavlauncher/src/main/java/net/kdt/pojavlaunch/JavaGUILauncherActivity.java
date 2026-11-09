package net.kdt.pojavlaunch;

import static net.kdt.pojavlaunch.MainActivity.fullyExit;
import static net.kdt.pojavlaunch.Tools.currentDisplayMetrics;

import static java.security.AccessController.getContext;

import android.annotation.SuppressLint;
import android.content.ClipboardManager;
import android.graphics.Color;
import android.os.Bundle;
import android.util.Log;
import android.view.GestureDetector;
import android.view.KeyEvent;
import android.view.MotionEvent;
import android.view.ScaleGestureDetector;
import android.view.View;
import android.view.ViewGroup;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.Toast;

import androidx.activity.OnBackPressedCallback;

import com.kdt.LoggerView;

import net.kdt.pojavlaunch.customcontrols.keyboard.AwtCharSender;
import net.kdt.pojavlaunch.customcontrols.keyboard.TouchCharInput;
import net.kdt.pojavlaunch.multirt.MultiRTUtils;
import net.kdt.pojavlaunch.multirt.Runtime;
import net.kdt.pojavlaunch.prefs.LauncherPreferences;
import net.kdt.pojavlaunch.utils.JREUtils;
import net.kdt.pojavlaunch.utils.KeyEncoder;
import net.kdt.pojavlaunch.utils.MathUtils;

import org.lwjgl.glfw.CallbackBridge;

import java.io.File;
import java.io.IOException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;

public class JavaGUILauncherActivity extends BaseActivity implements View.OnTouchListener {

    private AWTCanvasView mTextureView;
    private LoggerView mLoggerView;
    private TouchCharInput mTouchCharInput;

    private LinearLayout mTouchPad;
    private ImageView mMousePointerImageView;
    private GestureDetector mGestureDetector;

    private GestureDetector longPressDetector;
    private boolean cameraMode = false;
    float prevX = 0, prevY = 0;
    private long lastPress = 0;
    private ScaleGestureDetector scaleGestureDetector;
    private boolean rcState = false;
    private boolean mSkipDetectMod;
    private static boolean mIsVirtualMouseEnabled;

    @SuppressLint("ClickableViewAccessibility")
    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_java_gui_launcher);

        try {
            File latestLogFile = new File(Tools.DIR_GAME_HOME, "latestlog.txt");
            if (!latestLogFile.exists() && !latestLogFile.createNewFile())
                throw new IOException("Failed to create a new log file");
            Logger.begin(latestLogFile.getAbsolutePath());
            // force the log file to be utf-8
            System.setProperty("file.encoding", "UTF-8");
        } catch (IOException e) {
            Tools.showError(this, e, true);
        }

        mTouchCharInput = findViewById(R.id.awt_touch_char);
        mTouchCharInput.setCharacterSender(new AwtCharSender());

        mTextureView = findViewById(R.id.installmod_surfaceview);
        mLoggerView = findViewById(R.id.launcherLoggerView);

        mTouchPad = findViewById(R.id.main_touchpad);
        mMousePointerImageView = findViewById(R.id.main_mouse_pointer);
        mIsVirtualMouseEnabled = true;
        mTouchPad.setVisibility(View.VISIBLE);

        View.OnTouchListener touchListener = this;
        mTouchPad.setOnTouchListener(touchListener);
        mTextureView.setOnTouchListener(touchListener);

        // Gesture detectors for camera / right click
        mGestureDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public boolean onSingleTapConfirmed(MotionEvent e) {
                if (!mIsVirtualMouseEnabled) return false;
                CallbackBridge.sendMouseButton(CallbackBridge.MOUSE_LEFT, true);
                CallbackBridge.sendMouseButton(CallbackBridge.MOUSE_LEFT, false);
                return true;
            }

            @Override
            public boolean onDoubleTap(MotionEvent e) {
                return false;
            }
        });

        longPressDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(MotionEvent e) {
                if (!mIsVirtualMouseEnabled) return;
                CallbackBridge.sendMouseButton(CallbackBridge.MOUSE_RIGHT, true);
                CallbackBridge.sendMouseButton(CallbackBridge.MOUSE_RIGHT, false);
            }
        });

        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                return false;
            }
        });

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                fullyExit();
            }
        });

        // Start the Java runtime in background
        new Thread(() -> {
            try {
                Runtime runtime = MultiRTUtils.forceReread("Internal");
                launchJavaRuntime(runtime, null);
            } catch (Throwable th) {
                Tools.showError(JavaGUILauncherActivity.this, th, true);
            }
        }, "JREMainThread").start();
    }

    @Override
    public boolean onTouch(View v, MotionEvent e) {
        // Simplified touch handling for AWT / virtual mouse
        if (mIsVirtualMouseEnabled && v == mTouchPad) {
            float x = e.getX();
            float y = e.getY();
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                prevX = x;
                prevY = y;
            } else if (e.getAction() == MotionEvent.ACTION_MOVE) {
                float dx = x - prevX;
                float dy = y - prevY;
                CallbackBridge.mouseX += dx;
                CallbackBridge.mouseY += dy;
                prevX = x;
                prevY = y;
                CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
            }
            mGestureDetector.onTouchEvent(e);
            longPressDetector.onTouchEvent(e);
            return true;
        }
        // Map texture view coordinates to AWT canvas
        if (v == mTextureView) {
            sendScaledMousePosition(e.getX(), e.getY());
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                CallbackBridge.sendMouseButton(CallbackBridge.MOUSE_LEFT, true);
            } else if (e.getAction() == MotionEvent.ACTION_UP || e.getAction() == MotionEvent.ACTION_CANCEL) {
                CallbackBridge.sendMouseButton(CallbackBridge.MOUSE_LEFT, false);
            }
            return true;
        }
        return false;
    }

    private void sendScaledMousePosition(float x, float y) {
        CallbackBridge.sendCursorPos(
                (int) MathUtils.map(x, mTextureView.getX(), mTextureView.getX() + mTextureView.getWidth(), 0, AWTCanvasView.AWT_CANVAS_WIDTH),
                (int) MathUtils.map(y, mTextureView.getY(), mTextureView.getY() + mTextureView.getHeight(), 0, AWTCanvasView.AWT_CANVAS_HEIGHT)
                );
    }

    public void openLogOutput(View v) {
        mLoggerView.setVisibility(View.VISIBLE);
    }

    public void toggleVirtualMouse() {
        mIsVirtualMouseEnabled = !mIsVirtualMouseEnabled;
        ImageView view = findViewById(R.id.mouseModeIco);
        if(!mIsVirtualMouseEnabled){
            view.setImageResource(R.drawable.touch);
        } else{
            view.setImageResource(R.drawable.ic_mouse3);
        }
        mTouchPad.setVisibility(mIsVirtualMouseEnabled ? View.VISIBLE : View.GONE);
        Toast.makeText(this,
                mIsVirtualMouseEnabled ? R.string.control_mouseon : R.string.control_mouseoff,
                Toast.LENGTH_SHORT).show();
    }

    public int launchJavaRuntime(Runtime runtime, String javaArgs) {
        runtime = MultiRTUtils.forceReread("Internal");
        JREUtils.redirectAndPrintJRELog();
        try {
            List<String> javaArgList = new ArrayList<>();
            File gamedir = new File(Tools.DIR_DATA);

            // Enable Caciocavallo
            Tools.getCacioJavaArgs(javaArgList,runtime.javaVersion == 8);
            javaArgList.add("-DconfigFile="+Tools.DIR_DATA + "/config.json");
            javaArgList.add("-DpluginDir="+ Tools.DIR_DATA + "/plugins/");
            javaArgList.add("-DclientHomeOverride="+gamedir);
            javaArgList.add("-Dorg.lwjgl.librarypath=" + Tools.NATIVE_LIB_DIR);
            javaArgList.add("-Dorg.lwjgl.util.NoChecks=true");
            // Prefer classpath over -jar so LWJGL 3.2.3 Android classes win
            if (Tools.LOCAL_RENDERER == null) {
                Tools.LOCAL_RENDERER = LauncherPreferences.PREF_RENDERER;
            }
            javaArgList.add("-cp");
            javaArgList.add(Tools.getLWJGL3ClassPathPublic()+":"+Tools.DIR_DATA+"/rt4.jar");
            javaArgList.add(Tools.RT4_MAIN_CLASS);

            Logger.appendToLog("Info: Java arguments: " + Arrays.toString(javaArgList.toArray(new String[0])));

            return JREUtils.launchJavaVM(this, runtime,gamedir,javaArgList, LauncherPreferences.PREF_CUSTOM_JAVA_ARGS);
        } catch (Throwable th) {
            Tools.showError(this, th, true);
            return -1;
        }
    }
    public void toggleKeyboard(View view) {
        mTouchCharInput.switchKeyboardState();
    }
}
