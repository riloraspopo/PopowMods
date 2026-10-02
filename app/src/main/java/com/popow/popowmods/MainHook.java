package com.popow.popowmods;

import de.robv.android.xposed.IXposedHookLoadPackage;
import de.robv.android.xposed.XC_MethodHook;
import de.robv.android.xposed.XposedBridge;
import de.robv.android.xposed.XposedHelpers;
import de.robv.android.xposed.callbacks.XC_LoadPackage;

public class MainHook implements IXposedHookLoadPackage {

    private static final String SYSTEMUI_PACKAGE = "com.android.systemui";
    private static final String KEYGUARD_PIN_VIEW_CONTROLLER = "com.android.keyguard.KeyguardPinViewController";

    @Override
    public void handleLoadPackage(XC_LoadPackage.LoadPackageParam lpparam) throws Throwable {
        if (!SYSTEMUI_PACKAGE.equals(lpparam.packageName)) {
            return;
        }

        XposedBridge.log("PopowMods: SystemUI loaded");

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
            XposedBridge.log("PopowMods hook error: " + t);
        }
    }
}