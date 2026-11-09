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
            // Force log to file for debug export
            Log.i("JavaGUILauncher", "onCreate started");
        } catch (IOException e) {
            Log.e("JavaGUILauncher", "Failed to create log file", e);
        }

        mTextureView = findViewById(R.id.install_texture_view);
        mLoggerView = findViewById(R.id.launcherLoggerView);
        mTouchCharInput = findViewById(R.id.awt_touch_char);
        mTouchCharInput.setCharacterSender(new AwtCharSender());

        mTouchPad = findViewById(R.id.main_touchpad);
        mMousePointerImageView = findViewById(R.id.main_mouse_pointer);

        mGestureDetector = new GestureDetector(this, new SingleTapConfirm());

        longPressDetector = new GestureDetector(this, new GestureDetector.SimpleOnGestureListener() {
            @Override
            public void onLongPress(MotionEvent e) {
                if (cameraMode) return;
                if (System.currentTimeMillis() - lastPress < 500) return;
                lastPress = System.currentTimeMillis();
                // Right click
                CallbackBridge.sendMouseButton(CallbackBridge.MOUSE_RIGHT, true);
                CallbackBridge.sendMouseButton(CallbackBridge.MOUSE_RIGHT, false);
            }
        });

        scaleGestureDetector = new ScaleGestureDetector(this, new ScaleGestureDetector.SimpleOnScaleGestureListener() {
            @Override
            public boolean onScale(ScaleGestureDetector detector) {
                // Zoom not used in AWT path
                return true;
            }
        });

        View.OnTouchListener touchListener = this;
        mTextureView.setOnTouchListener(touchListener);
        mTouchPad.setOnTouchListener(touchListener);

        findViewById(R.id.mouseModeIco).setOnClickListener(v -> toggleVirtualMouse());
        findViewById(R.id.keyboardToggle).setOnClickListener(this::toggleKeyboard);
        findViewById(R.id.openLogOutput).setOnClickListener(this::openLogOutput);

        getOnBackPressedDispatcher().addCallback(this, new OnBackPressedCallback(true) {
            @Override
            public void handleOnBackPressed() {
                fullyExit();
            }
        });

        // Launch runtime
        new Thread(() -> {
            try {
                Runtime runtime = MultiRTUtils.forceReread("Internal");
                int code = launchJavaRuntime(runtime, null);
                Log.i("JavaGUILauncher", "launchJavaRuntime returned " + code);
                if (code != 0) {
                    runOnUiThread(() -> Tools.showError(JavaGUILauncherActivity.this, new RuntimeException("Java process exited with code " + code), true));
                }
            } catch (Throwable t) {
                Log.e("JavaGUILauncher", "launch failed", t);
                runOnUiThread(() -> Tools.showError(JavaGUILauncherActivity.this, t, true));
            }
        }, "JavaGUI-Launch").start();
    }

    @Override
    public boolean onTouch(View v, MotionEvent e) {
        // Simplified touch handling for AWT canvas
        float x = e.getX();
        float y = e.getY();

        if (v == mTouchPad && mIsVirtualMouseEnabled) {
            // Virtual mouse mode
            if (e.getAction() == MotionEvent.ACTION_DOWN) {
                prevX = x;
                prevY = y;
            } else if (e.getAction() == MotionEvent.ACTION_MOVE) {
                float dx = x - prevX;
                float dy = y - prevY;
                prevX = x;
                prevY = y;
                CallbackBridge.mouseX += dx;
                CallbackBridge.mouseY += dy;
                CallbackBridge.sendCursorPos(CallbackBridge.mouseX, CallbackBridge.mouseY);
            }
            return true;
        }

        // Map to AWT canvas coordinates
        int awtX = (int) MathUtils.map(x, 0, v.getWidth(), 0, AWTCanvasView.AWT_CANVAS_WIDTH);
        int awtY = (int) MathUtils.map(y, 0, v.getHeight(), 0, AWTCanvasView.AWT_CANVAS_HEIGHT);

        switch (e.getActionMasked()) {
            case MotionEvent.ACTION_DOWN:
                CallbackBridge.sendMouseButton(CallbackBridge.MOUSE_LEFT, true);
                CallbackBridge.sendCursorPos(awtX, awtY);
                break;
            case MotionEvent.ACTION_MOVE:
                CallbackBridge.sendCursorPos(awtX, awtY);
                break;
            case MotionEvent.ACTION_UP:
            case MotionEvent.ACTION_CANCEL:
                CallbackBridge.sendMouseButton(CallbackBridge.MOUSE_LEFT, false);
                break;
        }
        return true;
    }

    private void sendScaledMousePos(float x, float y) {
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
        if (!mIsVirtualMouseEnabled) {
            view.setImageResource(R.drawable.touch);
        } else {
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
            Tools.getCacioJavaArgs(javaArgList, runtime.javaVersion == 8);
            javaArgList.add("-DconfigFile=" + Tools.DIR_DATA + "/config.json");
            javaArgList.add("-DpluginDir=" + Tools.DIR_DATA + "/plugins/");
            javaArgList.add("-DclientHomeOverride=" + gamedir);
            javaArgList.add("-Dorg.lwjgl.librarypath=" + Tools.NATIVE_LIB_DIR);
            javaArgList.add("-Dorg.lwjgl.util.NoChecks=true");
            // Prefer classpath over -jar so LWJGL 3.2.3 Android classes win
            if (Tools.LOCAL_RENDERER == null) {
                Tools.LOCAL_RENDERER = LauncherPreferences.PREF_RENDERER;
            }
            javaArgList.add("-cp");
            javaArgList.add(Tools.getLWJGL3ClassPathPublic() + ":" + Tools.DIR_DATA + "/rt4.jar");
            javaArgList.add(Tools.RT4_MAIN_CLASS);

            Logger.appendToLog("Info: Java arguments: " + Arrays.toString(javaArgList.toArray(new String[0])));

            return JREUtils.launchJavaVM(this, runtime, gamedir, javaArgList, LauncherPreferences.PREF_CUSTOM_JAVA_ARGS);
        } catch (Throwable th) {
            Tools.showError(this, th, true);
            return -1;
        }
    }

    public void toggleKeyboard(View view) {
        mTouchCharInput.switchKeyboardState();
    }
}
