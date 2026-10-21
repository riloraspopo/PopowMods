package com.popow.popowmods;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.util.Log;
import android.view.View;
import android.widget.Button;
import android.widget.CompoundButton;
import android.widget.EditText;
import android.widget.Switch;
import android.widget.Toast;

import java.io.DataOutputStream;
import java.util.Locale;

public class MainActivity extends Activity {

    private static final String TAG = "PopowMods";
    private static final String PREFS_NAME = "popow_mods_settings";

    // Feature toggles
    private static final String PREF_PIN_ENABLED = "pin_enabled";
    private static final String PREF_DT2S_ENABLED = "dt2s_enabled";
    private static final String PREF_NET_TRAFFIC_ENABLED = "net_traffic_enabled";
    private static final String PREF_AA_DPI_ENABLED = "aa_dpi_enabled";

    private static final String PROP_PIN_ENABLED = "persist.popowmods.pin_enabled";
    private static final String PROP_DT2S_ENABLED = "persist.popowmods.dt2s_enabled";
    private static final String PROP_NET_TRAFFIC_ENABLED = "persist.popowmods.net_traffic_enabled";

    // Net speed settings
    private static final String PREF_NETWORK_INTERVAL_MS = "network_interval_ms";
    private static final String PROP_NETWORK_INTERVAL_MS = "persist.popowmods.net_interval_ms";
    private static final String ACTION_UPDATE_INTERVAL = "com.popow.popowmods.ACTION_UPDATE_INTERVAL";
    private static final String EXTRA_INTERVAL_MS = "interval_ms";

    private static final int DEFAULT_INTERVAL_MS = 2000;
    private static final int MIN_INTERVAL_MS = 200;
    private static final int MAX_INTERVAL_MS = 60000;

    // Android Auto DPI settings (using persist.sys.* for universal appdomain read access)
    private static final String PREF_AA_DPI = "aa_dpi";
    private static final String PROP_AA_DPI = "persist.sys.popow_aa_dpi";
    private static final String PROP_AA_DPI_ENABLED = "persist.sys.popow_aa_enabled";
    private static final String PROP_AA_DPI_LEGACY = "persist.popowmods.aa_dpi";
    private static final String PROP_AA_DPI_ENABLED_LEGACY = "persist.popowmods.aa_dpi_enabled";
    private static final int DEFAULT_AA_DPI = 0;
    private static final int MIN_AA_DPI = 90;
    private static final int MAX_AA_DPI = 360;

    // Android Auto Rail Side settings
    private static final String PREF_AA_RAIL = "aa_rail";
    private static final String PROP_AA_RAIL_SYS = "persist.sys.popow_aa_rail";
    private static final String PROP_AA_RAIL_SIDE = "persist.popowmods.aa_rail_side";
    private static final String PROP_AA_RAIL = "persist.popowmods.aa_rail";
    private static final int RAIL_BOTTOM = 0;
    private static final int RAIL_LEFT = 1;
    private static final int RAIL_RIGHT = 2;

    private Button btnRailBottom;
    private Button btnRailRight;
    private Button btnRailLeft;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        Button btnRestartSystemUi = findViewById(R.id.btnRestartSystemUi);
        Button btnRestartLauncher = findViewById(R.id.btnRestartLauncher);
        Button btnRestartAndroidAuto = findViewById(R.id.btnRestartAndroidAuto);
        Button btnRestartAll = findViewById(R.id.btnRestartAll);

        final View cardNetTraffic = findViewById(R.id.cardNetTraffic);
        final View cardAaDpi = findViewById(R.id.cardAaDpi);

        final Switch switchPin = findViewById(R.id.switchPin);
        final Switch switchDt2s = findViewById(R.id.switchDt2s);
        final Switch switchNetTraffic = findViewById(R.id.switchNetTraffic);
        final Switch switchAaDpi = findViewById(R.id.switchAaDpi);

        final EditText etNetworkInterval = findViewById(R.id.etNetworkInterval);
        Button btnSaveNetworkInterval = findViewById(R.id.btnSaveNetworkInterval);
        Button btnPreset250 = findViewById(R.id.btnPreset250);
        Button btnPreset500 = findViewById(R.id.btnPreset500);
        Button btnPreset1000 = findViewById(R.id.btnPreset1000);
        Button btnPreset2000 = findViewById(R.id.btnPreset2000);

        final EditText etAaDpi = findViewById(R.id.etAaDpi);
        Button btnSaveAaDpi = findViewById(R.id.btnSaveAaDpi);
        Button btnPresetAa130 = findViewById(R.id.btnPresetAa130);
        Button btnPresetAa140 = findViewById(R.id.btnPresetAa140);
        Button btnPresetAa160 = findViewById(R.id.btnPresetAa160);
        Button btnPresetAaDefault = findViewById(R.id.btnPresetAaDefault);

        btnRailBottom = findViewById(R.id.btnRailBottom);
        btnRailRight = findViewById(R.id.btnRailRight);
        btnRailLeft = findViewById(R.id.btnRailLeft);

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

        if (btnRestartAndroidAuto != null) {
            btnRestartAndroidAuto.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    executeRootAsync("am force-stop com.google.android.projection.gearhead", "Android Auto dihentikan. Sambungkan kembali ke head unit.");
                }
            });
        }

        if (btnRestartAll != null) {
            btnRestartAll.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    executeRootAsync("pkill -9 -f com.android.systemui; pkill -9 -f com.google.android.apps.nexuslauncher; pkill -9 -f com.android.launcher3; am force-stop com.google.android.projection.gearhead", "System UI, Launcher & Android Auto direstart");
                }
            });
        }

        // Initialize toggle states
        final int savedAaDpi = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(PREF_AA_DPI, DEFAULT_AA_DPI);
        int savedRailInit = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(PREF_AA_RAIL, -1);
        if (savedRailInit == -1) {
            savedRailInit = RAIL_BOTTOM;
            try {
                Class<?> spClass = Class.forName("android.os.SystemProperties");
                String val = (String) spClass.getMethod("get", String.class, String.class).invoke(null, PROP_AA_RAIL_SYS, "");
                if (val == null || val.isEmpty()) {
                    val = (String) spClass.getMethod("get", String.class, String.class).invoke(null, PROP_AA_RAIL_SIDE, "");
                }
                if ("2".equals(val)) {
                    savedRailInit = RAIL_RIGHT;
                } else if ("1".equals(val)) {
                    savedRailInit = RAIL_LEFT;
                }
            } catch (Throwable ignored) {}
            getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putInt(PREF_AA_RAIL, savedRailInit).apply();
        }
        final int savedRail = savedRailInit;
        updateRailButtonsUi(savedRail);

        if (btnRailBottom != null) {
            btnRailBottom.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setRailPosition(RAIL_BOTTOM);
                }
            });
        }

        if (btnRailRight != null) {
            btnRailRight.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setRailPosition(RAIL_RIGHT);
                }
            });
        }

        if (btnRailLeft != null) {
            btnRailLeft.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    setRailPosition(RAIL_LEFT);
                }
            });
        }

        boolean isPinEnabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_PIN_ENABLED, true);
        boolean isDt2sEnabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_DT2S_ENABLED, true);
        boolean isNetTrafficEnabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_NET_TRAFFIC_ENABLED, true);
        boolean isAaDpiEnabled = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getBoolean(PREF_AA_DPI_ENABLED, savedAaDpi > 0 || savedRail > 0);

        // Sync system properties on startup
        if (isAaDpiEnabled) {
            String syncCmd = "setprop " + PROP_AA_DPI_ENABLED + " 1; " +
                    "setprop " + PROP_AA_DPI_ENABLED_LEGACY + " 1";
            if (savedAaDpi > 0) {
                syncCmd += "; setprop " + PROP_AA_DPI + " " + savedAaDpi + "; " +
                        "setprop " + PROP_AA_DPI_LEGACY + " " + savedAaDpi;
            }
            if (savedRail > 0) {
                syncCmd += "; setprop " + PROP_AA_RAIL_SYS + " " + savedRail + "; " +
                        "setprop " + PROP_AA_RAIL_SIDE + " " + savedRail + "; " +
                        "setprop " + PROP_AA_RAIL + " " + savedRail;
            }
            executeRootAsync(syncCmd, null);
        }

        if (switchPin != null) {
            switchPin.setChecked(isPinEnabled);
            switchPin.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_PIN_ENABLED, isChecked).apply();
                    executeRootAsync(
                            "setprop " + PROP_PIN_ENABLED + " " + (isChecked ? "1" : "0"),
                            isChecked ? "Auto Confirm PIN diaktifkan" : "Auto Confirm PIN dinonaktifkan"
                    );
                }
            });
        }

        if (switchDt2s != null) {
            switchDt2s.setChecked(isDt2sEnabled);
            switchDt2s.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_DT2S_ENABLED, isChecked).apply();
                    executeRootAsync(
                            "setprop " + PROP_DT2S_ENABLED + " " + (isChecked ? "1" : "0"),
                            isChecked ? "Double Tap to Sleep diaktifkan" : "Double Tap to Sleep dinonaktifkan"
                    );
                }
            });
        }

        if (cardNetTraffic != null) {
            cardNetTraffic.setVisibility(isNetTrafficEnabled ? View.VISIBLE : View.GONE);
        }
        if (switchNetTraffic != null) {
            switchNetTraffic.setChecked(isNetTrafficEnabled);
            switchNetTraffic.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_NET_TRAFFIC_ENABLED, isChecked).apply();
                    if (cardNetTraffic != null) {
                        cardNetTraffic.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                    }
                    executeRootAsync(
                            "setprop " + PROP_NET_TRAFFIC_ENABLED + " " + (isChecked ? "1" : "0"),
                            isChecked ? "Net Traffic Monitor diaktifkan" : "Net Traffic Monitor dinonaktifkan"
                    );
                }
            });
        }

        if (cardAaDpi != null) {
            cardAaDpi.setVisibility(isAaDpiEnabled ? View.VISIBLE : View.GONE);
        }
        if (switchAaDpi != null) {
            switchAaDpi.setChecked(isAaDpiEnabled);
            switchAaDpi.setOnCheckedChangeListener(new CompoundButton.OnCheckedChangeListener() {
                @Override
                public void onCheckedChanged(CompoundButton buttonView, boolean isChecked) {
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE).edit().putBoolean(PREF_AA_DPI_ENABLED, isChecked).apply();
                    if (cardAaDpi != null) {
                        cardAaDpi.setVisibility(isChecked ? View.VISIBLE : View.GONE);
                    }
                    if (!isChecked) {
                        executeRootAsync(
                                "setprop " + PROP_AA_DPI_ENABLED + " 0; " +
                                "setprop " + PROP_AA_DPI_ENABLED_LEGACY + " 0; " +
                                "setprop " + PROP_AA_RAIL_SYS + " 0; " +
                                "setprop " + PROP_AA_RAIL_SIDE + " 0; " +
                                "setprop " + PROP_AA_RAIL + " 0; " +
                                "am force-stop com.google.android.projection.gearhead",
                                "Kustomisasi Android Auto dinonaktifkan (default)"
                        );
                    } else {
                        int r = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(PREF_AA_RAIL, 0);
                        int d = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(PREF_AA_DPI, 0);
                        String cmd = "setprop " + PROP_AA_DPI_ENABLED + " 1; " +
                                "setprop " + PROP_AA_DPI_ENABLED_LEGACY + " 1; " +
                                "setprop " + PROP_AA_DPI + " " + d + "; " +
                                "setprop " + PROP_AA_DPI_LEGACY + " " + d + "; " +
                                "setprop " + PROP_AA_RAIL_SYS + " " + r + "; " +
                                "setprop " + PROP_AA_RAIL_SIDE + " " + r + "; " +
                                "setprop " + PROP_AA_RAIL + " " + r + "; " +
                                "am force-stop com.google.android.projection.gearhead";
                        executeRootAsync(cmd, "Kustomisasi Android Auto diaktifkan");
                    }
                }
            });
        }

        // Net Speed Interval
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

                    try {
                        Intent intent = new Intent(ACTION_UPDATE_INTERVAL);
                        intent.setPackage("com.android.systemui");
                        intent.putExtra(EXTRA_INTERVAL_MS, intervalMs);
                        sendBroadcast(intent);
                    } catch (Exception e) {
                        Log.e(TAG, "Gagal kirim update interval broadcast: ", e);
                    }

                    float sec = intervalMs / 1000.0f;
                    String successMsg = String.format(Locale.ENGLISH, "Interval disimpan: %d ms (%.2fs)", intervalMs, sec);
                    executeRootAsync(
                            "setprop " + PROP_NETWORK_INTERVAL_MS + " " + intervalMs,
                            successMsg
                    );
                }
            });
        }

        // Android Auto DPI inputs
        if (etAaDpi != null) {
            etAaDpi.setText(savedAaDpi == 0 ? "0" : String.valueOf(savedAaDpi));
        }

        if (btnPresetAa130 != null && etAaDpi != null) {
            btnPresetAa130.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    etAaDpi.setText("130");
                }
            });
        }

        if (btnPresetAa140 != null && etAaDpi != null) {
            btnPresetAa140.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    etAaDpi.setText("140");
                }
            });
        }

        if (btnPresetAa160 != null && etAaDpi != null) {
            btnPresetAa160.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    etAaDpi.setText("160");
                }
            });
        }

        if (btnPresetAaDefault != null && etAaDpi != null) {
            btnPresetAaDefault.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    etAaDpi.setText("0");
                }
            });
        }

        if (btnSaveAaDpi != null && etAaDpi != null) {
            btnSaveAaDpi.setOnClickListener(new View.OnClickListener() {
                @Override
                public void onClick(View v) {
                    String input = etAaDpi.getText().toString().trim();
                    if (input.isEmpty()) {
                        Toast.makeText(MainActivity.this, "DPI tidak boleh kosong.", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    int dpi;
                    try {
                        dpi = Integer.parseInt(input);
                    } catch (NumberFormatException e) {
                        Toast.makeText(MainActivity.this, "Format DPI tidak valid.", Toast.LENGTH_SHORT).show();
                        return;
                    }

                    if (dpi != 0 && (dpi < MIN_AA_DPI || dpi > MAX_AA_DPI)) {
                        Toast.makeText(
                                MainActivity.this,
                                "DPI harus 0 (default) atau antara " + MIN_AA_DPI + " dan " + MAX_AA_DPI + ".",
                                Toast.LENGTH_SHORT
                        ).show();
                        return;
                    }

                    boolean enabled = (dpi > 0);
                    getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                            .edit()
                            .putInt(PREF_AA_DPI, dpi)
                            .putBoolean(PREF_AA_DPI_ENABLED, enabled)
                            .apply();

                    if (switchAaDpi != null) {
                        switchAaDpi.setChecked(enabled);
                    }

                    String msg = (dpi == 0)
                            ? "DPI Android Auto dikembalikan ke default."
                            : "DPI Android Auto disimpan: " + dpi + " DPI.";

                    executeRootAsync(
                            "setprop " + PROP_AA_DPI + " " + dpi + "; " +
                            "setprop " + PROP_AA_DPI_ENABLED + " " + (enabled ? "1" : "0") + "; " +
                            "setprop " + PROP_AA_DPI_LEGACY + " " + dpi + "; " +
                            "setprop " + PROP_AA_DPI_ENABLED_LEGACY + " " + (enabled ? "1" : "0") + "; " +
                            "am force-stop com.google.android.projection.gearhead",
                            msg + " Android Auto di-restart."
                    );
                }
            });
        }
    }

    private void updateRailButtonsUi(int rail) {
        if (btnRailBottom != null) {
            boolean active = (rail == RAIL_BOTTOM);
            btnRailBottom.setBackgroundResource(active ? R.drawable.btn_primary : R.drawable.btn_secondary);
            btnRailBottom.setText(active ? "✓ " + getString(R.string.aa_rail_bottom) : getString(R.string.aa_rail_bottom));
        }
        if (btnRailRight != null) {
            boolean active = (rail == RAIL_RIGHT);
            btnRailRight.setBackgroundResource(active ? R.drawable.btn_primary : R.drawable.btn_secondary);
            btnRailRight.setText(active ? "✓ " + getString(R.string.aa_rail_right) : getString(R.string.aa_rail_right));
        }
        if (btnRailLeft != null) {
            boolean active = (rail == RAIL_LEFT);
            btnRailLeft.setBackgroundResource(active ? R.drawable.btn_primary : R.drawable.btn_secondary);
            btnRailLeft.setText(active ? "✓ " + getString(R.string.aa_rail_left) : getString(R.string.aa_rail_left));
        }
    }

    private void setRailPosition(final int rail) {
        getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
                .edit()
                .putInt(PREF_AA_RAIL, rail)
                .apply();
        updateRailButtonsUi(rail);

        String name = (rail == RAIL_RIGHT) ? "Kanan" : (rail == RAIL_LEFT ? "Kiri" : "Bawah (Default)");
        executeRootAsync(
                "setprop " + PROP_AA_RAIL_SYS + " " + rail + "; " +
                "setprop " + PROP_AA_RAIL_SIDE + " " + rail + "; " +
                "setprop " + PROP_AA_RAIL + " " + rail + "; " +
                "am force-stop com.google.android.projection.gearhead",
                "Posisi Taskbar: " + name + ". Android Auto direstart."
        );
    }

    private void executeRootAsync(final String command, final String successMsg) {
        if (successMsg != null) {
            Toast.makeText(this, "Memproses akses root...", Toast.LENGTH_SHORT).show();
        }
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
                            if (successMsg != null) {
                                Toast.makeText(MainActivity.this, successMsg, Toast.LENGTH_SHORT).show();
                            }
                        } else {
                            if (successMsg != null) {
                                Toast.makeText(MainActivity.this, "Gagal menjalankan su. Pastikan perangkat di-root.", Toast.LENGTH_LONG).show();
                            }
                        }
                    }
                });
            }
        }).start();
    }
}
