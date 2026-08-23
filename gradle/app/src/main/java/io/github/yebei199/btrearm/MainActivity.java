package io.github.yebei199.btrearm;

import android.Manifest;
import android.app.NativeActivity;
import android.content.Context;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

import java.util.ArrayList;
import java.util.List;

/**
 * 极薄的 NativeActivity 子类。界面与布防决策都在 Rust 里,这里只负责三件事:
 * 把 Context 交给 {@link Rearm}、要蓝牙权限、权限到手后通知 Rust 重开扫描
 * 并拉起保活服务。
 */
public class MainActivity extends NativeActivity {

    private static final int REQ_BT = 1;

    /**
     * 在 super.onCreate 之前就把 Context 交出去。
     *
     * <p>NativeActivity 会在 onCreate 里启动原生线程,而 Rust 那边一上来就要读存盘
     * 的布防名单 —— 晚一步交,那次读取拿到的是 null Context,名单静默丢失。
     */
    @Override
    protected void attachBaseContext(Context base) {
        super.attachBaseContext(base);
        Rearm.attach(base);
    }

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        // 一次问全:分两次调用的话,系统只会弹出先到的那个,后一个被静默丢弃。
        List<String> want = new ArrayList<>();
        if (Build.VERSION.SDK_INT >= 31) {
            want.add(Manifest.permission.BLUETOOTH_CONNECT);
            want.add(Manifest.permission.BLUETOOTH_SCAN);
        }
        if (Build.VERSION.SDK_INT >= 33) {
            want.add(Manifest.permission.POST_NOTIFICATIONS);
        }
        want.removeIf(p -> checkSelfPermission(p) == PackageManager.PERMISSION_GRANTED);
        if (want.isEmpty()) {
            ready();
        } else {
            requestPermissions(want.toArray(new String[0]), REQ_BT);
        }
    }

    private boolean missingBtPermission() {
        return checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED
                || checkSelfPermission(Manifest.permission.BLUETOOTH_SCAN)
                        != PackageManager.PERMISSION_GRANTED;
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        // 通知没批只是收不到提醒,布防照常跑,所以这里只看蓝牙那两项。
        if (requestCode == REQ_BT && !missingBtPermission()) {
            ready();
        }
    }

    private void ready() {
        // 特权连接要等 Shizuku 的绑定器到位,越早挂上监听越好。
        Privileged.init(this);
        // 权限到手之前发起的开扫必然失败,这里让 Rust 按当前名单重来一次。
        Rearm.resumeScan();
        // 布防活在进程里,进程要活过切后台 —— 前台服务是那张「别冻我」的凭据。
        startForegroundService(new Intent(this, RearmService.class));
    }
}
