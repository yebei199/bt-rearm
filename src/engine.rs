//! 布防决策核心。
//!
//! 这里不碰任何安卓 API:输入是"发生了什么"(广播到了、连接状态变了、用户点了
//! 某一行),输出是"该做什么"(连这台、启停扫描)。安卓那一侧只负责把事件送进来、
//! 把动作执行出去,不做任何判断 —— 于是这套逻辑可以在电脑上直接跑测试。

use std::collections::{HashMap, HashSet};

/// 同一台设备两次主动连接之间的最小间隔。连接建立本身要几秒,别把它打断。
pub const RETRY_GAP_MS: u64 = 8_000;

/// 收到一条广播后,决定要不要动手。
#[derive(Debug, PartialEq, Eq)]
pub enum Action {
    /// 主动连接这台设备。
    Connect,
    /// 不动手,并附上原因 —— 原因会进事件日志给用户看。
    Skip(&'static str),
}

/// 布防名单变化后,扫描该怎么走。
#[derive(Debug, PartialEq, Eq)]
pub enum Scan {
    /// 按这批地址开扫(名单变化时也用它,等价于换过滤器重开)。
    Start(Vec<String>),
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

#[derive(Default)]
pub struct Engine {
    armed: HashSet<String>,
    last_try: HashMap<String, u64>,
    connected: HashSet<String>,
    log: Vec<String>,
}

/// 事件日志保留的条数。只够看清最近发生了什么,不做长期留存。
const LOG_CAP: usize = 60;

impl Engine {
    pub fn new() -> Self {
        Self::default()
    }

    /// 用户点了某一行:布防 ↔ 撤防。
    pub fn toggle(&mut self, mac: &str) -> Scan {
        if self.armed.remove(mac) {
            self.last_try.remove(mac);
            self.note(format!("{mac} 撤防"));
        } else {
            self.armed.insert(mac.to_string());
            self.note(format!("{mac} 布防"));
        }
        self.scan_cmd()
    }

    /// 进程重启后,从存盘名单恢复布防。
    pub fn restore(&mut self, macs: Vec<String>) -> Scan {
        for mac in macs {
            self.armed.insert(mac);
        }
        self.note(format!("恢复布防 {} 台", self.armed.len()));
        self.scan_cmd()
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
        if system_connected {
            // 系统自己连上了就让路 —— 这正是布防该袖手的时候。
            self.connected.insert(mac.to_string());
            self.note(format!("{mac} 系统已连接,让路"));
            return Action::Skip("系统已连接");
        }
        // 上一次尝试还在窗口内:连接建立本身要几秒,别把它打断。
        if let Some(last) = self.last_try.get(mac)
            && now_ms.saturating_sub(*last) < RETRY_GAP_MS
        {
            return Action::Skip("连接中");
        }
        self.last_try.insert(mac.to_string(), now_ms);
        self.note(format!("{mac} 发起连接"));
        Action::Connect
    }

    /// 连接状态变了(我们连上的,或链路断了)。
    pub fn on_connection_change(&mut self, mac: &str, connected: bool) {
        if connected {
            self.connected.insert(mac.to_string());
            self.note(format!("{mac} 已连接"));
        } else {
            self.connected.remove(mac);
            // 断开即清节流:下一条广播要能立刻接管,不必再等窗口 ——
            // 这正是这个工具存在的意义。
            self.last_try.remove(mac);
            self.note(format!("{mac} 断开,等待广播"));
        }
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

    /// 决策事件日志。这台平板的 logcat 不可用,日志只能显示在界面上。
    pub fn log(&self) -> &[String] {
        &self.log
    }

    /// 安卓侧报上来的错误(扫描失败、权限被拒等)。同样进日志,理由同上。
    pub fn note_external(&mut self, line: String) {
        self.note(line);
    }

    /// 按当前名单重新给出扫描指令。
    ///
    /// 用在两个时机:权限刚批下来(此前的开扫必然失败),以及扫描被系统掐掉后重开。
    pub fn scan_command(&self) -> Scan {
        self.scan_cmd()
    }

    /// 布防名单非空才扫描,空了必须停 —— 扫描是这里唯一的耗电项。
    fn scan_cmd(&self) -> Scan {
        if self.armed.is_empty() {
            Scan::Stop
        } else {
            Scan::Start(self.armed_macs())
        }
    }

    fn note(&mut self, line: String) {
        self.log.push(line);
        if self.log.len() > LOG_CAP {
            self.log.remove(0);
        }
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
        e.toggle(PAD);
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
    fn advertisement_while_system_already_connected_is_ignored() {
        // 系统自己已经连上了就让路,不重复插手。这是用户明确要求的语义。
        let mut e = armed_engine();
        assert_eq!(
            e.on_advertisement(PAD, true, 1_000),
            Action::Skip("系统已连接")
        );
    }

    #[test]
    fn advertisement_from_armed_disconnected_device_triggers_connect() {
        // 正常路径:布防中 + 系统没连 + 收到广播 → 主动连接。
        let mut e = armed_engine();
        assert_eq!(
            e.on_advertisement(PAD, false, 1_000),
            Action::Connect
        );
    }

    #[test]
    fn repeat_advertisement_within_throttle_window_is_ignored() {
        // 手柄一秒能广播几十次,节流窗口内只能发起一次连接,
        // 否则会把正在建立的连接反复打断。
        let mut e = armed_engine();
        assert_eq!(
            e.on_advertisement(PAD, false, 1_000),
            Action::Connect
        );
        assert_eq!(
            e.on_advertisement(PAD, false, 1_000 + RETRY_GAP_MS - 1),
            Action::Skip("连接中")
        );
    }

    #[test]
    fn advertisement_after_throttle_window_triggers_connect_again() {
        // 边界:上一次尝试失败后,过了节流窗口必须能再试,不能一次失败就放弃。
        let mut e = armed_engine();
        e.on_advertisement(PAD, false, 1_000);
        assert_eq!(
            e.on_advertisement(PAD, false, 1_000 + RETRY_GAP_MS),
            Action::Connect
        );
    }

    #[test]
    fn advertisement_after_connection_drops_triggers_connect_again() {
        // 连上又断开(手柄关机、游戏中掉线)后,下一条广播应重新连接 ——
        // 这是这个工具存在的全部意义。断开即清节流,不必再等窗口。
        let mut e = armed_engine();
        e.on_advertisement(PAD, false, 1_000);
        e.on_connection_change(PAD, true);
        e.on_connection_change(PAD, false);
        assert_eq!(
            e.on_advertisement(PAD, false, 2_000),
            Action::Connect
        );
    }

    #[test]
    fn disarmed_device_is_no_longer_acted_on() {
        // 撤防之后即使还在广播也不再动手,且状态回到"未布防"。
        let mut e = armed_engine();
        assert_eq!(e.toggle(PAD), Scan::Stop);
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
        assert_eq!(e.toggle(PAD), Scan::Start(vec![PAD.into()]));
        // 第二台加入后要带着两个地址重开扫描,否则新设备不在过滤器里。
        let two = e.toggle(MOUSE);
        match two {
            Scan::Start(mut macs) => {
                macs.sort();
                let mut want = vec![PAD.to_string(), MOUSE.to_string()];
                want.sort();
                assert_eq!(macs, want);
            }
            other => panic!("期望重开扫描,实际 {other:?}"),
        }
        e.toggle(PAD);
        assert_eq!(e.toggle(MOUSE), Scan::Stop);
    }

    #[test]
    fn restore_arms_saved_devices_and_starts_scan() {
        // 进程被杀后重启,存盘名单要能恢复成布防状态并重新开扫。
        let mut e = Engine::new();
        assert_eq!(
            e.restore(vec![PAD.into()]),
            Scan::Start(vec![PAD.into()])
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
        assert_eq!(e.scan_command(), Scan::Stop);
        e.toggle(PAD);
        assert_eq!(e.scan_command(), Scan::Start(vec![PAD.into()]));
    }

    #[test]
    fn external_errors_are_recorded_in_log() {
        // 安卓侧的失败(扫描失败、权限被拒)也必须能被用户看到,
        // 否则界面显示"等待广播"而实际上扫描根本没起来。
        let mut e = Engine::new();
        e.note_external("扫描失败,错误码 2".into());
        assert!(e.log().iter().any(|l| l.contains("错误码 2")));
    }

    #[test]
    fn log_is_capped() {
        // 日志要显示在界面上,不能无限增长。
        let mut e = Engine::new();
        for i in 0..(LOG_CAP + 20) {
            e.note_external(format!("第 {i} 条"));
        }
        assert_eq!(e.log().len(), LOG_CAP);
        // 保留的是最近的,最早那条应被挤掉。
        assert!(e.log().iter().all(|l| l != "第 0 条"));
    }

    #[test]
    fn decisions_are_recorded_in_log() {
        // 这台平板 logcat 不可用,每次决策(连了/跳过及原因)都要进日志,
        // 否则再出问题依然无法定位。
        let mut e = armed_engine();
        e.on_advertisement(PAD, false, 1_000);
        e.on_advertisement(PAD, true, 20_000);
        let log = e.log();
        assert!(
            log.iter().any(|l| l.contains("连接")),
            "日志里应有发起连接的记录: {log:?}"
        );
        assert!(
            log.iter().any(|l| l.contains("系统已连接")),
            "日志里应有让路原因: {log:?}"
        );
    }
}
