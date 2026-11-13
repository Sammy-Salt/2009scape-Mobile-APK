package net.kdt.pojavlaunch;

import android.annotation.SuppressLint;
import android.content.ContentUris;
import android.content.ContentValues;
import android.content.Intent;
import android.database.Cursor;
import android.net.Uri;
import android.os.Build;
import android.os.Bundle;
import android.os.Environment;
import android.provider.MediaStore;
import android.util.Log;
import android.widget.Button;
import android.widget.TextView;
import android.widget.Toast;

import java.io.File;
import java.io.FileInputStream;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;

import com.kdt.mcgui.ProgressLayout;

import net.kdt.pojavlaunch.progresskeeper.ProgressKeeper;
import net.kdt.pojavlaunch.services.ProgressServiceKeeper;

public class ScapeLauncher extends BaseActivity {

    private TextView settings;
    private Button playHD;
    private Button playSD;
    private Button exportLog;
    private static final int FILE_SELECT_CODE_JSON = 0;
    private static final int FILE_SELECT_CODE_ZIP = 1;
    private ProgressServiceKeeper mProgressServiceKeeper;
    private ProgressLayout mProgressLayout;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_dummy_launcher);
        settings = findViewById(R.id.settings);
        playHD = findViewById(R.id.playHD);
        playSD = findViewById(R.id.playSD);
        exportLog = findViewById(R.id.exportLog);
        mProgressLayout = findViewById(R.id.progress_layout);

        ProgressKeeper.addTaskCountListener((mProgressServiceKeeper = new ProgressServiceKeeper(this)));
        ProgressKeeper.addTaskCountListener(mProgressLayout);

        mProgressLayout.observe(ProgressLayout.UNPACK_RUNTIME);
        mProgressLayout.observe(ProgressLayout.INSTALL_MODPACK);

        playHD.setOnClickListener(view -> {
            if(!runtimeReady()) return;
            Intent intent = new Intent(ScapeLauncher.this, MainActivity.class);
            startActivity(intent);
        });

        playSD.setOnClickListener(view -> {
            if(!runtimeReady()) return;
            // SD uses the same GLFW/MainActivity path as HD (JavaGUI has no native
            // window surface, so LWJGL pojavInit null-derefs). Apply a low-end
            // resolution preset via intent extra.
            Intent intent = new Intent(ScapeLauncher.this, MainActivity.class);
            intent.putExtra("low_end_preset", true);
            startActivity(intent);
        });
        settings.setOnClickListener(view -> showBottomDialog());
        exportLog.setOnClickListener(view -> exportLatestLog());
    }



    /**
     * Exports the combined launcher and native EGL diagnostics to a unique
     * text file in Downloads. Native EGL diagnostics append to latestlog.txt.
     */
    private void exportLatestLog() {
        File source = new File(Tools.DIR_GAME_HOME, "latestlog.txt");
        if (!source.isFile() || !source.canRead()) {
            Toast.makeText(this, "No readable diagnostic log exists yet. Launch the game first.",
                    Toast.LENGTH_LONG).show();
            return;
        }
        if (source.length() == 0) {
            Toast.makeText(this, "The diagnostic log is empty. Launch the game first.",
                    Toast.LENGTH_LONG).show();
            return;
        }

        String timestamp = new java.text.SimpleDateFormat(
                "yyyyMMdd-HHmmss", java.util.Locale.US).format(new java.util.Date());
        String fileName = "2009scape-diagnostics-" + timestamp + ".txt";

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.content.ContentResolver resolver = getContentResolver();
                Uri downloads = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                ContentValues values = new ContentValues();
                values.put(MediaStore.MediaColumns.DISPLAY_NAME, fileName);
                values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                values.put(MediaStore.MediaColumns.RELATIVE_PATH,
                        Environment.DIRECTORY_DOWNLOADS + "/");
                values.put(MediaStore.MediaColumns.IS_PENDING, 1);

                Uri destination = resolver.insert(downloads, values);
                if (destination == null) {
                    throw new IOException("Android could not create the Downloads file");
                }
                try {
                    copyDiagnosticLog(source, resolver.openOutputStream(destination, "w"));
                    ContentValues ready = new ContentValues();
                    ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    if (resolver.update(destination, ready, null, null) != 1) {
                        throw new IOException("Android could not finalize the Downloads file");
                    }
                } catch (Exception e) {
                    resolver.delete(destination, null, null);
                    throw e;
                }
            } else {
                File downloads = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                if (!downloads.isDirectory() && !downloads.mkdirs()) {
                    throw new IOException("Could not create the Downloads folder");
                }
                copyDiagnosticLog(source, new FileOutputStream(new File(downloads, fileName), false));
            }

            Toast.makeText(this, "Exported " + fileName + " to Downloads",
                    Toast.LENGTH_LONG).show();
            Log.i("ScapeLauncher", "Exported diagnostic log: " + fileName
                    + " (" + source.length() + " bytes)");
        } catch (Exception e) {
            Log.e("ScapeLauncher", "Failed to export diagnostic log", e);
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            Toast.makeText(this, "Log export failed: " + detail, Toast.LENGTH_LONG).show();
        }
    }

    private static void copyDiagnosticLog(File source, OutputStream output) throws IOException {
        if (output == null) {
            throw new IOException("Android could not open the Downloads file");
        }
        try (InputStream input = new FileInputStream(source);
             OutputStream destination = output) {
            byte[] buffer = new byte[8192];
            int count;
            while ((count = input.read(buffer)) != -1) {
                destination.write(buffer, 0, count);
            }
            destination.flush();
        }
    }

    private boolean runtimeReady(){
        if(mProgressLayout.hasProcesses()){
            Toast.makeText(this, R.string.tasks_ongoing, Toast.LENGTH_LONG).show();
            return false;
        }
        return true;
    }

    @SuppressLint("ClickableViewAccessibility")
    private void showBottomDialog() {
        MyDialogFragment dialog = new MyDialogFragment();
        dialog.show(getSupportFragmentManager(), "tag");
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        ProgressKeeper.removeTaskCountListener(mProgressServiceKeeper);
    }
}
