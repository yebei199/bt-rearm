//! 布防决策核心。
//!
//! 这里不碰任何安卓 API:输入是"发生了什么"(广播到了、连接状态变了、用户点了
//! 某一行),输出是"该做什么"(连这台、启停扫描)。安卓那一侧只负责把事件送进来、
//! 把动作执行出去,不做任何判断 —— 于是这套逻辑可以在电脑上直接跑测试。

use std::collections::{HashMap, HashSet};

/// 同一台设备两次主动连接之间的最小间隔。连接建立本身要几秒,别把它打断。
pub const RETRY_GAP_MS: u64 = 8_000;

/// 盲试(没收到广播、纯靠定时器驱动)的起始间隔。
pub const BLIND_GAP_MS: u64 = 10_000;

/// 盲试间隔的上限。
///
/// 手柄关机时盲试注定失败,固定间隔一夜就是近三千次无效尝试,每次都让控制器
/// 去找一个不存在的设备。间隔逐次翻倍,但不能无限拉长 —— 手柄一直开着却收不到
/// 广播时(后台扫描被系统压制),盲试是唯一的活路,放弃它等于放弃布防。
pub const BLIND_GAP_MAX_MS: u64 = 300_000;

/// 链路刚建立后的稳定窗口,窗口内以 ACL 事实为准。
///
/// 平台给的两个信号快慢不同:ACL 广播链路一起来就到,而输入设备列表要等 HID
/// profile 完全起来才转真,中间有几秒空窗。让慢的覆盖快的,扫描会在链路建立
/// 中途被重开 —— 那是射频最紧张的时刻。取值远大于建链耗时的几秒,又远小于
/// 盲试退避的上限,窗口外仍由平台的当场判断兜底,免得回到「扫描永不恢复」。
pub const SETTLE_MS: u64 = 30_000;

/// 刚失去连接后维持满占空比扫描的时长。
///
/// 安卓的省电扫描每 5120 毫秒只听 512 毫秒,九成时间耳朵是闭着的 —— 手柄广播
/// 得再勤,平均也要两秒多才被撞上,最坏五秒往上。刚掉线那阵子人多半还在用,
/// 值得用满占空比把这段等待压掉;过了这段仍没回来,说明手柄多半已经关机,
/// 再全速扫下去只是把电白白烧掉。
pub const FAST_SCAN_MS: u64 = 120_000;

/// 两次保活之间的间隔。
///
/// 手柄闲置一段时间会自行休眠,那是它固件里的计时器,平板改不了。能试的只有
/// 一件事:往手柄发点数据,看它的固件认不认这算「有活动」。认不认查不到资料,
/// 只能实测,所以这是个实验而非确定的修复。一分钟一次足够试探,再密只是白耗电。
pub const KEEPALIVE_GAP_MS: u64 = 60_000;

/// 断开多久之后,认定「只有人能解决」并弹通知。
///
/// 手柄掉线后重试是自动的,几分钟内都算正常波动,这段时间弹通知纯属打扰。
/// 超过盲试退避的上限还没回来,基本只剩一种解释:手柄已经关机 —— 而按下电源
/// 键这件事,主机永远做不到。取值略大于退避上限,让自动那条路先走完再喊人。
pub const ATTENTION_MS: u64 = 360_000;

/// 收到一条广播后,决定要不要动手。
#[derive(Debug, PartialEq, Eq)]
pub enum Action {
    /// 主动连接这台设备。
    Connect,
    /// 不动手,并附上原因 —— 原因会进事件日志给用户看。
    Skip(&'static str),
}

/// 扫描该怎么走。
#[derive(Debug, Clone, PartialEq, Eq)]
pub enum Scan {
    /// 按这批地址开扫(名单变化时也用它,等价于换过滤器重开)。
    ///
    /// `fast` 为真时用满占空比:刚掉线那阵子要抢时间,省电模式九成时间听不见。
    Start { macs: Vec<String>, fast: bool },
    /// 名单空了,停扫 —— 扫描是这里唯一的耗电项。
    Stop,
}

/// 一台设备在界面上的样子。
#[derive(Debug, PartialEq, Eq)]
pub struct Row {
    pub mac: String,
    pub armed: bool,
    pub state: String,
}

/// 一条日志。时间是输入而非现读 —— 引擎不碰时钟,测试才能可重复。
struct Entry {
    /// 最后一次发生的时刻。合并后的条目按最后一次计龄。
    at_ms: u64,
    /// 正文,不含次数后缀。
    line: String,
    /// 连续出现的次数。
    repeat: usize,
}

#[derive(Default)]
pub struct Engine {
    armed: HashSet<String>,
    last_try: HashMap<String, u64>,
    connected: HashSet<String>,
    /// 每台设备当前的盲试间隔。收到广播或连上即回到起始值。
    blind_gap: HashMap<String, u64>,
    /// 上一次下发给安卓侧的扫描指令,用来避免重复下发。
    last_scan: Option<Scan>,
    /// 各设备 ACL 链路建立的时刻,用于判断是否还在稳定窗口内。
    acl_since: HashMap<String, u64>,
    /// 各设备上次发保活的时刻。
    last_keepalive: HashMap<String, u64>,
    /// 各设备开始「等着它回来」的时刻:布防那一刻,或失去连接那一刻。
    waiting_since: HashMap<String, u64>,
    /// 本次链路已经发过低延迟请求的设备。断开即清。
    low_latency_sent: HashSet<String>,
    log: Vec<Entry>,
}

/// 事件日志保留的条数。只够看清最近发生了什么,不做长期留存。
const LOG_CAP: usize = 60;

impl Engine {
    pub fn new() -> Self {
        Self::default()
    }

    /// 用户点了某一行:布防 ↔ 撤防。
    pub fn toggle(
        &mut self,
        mac: &str,
        now_ms: u64,
    ) -> Scan {
        if self.armed.remove(mac) {
            self.last_try.remove(mac);
            self.note(now_ms, format!("{mac} 撤防"));
        } else {
            self.armed.insert(mac.to_string());
            self.waiting_since
                .insert(mac.to_string(), now_ms);
            self.note(now_ms, format!("{mac} 布防"));
        }
        self.commit_scan_at(now_ms)
    }

    /// 进程重启后,从存盘名单恢复布防。
    pub fn restore(
        &mut self,
        macs: Vec<String>,
        now_ms: u64,
    ) -> Scan {
        for mac in macs {
            self.waiting_since.insert(mac.clone(), now_ms);
            self.armed.insert(mac);
        }
        self.note(
            now_ms,
            format!("恢复布防 {} 台", self.armed.len()),
        );
        self.commit_scan_at(now_ms)
    }

    /// 扫到一条广播。`system_connected` 是安卓侧当场查到的连接状态。
    pub fn on_advertisement(
        &mut self,
        mac: &str,
        system_connected: bool,
        now_ms: u64,
    ) -> Action {
        if !self.armed.contains(mac) {
            return Action::Skip("未布防");
        }
        self.sync_connected(mac, system_connected, now_ms);
        if system_connected {
            // 系统自己连上了就让路 —— 这正是布防该袖手的时候。
            self.note(
                now_ms,
                format!("{mac} 系统已连接,让路"),
            );
            return Action::Skip("系统已连接");
        }
        // 收到广播意味着设备真的在:退避是为了应付设备不在,不能让它拖慢
        // 设备回来那一刻的反应。
        self.blind_gap.remove(mac);
        // 上一次尝试还在窗口内:连接建立本身要几秒,别把它打断。
        if let Some(last) = self.last_try.get(mac)
            && now_ms.saturating_sub(*last) < RETRY_GAP_MS
        {
            return Action::Skip("连接中");
        }
        self.last_try.insert(mac.to_string(), now_ms);
        self.note(now_ms, format!("{mac} 发起连接"));
        Action::Connect
    }

    /// 定时器驱动的盲试:没有广播作依据,纯粹碰运气试一次。
    ///
    /// 存在的理由是后台扫描可能被系统压制,那时广播一条都收不到,盲试是唯一
    /// 还能动的路径。代价是设备不在时它必然失败,所以间隔逐次翻倍 —— 一直
    /// 十秒一次的话,手柄关一夜就是近三千次无效尝试。
    pub fn on_tick(
        &mut self,
        mac: &str,
        system_connected: bool,
        now_ms: u64,
    ) -> Action {
        if !self.armed.contains(mac) {
            return Action::Skip("未布防");
        }
        self.sync_connected(mac, system_connected, now_ms);
        if system_connected {
            self.blind_gap.remove(mac);
            self.note(
                now_ms,
                format!("{mac} 系统已连接,让路"),
            );
            return Action::Skip("系统已连接");
        }
        let gap = self
            .blind_gap
            .get(mac)
            .copied()
            .unwrap_or(BLIND_GAP_MS);
        if let Some(last) = self.last_try.get(mac)
            && now_ms.saturating_sub(*last) < gap
        {
            return Action::Skip("退避中");
        }
        self.last_try.insert(mac.to_string(), now_ms);
        self.blind_gap.insert(
            mac.to_string(),
            (gap * 2).min(BLIND_GAP_MAX_MS),
        );
        self.note(now_ms, format!("{mac} 盲试连接"));
        Action::Connect
    }

    /// 领取一次「把连接参数压到低延迟档」的许可。
    ///
    /// 只对布防中且已连上的设备发:压低连接间隔要双方付出功耗,那是我们为手柄
    /// 手感做的取舍,不该替用户没布防的设备(比如鼠标)决定。
    ///
    /// 每条链路只发一次 —— 参数是链路属性,连上后重复请求既无意义又会刷满日志;
    /// 断开时清除,下次连上重新发。做成「领取」而非「查询」,是为了让定时巡检也
    /// 能补发:应用启动时设备可能已经连着,那一刻不会再有连接广播。
    pub fn take_low_latency_request(
        &mut self,
        mac: &str,
    ) -> bool {
        if !(self.armed.contains(mac)
            && self.connected.contains(mac))
        {
            return false;
        }
        self.low_latency_sent.insert(mac.to_string())
    }

    /// 领取一次保活许可。到点且设备连着才给。
    ///
    /// 保活是往手柄发一点数据,试探它的固件认不认这算「有活动」,从而推迟自行
    /// 休眠。认不认取决于固件,查不到资料,只能实测 —— 所以这是实验性的。
    /// 没连上时无处可发;没布防的设备不归我们管,别去打扰它的链路。
    pub fn take_keepalive(
        &mut self,
        mac: &str,
        now_ms: u64,
    ) -> bool {
        if !(self.armed.contains(mac)
            && self.connected.contains(mac))
        {
            return false;
        }
        if let Some(last) = self.last_keepalive.get(mac)
            && now_ms.saturating_sub(*last)
                < KEEPALIVE_GAP_MS
        {
            return false;
        }
        self.last_keepalive.insert(mac.to_string(), now_ms);
        true
    }

    /// 用平台报来的权威状态校正本地记录。
    ///
    /// 只靠 ACL 广播维护连接状态是不够的:进程被冻结、被杀后重启、广播风暴时
    /// 都可能漏掉断开那一条,「已连接」便会永久残留 —— 而扫描按它决定开停,
    /// 于是扫描再也不会恢复,只剩已退避到五分钟一次的盲试兜底。广播路径与盲试
    /// 路径每次都带着平台的当场判断,拿它兜底即可。
    fn sync_connected(
        &mut self,
        mac: &str,
        system_connected: bool,
        now_ms: u64,
    ) {
        if system_connected {
            self.connected.insert(mac.to_string());
            return;
        }
        // 刚建好的链路上,平台的 HID 视图还没跟上,此时它说「没连」不作数。
        if let Some(since) = self.acl_since.get(mac)
            && now_ms.saturating_sub(*since) < SETTLE_MS
        {
            return;
        }
        if self.connected.remove(mac) {
            // 从「连着」跌到「没连」的那一刻,才是开始等它回来的起点。
            self.waiting_since
                .insert(mac.to_string(), now_ms);
        }
        // 链路没了,已发的低延迟请求也随之失效,下次连上要重新发。
        self.low_latency_sent.remove(mac);
    }

    /// 连接状态变了(我们连上的,或链路断了)。
    pub fn on_connection_change(
        &mut self,
        mac: &str,
        connected: bool,
        now_ms: u64,
    ) {
        if connected {
            // ACL 事实优先:记下建链时刻,稳定窗口内不让慢信号推翻它。
            self.acl_since.insert(mac.to_string(), now_ms);
        } else {
            self.acl_since.remove(mac);
        }
        self.sync_connected(mac, connected, now_ms);
        if connected {
            self.blind_gap.remove(mac);
            self.note(now_ms, format!("{mac} 已连接"));
        } else {
            // 断开即清节流:下一条广播要能立刻接管,不必再等窗口 ——
            // 这正是这个工具存在的意义。
            self.last_try.remove(mac);
            // 刚断开时设备多半还在(掉线而非关机),值得积极重试一次。
            self.blind_gap.remove(mac);
            self.note(
                now_ms,
                format!("{mac} 断开,等待广播"),
            );
        }
    }

    /// 现在有没有非人不可的事,有的话给出一句能直接当通知正文的话。
    ///
    /// 判断留在这里而不是安卓那侧,是因为「什么算需要人管」是策略。安卓只负责
    /// 把两个它才知道的事实(蓝牙开没开、特权身份就绪没有)递进来,再把返回的
    /// 话显示出去。返回 None 表示一切正常,通知该撤掉。
    ///
    /// 无论哪种原因,都要先等到有设备久等不回才开口。少了这道闸,应用每次启动
    /// 都会在 Shizuku 绑定完成前的那一瞬间弹一次「未就绪」,弹完自己撤掉 ——
    /// 通知栏一闪而过的假警报比不提醒更让人不信任它。
    pub fn attention(
        &self,
        bt_on: bool,
        privileged_ready: bool,
        now_ms: u64,
    ) -> Option<String> {
        let mut stale: Vec<&str> = self
            .armed
            .iter()
            .filter(|m| !self.connected.contains(*m))
            .filter(|m| {
                self.waiting_since.get(*m).is_some_and(
                    |since| {
                        now_ms.saturating_sub(*since)
                            >= ATTENTION_MS
                    },
                )
            })
            .map(|m| m.as_str())
            .collect();
        if stale.is_empty() {
            return None;
        }
        // 先说蓝牙:开了蓝牙才轮得到特权身份起作用,而特权身份到位了,
        // 剩下唯一说得通的解释就是设备自己不在。
        if !bt_on {
            return Some(
                "蓝牙已关闭,布防中的设备无法回连".into(),
            );
        }
        if !privileged_ready {
            return Some(
                "Shizuku 未就绪,回连会退回到成功率很低的普通方式"
                    .into(),
            );
        }
        stale.sort();
        Some(format!(
            "{} 已断开较久,自动重连没能接回,可能需要开机",
            stale.join("、")
        ))
    }

    /// 当前布防名单,给存盘用。
    pub fn armed_macs(&self) -> Vec<String> {
        let mut macs: Vec<String> =
            self.armed.iter().cloned().collect();
        // 存盘与测试都希望顺序稳定,HashSet 的迭代顺序不是。
        macs.sort();
        macs
    }

    /// 界面要显示的状态。
    pub fn row(&self, mac: &str) -> Row {
        let armed = self.armed.contains(mac);
        let connected = self.connected.contains(mac);
        let state = match (armed, connected) {
            (false, true) => "系统已连接",
            (false, false) => "未布防",
            (true, true) => "已连接",
            (true, false) => {
                if self.last_try.contains_key(mac) {
                    "正在连接"
                } else {
                    "等待广播"
                }
            }
        };
        Row {
            mac: mac.to_string(),
            armed,
            state: state.to_string(),
        }
    }

    /// 决策事件日志,每条带上距今多久。这台平板的 logcat 不可用,日志只能
    /// 显示在界面上;光有先后顺序不够 —— 断开是十秒前还是一小时前,决定了
    /// 要不要担心。
    pub fn log(&self, now_ms: u64) -> Vec<String> {
        self.log
            .iter()
            .map(|e| {
                let age =
                    ago(now_ms.saturating_sub(e.at_ms));
                if e.repeat > 1 {
                    format!(
                        "{age}  {} ×{}",
                        e.line, e.repeat
                    )
                } else {
                    format!("{age}  {}", e.line)
                }
            })
            .collect()
    }

    /// 安卓侧报上来的错误(扫描失败、权限被拒等)。同样进日志,理由同上。
    pub fn note_external(
        &mut self,
        now_ms: u64,
        line: String,
    ) {
        self.note(now_ms, line);
    }

    /// 按当前状态重新给出扫描指令。
    ///
    /// 用在两个时机:权限刚批下来(此前的开扫必然失败),以及扫描被系统掐掉后重开。
    pub fn scan_command(&mut self, now_ms: u64) -> Scan {
        self.commit_scan_at(now_ms)
    }

    /// 目标扫描状态与上次下发的不同时才返回。
    ///
    /// 每个事件都重下指令的话,安卓侧会不停地停扫再开扫,那本身就是一次射频扰动。
    pub fn scan_if_changed(
        &mut self,
        now_ms: u64,
    ) -> Option<Scan> {
        let want = self.desired_scan(now_ms);
        if self.last_scan.as_ref() == Some(&want) {
            return None;
        }
        Some(self.commit_scan_at(now_ms))
    }

    /// 只盯还没连上的布防设备。
    ///
    /// 已经连上的不能再扫:BLE 扫描与已建立的连接共用同一个射频,扫描窗口会挤掉
    /// 手柄的输入包 —— 轻则极短操作卡手,重则连续丢包触发 0x08 监督超时断线。
    /// 实测账单显示应用曾在手柄连着时累计扫了两个多小时。
    fn desired_scan(&self, now_ms: u64) -> Scan {
        let mut waiting: Vec<String> = self
            .armed
            .iter()
            .filter(|mac| !self.connected.contains(*mac))
            .cloned()
            .collect();
        if waiting.is_empty() {
            return Scan::Stop;
        }
        // 只要还有一台是「刚失去连接不久」,就值得用满占空比把它抢回来。
        let fast = waiting.iter().any(|mac| {
            self.waiting_since.get(mac).is_some_and(
                |since| {
                    now_ms.saturating_sub(*since)
                        < FAST_SCAN_MS
                },
            )
        });
        // 顺序稳定,测试与去重都依赖它。
        waiting.sort();
        Scan::Start {
            macs: waiting,
            fast,
        }
    }

    fn commit_scan_at(&mut self, now_ms: u64) -> Scan {
        let want = self.desired_scan(now_ms);
        self.last_scan = Some(want.clone());
        want
    }

    /// 记一条日志。连续重复的合并成一条并计次。
    ///
    /// 不合并的话,手柄不在时每 10 秒一轮的重试会在几分钟内把缓冲区刷满,
    /// 真正要看的那几行(服务是否就绪、系统有没有接管)全被挤掉。
    fn note(&mut self, now_ms: u64, line: String) {
        if let Some(last) = self.log.last_mut()
            && last.line == line
        {
            last.repeat += 1;
            // 按最后一次计龄:显示第一次的时间会让人以为事情早就停了,
            // 而它可能还在每十秒发生一遍。
            last.at_ms = now_ms;
            return;
        }
        self.log.push(Entry {
            at_ms: now_ms,
            line,
            repeat: 1,
        });
        if self.log.len() > LOG_CAP {
            self.log.remove(0);
        }
    }
}

/// 把时间差说成人话。精确到秒没有意义,看的人只想知道是刚刚还是很久以前。
fn ago(delta_ms: u64) -> String {
    let seconds = delta_ms / 1000;
    match seconds {
        0 => "刚刚".to_string(),
        1..=59 => format!("{seconds}秒前"),
        60..=3599 => format!("{}分前", seconds / 60),
        _ => format!("{}小时前", seconds / 3600),
    }
}

#[cfg(test)]
mod tests {
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
                let mut want = vec![
                    PAD.to_string(),
                    MOUSE.to_string(),
                ];
                want.sort();
                assert_eq!(macs, want);
            }
            other => panic!("期望重开扫描,实际 {other:?}"),
        }
        e.toggle(PAD, 0);
        assert_eq!(e.toggle(MOUSE, 0), Scan::Stop);
    }

    #[test]
    fn restore_arms_saved_devices_and_starts_scan() {
        // 进程被杀后重启,存盘名单要能恢复成布防状态并重新开扫。
        let mut e = Engine::new();
        assert_eq!(
            e.restore(vec![PAD.into()], 0),
            Scan::Start {
                macs: vec![PAD.into()],
                fast: true
            }
        );
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
            e.log(0)
                .iter()
                .all(|l| !l.ends_with("第 0 条"))
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
    fn unarmed_device_keeps_its_own_connection_parameters()
    {
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
        assert!(
            !e.take_keepalive(PAD, 0),
            "没连上时不该发"
        );

        e.on_connection_change(PAD, true, 1_000);
        assert!(e.take_keepalive(PAD, 1_000));
        assert!(
            !e.take_keepalive(
                PAD,
                1_000 + KEEPALIVE_GAP_MS - 1
            ),
            "未到间隔不该重发"
        );
        assert!(
            e.take_keepalive(PAD, 1_000 + KEEPALIVE_GAP_MS)
        );
    }

    #[test]
    fn keepalive_is_not_sent_to_unarmed_devices() {
        // 没布防的设备不归我们管,别去打扰它的链路。
        let mut e = armed_engine();
        e.on_connection_change(MOUSE, true, 1_000);
        assert!(!e.take_keepalive(MOUSE, 1_000));
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
        e.scan_if_changed(0);

        e.on_tick(PAD, false, 1_000 + SETTLE_MS + 1);
        assert_eq!(
            e.scan_if_changed(0),
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
        assert_eq!(e.scan_if_changed(0), Some(Scan::Stop));

        e.on_tick(PAD, false, 40_000);
        assert_eq!(
            e.scan_if_changed(0),
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
        assert_eq!(
            e.on_tick(PAD, false, 0),
            Action::Connect
        );
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
        assert_eq!(
            e.on_tick(PAD, false, at),
            Action::Connect
        );
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
            e.on_advertisement(
                PAD,
                false,
                BLIND_GAP_MS * 10
            ),
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
    fn consecutive_duplicate_lines_collapse_into_one_entry()
    {
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
        assert_eq!(
            e.attention(false, false, 10_000_000),
            None
        );
    }

    #[test]
    fn attention_is_silent_while_device_is_connected() {
        // 连着的时候没有任何要人做的事。
        let mut e = armed_engine();
        e.on_connection_change(PAD, true, 1_000);
        assert_eq!(
            e.attention(true, true, 10_000_000),
            None
        );
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
        let msg = e
            .attention(false, false, ATTENTION_MS)
            .unwrap();
        assert!(msg.contains("蓝牙"), "{msg}");
    }

    #[test]
    fn attention_stays_quiet_during_normal_wait() {
        // 刚断开的几分钟里正常重试就好,这时候弹通知纯属打扰。
        let mut e = armed_engine();
        e.on_connection_change(PAD, true, 1_000);
        e.on_connection_change(PAD, false, 2_000);
        assert_eq!(
            e.attention(
                true,
                true,
                2_000 + ATTENTION_MS - 1
            ),
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
        assert!(
            msg.contains(PAD),
            "提醒里要指明是哪台: {msg}"
        );
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
    fn attention_holds_fire_while_shizuku_is_still_binding()
    {
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
        assert_eq!(
            e.attention(true, true, 10_000_000),
            None
        );
    }
}
