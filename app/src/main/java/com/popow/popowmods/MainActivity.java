package com.popow.popowmods;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.Toast;

import java.io.DataOutputStream;

public class MainActivity extends Activity {

    private static final String TAG = "PopowMods";

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnRestartSystemUi = findViewById(R.id.btnRestartSystemUi);
        Button btnRestartLauncher = findViewById(R.id.btnRestartLauncher);
        Button btnRestartAll = findViewById(R.id.btnRestartAll);

        if (btnRestartSystemUi != null) {
            btnRestartSystemUi.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    executeRootAsync("pkill -9 -f com.android.systemui", "System UI direstart");
                }
            });
        }

        if (btnRestartLauncher != null) {
            btnRestartLauncher.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    executeRootAsync("pkill -9 -f com.google.android.apps.nexuslauncher || pkill -9 -f com.android.launcher3", "Launcher direstart");
                }
            });
        }

        if (btnRestartAll != null) {
            btnRestartAll.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    executeRootAsync("pkill -9 -f com.android.systemui; pkill -9 -f com.google.android.apps.nexuslauncher; pkill -9 -f com.android.launcher3", "System UI & Launcher direstart");
                }
            });
        }
    }

    private void executeRootAsync(final String command, final String successMsg) {
        Toast.makeText(this, "Memproses akses root...", Toast.LENGTH_SHORT).show();
        new Thread(new Runnable() {
            @Override
            public void run() {
                boolean started = false;
                try {
                    Process process = Runtime.getRuntime().exec("su");
                    DataOutputStream os = new DataOutputStream(process.getOutputStream());
                    os.writeBytes(command + "\n");
                    os.writeBytes("exit\n");
                    os.flush();
                    os.close();
                    started = true;
                    process.waitFor();
                } catch (Exception e) {
                    Log.e(TAG, "Root execution error: ", e);
                }

                final boolean success = started;
                runOnUiThread(new Runnable() {
                    @Override
                    public void run() {
                        if (success) {
                            Toast.makeText(MainActivity.this, successMsg, Toast.LENGTH_SHORT).show();
                        } else {
                            Toast.makeText(MainActivity.this, "Gagal menjalankan su. Pastikan perangkat di-root.", Toast.LENGTH_LONG).show();
                        }
                    }
                });
            }
        }).start();
    }
}
