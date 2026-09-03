"use client";

import { useState } from "react";
import { useApp } from "@/lib/store";

export function XtreamGateScreen() {
  const { completeXtreamGate } = useApp();
  const [username, setUsername] = useState("");
  const [password, setPassword] = useState("");
  const [error, setError] = useState<string | null>(null);
  const [progressText, setProgressText] = useState<string | null>(null);
  const [submitting, setSubmitting] = useState(false);

  const submit = async () => {
    if (submitting) return;
    const trimmedUser = username.trim();
    if (!trimmedUser || !password) {
      setError("Enter your username and password.");
      return;
    }
    setError(null);
    setSubmitting(true);
    setProgressText("Signing in…");
    try {
      const resp = await fetch("/api/xtream-login", {
        method: "POST",
        headers: { "Content-Type": "application/json" },
        body: JSON.stringify({ username: trimmedUser, password })
      });
      const result = await resp.json();

      if (!result.success) {
        setError(result.message ?? "Invalid username or password.");
        setSubmitting(false);
        setProgressText(null);
        return;
      }

      setProgressText("Setting up your account…");
      await completeXtreamGate(trimmedUser, password, result.packageLabels ?? []);
      // completeXtreamGate advances the view on success; nothing further to do.
    } catch (err) {
      setError(err instanceof Error ? `Something went wrong: ${err.message}` : "Something went wrong. Try again.");
      setSubmitting(false);
      setProgressText(null);
    }
  };

  return (
    <main className="login-shell">
      <div className="login-hero">
        <div className="login-copy">
          <div className="login-brand-lockup">
            <img src="/arvio-icon-192.png" alt="" className="login-brand-logo" />
            <img src="/arvio-wordmark.svg" alt="Extreme TV" className="login-wordmark" />
          </div>
          <p className="login-tag">Sign in with your subscription</p>
          <p className="login-sub">Enter the username and password from your Extreme TV Network subscription to get started.</p>
        </div>

        <div className="login-card">
          <p className="login-card-title">Sign in</p>
          <input
            type="text"
            placeholder="Username"
            value={username}
            onChange={(event) => setUsername(event.target.value)}
            autoComplete="username"
            autoCapitalize="off"
            autoCorrect="off"
            spellCheck={false}
            className="login-input"
            disabled={submitting}
          />
          <input
            type="password"
            placeholder="Password"
            value={password}
            onChange={(event) => setPassword(event.target.value)}
            autoComplete="current-password"
            className="login-input"
            disabled={submitting}
            onKeyDown={(event) => {
              if (event.key === "Enter") void submit();
            }}
          />
          {error && <p className="login-error">{error}</p>}
          <button type="button" className="primary login-submit" onClick={() => void submit()} disabled={submitting}>
            {submitting ? (progressText ?? "Please wait…") : "Sign In"}
          </button>
        </div>
      </div>
    </main>
  );
}
