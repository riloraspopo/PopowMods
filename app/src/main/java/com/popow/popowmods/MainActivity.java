package com.popow.popowmods;

import android.app.Activity;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import java.io.DataOutputStream;

public class MainActivity extends Activity {

    private static final String TAG = "PopowMods";
    private static final String PREFS_NAME = "popow_mods_settings";
    private static final String PREF_NETWORK_INTERVAL_SEC = "network_interval_sec";
    private static final String PROP_NETWORK_INTERVAL_SEC = "persist.popowmods.net_traffic_interval_sec";
    private static final int MIN_INTERVAL_SEC = 1;
    private static final int MAX_INTERVAL_SEC = 60;
    private static final int DEFAULT_INTERVAL_SEC = 2;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnRestartSystemUi = findViewById(R.id.btnRestartSystemUi);
        Button btnRestartLauncher = findViewById(R.id.btnRestartLauncher);
        Button btnRestartAll = findViewById(R.id.btnRestartAll);
        EditText etNetworkInterval = findViewById(R.id.etNetworkInterval);
        Button btnSaveNetworkInterval = findViewById(R.id.btnSaveNetworkInterval);

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

        final int savedInterval = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .getInt(PREF_NETWORK_INTERVAL_SEC, DEFAULT_INTERVAL_SEC);
        if (etNetworkInterval != null) {
            etNetworkInterval.setText(String.valueOf(savedInterval));
        }

        if (btnSaveNetworkInterval != null && etNetworkInterval != null) {
            btnSaveNetworkInterval.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String input = etNetworkInterval.getText().toString().trim();
                    if (input.isEmpty()) {
                        Toast.makeText(MainActivity.this, "Interval wajib diisi.", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    int interval;
                    try {
                        interval = Integer.parseInt(input);
                    } catch (NumberFormatException e) {
                        Toast.makeText(MainActivity.this, "Interval harus berupa angka.", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (interval < MIN_INTERVAL_SEC || interval > MAX_INTERVAL_SEC) {
                        Toast.makeText(
                                MainActivity.this,
                                "Interval harus di antara " + MIN_INTERVAL_SEC + "-" + MAX_INTERVAL_SEC + " detik.",
                                Toast.LENGTH_SHORT
                        ).show();
                        return;
                    }

                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit()
                            .putInt(PREF_NETWORK_INTERVAL_SEC, interval)
                            .apply();

                    executeRootAsync(
                            "setprop " + PROP_NETWORK_INTERVAL_SEC + " " + interval,
                            "Interval update network traffic disimpan: " + interval + " detik"
                    );
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
