const { invoke } = window.__TAURI__.core;
const { listen } = window.__TAURI__.event;

const $ = (id) => document.getElementById(id);
const EASE = "cubic-bezier(0.16, 1, 0.3, 1)";
const calm = matchMedia("(prefers-reduced-motion: reduce)");

const PHONE_ICON =
  '<svg viewBox="0 0 24 24" fill="none" stroke="currentColor" stroke-width="2" stroke-linecap="round"><rect x="6.5" y="2.5" width="11" height="19" rx="2.5"/><path d="M10.5 18h3"/></svg>';

const esc = (s) =>
  String(s).replace(/[&<>"']/g, (c) => ({ "&": "&amp;", "<": "&lt;", ">": "&gt;", '"': "&quot;", "'": "&#39;" })[c]);

function ago(unixSeconds) {
  if (!unixSeconds) return "Not used yet";
  const s = Math.max(0, Date.now() / 1000 - unixSeconds);
  if (s < 90) return "Used just now";
  if (s < 3600) return `Used ${Math.round(s / 60)} min ago`;
  if (s < 86400) return `Used ${Math.round(s / 3600)} h ago`;
  const d = Math.round(s / 86400);
  return d === 1 ? "Used yesterday" : `Used ${d} days ago`;
}

/** Swaps text with a short blur-in, only when it actually changed. */
function swap(el, html) {
  if (el.innerHTML === html) return;
  el.innerHTML = html;
  if (!calm.matches && el.isConnected) {
    el.animate(
      [
        { opacity: 0, filter: "blur(4px)", transform: "translateY(3px)" },
        { opacity: 1, filter: "blur(0)", transform: "none" },
      ],
      { duration: 380, easing: EASE },
    );
  }
}

let state = null;
let askId = null;
const leaving = new Set();

function render(s) {
  state = s;
  const connected = s.phones.filter((p) => p.state === "connected");
  const waiting = s.phones.filter((p) => p.state === "waiting");

  // ---- stage and headline
  let stage, title, sub;
  if (s.ble === "off") {
    stage = "off";
    title = "Bluetooth is off";
    sub = "Turn it on in Windows settings and Deckr picks up again by itself.";
  } else if (s.ble === "unsupported" || s.ble === "error") {
    stage = "problem";
    title = s.ble === "unsupported" ? "This PC can't host Deckr" : "Bluetooth problem";
    sub = esc(s.ble_detail);
  } else if (s.ble === "starting") {
    stage = "starting";
    title = "Starting Bluetooth…";
    sub = "This only takes a moment.";
  } else if (waiting.length) {
    stage = "asking";
    title = `${esc(waiting[0].name)} is asking to connect`;
    sub = "Allow it once and it connects by itself from then on.";
  } else if (connected.length) {
    stage = "connected";
    title = connected.length === 1 ? `${esc(connected[0].name)} is connected` : `${connected.length} phones connected`;
    sub = "Touchpad, keyboard and gamepad input now goes to this PC.";
  } else {
    stage = "waiting";
    title = "Waiting for your phone";
    sub = `Open Deckr on your phone. It finds <b>${esc(s.pc_name)}</b> and connects by itself.`;
  }
  document.body.dataset.stage = stage;
  swap($("title"), title);
  swap($("subtitle"), sub);
  $("pcLabel").textContent = s.pc_name;
  const shown = waiting[0] ?? connected[0];
  $("phoneLabel").textContent = shown ? shown.name : "Your phone";

  // ---- approval request
  const ask = $("ask");
  if (waiting.length) {
    askId = waiting[0].id;
    $("askTitle").textContent =
      waiting.length > 1 ? `${waiting[0].name} and ${waiting.length - 1} more want to connect` : `Let ${waiting[0].name} control this PC?`;
    ask.classList.add("open");
  } else {
    ask.classList.remove("open");
  }
  ask.inert = !waiting.length;

  renderPhones(s.trusted);

  // ---- settings
  $("autostart").checked = s.autostart;
  $("approval").checked = s.require_approval;
  $("smooth").checked = s.smooth_pointer;
  const hint = $("approvalHint");
  hint.textContent = s.require_approval
    ? "Phones you've allowed before connect straight away."
    : "Any nearby phone running Deckr can connect and control this PC.";
  hint.className = s.require_approval ? "" : "warn";

  const seg = document.querySelector(".segmented");
  seg.dataset.value = s.controller;
  seg.querySelectorAll("button").forEach((b) => b.setAttribute("aria-checked", String(b.dataset.controller === s.controller)));

  const driver = $("driver");
  const problem = s.gamepad === "missing" || s.gamepad === "error";
  driver.classList.toggle("open", problem);
  driver.inert = !problem;
  if (problem) {
    $("driverTitle").textContent = s.gamepad === "missing" ? "Gamepad driver needed" : "Gamepad problem";
    $("driverText").textContent =
      s.gamepad === "missing" ? "Gamepad mode needs the free ViGEmBus driver. Mouse and keyboard work without it." : s.gamepad_detail;
    driver.querySelector('[data-action="driver"]').hidden = s.gamepad !== "missing";
  }

  renderLog(s.log);
  $("version").textContent = `v${s.version}`;
}

/** Keyed update so rows animate in and out instead of the whole list redrawing. */
function renderPhones(list) {
  const box = $("phones");
  const rows = new Map([...box.querySelectorAll(".item")].map((el) => [el.dataset.id, el]));
  let fresh = 0;
  list.forEach((t) => {
    if (leaving.has(t.id)) return;
    let item = rows.get(t.id);
    if (!item) {
      item = document.createElement("div");
      item.className = "item enter";
      item.dataset.id = t.id;
      item.style.setProperty("--i", String(fresh++));
      item.innerHTML = `
        <div class="row">
          <span class="device">${PHONE_ICON}</span>
          <span class="text"><b></b><small></small></span>
          <button type="button" class="btn ghost small forget" data-action="forget">Forget</button>
        </div>`;
      item.addEventListener("animationend", () => item.classList.remove("enter"), { once: true });
    }
    item.querySelector("b").textContent = t.name;
    item.querySelector("small").textContent = t.connected
      ? t.rate > 0 ? `Connected · ${t.rate} updates/s` : "Connected"
      : ago(t.last_used);
    item.querySelector(".forget").title = `Remove ${t.name}. It will need to be allowed again.`;
    item.classList.toggle("on", t.connected);
    box.appendChild(item);
    rows.delete(t.id);
  });
  rows.forEach((el, id) => {
    if (!leaving.has(id)) el.remove();
  });
  $("phonesEmpty").hidden = list.length > 0;
  if (!list.length) box.prepend($("phonesEmpty"));
}

function renderLog(lines) {
  const ol = $("log");
  const firstOld = ol.firstElementChild?.dataset.line;
  const firstRender = !ol.dataset.ready;
  ol.dataset.ready = "1";
  const html = lines
    .map((line, i) => {
      const [time, ...rest] = line.split("  ");
      const isNew = !firstRender && firstOld !== undefined && i < lines.indexOf(firstOld);
      return `<li data-line="${esc(line)}"${isNew ? ' class="new"' : ""}><time>${esc(time)}</time>${esc(rest.join("  "))}</li>`;
    })
    .join("");
  if (ol.innerHTML !== html) ol.innerHTML = html;
}

// ---- actions
document.addEventListener("click", (e) => {
  const b = e.target.closest("button");
  if (!b) return;
  switch (b.dataset.action) {
    case "allow":
    case "deny":
      if (askId) invoke("respond", { id: askId, allow: b.dataset.action === "allow" });
      break;
    case "forget": {
      const item = b.closest(".item");
      const id = item.dataset.id;
      leaving.add(id);
      item.classList.add("leaving");
      const done = () => {
        leaving.delete(id);
        item.remove();
        invoke("forget", { id });
      };
      if (calm.matches) done();
      else item.addEventListener("transitionend", (ev) => ev.propertyName === "grid-template-rows" && done(), { once: false });
      setTimeout(() => leaving.has(id) && done(), 500);
      break;
    }
    case "driver":
      invoke("open_driver_page");
      break;
    case "retry-pad":
      invoke("retry_gamepad");
      break;
  }
  if (b.dataset.controller && state && b.dataset.controller !== state.controller) {
    // Move the thumb right away; the backend confirms.
    document.querySelector(".segmented").dataset.value = b.dataset.controller;
    invoke("set_controller", { value: b.dataset.controller });
  }
});

$("autostart").addEventListener("change", (e) => {
  invoke("set_autostart", { value: e.target.checked }).catch(() => state && render(state));
});
$("approval").addEventListener("change", (e) => invoke("set_require_approval", { value: e.target.checked }));
$("smooth").addEventListener("change", (e) => invoke("set_smooth_pointer", { value: e.target.checked }));

// Arrow keys move between the two gamepad types, like native radio buttons.
document.querySelector(".segmented").addEventListener("keydown", (e) => {
  if (!["ArrowLeft", "ArrowRight"].includes(e.key)) return;
  const next = e.key === "ArrowRight" ? "xbox" : "ds4";
  document.querySelector(`[data-controller="${next}"]`).click();
  document.querySelector(`[data-controller="${next}"]`).focus();
});

document.addEventListener("contextmenu", (e) => {
  if (!e.target.closest(".log ol")) e.preventDefault();
});

// Loops stop while the window is hidden in the tray.
document.addEventListener("visibilitychange", () => document.body.classList.toggle("paused", document.hidden));

// Keep "Used 3 min ago" fresh.
setInterval(() => state && renderPhones(state.trusted), 30000);

// ---- custom titlebar
const win = window.__TAURI__.window.getCurrentWindow();
$("minimize").addEventListener("click", () => win.minimize());
$("close").addEventListener("click", () => win.close()); // the app hides to the tray instead of quitting
window.addEventListener("blur", () => document.body.classList.add("inactive"));
window.addEventListener("focus", () => document.body.classList.remove("inactive"));

listen("state", (e) => render(e.payload));
invoke("get_state").then(render);
