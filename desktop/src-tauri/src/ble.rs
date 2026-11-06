//! Hosts the Deckr Bluetooth LE service on the PC's built-in Bluetooth.
//!
//! A supervisor thread creates the GATT service, keeps it advertising and rebuilds it if
//! Bluetooth is turned off and on again or the adapter goes away.

use crate::engine::Engine;
use std::collections::HashMap;
use std::sync::{Arc, Mutex};
use std::thread;
use std::time::Duration;
use windows::core::{Result, GUID, HSTRING};
use windows::Devices::Bluetooth::GenericAttributeProfile::*;
use windows::Devices::Bluetooth::{BluetoothAdapter, BluetoothError};
use windows::Devices::Radios::{Radio, RadioState};
use windows::Foundation::TypedEventHandler;
use windows::Storage::Streams::{DataReader, DataWriter};

const SERVICE: GUID = GUID::from_u128(0x6b1d0001_7c3e_4d8a_9f2b_5e4d3c2b1a00);
const INPUT: GUID = GUID::from_u128(0x6b1d0002_7c3e_4d8a_9f2b_5e4d3c2b1a00);
const STATUS: GUID = GUID::from_u128(0x6b1d0003_7c3e_4d8a_9f2b_5e4d3c2b1a00);

/// Open links, so each one's close event is hooked once.
type Links = Arc<Mutex<HashMap<String, GattSession>>>;

struct Server {
    radio: Option<Radio>,
    provider: GattServiceProvider,
    adv: GattServiceProviderAdvertisingParameters,
    _input: GattLocalCharacteristic,
    _status: GattLocalCharacteristic,
    failed_starts: u32,
    idle_checks: u32,
}

type Problem = (&'static str, String);

pub fn spawn(engine: Arc<Engine>) {
    thread::Builder::new()
        .name("deckr-bluetooth".into())
        .spawn(move || {
            let links: Links = Default::default();
            let mut server: Option<Server> = None;
            loop {
                if server.is_none() {
                    match Server::create(&engine, &links) {
                        Ok(s) => {
                            server = Some(s);
                            thread::sleep(Duration::from_millis(800));
                        }
                        Err((status, detail)) => engine.set_ble(status, detail),
                    }
                }
                if let Some(s) = server.as_mut() {
                    if !s.check(&engine) {
                        server = None;
                        continue;
                    }
                }
                thread::sleep(Duration::from_secs(2));
            }
        })
        .expect("start the Bluetooth thread");
}

fn link_id(engine: &Arc<Engine>, links: &Links, session: &GattSession) -> Result<String> {
    let id = session.DeviceId()?.Id()?.to_string();
    if links.lock().unwrap().contains_key(&id) {
        return Ok(id);
    }
    let eng = engine.clone();
    let map = links.clone();
    let key = id.clone();
    session.SessionStatusChanged(&TypedEventHandler::<GattSession, GattSessionStatusChangedEventArgs>::new(move |_, args| {
        if let Ok(args) = args.ok() {
            if args.Status()? == GattSessionStatus::Closed {
                map.lock().unwrap().remove(&key);
                eng.end(&key);
            }
        }
        Ok(())
    }))?;
    links.lock().unwrap().insert(id.clone(), session.clone());
    Ok(id)
}

impl Server {
    fn create(engine: &Arc<Engine>, links: &Links) -> std::result::Result<Server, Problem> {
        let adapter = BluetoothAdapter::GetDefaultAsync()
            .and_then(|op| op.join())
            .map_err(|_| ("off", "No Bluetooth adapter found.".to_string()))?;
        if !adapter.IsLowEnergySupported().unwrap_or(false) || !adapter.IsPeripheralRoleSupported().unwrap_or(false) {
            return Err(("unsupported", "This PC's Bluetooth can't host Deckr (it has no Bluetooth LE peripheral support).".into()));
        }
        let radio = adapter.GetRadioAsync().and_then(|op| op.join()).ok();
        if let Some(r) = &radio {
            if r.State().ok() != Some(RadioState::On) {
                return Err(("off", "Bluetooth is turned off.".into()));
            }
        }
        Self::build(engine, links, radio).map_err(|e| ("error", format!("Couldn't start the Bluetooth service: {}", e.message())))
    }

    fn build(engine: &Arc<Engine>, links: &Links, radio: Option<Radio>) -> Result<Server> {
        let created = GattServiceProvider::CreateAsync(SERVICE)?.join()?;
        if created.Error()? != BluetoothError::Success {
            return Err(windows::core::Error::new(windows::core::HRESULT(0x8000_4005u32 as i32), format!("{:?}", created.Error()?)));
        }
        let provider = created.ServiceProvider()?;
        let service = provider.Service()?;

        // Input: phones write packets here.
        let p = GattLocalCharacteristicParameters::new()?;
        p.SetCharacteristicProperties(GattCharacteristicProperties::Write | GattCharacteristicProperties::WriteWithoutResponse)?;
        p.SetWriteProtectionLevel(GattProtectionLevel::Plain)?;
        p.SetUserDescription(&HSTRING::from("Deckr input"))?;
        let input = service.CreateCharacteristicAsync(INPUT, &p)?.join()?.Characteristic()?;

        // Status: whether this phone is let in (see engine.rs).
        let p = GattLocalCharacteristicParameters::new()?;
        p.SetCharacteristicProperties(GattCharacteristicProperties::Read)?;
        p.SetReadProtectionLevel(GattProtectionLevel::Plain)?;
        p.SetUserDescription(&HSTRING::from("Deckr status"))?;
        let status = service.CreateCharacteristicAsync(STATUS, &p)?.join()?.Characteristic()?;

        let (eng, map) = (engine.clone(), links.clone());
        input.WriteRequested(&TypedEventHandler::<GattLocalCharacteristic, GattWriteRequestedEventArgs>::new(move |_, args| {
            let args = args.ok()?;
            let deferral = args.GetDeferral()?;
            let result = (|| -> Result<()> {
                let link = link_id(&eng, &map, &args.Session()?)?;
                let request = args.GetRequestAsync()?.join()?;
                let buffer = request.Value()?;
                let mut data = vec![0u8; buffer.Length()? as usize];
                if !data.is_empty() {
                    DataReader::FromBuffer(&buffer)?.ReadBytes(&mut data)?;
                }
                eng.handle(&link, &data);
                if request.Option()? == GattWriteOption::WriteWithResponse {
                    request.Respond()?;
                }
                Ok(())
            })();
            let _ = deferral.Complete();
            result
        }))?;

        let (eng, map) = (engine.clone(), links.clone());
        status.ReadRequested(&TypedEventHandler::<GattLocalCharacteristic, GattReadRequestedEventArgs>::new(move |_, args| {
            let args = args.ok()?;
            let deferral = args.GetDeferral()?;
            let result = (|| -> Result<()> {
                let link = link_id(&eng, &map, &args.Session()?)?;
                let request = args.GetRequestAsync()?.join()?;
                let writer = DataWriter::new()?;
                writer.WriteByte(eng.status(&link))?;
                request.RespondWithValue(&writer.DetachBuffer()?)?;
                Ok(())
            })();
            let _ = deferral.Complete();
            result
        }))?;

        let adv = GattServiceProviderAdvertisingParameters::new()?;
        adv.SetIsConnectable(true)?;
        adv.SetIsDiscoverable(true)?;
        provider.StartAdvertisingWithParameters(&adv)?;

        Ok(Server { radio, provider, adv, _input: input, _status: status, failed_starts: 0, idle_checks: 0 })
    }

    /// Keeps advertising going. Returns false when the service should be rebuilt.
    fn check(&mut self, engine: &Engine) -> bool {
        if let Some(r) = &self.radio {
            if r.State().ok() != Some(RadioState::On) {
                engine.set_ble("off", "Bluetooth is turned off.");
                // The service doesn't survive the radio going off; rebuild once it's back.
                return false;
            }
        }
        #[cfg(debug_assertions)]
        eprintln!("advertisement status: {:?}", self.provider.AdvertisementStatus().map(|s| s.0));
        match self.provider.AdvertisementStatus() {
            Ok(GattServiceProviderAdvertisementStatus::Started)
            | Ok(GattServiceProviderAdvertisementStatus::StartedWithoutAllAdvertisementData) => {
                self.failed_starts = 0;
                engine.set_ble("ready", "");
                // Windows can report "started" while the advert has gone quiet (seen after its own
                // links to a phone come and go). Restart it now and then while nobody is connected.
                if engine.has_links() {
                    self.idle_checks = 0;
                } else {
                    self.idle_checks += 1;
                    if self.idle_checks >= 30 {
                        self.idle_checks = 0;
                        let _ = self.provider.StopAdvertising();
                        let _ = self.provider.StartAdvertisingWithParameters(&self.adv);
                    }
                }
                true
            }
            _ => {
                self.failed_starts += 1;
                if self.failed_starts > 3 {
                    return false;
                }
                let _ = self.provider.StopAdvertising();
                let _ = self.provider.StartAdvertisingWithParameters(&self.adv);
                engine.set_ble("starting", "Starting Bluetooth…");
                true
            }
        }
    }
}

impl Drop for Server {
    fn drop(&mut self) {
        let _ = self.provider.StopAdvertising();
    }
}
