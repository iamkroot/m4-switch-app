package dev.kroot.m4switch;

import org.json.JSONException;
import org.json.JSONObject;

public class DeviceInfo {
    public int index;
    public int priority;
    public boolean connected;
    public String name;
    public boolean isSelf;

    public DeviceInfo() {
        this.name = "";
    }

    public DeviceInfo(int index, int priority, boolean connected, String name, boolean isSelf) {
        this.index = index;
        this.priority = priority;
        this.connected = connected;
        this.name = name != null ? name : "";
        this.isSelf = isSelf;
    }

    public JSONObject toJson() {
        try {
            JSONObject obj = new JSONObject();
            obj.put("index", index);
            obj.put("priority", priority);
            obj.put("connected", connected);
            obj.put("name", name != null ? name : "");
            obj.put("isSelf", isSelf);
            return obj;
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    public static DeviceInfo fromJson(JSONObject obj) {
        if (obj == null) return null;
        DeviceInfo d = new DeviceInfo();
        d.index = obj.optInt("index", 0);
        d.priority = obj.optInt("priority", 0);
        d.connected = obj.optBoolean("connected", false);
        d.name = obj.optString("name", "");
        d.isSelf = obj.optBoolean("isSelf", false);
        return d;
    }

    @Override
    public String toString() {
        return "[" + index + "] " + name + (connected ? " (CONNECTED)" : " (idle)") + (isSelf ? " [Self]" : "");
    }
}
