//! Smooth pointer movement.
//!
//! Bluetooth delivers the phone's movement in bursts, one packet every ~11-15 ms. Moving the cursor
//! by each packet's full amount looks steppy, so each move is spread over the gap until the next
//! packet is expected and fed to Windows in ~1 ms steps. Clicks first finish any movement still in
//! flight so they land where the pointer was headed.

use crate::input;
use std::sync::atomic::{AtomicBool, Ordering};
use std::sync::mpsc::{self, Receiver, RecvTimeoutError, Sender};
use std::sync::OnceLock;
use std::thread;
use std::time::{Duration, Instant};

enum Cmd {
    Move(i32, i32),
    Button(u8, bool),
    Click(u8),
}

static TX: OnceLock<Sender<Cmd>> = OnceLock::new();
static SMOOTH: AtomicBool = AtomicBool::new(true);

pub fn set_smoothing(on: bool) {
    SMOOTH.store(on, Ordering::Relaxed);
}

fn tx() -> &'static Sender<Cmd> {
    TX.get_or_init(|| {
        let (tx, rx) = mpsc::channel();
        thread::Builder::new().name("deckr-pointer".into()).spawn(move || run(rx)).expect("start the pointer thread");
        tx
    })
}

pub fn move_by(dx: i32, dy: i32) {
    if dx != 0 || dy != 0 {
        let _ = tx().send(Cmd::Move(dx, dy));
    }
}

pub fn button(button: u8, down: bool) {
    let _ = tx().send(Cmd::Button(button, down));
}

pub fn click(button: u8) {
    let _ = tx().send(Cmd::Click(button));
}

struct Glide {
    /// Movement still to deliver, in pixels.
    rem: (f32, f32),
    /// Sub-pixel carry so rounding never loses or adds distance.
    frac: (f32, f32),
    /// Milliseconds over which `rem` should be spread.
    left: f32,
    /// Typical gap between packets, in ms.
    gap: f32,
    last_packet: Instant,
}

impl Glide {
    fn moving(&self) -> bool {
        self.rem != (0.0, 0.0)
    }

    fn emit(&mut self, x: f32, y: f32) {
        self.frac.0 += x;
        self.frac.1 += y;
        let (ix, iy) = (self.frac.0.trunc(), self.frac.1.trunc());
        self.frac.0 -= ix;
        self.frac.1 -= iy;
        input::mouse_move(ix as i32, iy as i32);
    }

    /// Delivers everything still pending right away.
    fn flush(&mut self) {
        let (x, y) = self.rem;
        self.rem = (0.0, 0.0);
        self.left = 0.0;
        self.emit(x, y);
    }

    fn add(&mut self, dx: i32, dy: i32) {
        let now = Instant::now();
        let gap = now.duration_since(self.last_packet).as_secs_f32() * 1000.0;
        self.last_packet = now;
        if gap < 50.0 {
            self.gap += (gap - self.gap) * 0.2;
        }
        self.rem.0 += dx as f32;
        self.rem.1 += dy as f32;
        // Finish this packet's movement about when the next one should arrive.
        self.left = self.gap.clamp(3.0, 20.0);
    }

    fn step(&mut self, dt: f32) {
        if !self.moving() {
            return;
        }
        if self.left <= dt {
            self.flush();
            return;
        }
        let share = dt / self.left;
        self.left -= dt;
        let (x, y) = (self.rem.0 * share, self.rem.1 * share);
        self.rem.0 -= x;
        self.rem.1 -= y;
        self.emit(x, y);
    }
}

fn run(rx: Receiver<Cmd>) {
    let mut g = Glide { rem: (0.0, 0.0), frac: (0.0, 0.0), left: 0.0, gap: 12.0, last_packet: Instant::now() };
    let mut last_tick = Instant::now();
    loop {
        // Sleep until something arrives when idle; tick about every millisecond while gliding.
        let first = if g.moving() {
            match rx.recv_timeout(Duration::from_millis(1)) {
                Ok(c) => Some(c),
                Err(RecvTimeoutError::Timeout) => None,
                Err(RecvTimeoutError::Disconnected) => return,
            }
        } else {
            let c = match rx.recv() {
                Ok(c) => c,
                Err(_) => return,
            };
            last_tick = Instant::now();
            Some(c)
        };

        let mut next = first;
        while let Some(cmd) = next.take() {
            match cmd {
                Cmd::Move(dx, dy) if SMOOTH.load(Ordering::Relaxed) => g.add(dx, dy),
                Cmd::Move(dx, dy) => {
                    g.flush();
                    input::mouse_move(dx, dy);
                }
                Cmd::Button(b, down) => {
                    g.flush();
                    input::mouse_button(b, down);
                }
                Cmd::Click(b) => {
                    g.flush();
                    input::mouse_click(b);
                }
            }
            next = rx.try_recv().ok();
        }

        let now = Instant::now();
        let dt = now.duration_since(last_tick).as_secs_f32() * 1000.0;
        last_tick = now;
        g.step(dt);
    }
}
