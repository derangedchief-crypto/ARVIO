"use client";

import { useEffect, useState } from "react";
import { useApp } from "@/lib/store";
import { AddonsScreen } from "@/components/addons/AddonsScreen";
import { DetailsDrawer } from "@/components/details/DetailsDrawer";
import { HomeScreen } from "@/components/home/HomeScreen";
import { LiveTvScreen } from "@/components/livetv/LiveTvScreen";
import { LoginScreen } from "@/components/login/LoginScreen";
import { PlayerOverlay } from "@/components/player/PlayerOverlay";
import { ProfileSelectionScreen } from "@/components/profile/ProfileSelectionScreen";
import { SearchScreen } from "@/components/search/SearchScreen";
import { SettingsScreen } from "@/components/settings/SettingsScreen";
import { WatchlistScreen } from "@/components/watchlist/WatchlistScreen";
import { BackHandler } from "./BackHandler";
import { ExternalPlaybackPrompt } from "./ExternalPlaybackPrompt";
import { MediaContextMenu } from "./MediaContextMenu";
import { NoAddonsPrompt } from "./NoAddonsPrompt";
import { EntitlementGate } from "./Paywall";
import { isSourceGateComplete, SourceGateScreen } from "./SourceGateScreen";
import { Toast } from "./Toast";
import { TopNav } from "./TopNav";

const ACCENTS: Record<string, string> = {
  arctic: "#ededed",
  gold: "#ffcd3c",
  green: "#00d588",
  blue: "#3b82f6",
  purple: "#8b5cf6"
};

export function AppShell() {
  const { view, section, settings, selected, activeStream, activeProfile } = useApp();
  const [mounted, setMounted] = useState(false);
  const [gateOpen, setGateOpen] = useState(false);

  useEffect(() => {
    setMounted(true);
  }, []);

  // First-run Xtream/Jellyfin gate, mirroring the Android app's onboarding.
  // Shown once per profile; skipping or completing either step marks it done.
  // Returning cloud accounts that already synced a home server / playlist
  // from the Android app skip this automatically — nothing to add.
  useEffect(() => {
    if (view !== "app" || !activeProfile?.id) {
      setGateOpen(false);
      return;
    }
    const alreadyHasSources = (settings.homeServers?.length ?? 0) > 0 || (settings.iptvPlaylists?.length ?? 0) > 0;
    if (alreadyHasSources) {
      setGateOpen(false);
      return;
    }
    setGateOpen(!isSourceGateComplete(activeProfile.id));
  }, [view, activeProfile?.id, settings.homeServers, settings.iptvPlaylists]);

  // D-pad / keyboard spatial navigation for TV browsers (Tizen, webOS) and
  // desktop keyboards: arrows move focus, Enter activates, TV Back → Escape.
  // The gamepad layer (HTPC mode: Xbox Ally, Steam Deck, any pad) feeds the
  // same engine, so a controller drives the whole app like Plex HTPC.
  useEffect(() => {
    void import("@/lib/tvNav").then((mod) => mod.installTvNav()).catch(() => undefined);
    void import("@/lib/gamepadNav").then((mod) => mod.installGamepadNav()).catch(() => undefined);
  }, []);

  // Warm the lazy player libraries during idle time so the first Play press
  // doesn't pay their download cost. They stay out of the initial bundle.
  useEffect(() => {
    const warm = () => {
      void import("hls.js").catch(() => undefined);
      void import("mediabunny").catch(() => undefined);
    };
    if (typeof window.requestIdleCallback === "function") {
      const id = window.requestIdleCallback(warm, { timeout: 8000 });
      return () => window.cancelIdleCallback(id);
    }
    const timer = window.setTimeout(warm, 4000);
    return () => window.clearTimeout(timer);
  }, []);

  useEffect(() => {
    document.documentElement.style.scrollBehavior = settings.smoothScrolling ? "smooth" : "auto";
  }, [settings.smoothScrolling]);

  if (!mounted) {
    return (
      <main className="app-boot">
        <img src="/arvio-logo.svg" alt="" className="app-boot-logo" />
        <img src="/arvio-wordmark.svg" alt="ARVIO" className="app-boot-wordmark" />
      </main>
    );
  }

  if (view === "login") return <LoginScreen />;
  if (view === "profiles") return <ProfileSelectionScreen />;

  const accent = ACCENTS[settings.accentColor] ?? ACCENTS.arctic;

  // Between profile selection and the app: web membership gate. Off unless
  // NEXT_PUBLIC_PAYWALL_ENABLED=true, so nothing changes for users until you
  // flip it. The APK never mounts this.
  return (
    <EntitlementGate>
    <main
      className={`app-shell ${settings.oledBlack ? "oled" : ""} ${settings.spoilerBlur ? "spoiler-blur" : ""}`}
      style={{ ["--accent" as string]: accent }}
    >
      {!activeStream && <TopNav />}

      <section className="content">
        {selected ? (
          <DetailsDrawer />
        ) : (
          <>
            {section === "home" && <HomeScreen />}
            {section === "search" && <SearchScreen />}
            {section === "watchlist" && <WatchlistScreen />}
            {section === "tv" && <LiveTvScreen />}
            {section === "addons" && <AddonsScreen />}
            {section === "settings" && <SettingsScreen />}
          </>
        )}
      </section>

      <PlayerOverlay />
      <ExternalPlaybackPrompt />
      <MediaContextMenu />
      {gateOpen ? <SourceGateScreen onDone={() => setGateOpen(false)} /> : <NoAddonsPrompt />}
      <BackHandler />
      <Toast />
    </main>
    </EntitlementGate>
  );
}
