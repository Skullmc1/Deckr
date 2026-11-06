//! Turns packets from phones into input, tracks who is connected and which phones are allowed,
//! and pushes a snapshot of everything to the window whenever it changes.

use crate::config::{Config, Trusted};
use crate::input;
use crate::pointer;
use crate::pad::{Pad, PadKind, PadState, PadStatus};
use serde::Serialize;
use std::collections::{HashMap, VecDeque};
use std::sync::{Mutex, OnceLock};
use std::time::{Duration, Instant, SystemTime, UNIX_EPOCH};
use tauri::{AppHandle, Emitter, Manager};

const HELLO: u8 = 0x00;
const MOVE: u8 = 0x01;
const BUTTON: u8 = 0x02;
const SCROLL: u8 = 0x03;
const TEXT: u8 = 0x04;
const KEY: u8 = 0x05;
const CLICK: u8 = 0x06;
const PAD: u8 = 0x10;
const PING: u8 = 0x7F;

/// Values the phone reads from the status characteristic.
const STATUS_UNKNOWN: u8 = 0;
const STATUS_OK: u8 = 1;
const STATUS_WAITING: u8 = 2;
const STATUS_DENIED: u8 = 3;

/// A link with no packets or status reads for this long is treated as gone. The phone pings every 3 s.
const SESSION_TIMEOUT: Duration = Duration::from_secs(10);

#[derive(Clone, Copy, PartialEq, Eq)]
enum Auth {
    Unknown,
    Waiting,
    Approved,
    Denied,
}

struct Session {
    phone_id: Option<String>,
    name: String,
    auth: Auth,
    last_seen: Instant,
    /// Input packets since the last tick, and the resulting rate shown in the window.
    packets: u32,
    rate: u32,
}

struct State {
    config: Config,
    /// Keyed by the Bluetooth link, so two phones at once each get their own entry.
    sessions: HashMap<String, Session>,
    ble: &'static str,
    ble_detail: String,
    autostart: bool,
    log: VecDeque<String>,
}

#[derive(Serialize, Clone)]
pub struct PhoneView {
    id: String,
    name: String,
    /// "connected" or "waiting"
    state: &'static str,
}

#[derive(Serialize, Clone)]
pub struct TrustedView {
    id: String,
    name: String,
    last_used: u64,
    connected: bool,
    /// Input packets per second from this phone right now.
    rate: u32,
}

#[derive(Serialize, Clone)]
pub struct Snapshot {
    /// "starting", "ready", "off", "unsupported" or "error"
    ble: &'static str,
    ble_detail: String,
    pc_name: String,
    phones: Vec<PhoneView>,
    trusted: Vec<TrustedView>,
    require_approval: bool,
    smooth_pointer: bool,
    controller: String,
    autostart: bool,
    /// "idle", "active", "missing" or "error"
    gamepad: &'static str,
    gamepad_detail: String,
    log: Vec<String>,
    version: &'static str,
}

pub struct Engine {
    st: Mutex<State>,
    pad: Mutex<Pad>,
    app: OnceLock<AppHandle>,
}

fn now_secs() -> u64 {
    SystemTime::now().duration_since(UNIX_EPOCH).map(|d| d.as_secs()).unwrap_or(0)
}

fn clock() -> String {
    let t = unsafe { windows::Win32::System::SystemInformation::GetLocalTime() };
    format!("{:02}:{:02}", t.wHour, t.wMinute)
}

fn i16le(b: &[u8], at: usize) -> i32 {
    i16::from_le_bytes([b[at], b[at + 1]]) as i32
}

/// HELLO body: 16-byte install id, then the phone's name in UTF-8.
fn parse_hello(body: &[u8]) -> Option<(String, String)> {
    if body.len() < 16 {
        return None;
    }
    let id: String = body[..16].iter().map(|b| format!("{b:02x}")).collect();
    let name: String = String::from_utf8_lossy(&body[16..])
        .chars()
        .filter(|c| !c.is_control())
        .take(40)
        .collect::<String>()
        .trim()
        .to_string();
    Some((id, if name.is_empty() { "Phone".into() } else { name }))
}

impl State {
    fn push_log(&mut self, msg: impl Into<String>) {
        let line = format!("{}  {}", clock(), msg.into());
        #[cfg(debug_assertions)]
        eprintln!("{line}");
        self.log.push_front(line);
        self.log.truncate(60);
    }

    fn remember(&mut self, id: &str, name: &str) {
        match self.config.trusted.iter_mut().find(|t| t.id == id) {
            Some(t) => {
                t.name = name.to_string();
                t.last_used = now_secs();
            }
            None => self.config.trusted.push(Trusted { id: id.into(), name: name.into(), last_used: now_secs() }),
        }
        self.config.save();
    }
}

impl Engine {
    pub fn new() -> Engine {
        let config = Config::load();
        let pad = Pad::new(PadKind::from_name(&config.controller));
        pointer::set_smoothing(config.smooth_pointer);
        Engine {
            st: Mutex::new(State {
                config,
                sessions: HashMap::new(),
                ble: "starting",
                ble_detail: String::new(),
                autostart: false,
                log: VecDeque::new(),
            }),
            pad: Mutex::new(pad),
            app: OnceLock::new(),
        }
    }

    pub fn attach(&self, app: AppHandle) {
        let _ = self.app.set(app);
    }

    // ------------------------------------------------------------------ packets from phones

    pub fn handle(&self, conn: &str, data: &[u8]) {
        let Some((&kind, body)) = data.split_first() else { return };
        if kind == HELLO {
            self.hello(conn, body);
            return;
        }
        {
            let mut st = self.st.lock().unwrap();
            let Some(s) = st.sessions.get_mut(conn) else { return };
            s.last_seen = Instant::now();
            if s.auth != Auth::Approved {
                return;
            }
            if kind != PING {
                s.packets += 1;
            }
        }
        match kind {
            MOVE if body.len() >= 4 => pointer::move_by(i16le(body, 0), i16le(body, 2)),
            BUTTON if body.len() >= 2 => pointer::button(body[0], body[1] != 0),
            CLICK if !body.is_empty() => pointer::click(body[0]),
            SCROLL if body.len() >= 4 => input::mouse_scroll(i16le(body, 0), i16le(body, 2)),
            TEXT => input::type_text(&String::from_utf8_lossy(body)),
            KEY if !body.is_empty() => input::press_key(body[0]),
            PAD => {
                if let Some(state) = PadState::parse(body) {
                    let changed = {
                        let mut pad = self.pad.lock().unwrap();
                        let before = pad.status().0;
                        pad.update(state);
                        pad.status().0 != before
                    };
                    if changed {
                        self.emit();
                    }
                }
            }
            _ => {} // PING and anything unknown just keep the link alive
        }
    }

    fn hello(&self, conn: &str, body: &[u8]) {
        let mut ask = false;
        {
            let mut guard = self.st.lock().unwrap();
            let st = &mut *guard;
            let s = st.sessions.entry(conn.to_string()).or_insert_with(|| Session {
                phone_id: None,
                name: "Phone".into(),
                auth: Auth::Unknown,
                last_seen: Instant::now(),
                packets: 0,
                rate: 0,
            });
            s.last_seen = Instant::now();
            let Some((id, name)) = parse_hello(body) else {
                if s.auth != Auth::Denied {
                    s.auth = Auth::Denied;
                    st.push_log("A phone with an old version of Deckr tried to connect. Update the app on the phone.");
                    drop(guard);
                    self.emit();
                }
                return;
            };
            if s.phone_id.as_deref() == Some(id.as_str()) && s.auth != Auth::Unknown {
                return; // repeated hello on the same link
            }
            s.phone_id = Some(id.clone());
            s.name = name.clone();
            let trusted = st.config.trusted.iter().any(|t| t.id == id);
            if trusted || !st.config.require_approval {
                s.auth = Auth::Approved;
                st.remember(&id, &name);
                st.push_log(format!("{name} connected"));
            } else if st.sessions.values().any(|o| o.phone_id.as_deref() == Some(id.as_str()) && o.auth == Auth::Denied && o.last_seen.elapsed() < Duration::from_secs(30)) {
                // Denied a moment ago on an earlier link: don't keep asking.
                st.sessions.get_mut(conn).unwrap().auth = Auth::Denied;
            } else {
                st.sessions.get_mut(conn).unwrap().auth = Auth::Waiting;
                st.push_log(format!("{name} wants to connect"));
                ask = true;
            }
        }
        self.emit();
        if ask {
            self.show_window();
        }
    }

    pub fn has_links(&self) -> bool {
        !self.st.lock().unwrap().sessions.is_empty()
    }

    /// Answer for the status characteristic. Reading it also counts as activity.
    pub fn status(&self, conn: &str) -> u8 {
        let mut st = self.st.lock().unwrap();
        match st.sessions.get_mut(conn) {
            Some(s) => {
                s.last_seen = Instant::now();
                match s.auth {
                    Auth::Unknown => STATUS_UNKNOWN,
                    Auth::Waiting => STATUS_WAITING,
                    Auth::Approved => STATUS_OK,
                    Auth::Denied => STATUS_DENIED,
                }
            }
            None => STATUS_UNKNOWN,
        }
    }

    /// The Bluetooth link closed.
    pub fn end(&self, conn: &str) {
        let release_pad;
        {
            let mut st = self.st.lock().unwrap();
            let Some(s) = st.sessions.remove(conn) else { return };
            match s.auth {
                Auth::Approved => st.push_log(format!("{} disconnected", s.name)),
                Auth::Waiting => st.push_log(format!("{} stopped waiting", s.name)),
                _ => {}
            }
            release_pad = !st.sessions.values().any(|s| s.auth == Auth::Approved);
        }
        if release_pad {
            self.pad.lock().unwrap().neutral();
        }
        self.emit();
    }

    /// Called every second: updates the input rate and drops links that went quiet.
    pub fn tick(&self) {
        let (expired, rate_changed) = {
            let mut st = self.st.lock().unwrap();
            let mut changed = false;
            for s in st.sessions.values_mut() {
                let rate = std::mem::take(&mut s.packets);
                if rate != s.rate {
                    changed = true;
                    s.rate = rate;
                }
            }
            let expired: Vec<String> =
                st.sessions.iter().filter(|(_, s)| s.last_seen.elapsed() > SESSION_TIMEOUT).map(|(k, _)| k.clone()).collect();
            (expired, changed)
        };
        if rate_changed {
            self.emit();
        }
        for conn in expired {
            self.end(&conn);
        }
    }

    // ------------------------------------------------------------------ actions from the window

    pub fn respond(&self, phone_id: &str, allow: bool) {
        {
            let mut guard = self.st.lock().unwrap();
            let st = &mut *guard;
            let mut name = None;
            for s in st.sessions.values_mut() {
                if s.phone_id.as_deref() == Some(phone_id) && s.auth == Auth::Waiting {
                    s.auth = if allow { Auth::Approved } else { Auth::Denied };
                    name = Some(s.name.clone());
                }
            }
            let Some(name) = name else { return };
            if allow {
                st.remember(phone_id, &name);
                st.push_log(format!("Allowed {name}"));
                st.push_log(format!("{name} connected"));
            } else {
                st.push_log(format!("Turned away {name}"));
            }
        }
        self.emit();
    }

    pub fn forget(&self, phone_id: &str) {
        {
            let mut st = self.st.lock().unwrap();
            let before = st.config.trusted.len();
            st.config.trusted.retain(|t| t.id != phone_id);
            if st.config.trusted.len() == before {
                return;
            }
            st.config.save();
        }
        self.emit();
    }

    pub fn set_require_approval(&self, value: bool) {
        {
            let mut st = self.st.lock().unwrap();
            st.config.require_approval = value;
            st.config.save();
        }
        self.emit();
    }

    pub fn set_controller(&self, name: &str) {
        let kind = PadKind::from_name(name);
        {
            let mut st = self.st.lock().unwrap();
            st.config.controller = if kind == PadKind::Xbox { "xbox".into() } else { "ds4".into() };
            st.config.save();
            st.push_log(format!("Gamepad set to {}", kind.label()));
        }
        self.pad.lock().unwrap().set_kind(kind);
        self.emit();
    }

    pub fn set_smooth_pointer(&self, value: bool) {
        {
            let mut st = self.st.lock().unwrap();
            st.config.smooth_pointer = value;
            st.config.save();
        }
        pointer::set_smoothing(value);
        self.emit();
    }

    pub fn retry_gamepad(&self) {
        self.pad.lock().unwrap().retry();
        self.emit();
    }

    pub fn set_autostart(&self, value: bool) {
        self.st.lock().unwrap().autostart = value;
        self.emit();
    }

    pub fn autostart_wanted(&self) -> bool {
        self.st.lock().unwrap().config.autostart
    }

    pub fn save_autostart_choice(&self, value: bool) {
        let mut st = self.st.lock().unwrap();
        st.config.autostart = value;
        st.config.save();
    }

    pub fn set_ble(&self, status: &'static str, detail: impl Into<String>) {
        let detail = detail.into();
        {
            let mut st = self.st.lock().unwrap();
            if st.ble == status && st.ble_detail == detail {
                return;
            }
            let was = st.ble;
            st.ble = status;
            st.ble_detail = detail.clone();
            match status {
                "ready" => st.push_log("Bluetooth ready. Phones can find this PC."),
                "off" if was != "off" => st.push_log("Bluetooth is off"),
                "error" | "unsupported" => st.push_log(detail),
                _ => {}
            }
        }
        self.emit();
    }

    // ------------------------------------------------------------------ window updates

    pub fn snapshot(&self) -> Snapshot {
        let (gamepad, gamepad_detail) = {
            let pad = self.pad.lock().unwrap();
            let (s, d) = pad.status();
            let label = match s {
                PadStatus::Idle => "idle",
                PadStatus::Active => "active",
                PadStatus::DriverMissing => "missing",
                PadStatus::Failed => "error",
            };
            (label, d)
        };
        let st = self.st.lock().unwrap();
        let mut phones: Vec<PhoneView> = st
            .sessions
            .values()
            .filter_map(|s| {
                let state = match s.auth {
                    Auth::Approved => "connected",
                    Auth::Waiting => "waiting",
                    _ => return None,
                };
                Some(PhoneView { id: s.phone_id.clone().unwrap_or_default(), name: s.name.clone(), state })
            })
            .collect();
        phones.sort_by(|a, b| a.name.cmp(&b.name));
        phones.dedup_by(|a, b| a.id == b.id && a.state == b.state);
        let mut trusted: Vec<TrustedView> = st
            .config
            .trusted
            .iter()
            .map(|t| {
                let mine = st.sessions.values().filter(|s| s.auth == Auth::Approved && s.phone_id.as_deref() == Some(t.id.as_str()));
                let (mut connected, mut rate) = (false, 0);
                for s in mine {
                    connected = true;
                    rate += s.rate;
                }
                TrustedView { id: t.id.clone(), name: t.name.clone(), last_used: t.last_used, connected, rate }
            })
            .collect();
        trusted.sort_by(|a, b| b.connected.cmp(&a.connected).then(b.last_used.cmp(&a.last_used)));
        Snapshot {
            ble: st.ble,
            ble_detail: st.ble_detail.clone(),
            pc_name: std::env::var("COMPUTERNAME").unwrap_or_else(|_| "this PC".into()),
            phones,
            trusted,
            require_approval: st.config.require_approval,
            smooth_pointer: st.config.smooth_pointer,
            controller: st.config.controller.clone(),
            autostart: st.autostart,
            gamepad,
            gamepad_detail,
            log: st.log.iter().cloned().collect(),
            version: env!("CARGO_PKG_VERSION"),
        }
    }

    fn emit(&self) {
        let Some(app) = self.app.get() else { return };
        let snap = self.snapshot();
        let connected: Vec<&str> = snap.phones.iter().filter(|p| p.state == "connected").map(|p| p.name.as_str()).collect();
        let tip = match (snap.ble, connected.is_empty()) {
            ("ready", true) => "Deckr: waiting for a phone".to_string(),
            ("ready", false) => format!("Deckr: {} connected", connected.join(", ")),
            ("off", _) => "Deckr: Bluetooth is off".to_string(),
            _ => "Deckr".to_string(),
        };
        if let Some(tray) = app.tray_by_id("tray") {
            let _ = tray.set_tooltip(Some(tip));
        }
        let _ = app.emit("state", snap);
    }

    pub fn show_window(&self) {
        if let Some(app) = self.app.get() {
            show_main(app);
        }
    }
}

pub fn show_main(app: &AppHandle) {
    if let Some(w) = app.get_webview_window("main") {
        let _ = w.show();
        let _ = w.unminimize();
        let _ = w.set_focus();
    }
}
