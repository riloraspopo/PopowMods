package com.popow.popowmods;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import java.util.Locale;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String SYSTEMUI_PKG = "com.android.systemui";
    private static final String LAUNCHER_NEXUS_PKG = "com.google.android.apps.nexuslauncher";
    private static final String LAUNCHER_AOSP_PKG = "com.android.launcher3";

    private static final String ACTION_SLEEP = "com.popow.popowmods.ACTION_SLEEP";
    private static final String KEYGUARD_PIN_VIEW_CONTROLLER = "com.android.keyguard.KeyguardPinViewController";
    private static final String WORKSPACE_TOUCH_LISTENER = "com.android.launcher3.touch.WorkspaceTouchListener";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (SYSTEMUI_PKG.equals(lpparam.packageName)) {
            hookSystemUI(lpparam);
        } else if (LAUNCHER_NEXUS_PKG.equals(lpparam.packageName) || LAUNCHER_AOSP_PKG.equals(lpparam.packageName)) {
            hookLauncher(lpparam);
        }
    }

    private void hookSystemUI(final XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log("PopowMods: Initializing SystemUI hooks");

        // 1. Hook SystemUIApplication.onCreate to register sleep broadcast receiver
        try {
            XposedHelpers.findAndHookMethod(
                    "com.android.systemui.SystemUIApplication",
                    lpparam.classLoader,
                    "onCreate",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Context context = (Context) param.thisObject;
                            registerSleepReceiver(context);
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log("PopowMods: Failed to hook SystemUIApplication.onCreate: " + t);
        }

        // 2. Hook KeyguardPinViewController.onUserInput for 4-digit PIN auto confirm
        try {
            XposedHelpers.findAndHookMethod(
                    KEYGUARD_PIN_VIEW_CONTROLLER,
                    lpparam.classLoader,
                    "onUserInput",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            Object passwordEntry = XposedHelpers.getObjectField(param.thisObject, "mPasswordEntry");
                            if (passwordEntry == null) {
                                return;
                            }

                            String pin = (String) XposedHelpers.getObjectField(passwordEntry, "mText");
                            if (pin != null && pin.length() == 4) {
                                boolean dismissing = XposedHelpers.getBooleanField(param.thisObject, "mDismissing");
                                boolean lockedOut = XposedHelpers.getBooleanField(param.thisObject, "mLockedOut");
                                if (dismissing || lockedOut) {
                                    return;
                                }

                                Object pendingCheck = XposedHelpers.getObjectField(param.thisObject, "mPendingLockCheck");
                                if (pendingCheck != null) {
                                    return;
                                }

                                XposedBridge.log("PopowMods: 4 PIN digits detected, triggering verifyPasswordAndUnlock");
                                XposedHelpers.callMethod(param.thisObject, "verifyPasswordAndUnlock");
                            }
                        }
                    }
            );
            XposedBridge.log("PopowMods: Successfully hooked KeyguardPinViewController.onUserInput");
        } catch (Throwable t) {
            XposedBridge.log("PopowMods: KeyguardPinViewController hook error: " + t);
        }

        // 3. Hook NetworkTraffic for separate upload (top) and download (bottom) with arrows
        hookNetworkTraffic(lpparam.classLoader);
    }

    private void hookNetworkTraffic(ClassLoader classLoader) {
        String[] targetClasses = new String[] {
            "com.android.internal.statusbar.NetworkTraffic$1",
            "com.libremobileos.statusbar.NetworkTraffic$3"
        };

        for (String targetClass : targetClasses) {
            boolean hooked = false;
            try {
                XposedHelpers.findAndHookMethod(
                        targetClass,
                        classLoader,
                        "displayStatsAndReschedule",
                        new XC_MethodHook() {
                            @Override
                            protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                handleNetworkTrafficDisplay(param);
                            }
                        }
                );
                XposedBridge.log("PopowMods: Successfully hooked " + targetClass + ".displayStatsAndReschedule");
                hooked = true;
            } catch (Throwable t) {
                // Try fallback with system classloader
                try {
                    XposedHelpers.findAndHookMethod(
                            targetClass,
                            ClassLoader.getSystemClassLoader(),
                            "displayStatsAndReschedule",
                            new XC_MethodHook() {
                                @Override
                                protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                                    handleNetworkTrafficDisplay(param);
                                }
                            }
                    );
                    XposedBridge.log("PopowMods: Successfully hooked " + targetClass + ".displayStatsAndReschedule via SystemClassLoader");
                    hooked = true;
                } catch (Throwable t2) {
                    XposedBridge.log("PopowMods: Note: " + targetClass + " not hooked: " + t2.getMessage());
                }
            }
        }
    }

    private void handleNetworkTrafficDisplay(XC_MethodHook.MethodHookParam param) {
        try {
            TextView trafficView = (TextView) XposedHelpers.getObjectField(param.thisObject, "this$0");
            if (trafficView == null || trafficView.getVisibility() != View.VISIBLE) {
                return;
            }

            int mode = XposedHelpers.getIntField(trafficView, "mMode");
            if (mode == 0) {
                return;
            }

            long txKbps = XposedHelpers.getLongField(trafficView, "mTxKbps");
            long rxKbps = XposedHelpers.getLongField(trafficView, "mRxKbps");
            int units = XposedHelpers.getIntField(trafficView, "mUnits");

            String txFormatted = "▲ " + formatTrafficSpeed(txKbps, units);
            String rxFormatted = "▼ " + formatTrafficSpeed(rxKbps, units);

            String resultText;
            if (mode == 3) {
                resultText = txFormatted + "\n" + rxFormatted;
            } else if (mode == 1) {
                resultText = txFormatted;
            } else if (mode == 2) {
                resultText = rxFormatted;
            } else {
                return;
            }

            if (!resultText.contentEquals(trafficView.getText())) {
                trafficView.setText(resultText);
            }
            trafficView.setLineSpacing(0f, 0.85f);
            trafficView.setIncludeFontPadding(false);
            trafficView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            trafficView.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);
        } catch (Throwable t) {
            XposedBridge.log("PopowMods: handleNetworkTrafficDisplay error: " + t);
        }
    }

    private static String formatTrafficSpeed(long kbps, int units) {
        switch (units) {
            case 0: // UNITS_KILOBITS
                return kbps + " kb/s";
            case 1: // UNITS_MEGABITS
                return String.format(Locale.ENGLISH, "%.1f Mb/s", kbps / 1000.0f);
            case 2: // UNITS_KILOBYTES
                return String.format(Locale.ENGLISH, "%.0f kB/s", kbps / 8.0f);
            case 3: // UNITS_MEGABYTES
                float mb = kbps / 8000.0f;
                if (mb < 10f) {
                    return String.format(Locale.ENGLISH, "%.2f MB/s", mb);
                } else if (mb < 100f) {
                    return String.format(Locale.ENGLISH, "%.1f MB/s", mb);
                } else {
                    return String.format(Locale.ENGLISH, "%.0f MB/s", mb);
                }
            case 4: // UNITS_AUTOBYTES
            default:
                if (kbps < 8000) {
                    float kBps = kbps / 8.0f;
                    if (kBps == 0f) {
                        return "0 kB/s";
                    } else if (kBps < 10f) {
                        return String.format(Locale.ENGLISH, "%.1f kB/s", kBps);
                    } else {
                        return String.format(Locale.ENGLISH, "%.0f kB/s", kBps);
                    }
                } else if (kbps < 8000000) {
                    float mBps = kbps / 8000.0f;
                    if (mBps < 10f) {
                        return String.format(Locale.ENGLISH, "%.2f MB/s", mBps);
                    } else if (mBps < 100f) {
                        return String.format(Locale.ENGLISH, "%.1f MB/s", mBps);
                    } else {
                        return String.format(Locale.ENGLISH, "%.0f MB/s", mBps);
                    }
                } else {
                    float gBps = kbps / 8000000.0f;
                    return String.format(Locale.ENGLISH, "%.2f GB/s", gBps);
                }
        }
    }

    private void registerSleepReceiver(Context context) {
        try {
            IntentFilter filter = new IntentFilter(ACTION_SLEEP);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    try {
                        PowerManager pm = (PowerManager) ctx.getSystemService(Context.POWER_SERVICE);
                        if (pm != null) {
                            XposedBridge.log("PopowMods: Sleep broadcast received -> putting device to sleep");
                            try {
                                XposedHelpers.callMethod(pm, "goToSleep", SystemClock.uptimeMillis());
                            } catch (Throwable t1) {
                                XposedHelpers.callMethod(pm, "goToSleep", SystemClock.uptimeMillis(), 0, 0);
                            }
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("PopowMods: goToSleep error: " + t);
                    }
                }
            };

            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, 2 /* Context.RECEIVER_EXPORTED */);
            } else {
                context.registerReceiver(receiver, filter);
            }
            XposedBridge.log("PopowMods: Sleep receiver registered successfully in SystemUI");
        } catch (Throwable t) {
            XposedBridge.log("PopowMods: Failed to register sleep receiver: " + t);
        }
    }

    private void hookLauncher(final XC_LoadPackage.LoadPackageParam lpparam) {
        XposedBridge.log("PopowMods: Initializing Launcher hooks for " + lpparam.packageName);

        try {
            XposedHelpers.findAndHookConstructor(
                    WORKSPACE_TOUCH_LISTENER,
                    lpparam.classLoader,
                    "com.android.launcher3.Launcher",
                    "com.android.launcher3.Workspace",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(final MethodHookParam param) throws Throwable {
                            GestureDetector gd = (GestureDetector) XposedHelpers.getObjectField(param.thisObject, "mGestureDetector");
                            if (gd == null) {
                                return;
                            }

                            gd.setOnDoubleTapListener(new GestureDetector.SimpleOnGestureListener() {
                                @Override
                                public boolean onDoubleTap(MotionEvent e) {
                                    try {
                                        Context launcher = (Context) XposedHelpers.getObjectField(param.thisObject, "mLauncher");
                                        if (launcher != null) {
                                            Boolean canHandle = (Boolean) XposedHelpers.callMethod(param.thisObject, "canHandleLongPress");
                                            if (Boolean.TRUE.equals(canHandle)) {
                                                View workspace = (View) XposedHelpers.getObjectField(param.thisObject, "mWorkspace");
                                                if (workspace != null) {
                                                    workspace.performHapticFeedback(HapticFeedbackConstants.CLOCK_TICK);
                                                }

                                                XposedBridge.log("PopowMods: Double tap on home screen -> broadcasting ACTION_SLEEP");
                                                Intent intent = new Intent(ACTION_SLEEP);
                                                intent.setPackage(SYSTEMUI_PKG);
                                                launcher.sendBroadcast(intent);
                                                return true;
                                            }
                                        }
                                    } catch (Throwable t) {
                                        XposedBridge.log("PopowMods: Double tap handler error: " + t);
                                    }
                                    return false;
                                }
                            });
                            XposedBridge.log("PopowMods: Successfully installed double-tap listener on WorkspaceTouchListener");
                        }
                    }
            );
        } catch (Throwable t) {
            XposedBridge.log("PopowMods: Failed to hook WorkspaceTouchListener: " + t);
        }
    }
}