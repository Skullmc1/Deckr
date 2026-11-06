// No console window in release builds.
#![cfg_attr(not(debug_assertions), windows_subsystem = "windows")]

mod ble;
mod config;
mod engine;
mod input;
mod pad;
mod pointer;

use engine::{show_main, Engine, Snapshot};
use std::sync::Arc;
use std::time::Duration;
use tauri::menu::{Menu, MenuItem, PredefinedMenuItem};
use tauri::tray::{MouseButton, MouseButtonState, TrayIconBuilder, TrayIconEvent};
use tauri::{AppHandle, State, WindowEvent};
use tauri_plugin_autostart::{MacosLauncher, ManagerExt};

type Eng<'a> = State<'a, Arc<Engine>>;

#[tauri::command]
fn get_state(engine: Eng) -> Snapshot {
    engine.snapshot()
}

#[tauri::command]
fn respond(engine: Eng, id: String, allow: bool) {
    engine.respond(&id, allow);
}

#[tauri::command]
fn forget(engine: Eng, id: String) {
    engine.forget(&id);
}

#[tauri::command]
fn set_require_approval(engine: Eng, value: bool) {
    engine.set_require_approval(value);
}

#[tauri::command]
fn set_controller(engine: Eng, value: String) {
    engine.set_controller(&value);
}

#[tauri::command]
fn set_smooth_pointer(engine: Eng, value: bool) {
    engine.set_smooth_pointer(value);
}

#[tauri::command]
fn retry_gamepad(engine: Eng) {
    engine.retry_gamepad();
}

#[tauri::command]
fn set_autostart(app: AppHandle, engine: Eng, value: bool) -> Result<(), String> {
    let launcher = app.autolaunch();
    let result = if value { launcher.enable() } else { launcher.disable() };
    engine.save_autostart_choice(value);
    engine.set_autostart(launcher.is_enabled().unwrap_or(false));
    result.map_err(|e| e.to_string())
}

/// Opens the ViGEmBus download page in the browser. Only this one address is allowed.
#[tauri::command]
fn open_driver_page() {
    let _ = std::process::Command::new("explorer.exe")
        .arg("https://github.com/nefarius/ViGEmBus/releases/latest")
        .spawn();
}

fn main() {
    let start_hidden = std::env::args().any(|a| a == "--hidden");
    let engine = Arc::new(Engine::new());
    let setup_engine = engine.clone();

    tauri::Builder::default()
        // A second launch (e.g. from the Start menu) just brings the window forward.
        .plugin(tauri_plugin_single_instance::init(|app, _args, _cwd| show_main(app)))
        .plugin(tauri_plugin_autostart::init(MacosLauncher::LaunchAgent, Some(vec!["--hidden"])))
        .manage(engine)
        .invoke_handler(tauri::generate_handler![
            get_state,
            respond,
            forget,
            set_require_approval,
            set_controller,
            retry_gamepad,
            set_smooth_pointer,
            set_autostart,
            open_driver_page
        ])
        .setup(move |app| {
            let engine = setup_engine;
            engine.attach(app.handle().clone());

            // Start with Windows unless the user switched it off. Checked on every launch since an
            // update removes the entry. Debug builds skip this so a dev build never registers itself.
            let launcher = app.autolaunch();
            if !cfg!(debug_assertions) && engine.autostart_wanted() && !launcher.is_enabled().unwrap_or(false) {
                let _ = launcher.enable();
            }
            engine.set_autostart(launcher.is_enabled().unwrap_or(false));

            let open = MenuItem::with_id(app, "open", "Open Deckr", true, None::<&str>)?;
            let quit = MenuItem::with_id(app, "quit", "Quit Deckr", true, None::<&str>)?;
            let menu = Menu::with_items(app, &[&open, &PredefinedMenuItem::separator(app)?, &quit])?;
            TrayIconBuilder::with_id("tray")
                .icon(app.default_window_icon().cloned().expect("app icon"))
                .tooltip("Deckr")
                .menu(&menu)
                .show_menu_on_left_click(false)
                .on_menu_event(|app, event| match event.id.as_ref() {
                    "open" => show_main(app),
                    "quit" => app.exit(0),
                    _ => {}
                })
                .on_tray_icon_event(|tray, event| {
                    if let TrayIconEvent::Click { button: MouseButton::Left, button_state: MouseButtonState::Up, .. } = event {
                        show_main(tray.app_handle());
                    }
                })
                .build(app)?;

            ble::spawn(engine.clone());
            std::thread::spawn(move || loop {
                std::thread::sleep(Duration::from_secs(1));
                engine.tick();
            });

            if !start_hidden {
                show_main(app.handle());
            }
            Ok(())
        })
        // Closing the window keeps Deckr running in the tray.
        .on_window_event(|window, event| {
            if let WindowEvent::CloseRequested { api, .. } = event {
                api.prevent_close();
                let _ = window.hide();
            }
        })
        .run(tauri::generate_context!())
        .expect("Deckr failed to start");
}
