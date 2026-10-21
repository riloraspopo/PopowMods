package com.popow.popowmods;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.Toast;

import java.io.DataOutputStream;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String TAG = "PopowMods";
    private static final String PREFS_NAME = "popow_mods_settings";
    private static final String PREF_NETWORK_INTERVAL_MS = "network_interval_ms";
    private static final String PROP_NETWORK_INTERVAL_MS = "persist.popowmods.net_interval_ms";
    private static final String ACTION_UPDATE_INTERVAL = "com.popow.popowmods.ACTION_UPDATE_INTERVAL";
    private static final String EXTRA_INTERVAL_MS = "interval_ms";

    private static final int DEFAULT_INTERVAL_MS = 2000;
    private static final int MIN_INTERVAL_MS = 200;
    private static final int MAX_INTERVAL_MS = 60000;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnRestartSystemUi = findViewById(R.id.btnRestartSystemUi);
        Button btnRestartLauncher = findViewById(R.id.btnRestartLauncher);
        Button btnRestartAll = findViewById(R.id.btnRestartAll);

        final EditText etNetworkInterval = findViewById(R.id.etNetworkInterval);
        Button btnSaveNetworkInterval = findViewById(R.id.btnSaveNetworkInterval);
        Button btnPreset250 = findViewById(R.id.btnPreset250);
        Button btnPreset500 = findViewById(R.id.btnPreset500);
        Button btnPreset1000 = findViewById(R.id.btnPreset1000);
        Button btnPreset2000 = findViewById(R.id.btnPreset2000);

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
                .getInt(PREF_NETWORK_INTERVAL_MS, DEFAULT_INTERVAL_MS);
        if (etNetworkInterval != null) {
            etNetworkInterval.setText(String.valueOf(savedInterval));
        }

        if (btnPreset250 != null && etNetworkInterval != null) {
            btnPreset250.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    etNetworkInterval.setText("250");
                }
            });
        }

        if (btnPreset500 != null && etNetworkInterval != null) {
            btnPreset500.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    etNetworkInterval.setText("500");
                }
            });
        }

        if (btnPreset1000 != null && etNetworkInterval != null) {
            btnPreset1000.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    etNetworkInterval.setText("1000");
                }
            });
        }

        if (btnPreset2000 != null && etNetworkInterval != null) {
            btnPreset2000.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    etNetworkInterval.setText("2000");
                }
            });
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

                    int intervalMs;
                    try {
                        if (input.contains(".")) {
                            float sec = Float.parseFloat(input);
                            intervalMs = Math.round(sec * 1000f);
                        } else {
                            int val = Integer.parseInt(input);
                            // Jika user menginput nilai <= 10, anggap detik (contoh: 1 -> 1000 ms, 2 -> 2000 ms)
                            if (val <= 10) {
                                intervalMs = val * 1000;
                            } else {
                                intervalMs = val;
                            }
                        }
                    } catch (NumberFormatException e) {
                        Toast.makeText(MainActivity.this, "Format interval tidak valid.", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (intervalMs < MIN_INTERVAL_MS || intervalMs > MAX_INTERVAL_MS) {
                        Toast.makeText(
                                MainActivity.this,
                                "Interval harus di antara " + MIN_INTERVAL_MS + " ms dan " + MAX_INTERVAL_MS + " ms.",
                                Toast.LENGTH_SHORT
                        ).show();
                        return;
                    }

                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit()
                            .putInt(PREF_NETWORK_INTERVAL_MS, intervalMs)
                            .apply();

                    etNetworkInterval.setText(String.valueOf(intervalMs));

                    // Broadcast ke SystemUI untuk update langsung tanpa restart
                    try {
                        Intent intent = new Intent(ACTION_UPDATE_INTERVAL);
                        intent.setPackage("com.android.systemui");
                        intent.putExtra(EXTRA_INTERVAL_MS, intervalMs);
                        sendBroadcast(intent);
                    } catch (Exception e) {
                        Log.e(TAG, "Gagal kirim update interval broadcast: ", e);
                    }

                    // Terapkan ke system property via root
                    float sec = intervalMs / 1000.0f;
                    String successMsg = String.format(Locale.ENGLISH, "Interval disimpan: %d ms (%.2fs)", intervalMs, sec);
                    executeRootAsync(
                            "setprop " + PROP_NETWORK_INTERVAL_MS + " " + intervalMs,
                            successMsg
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
