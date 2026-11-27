package io.github.escosis.vivothemehelper;

import android.util.Log;
import de.robv.android.xposed.XposedBridge;

final class ModuleLog {
    private ModuleLog() {}
    static void log(String message) {
        Log.i(ModuleInfo.LOG_TAG, message);
        try { XposedBridge.log(ModuleInfo.LOG_TAG + ": " + message); }
        catch (Throwable ignored) { /* Info activity can run without an Xposed runtime. */ }
    }
    static void error(String message, Throwable error) {
        Log.e(ModuleInfo.LOG_TAG, message, error);
        try {
            XposedBridge.log(ModuleInfo.LOG_TAG + ": " + message + " [" + error.getClass().getSimpleName() + "]");
            XposedBridge.log(error);
        } catch (Throwable ignored) { }
    }
}
