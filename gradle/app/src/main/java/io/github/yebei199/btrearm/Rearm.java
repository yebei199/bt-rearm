package io.github.yebei199.btrearm;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.content.Context;
import android.content.SharedPreferences;
import android.util.Log;

import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.Map;

/**
 * 布防核心:替系统给选中的 BLE 设备挂后台自动回连。
 *
 * <p>背景:红魔平板5 Pro(RedMagicOS 11.5 / Android 16)的蓝牙栈不给已配对的
 * BLE HID 设备做后台回连 —— 手柄开机后连续广播,系统一次连接尝试都不发起,
 * 用户只能手动去设置里点。而一个挂着 {@code autoConnect=true} 的 GATT 客户端
 * 会把目标设备塞进控制器的后台连接名单:设备一广播,ACL 链路即被拉起,
 * 已配对 HID 设备的输入服务随即附着。这个类做的就是替系统补上这一步。
 *
 * <p>所有方法都可能被两个线程碰(Rust UI 经 JNI、Activity 生命周期),
 * 一律 synchronized —— 这里没有任何吞吐可言,锁粗一点换简单。
 */
public final class Rearm {
    private static final String TAG = "btrearm";

    private static Context ctx;
    /** 布防中的设备 → 挂着的 GATT 客户端。close 之前回连一直有效。 */
    private static final Map<String, BluetoothGatt> armed = new HashMap<>();
    /** 每台设备的人话状态,给界面看的。 */
    private static final Map<String, String> state = new HashMap<>();

    private Rearm() {}

    static synchronized void attach(Context c) {
        ctx = c.getApplicationContext();
    }

    /** 把上次留下的布防名单重新挂上。要等 BLUETOOTH_CONNECT 批下来才能调。 */
    static synchronized void armSaved() {
        for (String mac : prefs().getStringSet("macs", Collections.emptySet())) {
            arm(mac);
        }
    }

    /** 行协议:{@code MAC\t名字\t是否布防\t状态},一行一台。Rust 侧照此解析。 */
    public static synchronized String list() {
        BluetoothAdapter adapter = adapter();
        if (adapter == null) return "";
        StringBuilder sb = new StringBuilder();
        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                String mac = d.getAddress();
                String name = d.getName();
                sb.append(mac).append('\t')
                        .append(name == null ? "?" : name).append('\t')
                        .append(armed.containsKey(mac) ? '1' : '0').append('\t')
                        .append(state.getOrDefault(mac, "未布防")).append('\n');
            }
        } catch (SecurityException e) {
            // 权限没批。界面会显示空列表,批完权限下一轮轮询自然恢复。
            return "";
        }
        return sb.toString();
    }

    public static synchronized void toggle(String mac) {
        if (armed.containsKey(mac)) {
            disarm(mac);
        } else {
            arm(mac);
        }
        prefs().edit().putStringSet("macs", new HashSet<>(armed.keySet())).apply();
    }

    private static void arm(String mac) {
        if (ctx == null || armed.containsKey(mac)) return;
        BluetoothAdapter adapter = adapter();
        if (adapter == null) return;
        try {
            BluetoothDevice d = adapter.getRemoteDevice(mac);
            // TRANSPORT_LE:目标是 BLE HID 设备;不指定的话双模地址可能走 BR/EDR。
            BluetoothGatt g = d.connectGatt(ctx, true, CALLBACK, BluetoothDevice.TRANSPORT_LE);
            if (g != null) {
                armed.put(mac, g);
                state.put(mac, "等待广播");
                Log.i(TAG, "armed " + mac);
            }
        } catch (SecurityException | IllegalArgumentException e) {
            Log.w(TAG, "arm " + mac + " failed: " + e);
        }
    }

    private static void disarm(String mac) {
        BluetoothGatt g = armed.remove(mac);
        if (g != null) {
            try {
                g.close();
            } catch (SecurityException e) {
                // close 也要 BLUETOOTH_CONNECT;走到这里权限必然已批过,防御而已。
            }
        }
        state.put(mac, "未布防");
        Log.i(TAG, "disarmed " + mac);
    }

    private static BluetoothAdapter adapter() {
        if (ctx == null) return null;
        BluetoothManager m = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        return m == null ? null : m.getAdapter();
    }

    private static SharedPreferences prefs() {
        return ctx.getSharedPreferences("rearm", Context.MODE_PRIVATE);
    }

    private static final BluetoothGattCallback CALLBACK = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            String mac = g.getDevice().getAddress();
            synchronized (Rearm.class) {
                state.put(mac, newState == BluetoothProfile.STATE_CONNECTED
                        ? "已连接" : "等待广播");
            }
            // autoConnect=true 的客户端断开后自动回到等待态,无需在此重连。
            Log.i(TAG, mac + " newState=" + newState + " status=" + status);
        }
    };
}
