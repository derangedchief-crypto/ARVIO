// Fixed Extreme TV Network infrastructure. These match the Android app's
// hardcoded hosts exactly — see FIXED_XTREAM_HOST_URL / FIXED_JELLYFIN_SERVER_URL
// in SettingsScreen.kt and the constants of the same shape in
// XtreamGateViewModel.kt / JellyfinGateViewModel.kt. Keep these two values in
// lockstep with the Android app; if the infrastructure ever moves, update both.
export const FIXED_XTREAM_HOST_URL = "https://tv.extremeiptv.net";
export const FIXED_JELLYFIN_SERVER_URL = "http://38.127.60.212:8096";

export const BRAND_NAME = "Extreme TV Network";
