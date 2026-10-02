package io.github.yebei199.btrearm.diagnostics;

import android.Manifest;
import android.app.Notification;
import android.app.NotificationManager;
import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothManager;
import android.content.BroadcastReceiver;
import android.content.Context;
import android.content.Intent;
import android.content.IntentFilter;
import android.content.pm.PackageManager;
import android.hardware.input.InputManager;
import android.os.Build;
import android.os.Bundle;
import android.os.Handler;
import android.os.HandlerThread;
import android.os.SystemClock;
import android.view.InputDevice;

import java.io.File;
import java.time.Instant;
import java.util.UUID;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.atomic.AtomicLong;

/** 被动诊断：事件异步落盘，独立心跳区分安静链路与采集停止。 */
public final class Diagnostics {
    /** 四片各 2 MiB，限制设备占用；队列限制回调风暴期间的内存。 */
    private static final int FILE_BYTES = 2 * 1024 * 1024;
    private static final int FILE_COUNT = 4;
    private static final int QUEUE_SIZE = 256;
    private static final long HEARTBEAT_MS = 5_000;
    /** 一个应用进程只有一个采集器，不随 Activity 重建重复注册。 */
    private static volatile Diagnostics instance;
    /** 会话及顺序号用于跨重启和跨线程定位事件。 */
    private final String session = UUID.randomUUID().toString();
    private final AtomicLong sequence = new AtomicLong();
    private final AtomicLong dropped = new AtomicLong();
    /** 磁盘写入和只读状态采样各用独立线程，不占蓝牙回调或界面线程。 */
    private final PersistentLog store;
    private final ThreadPoolExecutor writer;
    private final Handler sampler;
    private final Context context;
    /** 最近一次成功写入的墙钟；通知只在写入实际完成后更新。 */
    private volatile String lastWritten = "none";
    /** 心跳存活不等于观察器注册成功，降级必须能从文件及通知看见。 */
    private volatile String observers = "starting";

    /** 只保留 ApplicationContext，创建线程但不操作蓝牙连接。 */
    private Diagnostics(Context context) {
        this.context = context.getApplicationContext();
        store = new PersistentLog(new File(context.getFilesDir(), "diagnostics"), FILE_BYTES, FILE_COUNT);
        writer = new ThreadPoolExecutor(1, 1, 0, TimeUnit.SECONDS,
                new ArrayBlockingQueue<>(QUEUE_SIZE), runnable -> new Thread(runnable, "rearm-log"));
        HandlerThread thread = new HandlerThread("rearm-diagnostics");
        thread.start();
        sampler = new Handler(thread.getLooper());
    }

    /** 初始化失败不阻止应用启动，错误同时留给系统日志。 */
    public static synchronized void start(Context context) {
        if (instance != null) return;
        try {
            instance = new Diagnostics(context);
            record("session_start", "model=" + Build.MODEL + " sdk=" + Build.VERSION.SDK_INT
                    + " build=" + Build.DISPLAY + " pid=" + android.os.Process.myPid());
            instance.observe();
            instance.observers = "ready";
            record("observers_ready", "bluetooth,input");
        } catch (RuntimeException e) {
            if (instance != null) {
                instance.observers = "failed:" + e.getClass().getSimpleName();
                record("observer_start_error", e.toString());
            }
            android.util.Log.e("btrearm", "diagnostics startup failed", e);
        }
    }

    /** 队列满时记丢弃计数，绝不等待磁盘或把错误送回连接调用方。 */
    public static void record(String event, String detail) {
        Diagnostics current = instance;
        if (current == null) return;
        String wall = Instant.now().toString();
        String bounded = detail == null ? "null" : detail.substring(0, Math.min(detail.length(), 4096));
        String line = "wall=" + wall + " elapsed_ms=" + SystemClock.elapsedRealtime()
                + " session=" + current.session + " seq=" + current.sequence.incrementAndGet()
                + " thread=" + Thread.currentThread().getName() + " event=" + event + " " + bounded;
        try {
            current.writer.execute(() -> {
                if (current.store.append(line)) current.lastWritten = wall;
                if (event.equals("heartbeat")) current.updateNotification();
            });
        } catch (RejectedExecutionException e) {
            current.dropped.incrementAndGet();
        }
    }

    /** 原始广播和输入设备变化独立记录，不消费原有断开原因或改变状态机。 */
    private void observe() {
        sampler.post(new Runnable() {
            /** 每次异常也安排下一次采样，避免一次权限错误让采集静默死亡。 */
            @Override public void run() {
                String state;
                try {
                    state = snapshot();
                } catch (RuntimeException e) {
                    state = "snapshot_error=" + e;
                }
                record("heartbeat", "observers=" + observers + " " + state + " dropped=" + dropped.get()
                        + " write_failures=" + store.failures() + " last_error=" + store.lastError()
                        + " last_written=" + lastWritten);
                sampler.postDelayed(this, HEARTBEAT_MS);
            }
        });
        IntentFilter filter = new IntentFilter();
        filter.addAction(BluetoothDevice.ACTION_ACL_CONNECTED);
        filter.addAction(BluetoothDevice.ACTION_ACL_DISCONNECTED);
        filter.addAction(BluetoothDevice.ACTION_BOND_STATE_CHANGED);
        filter.addAction(BluetoothAdapter.ACTION_STATE_CHANGED);
        context.registerReceiver(new BroadcastReceiver() {
            /** 同时记录原始 extras，ROM 没有原因码时保持缺失，不猜测。 */
            @Override public void onReceive(Context c, Intent intent) {
                record("bluetooth_broadcast", intent.getAction() + " extras=" + extras(intent));
            }
        }, filter, null, sampler);
        InputManager input = context.getSystemService(InputManager.class);
        if (input == null) throw new IllegalStateException("InputManager unavailable");
        input.registerInputDeviceListener(new InputManager.InputDeviceListener() {
            /** 设备加入时记录系统输入层事实。 */
            @Override public void onInputDeviceAdded(int id) { inputEvent("input_added", id); }
            /** 移除事件保留 id，移除后可能已查不到名称。 */
            @Override public void onInputDeviceRemoved(int id) { inputEvent("input_removed", id); }
            /** 能力变化可以揭示游戏映射层重新接管。 */
            @Override public void onInputDeviceChanged(int id) { inputEvent("input_changed", id); }
        }, sampler);
    }

    /** 强制解包 IPC extras；Bundle.toString 可能只给字节数，丢掉设备和原因码。 */
    @SuppressWarnings("deprecation")
    private static String extras(Intent intent) {
        try {
            Bundle extras = intent.getExtras();
            if (extras == null) return "absent";
            StringBuilder value = new StringBuilder();
            for (String key : extras.keySet()) {
                value.append(key).append('=');
                try {
                    value.append(extras.get(key));
                } catch (RuntimeException e) {
                    value.append("unreadable:").append(e.getClass().getSimpleName());
                }
                value.append(';');
            }
            return value.toString();
        } catch (RuntimeException e) {
            return "unreadable:" + e.getClass().getSimpleName();
        }
    }

    /** 只记录设备元数据，不采集用户的按键或触摸内容。 */
    private void inputEvent(String event, int id) {
        InputDevice device = InputDevice.getDevice(id);
        record(event, "id=" + id + " device=" + describe(device));
    }

    /** 心跳包含当前真实输入设备列表，不把“没事件”当作“手柄在线”。 */
    private String snapshot() {
        boolean permission = Build.VERSION.SDK_INT < 31 || context.checkSelfPermission(
                Manifest.permission.BLUETOOTH_CONNECT) == PackageManager.PERMISSION_GRANTED;
        BluetoothManager manager = context.getSystemService(BluetoothManager.class);
        BluetoothAdapter adapter = manager == null ? null : manager.getAdapter();
        StringBuilder value = new StringBuilder("bt_permission=").append(permission)
                .append(" bt_enabled=").append(permission && adapter != null && adapter.isEnabled());
        for (int id : InputDevice.getDeviceIds()) {
            value.append(" input={").append(describe(InputDevice.getDevice(id))).append('}');
        }
        return value.toString();
    }

    /** 记录 id、名称及能力供 ACL/HID 时间线对照。 */
    private static String describe(InputDevice device) {
        if (device == null) return "absent";
        return "id=" + device.getId() + ",name=" + device.getName()
                + ",sources=" + device.getSources() + ",vendor=" + device.getVendorId()
                + ",product=" + device.getProductId();
    }

    /** 沿用前台服务的通知，以最后成功落盘时间报告健康情况。 */
    private void updateNotification() {
        try {
            NotificationManager manager = context.getSystemService(NotificationManager.class);
            if (manager == null || manager.getNotificationChannel("rearm") == null) return;
            String status = "日志写入 " + lastWritten + " · 丢弃 " + dropped.get()
                    + " · 写入失败 " + store.failures() + " · 监听 " + observers;
            manager.notify(1, new Notification.Builder(context, "rearm")
                    .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                    .setContentTitle("蓝牙布防运行中")
                    .setContentText(status).setStyle(new Notification.BigTextStyle().bigText(status))
                    .setOnlyAlertOnce(true).setOngoing(true).build());
        } catch (RuntimeException e) {
            android.util.Log.e("btrearm", "diagnostics notification failed", e);
        }
    }
}
