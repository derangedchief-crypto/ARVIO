"use client";

import { useState } from "react";
import { ArrowLeft } from "lucide-react";
import { hasSupabaseConfig } from "@/lib/config";
import { useApp } from "@/lib/store";

export function LoginScreen() {
  const { backToProfiles, cloudLoginRequired, signIn } = useApp();
  const cloudConfigured = hasSupabaseConfig();
  const [mode, setMode] = useState<"sign-in" | "sign-up">("sign-in");
  const [email, setEmail] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const submit = async () => {
    if (submitting) return;
    setError(null);
    setSubmitting(true);
    try {
      await signIn(email, password, mode);
    } catch (err) {
      setError(err instanceof Error ? err.message : "Authentication failed.");
    } finally {
      setSubmitting(false);
    }
  };

  return (
    <main className="login-shell">
      {!cloudLoginRequired && (
        <button type="button" className="login-back" onClick={backToProfiles} aria-label="Back"><ArrowLeft size={20} /> Back</button>
      )}
      <div className="login-hero">
        <div className="login-copy">
          <div className="login-brand-lockup">
            <img src="/arvio-icon-192.png" alt="" className="login-brand-logo" />
            <img src="/arvio-wordmark.svg" alt="Extreme TV" className="login-wordmark" />
          </div>
          <p className="login-tag">Cloud sign-in required</p>
          <p className="login-sub">Use your Extreme TV Cloud account to sync profiles, continue watching, Trakt activity, addons, catalogs, and playback settings across devices.</p>
          <div className="login-proof">
            <span>Profiles</span>
            <span>Watch history</span>
            <span>Addons</span>
            <span>Trakt sync</span>
          </div>
        </div>

        <div className="login-card">
          <p className="login-card-title">{mode === "sign-up" ? "Create your account" : "Sign in to continue"}</p>
          {!cloudConfigured && <p className="login-error">Extreme TV Cloud backend env is missing. Add values in web/.env.local.</p>}
          {cloudConfigured && (
            <>
              <input
                type="email"
                placeholder="Email"
                value={email}
                onChange={(event) => setEmail(event.target.value)}
                autoComplete="email"
                className="login-input"
              />
              <input
                type="password"
                placeholder="Password"
                value={password}
                onChange={(event) => setPassword(event.target.value)}
                autoComplete={mode === "sign-up" ? "new-password" : "current-password"}
                className="login-input"
                onKeyDown={(event) => {
                  if (event.key === "Enter") void submit();
                }}
              />
              {error && <p className="login-error">{error}</p>}
              <button type="button" className="primary login-submit" onClick={() => void submit()} disabled={submitting}>
                {submitting ? "Please wait…" : mode === "sign-up" ? "Create Account" : "Sign In"}
              </button>
              <button
                type="button"
                className="login-switch-mode"
                onClick={() => { setMode(mode === "sign-up" ? "sign-in" : "sign-up"); setError(null); }}
              >
                {mode === "sign-up" ? "Already have an account? Sign in" : "New here? Create an account"}
              </button>
            </>
          )}
        </div>
      </div>
    </main>
  );
}
