"use strict";

const { connectLambda, getStore } = require("@netlify/blobs");
const { json, options, parseBody, assertAppRequest, sha256 } = require("./_backend");

/** The Xtream gate blocks on this call, so panel requests fail fast. */
const PANEL_TIMEOUT_MS = 12_000;
/** /ext/packages and /ext/bouquets change rarely; cache to keep the panel quiet. */
const PANEL_CACHE_TTL_MS = 10 * 60 * 1000;
/**
 * This endpoint accepts credentials, so it must not be usable as a password
 * oracle against the panel. Per-username, per-minute ceiling.
 */
const RATE_LIMIT_PER_MINUTE = 12;

/**
 * Package-name keyword -> Stremio addon.
 *
 * Manifest URLs come from environment variables, never from source: the
 * Torrentio URL embeds a Premiumize API key, and anything committed here would
 * also end up decompilable inside the APK.
 */
const ENTITLEMENTS = [
  {
    id: "cloud_stream",
    keyword: "cloud stream enabled",
    displayName: "Cloud Stream",
    manifestEnv: "ENTITLEMENT_CLOUD_STREAM_MANIFEST_URL"
  }
];

function trimTrailingSlash(value) {
  return String(value || "").trim().replace(/\/+$/, "");
}

function panelBaseUrl() {
  return trimTrailingSlash(process.env.XTREAM_PANEL_API_URL);
}

function portalBaseUrl() {
  return trimTrailingSlash(process.env.XTREAM_PORTAL_URL || "https://tv.extremeiptv.net");
}

function manifestUrlFor(entitlement) {
  return String(process.env[entitlement.manifestEnv] || "").trim();
}

/** Every manifest this function knows about, so downgrades can be revoked. */
function knownManifestUrls() {
  return ENTITLEMENTS.map(manifestUrlFor).filter(Boolean);
}

function configError(message) {
  const error = new Error(message);
  error.statusCode = 503;
  return error;
}

function upstreamError(message) {
  const error = new Error(message);
  error.statusCode = 502;
  return error;
}

/**
 * Collapses separators so hand-typed package names still match:
 * "1 Month - 2 Connections - Cloud_Stream Enabled" -> contains
 * "cloud stream enabled".
 */
function normalizeLabel(raw) {
  return String(raw || "")
    .toLowerCase()
    .replace(/[_\-/|+]+/g, " ")
    .replace(/\s+/g, " ")
    .trim();
}

function matchEntitlements(labels) {
  const haystack = labels.map(normalizeLabel).filter(Boolean);
  if (haystack.length === 0) return [];
  const matched = [];
  for (const entitlement of ENTITLEMENTS) {
    const manifestUrl = manifestUrlFor(entitlement);
    if (!manifestUrl) {
      console.warn(
        `xtream-entitlements: ${entitlement.manifestEnv} is not set; skipping ${entitlement.id}`
      );
      continue;
    }
    if (haystack.some((label) => label.includes(entitlement.keyword))) {
      matched.push({
        id: entitlement.id,
        display_name: entitlement.displayName,
        manifest_url: manifestUrl
      });
    }
  }
  return matched;
}

async function fetchJson(url, headers) {
  const response = await fetch(url, {
    headers: { accept: "application/json", ...headers },
    signal: AbortSignal.timeout(PANEL_TIMEOUT_MS)
  });
  const text = await response.text();
  let data = null;
  try {
    data = text ? JSON.parse(text) : null;
  } catch {
    data = null;
  }
  return { ok: response.ok, status: response.status, data };
}

/**
 * The Xtream portal is the authority on whether the caller owns this line.
 * Without this step, anyone with the app's anon key could enumerate usernames
 * and learn which packages they hold.
 */
async function verifyPortalLogin(username, password) {
  const url =
    `${portalBaseUrl()}/player_api.php` +
    `?username=${encodeURIComponent(username)}&password=${encodeURIComponent(password)}`;
  const result = await fetchJson(url, {});
  const userInfo = result.data && result.data.user_info;
  if (!result.ok || !userInfo || Number(userInfo.auth || 0) !== 1) {
    return { ok: false, reason: "invalid_credentials", status: null };
  }
  const status = String(userInfo.status || "").trim();
  if (status && status.toLowerCase() !== "active") {
    return { ok: false, reason: "inactive_account", status };
  }
  return { ok: true, status: status || "Active" };
}

function panelHeaders() {
  const apiKey = String(process.env.XTREAM_PANEL_API_KEY || "").trim();
  const authUser = String(process.env.XTREAM_PANEL_AUTH_USER || "").trim();
  if (!apiKey || !authUser) {
    throw configError("panel_api_not_configured");
  }
  return { "X-Api-Key": apiKey, "X-Auth-User": authUser };
}

function pickLine(lines, username) {
  return (
    lines.find((line) => String((line && line.username) || "") === username) || null
  );
}

/**
 * Resolves the line record for a username.
 *
 * Tries /ext/lines first (v1.1.22+) and falls back to the paginated
 * /ext/lines/index (v2.0.10+). Both need the `indexLines` permission on the
 * token; /ext/line/find is deliberately not used because it returns only a
 * line_id and no package information.
 */
async function loadLine(username) {
  const base = panelBaseUrl();
  if (!base) throw configError("panel_api_not_configured");
  const headers = panelHeaders();
  const query = `username=${encodeURIComponent(username)}`;

  const direct = await fetchJson(`${base}/ext/lines?${query}`, headers);
  if (direct.ok && Array.isArray(direct.data)) {
    return pickLine(direct.data, username);
  }

  const paged = await fetchJson(`${base}/ext/lines/index?${query}&per_page=50`, headers);
  const payload = paged.data && paged.data.data;
  const items = Array.isArray(payload && payload.items)
    ? payload.items
    : Array.isArray(payload)
      ? payload.flatMap((entry) => (entry && Array.isArray(entry.items) ? entry.items : []))
      : null;
  if (paged.ok && Array.isArray(items)) {
    return pickLine(items, username);
  }

  throw upstreamError(`panel_line_lookup_failed (${direct.status}/${paged.status})`);
}

function cacheStore(event) {
  connectLambda(event);
  return getStore("xtream-panel-cache");
}

/**
 * Cached GET for the panel's slow-changing reference lists.
 *
 * On a panel hiccup a stale cache is preferred over an error: a missing
 * package name would look like "not granted" and uninstall a paid addon.
 */
async function cachedPanelList(event, cacheKey, path) {
  const store = cacheStore(event);
  const cached = await store.get(cacheKey, { type: "json" }).catch(() => null);
  const cachedItems = cached && Array.isArray(cached.items) ? cached.items : null;
  const fetchedAt = Date.parse((cached && cached.fetchedAt) || "");
  if (cachedItems && Number.isFinite(fetchedAt) && Date.now() - fetchedAt < PANEL_CACHE_TTL_MS) {
    return cachedItems;
  }

  const result = await fetchJson(`${panelBaseUrl()}${path}`, panelHeaders());
  if (!result.ok || !Array.isArray(result.data)) {
    if (cachedItems) return cachedItems;
    throw upstreamError(`panel_list_failed ${path} (${result.status})`);
  }
  await store
    .setJSON(cacheKey, { fetchedAt: new Date().toISOString(), items: result.data })
    .catch(() => {});
  return result.data;
}

/**
 * Compare-and-swap rate limiter, same shape as the Simkl proxy's limiter in
 * _backend.js. Keyed on the username so one abusive client cannot lock out
 * every other user.
 */
async function consumeRateLimit(event, username) {
  if (process.env.IS_LOCAL_DEV === "true") return { exceeded: false, resetSeconds: 60 };
  connectLambda(event);
  const store = getStore("xtream-entitlements-rate-limits");
  const key = `user/${sha256(`xtream:${username.toLowerCase()}`)}.json`;
  const now = Date.now();

  for (let attempt = 0; attempt < 5; attempt += 1) {
    const existing = await store.getWithMetadata(key, { type: "json" }).catch(() => null);
    const previousStart = Date.parse((existing && existing.data && existing.data.windowStartedAt) || "");
    const sameWindow = Number.isFinite(previousStart) && now - previousStart < 60_000;
    const windowStartedAt = sameWindow ? previousStart : now;
    const previousCount = sameWindow
      ? Math.max(0, Number((existing && existing.data && existing.data.requestCount) || 0))
      : 0;
    const requestCount = previousCount + 1;

    const write = await store.setJSON(
      key,
      { windowStartedAt: new Date(windowStartedAt).toISOString(), requestCount },
      existing && existing.etag ? { onlyIfMatch: existing.etag } : { onlyIfNew: true }
    );
    if (!write.modified) continue;

    return {
      exceeded: requestCount > RATE_LIMIT_PER_MINUTE,
      resetSeconds: Math.max(1, Math.ceil((windowStartedAt + 60_000 - now) / 1_000))
    };
  }
  return { exceeded: true, resetSeconds: 5 };
}

exports.handler = async (event) => {
  const cors = options(event);
  if (cors) return cors;
  if (event.httpMethod !== "POST") {
    return json(405, { error: "method_not_allowed" });
  }

  try {
    assertAppRequest(event);
    const body = parseBody(event);
    const username = String(body.username || "").trim();
    const password = String(body.password || "").trim();
    if (!username || !password) return json(400, { error: "missing_credentials" });
    if (username.length > 255 || password.length > 255) {
      return json(400, { error: "invalid_credentials" });
    }

    const rate = await consumeRateLimit(event, username);
    if (rate.exceeded) {
      const limited = json(429, { error: "rate_limited" });
      limited.headers = { ...limited.headers, "retry-after": String(rate.resetSeconds) };
      return limited;
    }

    const portal = await verifyPortalLogin(username, password);
    if (!portal.ok) {
      return json(401, { error: portal.reason, status: portal.status });
    }

    const line = await loadLine(username);
    if (!line) {
      // Valid on the portal, but this reseller token cannot see the line (wrong
      // owner, or missing indexLines permission). `resolved: false` and no
      // `revoked` list: the app must leave installed addons untouched rather
      // than treat an unknown as a downgrade.
      return json(200, { ok: true, resolved: false, reason: "line_not_visible", granted: [] });
    }

    const [packages, bouquets] = await Promise.all([
      cachedPanelList(event, "packages.json", "/ext/packages"),
      cachedPanelList(event, "bouquets.json", "/ext/bouquets")
    ]);

    const pkg =
      packages.find((entry) => Number((entry && entry.id) ?? NaN) === Number(line.package_id)) ||
      null;

    const bouquetNameById = new Map(
      bouquets.map((entry) => [Number((entry && entry.id) ?? NaN), String((entry && entry.name) || "")])
    );
    const lineBouquetNames = Array.isArray(line.bouquets)
      ? line.bouquets.map((id) => bouquetNameById.get(Number(id)) || "").filter(Boolean)
      : [];

    // expire_at === null means unlimited, so only a parseable past date expires.
    const expiresAtMs = line.expire_at ? Date.parse(line.expire_at) : NaN;
    const expired = Number.isFinite(expiresAtMs) && expiresAtMs <= Date.now();
    const active = line.is_enabled !== false && !expired;

    const labels = [
      pkg && pkg.name,
      pkg && pkg.description,
      line.reseller_notes,
      ...lineBouquetNames
    ].filter(Boolean);

    const granted = active ? matchEntitlements(labels) : [];
    const grantedUrls = granted.map((entry) => entry.manifest_url);
    const revoked = knownManifestUrls().filter((url) => !grantedUrls.includes(url));

    return json(200, {
      ok: true,
      resolved: true,
      granted,
      revoked,
      package_name: (pkg && pkg.name) || null,
      expires_at: line.expire_at || null,
      is_enabled: line.is_enabled !== false,
      max_connections: Number(line.max_connections || 0) || null
    });
  } catch (error) {
    console.error("xtream-entitlements failed", error);
    const status = (error && error.statusCode) || 502;
    return json(status, { error: (error && error.message) || "entitlement_lookup_failed" });
  }
};
