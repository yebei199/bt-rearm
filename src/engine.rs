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
/// 两次重申低延迟连接参数之间的间隔。
///
/// 不是「每条链路一次」而是定期重申,理由是手柄会把监督超时抢回去。我们请求
/// 高优先级时链路是 `timeout=500`(5000 毫秒),手柄随后请求它自己的
/// `timeout=300`(3000 毫秒),系统照办 —— 实测我们的设置只维持约 6.8 秒。
///
/// 而判死线的位置直接决定掉线率:链路会反复停顿,大多数自己缓过来,所谓掉线
/// 只是某次停顿越过了监督超时。实测抓到过 2858 毫秒的停顿,距 3000 毫秒只差
/// 142 毫秒。把超时顶回 5 秒,这类停顿就连风险都算不上。
///
/// 取值是两难的折中:间隔越短,超时停在 5 秒的时间占比越高,但每次参数更新都
/// 要双方在一个约定时刻同步切换,是实打实的空口开销。
///
/// 注意实际节奏由巡检周期量化 —— 巡检 10 秒一轮,所以这里取 15 秒时,真实的
/// 重申间隔是 20 秒。手柄约 7 秒抢回一次,于是超时停在 5 秒的时间占比约三分之一。
/// 这是保守的第一步:先看「越过 3 秒但存活」的停顿计数有没有变化,不够再往下调。
/// 要更高的占比就得把这个值降到 10 秒以内(即每轮巡检都重申)。
pub const LOW_LATENCY_REFRESH_MS: u64 = 15_000;

pub const KEEPALIVE_GAP_MS: u64 = 60_000;

/// 保活开着。
///
/// 一度关掉过,因为它刚开始真正发出去(此前一直空转)那阵子掉线变密了。后来
/// 数据不支持这个指控:关掉保活的同时我也在反复重装应用,而每次启动都会对着
/// 活链路满占空比扫十秒 —— 两个变量搅在一起,密集掉线更像是后者。关掉保活之后
/// 那次真实掉线距上一次隔了 19 分钟,和更早的间隔相当。
///
/// 它的收益仍未验证:手柄的休眠由自己的固件决定,主机发的读请求算不算「有活动」
/// 只能实测。要验就开着游戏把手柄放一边十几分钟,看它会不会自己断。
pub const KEEPALIVE_ENABLED: bool = true;

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
    /// 上次向各设备重申低延迟参数的时刻。
    last_low_latency: HashMap<String, u64>,
    /// 平台说「这台不在已配对列表里」的设备。没有配对记录,连接无从谈起。
    unpaired: HashSet<String>,
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
    /// 只恢复布防名单,不顺手开扫。
    ///
    /// 开不开扫要等安卓那侧把每台设备此刻连没连问回来 —— 名单里的设备很可能
    /// 正连着,这时候开扫就是往活链路上撒扫描窗口,挤掉的是手柄的输入包。
    pub fn restore(
        &mut self,
        macs: Vec<String>,
        now_ms: u64,
    ) {
        for mac in macs {
            self.waiting_since.insert(mac.clone(), now_ms);
            self.armed.insert(mac);
        }
        self.note(
            now_ms,
            format!("恢复布防 {} 台", self.armed.len()),
        );
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
        // 看校正过的状态而不是平台的当场判断:链路刚建好的那几秒平台会说「没连」,
        // 照它办就会往一条正在建立的链路上再发一次连接。
        if self.connected.contains(mac) {
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
        if self.connected.contains(mac) {
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

    /// 把领走的低延迟许可还回来。
    ///
    /// 许可在安卓那侧真正发出去之前就被领走了,那一侧失败的话不还回来,这条链路
    /// 就再没有第二次机会 —— 而低延迟正是这个工具的手感所系。
    pub fn return_low_latency_permit(&mut self, mac: &str) {
        self.last_low_latency.remove(mac);
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
        now_ms: u64,
    ) -> bool {
        if !(self.armed.contains(mac)
            && self.connected.contains(mac))
        {
            return false;
        }
        if let Some(last) = self.last_low_latency.get(mac)
            && now_ms.saturating_sub(*last)
                < LOW_LATENCY_REFRESH_MS
        {
            return false;
        }
        self.last_low_latency
            .insert(mac.to_string(), now_ms);
        true
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
        KEEPALIVE_ENABLED && self.keepalive_due(mac, now_ms)
    }

    /// 抛开总开关,这一刻该不该给这台设备发保活。
    ///
    /// 与开关分开,是为了让节奏与适用范围这两条规则始终受测试约束 —— 开关关着
    /// 的时候,针对 take_keepalive 的用例会全部变成空转,规则一旦被改坏也看不出来。
    fn keepalive_due(
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
        self.last_low_latency.remove(mac);
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
        // 安卓那侧无法区分「链路断了」和「这次连接没连上」—— 两者都是
        // DISCONNECTED 加一个非零状态码。能区分的只有我们自己:先前认不认为
        // 它是连着的。
        let was_connected = self.connected.contains(mac);
        self.sync_connected(mac, connected, now_ms);
        if connected {
            self.blind_gap.remove(mac);
            self.note(now_ms, format!("{mac} 已连接"));
        } else if was_connected {
            // 真的掉线:清节流,下一条广播要能立刻接管,不必再等窗口 ——
            // 这正是这个工具存在的意义。刚掉线时设备多半还在(掉线而非关机),
            // 值得积极重试一次。
            self.last_try.remove(mac);
            self.blind_gap.remove(mac);
            self.note(
                now_ms,
                format!("{mac} 断开,等待广播"),
            );
        } else {
            // 这次尝试没连上。退避必须照常翻倍 —— 清零的话,手柄关机时会以
            // 最高频率无休止重试。实测过这个后果:30 秒一轮,直到手柄电池耗尽。
            self.note(
                now_ms,
                format!("{mac} 连接未成功,继续退避"),
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
        // 配对记录没了是确定的事实,不像「久等不回」那样需要先观望 —— 观望
        // 只是让用户多困惑几分钟,而这件事无论如何都得他去处理。
        let mut lost: Vec<&str> = self
            .armed
            .iter()
            .filter(|m| self.unpaired.contains(*m))
            .map(|m| m.as_str())
            .collect();
        if !lost.is_empty() {
            lost.sort();
            return Some(format!(
                "{} 的配对记录不在了,要到系统设置里重新配对",
                lost.join("、")
            ));
        }
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

    /// 平台报告这台设备已经不在配对列表里了。
    ///
    /// 配对记录是这一切的地基:没有它,系统的连接接口无从谈起,扫到广播也没用。
    /// 记下来是为了立刻停扫 —— 否则会为一台不存在的设备满占空比空转,而旁边
    /// 往往还有别的设备正连着,那是在白抢射频。
    pub fn on_unpaired(&mut self, mac: &str, now_ms: u64) {
        if self.unpaired.insert(mac.to_string()) {
            self.note(
                now_ms,
                format!("{mac} 配对记录不在了"),
            );
        }
    }

    /// 配对记录又回来了,恢复正常工作。
    pub fn on_paired(&mut self, mac: &str, now_ms: u64) {
        if self.unpaired.remove(mac) {
            // 重新配好就当作刚开始等它,免得沿用几小时前的起点直接触发提醒。
            self.waiting_since
                .insert(mac.to_string(), now_ms);
            self.note(now_ms, format!("{mac} 已重新配对"));
        }
    }

    /// 这台设备当前有没有配对记录。安卓那侧据此决定要不要去问平台。
    pub fn is_unpaired(&self, mac: &str) -> bool {
        self.unpaired.contains(mac)
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

    /// 安卓那侧没能把扫描开起来。
    ///
    /// 引擎记的是「我下发过什么」而不是「平台真的在扫」,这是有意的 —— 每个事件
    /// 都重下指令会让扫描不停地停开,那本身就是射频扰动。代价是失败必须报回来,
    /// 否则目标状态没变就再也不会重下,扫描永远回不来。
    pub fn on_scan_failed(&mut self, now_ms: u64) {
        self.last_scan = None;
        self.note(
            now_ms,
            "扫描没能开起来,下一轮重试".into(),
        );
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
            // 没有配对记录的设备扫到了也用不上。
            .filter(|mac| !self.unpaired.contains(*mac))
            // 连接已经发出去的那几秒也别扫。那段时间收到的广播引擎一律按
            // 「连接中」跳过,扫了也不会被采纳;而满占空比扫描一直占着接收机,
            // 系统的连接发起器要用同一个射频去等对方的广播。
            .filter(|mac| !self.attempt_in_flight(mac, now_ms))
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

    /// 这台设备上有没有一次刚发出去、还没有结果的连接尝试。
    ///
    /// 判据就是重试窗口本身:窗口内不会再发第二次,也不会采纳任何广播。
    fn attempt_in_flight(
        &self,
        mac: &str,
        now_ms: u64,
    ) -> bool {
        self.last_try.get(mac).is_some_and(|last| {
            now_ms.saturating_sub(*last) < RETRY_GAP_MS
        })
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
mod tests;
