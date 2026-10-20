"use strict";
const $ = id => document.getElementById(id);
$("setup-form").addEventListener("submit", async event => {
  event.preventDefault();
  if ($("owner-password").value !== $("owner-confirm").value || $("second-password").value !== $("second-confirm").value) {
    $("notice").textContent = "The password confirmations do not match."; return;
  }
  if (!confirm("Save both accounts? Existing administrator sessions will be signed out. Customer logins and the maintenance flag will not change.")) return;
  const button = event.target.querySelector("button"); button.disabled = true;
  try {
    const response = await fetch("/api/setup/", {method: "POST", credentials: "same-origin", cache: "no-store",
      headers: {"Content-Type": "application/json", "X-Hub-Manager": "1"},
      body: JSON.stringify({setup_key: $("key").value, owner: $("owner").value, owner_password: $("owner-password").value,
        second: $("second").value, second_password: $("second-password").value}), signal: AbortSignal.timeout(15000)});
    const data = await response.json();
    if (!response.ok) throw new Error(data.detail || "Setup failed");
    event.target.reset(); $("notice").textContent = "Both accounts saved. Return to admin sign in to continue.";
  } catch (error) { $("notice").textContent = error.message || "Connection failed."; }
  finally { button.disabled = false; }
});
