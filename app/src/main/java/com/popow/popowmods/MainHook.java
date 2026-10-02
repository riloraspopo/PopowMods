package com.popow.popowmods;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.os.Build;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;

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