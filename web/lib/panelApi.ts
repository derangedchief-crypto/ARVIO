import { config } from "./config";

type PanelLine = {
  line_id?: string;
  username?: string;
  bouquets?: number[];
  [key: string]: unknown;
};

type PanelBouquet = {
  id: number;
  name: string;
};

let bouquetCache: { map: Map<number, string>; fetchedAt: number } | null = null;
const BOUQUET_CACHE_TTL_MS = 30 * 60 * 1000; // panel's bouquet catalog rarely changes

function panelHeaders(): Record<string, string> {
  return {
    "X-Api-Key": config.panelApiKey,
    "X-Auth-User": config.panelAuthUser,
    "Content-Type": "application/json"
  };
}

function panelUrl(path: string) {
  return `${config.panelApiBaseUrl.replace(/\/+$/, "")}${path}`;
}

/** Cached id -> name map for every bouquet on the panel (GET /ext/bouquets). */
async function fetchBouquetMap(): Promise<Map<number, string>> {
  if (bouquetCache && Date.now() - bouquetCache.fetchedAt < BOUQUET_CACHE_TTL_MS) {
    return bouquetCache.map;
  }
  const resp = await fetch(panelUrl("/ext/bouquets"), { headers: panelHeaders(), cache: "no-store" });
  if (!resp.ok) throw new Error(`GET /ext/bouquets failed: ${resp.status}`);
  const data = (await resp.json()) as PanelBouquet[];
  const map = new Map(data.map((bouquet) => [bouquet.id, bouquet.name]));
  bouquetCache = { map, fetchedAt: Date.now() };
  return map;
}

/**
 * Resolves a customer's actual bouquet/package label names straight from
 * the billing panel, using the admin "ext" API — GET /ext/lines to find
 * this username's assigned bouquet IDs, GET /ext/bouquets (cached) to turn
 * those IDs into names. Both responses are small and fixed-size regardless
 * of how many channels the panel has, so unlike Xtream's
 * get_live_categories they never truncate. Returns [] (never throws) if
 * the panel isn't configured or the lookup fails, so a panel hiccup can
 * never block sign-in — matching every other entitlement path here.
 */
export async function fetchPanelBouquetLabels(username: string): Promise<string[]> {
  if (!config.panelApiBaseUrl || !config.panelApiKey || !config.panelAuthUser) return [];
  try {
    const linesResp = await fetch(panelUrl(`/ext/lines?username=${encodeURIComponent(username)}`), {
      headers: panelHeaders(),
      cache: "no-store"
    });
    if (!linesResp.ok) {
      console.error(`[PanelApi] GET /ext/lines failed: ${linesResp.status}`);
      return [];
    }
    const lines = (await linesResp.json()) as PanelLine[];
    const line = Array.isArray(lines) ? lines.find((entry) => entry.username === username) ?? lines[0] : null;
    const bouquetIds = Array.isArray(line?.bouquets) ? line.bouquets : [];
    if (!bouquetIds.length) return [];

    const bouquetMap = await fetchBouquetMap();
    return bouquetIds
      .map((id) => bouquetMap.get(id))
      .filter((name): name is string => Boolean(name && name.trim()));
  } catch (error) {
    console.error("[PanelApi] fetchPanelBouquetLabels failed:", error);
    return [];
  }
}
