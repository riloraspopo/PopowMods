package com.popow.popowmods;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.net.LinkProperties;
import android.net.TrafficStats;
import android.os.Build;
import android.os.Handler;
import android.os.PowerManager;
import android.os.SystemClock;
import android.view.GestureDetector;
import android.view.Gravity;
import android.view.HapticFeedbackConstants;
import android.view.MotionEvent;
import android.view.View;
import android.widget.TextView;

import java.lang.ref.WeakReference;
import java.util.ArrayDeque;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;

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
    private static final String ACTION_UPDATE_INTERVAL = "com.popow.popowmods.ACTION_UPDATE_INTERVAL";
    private static final String EXTRA_INTERVAL_MS = "interval_ms";
    private static final String PROP_NETWORK_INTERVAL_MS = "persist.popowmods.net_interval_ms";

    private static final int DEFAULT_NETWORK_INTERVAL_MS = 2000;
    private static final int MIN_NETWORK_INTERVAL_MS = 200;
    private static final int MAX_NETWORK_INTERVAL_MS = 60000;

    private static final String KEYGUARD_PIN_VIEW_CONTROLLER = "com.android.keyguard.KeyguardPinViewController";
    private static final String WORKSPACE_TOUCH_LISTENER = "com.android.launcher3.touch.WorkspaceTouchListener";

    private static volatile int sCachedNetworkIntervalMs = -1;
    private static WeakReference<Handler> sLastTrafficHandler = null;

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

        // 1. Hook SystemUIApplication.onCreate to register broadcast receivers
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
                            registerIntervalReceiver(context);
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

        // 3. Hook NetworkTraffic for separate upload/download arrows and configurable update interval
        hookNetworkTraffic(lpparam.classLoader);
    }

    private void hookNetworkTraffic(ClassLoader classLoader) {
        Set<String> targetClasses = new LinkedHashSet<>();
        targetClasses.add("com.android.internal.statusbar.NetworkTraffic$1");
        targetClasses.add("com.libremobileos.statusbar.NetworkTraffic$3");

        String[] parentClasses = new String[] {
            "com.android.internal.statusbar.NetworkTraffic",
            "com.libremobileos.statusbar.NetworkTraffic",
            "com.android.systemui.statusbar.NetworkTraffic"
        };

        for (String parentName : parentClasses) {
            try {
                Class<?> parentClass = XposedHelpers.findClassIfExists(parentName, classLoader);
                if (parentClass == null) {
                    parentClass = XposedHelpers.findClassIfExists(parentName, ClassLoader.getSystemClassLoader());
                }
                if (parentClass != null) {
                    for (Class<?> inner : parentClass.getDeclaredClasses()) {
                        targetClasses.add(inner.getName());
                    }
                }
            } catch (Throwable ignored) {
            }
        }

        for (String targetClass : targetClasses) {
            hookTargetClass(targetClass, classLoader);
        }
    }

    private void hookTargetClass(String targetClass, ClassLoader classLoader) {
        Class<?> clazz = XposedHelpers.findClassIfExists(targetClass, classLoader);
        if (clazz == null) {
            clazz = XposedHelpers.findClassIfExists(targetClass, ClassLoader.getSystemClassLoader());
        }
        if (clazz == null) {
            return;
        }

        boolean hasDisplayMethod = false;
        boolean hasRecalculateMethod = false;
        for (java.lang.reflect.Method m : clazz.getDeclaredMethods()) {
            if ("displayStatsAndReschedule".equals(m.getName())) {
                hasDisplayMethod = true;
            } else if ("recalculateStats".equals(m.getName())) {
                hasRecalculateMethod = true;
            }
        }

        if (!hasDisplayMethod) {
            return;
        }

        try {
            XposedHelpers.findAndHookMethod(
                    clazz,
                    "displayStatsAndReschedule",
                    new XC_MethodHook() {
                        @Override
                        protected void afterHookedMethod(MethodHookParam param) throws Throwable {
                            handleNetworkTrafficDisplay(param);
                        }
                    }
            );
            XposedBridge.log("PopowMods: Successfully hooked " + targetClass + ".displayStatsAndReschedule");
        } catch (Throwable t) {
            XposedBridge.log("PopowMods: Note: " + targetClass + " displayStatsAndReschedule not hooked: " + t.getMessage());
        }

        if (hasRecalculateMethod) {
            try {
                XposedHelpers.findAndHookMethod(
                        clazz,
                        "recalculateStats",
                        new XC_MethodHook() {
                            @Override
                            protected void beforeHookedMethod(MethodHookParam param) throws Throwable {
                                try {
                                    handleRecalculateStats(param);
                                    param.setResult(null); // Bypass hardcoded 2000ms check
                                } catch (Throwable t) {
                                    XposedBridge.log("PopowMods: handleRecalculateStats failed, letting original run: " + t);
                                }
                            }
                        }
                );
                XposedBridge.log("PopowMods: Successfully hooked " + targetClass + ".recalculateStats");
            } catch (Throwable t) {
                XposedBridge.log("PopowMods: Note: " + targetClass + " recalculateStats not hooked: " + t.getMessage());
            }
        }
    }

    private static class TrafficSample {
        final long time;
        final long txBytes;
        final long rxBytes;

        TrafficSample(long time, long txBytes, long rxBytes) {
            this.time = time;
            this.txBytes = txBytes;
            this.rxBytes = rxBytes;
        }
    }

    private static final Map<TextView, ArrayDeque<TrafficSample>> sHistoryMap = new WeakHashMap<>();

    private static ArrayDeque<TrafficSample> getHistoryForView(TextView trafficView) {
        synchronized (sHistoryMap) {
            ArrayDeque<TrafficSample> history = sHistoryMap.get(trafficView);
            if (history == null) {
                history = new ArrayDeque<>();
                sHistoryMap.put(trafficView, history);
            }
            return history;
        }
    }

    private void handleRecalculateStats(XC_MethodHook.MethodHookParam param) {
        TextView trafficView = (TextView) XposedHelpers.getObjectField(param.thisObject, "this$0");
        if (trafficView == null) {
            return;
        }

        int intervalMs = getNetworkIntervalMs();
        long now = SystemClock.elapsedRealtime();
        long lastUpdateTime = XposedHelpers.getLongField(trafficView, "mLastUpdateTime");
        long timeDelta = now - lastUpdateTime;

        // Skip duplicate calls within 75% of configured interval
        if (timeDelta < (long) (intervalMs * 0.75f)) {
            return;
        }

        long txBytes = 0;
        long rxBytes = 0;
        boolean gotInterfaceStats = false;

        try {
            Map<?, ?> linkMap = (Map<?, ?>) XposedHelpers.getObjectField(trafficView, "mLinkPropertiesMap");
            if (linkMap != null && !linkMap.isEmpty()) {
                for (Object item : linkMap.values()) {
                    if (item instanceof LinkProperties) {
                        String iface = ((LinkProperties) item).getInterfaceName();
                        if (iface != null) {
                            long ifaceTx = TrafficStats.getTxBytes(iface);
                            long ifaceRx = TrafficStats.getRxBytes(iface);
                            if (ifaceTx > 0) txBytes += ifaceTx;
                            if (ifaceRx > 0) rxBytes += ifaceRx;

                            long clatTx = TrafficStats.getTxBytes("v4-" + iface);
                            long clatRx = TrafficStats.getRxBytes("v4-" + iface);
                            if (clatTx > 0) txBytes += clatTx;
                            if (clatRx > 0) rxBytes += clatRx;
                            gotInterfaceStats = true;
                        }
                    }
                }
            }
        } catch (Throwable ignored) {
        }

        if (!gotInterfaceStats || (txBytes == 0 && rxBytes == 0)) {
            long totalTx = TrafficStats.getTotalTxBytes();
            long totalRx = TrafficStats.getTotalRxBytes();
            if (totalTx != TrafficStats.UNSUPPORTED && totalRx != TrafficStats.UNSUPPORTED) {
                txBytes = totalTx;
                rxBytes = totalRx;
            }
        }

        ArrayDeque<TrafficSample> history = getHistoryForView(trafficView);
        boolean networksChanged = false;
        try {
            networksChanged = XposedHelpers.getBooleanField(trafficView, "mNetworksChanged");
        } catch (Throwable ignored) {
        }

        if (networksChanged) {
            synchronized (history) {
                history.clear();
                history.addLast(new TrafficSample(now, txBytes, rxBytes));
            }
            XposedHelpers.setLongField(trafficView, "mTxKbps", 0L);
            XposedHelpers.setLongField(trafficView, "mRxKbps", 0L);
            try {
                XposedHelpers.setBooleanField(trafficView, "mNetworksChanged", false);
            } catch (Throwable ignored) {
            }
            XposedHelpers.setLongField(trafficView, "mLastTxBytes", txBytes);
            XposedHelpers.setLongField(trafficView, "mLastRxBytes", rxBytes);
            XposedHelpers.setLongField(trafficView, "mLastUpdateTime", now);
            return;
        }

        // Window size minimal 1000ms untuk cegah pembacaan 0 akibat packet burst delay
        long windowMs = Math.max(intervalMs, 1000L);
        long targetTime = now - windowMs;

        synchronized (history) {
            history.addLast(new TrafficSample(now, txBytes, rxBytes));

            while (history.size() > 1) {
                Iterator<TrafficSample> it = history.iterator();
                it.next(); // first
                TrafficSample second = it.next();
                if (second.time <= targetTime) {
                    history.pollFirst();
                } else {
                    break;
                }
            }

            TrafficSample baseline = history.peekFirst();
            if (baseline != null) {
                long sampleTimeDelta = now - baseline.time;
                long txBytesDelta = txBytes - baseline.txBytes;
                long rxBytesDelta = rxBytes - baseline.rxBytes;

                if (txBytesDelta < 0 || rxBytesDelta < 0) {
                    history.clear();
                    history.addLast(new TrafficSample(now, txBytes, rxBytes));
                    XposedHelpers.setLongField(trafficView, "mTxKbps", 0L);
                    XposedHelpers.setLongField(trafficView, "mRxKbps", 0L);
                } else if (sampleTimeDelta >= 100) {
                    long txKbps = (long) (txBytesDelta * 8f / 1000f / (sampleTimeDelta / 1000f));
                    long rxKbps = (long) (rxBytesDelta * 8f / 1000f / (sampleTimeDelta / 1000f));
                    XposedHelpers.setLongField(trafficView, "mTxKbps", txKbps);
                    XposedHelpers.setLongField(trafficView, "mRxKbps", rxKbps);
                }
            }
        }

        XposedHelpers.setLongField(trafficView, "mLastTxBytes", txBytes);
        XposedHelpers.setLongField(trafficView, "mLastRxBytes", rxBytes);
        XposedHelpers.setLongField(trafficView, "mLastUpdateTime", now);
    }

    private void handleNetworkTrafficDisplay(XC_MethodHook.MethodHookParam param) {
        try {
            if (param.thisObject instanceof Handler) {
                sLastTrafficHandler = new WeakReference<>((Handler) param.thisObject);
            }

            TextView trafficView = (TextView) XposedHelpers.getObjectField(param.thisObject, "this$0");
            if (trafficView == null || trafficView.getVisibility() != View.VISIBLE) {
                rescheduleIfNeeded(param.thisObject);
                return;
            }

            int mode = XposedHelpers.getIntField(trafficView, "mMode");
            if (mode == 0) {
                rescheduleIfNeeded(param.thisObject);
                return;
            }

            long txKbps = XposedHelpers.getLongField(trafficView, "mTxKbps");
            long rxKbps = XposedHelpers.getLongField(trafficView, "mRxKbps");
            int units = XposedHelpers.getIntField(trafficView, "mUnits");

            String txFormatted = "\u25B4 " + formatTrafficSpeed(txKbps, units);
            String rxFormatted = "\u25BE " + formatTrafficSpeed(rxKbps, units);

            CharSequence resultText;
            if (mode == 3) {
                String full = txFormatted + "\n" + rxFormatted;
                android.text.SpannableString ss = new android.text.SpannableString(full);
                ss.setSpan(new android.text.style.RelativeSizeSpan(1.25f), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                int secondArrowPos = txFormatted.length() + 1;
                ss.setSpan(new android.text.style.RelativeSizeSpan(1.25f), secondArrowPos, secondArrowPos + 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                resultText = ss;
            } else if (mode == 1) {
                android.text.SpannableString ss = new android.text.SpannableString(txFormatted);
                ss.setSpan(new android.text.style.RelativeSizeSpan(1.25f), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                resultText = ss;
            } else if (mode == 2) {
                android.text.SpannableString ss = new android.text.SpannableString(rxFormatted);
                ss.setSpan(new android.text.style.RelativeSizeSpan(1.25f), 0, 1, android.text.Spanned.SPAN_EXCLUSIVE_EXCLUSIVE);
                resultText = ss;
            } else {
                rescheduleIfNeeded(param.thisObject);
                return;
            }

            if (!resultText.toString().contentEquals(trafficView.getText())) {
                trafficView.setText(resultText);
            }
            trafficView.setLineSpacing(0f, 0.85f);
            trafficView.setIncludeFontPadding(false);
            trafficView.setGravity(Gravity.START | Gravity.CENTER_VERTICAL);
            trafficView.setCompoundDrawablesWithIntrinsicBounds(null, null, null, null);

            rescheduleIfNeeded(param.thisObject);
        } catch (Throwable t) {
            XposedBridge.log("PopowMods: handleNetworkTrafficDisplay error: " + t);
        }
    }

    private void rescheduleIfNeeded(Object handlerObj) {
        if (handlerObj instanceof Handler) {
            Handler handler = (Handler) handlerObj;
            if (handler.hasMessages(0 /* MESSAGE_TYPE_PERIODIC_REFRESH */)) {
                int intervalMs = getNetworkIntervalMs();
                handler.removeMessages(0);
                handler.sendEmptyMessageDelayed(0, intervalMs);
            }
        }
    }

    private static int getNetworkIntervalMs() {
        int interval = sCachedNetworkIntervalMs;
        if (interval > 0) {
            return interval;
        }
        interval = readSystemPropertyIntervalMs();
        sCachedNetworkIntervalMs = interval;
        return interval;
    }

    private static int readSystemPropertyIntervalMs() {
        try {
            Class<?> spClass = Class.forName("android.os.SystemProperties");
            String value = (String) XposedHelpers.callStaticMethod(
                    spClass,
                    "get",
                    PROP_NETWORK_INTERVAL_MS,
                    ""
            );
            if (value != null && !value.isEmpty()) {
                int parsed = Integer.parseInt(value.trim());
                if (parsed >= MIN_NETWORK_INTERVAL_MS && parsed <= MAX_NETWORK_INTERVAL_MS) {
                    return parsed;
                }
            }
        } catch (Throwable ignored) {
        }
        return DEFAULT_NETWORK_INTERVAL_MS;
    }

    private void registerIntervalReceiver(Context context) {
        try {
            IntentFilter filter = new IntentFilter(ACTION_UPDATE_INTERVAL);
            BroadcastReceiver receiver = new BroadcastReceiver() {
                @Override
                public void onReceive(Context ctx, Intent intent) {
                    try {
                        int newInterval = intent.getIntExtra(EXTRA_INTERVAL_MS, -1);
                        if (newInterval >= MIN_NETWORK_INTERVAL_MS && newInterval <= MAX_NETWORK_INTERVAL_MS) {
                            sCachedNetworkIntervalMs = newInterval;
                            XposedBridge.log("PopowMods: Net interval updated via broadcast: " + newInterval + " ms");

                            if (sLastTrafficHandler != null) {
                                Handler handler = sLastTrafficHandler.get();
                                if (handler != null) {
                                    handler.removeMessages(0);
                                    handler.sendEmptyMessage(0);
                                }
                            }
                        }
                    } catch (Throwable t) {
                        XposedBridge.log("PopowMods: Error handling interval broadcast: " + t);
                    }
                }
            };

            if (Build.VERSION.SDK_INT >= 33) {
                context.registerReceiver(receiver, filter, 2 /* Context.RECEIVER_EXPORTED */);
            } else {
                context.registerReceiver(receiver, filter);
            }
            XposedBridge.log("PopowMods: Interval receiver registered successfully in SystemUI");
        } catch (Throwable t) {
            XposedBridge.log("PopowMods: Failed to register interval receiver: " + t);
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
