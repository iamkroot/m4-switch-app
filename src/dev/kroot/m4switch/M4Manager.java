package dev.kroot.m4switch;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothSocket;
import android.content.Context;
import android.content.Intent;
import android.content.SharedPreferences;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.widget.Toast;

import org.json.JSONArray;
import org.json.JSONObject;

import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

public class M4Manager {
    public static final String TAG = "M4Switch";
    public static final String DEFAULT_M4_MAC = "80:C3:BA:6A:00:EF";
    public static final UUID GAIA_UUID = UUID.fromString("a2129ff3-081b-4c45-8afe-469d9c4842ec");
    public static final int GAIA_VENDOR = 0x0495;

    public static final int CMD_GET_PAIRED_COUNT = 0x1400;
    public static final int CMD_GET_DEVICE_INFO = 0x1401;
    public static final int CMD_CONNECT_DEVICE = 0x1402;
    public static final int CMD_DISCONNECT_DEVICE = 0x1403;
    public static final int CMD_GET_OWN_INDEX = 0x1407;

    public static final String PREFS_NAME = "m4switch_prefs";
    public static final String KEY_MAC = "pref_m4_mac";
    public static final String KEY_CACHED_DEVICES = "pref_cached_devices";
    public static final String KEY_CACHE_TIME = "pref_cache_time";
    public static final String ACTION_CACHE_UPDATED = "dev.kroot.m4switch.ACTION_CACHE_UPDATED";

    public static class Frame {
        public int cmd;
        public byte[] payload;
    }

    public static class SwitchResult {
        public boolean success;
        public String message;
        public DeviceInfo disconnectedDevice;
        public DeviceInfo connectedDevice;
        public List<DeviceInfo> updatedDevices;
    }

    public static String getMac(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getString(KEY_MAC, DEFAULT_M4_MAC);
    }

    public static void setMac(Context context, String mac) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        prefs.edit().putString(KEY_MAC, mac.trim().toUpperCase()).apply();
    }

    public static long getCacheTime(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        return prefs.getLong(KEY_CACHE_TIME, 0);
    }

    public static List<DeviceInfo> getCachedDevices(Context context) {
        SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
        String json = prefs.getString(KEY_CACHED_DEVICES, null);
        List<DeviceInfo> list = new ArrayList<>();
        if (json == null || json.isEmpty()) {
            return list;
        }
        try {
            JSONArray arr = new JSONArray(json);
            for (int i = 0; i < arr.length(); i++) {
                JSONObject obj = arr.getJSONObject(i);
                DeviceInfo d = DeviceInfo.fromJson(obj);
                if (d != null) {
                    list.add(d);
                }
            }
        } catch (Exception e) {
            Log.e(TAG, "Failed to parse cached devices: " + e.getMessage());
        }
        return list;
    }

    public static void saveCachedDevices(Context context, List<DeviceInfo> devices) {
        try {
            JSONArray arr = new JSONArray();
            if (devices != null) {
                for (DeviceInfo d : devices) {
                    arr.put(d.toJson());
                }
            }
            SharedPreferences prefs = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE);
            prefs.edit()
                    .putString(KEY_CACHED_DEVICES, arr.toString())
                    .putLong(KEY_CACHE_TIME, System.currentTimeMillis())
                    .apply();

            Intent intent = new Intent(ACTION_CACHE_UPDATED);
            intent.setPackage(context.getPackageName());
            context.sendBroadcast(intent);
        } catch (Exception e) {
            Log.e(TAG, "Failed to save cached devices: " + e.getMessage());
        }
    }

    public static BluetoothSocket openSocket(String mac) throws IOException {
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter == null || !adapter.isEnabled()) {
            throw new IOException("Bluetooth is not enabled");
        }

        BluetoothDevice device = adapter.getRemoteDevice(mac);
        BluetoothSocket socket = null;
        try {
            socket = device.createRfcommSocketToServiceRecord(GAIA_UUID);
            socket.connect();
            return socket;
        } catch (IOException e) {
            Log.w(TAG, "Secure RFCOMM failed, trying insecure: " + e.getMessage());
            try {
                socket = device.createInsecureRfcommSocketToServiceRecord(GAIA_UUID);
                socket.connect();
                return socket;
            } catch (IOException e2) {
                throw new IOException("Failed to connect to M4 RFCOMM at " + mac + ": " + e2.getMessage(), e2);
            }
        }
    }

    public static List<DeviceInfo> readAllDevices(InputStream in, OutputStream out) throws IOException {
        // 1. Get paired devices count (0x1400)
        out.write(buildFrame(CMD_GET_PAIRED_COUNT, null));
        out.flush();
        Frame fCount = readFrameForCmd(in, CMD_GET_PAIRED_COUNT);
        int count = ByteBuffer.wrap(fCount.payload).order(ByteOrder.BIG_ENDIAN).getShort() & 0xFFFF;
        Log.i(TAG, "M4 paired device count: " + count);

        // 2. Get own device index (0x1407)
        out.write(buildFrame(CMD_GET_OWN_INDEX, null));
        out.flush();
        Frame fOwn = readFrameForCmd(in, CMD_GET_OWN_INDEX);
        int ownIndex = fOwn.payload[0] & 0xFF;
        Log.i(TAG, "Own device index: " + ownIndex);

        // 3. Enumerate all devices (0x1401)
        List<DeviceInfo> devices = new ArrayList<>();
        for (int i = 0; i < count; i++) {
            out.write(buildFrame(CMD_GET_DEVICE_INFO, new byte[]{(byte) i}));
            out.flush();
            Frame fDev = readFrameForCmd(in, CMD_GET_DEVICE_INFO);
            DeviceInfo d = new DeviceInfo();
            d.index = fDev.payload[0] & 0xFF;
            d.priority = fDev.payload[1] & 0xFF;
            d.connected = (fDev.payload[2] & 0xFF) == 1;
            d.name = new String(fDev.payload, 3, fDev.payload.length - 3, StandardCharsets.UTF_8).replace("\0", "").trim();
            d.isSelf = (d.index == ownIndex);
            devices.add(d);
            Log.i(TAG, "  Device: " + d);
        }
        return devices;
    }

    public static List<DeviceInfo> fetchDevices(Context context, String mac) throws Exception {
        BluetoothSocket socket = openSocket(mac);
        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();
            List<DeviceInfo> devices = readAllDevices(in, out);
            saveCachedDevices(context, devices);
            return devices;
        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {}
        }
    }

    public static DeviceInfo findDevice(List<DeviceInfo> devices, String query) {
        if (devices == null || query == null || query.trim().isEmpty()) {
            return null;
        }
        query = query.trim();

        // 1. Numeric index
        try {
            int idx = Integer.parseInt(query);
            for (DeviceInfo d : devices) {
                if (d.index == idx) return d;
            }
        } catch (NumberFormatException ignored) {}

        // 2. Exact name match (case-insensitive)
        for (DeviceInfo d : devices) {
            if (d.name.equalsIgnoreCase(query)) return d;
        }

        // 3. Substring match (case-insensitive)
        for (DeviceInfo d : devices) {
            if (d.name.toLowerCase().contains(query.toLowerCase())) return d;
        }

        return null;
    }

    public static SwitchResult switchDevice(Context context, String mac, String targetDisconnect, String targetConnect) throws Exception {
        SwitchResult res = new SwitchResult();
        BluetoothSocket socket = openSocket(mac);

        try {
            InputStream in = socket.getInputStream();
            OutputStream out = socket.getOutputStream();

            List<DeviceInfo> devices = readAllDevices(in, out);

            DeviceInfo toDisconnect = null;
            DeviceInfo toConnect = null;

            if (targetDisconnect != null && !targetDisconnect.trim().isEmpty()) {
                toDisconnect = findDevice(devices, targetDisconnect);
            }

            if (targetConnect != null && !targetConnect.trim().isEmpty()) {
                toConnect = findDevice(devices, targetConnect);
                if (toConnect == null) {
                    throw new IllegalArgumentException("Target device '" + targetConnect + "' not found in paired list");
                }
            }

            // Smart kick: if connect requested without explicit disconnect, and 2 slots full
            if (toConnect != null && !toConnect.connected && toDisconnect == null) {
                List<DeviceInfo> connectedPeers = new ArrayList<>();
                for (DeviceInfo d : devices) {
                    if (d.connected) connectedPeers.add(d);
                }
                if (connectedPeers.size() >= 2) {
                    for (DeviceInfo d : connectedPeers) {
                        if (!d.isSelf) {
                            toDisconnect = d;
                            Log.i(TAG, "Auto-selected non-self peer to disconnect: " + d.name);
                            break;
                        }
                    }
                    if (toDisconnect == null && !connectedPeers.isEmpty()) {
                        toDisconnect = connectedPeers.get(0);
                    }
                }
            }

            StringBuilder msg = new StringBuilder();

            // Disconnect target
            if (toDisconnect != null) {
                if (toDisconnect.connected) {
                    Log.i(TAG, "Disconnecting " + toDisconnect.name + " (idx=" + toDisconnect.index + ")");
                    out.write(buildFrame(CMD_DISCONNECT_DEVICE, new byte[]{(byte) toDisconnect.index}));
                    out.flush();
                    Frame ack = readFrameForCmd(in, CMD_DISCONNECT_DEVICE);
                    Log.i(TAG, "Disconnect ACK cmd: 0x" + Integer.toHexString(ack.cmd));
                    res.disconnectedDevice = toDisconnect;
                    msg.append("Disconnected ").append(toDisconnect.name).append(". ");

                    // Actively poll until M4 actually reports toDisconnect as disconnected
                    boolean confirmedDisconnected = false;
                    for (int attempt = 0; attempt < 25; attempt++) {
                        Thread.sleep(100);
                        out.write(buildFrame(CMD_GET_DEVICE_INFO, new byte[]{(byte) toDisconnect.index}));
                        out.flush();
                        Frame fDev = readFrameForCmd(in, CMD_GET_DEVICE_INFO);
                        boolean isConn = (fDev.payload[2] & 0xFF) == 1;
                        if (!isConn) {
                            Log.i(TAG, toDisconnect.name + " confirmed disconnected after " + ((attempt + 1) * 100) + "ms");
                            confirmedDisconnected = true;
                            break;
                        }
                    }
                    if (!confirmedDisconnected) {
                        Log.w(TAG, toDisconnect.name + " disconnect confirmation timed out, proceeding");
                    }
                    // Minimal settling pause
                    Thread.sleep(100);
                } else {
                    Log.i(TAG, toDisconnect.name + " is already disconnected.");
                }
            }

            // Connect target
            if (toConnect != null) {
                if (!toConnect.connected) {
                    Log.i(TAG, "Connecting " + toConnect.name + " (idx=" + toConnect.index + ")");
                    out.write(buildFrame(CMD_CONNECT_DEVICE, new byte[]{(byte) toConnect.index}));
                    out.flush();
                    Frame ack = readFrameForCmd(in, CMD_CONNECT_DEVICE);
                    Log.i(TAG, "Connect ACK cmd: 0x" + Integer.toHexString(ack.cmd));
                    res.connectedDevice = toConnect;

                    // Poll to verify if toConnect becomes connected
                    boolean confirmedConnected = false;
                    for (int attempt = 0; attempt < 10; attempt++) {
                        Thread.sleep(150);
                        out.write(buildFrame(CMD_GET_DEVICE_INFO, new byte[]{(byte) toConnect.index}));
                        out.flush();
                        Frame fDev = readFrameForCmd(in, CMD_GET_DEVICE_INFO);
                        boolean isConn = (fDev.payload[2] & 0xFF) == 1;
                        if (isConn) {
                            Log.i(TAG, toConnect.name + " confirmed connected after " + ((attempt + 1) * 150) + "ms");
                            confirmedConnected = true;
                            break;
                        }
                        if (attempt == 4) {
                            // Re-trigger connect once if not connected yet after ~750ms
                            Log.i(TAG, "Re-triggering connect for " + toConnect.name);
                            out.write(buildFrame(CMD_CONNECT_DEVICE, new byte[]{(byte) toConnect.index}));
                            out.flush();
                            readFrameForCmd(in, CMD_CONNECT_DEVICE);
                        }
                    }

                    if (confirmedConnected) {
                        if (toDisconnect != null && toDisconnect.connected) {
                            msg.append("Disconnected ").append(toDisconnect.name).append(" & connected ").append(toConnect.name).append(".");
                        } else {
                            msg.append("Connected ").append(toConnect.name).append(".");
                        }
                    } else {
                        if (toDisconnect != null && toDisconnect.connected) {
                            msg.append("Disconnected ").append(toDisconnect.name).append(" & connecting ").append(toConnect.name).append("...");
                        } else {
                            msg.append("Connecting ").append(toConnect.name).append("...");
                        }
                    }
                } else {
                    Log.i(TAG, toConnect.name + " is already connected.");
                    msg.append(toConnect.name).append(" is already connected.");
                }
            }

            // Re-read updated devices state
            try {
                res.updatedDevices = readAllDevices(in, out);
                saveCachedDevices(context, res.updatedDevices);
            } catch (Exception e) {
                Log.w(TAG, "Could not re-read devices: " + e.getMessage());
            }

            res.success = true;
            res.message = msg.toString().trim();
            return res;

        } finally {
            try {
                socket.close();
            } catch (Exception ignored) {}
        }
    }

    public static SwitchResult connectDevice(Context context, String mac, int deviceIndex) throws Exception {
        return switchDevice(context, mac, null, String.valueOf(deviceIndex));
    }

    public static SwitchResult disconnectDevice(Context context, String mac, int deviceIndex) throws Exception {
        return switchDevice(context, mac, String.valueOf(deviceIndex), null);
    }

    public static byte[] buildFrame(int cmd, byte[] payload) {
        int len = (payload == null) ? 0 : payload.length;
        ByteBuffer buf = ByteBuffer.allocate(8 + len);
        buf.order(ByteOrder.BIG_ENDIAN);
        buf.put((byte) 0xFF);
        buf.put((byte) 0x03);
        buf.put((byte) 0x00);
        buf.put((byte) len);
        buf.putShort((short) GAIA_VENDOR);
        buf.putShort((short) cmd);
        if (payload != null && len > 0) {
            buf.put(payload);
        }
        return buf.array();
    }

    public static Frame readFrame(InputStream in) throws IOException {
        int b;
        while ((b = in.read()) != 0xFF) {
            if (b < 0) throw new EOFException("Stream closed while waiting for SOF");
        }

        byte[] header = new byte[7];
        int read = 0;
        while (read < 7) {
            int r = in.read(header, read, 7 - read);
            if (r < 0) throw new EOFException("Stream closed while reading header");
            read += r;
        }

        ByteBuffer hb = ByteBuffer.wrap(header).order(ByteOrder.BIG_ENDIAN);
        int ver = hb.get() & 0xFF;
        int flags = hb.get() & 0xFF;
        int len = hb.get() & 0xFF;
        int vendor = hb.getShort() & 0xFFFF;
        int cmd = hb.getShort() & 0xFFFF;

        byte[] payload = new byte[len];
        read = 0;
        while (read < len) {
            int r = in.read(payload, read, len - read);
            if (r < 0) throw new EOFException("Stream closed while reading payload");
            read += r;
        }

        Frame f = new Frame();
        f.cmd = cmd;
        f.payload = payload;
        return f;
    }

    public static Frame readFrameForCmd(InputStream in, int expectedCmd) throws IOException {
        long deadline = System.currentTimeMillis() + 4000;
        while (System.currentTimeMillis() < deadline) {
            Frame f = readFrame(in);
            int expectedAck = expectedCmd + 0x0100;
            if (f.cmd == expectedCmd || f.cmd == expectedAck || (f.cmd & 0x7FFF) == (expectedCmd & 0x7FFF)) {
                return f;
            }
            Log.d(TAG, "Ignoring asynchronous/unsolicited GAIA frame: 0x" + Integer.toHexString(f.cmd));
        }
        throw new IOException("Timed out waiting for response to 0x" + Integer.toHexString(expectedCmd));
    }

    public static void showToast(Context context, String msg) {
        new Handler(Looper.getMainLooper()).post(() -> {
            Toast.makeText(context.getApplicationContext(), msg, Toast.LENGTH_SHORT).show();
        });
    }
}

