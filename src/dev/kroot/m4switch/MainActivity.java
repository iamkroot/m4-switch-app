package dev.kroot.m4switch;

import android.app.Activity;
import android.app.AlertDialog;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;
import android.view.LayoutInflater;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ProgressBar;
import android.widget.TextView;
import android.widget.Toast;

import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int PERMISSION_REQ_CODE = 101;

    private TextView textMacAddress;
    private Button btnEditMac;
    private ProgressBar progressBar;
    private TextView textStatus;
    private Button btnRefresh;
    private TextView textLastUpdated;
    private TextView textConnectedHeader;
    private LinearLayout containerConnectedDevices;
    private TextView textNoConnected;
    private LinearLayout containerAllDevices;
    private TextView textNoCached;

    private final ExecutorService executor = Executors.newSingleThreadExecutor();
    private boolean isBusy = false;

    private final BroadcastReceiver cacheUpdatedReceiver = new BroadcastReceiver() {
        @Override
        public void onReceive(Context context, Intent intent) {
            runOnUiThread(() -> loadFromCacheAndRender());
        }
    };

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        setContentView(R.layout.activity_main);

        initViews();
        setupListeners();

        loadFromCacheAndRender();

        checkPermissionsAndAutoRefresh();
    }

    private void initViews() {
        textMacAddress = findViewById(R.id.text_mac_address);
        btnEditMac = findViewById(R.id.btn_edit_mac);
        progressBar = findViewById(R.id.progress_bar);
        textStatus = findViewById(R.id.text_status);
        btnRefresh = findViewById(R.id.btn_refresh);
        textLastUpdated = findViewById(R.id.text_last_updated);
        textConnectedHeader = findViewById(R.id.text_connected_header);
        containerConnectedDevices = findViewById(R.id.container_connected_devices);
        textNoConnected = findViewById(R.id.text_no_connected);
        containerAllDevices = findViewById(R.id.container_all_devices);
        textNoCached = findViewById(R.id.text_no_cached);

        updateMacDisplay();
    }

    private void setupListeners() {
        btnRefresh.setOnClickListener(v -> refreshDevices());
        btnEditMac.setOnClickListener(v -> showEditMacDialog());
    }

    private void updateMacDisplay() {
        String mac = M4Manager.getMac(this);
        textMacAddress.setText("Target: " + mac);
    }

    @Override
    protected void onResume() {
        super.onResume();
        IntentFilter filter = new IntentFilter(M4Manager.ACTION_CACHE_UPDATED);
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            registerReceiver(cacheUpdatedReceiver, filter, Context.RECEIVER_NOT_EXPORTED);
        } else {
            registerReceiver(cacheUpdatedReceiver, filter);
        }
        loadFromCacheAndRender();
    }

    @Override
    protected void onPause() {
        super.onPause();
        try {
            unregisterReceiver(cacheUpdatedReceiver);
        } catch (Exception ignored) {}
    }

    @Override
    protected void onDestroy() {
        super.onDestroy();
        executor.shutdown();
    }

    private void checkPermissionsAndAutoRefresh() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            if (checkSelfPermission(android.Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                requestPermissions(new String[]{android.Manifest.permission.BLUETOOTH_CONNECT}, PERMISSION_REQ_CODE);
                return;
            }
        }
        // If cache is empty, automatically trigger a refresh
        List<DeviceInfo> cached = M4Manager.getCachedDevices(this);
        if (cached.isEmpty()) {
            refreshDevices();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] grantResults) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults);
        if (requestCode == PERMISSION_REQ_CODE) {
            if (grantResults.length > 0 && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                refreshDevices();
            } else {
                Toast.makeText(this, "BLUETOOTH_CONNECT permission required to manage connections", Toast.LENGTH_LONG).show();
            }
        }
    }

    private void setBusyState(boolean busy, String statusMessage) {
        isBusy = busy;
        runOnUiThread(() -> {
            progressBar.setVisibility(busy ? View.VISIBLE : View.GONE);
            btnRefresh.setEnabled(!busy);
            btnEditMac.setEnabled(!busy);
            if (statusMessage != null) {
                textStatus.setText(statusMessage);
            }
        });
    }

    private void loadFromCacheAndRender() {
        List<DeviceInfo> cached = M4Manager.getCachedDevices(this);
        long timestamp = M4Manager.getCacheTime(this);
        if (timestamp > 0) {
            SimpleDateFormat sdf = new SimpleDateFormat("MMM d, HH:mm:ss", Locale.getDefault());
            textLastUpdated.setText("Last updated: " + sdf.format(new Date(timestamp)));
        } else {
            textLastUpdated.setText("Last updated: Never");
        }
        renderDevices(cached);
    }

    private void renderDevices(List<DeviceInfo> devices) {
        LayoutInflater inflater = LayoutInflater.from(this);

        // 1. Render Connected Devices
        List<DeviceInfo> connectedList = new ArrayList<>();
        if (devices != null) {
            for (DeviceInfo d : devices) {
                if (d.connected) {
                    connectedList.add(d);
                }
            }
        }

        textConnectedHeader.setText(String.format(Locale.getDefault(), "CURRENTLY CONNECTED (%d/2)", connectedList.size()));

        // Keep textNoConnected, remove other inflated views
        containerConnectedDevices.removeAllViews();
        if (connectedList.isEmpty()) {
            containerConnectedDevices.addView(textNoConnected);
            textNoConnected.setVisibility(View.VISIBLE);
        } else {
            textNoConnected.setVisibility(View.GONE);
            for (DeviceInfo dev : connectedList) {
                View item = inflater.inflate(R.layout.item_connected_device, containerConnectedDevices, false);
                TextView nameView = item.findViewById(R.id.text_connected_name);
                TextView badgeSelf = item.findViewById(R.id.badge_connected_self);
                Button btnDisc = item.findViewById(R.id.btn_connected_disconnect);

                nameView.setText(dev.name);
                if (dev.isSelf) {
                    badgeSelf.setVisibility(View.VISIBLE);
                } else {
                    badgeSelf.setVisibility(View.GONE);
                }

                btnDisc.setOnClickListener(v -> {
                    if (isBusy) return;
                    confirmDisconnect(dev);
                });

                containerConnectedDevices.addView(item);
            }
        }

        // 2. Render All Cached Devices
        containerAllDevices.removeAllViews();
        if (devices == null || devices.isEmpty()) {
            containerAllDevices.addView(textNoCached);
            textNoCached.setVisibility(View.VISIBLE);
        } else {
            textNoCached.setVisibility(View.GONE);
            for (DeviceInfo dev : devices) {
                View item = inflater.inflate(R.layout.item_device, containerAllDevices, false);
                LinearLayout rowLayout = item.findViewById(R.id.layout_device_row);
                TextView nameView = item.findViewById(R.id.text_device_name);
                TextView indexBadge = item.findViewById(R.id.badge_device_index);
                TextView statusBadge = item.findViewById(R.id.badge_device_status);
                TextView selfBadge = item.findViewById(R.id.badge_device_self);
                Button btnAction = item.findViewById(R.id.btn_device_action);

                nameView.setText(dev.name);
                indexBadge.setText("#" + dev.index);

                if (dev.isSelf) {
                    selfBadge.setVisibility(View.VISIBLE);
                } else {
                    selfBadge.setVisibility(View.GONE);
                }

                if (dev.connected) {
                    statusBadge.setText("● Connected");
                    statusBadge.setTextColor(0xFF81C784);
                    statusBadge.setBackgroundResource(R.drawable.badge_connected);

                    btnAction.setText("Disconnect");
                    btnAction.setTextColor(0xFFFF8A80);
                    btnAction.setBackgroundResource(R.drawable.btn_disconnect);

                    View.OnClickListener discListener = v -> {
                        if (isBusy) return;
                        confirmDisconnect(dev);
                    };
                    btnAction.setOnClickListener(discListener);
                    rowLayout.setOnClickListener(discListener);
                } else {
                    statusBadge.setText("○ Idle");
                    statusBadge.setTextColor(0xFFAAAAAA);
                    statusBadge.setBackgroundResource(R.drawable.badge_idle);

                    btnAction.setText("Connect");
                    btnAction.setTextColor(0xFFFFFFFF);
                    btnAction.setBackgroundResource(R.drawable.btn_primary);

                    View.OnClickListener connListener = v -> {
                        if (isBusy) return;
                        connectDevice(dev);
                    };
                    btnAction.setOnClickListener(connListener);
                    rowLayout.setOnClickListener(connListener);
                }

                containerAllDevices.addView(item);
            }
        }
    }

    private void refreshDevices() {
        if (isBusy) return;
        final String mac = M4Manager.getMac(this);
        setBusyState(true, "Connecting to MOMENTUM 4 (" + mac + ")...");

        executor.execute(() -> {
            try {
                List<DeviceInfo> devices = M4Manager.fetchDevices(this, mac);
                runOnUiThread(() -> {
                    setBusyState(false, "Refreshed " + devices.size() + " devices.");
                    loadFromCacheAndRender();
                    Toast.makeText(this, "Refreshed " + devices.size() + " devices from M4", Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusyState(false, "Refresh failed: " + e.getMessage());
                    Toast.makeText(this, "Refresh error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void connectDevice(DeviceInfo dev) {
        if (isBusy) return;
        final String mac = M4Manager.getMac(this);
        setBusyState(true, "Connecting to " + dev.name + "...");

        executor.execute(() -> {
            try {
                M4Manager.SwitchResult res = M4Manager.connectDevice(this, mac, dev.index);
                runOnUiThread(() -> {
                    setBusyState(false, res.message);
                    loadFromCacheAndRender();
                    Toast.makeText(this, res.message, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusyState(false, "Connect failed: " + e.getMessage());
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void confirmDisconnect(DeviceInfo dev) {
        new AlertDialog.Builder(this)
                .setTitle("Disconnect Device")
                .setMessage("Disconnect " + dev.name + " from MOMENTUM 4?")
                .setPositiveButton("Disconnect", (dialog, which) -> disconnectDevice(dev))
                .setNegativeButton("Cancel", null)
                .show();
    }

    private void disconnectDevice(DeviceInfo dev) {
        if (isBusy) return;
        final String mac = M4Manager.getMac(this);
        setBusyState(true, "Disconnecting " + dev.name + "...");

        executor.execute(() -> {
            try {
                M4Manager.SwitchResult res = M4Manager.disconnectDevice(this, mac, dev.index);
                runOnUiThread(() -> {
                    setBusyState(false, res.message);
                    loadFromCacheAndRender();
                    Toast.makeText(this, res.message, Toast.LENGTH_SHORT).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> {
                    setBusyState(false, "Disconnect failed: " + e.getMessage());
                    Toast.makeText(this, "Error: " + e.getMessage(), Toast.LENGTH_LONG).show();
                });
            }
        });
    }

    private void showEditMacDialog() {
        final String currentMac = M4Manager.getMac(this);

        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL);
        layout.setPadding(50, 20, 50, 10);

        final EditText input = new EditText(this);
        input.setSingleLine(true);
        input.setText(currentMac);
        input.setSelection(currentMac.length());
        layout.addView(input);

        // Check bonded Bluetooth devices to allow 1-click select
        BluetoothAdapter adapter = BluetoothAdapter.getDefaultAdapter();
        if (adapter != null && adapter.isEnabled()) {
            try {
                Set<BluetoothDevice> bonded = adapter.getBondedDevices();
                if (bonded != null && !bonded.isEmpty()) {
                    TextView bondedHeader = new TextView(this);
                    bondedHeader.setText("Or select paired device:");
                    bondedHeader.setTextSize(12);
                    bondedHeader.setPadding(0, 20, 0, 10);
                    layout.addView(bondedHeader);

                    for (BluetoothDevice d : bonded) {
                        String name = d.getName() != null ? d.getName() : "Unknown";
                        if (name.contains("MOMENTUM") || name.contains("Sennheiser") || name.contains("M4") || name.contains("Accentum")) {
                            Button btnBonded = new Button(this);
                            btnBonded.setText(name + " (" + d.getAddress() + ")");
                            btnBonded.setTextSize(11);
                            btnBonded.setOnClickListener(v -> input.setText(d.getAddress()));
                            layout.addView(btnBonded);
                        }
                    }
                }
            } catch (SecurityException ignored) {}
        }

        new AlertDialog.Builder(this)
                .setTitle("Headphone Bluetooth MAC")
                .setView(layout)
                .setPositiveButton("Save", (dialog, which) -> {
                    String newMac = input.getText().toString().trim().toUpperCase();
                    if (!newMac.isEmpty()) {
                        M4Manager.setMac(this, newMac);
                        updateMacDisplay();
                        refreshDevices();
                    }
                })
                .setNegativeButton("Cancel", null)
                .show();
    }
}
