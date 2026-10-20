"use strict";
const $ = id => document.getElementById(id);
let revision = null;
let busy = false;
async function api(path, body) {
  const response = await fetch(path, {
    method: body === undefined ? "GET" : "POST", credentials: "same-origin", cache: "no-store",
    headers: body === undefined ? {} : {"Content-Type": "application/json", "X-Hub-Manager": "1"},
    body: body === undefined ? undefined : JSON.stringify(body), signal: AbortSignal.timeout(10000)
  });
  const data = await response.json();
  if (!response.ok) {
    if (response.status === 401) { $("login").hidden = false; $("controls").hidden = true; revision = null; }
    throw new Error(typeof data.detail === "string" ? data.detail : "Request failed. Reload status before retrying.");
  }
  return data;
}
function show(state) {
  revision = state.revision;
  $("login").hidden = true; $("controls").hidden = false;
  $("status").textContent = state.active ? "Maintenance is ON" : "App is OPEN";
  $("updated").textContent = state.updated_at ? `Last changed by ${state.updated_by} · ${new Date(state.updated_at * 1000).toLocaleString()}` : "No maintenance changes yet.";
  $("message").value = state.message;
  $("enable").textContent = state.active ? "Update maintenance message…" : "Enable maintenance…";
  $("disable").hidden = !state.active;
}
async function action(work) {
  if (busy) return;
  busy = true; document.querySelectorAll("button").forEach(b => b.disabled = true);
  try { await work(); } catch (e) { $("notice").textContent = e.message || "Connection failed; reload status."; }
  finally { busy = false; document.querySelectorAll("button").forEach(b => b.disabled = false); }
}
$("login-form").addEventListener("submit", event => {
  event.preventDefault();
  action(async () => {
    try { await api("/api/admin/login/", {username: $("username").value.trim(), password: $("password").value}); }
    finally { $("password").value = ""; }
    show(await api("/api/admin/status/")); $("notice").textContent = "";
  });
});
async function change(active) {
  if (revision === null) return;
  if (!confirm(active ? "Enable/update maintenance? This stops customer playback and blocks the app." : "End maintenance and reopen all connected updated apps?")) return;
  await action(async () => {
    show(await api("/api/admin/maintenance/", {active, message: $("message").value, revision}));
    $("notice").textContent = active ? "Maintenance saved. Connected updated apps will block on their next check." : "Maintenance ended. Connected apps will reopen on their next check.";
  });
}
$("enable").addEventListener("click", () => change(true));
$("disable").addEventListener("click", () => change(false));
$("reload").addEventListener("click", () => action(async () => { show(await api("/api/admin/status/")); $("notice").textContent = ""; }));
$("logout").addEventListener("click", () => action(async () => {
  await api("/api/admin/logout/", {}); revision = null; $("controls").hidden = true; $("login").hidden = false; $("notice").textContent = "Signed out.";
}));
action(async () => { try { show(await api("/api/admin/status/")); } catch (e) { if (!e.message.includes("Sign in required")) $("notice").textContent = e.message; } });
