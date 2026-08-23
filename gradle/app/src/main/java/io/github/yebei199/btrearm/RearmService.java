package io.github.yebei199.btrearm;

import android.app.Notification;
import android.app.NotificationChannel;
import android.app.NotificationManager;
import android.app.Service;
import android.content.Intent;
import android.content.pm.ServiceInfo;
import android.os.IBinder;

/**
 * 保活用前台服务。布防的 GATT 客户端都是进程内对象,进程被冻结或回收,
 * 布防就没了 —— 这个服务只是一张「别冻我」的凭据,不做任何事。
 * connectedDevice 类型对 targetSdk 34 是强制声明的,资格由已批的
 * BLUETOOTH_CONNECT 权限满足。
 */
public class RearmService extends Service {

    @Override
    public int onStartCommand(Intent intent, int flags, int startId) {
        NotificationChannel ch =
                new NotificationChannel("rearm", "布防保活", NotificationManager.IMPORTANCE_MIN);
        getSystemService(NotificationManager.class).createNotificationChannel(ch);
        Notification n = new Notification.Builder(this, "rearm")
                .setSmallIcon(android.R.drawable.stat_sys_data_bluetooth)
                .setContentTitle("蓝牙布防运行中")
                .build();
        startForeground(1, n, ServiceInfo.FOREGROUND_SERVICE_TYPE_CONNECTED_DEVICE);
        // 不用 START_STICKY:布防的引擎与 Context 都随界面创建而初始化,进程被系统
        // 回收后,单独被拉回来的服务里什么都没有 —— 它会挂着「运行中」的通知却不
        // 扫描、不重试。那比不工作更糟,因为它在骗人。宁可安静地不在。
        return START_NOT_STICKY;
    }

    @Override
    public IBinder onBind(Intent intent) {
        return null;
    }
}
