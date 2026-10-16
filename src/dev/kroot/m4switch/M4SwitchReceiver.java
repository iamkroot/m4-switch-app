package dev.kroot.m4switch;

import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.util.Log;

import java.util.List;

public class M4SwitchReceiver extends BroadcastReceiver {
    private static final String TAG = "M4Switch";
    public static final String ACTION_SWITCH = "dev.kroot.m4switch.ACTION_SWITCH";
    public static final String ACTION_SWITCH_SHORT = "m4switch.ACTION_SWITCH";
    public static final String ACTION_SWITCH_LEGACY = "com.m4.SWITCH";

    // Backward-compatible alias
    public static class DeviceInfo extends dev.kroot.m4switch.DeviceInfo {
        public DeviceInfo() { super(); }
        public DeviceInfo(int index, int priority, boolean connected, String name, boolean isSelf) {
            super(index, priority, connected, name, isSelf);
        }
    }

    @Override
    public void onReceive(Context context, Intent intent) {
        String action = intent.getAction();
        if (action == null || (!action.equals(ACTION_SWITCH) && !action.equals(ACTION_SWITCH_SHORT) && !action.equals(ACTION_SWITCH_LEGACY))) {
            return;
        }

        final PendingResult pendingResult = goAsync();
        final String macExtra = intent.getStringExtra("mac");
        final String mac = (macExtra != null && !macExtra.trim().isEmpty()) ? macExtra.trim() : M4Manager.getMac(context);
        final String targetDisconnect = intent.getStringExtra("disconnect");
        final String targetConnect = intent.getStringExtra("connect");
        final boolean listOnly = intent.getBooleanExtra("list", false);
        final boolean showToast = intent.getBooleanExtra("toast", true);

        Log.i(TAG, "M4Switch request: mac=" + mac + ", disc=" + targetDisconnect + ", conn=" + targetConnect + ", list=" + listOnly);

        new Thread(() -> {
            try {
                if (listOnly || (targetDisconnect == null && targetConnect == null)) {
                    List<dev.kroot.m4switch.DeviceInfo> devices = M4Manager.fetchDevices(context, mac);
                    StringBuilder sb = new StringBuilder("M4 Paired Devices:\n");
                    for (dev.kroot.m4switch.DeviceInfo d : devices) {
                        sb.append(d.toString()).append("\n");
                    }
                    Log.i(TAG, sb.toString());
                    if (showToast) {
                        M4Manager.showToast(context, sb.toString().trim());
                    }
                } else {
                    M4Manager.SwitchResult result = M4Manager.switchDevice(context, mac, targetDisconnect, targetConnect);
                    Log.i(TAG, "M4 Switch result: " + result.message);
                    if (showToast && result.message != null && !result.message.isEmpty()) {
                        M4Manager.showToast(context, result.message);
                    }
                }
            } catch (Exception e) {
                Log.e(TAG, "Error executing M4 switch: " + e.getMessage(), e);
                if (showToast) {
                    M4Manager.showToast(context, "M4 Switch Error: " + e.getMessage());
                }
            } finally {
                pendingResult.finish();
            }
        }).start();
    }

    // Retained for backward compatibility
    public static void handleSwitch(Context context, String mac, String targetDisconnect, String targetConnect, boolean listOnly, boolean showToast) throws Exception {
        if (listOnly) {
            M4Manager.fetchDevices(context, mac);
        } else {
            M4Manager.switchDevice(context, mac, targetDisconnect, targetConnect);
        }
    }
}
