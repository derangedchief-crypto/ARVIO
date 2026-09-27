"use client";

import { useState } from "react";
import { useApp } from "@/lib/store";
import { loadStored, saveStored } from "@/lib/storage";
import { normalizeIptvInput } from "@/lib/iptv";
import { testHomeServerConnection } from "@/lib/homeserver";
import { BRAND_NAME, FIXED_JELLYFIN_SERVER_URL, FIXED_XTREAM_HOST_URL } from "@/lib/fixedSources";
import type { IptvPlaylistEntry } from "@/lib/types";

// Profile-scoped "have we shown this" flag. Mirrors the Android app's
// first-launch-only XtreamGateScreen / JellyfinGateScreen: shown once per
// profile, skippable, never forced again after the profile has been through
// it (whether they added credentials or skipped both steps).
const GATE_KEY_PREFIX = "arvio.web.sourceGate.v1:";

export function isSourceGateComplete(profileId: string): boolean {
  if (!profileId) return true;
  return loadStored<boolean>(`${GATE_KEY_PREFIX}${profileId}`, false);
}

function markSourceGateComplete(profileId: string) {
  if (!profileId) return;
  saveStored(`${GATE_KEY_PREFIX}${profileId}`, true);
}

type Step = "xtream" | "jellyfin";

export function SourceGateScreen({ onDone }: { onDone: () => void }) {
  const { activeProfile, settings, updateSettings, refreshIptv, setToast } = useApp();
  const [step, setStep] = useState<Step>("xtream");

  const [xUser, setXUser] = useState("");
  const [xPass, setXPass] = useState("");
  const [xBusy, setXBusy] = useState(false);

  const [jUser, setJUser] = useState("");
  const [jPass, setJPass] = useState("");
  const [jBusy, setJBusy] = useState(false);

  const finish = () => {
    if (activeProfile?.id) markSourceGateComplete(activeProfile.id);
    onDone();
  };

  const skip = () => {
    if (step === "xtream") setStep("jellyfin");
    else finish();
  };

  const submitXtream = async () => {
    if (!xUser.trim() || !xPass.trim()) {
      setToast("Enter your Xtream username and password, or skip for now.");
      return;
    }
    setXBusy(true);
    try {
      const m3uUrl = normalizeIptvInput(`${FIXED_XTREAM_HOST_URL} ${xUser.trim()} ${xPass.trim()}`);
      const existing = settings.iptvPlaylists ?? [];
      const entry: IptvPlaylistEntry = {
        id: crypto.randomUUID(),
        name: "Extreme TV",
        m3uUrl,
        epgUrl: "",
        enabled: true
      };
      updateSettings({ iptvPlaylists: [entry, ...existing] });
      void refreshIptv();
      setToast("Xtream account connected.");
      setStep("jellyfin");
    } finally {
      setXBusy(false);
    }
  };

  const submitJellyfin = async () => {
    if (!jUser.trim()) {
      setToast("Enter your Jellyfin username, or skip for now.");
      return;
    }
    setJBusy(true);
    try {
      const draft = {
        id: crypto.randomUUID(),
        type: "jellyfin" as const,
        name: "Jellyfin",
        url: FIXED_JELLYFIN_SERVER_URL,
        username: jUser.trim(),
        password: jPass || undefined,
        enabled: true
      };
      const result = await testHomeServerConnection(draft);
      if (!result.ok || !result.connection) {
        setToast(`Could not connect: ${result.error || "Connection failed"}`);
        return;
      }
      const existing = settings.homeServers ?? [];
      updateSettings({ homeServers: [result.connection, ...existing] });
      setToast(`Connected to ${result.serverName || "Jellyfin"}.`);
      finish();
    } finally {
      setJBusy(false);
    }
  };

  return (
    <div className="modal-scrim source-gate-scrim">
      <section className="source-gate-dialog" role="dialog" aria-modal="true" aria-labelledby="source-gate-title">
        <p className="eyebrow">{BRAND_NAME}</p>

        {step === "xtream" ? (
          <>
            <h2 id="source-gate-title">Sign in with your Xtream account, or skip for now</h2>
            <p className="source-gate-server">Server: {FIXED_XTREAM_HOST_URL}</p>
            <div className="source-gate-form">
              <input
                value={xUser}
                onChange={(e) => setXUser(e.target.value)}
                placeholder="Username"
                autoComplete="username"
              />
              <input
                value={xPass}
                onChange={(e) => setXPass(e.target.value)}
                placeholder="Password"
                type="password"
                autoComplete="current-password"
              />
            </div>
            <div className="source-gate-actions">
              <button type="button" className="secondary" onClick={skip} disabled={xBusy}>
                Skip
              </button>
              <button type="button" className="primary" onClick={() => void submitXtream()} disabled={xBusy}>
                {xBusy ? "Connecting…" : "Continue"}
              </button>
            </div>
          </>
        ) : (
          <>
            <h2 id="source-gate-title">Sign in with your Jellyfin account, or skip for now</h2>
            <p className="source-gate-server">Server: {FIXED_JELLYFIN_SERVER_URL}</p>
            <div className="source-gate-form">
              <input
                value={jUser}
                onChange={(e) => setJUser(e.target.value)}
                placeholder="Username"
                autoComplete="username"
              />
              <input
                value={jPass}
                onChange={(e) => setJPass(e.target.value)}
                placeholder="Password (optional)"
                type="password"
                autoComplete="current-password"
              />
            </div>
            <div className="source-gate-actions">
              <button type="button" className="secondary" onClick={skip} disabled={jBusy}>
                Skip
              </button>
              <button type="button" className="primary" onClick={() => void submitJellyfin()} disabled={jBusy}>
                {jBusy ? "Connecting…" : "Finish"}
              </button>
            </div>
          </>
        )}
      </section>
    </div>
  );
}
