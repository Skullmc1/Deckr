//! Virtual controller (DualShock 4 or Xbox 360) through the ViGEmBus driver.

use std::time::{Duration, Instant};
use vigem_client::{Client, DS4Report, DualShock4Wired, Error, TargetId, XButtons, XGamepad, Xbox360Wired};

// PAD button bits sent by the phone
const CROSS: u16 = 0;
const CIRCLE: u16 = 1;
const SQUARE: u16 = 2;
const TRIANGLE: u16 = 3;
const L1: u16 = 4;
const R1: u16 = 5;
const L3: u16 = 6;
const R3: u16 = 7;
const CREATE: u16 = 8;
const OPTIONS: u16 = 9;
const PS: u16 = 10;
const TOUCHPAD: u16 = 11;

const D_UP: u8 = 1;
const D_RIGHT: u8 = 2;
const D_DOWN: u8 = 4;
const D_LEFT: u8 = 8;

#[derive(Clone, Copy, PartialEq, Eq)]
pub struct PadState {
    pub buttons: u16,
    pub dpad: u8,
    pub lx: u8,
    pub ly: u8,
    pub rx: u8,
    pub ry: u8,
    pub l2: u8,
    pub r2: u8,
}

impl PadState {
    pub const NEUTRAL: PadState = PadState { buttons: 0, dpad: 0, lx: 128, ly: 128, rx: 128, ry: 128, l2: 0, r2: 0 };

    /// Parses a PAD packet body: u16 buttons, u8 dpad, u8 lx ly rx ry l2 r2.
    pub fn parse(b: &[u8]) -> Option<PadState> {
        if b.len() < 9 {
            return None;
        }
        Some(PadState {
            buttons: u16::from_le_bytes([b[0], b[1]]),
            dpad: b[2],
            lx: b[3],
            ly: b[4],
            rx: b[5],
            ry: b[6],
            l2: b[7],
            r2: b[8],
        })
    }

    fn has(&self, bit: u16) -> bool {
        self.buttons & (1 << bit) != 0
    }
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum PadKind {
    Ds4,
    Xbox,
}

impl PadKind {
    pub fn from_name(name: &str) -> PadKind {
        if name == "xbox" { PadKind::Xbox } else { PadKind::Ds4 }
    }
    pub fn label(self) -> &'static str {
        match self {
            PadKind::Ds4 => "DualShock 4",
            PadKind::Xbox => "Xbox 360",
        }
    }
}

enum Target {
    Ds4(DualShock4Wired<Client>),
    Xbox(Xbox360Wired<Client>),
}

#[derive(Clone, Copy, PartialEq, Eq)]
pub enum PadStatus {
    /// Not plugged in yet; plugs in on the first gamepad input.
    Idle,
    Active,
    DriverMissing,
    Failed,
}

pub struct Pad {
    kind: PadKind,
    target: Option<Target>,
    last: Option<PadState>,
    error: Option<(PadStatus, String, Instant)>,
}

impl Pad {
    pub fn new(kind: PadKind) -> Pad {
        Pad { kind, target: None, last: None, error: None }
    }

    pub fn status(&self) -> (PadStatus, String) {
        if self.target.is_some() {
            return (PadStatus::Active, String::new());
        }
        match &self.error {
            Some((s, msg, _)) => (*s, msg.clone()),
            None => (PadStatus::Idle, String::new()),
        }
    }

    /// Switches controller type. The old virtual controller is unplugged right away.
    pub fn set_kind(&mut self, kind: PadKind) {
        if kind != self.kind {
            self.target = None;
            self.kind = kind;
            self.last = None;
            self.error = None;
        }
    }

    /// Clears a previous error so the next input tries again (after installing the driver).
    pub fn retry(&mut self) {
        self.error = None;
    }

    fn plug(&self) -> Result<Target, Error> {
        let client = Client::connect()?;
        Ok(match self.kind {
            PadKind::Ds4 => {
                let mut t = DualShock4Wired::new(client, TargetId::DUALSHOCK4_WIRED);
                t.plugin()?;
                t.wait_ready()?;
                // The first report after plugging in always fails with error 259; get it out of the way.
                let _ = t.update(&ds4_report(PadState::NEUTRAL));
                Target::Ds4(t)
            }
            PadKind::Xbox => {
                let mut t = Xbox360Wired::new(client, TargetId::XBOX360_WIRED);
                t.plugin()?;
                t.wait_ready()?;
                let _ = t.update(&xbox_report(PadState::NEUTRAL));
                Target::Xbox(t)
            }
        })
    }

    fn ensure(&mut self) -> bool {
        if self.target.is_some() {
            return true;
        }
        if let Some((_, _, at)) = &self.error {
            if at.elapsed() < Duration::from_secs(5) {
                return false;
            }
        }
        match self.plug() {
            Ok(t) => {
                self.target = Some(t);
                self.error = None;
                self.last = None;
                true
            }
            Err(e) => {
                let (status, msg) = match e {
                    Error::BusNotFound => (PadStatus::DriverMissing, "The ViGEmBus driver isn't installed.".to_string()),
                    other => (PadStatus::Failed, format!("Couldn't create the virtual controller ({other:?}).")),
                };
                self.error = Some((status, msg, Instant::now()));
                false
            }
        }
    }

    pub fn update(&mut self, s: PadState) {
        if self.last == Some(s) || !self.ensure() {
            return;
        }
        let mut result = self.send(s);
        if matches!(result, Err(ref e) if !is_fatal(e)) {
            // ViGEmBus sometimes reports a harmless error (e.g. 259); its own client ignores these.
            result = self.send(s);
        }
        match result {
            Ok(()) => self.last = Some(s),
            Err(e) if is_fatal(&e) => {
                self.target = None;
                self.error = Some((PadStatus::Failed, format!("The virtual controller stopped responding ({e:?})."), Instant::now()));
            }
            Err(_) => {} // not remembered as sent, so the next input tries again
        }
    }

    fn send(&mut self, s: PadState) -> Result<(), Error> {
        match self.target.as_mut() {
            Some(Target::Ds4(t)) => t.update(&ds4_report(s)),
            Some(Target::Xbox(t)) => t.update(&xbox_report(s)),
            None => Err(Error::NotPluggedIn),
        }
    }

    /// Releases everything, e.g. when the phone disconnects.
    pub fn neutral(&mut self) {
        if self.target.is_some() {
            self.update(PadState::NEUTRAL);
        }
    }
}

/// Errors that mean the controller is really gone, as opposed to a one-off hiccup.
fn is_fatal(e: &Error) -> bool {
    // 2 file not found, 5 access denied (invalid target), 6 invalid handle, 1167 device not connected
    matches!(e, Error::NotPluggedIn | Error::BusNotFound | Error::WinError(2 | 5 | 6 | 1167))
}

fn ds4_report(s: PadState) -> DS4Report {
    let (up, right, down, left) = (s.dpad & D_UP != 0, s.dpad & D_RIGHT != 0, s.dpad & D_DOWN != 0, s.dpad & D_LEFT != 0);
    // DS4 hat: 0 = north, clockwise, 8 = released
    let hat: u16 = if up && right {
        1
    } else if down && right {
        3
    } else if down && left {
        5
    } else if up && left {
        7
    } else if up {
        0
    } else if right {
        2
    } else if down {
        4
    } else if left {
        6
    } else {
        8
    };
    let mut buttons = hat;
    for (bit, ds4) in [
        (SQUARE, 4),
        (CROSS, 5),
        (CIRCLE, 6),
        (TRIANGLE, 7),
        (L1, 8),
        (R1, 9),
        (CREATE, 12),
        (OPTIONS, 13),
        (L3, 14),
        (R3, 15),
    ] {
        if s.has(bit) {
            buttons |= 1 << ds4;
        }
    }
    // Analog triggers also press their digital buttons, like a real DS4.
    if s.l2 > 30 {
        buttons |= 1 << 10;
    }
    if s.r2 > 30 {
        buttons |= 1 << 11;
    }
    let mut special = 0u8;
    if s.has(PS) {
        special |= 1;
    }
    if s.has(TOUCHPAD) {
        special |= 2;
    }
    // DS4 sticks are 0..255 with 0 = up, same as the phone sends.
    DS4Report {
        thumb_lx: s.lx,
        thumb_ly: s.ly,
        thumb_rx: s.rx,
        thumb_ry: s.ry,
        buttons,
        special,
        trigger_l: s.l2,
        trigger_r: s.r2,
    }
}

fn xbox_report(s: PadState) -> XGamepad {
    let mut raw = 0u16;
    for (bit, x) in [
        (CROSS, XButtons::A),
        (CIRCLE, XButtons::B),
        (SQUARE, XButtons::X),
        (TRIANGLE, XButtons::Y),
        (L1, XButtons::LB),
        (R1, XButtons::RB),
        (L3, XButtons::LTHUMB),
        (R3, XButtons::RTHUMB),
        (CREATE, XButtons::BACK),
        (OPTIONS, XButtons::START),
        (PS, XButtons::GUIDE),
    ] {
        if s.has(bit) {
            raw |= x;
        }
    }
    for (bit, x) in [(D_UP, XButtons::UP), (D_RIGHT, XButtons::RIGHT), (D_DOWN, XButtons::DOWN), (D_LEFT, XButtons::LEFT)] {
        if s.dpad & bit != 0 {
            raw |= x;
        }
    }
    // Xbox sticks are -32768..32767 with + = up.
    fn axis(v: u8, invert: bool) -> i16 {
        let f = ((v as f32 - 128.0) / 127.0).clamp(-1.0, 1.0);
        ((if invert { -f } else { f }) * 32767.0) as i16
    }
    XGamepad {
        buttons: XButtons(raw),
        left_trigger: s.l2,
        right_trigger: s.r2,
        thumb_lx: axis(s.lx, false),
        thumb_ly: axis(s.ly, true),
        thumb_rx: axis(s.rx, false),
        thumb_ry: axis(s.ry, true),
    }
}
