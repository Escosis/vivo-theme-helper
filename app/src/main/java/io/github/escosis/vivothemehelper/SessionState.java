package io.github.escosis.vivothemehelper;

/** Pure state decisions shared by the Android lifecycle code and desktop regression tests. */
final class SessionState {
    private SessionState() {}
    static boolean lostWorker(String state, boolean liveWorker) {
        return !liveWorker && ("working".equals(state) || "preparing".equals(state) || "cancelling".equals(state));
    }
    static boolean shouldAdopt(String state) { return !"new".equals(state) && !"finished".equals(state); }
    static boolean picker(String state, int request) {
        return (request == ModuleInfo.PICK_REQUEST && "picker".equals(state))
                || (request == ModuleInfo.EXPORT_REQUEST && "export_picker".equals(state));
    }
}
