import { config } from "./config";
import type { InstalledAddon } from "./types";

export type EntitlementResolution =
  | { kind: "unresolved"; reason: string }
  | { kind: "resolved"; granted: boolean; matchedLabel: string | null };

function normalize(label: string): string {
  return label.trim().toLowerCase().replace(/\s+/g, " ");
}

/**
 * Mirrors XtreamEntitlementsRepository.resolve() on Android: with no
 * manifest URL configured, deliberately unresolved (never grant/revoke
 * blind). Otherwise checks every package label for the "cloud stream"
 * keyword and grants/revokes based on a single match.
 */
export function resolveCloudStreamEntitlement(labels: string[]): EntitlementResolution {
  if (!config.entitlementCloudStreamManifestUrl) {
    return { kind: "unresolved", reason: "no_entitlements_configured" };
  }

  const normalized = Array.from(
    new Set(
      labels
        .map(normalize)
        .filter((label) => label.length > 0)
    )
  ).slice(0, 500);

  if (normalized.length === 0) {
    return { kind: "unresolved", reason: "no_labels" };
  }

  const matched = normalized.find((label) => label.includes(config.entitlementCloudStreamKeyword)) ?? null;
  return { kind: "resolved", granted: matched !== null, matchedLabel: matched };
}

/**
 * Applies a resolved entitlement: installs the Cloud Stream addon if
 * granted and not already present, removes it if revoked and currently
 * installed. Never throws — a failed grant/revoke should not break sign-in,
 * matching the Android gate's runCatching { applyPackageEntitlements(...) }.
 */
export async function applyCloudStreamEntitlement(
  labels: string[],
  addons: InstalledAddon[],
  installAddon: (url: string) => Promise<void>,
  removeAddon: (addon: InstalledAddon) => Promise<void>
): Promise<void> {
  try {
    const resolution = resolveCloudStreamEntitlement(labels);
    if (resolution.kind === "unresolved") return;

    const manifestUrl = config.entitlementCloudStreamManifestUrl;
    const existing = addons.find((addon) => addon.manifestUrl === manifestUrl);

    if (resolution.granted) {
      if (!existing) await installAddon(manifestUrl);
    } else if (existing) {
      await removeAddon(existing);
    }
  } catch (error) {
    console.error("[Entitlements] apply failed:", error);
  }
}
