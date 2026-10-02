package io.github.yebei199.btrearm;

import android.bluetooth.BluetoothAdapter;
import android.bluetooth.BluetoothDevice;
import android.bluetooth.BluetoothGatt;
import android.bluetooth.BluetoothProfile;
import android.bluetooth.IBluetoothGatt;
import android.content.Context;
import android.content.Intent;
import rikka.shizuku.Shizuku;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Future;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.TimeUnit;

/** 每条用例从真实业务入口到 GATT 对象或 JNI 事件；平台状态只从边界注入。 */
public final class ObserverLifecycleTest {
    /** 固定有效地址只用作内存数据，不占用共享设备。 */
    private static final String MAC = "C0:11:22:33:44:55";
    /** 第二台配对设备用于验证 MAC 隔离。 */
    private static final String OTHER_MAC = "C0:11:22:33:44:66";
    /** JNI 实际发出的事件，独立 JVM 内独占。 */
    private static final List<String> nativeEvents = Collections.synchronizedList(new ArrayList<>());
    /** 当前用例的真实用户服务。 */
    private static PrivilegedConnect service;
    /** 当前用例的平台注册与广播边界。 */
    private static Context context;
    /** 当前用例的配对记录对象。 */
    private static BluetoothDevice device;

    /** 记录真实 JNI 出口，不在这里推导任何业务结果。 */
    public static void recordNative(String mac, String event) { nativeEvents.add(event + ":" + mac); }

    /** 从公开初始化进入，保留两侧真实缓存和 AIDL 服务方法。 */
    private static void setup() {
        service = new PrivilegedConnect();
        device = new BluetoothDevice(MAC);
        BluetoothAdapter.bonded.add(device);
        context = new Context();
        Rearm.attach(context);
        Shizuku.service = service;
        Privileged.init(context);
    }

    /** 从既有 Rust 巡检的 Java 调用终点进入，取得实际新建的对象。 */
    private static BluetoothGatt watch() {
        Rearm.watchLink(MAC);
        require(!BluetoothGatt.created.isEmpty(), "watch did not create a GATT observer");
        return BluetoothGatt.created.get(BluetoothGatt.created.size() - 1);
    }

    /** 按真实平台注册的 filter 投递状态广播，不直接改 tuned 或 gatts。 */
    private static void state(int value) {
        BluetoothAdapter.enabled = value == BluetoothAdapter.STATE_ON;
        context.broadcast(new Intent(BluetoothAdapter.ACTION_STATE_CHANGED)
                .putExtra(BluetoothAdapter.EXTRA_STATE, value));
    }

    /** 以已观测的状态序列失效并恢复系统 GATT 服务。 */
    private static void restartBluetooth() {
        state(BluetoothAdapter.STATE_TURNING_OFF);
        BluetoothAdapter.gattService.die();
        BluetoothAdapter.gattService = null;
        state(BluetoothAdapter.STATE_OFF);
        state(BluetoothAdapter.STATE_TURNING_ON);
        BluetoothAdapter.gattService = new IBluetoothGatt();
        state(BluetoothAdapter.STATE_ON);
    }

    /** ACL 广播走真实 Rearm receiver 和 Privileged 原因消费。 */
    private static void acl(boolean connected) {
        context.broadcast(new Intent(connected ? BluetoothDevice.ACTION_ACL_CONNECTED
                : BluetoothDevice.ACTION_ACL_DISCONNECTED).putExtra(BluetoothDevice.EXTRA_DEVICE, device));
    }

    /** 断言实际注册保持原有只读、LE、1M 参数，不允许主动连接。 */
    private static void requirePassive(BluetoothGatt gatt) {
        require(gatt.opportunistic, "observer is not opportunistic");
        require(Boolean.FALSE.equals(gatt.autoConnect), "observer registered active autoConnect");
        require(gatt.transport == 2 && gatt.phy == 1, "observer changed transport/PHY");
        require(gatt.device == device, "observer lost bonded device identity/address type");
        require(gatt.registrations == 1, "observer did not register exactly once");
    }

    /** 保持断言原始可读失败原因。 */
    private static void require(boolean condition, String message) {
        if (!condition) throw new AssertionError(message);
    }

    /** 缺任一层失效会让恢复后的实际附着数仍为一。 */
    public static void testServiceRestartReattachesOnce() throws Exception {
        setup();
        BluetoothGatt old = watch();
        old.emit(0, BluetoothProfile.STATE_CONNECTED);
        restartBluetooth();
        BluetoothGatt current = watch();
        Rearm.watchLink(MAC);
        require(BluetoothGatt.created.size() == 2, "restored patrol must create exactly one new observer");
        require(current != old && current.service == BluetoothAdapter.gattService, "patrol reused stale service handle");
        require(old.closes == 1 && current.closes == 0, "restart must close old object only");
        requirePassive(current);
    }

    /** 单独直接服务入口也不能信任死 binder 的旧 MAC 条目。 */
    public static void testDeadServiceHandleIsNotAlreadyWatching() {
        setup();
        BluetoothGatt old = watch();
        BluetoothAdapter.gattService.die();
        BluetoothAdapter.gattService = null;
        String result = service.watchLink(MAC);
        require(!result.startsWith("链路观察客户端已挂着") && !result.startsWith("已挂"),
                "dead service reported observer attached");
        require(old.closes == 1, "dead service handle was not released");
        BluetoothAdapter.gattService = new IBluetoothGatt();
        service.watchLink(MAC);
        require(BluetoothGatt.created.size() == 2, "direct service entry failed to rebuild observer");
    }

    /** 无 ACL/状态广播时，系统服务接口替换也必须穿透 app 缓存恢复。 */
    public static void testServiceReplacementWithoutAclReattaches() {
        setup();
        BluetoothGatt old = watch();
        BluetoothAdapter.gattService.die();
        BluetoothAdapter.gattService = new IBluetoothGatt();
        BluetoothGatt current = watch();
        require(BluetoothGatt.created.size() == 2 && current != old, "patrol trusted stale tuned after service replacement");
        require(current.service == BluetoothAdapter.gattService && old.closes == 1, "wrong service identity or leaked old handle");
        requirePassive(current);
    }

    /** 正常重复巡检必须复用观察器而非主动重连或重注册。 */
    public static void testRepeatedPatrolKeepsPassiveObserver() throws Exception {
        setup();
        BluetoothGatt first = watch();
        first.emit(0, BluetoothProfile.STATE_CONNECTED);
        Rearm.watchLink(MAC);
        service.watchLink(MAC);
        require(BluetoothGatt.created.size() == 1 && first.closes == 0, "healthy observer duplicated or closed");
        requirePassive(first);
    }

    /** 关闭状态不应创建观察器，恢复后既有调用能再次附着。 */
    public static void testDisabledAdapterDefersAttachment() {
        setup();
        state(BluetoothAdapter.STATE_OFF);
        Rearm.watchLink(MAC);
        require(BluetoothGatt.created.isEmpty(), "disabled adapter created an observer");
        state(BluetoothAdapter.STATE_ON);
        requirePassive(watch());
    }

    /** 无系统 GATT 服务时不能留下虚假成功缓存。 */
    public static void testUnavailableServiceRetriesAfterRecovery() {
        setup();
        BluetoothAdapter.gattService = null;
        Rearm.watchLink(MAC);
        require(BluetoothGatt.created.isEmpty(), "unavailable service created usable observer");
        BluetoothAdapter.gattService = new IBluetoothGatt();
        requirePassive(watch());
    }

    /** 注册被拒必须释放已构造对象，下一巡检再试。 */
    public static void testRejectedRegistrationReleasesAndRetries() {
        setup();
        BluetoothGatt.accept = false;
        Rearm.watchLink(MAC);
        require(BluetoothGatt.created.size() == 1, "rejected registration did not reach platform boundary");
        BluetoothGatt rejected = BluetoothGatt.created.get(0);
        require(rejected.closes == 1, "rejected registration leaked GATT object");
        BluetoothGatt.accept = true;
        BluetoothGatt current = watch();
        require(BluetoothGatt.created.size() == 2 && current != rejected, "rejected observer suppressed retry");
        requirePassive(current);
    }

    /** 133是平台注册/建链失败状态；无CONNECTED或ACL时，巡检仍须重挂只读观察器。 */
    public static void testAsyncRegistrationFailureRetriesWithoutAcl() throws Exception {
        setup();
        BluetoothGatt failed = watch();
        requirePassive(failed);
        // 只注入系统注册失败回调，不把133当作手柄HCI原因，也不清理业务缓存。
        failed.emit(133, BluetoothProfile.STATE_DISCONNECTED);
        require(failed.closes == 1, "asynchronously failed observer was not released");
        BluetoothGatt current = watch();
        require(BluetoothGatt.created.size() == 2 && current != failed,
                "async registration failure without ACL suppressed next patrol");
        require(current.service == BluetoothAdapter.gattService && current.closes == 0,
                "retry did not attach to the current service");
        requirePassive(current);
        current.emit(0, BluetoothProfile.STATE_CONNECTED);
        Rearm.watchLink(MAC);
        Rearm.watchLink(MAC);
        require(BluetoothGatt.created.size() == 2 && current.closes == 0,
                "healthy retry was closed or duplicated by repeated patrol");
        requirePassive(current);
    }

    /** 注册异常与被拒具有相同回收/重试语义。 */
    public static void testRegistrationExceptionReleasesAndRetries() {
        setup();
        BluetoothGatt.throwOnRegister = true;
        Rearm.watchLink(MAC);
        BluetoothGatt rejected = BluetoothGatt.created.get(0);
        require(rejected.closes == 1, "registration exception leaked GATT object");
        BluetoothGatt.throwOnRegister = false;
        require(watch() != rejected && BluetoothGatt.created.size() == 2, "exception suppressed next patrol");
    }

    /** Shizuku 服务替换需要清 app 缓存，新服务真实创建观察器。 */
    public static void testShizukuReplacementClearsAppCache() {
        setup();
        BluetoothGatt first = watch();
        Shizuku.replace(new PrivilegedConnect());
        BluetoothGatt current = watch();
        require(current != first && BluetoothGatt.created.size() == 2, "new user service blocked by tuned cache");
        requirePassive(current);
    }

    /** 无 ServiceConnection 断开通知的 binder 死亡也必须允许恢复。 */
    public static void testShizukuDeathClearsAppCache() {
        setup();
        BluetoothGatt first = watch();
        Shizuku.dieAndRestore(new PrivilegedConnect());
        require(watch() != first && BluetoothGatt.created.size() == 2, "binder death left stale tuned cache");
    }

    /** Binder 替身只延迟真实 watch 结果，旧成功返回不能重新写入失效世代。 */
    public static void testInFlightSuccessCannotRestoreInvalidatedCache() throws Exception {
        setup();
        DelayedReply gate = new DelayedReply(service);
        Shizuku.replace(gate);
        var executor = Executors.newSingleThreadExecutor();
        var call = executor.submit(() -> Rearm.watchLink(MAC));
        try {
            require(gate.accepted.await(5, TimeUnit.SECONDS), "watch did not reach delayed Binder reply");
            restartBluetooth();
            gate.release.countDown();
            call.get(5, TimeUnit.SECONDS);
            BluetoothGatt current = watch();
            require(BluetoothGatt.created.size() == 2, "late success repopulated invalidated app cache");
            require(current.service == BluetoothAdapter.gattService, "late reply retained old service");
        } finally {
            gate.release.countDown(); executor.shutdownNow();
        }
    }

    /** 用户服务已换世代时，在途旧成功不能挡住新服务附着。 */
    public static void testInFlightReplyFromReplacedShizukuIsIgnored() throws Exception {
        setup();
        DelayedReply gate = new DelayedReply(service);
        Shizuku.replace(gate);
        var executor = Executors.newSingleThreadExecutor();
        var call = executor.submit(() -> Rearm.watchLink(MAC));
        try {
            require(gate.accepted.await(5, TimeUnit.SECONDS), "watch did not reach delayed Binder reply");
            Shizuku.replace(new PrivilegedConnect());
            gate.release.countDown();
            call.get(5, TimeUnit.SECONDS);
            watch();
            require(BluetoothGatt.created.size() == 2, "old Binder reply blocked replacement service observer");
        } finally {
            gate.release.countDown(); executor.shutdownNow();
        }
    }

    /** 旧 DISCONNECTED 不能误删新 MAC 条目或污染原因。 */
    public static void testOldDisconnectCannotRemoveNewObserver() throws Exception {
        setup();
        BluetoothGatt old = watch();
        old.emit(8, BluetoothProfile.STATE_DISCONNECTED);
        require(service.lastDisconnectReason(MAC) == 8, "current reason missing");
        acl(false);
        BluetoothGatt current = watch();
        old.emit(19, BluetoothProfile.STATE_DISCONNECTED);
        service.watchLink(MAC);
        require(BluetoothGatt.created.size() == 2 && current.closes == 0, "old callback deleted new observer");
        require(old.closes == 1, "old callback closed old object repeatedly");
        require(service.lastDisconnectReason(MAC) == -1, "old reason contaminated current connection");
        current.emit(8, BluetoothProfile.STATE_DISCONNECTED);
        require(service.lastDisconnectReason(MAC) == 8, "current observer lost reason ownership");
    }

    /** 旧 CONNECTED 同样不能影响新观察器，后续旧断开仍被隔离。 */
    public static void testOldConnectedCannotReviveOldObserver() throws Exception {
        setup();
        BluetoothGatt old = watch();
        old.emit(8, BluetoothProfile.STATE_DISCONNECTED);
        service.lastDisconnectReason(MAC);
        acl(false);
        BluetoothGatt current = watch();
        old.emit(0, BluetoothProfile.STATE_CONNECTED);
        old.emit(19, BluetoothProfile.STATE_DISCONNECTED);
        service.watchLink(MAC);
        require(BluetoothGatt.created.size() == 2 && current.closes == 0, "old CONNECTED revived ownership");
        require(service.lastDisconnectReason(MAC) == -1, "old callback published a reason");
    }

    /** 拒绝注册的回调不拥有当前连接的原因槽。 */
    public static void testRejectedObserverCannotPublishReason() throws Exception {
        setup();
        BluetoothGatt.accept = false;
        Rearm.watchLink(MAC);
        BluetoothGatt rejected = BluetoothGatt.created.get(0);
        rejected.emit(19, BluetoothProfile.STATE_DISCONNECTED);
        require(service.lastDisconnectReason(MAC) == -1, "rejected registration published reason");
    }

    /** 当前断开原因取一次即清，不允许重复使用。 */
    public static void testCurrentReasonConsumedOnce() throws Exception {
        setup();
        watch().emit(8, BluetoothProfile.STATE_DISCONNECTED);
        require(service.lastDisconnectReason(MAC) == 8, "wrong current reason");
        require(service.lastDisconnectReason(MAC) == -1, "reason consumed twice");
    }

    /** 新连接建立/附着后不能消费上一连接未取的原因。 */
    public static void testNewConnectionDropsUnconsumedReason() throws Exception {
        setup();
        watch().emit(19, BluetoothProfile.STATE_DISCONNECTED);
        acl(true);
        watch();
        require(service.lastDisconnectReason(MAC) == -1, "new connection inherited previous reason");
    }

    /** ACL先到保留unknown；晚到原因不能留下给下一连接。 */
    public static void testAclBeforeCallbackKeepsUnknownAndDropsLateReason() throws Exception {
        setup();
        BluetoothGatt old = watch();
        nativeEvents.clear();
        acl(false);
        require(nativeEvents.contains("disconnected:" + MAC) && !nativeEvents.contains("peer-left:" + MAC),
                "ACL without reason guessed peer-left");
        require(nativeEvents.stream().anyMatch(value -> value.contains("原因 -1")), "missing unknown diagnostic");
        old.emit(19, BluetoothProfile.STATE_DISCONNECTED);
        acl(true);
        watch();
        require(service.lastDisconnectReason(MAC) == -1, "late callback reason survived into new connection");
    }

    /** ACL早于旧原因且新观察器已挂好时，旧回调绝不能串到新连接。 */
    public static void testLateReasonAfterReattachIsIgnored() throws Exception {
        setup();
        BluetoothGatt old = watch();
        acl(false);
        acl(true);
        BluetoothGatt current = watch();
        old.emit(19, BluetoothProfile.STATE_DISCONNECTED);
        require(service.lastDisconnectReason(MAC) == -1, "old connection supplied next connection reason");
        current.emit(8, BluetoothProfile.STATE_DISCONNECTED);
        require(service.lastDisconnectReason(MAC) == 8, "old callback removed current reason owner");
    }

    /** 原因先到时，既有0x13出口仍通知Rust让路。 */
    public static void testRemoteTerminatedKeepsPeerLeftPolicy() throws Exception {
        setup();
        watch().emit(19, BluetoothProfile.STATE_DISCONNECTED);
        nativeEvents.clear();
        acl(false);
        require(nativeEvents.contains("peer-left:" + MAC) && !nativeEvents.contains("disconnected:" + MAC),
                "reason 19 changed existing peer-left policy");
        require(service.lastDisconnectReason(MAC) == -1, "ACL did not consume reason");
    }

    /** 原因先到时，0x08仍走已有断联出口，附着不得主动连接。 */
    public static void testTimeoutKeepsDisconnectPolicy() throws Exception {
        setup();
        watch().emit(8, BluetoothProfile.STATE_DISCONNECTED);
        nativeEvents.clear();
        acl(false);
        require(nativeEvents.contains("disconnected:" + MAC) && !nativeEvents.contains("peer-left:" + MAC),
                "reason 8 changed existing disconnect policy");
    }

    /** 未附着/未知原因保持原有unknown出口。 */
    public static void testUnknownKeepsDisconnectPolicy() {
        setup();
        nativeEvents.clear();
        acl(false);
        require(nativeEvents.contains("disconnected:" + MAC) && !nativeEvents.contains("peer-left:" + MAC),
                "unknown changed existing disconnect policy");
    }

    /** 服务失效后的旧回调不能把旧原因交给恢复的新连接。 */
    public static void testServiceRestartDiscardsOldReason() throws Exception {
        setup();
        BluetoothGatt old = watch();
        restartBluetooth();
        old.emit(19, BluetoothProfile.STATE_DISCONNECTED);
        watch();
        require(service.lastDisconnectReason(MAC) == -1, "restarted service inherited stale reason");
    }

    /** 未配对地址不创建观察器，另一设备仍能正常附着。 */
    public static void testUnbondedAddressDoesNotAttach() {
        setup();
        service.watchLink(OTHER_MAC);
        require(BluetoothGatt.created.isEmpty(), "observer attached an unbonded device");
        requirePassive(watch());
    }

    /** 按MAC消费互不干扰，当前设备断开不能关闭另一设备观察器。 */
    public static void testReasonsAndObserversStayPerDevice() throws Exception {
        setup();
        BluetoothAdapter.bonded.add(new BluetoothDevice(OTHER_MAC));
        BluetoothGatt first = watch();
        service.watchLink(OTHER_MAC);
        BluetoothGatt other = BluetoothGatt.created.get(1);
        first.emit(19, BluetoothProfile.STATE_DISCONNECTED);
        require(service.lastDisconnectReason(OTHER_MAC) == -1 && other.closes == 0, "reason/close crossed MAC ownership");
        require(service.lastDisconnectReason(MAC) == 19, "reading other MAC consumed current reason");
        service.watchLink(OTHER_MAC);
        require(BluetoothGatt.created.size() == 2, "other observer was removed");
    }

    /** 已记19的回调卡在平台close时，真实ACL仍须立即消费并发出peer-left。 */
    public static void testRecordedPeerLeftSurvivesBlockedClose() throws Exception {
        setup();
        BluetoothGatt old = watch();
        var gate = new BluetoothGatt.Gate();
        old.closeGate = gate;
        var calls = Executors.newSingleThreadExecutor();
        try {
            old.emitQueued(19, BluetoothProfile.STATE_DISCONNECTED);
            require(gate.entered.await(5, TimeUnit.SECONDS), "callback did not enter blocked close");
            awaitCall(calls.submit(() -> acl(false)), "ACL waited for callback close instead of consuming recorded 19");
            require(nativeEvents.stream().filter(value -> value.equals("peer-left:" + MAC)).count() == 1,
                    "recorded 19 did not produce exactly one peer-left");
            require(!nativeEvents.contains("disconnected:" + MAC), "recorded 19 degraded to unknown policy");
            int consumed = awaitCall(calls.submit(() -> service.lastDisconnectReason(MAC)),
                    "reason read waited for callback close");
            require(consumed == -1, "ACL did not consume recorded reason once");
        } finally {
            releaseCalls(gate, calls);
            old.handler.drain();
        }
        require(old.closes == 1 && service.lastDisconnectReason(MAC) == -1,
                "released callback left an old reason or repeated close");
    }

    /** ACL自身退休健康观察器时，平台close阻塞不能阻挡出口或让迟到回调发布原因。 */
    public static void testAclRetiresHealthyObserverBeforeBlockedClose() throws Exception {
        setup();
        BluetoothGatt old = watch();
        old.emit(0, BluetoothProfile.STATE_CONNECTED);
        var gate = new BluetoothGatt.Gate();
        old.closeGate = gate;
        var calls = Executors.newSingleThreadExecutor();
        try {
            Future<?> aclCall = calls.submit(() -> acl(false));
            require(gate.entered.await(5, TimeUnit.SECONDS), "ACL retirement did not enter platform close");
            awaitCall(aclCall, "ACL waited for its own observer close");
            require(nativeEvents.contains("disconnected:" + MAC) && !nativeEvents.contains("peer-left:" + MAC),
                    "ACL without a reason changed unknown policy");
            old.emitQueued(19, BluetoothProfile.STATE_DISCONNECTED);
        } finally {
            releaseCalls(gate, calls);
            old.handler.drain();
        }
        require(old.closes == 1 && service.lastDisconnectReason(MAC) == -1,
                "retired healthy observer republished a late reason");
        BluetoothGatt current = watch();
        old.emit(19, BluetoothProfile.STATE_DISCONNECTED);
        service.watchLink(MAC);
        require(BluetoothGatt.created.size() == 2 && current != old && current.closes == 0,
                "late callback removed the replacement observer");
        current.emit(8, BluetoothProfile.STATE_DISCONNECTED);
        require(service.lastDisconnectReason(MAC) == 8, "replacement observer lost current reason ownership");
    }

    /** 另一MAC注册阻塞时，本设备已记录的19仍能从真实ACL及时消费。 */
    public static void testRecordedPeerLeftSurvivesOtherRegistration() throws Exception {
        setup();
        BluetoothGatt old = watch();
        old.emit(19, BluetoothProfile.STATE_DISCONNECTED);
        BluetoothAdapter.bonded.add(new BluetoothDevice(OTHER_MAC));
        var gate = new BluetoothGatt.Gate();
        BluetoothGatt.nextRegistrationGate = gate;
        var calls = Executors.newFixedThreadPool(2);
        try {
            Future<?> registration = calls.submit(() -> Rearm.watchLink(OTHER_MAC));
            require(gate.entered.await(5, TimeUnit.SECONDS), "other MAC did not enter blocked registration");
            awaitCall(calls.submit(() -> acl(false)), "recorded reason waited for another MAC registration");
            require(nativeEvents.contains("peer-left:" + MAC) && !nativeEvents.contains("disconnected:" + MAC),
                    "blocked registration degraded recorded 19");
            require(awaitCall(calls.submit(() -> service.lastDisconnectReason(MAC)),
                    "reason getter waited for another MAC registration") == -1, "reason consumed twice");
            gate.release.countDown();
            awaitCall(registration, "released registration did not finish");
        } finally {
            releaseCalls(gate, calls);
        }
        BluetoothGatt other = BluetoothGatt.created.get(1);
        other.handler.drain();
        service.watchLink(OTHER_MAC);
        require(BluetoothGatt.created.size() == 2 && other.closes == 0,
                "ACL retirement crossed MAC ownership into the blocked registration");
        require(service.lastDisconnectReason(MAC) == -1, "released registration restored consumed reason");
    }

    /** ACL断开在注册返回前退休旧连接，旧成功不能重新获得观察所有权。 */
    public static void testAclDisconnectionRetiresInFlightRegistration() throws Exception {
        requireInFlightRegistrationRetired(() -> acl(false), "disconnected:");
    }

    /** ACL新连接同样隔离上一连接在途注册，既有连接出口保持。 */
    public static void testAclConnectionRetiresInFlightRegistration() throws Exception {
        requireInFlightRegistrationRetired(() -> acl(true), "connected:");
    }

    /** 适配器失效并恢复时，在途旧注册也不能重新取得已经退休的服务世代。 */
    public static void testAdapterInvalidationRetiresInFlightRegistration() throws Exception {
        requireInFlightRegistrationRetired(() -> {
            state(BluetoothAdapter.STATE_TURNING_OFF);
            state(BluetoothAdapter.STATE_ON);
        }, null);
    }

    /** 平台注册门闩跨过真实退休信号后才释放，检验旧返回、迟到回调与新对象去重。 */
    private static void requireInFlightRegistrationRetired(Runnable retire, String expectedEvent) throws Exception {
        setup();
        var gate = new BluetoothGatt.Gate();
        BluetoothGatt.nextRegistrationGate = gate;
        var calls = Executors.newFixedThreadPool(2);
        BluetoothGatt old = null;
        try {
            Future<?> registration = calls.submit(() -> Rearm.watchLink(MAC));
            require(gate.entered.await(5, TimeUnit.SECONDS), "observer did not enter blocked registration");
            old = BluetoothGatt.created.get(0);
            awaitCall(calls.submit(retire), "retirement waited for in-flight registration");
            boolean unchangedPolicy = expectedEvent == null
                    ? !nativeEvents.contains("connected:" + MAC) && !nativeEvents.contains("disconnected:" + MAC)
                    : nativeEvents.contains(expectedEvent + MAC);
            require(unchangedPolicy && !nativeEvents.contains("peer-left:" + MAC),
                    "retirement changed its existing policy");
            gate.release.countDown();
            awaitCall(registration, "released registration did not finish");
        } finally {
            releaseCalls(gate, calls);
            if (old != null) old.handler.drain();
        }
        require(old != null && old.closes == 1, "in-flight old success regained ownership or leaked its object");
        old.emit(19, BluetoothProfile.STATE_DISCONNECTED);
        require(service.lastDisconnectReason(MAC) == -1, "retired registration published a late reason");
        BluetoothGatt current = watch();
        old.emit(19, BluetoothProfile.STATE_DISCONNECTED);
        Rearm.watchLink(MAC);
        require(BluetoothGatt.created.size() == 2 && current != old && current.closes == 0,
                "in-flight old return or callback removed the replacement");
        requirePassive(current);
        current.emit(8, BluetoothProfile.STATE_DISCONNECTED);
        require(service.lastDisconnectReason(MAC) == 8, "current registration lost reason ownership");
    }

    /** 有界等待只作失败保护，超时明确归因于阻塞的业务入口。 */
    private static <T> T awaitCall(Future<T> call, String message) throws Exception {
        try {
            return call.get(3, TimeUnit.SECONDS);
        } catch (TimeoutException failure) {
            throw new AssertionError(message, failure);
        }
    }

    /** 每用例finally先解除平台阻塞再等自己的调用退出，保证没有遗留任务。 */
    private static void releaseCalls(BluetoothGatt.Gate gate, ExecutorService calls) throws Exception {
        gate.release.countDown();
        calls.shutdown();
        require(calls.awaitTermination(5, TimeUnit.SECONDS), "test calls did not finish after releasing platform gate");
    }

    /** 只延迟传输返回；内部仍调用真实 PrivilegedConnect，不制造观察成功。 */
    private static final class DelayedReply extends IPrivilegedConnect.Stub {
        /** 被包装的真实服务。 */
        private final PrivilegedConnect delegate;
        /** 真实服务完成注册后的同步点。 */
        final CountDownLatch accepted = new CountDownLatch(1);
        /** 测试允许Binder旧返回继续的同步点。 */
        final CountDownLatch release = new CountDownLatch(1);
        /** 只对首次返回施加延迟。 */
        private boolean delay = true;
        /** 不替换业务方法，只包装传输。 */
        DelayedReply(PrivilegedConnect delegate) { this.delegate = delegate; }
        /** 禁止测试中请求连接。 */
        public String connect(String mac) { throw PlatformViolations.forbidden("observer requested active connection"); }
        /** 真实服务完成后暂停返回，模拟跨进程在途结果。 */
        public String watchLink(String mac) throws Exception {
            String result = delegate.watchLink(mac);
            if (delay) {
                delay = false; accepted.countDown();
                if (!release.await(5, TimeUnit.SECONDS)) throw new AssertionError("delayed Binder reply not released");
            }
            return result;
        }
        /** 消费仍走真实服务。 */
        public int lastDisconnectReason(String mac) { return delegate.lastDisconnectReason(mac); }
        /** 只转发平台失效信号到真实服务，不在替身中清业务状态。 */
        public void invalidateObservers() { delegate.invalidateObservers(); }
        /** 只转发ACL退休到真实服务。 */
        public int retireConnection(String mac) { return delegate.retireConnection(mac); }
        /** 子JVM退出统一回收，不调用生产System.exit。 */
        public void destroy() {}
    }

    /** 稳定收集全部声明用例；不初始化任何生产类或运行行为。 */
    private static List<String> tests() {
        return java.util.Arrays.stream(ObserverLifecycleTest.class.getDeclaredMethods())
                .filter(method -> java.lang.reflect.Modifier.isPublic(method.getModifiers())
                        && method.getParameterCount() == 0 && method.getReturnType() == void.class
                        && method.getName().startsWith("test"))
                .map(Method::getName).sorted().toList();
    }

    /** 输出一个子JVM的真实退出码和完整日志。 */
    private record Result(String name, int exit, String output) {}

    /** 一个case独占子进程和日志，超时失败，不做重试。 */
    private static Result runCase(String name, Path work) throws Exception {
        Path log = work.resolve(name + ".log");
        Process child = new ProcessBuilder(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-Djava.library.path=" + System.getProperty("java.library.path"),
                "-cp", System.getProperty("java.class.path"), ObserverLifecycleTest.class.getName(), name)
                .redirectErrorStream(true).redirectOutput(log.toFile()).start();
        try {
            if (!child.waitFor(20, TimeUnit.SECONDS)) {
                child.destroyForcibly(); child.waitFor();
                return new Result(name, 124, Files.readString(log) + "\nchild JVM timed out");
            }
            return new Result(name, child.exitValue(), Files.readString(log));
        } finally {
            if (child.isAlive()) { child.destroyForcibly(); child.waitFor(); }
        }
    }

    /** JUnit正文/属性统一转义，避免日志破坏结果文件。 */
    private static String xml(String value) {
        return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }

    /** 环境给并行预算；无预算立即失败，不默认为整台机器或串行。 */
    private static int workers() {
        String budget = System.getenv("OBSERVER_TEST_WORKERS");
        if (budget == null) budget = System.getenv("NIX_BUILD_CORES");
        if (budget == null || !budget.matches("[1-9][0-9]*")) {
            throw new IllegalArgumentException("set OBSERVER_TEST_WORKERS to the available test worker budget");
        }
        return Math.min(Integer.parseInt(budget), tests().size());
    }

    /** 有界并行跑所有用例，由父进程统一产JUnit，任何子进程失败都会使套件非零。 */
    private static int suite(Path junit) throws Exception {
        var pool = Executors.newFixedThreadPool(workers());
        Path work = Files.createTempDirectory("btrearm-observer-suite-");
        try {
            var jobs = new ArrayList<java.util.concurrent.Future<Result>>();
            for (String name : tests()) jobs.add(pool.submit(() -> runCase(name, work)));
            var results = new ArrayList<Result>();
            for (var job : jobs) results.add(job.get());
            long failures = results.stream().filter(result -> result.exit != 0).count();
            StringBuilder output = new StringBuilder("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n");
            output.append("<testsuite name=\"observer\" tests=\"").append(results.size())
                    .append("\" failures=\"").append(failures).append("\">\n");
            for (Result result : results) {
                System.out.println((result.exit == 0 ? "PASS " : "FAIL ") + result.name + " exit=" + result.exit);
                System.out.print(result.output);
                output.append("<testcase classname=\"").append(ObserverLifecycleTest.class.getName())
                        .append("\" name=\"").append(result.name).append("\">");
                if (result.exit != 0) output.append("<failure message=\"exit ").append(result.exit)
                        .append("\">").append(xml(result.output)).append("</failure>");
                output.append("<system-out>").append(xml(result.output)).append("</system-out></testcase>\n");
            }
            output.append("</testsuite>\n");
            Files.createDirectories(junit.toAbsolutePath().getParent());
            Files.writeString(junit, output);
            return failures == 0 ? 0 : 1;
        } finally {
            pool.shutdownNow();
            require(pool.awaitTermination(30, TimeUnit.SECONDS), "test workers did not stop");
            try (var paths = Files.walk(work)) {
                for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) Files.delete(path);
            }
        }
    }

    /** list仅收集名字，suite跑独立JVM，单case原样暴露真实业务异常。 */
    public static void main(String[] args) throws Exception {
        if (args.length == 1 && args[0].equals("--list")) {
            tests().forEach(System.out::println); return;
        }
        if (args.length == 2 && args[0].equals("--suite")) {
            System.exit(suite(Path.of(args[1]))); return;
        }
        if (args.length != 1 || !tests().contains(args[0])) throw new IllegalArgumentException("unknown test case");
        try {
            ObserverLifecycleTest.class.getMethod(args[0]).invoke(null);
            require(PlatformViolations.attempts.isEmpty(), "observer crossed passive boundary: " + PlatformViolations.attempts);
        } catch (InvocationTargetException failure) {
            Throwable cause = failure.getCause();
            cause.printStackTrace(); System.exit(cause instanceof AssertionError ? 1 : 2);
        }
    }
}
