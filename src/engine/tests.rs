//! 引擎的单元测试。决策核心不碰任何安卓 API,所以这些用例在电脑上直接跑。

use super::*;

use super::*;

const PAD: &str = "C5:C5:30:98:47:5C";
const MOUSE: &str = "AA:BB:CC:DD:76:20";

/// 布防一台设备的引擎。多数用例都从这里起步。
fn armed_engine() -> Engine {
    let mut e = Engine::new();
    e.toggle(PAD, 0);
    e
}

#[test]
fn advertisement_from_unarmed_device_is_ignored() {
    // 没布防的设备(比如鼠标)广播时不该被碰 —— 布防只管用户点过的那几台。
    let mut e = armed_engine();
    assert_eq!(
        e.on_advertisement(MOUSE, false, 1_000),
        Action::Skip("未布防")
    );
}

#[test]
fn advertisement_while_system_already_connected_is_ignored()
{
    // 系统自己已经连上了就让路,不重复插手。这是用户明确要求的语义。
    let mut e = armed_engine();
    assert_eq!(
        e.on_advertisement(PAD, true, 1_000),
        Action::Skip("系统已连接")
    );
}

#[test]
fn advertisement_from_armed_disconnected_device_triggers_connect()
 {
    // 正常路径:布防中 + 系统没连 + 收到广播 → 主动连接。
    let mut e = armed_engine();
    assert_eq!(
        e.on_advertisement(PAD, false, 1_000),
        Action::Connect
    );
}

#[test]
fn restored_device_that_is_already_connected_is_not_scanned_for()
 {
    // 恢复名单之后安卓报「这台正连着」,那就一次扫描都不该开。
    let mut e = Engine::new();
    e.restore(vec![PAD.into()], 0);
    e.on_connection_change(PAD, true, 0);
    assert_eq!(e.scan_command(0), Scan::Stop);
}

#[test]
fn repeat_advertisement_within_throttle_window_is_ignored()
{
    // 手柄一秒能广播几十次,节流窗口内只能发起一次连接,
    // 否则会把正在建立的连接反复打断。
    let mut e = armed_engine();
    assert_eq!(
        e.on_advertisement(PAD, false, 1_000),
        Action::Connect
    );
    assert_eq!(
        e.on_advertisement(
            PAD,
            false,
            1_000 + RETRY_GAP_MS - 1
        ),
        Action::Skip("连接中")
    );
}

#[test]
fn advertisement_after_throttle_window_triggers_connect_again()
 {
    // 边界:上一次尝试失败后,过了节流窗口必须能再试,不能一次失败就放弃。
    let mut e = armed_engine();
    e.on_advertisement(PAD, false, 1_000);
    assert_eq!(
        e.on_advertisement(
            PAD,
            false,
            1_000 + RETRY_GAP_MS
        ),
        Action::Connect
    );
}

#[test]
fn advertisement_after_connection_drops_triggers_connect_again()
 {
    // 连上又断开(手柄关机、游戏中掉线)后,下一条广播应重新连接 ——
    // 这是这个工具存在的全部意义。断开即清节流,不必再等窗口。
    let mut e = armed_engine();
    e.on_advertisement(PAD, false, 1_000);
    e.on_connection_change(PAD, true, 1_500);
    e.on_connection_change(PAD, false, 1_800);
    assert_eq!(
        e.on_advertisement(PAD, false, 2_000),
        Action::Connect
    );
}

#[test]
fn disarmed_device_is_no_longer_acted_on() {
    // 撤防之后即使还在广播也不再动手,且状态回到"未布防"。
    let mut e = armed_engine();
    assert_eq!(e.toggle(PAD, 0), Scan::Stop);
    assert_eq!(
        e.on_advertisement(PAD, false, 1_000),
        Action::Skip("未布防")
    );
    assert!(!e.row(PAD).armed);
    assert_eq!(e.row(PAD).state, "未布防");
}

#[test]
fn toggle_drives_scan_lifecycle() {
    // 布防名单非空才扫描,空了必须停 —— 扫描是唯一的耗电项。
    let mut e = Engine::new();
    assert_eq!(
        e.toggle(PAD, 0),
        Scan::Start {
            macs: vec![PAD.into()],
            fast: true
        }
    );
    // 第二台加入后要带着两个地址重开扫描,否则新设备不在过滤器里。
    let two = e.toggle(MOUSE, 0);
    match two {
        Scan::Start { mut macs, .. } => {
            macs.sort();
            let mut want =
                vec![PAD.to_string(), MOUSE.to_string()];
            want.sort();
            assert_eq!(macs, want);
        }
        other => panic!("期望重开扫描,实际 {other:?}"),
    }
    e.toggle(PAD, 0);
    assert_eq!(e.toggle(MOUSE, 0), Scan::Stop);
}

#[test]
fn restore_arms_saved_devices_without_scanning_yet() {
    // 进程被杀后重启,存盘名单要能恢复成布防状态。但开不开扫得等安卓那侧把
    // 连接状态问回来 —— 名单里的设备很可能正连着,这时候开扫是在自找卡手。
    let mut e = Engine::new();
    e.restore(vec![PAD.into()], 0);
    assert!(e.row(PAD).armed);
    assert_eq!(e.armed_macs(), vec![PAD.to_string()]);
    assert_eq!(
        e.on_advertisement(PAD, false, 1_000),
        Action::Connect
    );
}

#[test]
fn scan_command_reflects_current_list() {
    // 权限刚批下来时要能按当前名单重开扫描 —— 在那之前的开扫必然失败,
    // 不重来的话界面显示"等待广播"而扫描根本没起来。
    let mut e = Engine::new();
    assert_eq!(e.scan_command(0), Scan::Stop);
    e.toggle(PAD, 0);
    assert_eq!(
        e.scan_command(0),
        Scan::Start {
            macs: vec![PAD.into()],
            fast: true
        }
    );
}

#[test]
fn external_errors_are_recorded_in_log() {
    // 安卓侧的失败(扫描失败、权限被拒)也必须能被用户看到,
    // 否则界面显示"等待广播"而实际上扫描根本没起来。
    let mut e = Engine::new();
    e.note_external(0, "扫描失败,错误码 2".into());
    assert!(
        e.log(0).iter().any(|l| l.contains("错误码 2"))
    );
}

#[test]
fn log_is_capped() {
    // 日志要显示在界面上,不能无限增长。
    let mut e = Engine::new();
    for i in 0..(LOG_CAP + 20) {
        e.note_external(i as u64, format!("第 {i} 条"));
    }
    assert_eq!(e.log(0).len(), LOG_CAP);
    // 保留的是最近的,最早那条应被挤掉。
    assert!(
        e.log(0).iter().all(|l| !l.ends_with("第 0 条"))
    );
}

#[test]
fn connected_armed_device_wants_low_latency() {
    // 系统建链时用的是保守的连接参数,输入延迟明显偏高,实测要等几秒才
    // 自行变好(推测是游戏或系统随后请求了高优先级)。布防的设备一连上
    // 就该由我们主动去压,而不是让用户等。
    let mut e = armed_engine();
    assert!(
        !e.take_low_latency_request(PAD),
        "还没连上时不该动参数"
    );
    e.on_connection_change(PAD, true, 1_000);
    assert!(e.take_low_latency_request(PAD));
}

#[test]
fn low_latency_is_requested_once_per_link() {
    // 参数是链路属性,连上后重复请求既无意义又会刷满日志;断开重连要能再发。
    let mut e = armed_engine();
    e.on_connection_change(PAD, true, 1_000);
    assert!(e.take_low_latency_request(PAD));
    assert!(
        !e.take_low_latency_request(PAD),
        "同一条链路不该重复请求"
    );

    e.on_connection_change(PAD, false, 2_000);
    e.on_connection_change(PAD, true, 3_000);
    assert!(
        e.take_low_latency_request(PAD),
        "重连后要重新请求"
    );
}

#[test]
fn unarmed_device_keeps_its_own_connection_parameters() {
    // 没布防的设备是别人的链路,不该被我们改连接参数 —— 压低间隔要付出
    // 双方的功耗,不是我们该替鼠标做的决定。
    let mut e = armed_engine();
    e.on_connection_change(MOUSE, true, 1_000);
    assert!(!e.take_low_latency_request(MOUSE));
}

#[test]
fn scanning_runs_at_full_duty_right_after_a_drop() {
    // 省电扫描每 5120 毫秒只听 512 毫秒,九成时间听不见 —— 刚掉线那阵子
    // 人多半还在用,这几秒的等待要靠满占空比压掉。
    let mut e = armed_engine();
    e.on_connection_change(PAD, true, 1_000);
    e.scan_if_changed(1_000);
    e.on_connection_change(PAD, false, 2_000);
    assert_eq!(
        e.scan_if_changed(2_000),
        Some(Scan::Start {
            macs: vec![PAD.into()],
            fast: true
        })
    );
}

#[test]
fn scanning_falls_back_to_low_power_once_the_device_stays_away()
 {
    // 过了这段还没回来,手柄多半已经关机,再全速扫只是把电白白烧掉。
    let mut e = armed_engine();
    e.on_connection_change(PAD, true, 1_000);
    e.scan_if_changed(1_000);
    e.on_connection_change(PAD, false, 2_000);
    e.scan_if_changed(2_000);
    assert_eq!(
        e.scan_if_changed(2_000 + FAST_SCAN_MS),
        Some(Scan::Start {
            macs: vec![PAD.into()],
            fast: false
        })
    );
}

#[test]
fn keepalive_is_paced_and_only_sent_while_connected() {
    // 保活是发给「连着的手柄」的:没连上时发无处可发。节奏也要限住,
    // 每分钟一次足够试探固件,再密只是白耗电。
    let mut e = armed_engine();
    assert!(!e.keepalive_due(PAD, 0), "没连上时不该发");

    e.on_connection_change(PAD, true, 1_000);
    assert!(e.keepalive_due(PAD, 1_000));
    assert!(
        !e.keepalive_due(PAD, 1_000 + KEEPALIVE_GAP_MS - 1),
        "未到间隔不该重发"
    );
    assert!(e.keepalive_due(PAD, 1_000 + KEEPALIVE_GAP_MS));
}

#[test]
fn keepalive_is_not_sent_to_unarmed_devices() {
    // 没布防的设备不归我们管,别去打扰它的链路。
    let mut e = armed_engine();
    e.on_connection_change(MOUSE, true, 1_000);
    assert!(!e.keepalive_due(MOUSE, 1_000));
}

#[test]
fn hid_view_lag_does_not_restart_scanning_on_a_fresh_link()
{
    // 两个信号快慢不同:ACL 广播链路一起来就到,而平台的 HID 视图(输入设备
    // 列表)要等 HID profile 完全起来才转真,中间有几秒空窗。让慢的覆盖快的,
    // 扫描就会在链路建立中途被重开 —— 那是射频最紧张的时刻,既拖慢建链,
    // 也是 0x08 监督超时的已知诱因。
    let mut e = armed_engine();
    e.on_connection_change(PAD, true, 1_000);
    assert_eq!(e.scan_if_changed(0), Some(Scan::Stop));

    e.on_tick(PAD, false, 11_000);
    assert_eq!(
        e.scan_if_changed(0),
        None,
        "空窗期内不该重开扫描"
    );
}

#[test]
fn platform_view_wins_after_the_settle_window() {
    // 但空窗不能无限延长:ACL 断开广播可能丢失,过了稳定窗口就该由平台的
    // 当场判断说了算,否则又回到「扫描永不恢复」。
    let mut e = armed_engine();
    e.on_connection_change(PAD, true, 1_000);
    let late = 1_000 + SETTLE_MS + 1;
    e.scan_if_changed(late);

    e.on_tick(PAD, false, late);
    // 那一次盲试刚发出去,扫描先让位给发起器;重试窗口一过就该恢复。
    assert_eq!(
        e.scan_if_changed(late + RETRY_GAP_MS),
        Some(Scan::Start {
            macs: vec![PAD.into()],
            fast: true
        })
    );
}

#[test]
fn tick_clears_a_stale_connected_flag_and_scanning_resumes()
{
    // 断开广播可能丢失:进程被冻结、被杀后重启、广播风暴时都会漏。
    // on_tick 每次都从平台拿到权威的连接状态,必须用它把残留的「已连接」
    // 清掉 —— 否则引擎永远认为设备连着,扫描再也不会恢复,只剩已退避到
    // 五分钟一次的盲试兜底,手柄回来后要等很久才被接上。
    let mut e = armed_engine();
    e.on_connection_change(PAD, true, 1_000);
    assert_eq!(e.scan_if_changed(1_000), Some(Scan::Stop));

    e.on_tick(PAD, false, 40_000);
    assert_eq!(
        e.scan_if_changed(40_000 + RETRY_GAP_MS),
        Some(Scan::Start {
            macs: vec![PAD.into()],
            fast: true
        })
    );
}

#[test]
fn advertisement_clears_a_stale_connected_flag() {
    // 广播路径同理:收到广播且平台说没连,那就是没连。
    let mut e = armed_engine();
    e.on_connection_change(PAD, true, 1_000);
    e.scan_if_changed(0);
    e.on_advertisement(PAD, false, 40_000);
    assert_eq!(e.row(PAD).state, "正在连接");
}

#[test]
fn scan_stops_once_the_armed_device_is_connected() {
    // 连上之后还扫它,是拿同一个射频跟自己抢:扫描窗口会挤掉手柄的输入包,
    // 轻则极短操作卡手,重则连续丢包触发监督超时断线。实测账单显示应用在
    // 手柄连着时累计扫了两个多小时。
    let mut e = armed_engine();
    e.on_connection_change(PAD, true, 1_000);
    assert_eq!(e.scan_if_changed(0), Some(Scan::Stop));
}

#[test]
fn scan_resumes_when_the_connection_drops() {
    // 断开后必须立刻恢复盯广播,否则手柄回来时没人看见。
    let mut e = armed_engine();
    e.on_connection_change(PAD, true, 1_000);
    e.scan_if_changed(0);
    e.on_connection_change(PAD, false, 2_000);
    assert_eq!(
        e.scan_if_changed(0),
        Some(Scan::Start {
            macs: vec![PAD.into()],
            fast: true
        })
    );
}

#[test]
fn scan_is_not_reissued_while_the_target_set_is_unchanged()
{
    // 每个事件都重下扫描指令的话,安卓侧会不停地停扫再开扫,
    // 那本身就是一次射频扰动。只在目标集合变化时下发。
    let mut e = armed_engine();
    e.scan_if_changed(0);
    assert_eq!(e.scan_if_changed(0), None);
}

#[test]
fn scan_covers_only_the_devices_still_waiting() {
    // 两台布防、一台已连上时,扫描过滤器里只该留还没连上的那台。
    let mut e = armed_engine();
    e.toggle(MOUSE, 0);
    e.on_connection_change(PAD, true, 1_000);
    assert_eq!(
        e.scan_if_changed(0),
        Some(Scan::Start {
            macs: vec![MOUSE.into()],
            fast: true
        })
    );
}

#[test]
fn blind_retry_backs_off_while_device_stays_absent() {
    // 手柄关机时盲试注定失败。固定十秒一次的话,一夜就是近三千次无效尝试,
    // 每次都让控制器去找一个不存在的设备。间隔要逐次拉长。
    let mut e = armed_engine();
    assert_eq!(e.on_tick(PAD, false, 0), Action::Connect);
    // 第一次之后间隔翻倍,原间隔到点时还不该动。
    assert_eq!(
        e.on_tick(PAD, false, BLIND_GAP_MS),
        Action::Skip("退避中")
    );
    assert_eq!(
        e.on_tick(PAD, false, BLIND_GAP_MS * 2),
        Action::Connect
    );
}

#[test]
fn blind_retry_backoff_has_a_ceiling() {
    // 退避不能无限拉长,否则手柄一直开着却收不到广播时,应用等于放弃了。
    let mut e = armed_engine();
    let mut at = 0;
    for _ in 0..20 {
        e.on_tick(PAD, false, at);
        at += BLIND_GAP_MAX_MS;
    }
    // 到了上限之后,每隔上限时间必定还会再试一次。
    assert_eq!(e.on_tick(PAD, false, at), Action::Connect);
}

#[test]
fn advertisement_resets_blind_retry_backoff() {
    // 收到广播意味着设备真的在,此时必须立刻恢复灵敏 —— 退避是为了应付
    // 设备不在,不能让它拖慢设备回来那一刻的反应。
    let mut e = armed_engine();
    e.on_tick(PAD, false, 0);
    e.on_tick(PAD, false, BLIND_GAP_MS * 2);
    // 广播到达,自身触发一次连接并把退避清零。
    assert_eq!(
        e.on_advertisement(PAD, false, BLIND_GAP_MS * 10),
        Action::Connect
    );
    assert_eq!(
        e.on_tick(
            PAD,
            false,
            BLIND_GAP_MS * 10 + BLIND_GAP_MS
        ),
        Action::Connect
    );
}

#[test]
fn blind_retry_stands_down_while_system_connected() {
    // 系统已经连上就别插手,语义与广播路径一致。
    let mut e = armed_engine();
    assert_eq!(
        e.on_tick(PAD, true, 0),
        Action::Skip("系统已连接")
    );
}

#[test]
fn log_lines_carry_relative_age() {
    // 光有先后顺序不够:断开是十秒前还是一小时前,决定了要不要担心。
    let mut e = Engine::new();
    e.note_external(1_000, "扫描失败".into());
    assert_eq!(e.log(31_000), ["30秒前  扫描失败"]);
}

#[test]
fn repeated_line_is_aged_from_its_latest_occurrence() {
    // 合并后的那条要显示最后一次发生在何时 —— 显示第一次的时间会让人
    // 以为事情早就停了,而它可能还在每十秒发生一遍。
    let mut e = Engine::new();
    e.note_external(1_000, "发起连接".into());
    e.note_external(60_000, "发起连接".into());
    assert_eq!(e.log(61_000), ["1秒前  发起连接 ×2"]);
}

#[test]
fn consecutive_duplicate_lines_collapse_into_one_entry() {
    // 手柄不在时每 10 秒重试一次,同一行会把日志刷满,真正有用的
    // 信息被挤出缓冲区。连续重复的只占一条,并标出次数。
    let mut e = Engine::new();
    for _ in 0..5 {
        e.note_external(1_000, "发起连接".into());
    }
    assert_eq!(e.log(1_000).len(), 1);
    assert_eq!(e.log(1_000)[0], "刚刚  发起连接 ×5");
}

#[test]
fn a_different_line_ends_the_collapse() {
    // 合并只针对连续重复:中间夹了别的行,再出现的同一行是新事件,
    // 必须另起一条,否则看不出事情发生过两轮。
    let mut e = Engine::new();
    e.note_external(0, "发起连接".into());
    e.note_external(0, "已连接".into());
    e.note_external(0, "发起连接".into());
    assert_eq!(
        e.log(0),
        [
            "刚刚  发起连接",
            "刚刚  已连接",
            "刚刚  发起连接"
        ]
    );
}

#[test]
fn decisions_are_recorded_in_log() {
    // 这台平板 logcat 不可用,每次决策(连了/跳过及原因)都要进日志,
    // 否则再出问题依然无法定位。
    let mut e = armed_engine();
    e.on_advertisement(PAD, false, 1_000);
    e.on_advertisement(PAD, true, 20_000);
    let log = e.log(20_000);
    assert!(
        log.iter().any(|l| l.contains("连接")),
        "日志里应有发起连接的记录: {log:?}"
    );
    assert!(
        log.iter().any(|l| l.contains("系统已连接")),
        "日志里应有让路原因: {log:?}"
    );
}

// ---- 需要人工干预时的提醒 ----

#[test]
fn attention_is_silent_when_nothing_is_armed() {
    // 用户没布防任何设备时,这个工具就该完全闭嘴 —— 蓝牙关着也不关它的事。
    let e = Engine::new();
    assert_eq!(e.attention(false, false, 10_000_000), None);
}

#[test]
fn attention_is_silent_while_device_is_connected() {
    // 连着的时候没有任何要人做的事。
    let mut e = armed_engine();
    e.on_connection_change(PAD, true, 1_000);
    assert_eq!(e.attention(true, true, 10_000_000), None);
}

#[test]
fn attention_asks_to_turn_bluetooth_on() {
    // 蓝牙关了谁也救不回来,只能让用户去开。
    let e = armed_engine();
    let msg = e
        .attention(false, true, ATTENTION_MS)
        .expect("该提醒开蓝牙");
    assert!(msg.contains("蓝牙"), "{msg}");
}

#[test]
fn attention_asks_to_start_shizuku() {
    // 没有特权身份就退回到普通连接,回连成功率大跌,值得让用户去启动 Shizuku。
    let e = armed_engine();
    let msg = e
        .attention(true, false, ATTENTION_MS)
        .expect("该提醒 Shizuku");
    assert!(msg.contains("Shizuku"), "{msg}");
}

#[test]
fn attention_prefers_bluetooth_over_shizuku() {
    // 两个都不满足时先说蓝牙:开了蓝牙才轮得到特权身份起作用。
    let e = armed_engine();
    let msg =
        e.attention(false, false, ATTENTION_MS).unwrap();
    assert!(msg.contains("蓝牙"), "{msg}");
}

#[test]
fn attention_stays_quiet_during_normal_wait() {
    // 刚断开的几分钟里正常重试就好,这时候弹通知纯属打扰。
    let mut e = armed_engine();
    e.on_connection_change(PAD, true, 1_000);
    e.on_connection_change(PAD, false, 2_000);
    assert_eq!(
        e.attention(true, true, 2_000 + ATTENTION_MS - 1),
        None
    );
}

#[test]
fn attention_asks_to_wake_device_after_long_wait() {
    // 等了这么久还没回来,基本可以断定手柄已经关机 —— 只有人能按那个键。
    let mut e = armed_engine();
    e.on_connection_change(PAD, true, 1_000);
    e.on_connection_change(PAD, false, 2_000);
    let msg = e
        .attention(true, true, 2_000 + ATTENTION_MS)
        .expect("该提醒去开手柄");
    assert!(msg.contains(PAD), "提醒里要指明是哪台: {msg}");
}

#[test]
fn attention_clears_once_device_comes_back() {
    // 回来了就该撤掉通知,而不是留一条过期的提醒在通知栏。
    let mut e = armed_engine();
    e.on_connection_change(PAD, true, 1_000);
    e.on_connection_change(PAD, false, 2_000);
    let late = 2_000 + ATTENTION_MS;
    assert!(e.attention(true, true, late).is_some());
    e.on_connection_change(PAD, true, late);
    assert_eq!(e.attention(true, true, late), None);
}

#[test]
fn attention_holds_fire_while_shizuku_is_still_binding() {
    // 应用刚启动时 Shizuku 还在绑定,这一瞬间不该弹「未就绪」再自己撤掉 ——
    // 一闪而过的假警报比不提醒更让人不信任它。
    let e = armed_engine();
    assert_eq!(e.attention(true, false, 1_000), None);
}

#[test]
fn attention_ignores_unarmed_device() {
    // 没布防的设备断着是它自己的事,不该因为它去打扰用户。
    let mut e = Engine::new();
    e.toggle(MOUSE, 0);
    e.toggle(MOUSE, 1_000); // 撤防
    assert_eq!(e.attention(true, true, 10_000_000), None);
}

// ---- 审计出来的几个真问题 ----

#[test]
fn advertisement_does_not_reconnect_while_link_is_still_settling()
 {
    // 链路刚建好的那几秒,平台的 HID 视图还没跟上,会报「没连」。扫描那一侧
    // 已经按 ACL 事实兜住了,发起连接这一侧却还在看那个慢信号 —— 于是会往
    // 一条正在建立的链路上再发一次特权连接。
    let mut e = armed_engine();
    e.on_connection_change(PAD, true, 1_000);
    let late = 1_000 + RETRY_GAP_MS + 1;
    assert!(
        late < 1_000 + SETTLE_MS,
        "这一刻仍在稳定窗口内"
    );
    assert_eq!(
        e.on_advertisement(PAD, false, late),
        Action::Skip("系统已连接")
    );
}

#[test]
fn tick_does_not_reconnect_while_link_is_still_settling() {
    // 盲试那一路同理。
    let mut e = armed_engine();
    e.on_connection_change(PAD, true, 1_000);
    let late = 1_000 + BLIND_GAP_MS + 1;
    assert!(
        late < 1_000 + SETTLE_MS,
        "这一刻仍在稳定窗口内"
    );
    assert_eq!(
        e.on_tick(PAD, false, late),
        Action::Skip("系统已连接")
    );
}

#[test]
fn scan_is_reissued_after_the_platform_refuses_to_start_it()
{
    // 开扫可能失败:蓝牙关着拿不到扫描器、权限被撤、或者平台直接回一个错误码。
    // 引擎记的是「我下发过什么」,不是「平台真的在扫」—— 不把失败告诉它,
    // 目标状态没变就再也不会重下,扫描永远回不来。
    let mut e = armed_engine();
    assert!(matches!(
        e.scan_command(1_000),
        Scan::Start { .. }
    ));
    assert_eq!(
        e.scan_if_changed(2_000),
        None,
        "没变就不重下"
    );
    e.on_scan_failed(3_000);
    assert!(
        matches!(
            e.scan_if_changed(4_000),
            Some(Scan::Start { .. })
        ),
        "知道失败之后应当重下"
    );
}

#[test]
fn low_latency_permit_comes_back_when_the_request_fails() {
    // 许可是「每条链路只发一次」,可它在安卓那侧真正发出去之前就被收走了。
    // 那一侧失败(设备不在配对列表、拿不到 GATT 客户端)许可就白烧,这条
    // 链路再没有第二次机会。
    let mut e = armed_engine();
    e.on_connection_change(PAD, true, 1_000);
    assert!(e.take_low_latency_request(PAD));
    e.return_low_latency_permit(PAD);
    assert!(
        e.take_low_latency_request(PAD),
        "失败后应当还能再领一次"
    );
}

#[test]
fn scanning_pauses_while_a_connect_attempt_is_in_flight() {
    // 连接发出去之后的那几秒,收到的广播引擎一律按「连接中」跳过 —— 建链本身要
    // 几秒,不能被打断。既然这段时间的广播不会被采纳,扫描就是白扫,而它偏偏是
    // 满占空比的,一直占着接收机;系统的连接发起器要用同一个射频去等对方广播。
    let mut e = armed_engine();
    e.on_connection_change(PAD, true, 1_000);
    e.on_connection_change(PAD, false, 2_000);
    assert!(matches!(
        e.scan_command(2_000),
        Scan::Start { .. }
    ));
    assert_eq!(
        e.on_advertisement(PAD, false, 3_000),
        Action::Connect
    );
    assert_eq!(
        e.scan_command(3_000),
        Scan::Stop,
        "发起连接后应当停扫"
    );
}

#[test]
fn scanning_resumes_when_the_attempt_did_not_land() {
    // 这次尝试没能把设备接回来,退避窗口一过就得重新去听广播,否则一次失败
    // 就此失明。
    let mut e = armed_engine();
    e.on_connection_change(PAD, true, 1_000);
    e.on_connection_change(PAD, false, 2_000);
    e.on_advertisement(PAD, false, 3_000);
    assert_eq!(e.scan_command(3_000), Scan::Stop);
    assert!(
        matches!(
            e.scan_command(3_000 + RETRY_GAP_MS),
            Scan::Start { .. }
        ),
        "退避窗口过了要复扫"
    );
}

#[test]
fn one_device_being_connected_does_not_blind_the_others() {
    // 停扫只针对正在尝试的那一台,别的布防设备该扫还得扫。
    let mut e = armed_engine();
    e.toggle(MOUSE, 0);
    e.on_advertisement(PAD, false, 3_000);
    match e.scan_command(3_000) {
        Scan::Start { macs, .. } => {
            assert_eq!(macs, vec![MOUSE.to_string()])
        }
        other => panic!("另一台还该扫: {other:?}"),
    }
}

#[test]
fn keepalive_goes_out_when_the_switch_is_on() {
    // 总开关与内层规则分开:开关一关,针对 take_keepalive 的用例就会全部变成
    // 空转,节奏和适用范围哪天被改坏都看不出来。这个用例盯着两者的接合处。
    let mut e = armed_engine();
    e.on_connection_change(PAD, true, 1_000);
    assert_eq!(
        e.take_keepalive(PAD, 1_000),
        KEEPALIVE_ENABLED,
        "对外发不发,只取决于总开关"
    );
}

#[test]
fn unpaired_device_is_not_scanned_for() {
    // 配对记录没了,connect() 无从谈起 —— 再扫也只是白占射频,而旁边还有
    // 别的设备正连着。
    let mut e = armed_engine();
    e.on_unpaired(PAD, 1_000);
    assert_eq!(e.scan_command(1_000), Scan::Stop);
}

#[test]
fn unpaired_device_asks_the_user_to_pair_again() {
    // 这件事只有人能解决,而且它是确定的事实,不必像「久等不回」那样先观望 ——
    // 观望六分钟只是让用户多困惑六分钟。
    let e = {
        let mut e = armed_engine();
        e.on_unpaired(PAD, 1_000);
        e
    };
    let msg = e
        .attention(true, true, 1_000)
        .expect("该提醒重新配对");
    assert!(msg.contains("配对"), "{msg}");
    assert!(msg.contains(PAD), "要指明是哪台: {msg}");
}

#[test]
fn pairing_again_puts_the_device_back_to_work() {
    // 重新配好之后要自己恢复,不该等用户再去点一次布防。
    let mut e = armed_engine();
    e.on_unpaired(PAD, 1_000);
    e.on_paired(PAD, 2_000);
    assert_eq!(e.attention(true, true, 2_000), None);
    assert!(matches!(
        e.scan_command(2_000),
        Scan::Start { .. }
    ));
}
