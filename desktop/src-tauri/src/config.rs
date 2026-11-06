//! Settings and the list of phones allowed to connect, saved in %APPDATA%\Deckr\config.json.

use serde::{Deserialize, Serialize};
use std::path::PathBuf;

#[derive(Serialize, Deserialize, Clone)]
pub struct Trusted {
    pub id: String,
    pub name: String,
    /// Unix seconds
    pub last_used: u64,
}

#[derive(Serialize, Deserialize, Clone)]
#[serde(default)]
pub struct Config {
    pub trusted: Vec<Trusted>,
    /// Ask on the PC before a phone that hasn't connected before is let in.
    pub require_approval: bool,
    /// "ds4" or "xbox"
    pub controller: String,
    /// Start with Windows. Re-applied on every launch, because updating Deckr runs the old
    /// uninstaller, which deletes the startup entry.
    pub autostart: bool,
    /// Spread pointer movement between Bluetooth packets for a smoother cursor.
    pub smooth_pointer: bool,
}

impl Default for Config {
    fn default() -> Self {
        Config {
            trusted: Vec::new(),
            require_approval: true,
            controller: "ds4".into(),
            autostart: true,
            smooth_pointer: true,
        }
    }
}

fn path() -> PathBuf {
    let base = std::env::var_os("APPDATA").map(PathBuf::from).unwrap_or_else(|| PathBuf::from("."));
    base.join("Deckr").join("config.json")
}

impl Config {
    pub fn load() -> Config {
        std::fs::read_to_string(path())
            .ok()
            .and_then(|s| serde_json::from_str(&s).ok())
            .unwrap_or_default()
    }

    pub fn save(&self) {
        let p = path();
        if let Some(dir) = p.parent() {
            let _ = std::fs::create_dir_all(dir);
        }
        if let Ok(s) = serde_json::to_string_pretty(self) {
            let tmp = p.with_extension("json.tmp");
            if std::fs::write(&tmp, s).is_ok() {
                let _ = std::fs::rename(&tmp, &p);
            }
        }
    }
}
