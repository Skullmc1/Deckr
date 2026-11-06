//! Mouse and keyboard injection through SendInput.

use std::mem::size_of;
use windows::Win32::UI::Input::KeyboardAndMouse::*;

fn send(inputs: &[INPUT]) {
    if !inputs.is_empty() {
        unsafe {
            SendInput(inputs, size_of::<INPUT>() as i32);
        }
    }
}

fn mouse(flags: MOUSE_EVENT_FLAGS, dx: i32, dy: i32, data: i32) -> INPUT {
    INPUT {
        r#type: INPUT_MOUSE,
        Anonymous: INPUT_0 {
            mi: MOUSEINPUT { dx, dy, mouseData: data as u32, dwFlags: flags, time: 0, dwExtraInfo: 0 },
        },
    }
}

fn key(vk: u16, scan: u16, flags: KEYBD_EVENT_FLAGS) -> INPUT {
    INPUT {
        r#type: INPUT_KEYBOARD,
        Anonymous: INPUT_0 {
            ki: KEYBDINPUT { wVk: VIRTUAL_KEY(vk), wScan: scan, dwFlags: flags, time: 0, dwExtraInfo: 0 },
        },
    }
}

// ---- mouse ----

fn button_flags(button: u8) -> Option<(MOUSE_EVENT_FLAGS, MOUSE_EVENT_FLAGS)> {
    match button {
        0 => Some((MOUSEEVENTF_LEFTDOWN, MOUSEEVENTF_LEFTUP)),
        1 => Some((MOUSEEVENTF_RIGHTDOWN, MOUSEEVENTF_RIGHTUP)),
        2 => Some((MOUSEEVENTF_MIDDLEDOWN, MOUSEEVENTF_MIDDLEUP)),
        _ => None,
    }
}

pub fn mouse_move(dx: i32, dy: i32) {
    if dx != 0 || dy != 0 {
        send(&[mouse(MOUSEEVENTF_MOVE, dx, dy, 0)]);
    }
}

pub fn mouse_button(button: u8, down: bool) {
    if let Some((d, u)) = button_flags(button) {
        send(&[mouse(if down { d } else { u }, 0, 0, 0)]);
    }
}

pub fn mouse_click(button: u8) {
    if let Some((d, u)) = button_flags(button) {
        send(&[mouse(d, 0, 0, 0), mouse(u, 0, 0, 0)]);
    }
}

pub fn mouse_scroll(vertical: i32, horizontal: i32) {
    let mut events = Vec::with_capacity(2);
    if vertical != 0 {
        events.push(mouse(MOUSEEVENTF_WHEEL, 0, 0, vertical));
    }
    if horizontal != 0 {
        events.push(mouse(MOUSEEVENTF_HWHEEL, 0, 0, horizontal));
    }
    send(&events);
}

// ---- keyboard ----

fn vk_for(id: u8) -> Option<VIRTUAL_KEY> {
    Some(match id {
        1 => VK_BACK,
        2 => VK_RETURN,
        3 => VK_TAB,
        4 => VK_ESCAPE,
        5 => VK_LEFT,
        6 => VK_RIGHT,
        7 => VK_UP,
        8 => VK_DOWN,
        9 => VK_DELETE,
        _ => return None,
    })
}

fn tap(vk: VIRTUAL_KEY, events: &mut Vec<INPUT>) {
    let ext = if matches!(vk, VK_LEFT | VK_RIGHT | VK_UP | VK_DOWN | VK_DELETE) {
        KEYEVENTF_EXTENDEDKEY
    } else {
        KEYBD_EVENT_FLAGS(0)
    };
    events.push(key(vk.0, 0, ext));
    events.push(key(vk.0, 0, ext | KEYEVENTF_KEYUP));
}

pub fn press_key(id: u8) {
    if let Some(vk) = vk_for(id) {
        let mut events = Vec::with_capacity(2);
        tap(vk, &mut events);
        send(&events);
    }
}

/// Types text as Unicode keystrokes, so any language and emoji work.
pub fn type_text(text: &str) {
    let mut events = Vec::with_capacity(text.len() * 2);
    for ch in text.chars() {
        match ch {
            '\n' => tap(VK_RETURN, &mut events),
            '\t' => tap(VK_TAB, &mut events),
            _ => {
                let mut buf = [0u16; 2];
                for unit in ch.encode_utf16(&mut buf) {
                    events.push(key(0, *unit, KEYEVENTF_UNICODE));
                    events.push(key(0, *unit, KEYEVENTF_UNICODE | KEYEVENTF_KEYUP));
                }
            }
        }
    }
    send(&events);
}
