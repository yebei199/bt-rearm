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

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * 安卓侧的转发壳。**这里不做任何判断** —— 谁该连、什么时候连、要不要让路,
 * 全部由 Rust 的 engine 模块决定(那边有单元测试,这边没有)。
 *
 * <p>之所以还需要 Java:安卓的 {@link ScanCallback} 与 {@link BluetoothGattCallback}
 * 是抽象类,JNI 无法从 Rust 实现(动态代理只支持接口),必须有个 Java 子类把事件
 * 转进来。除此之外这个类只提供三种能力:开关扫描、发起连接、查已配对设备。
 *
 * <p>调用方向:
 * <ul>
 *   <li>Rust → Java:{@link #startScan}、{@link #stopScan}、{@link #connect}、
 *       {@link #bondedDevices}、{@link #isConnected}
 *   <li>Java → Rust:{@link #nativeOnAdvertisement}、{@link #nativeOnConnectionChange}
 * </ul>
 */
public final class Rearm {

    private static Context ctx;
    /** 当前挂着的 GATT 客户端,按 MAC 存,断开时释放。 */
    private static final Map<String, BluetoothGatt> gatts = new HashMap<>();
    private static boolean scanning;

    private Rearm() {}

    public static synchronized void attach(Context c) {
        ctx = c.getApplicationContext();
    }

    // ---- Rust 调过来的:平台能力,不含判断 ----

    /**
     * 按地址过滤开扫。地址过滤的扫描在后台是允许的(未过滤的会被系统掐掉)。
     *
     * @param macList 换行分隔的 MAC 列表。用字符串而不是 String[]:JNI 造数组要
     *     多一圈 API,而这里的量小到不值得。
     */
    public static synchronized void startScan(String macList) {
        String[] macs = macList.isEmpty() ? new String[0] : macList.split("\n");
        BluetoothLeScanner scanner = scanner();
        if (scanner == null) return;
        try {
            if (scanning) {
                scanner.stopScan(SCAN);
                scanning = false;
            }
            List<ScanFilter> filters = new ArrayList<>();
            for (String mac : macs) {
                filters.add(new ScanFilter.Builder().setDeviceAddress(mac).build());
            }
            // LOW_POWER 已足够:手柄开机后会持续广播好几分钟。
            ScanSettings settings = new ScanSettings.Builder()
                    .setScanMode(ScanSettings.SCAN_MODE_LOW_POWER)
                    .build();
            scanner.startScan(filters, settings, SCAN);
            scanning = true;
        } catch (SecurityException e) {
            nativeOnError("开扫失败: " + e);
        }
    }

    public static synchronized void stopScan() {
        BluetoothLeScanner scanner = scanner();
        if (scanner == null || !scanning) return;
        try {
            scanner.stopScan(SCAN);
            scanning = false;
        } catch (SecurityException e) {
            nativeOnError("停扫失败: " + e);
        }
    }

    /**
     * 主动把 ACL 链路拉起来。
     *
     * <p>设备对象从已配对列表取,不用 {@code getRemoteDevice(MAC)}:那样拿到的对象
     * 地址类型是 public,而手柄用的是随机静态地址(抓包实测),类型不符时连接请求
     * 发给的是一个永不出现的设备。已配对列表里的对象带着配对记录里的正确类型。
     */
    public static synchronized void connect(String mac) {
        BluetoothDevice d = bonded(mac);
        if (d == null) {
            nativeOnError(mac + " 不在已配对列表里");
            return;
        }
        try {
            closeQuietly(gatts.remove(mac));
            // autoConnect=false:广播刚到手,目标就在旁边,这是"现在就连"。
            // autoConnect=true 的后台名单在这台平板上实测无效。
            gatts.put(mac, d.connectGatt(ctx, false, CALLBACK, BluetoothDevice.TRANSPORT_LE));
        } catch (SecurityException e) {
            nativeOnError("连接 " + mac + " 失败: " + e);
        }
    }

    /** 行协议:{@code MAC\t名字},一行一台。设备名只有 Java 侧拿得到。 */
    public static synchronized String bondedDevices() {
        BluetoothAdapter adapter = adapter();
        if (adapter == null) return "";
        StringBuilder sb = new StringBuilder();
        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                String name = d.getName();
                sb.append(d.getAddress()).append('\t')
                        .append(name == null ? "?" : name).append('\n');
            }
        } catch (SecurityException e) {
            return "";
        }
        return sb.toString();
    }

    /** 这台设备当前有没有链路 —— GATT 与 HID 任一在连即算已连。 */
    public static synchronized boolean isConnected(String mac) {
        BluetoothDevice d = bonded(mac);
        if (d == null || ctx == null) return false;
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

    // ---- 存盘。名单内容由 Rust 决定,这里只负责读写 ----

    public static synchronized String loadArmed() {
        if (ctx == null) return "";
        return String.join("\n", prefs().getStringSet("macs", Collections.emptySet()));
    }

    /** @param macList 换行分隔的 MAC 列表,理由同 startScan。 */
    public static synchronized void saveArmed(String macList) {
        if (ctx == null) return;
        Set<String> set = new HashSet<>();
        if (!macList.isEmpty()) {
            Collections.addAll(set, macList.split("\n"));
        }
        prefs().edit().putStringSet("macs", set).apply();
    }

    /** 权限到手后让 Rust 按当前布防名单重开扫描。 */
    public static void resumeScan() {
        nativeResumeScan();
    }

    // ---- Java 转给 Rust 的事件 ----

    private static native void nativeResumeScan();

    private static native void nativeOnAdvertisement(String mac);

    private static native void nativeOnConnectionChange(String mac, boolean connected);

    private static native void nativeOnError(String message);

    private static final ScanCallback SCAN = new ScanCallback() {
        @Override
        public void onScanResult(int callbackType, ScanResult result) {
            // 判断一概不做,原样转给 Rust。
            nativeOnAdvertisement(result.getDevice().getAddress());
        }

        @Override
        public void onScanFailed(int errorCode) {
            synchronized (Rearm.class) {
                scanning = false;
            }
            nativeOnError("扫描失败,错误码 " + errorCode);
        }
    };

    private static final BluetoothGattCallback CALLBACK = new BluetoothGattCallback() {
        @Override
        public void onConnectionStateChange(BluetoothGatt g, int status, int newState) {
            String mac = g.getDevice().getAddress();
            boolean connected = newState == BluetoothProfile.STATE_CONNECTED;
            if (!connected) {
                synchronized (Rearm.class) {
                    closeQuietly(gatts.remove(mac));
                }
            }
            nativeOnConnectionChange(mac, connected);
        }
    };

    // ---- 内部工具 ----

    private static BluetoothDevice bonded(String mac) {
        BluetoothAdapter adapter = adapter();
        if (adapter == null) return null;
        try {
            for (BluetoothDevice d : adapter.getBondedDevices()) {
                if (d.getAddress().equals(mac)) return d;
            }
        } catch (SecurityException e) {
            // 权限没批,调用方会看到"不在已配对列表里"。
        }
        return null;
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
}
