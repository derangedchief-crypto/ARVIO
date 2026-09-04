import { NextRequest, NextResponse } from "next/server";
import { config } from "@/lib/config";

// Mirrors com.arflix.tv.data.repository.IptvRepository.verifyXtreamLogin +
// collectXtreamPackageLabels on Android: same endpoint, same auth/status
// checks, same "harvest every string value" approach to finding the
// reseller package label, since Xtream/XUI.one panels have no standard
// field name for it.
function collectPackageLabels(value: unknown, out: string[], depth = 0) {
  if (depth > 6 || out.length > 200) return;
  if (typeof value === "string") {
    const trimmed = value.trim();
    if (trimmed) out.push(trimmed);
    return;
  }
  if (Array.isArray(value)) {
    for (const item of value) collectPackageLabels(item, out, depth + 1);
    return;
  }
  if (value && typeof value === "object") {
    for (const [key, item] of Object.entries(value as Record<string, unknown>)) {
      // Matches Android exactly: only the literal "username"/"password" keys
      // are skipped, not a broad pattern — a broader filter risks silently
      // dropping legitimate package-label text.
      const lowerKey = key.toLowerCase();
      if (lowerKey === "username" || lowerKey === "password") continue;
      collectPackageLabels(item, out, depth + 1);
    }
  }
}

export async function POST(request: NextRequest) {
  let body: { username?: string; password?: string };
  try {
    body = await request.json();
  } catch {
    return NextResponse.json({ success: false, message: "Bad request." }, { status: 400 });
  }

  const username = (body.username ?? "").trim();
  const password = (body.password ?? "").trim();
  if (!username || !password) {
    return NextResponse.json({ success: false, message: "Enter your username and password." });
  }

  const base = config.xtreamGateHostUrl.replace(/\/+$/, "");
  const url = `${base}/player_api.php?username=${encodeURIComponent(username)}&password=${encodeURIComponent(password)}`;

  let parsed: unknown;
  try {
    const resp = await fetch(url, { cache: "no-store" });
    if (!resp.ok) {
      return NextResponse.json({
        success: false,
        message: "Couldn't verify your login. Check your username and password, or try again."
      });
    }
    parsed = await resp.json();
  } catch {
    return NextResponse.json({
      success: false,
      message: "Couldn't verify your login. Check your username and password, or try again."
    });
  }

  const userInfo = (parsed as Record<string, unknown> | null)?.user_info as Record<string, unknown> | undefined;
  if (!userInfo) {
    return NextResponse.json({ success: false, message: "Invalid username or password." });
  }

  const auth = typeof userInfo.auth === "number" ? userInfo.auth : Number(userInfo.auth ?? 0);
  if (auth !== 1) {
    return NextResponse.json({ success: false, message: "Invalid username or password." });
  }

  const status = typeof userInfo.status === "string" ? userInfo.status : "";
  if (status && status.toLowerCase() !== "active") {
    return NextResponse.json({ success: false, message: `Account status: ${status}. Contact support.` });
  }

  const labels: string[] = [];
  collectPackageLabels(parsed, labels, 0);

  return NextResponse.json({ success: true, packageLabels: labels.slice(0, 500) });
}
