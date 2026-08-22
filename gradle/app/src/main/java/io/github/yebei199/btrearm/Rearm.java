package io.github.yebei199.btrearm;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothGattCallback;
import android.bluetooth.BluetoothManager;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.le.BluetoothLeScanner;
import android.bluetooth.le.ScanCallback;
import android.bluetooth.le.ScanFilter;
import android.bluetooth.le.ScanResult;
import android.bluetooth.le.ScanSettings;
import android.content.Context;
import android.content.SharedPreferences;
import android.os.SystemClock;
import android.util.Log;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;

/**
 * 布防核心:盯着广播,在系统不出手时替它把设备连回来。
 *
 * <p>背景:红魔平板 5 Pro(RedMagicOS 11.5 / Android 16)不给已配对的 BLE HID
 * 设备做后台回连 —— 手柄开机后连续广播可连接报文,系统一次连接尝试都不发起。
 *
 * <p>做法是用一个按地址过滤的 BLE 扫描守着:扫到目标设备的广播、且它当前没有
 * 连接,就主动 {@code connectGatt} 把 ACL 链路拉起来,已配对 HID 的输入服务随即
 * 附着。这正是用户要的语义 —— **扫描到 + 系统没连** 才动手,系统自己连上了就
 * 什么都不做。
 *
 * <p>为什么不是 {@code connectGatt(autoConnect=true)}:那条路把设备交给控制器的
 * 后台连接名单,在这台平板上试过,布防挂上了却毫无反应(而且
 * {@code getRemoteDevice(MAC)} 拿到的设备对象地址类型是 public,与手柄实际使用的
 * 随机静态地址对不上,名单等的是一个永不出现的设备)。现在设备对象一律从已配对
 * 列表里取,地址类型由配对记录给出。
 *
 * <p>所有方法都可能被两个线程碰(Rust UI 经 JNI、扫描回调、Activity 生命周期),
 * 一律 synchronized —— 这里没有任何吞吐可言,锁粗一点换简单。
 */
public final class Rearm {
    private static final String TAG = "btrearm";

    /** 同一台设备两次主动连接之间的最小间隔。连接建立本身要几秒,别把它打断。 */
    private static final long RETRY_GAP_MS = 8000;

    private static Context ctx;
    /** 布防名单。值是当前挂着的 GATT 客户端,没连上时为 null。 */
    private static final Map<String, BluetoothGatt> armed = new HashMap<>();
    /** 每台设备的人话状态,给界面看的。 */
    private static final Map<String, String> state = new HashMap<>();
    /** 上次发起连接的时刻,用于 RETRY_GAP_MS 节流。 */
    private static final Map<String, Long> lastTry = new HashMap<>();
    private static boolean scanning;

    private Rearm() {}

    static synchronized void attach(Context c) {
        ctx = c.getApplicationContext();
    }

    /** 把上次留下的布防名单重新挂上。要等蓝牙权限批下来才能调。 */
    static synchronized void armSaved() {
        for (String mac : prefs().getStringSet("macs", Collections.emptySet())) {
            armed.put(mac, null);
            state.put(mac, "等待广播");
        }
        syncScan();
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
                boolean isArmed = armed.containsKey(mac);
                String s = isArmed
                        ? state.getOrDefault(mac, "等待广播")
                        : (connected(d) ? "系统已连接" : "未布防");
                sb.append(mac).append('\t')
                        .append(name == null ? "?" : name).append('\t')
                        .append(isArmed ? '1' : '0').append('\t')
                        .append(s).append('\n');
            }
        } catch (SecurityException e) {
            // 权限没批。界面显示空列表,批完权限下一轮轮询自然恢复。
            return "";
        }
        return sb.toString();
    }

    public static synchronized void toggle(String mac) {
        if (armed.containsKey(mac)) {
            BluetoothGatt g = armed.remove(mac);
            closeQuietly(g);
            state.put(mac, "未布防");
            Log.i(TAG, "disarmed " + mac);
        } else {
            armed.put(mac, null);
            state.put(mac, "等待广播");
            Log.i(TAG, "armed " + mac);
        }
        prefs().edit().putStringSet("macs", new HashSet<>(armed.keySet())).apply();
        syncScan();
    }

    /** 布防名单非空就开扫,空了就停 —— 扫描是这里唯一的耗电项。 */
    private static void syncScan() {
        BluetoothLeScanner scanner = scanner();
        if (scanner == null) return;
        try {
            if (!armed.isEmpty() && !scanning) {
                List<ScanFilter> filters = new ArrayList<>();
                for (String mac : armed.keySet()) {
                    filters.add(new ScanFilter.Builder().setDeviceAddress(mac).build());
                }
                // 按地址过滤的扫描在后台是允许的(未过滤的会被系统掐掉)。
                // LOW_POWER 已足够:手柄开机后会持续广播好几分钟。
                ScanSettings settings = new ScanSettings.Builder()
                        .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                        .build();
                scanner.startScan(filters, settings, SCAN);
                scanning = true;
                Log.i(TAG, "scan started for " + armed.size() + " device(s)");
            } else if (armed.isEmpty() && scanning) {
                scanner.stopScan(SCAN);
                scanning = false;
                Log.i(TAG, "scan stopped");
            } else if (scanning) {
                // 名单变了,重启扫描换一套过滤器。
                scanner.stopScan(SCAN);
                scanning = false;
                syncScan();
            }
        } catch (SecurityException e) {
            Log.w(TAG, "scan control failed: " + e);
        }
    }

    private static final ScanCallback SCAN = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            BluetoothDevice d = result.getDevice();
            synchronized (Rearm.class) {
                String mac = d.getAddress();
                if (!armed.containsKey(mac)) return;

                // 系统已经连上了 —— 什么都不做,这正是布防该让路的时候。
                if (connected(d)) {
                    state.put(mac, "已连接");
                    return;
                }

                long now = SystemClock.elapsedRealtime();
                Long last = lastTry.get(mac);
                if (last != null && now - last < RETRY_GAP_MS) return;
                lastTry.put(mac, now);

                connect(mac);
            }
        }

        @Override
        public void onScanFailed(int errorCode) {
            Log.w(TAG, "scan failed: " + errorCode);
            synchronized (Rearm.class) {
                scanning = false;
            }
        }
    };

    /** 主动把 ACL 链路拉起来。设备对象从已配对列表取 —— 地址类型必须正确。 */
    private static void connect(String mac) {
        BluetoothDevice d = bonded(mac);
        if (d == null) return;
        try {
            closeQuietly(armed.get(mac));
            // autoConnect=false:这是"现在就连",广播刚到手,目标就在旁边。
            BluetoothGatt g = d.connectGatt(ctx, false, CALLBACK, BluetoothDevice.TRANSPORT_LE);
            armed.put(mac, g);
            state.put(mac, "正在连接");
            Log.i(TAG, "connecting " + mac);
        } catch (SecurityException e) {
            Log.w(TAG, "connect " + mac + " failed: " + e);
        }
    }

    /**
     * 从已配对列表里取设备对象。
     *
     * <p>不用 {@code getRemoteDevice(MAC)}:那样拿到的对象地址类型是 public,而这只
     * 手柄用的是随机静态地址(抓包实测),类型不符时连接请求发给的是一个永不出现
     * 的设备。已配对列表里的对象带着配对记录里的正确类型。
     */
    private static BluetoothDevice bonded(String mac) {
        BluetoothAdapter adapter = adapter();
        if (adapter == null) return null;
        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                if (d.getAddress().equals(mac)) return d;
            }
        } catch (SecurityException e) {
            Log.w(TAG, "bonded lookup failed: " + e);
        }
        return null;
    }

    /** 这台设备当前有没有链路 —— GATT 与 HID 任一在连即算已连。 */
    private static boolean connected(BluetoothDevice d) {
        if (ctx == null) return false;
        BluetoothManager m = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        if (m == null) return false;
        try {
            for (int profile : new int[] {BluetoothProfile.GATT, BluetoothProfile.HID_DEVICE}) {
                if (m.getConnectedDevices(profile).contains(d)) return true;
            }
        } catch (SecurityException | IllegalArgumentException e) {
            // 某些 profile 在部分机型上不给查,当作没连。
        }
        return false;
    }

    private static void closeQuietly(BluetoothGatt g) {
        if (g == null) return;
        try {
            g.close();
        } catch (SecurityException e) {
            // close 也要 BLUETOOTH_CONNECT;走到这里权限必然已批过,防御而已。
        }
    }

    private static BluetoothAdapter adapter() {
        if (ctx == null) return null;
        BluetoothManager m = (BluetoothManager) ctx.getSystemService(Context.BLUETOOTH_SERVICE);
        return m == null ? null : m.getAdapter();
    }

    private static BluetoothLeScanner scanner() {
        BluetoothAdapter a = adapter();
        return a == null ? null : a.getBluetoothLeScanner();
    }

    private static SharedPreferences prefs() {
        return ctx.getSharedPreferences("rearm", Context.MODE_PRIVATE);
    }

    private static final BluetoothGattCallback CALLBACK = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            String mac = g.getDevice().getAddress();
            synchronized (Rearm.class) {
                if (newState == BluetoothProfile.STATE_CONNECTED) {
                    state.put(mac, "已连接");
                } else {
                    state.put(mac, "等待广播");
                    // 断开后释放客户端,下一次广播再重新连 —— 留着不放会占资源,
                    // 而且这台平板的栈对复用客户端的行为并不可靠。
                    if (armed.containsKey(mac)) {
                        closeQuietly(armed.put(mac, null));
                    }
                }
            }
            Log.i(TAG, mac + " newState=" + newState + " status=" + status);
        }
    };
}
