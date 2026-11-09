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
     * Copies the game's latestlog.txt into the public Downloads folder.
     * MediaStore is used on Android 10+ so this works with scoped storage.
     */
    private void exportLatestLog() {
        File source = new File(Tools.DIR_GAME_HOME, "latestlog.txt");
        if (!source.isFile()) {
            Toast.makeText(this, "No latestlog.txt file was found yet.", Toast.LENGTH_LONG).show();
            return;
        }

        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                android.content.ContentResolver resolver = getContentResolver();
                Uri downloads = MediaStore.Downloads.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY);
                String relativePath = Environment.DIRECTORY_DOWNLOADS + "/";
                Uri destination = null;

                String selection = MediaStore.MediaColumns.DISPLAY_NAME + "=? AND "
                        + MediaStore.MediaColumns.RELATIVE_PATH + "=?";
                String[] selectionArgs = {"latestlog.txt", relativePath};
                try (Cursor cursor = resolver.query(downloads,
                        new String[]{MediaStore.MediaColumns._ID},
                        selection, selectionArgs, null)) {
                    if (cursor != null && cursor.moveToFirst()) {
                        destination = ContentUris.withAppendedId(
                                downloads, cursor.getLong(0));
                    }
                }

                boolean created = false;
                if (destination == null) {
                    ContentValues values = new ContentValues();
                    values.put(MediaStore.MediaColumns.DISPLAY_NAME, "latestlog.txt");
                    values.put(MediaStore.MediaColumns.MIME_TYPE, "text/plain");
                    values.put(MediaStore.MediaColumns.RELATIVE_PATH, relativePath);
                    values.put(MediaStore.MediaColumns.IS_PENDING, 1);
                    destination = resolver.insert(downloads, values);
                    if (destination == null) {
                        throw new IOException("Android could not create the Downloads file");
                    }
                    created = true;
                }

                try (InputStream input = new FileInputStream(source);
                     OutputStream output = resolver.openOutputStream(destination, "wt")) {
                    if (output == null) {
                        throw new IOException("Android could not open the Downloads file");
                    }
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                    }
                    output.flush();
                } catch (IOException e) {
                    if (created) resolver.delete(destination, null, null);
                    throw e;
                }

                if (created) {
                    ContentValues ready = new ContentValues();
                    ready.put(MediaStore.MediaColumns.IS_PENDING, 0);
                    resolver.update(destination, ready, null, null);
                }
            } else {
                File downloads = Environment.getExternalStoragePublicDirectory(
                        Environment.DIRECTORY_DOWNLOADS);
                if (!downloads.isDirectory() && !downloads.mkdirs()) {
                    throw new IOException("Could not create the Downloads folder");
                }
                File destination = new File(downloads, "latestlog.txt");
                try (InputStream input = new FileInputStream(source);
                     OutputStream output = new FileOutputStream(destination, false)) {
                    byte[] buffer = new byte[8192];
                    int count;
                    while ((count = input.read(buffer)) != -1) {
                        output.write(buffer, 0, count);
                    }
                    output.flush();
                }
            }

            Toast.makeText(this,
                    "Exported latestlog.txt to Internal storage/Download",
                    Toast.LENGTH_LONG).show();
        } catch (Exception e) {
            Log.e("ScapeLauncher", "Failed to export latestlog.txt", e);
            String detail = e.getMessage() == null ? e.getClass().getSimpleName() : e.getMessage();
            Toast.makeText(this, "Log export failed: " + detail, Toast.LENGTH_LONG).show();
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
