package io.github.yebei199.btrearm;

import android.Manifest;
import android.app.NativeActivity;
import android.content.Intent;
import android.content.pm.PackageManager;
import android.os.Build;
import android.os.Bundle;

/**
 * 极薄的 NativeActivity 子类。界面全在 Rust(Slint)里;这里只负责三件事:
 * 把 Context 交给 {@link Rearm}、要 BLUETOOTH_CONNECT 权限、权限到手后
 * 恢复上次的布防并拉起保活服务。
 */
public class MainActivity extends NativeActivity {

    private static final int REQ_BT = 1;

    @Override
    protected void onCreate(Bundle savedInstanceState) {
        super.onCreate(savedInstanceState);
        Rearm.attach(this);
        if (Build.VERSION.SDK_INT >= 31
                && checkSelfPermission(Manifest.permission.BLUETOOTH_CONNECT)
                        != PackageManager.PERMISSION_GRANTED) {
            requestPermissions(new String[] {Manifest.permission.BLUETOOTH_CONNECT}, REQ_BT);
        } else {
            ready();
        }
    }

    @Override
    public void onRequestPermissionsResult(int requestCode, String[] permissions, int[] results) {
        super.onRequestPermissionsResult(requestCode, permissions, results);
        if (requestCode == REQ_BT
                && results.length > 0
                && results[0] == PackageManager.PERMISSION_GRANTED) {
            ready();
        }
    }

    private void ready() {
        Rearm.armSaved();
        // 布防挂在进程里,进程要活过切后台 —— 前台服务是那张「别冻我」的凭据。
        startForegroundService(new Intent(this, RearmService.class));
    }
}
