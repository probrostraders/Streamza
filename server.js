/**
 * Streamza Relay — "claim a slot, upload a video -> stream to RTMP" (Node + FFmpeg `-c copy`).
 *
 * Model: SLOT_COUNT concurrent slots. Tapping a free slot claims the full 24h (SLOT_MS), multistream
 * included — see the BETA note on tierOf() below for why. Each claim returns a private manage-token
 * (stored in the browser) to monitor/stop that slot. The expiry watchdog stops slots when they run out.
 *
 * Payment: Web Studio itself is free — tierOf() grants every claim full access, no checkout on the
 * website at all. The only paid product is the Streamza Loop Android app's Google Play subscription
 * (see googlePlay.js, /billing/*), which writes into the same `subscribers` map tierOf() reads — see
 * the note on tierOf() below for how to switch Web Studio back to reading it for real.
 */
const express = require("express");
const compression = require("compression");
const multer = require("multer");
const { spawn } = require("child_process");
const crypto = require("crypto");
const fs = require("fs");
const path = require("path");
const r2 = require("./r2"); // Cloudflare R2 storage — Streamza Loop's chunked uploads, and Web Studio's local uploads promoted to it opportunistically while under the free-tier budget (see R2_MAX_TOTAL_BYTES)
const googlePlay = require("./googlePlay"); // Google Play Developer API — verifies Streamza Loop's subscription purchases

const PORT = process.env.PORT || 3000;
const ADMIN_KEY = process.env.ADMIN_KEY || "streamza-admin";
const SLOT_COUNT = Number(process.env.SLOT_COUNT) || 10; // max safe on the free 1GB micro (-c copy is light; RAM + outbound bandwidth are the limit). Raise via env on a bigger instance.
const SLOT_MS = 24 * 60 * 60 * 1000;  // every claim streams for this long, then the slot frees up
// Streaming pipeline. By default we RE-ENCODE to a YouTube-Live-friendly stream (a keyframe every 2s) so
// ANY uploaded MP4 goes live cleanly — uploaded files usually have ~5-10s keyframes, which makes YouTube
// show "not receiving enough video / Preparing". Re-encoding costs CPU; set RELAY_COPY=1 to stream-copy
// instead (lightest, but the file MUST already have ~2s keyframes). RELAY_MAXH caps height on small VMs.
const RELAY_COPY = process.env.RELAY_COPY === "1";
const RELAY_PRESET = process.env.RELAY_PRESET || "veryfast";    // x264 speed/CPU trade-off
const RELAY_MAXH = Number(process.env.RELAY_MAXH) || 0;         // 0 = keep source height; e.g. 720 on a micro VM
const RELAY_VBITRATE = process.env.RELAY_VBITRATE || "3500k";   // target video bitrate when re-encoding
// Screen-recorded source files (OBS/Chrome capture etc.) are usually variable frame rate — a container
// that says 60fps but only actually delivers ~30fps of real frames. Piped straight into an RTMP output
// that irregular timing makes YouTube/Twitch sit on "Preparing..." forever instead of going live, since
// the incoming feed never looks like a steady real-time signal. Forcing a constant output frame rate
// fixes it regardless of what the source file does.
const RELAY_FPS = Number(process.env.RELAY_FPS) || 30;
// FFmpeg can die mid-stream on a transient RTMP hiccup (destination reconnect, network blip) even with
// -stream_loop -1 looping the input — that's a process crash, not the loop ending. Auto-reconnect instead
// of ending the slot, as long as it wasn't a manual stop and time remains on the slot.
const FFMPEG_MAX_RESTARTS = Number(process.env.FFMPEG_MAX_RESTARTS) || 20;      // auto-reconnects before giving up
const FFMPEG_RESTART_DELAY_MS = Number(process.env.FFMPEG_RESTART_DELAY_MS) || 3000; // backoff between reconnects
const MULTISTREAM_MAX = Number(process.env.MULTISTREAM_MAX) || 3;    // max simultaneous platforms on the multistream plan
// --- Streamza Loop's Play Billing product IDs — must match exactly what's created in Play Console.
// The actual price and any free-trial phase are configured there too, not here; this app only ever
// references the product IDs and displays whatever Play Billing reports back for them. This is the
// only subscription system in the codebase — Web Studio itself is free with no paid checkout; the
// Android app is where a subscription is actually bought (see googlePlay.js, /billing/*).
const PLAY_PRODUCT_SINGLE = process.env.PLAY_PRODUCT_SINGLE || "streamza_loop_single";
const PLAY_PRODUCT_MULTI = process.env.PLAY_PRODUCT_MULTI || "streamza_loop_multi";
// --- Google Sign-In (optional accounts) — set GOOGLE_CLIENT_ID to enable ---
const GOOGLE_CLIENT_ID = process.env.GOOGLE_CLIENT_ID || ""; // OAuth Web client id (…apps.googleusercontent.com)
const SESSION_SECRET = process.env.SESSION_SECRET || (ADMIN_KEY + "-sz-session"); // signs the login cookie
const AUTH_ON = !!GOOGLE_CLIENT_ID;

const UPLOAD_DIR = path.join(__dirname, "uploads");
const DATA_DIR = path.join(__dirname, "data");
const LEADS_FILE = path.join(DATA_DIR, "leads.csv");
fs.mkdirSync(UPLOAD_DIR, { recursive: true });
fs.mkdirSync(DATA_DIR, { recursive: true });
if (!fs.existsSync(LEADS_FILE)) fs.writeFileSync(LEADS_FILE, "email,source,timestamp\n");
// On boot, KEEP saved (reusable) uploads; delete only orphan files not in the saved library.
try {
  const savedIds = new Set();
  try { (JSON.parse(fs.readFileSync(path.join(DATA_DIR, "library.json"), "utf8")) || []).forEach((u) => savedIds.add(u.id)); } catch (_) {}
  for (const f of fs.readdirSync(UPLOAD_DIR)) if (!savedIds.has(f)) fs.unlinkSync(path.join(UPLOAD_DIR, f));
} catch (_) {}

// active subscribers (emails) — updated by the Paddle webhook, persisted to disk
const SUBS_FILE = path.join(DATA_DIR, "subscribers.json");
let subscribers = new Map(); // email -> "single" | "multi"
try {
  const raw = JSON.parse(fs.readFileSync(SUBS_FILE, "utf8"));
  if (Array.isArray(raw)) raw.forEach((e) => subscribers.set(e, "single")); // legacy [emails] format
  else for (const [e, t] of Object.entries(raw)) subscribers.set(e, t);
} catch (_) {}
function saveSubs() { try { fs.writeFileSync(SUBS_FILE, JSON.stringify(Object.fromEntries(subscribers))); } catch (_) {} }
// BETA — Web Studio is free for everyone, no checkout on the website at all. `subscribers` is written
// to only by the Play Billing verification flow (/billing/verify-purchase, see googlePlay.js) for the
// Streamza Loop Android app; flipping Web Studio back to gating on it for real is just reverting this
// one function to `return subscribers.get((email||"").trim().toLowerCase()) || null;`.
function tierOf(_email) { return "multi"; }
function isSubscribed(email) { return !!tierOf(email); }            // any plan → full 24h
function canMultistream(email) { return tierOf(email) === "multi"; } // only the multi tier

// Customer-portal links per email (captured by the Play Billing verify flow — see
// /billing/verify-purchase) so a signed-in user can manage/cancel their subscription from Google Play.
const PORTAL_FILE = path.join(DATA_DIR, "portals.json");
let portals = new Map();
try { const raw = JSON.parse(fs.readFileSync(PORTAL_FILE, "utf8")) || {}; for (const [e, u] of Object.entries(raw)) portals.set(e, u); } catch (_) {}
function savePortals() { try { fs.writeFileSync(PORTAL_FILE, JSON.stringify(Object.fromEntries(portals))); } catch (_) {} }
function portalFor(email) {
  return portals.get((email || "").trim().toLowerCase()) || "";
}
// The signed-in user's currently active slot — lets them resume control of their stream from any device.
function mySlot(email) {
  const e = (email || "").trim().toLowerCase();
  const s = slots.find((x) => x.busy && (x.email || "").toLowerCase() === e);
  if (!s) return null;
  return { id: s.id, token: s.token, live: !!s.proc, file: s.file,
           secondsLeft: Math.max(0, Math.floor((s.expiresAt - Date.now()) / 1000)) };
}

// ---- sessions (HMAC-signed cookie) for Google accounts ----
function signSession(email) {
  const payload = Buffer.from(`${email}|${Date.now() + 30 * 86400000}`).toString("base64url"); // 30-day session
  const mac = crypto.createHmac("sha256", SESSION_SECRET).update(payload).digest("base64url");
  return `${payload}.${mac}`;
}
function readSession(req) {
  try {
    const m = (req.headers.cookie || "").match(/(?:^|;\s*)sz_session=([^;]+)/);
    if (!m) return null;
    const [payload, mac] = decodeURIComponent(m[1]).split(".");
    if (!payload || !mac) return null;
    const good = crypto.createHmac("sha256", SESSION_SECRET).update(payload).digest("base64url");
    if (mac.length !== good.length || !crypto.timingSafeEqual(Buffer.from(mac), Buffer.from(good))) return null;
    const [email, exp] = Buffer.from(payload, "base64url").toString().split("|");
    if (!email || Number(exp) < Date.now()) return null;
    return email.toLowerCase();
  } catch (_) { return null; }
}
function sessionCookie(req, val, maxAge) {
  const secure = req.protocol === "https" ? "; Secure" : ""; // omit Secure on http://localhost so it still works
  return `sz_session=${val}; Path=/; HttpOnly; SameSite=Lax; Max-Age=${maxAge != null ? maxAge : 30 * 86400}${secure}`;
}

const upload = multer({ dest: UPLOAD_DIR, limits: { fileSize: 1.5 * 1024 * 1024 * 1024 } }); // 1.5 GB cap — the micro's disk is small
const app = express();
app.set("trust proxy", true); // behind Caddy — read the real client IP from X-Forwarded-For
app.use(compression()); // gzip text responses (HTML/CSS/JS/JSON) — Caddy in front doesn't compress origin responses today
app.use(express.json({ verify: (req, _res, buf) => { req.rawBody = buf; } })); // keep raw body for webhook signature checks

// --- tiny in-memory per-IP rate limiter (abuse guard) ---
const rlHits = new Map();
function rateLimit(max, windowMs) {
  return (req, res, next) => {
    const ip = req.ip || "?";
    const now = Date.now();
    let rec = rlHits.get(ip);
    if (!rec || now > rec.reset) { rec = { count: 0, reset: now + windowMs }; rlHits.set(ip, rec); }
    rec.count++;
    if (rec.count > max) return res.status(429).json({ error: "Too many requests — please slow down and try again in a minute." });
    next();
  };
}

// --- security headers ---
app.use((req, res, next) => {
  res.setHeader("X-Content-Type-Options", "nosniff");
  res.setHeader("X-Frame-Options", "SAMEORIGIN");
  res.setHeader("Referrer-Policy", "strict-origin-when-cross-origin");
  next();
});

// Keep app/API surfaces out of search results. Anything that isn't a marketing
// page has no business being indexed, and robots.txt alone doesn't deindex a URL
// that other sites happen to link to — the header does.
const NOINDEX_PREFIXES = ["/admin", "/auth", "/billing", "/owner", "/r2",
  "/status", "/slots", "/start", "/stop", "/subscribed", "/live-preview",
  "/myuploads", "/deleteupload", "/waitlist", "/twitch-callback", "/studio-assets"];
app.use((req, res, next) => {
  if (NOINDEX_PREFIXES.some((p) => req.path === p || req.path.startsWith(p + "/"))) {
    res.setHeader("X-Robots-Tag", "noindex, nofollow");
  }
  next();
});

// Public Web Studio (claim a slot, upload, go live) at /studio.
app.get(["/studio", "/studio/"], (_req, res) =>
  res.sendFile(path.join(__dirname, "public", "index.html")));

// Owner admin dashboard at /admin (login shell; all data APIs require ADMIN_KEY).
app.get(["/admin", "/admin/"], (_req, res) =>
  res.sendFile(path.join(__dirname, "public", "admin.html")));

// Marketing site + legal pages (privacy/terms/support/sitemap/...) served at /.
// `extensions` lets /stream-key-guide resolve to stream-key-guide.html so clean
// links never 404; every page carries a canonical pointing at the .html form.
app.use(express.static(path.join(__dirname, "site"), {
  extensions: ["html"],
  setHeaders(res, filePath) {
    // HTML changes often and must stay fresh; images/CSS are safe to cache hard.
    res.setHeader("Cache-Control", /\.html$/i.test(filePath)
      ? "public, max-age=0, must-revalidate"
      : "public, max-age=604800");
  },
}));
// Studio's own assets, if ever referenced under a prefix.
app.use("/studio-assets", express.static(path.join(__dirname, "public")));

// ---- Google Sign-In endpoints (no-op until GOOGLE_CLIENT_ID is set) ----
app.get("/auth/config", (_req, res) => res.json({ enabled: AUTH_ON, clientId: GOOGLE_CLIENT_ID }));
app.get("/auth/me", (req, res) => {
  const email = readSession(req);
  if (!email) return res.json({ signedIn: false });
  const tier = tierOf(email);
  res.json({ signedIn: true, email, subscribed: !!tier, tier, multi: tier === "multi", portal: tier ? portalFor(email) : null, slot: mySlot(email) });
});
app.post("/auth/google", rateLimit(20, 60000), async (req, res) => {
  if (!AUTH_ON) return res.status(400).json({ error: "Sign-in is not enabled yet." });
  const idToken = (req.body && req.body.credential) || "";
  if (!idToken) return res.status(400).json({ error: "Missing Google credential." });
  try {
    const r = await fetch("https://oauth2.googleapis.com/tokeninfo?id_token=" + encodeURIComponent(idToken));
    const t = await r.json();
    if (!r.ok || t.aud !== GOOGLE_CLIENT_ID || String(t.email_verified) !== "true" || !t.email) {
      return res.status(401).json({ error: "Could not verify your Google sign-in." });
    }
    const email = String(t.email).trim().toLowerCase();
    res.setHeader("Set-Cookie", sessionCookie(req, signSession(email)));
    saveLead(email, "google-signin");
    const tier = tierOf(email);
    res.json({ signedIn: true, email, name: t.name || "", subscribed: !!tier, tier, multi: tier === "multi", portal: tier ? portalFor(email) : null, slot: mySlot(email) });
  } catch (_) { res.status(500).json({ error: "Sign-in failed — please try again." }); }
});
app.post("/auth/logout", (req, res) => {
  res.setHeader("Set-Cookie", sessionCookie(req, "", 0));
  res.json({ ok: true });
});

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
function csvSafe(v) {
  v = String(v).replace(/[",\n\r]/g, "");
  return /^[=+\-@\t]/.test(v) ? "'" + v : v; // neutralize spreadsheet formula injection
}
function saveLead(email, source) {
  if (!EMAIL_RE.test(email || "")) return false;
  try { fs.appendFileSync(LEADS_FILE, `${csvSafe(email)},${csvSafe(source)},${new Date().toISOString()}\n`); } catch (_) {}
  return true;
}
function rm(p) { try { fs.unlinkSync(p); } catch (_) {} }

// ---- saved videos (reuse an upload without re-uploading every time — subscribers, and any signed-in
// Google account) ----
const LIB_FILE = path.join(DATA_DIR, "library.json");
const LIB_MAX_PER_USER = Number(process.env.LIB_MAX_PER_USER) || 5;             // newest N kept per user
const LIB_MAX_TOTAL = (Number(process.env.LIB_MAX_TOTAL_GB) || 8) * 1024 ** 3;  // global disk budget
const LIB_UNUSED_MS = (Number(process.env.LIB_UNUSED_DAYS) || 30) * 86400000;          // subscriber retention
const LIB_UNUSED_MS_FREE = (Number(process.env.LIB_UNUSED_HOURS_FREE) || 1) * 3600000; // signed-in, never subscribed — a short grace window, not a real "save my videos" perk
let library = []; // [{ id, email, name, size, uploadedAt, lastUsedAt, signedIn }]
try { library = JSON.parse(fs.readFileSync(LIB_FILE, "utf8")) || []; } catch (_) { library = []; }
const saveLib = () => { try { fs.writeFileSync(LIB_FILE, JSON.stringify(library)); } catch (_) {} };
const libPath = (id) => path.join(UPLOAD_DIR, id);
// R2-backed entries have no local file to check — trust the library.json record; the R2 hygiene sweep
// (below) is what keeps that record in sync with what's actually still in the bucket.
const libExists = (u) => { if (u.storage === "r2") return true; try { return fs.existsSync(libPath(u.id)); } catch (_) { return false; } };
const libFor = (email) => { const e = (email || "").trim().toLowerCase(); return library.filter((u) => u.email === e && libExists(u)); };
const libRetentionMs = (email) => (isSubscribed(email) ? LIB_UNUSED_MS : LIB_UNUSED_MS_FREE);
// Deletes a saved video's actual bytes, wherever they live — local disk (Web Studio) or R2 (Streamza
// Loop). Every eviction path below goes through this instead of calling rm()/r2.deleteObject() directly.
function rmVideo(u) {
  if (u.storage === "r2") { r2.deleteObject(u.id); r2UsedBytes = Math.max(0, r2UsedBytes - (u.size || 0)); }
  else rm(libPath(u.id));
}
function libAdd(email, fileId, name, size, signedIn, canCopy, storage) {
  const e = (email || "").trim().toLowerCase();
  library = library.filter((u) => u.id !== fileId); // de-dupe (also refreshes lastUsedAt on reuse)
  library.push({ id: fileId, email: e, name: name || "video.mp4", size: size || 0, uploadedAt: Date.now(), lastUsedAt: Date.now(), signedIn: !!signedIn, canCopy: !!canCopy, storage: storage || "local" });
  const mine = library.filter((u) => u.email === e).sort((a, b) => b.lastUsedAt - a.lastUsedAt);
  mine.slice(LIB_MAX_PER_USER).forEach((u) => { rmVideo(u); library = library.filter((x) => x.id !== u.id); }); // keep newest N
  saveLib();
}
const libTouch = (fileId, signedIn, canCopy) => { const u = library.find((x) => x.id === fileId); if (u) { u.lastUsedAt = Date.now(); if (signedIn) u.signedIn = true; if (typeof canCopy === "boolean") u.canCopy = canCopy; saveLib(); } };

// ---- R2 storage budget — Cloudflare's R2 free tier is 10GB-month; default the effective ceiling a
// little under that (9GB) so normal day-to-day drift never risks a real bill. Once the bucket is at or
// over this, both Web Studio and Streamza Loop stop routing NEW uploads to R2 and fall back to Oracle's
// own local disk (Web Studio always could; Streamza Loop gets this fallback client-side — see
// /r2/status below). Nothing already stored in R2 is moved or deleted because of this cap.
const R2_MAX_TOTAL_BYTES = (Number(process.env.R2_MAX_TOTAL_GB) || 9) * 1024 ** 3;
let r2UsedBytes = 0; // kept approximately current by direct increment/decrement, corrected hourly (see the R2 sweep) so it can never drift far from reality
function r2HasBudget(size) { return r2.R2_ENABLED && (r2UsedBytes + (size || 0)) <= R2_MAX_TOTAL_BYTES; }
if (r2.R2_ENABLED) {
  r2.listAllObjects().then((objs) => { r2UsedBytes = objs.reduce((n, o) => n + o.size, 0); }).catch(() => {});
}

// ---- 5 slots ----
const slots = Array.from({ length: SLOT_COUNT }, (_, i) => ({
  id: i + 1, busy: false, email: null, dest: null, dests: [], destsFull: [], loop: true,
  file: null, filePath: null, fileSize: 0, storage: "local", r2Key: null,
  startedAt: 0, expiresAt: 0, proc: null, token: null, log: [],
  stopping: false, restartCount: 0, ffmpegArgs: null, signedIn: false, everStreamed: false,
}));
function slog(s, line) { if (!line) return; s.log.push(line); if (s.log.length > 60) s.log.shift(); }

// Why a slot most recently ended, keyed by its (now-cleared) token — release() wipes the slot object
// itself, so /status needs this to tell a real "slot expired, claim another" from "we gave up because
// we couldn't reach your RTMP destination", which look identical from the frontend's polling otherwise.
const recentEndings = new Map(); // token -> { reason: "expired"|"stopped"|"connection_failed", message, at }
const END_REASON_TTL_MS = 5 * 60 * 1000;
function noteEnding(slot, reason, message) { if (slot.token) recentEndings.set(slot.token, { reason, message: message || null, at: Date.now() }); }

function release(s) {
  // Videos are kept for reuse (appear under "Your recent videos") rather than deleted on release.
  if (s.filePath) libAdd(s.email, path.basename(s.filePath), s.file, s.fileSize, s.signedIn, undefined, "local");
  else if (s.r2Key) libAdd(s.email, s.r2Key, s.file, s.fileSize, s.signedIn, undefined, "r2");
  Object.assign(s, {
    busy: false, email: null, dest: null, dests: [], destsFull: [], loop: true,
    file: null, filePath: null, fileSize: 0, storage: "local", r2Key: null,
    startedAt: 0, expiresAt: 0, proc: null, token: null, log: [],
    stopping: false, restartCount: 0, ffmpegArgs: null, signedIn: false, everStreamed: false,
  });
}
// Spawns (or re-spawns) the ffmpeg relay process for a slot. If it dies unexpectedly mid-stream — an RTMP
// hiccup, not a manual /stop or slot expiry — reconnect automatically instead of ending the whole slot,
// so "Loop the video" really does keep streaming until the user stops it or the slot expires.
// But if it never actually got a frame out (a bad stream key/URL, not a blip), retrying 20 times just
// burns time on a doomed connection — fail fast instead and say so clearly.
function launchFfmpeg(slot, args) {
  slot.ffmpegArgs = args;
  const proc = spawn("ffmpeg", args);
  slot.proc = proc;
  proc.on("error", (e) => {
    slog(slot, "FFmpeg failed to start: " + e.message);
    console.log(`[slot ${slot.id}] ffmpeg spawn error: ${e.message}`);
    noteEnding(slot, "connection_failed", "Couldn't start streaming — please double-check your RTMP URL and stream key.");
    release(slot);
  });
  proc.stderr.on("data", (d) => {
    const text = d.toString();
    if (/bitrate=/.test(text)) slot.everStreamed = true; // real encode progress = the connection is actually up
    slog(slot, text.trim().split("\n").pop());
  });
  proc.on("exit", (code) => {
    slog(slot, `FFmpeg stopped (exit ${code}).`);
    if (code) console.log(`[slot ${slot.id}] ffmpeg exit ${code} → ${slot.log.slice(-5).join(" | ")}`);
    const expired = slot.expiresAt && Date.now() >= slot.expiresAt;
    const retryBudget = slot.everStreamed ? FFMPEG_MAX_RESTARTS : 2; // never connected once → fail fast
    const canReconnect = !slot.stopping && slot.busy && !expired && slot.restartCount < retryBudget;
    if (canReconnect) {
      slot.restartCount++;
      slog(slot, `Reconnecting… (attempt ${slot.restartCount}/${retryBudget})`);
      setTimeout(() => { if (slot.busy && !slot.stopping) launchFfmpeg(slot, slot.ffmpegArgs); }, FFMPEG_RESTART_DELAY_MS);
    } else {
      if (!slot.stopping) {
        if (expired) noteEnding(slot, "expired", null);
        else noteEnding(slot, "connection_failed", "Stream disconnected — couldn't reach your destination. Double-check the RTMP URL and stream key, then try again.");
      }
      release(slot);
    }
  });
}

// expiry watchdog: stop+free any slot past its 24h
setInterval(() => {
  const now = Date.now();
  for (const s of slots) {
    if (s.busy && s.expiresAt && now > s.expiresAt) {
      slog(s, "Slot expired (24h) — stopping. Claim a slot again to keep streaming.");
      noteEnding(s, "expired", null);
      s.stopping = true;
      try { if (s.proc) s.proc.kill("SIGINT"); else release(s); } catch (_) { release(s); }
    }
  }
  // saved-video cleanup: drop a library entry once its owner is neither subscribed nor was signed in
  // when it was saved, the file is gone, or it's passed its retention window (30d subscriber / 7d signed-in
  // free — see LIB_UNUSED_MS / LIB_UNUSED_MS_FREE); never touch a file that's streaming right now.
  try {
    const live = new Set(slots.filter((s) => s.filePath || s.r2Key).map((s) => s.filePath ? path.basename(s.filePath) : s.r2Key));
    library = library.filter((u) => {
      if (live.has(u.id)) return true;
      if (!libExists(u)) return false;
      const eligible = isSubscribed(u.email) || u.signedIn;
      if (!eligible || now - u.lastUsedAt > libRetentionMs(u.email)) { rmVideo(u); return false; }
      return true;
    });
    // global disk budget — local disk only (R2 isn't the constrained resource this box's small disk is,
    // so R2-backed entries don't count toward it and are never evicted by this loop).
    let total = library.reduce((n, u) => n + (u.storage === "r2" ? 0 : (u.size || 0)), 0);
    if (total > LIB_MAX_TOTAL) {
      for (const u of [...library].sort((a, b) => a.lastUsedAt - b.lastUsedAt)) {
        if (total <= LIB_MAX_TOTAL) break;
        if (live.has(u.id) || u.storage === "r2") continue;
        rmVideo(u); library = library.filter((x) => x.id !== u.id); total -= (u.size || 0);
      }
    }
    saveLib();
    // drop pre-uploads nobody ever claimed with "Go Live" (picked a file, then walked away)
    for (const [id, p] of pendingUploads) {
      if (now - p.createdAt > PENDING_UPLOAD_TTL_MS) {
        if (p.storage === "r2") r2.deleteObject(p.r2Key); else rm(p.path);
        pendingUploads.delete(id);
      }
    }
    // delete true orphans (files belonging to nothing) older than 30 min — failed/aborted uploads
    const known = new Set(library.map((u) => u.id));
    for (const f of fs.readdirSync(UPLOAD_DIR)) {
      if (live.has(f) || known.has(f) || pendingUploads.has(f)) continue;
      const fp = path.join(UPLOAD_DIR, f);
      try { if (now - fs.statSync(fp).mtimeMs > 30 * 60 * 1000) fs.unlinkSync(fp); } catch (_) {}
    }
  } catch (_) {}
  // prune stale rate-limit records
  for (const [ip, rec] of rlHits) { if (now > rec.reset) rlHits.delete(ip); }
  for (const [tok, rec] of recentEndings) { if (now - rec.at > END_REASON_TTL_MS) recentEndings.delete(tok); }
}, 20000);

// R2 hygiene sweep — a separate, much slower interval than the 20s loop above. Listing the whole bucket
// has a real (if small) cost and orphaned R2 objects aren't time-sensitive the way an expiring slot is.
// Deletes objects that aren't referenced by any saved video, live slot, or still-pending upload — e.g. a
// multipart upload that completed on R2 but the server crashed before /r2/multipart/complete ran.
if (r2.R2_ENABLED) {
  setInterval(async () => {
    try {
      const known = new Set(library.filter((u) => u.storage === "r2").map((u) => u.id));
      for (const p of pendingUploads.values()) if (p.storage === "r2") known.add(p.r2Key);
      for (const s of slots) if (s.r2Key) known.add(s.r2Key);
      const objects = await r2.listAllObjects();
      let liveTotal = 0;
      for (const o of objects) {
        if (known.has(o.key)) liveTotal += o.size;
        else await r2.deleteObject(o.key);
      }
      r2UsedBytes = liveTotal; // self-heals any drift from the incremental +=/-= elsewhere
    } catch (_) {}
  }, 60 * 60 * 1000);
}

// public availability board (no PII)
app.get("/slots", (req, res) => {
  res.json({
    total: SLOT_COUNT,
    free: slots.filter((s) => !s.busy).length,
    slots: slots.map((s) => ({
      id: s.id, busy: s.busy,
      secondsLeft: s.busy && s.expiresAt ? Math.max(0, Math.floor((s.expiresAt - Date.now()) / 1000)) : 0,
    })),
  });
});

// Probe the uploaded file's codecs (ffprobe) — `-c copy` needs H.264 video + AAC audio.
function probeCodecs(file) {
  return new Promise((resolve) => {
    const p = spawn("ffprobe", ["-v", "error", "-show_entries", "stream=codec_type,codec_name", "-of", "json", file]);
    let out = "";
    p.stdout.on("data", (d) => (out += d));
    p.on("close", () => {
      try {
        const j = JSON.parse(out);
        const v = j.streams.find((s) => s.codec_type === "video");
        const a = j.streams.find((s) => s.codec_type === "audio");
        resolve({ video: v ? v.codec_name : null, audio: a ? a.codec_name : null });
      } catch (_) { resolve({ video: null, audio: null }); }
    });
    p.on("error", () => resolve({ video: null, audio: null }));
  });
}

// Whether the source's own keyframe spacing is already tight enough (~2.2s or less) to go out with
// -c copy instead of being re-encoded. This is the single biggest lever for quality on this box: a
// compliant file streams at its ORIGINAL resolution/bitrate — true 4K stays 4K — for close to zero CPU,
// instead of always being downscaled to fit what this VM's 2 vCPUs can re-encode in real time. Only
// looks at the first ~12s of keyframes so it stays fast even on a large 4K file.
function probeGopOk(file) {
  return new Promise((resolve) => {
    const p = spawn("ffprobe", [
      "-v", "error", "-select_streams", "v:0", "-skip_frame", "nokey",
      "-show_entries", "frame=pts_time", "-read_intervals", "%+12",
      "-of", "csv=p=0", file,
    ]);
    let out = "";
    p.stdout.on("data", (d) => (out += d));
    p.on("close", () => {
      const times = out.trim().split("\n").map(Number).filter((n) => Number.isFinite(n));
      if (times.length < 2) return resolve(false); // couldn't confirm two keyframes — re-encode to be safe
      let maxGap = 0;
      for (let i = 1; i < times.length; i++) maxGap = Math.max(maxGap, times[i] - times[i - 1]);
      resolve(maxGap <= 2.2);
    });
    p.on("error", () => resolve(false));
  });
}
async function canStreamCopy(file, codecs) {
  if (!codecs || codecs.video !== "h264" || codecs.audio !== "aac") return false;
  return probeGopOk(file);
}

// Auto-upload: the browser sends the file the moment it's picked (before the plan/destination is even
// chosen), so "Go Live" just claims a slot against an already-uploaded file instead of waiting through
// the whole transfer again. Validated (codec-checked) immediately, same as a normal fresh upload.
const pendingUploads = new Map(); // uploadId -> { path, r2Key, storage, name, size, createdAt, canCopy }
const PENDING_UPLOAD_TTL_MS = 2 * 60 * 60 * 1000; // unclaimed pre-uploads older than this are swept

app.post("/pending-upload", rateLimit(15, 60000), upload.single("video"), async (req, res) => {
  const file = req.file;
  if (!file) return res.status(400).json({ error: "No video file uploaded." });
  const codecs = await probeCodecs(file.path);
  if (codecs.video && codecs.video !== "h264") { rm(file.path); return res.status(400).json({ error: `Your video is ${codecs.video.toUpperCase()} — please export as MP4 with H.264 video so it can stream instantly (no re-encode).` }); }
  if (codecs.audio && codecs.audio !== "aac") { rm(file.path); return res.status(400).json({ error: `Your audio is ${codecs.audio.toUpperCase()} — please use AAC audio (MP4 = H.264 + AAC).` }); }
  const canCopy = await canStreamCopy(file.path, codecs);
  const uploadId = path.basename(file.path);
  // Opportunistically promote to R2 while there's free-tier budget for it — frees the local disk this
  // small VM actually cares about. The browser upload itself is completely unchanged either way; this
  // is purely a server-side "where do these already-received bytes end up living" decision.
  if (r2HasBudget(file.size)) {
    try {
      await r2.uploadFile(uploadId, fs.createReadStream(file.path), file.size, file.mimetype);
      rm(file.path);
      r2UsedBytes += file.size;
      pendingUploads.set(uploadId, { r2Key: uploadId, storage: "r2", name: file.originalname, size: file.size, createdAt: Date.now(), canCopy });
      return res.json({ ok: true, uploadId, name: file.originalname, size: file.size });
    } catch (_) {
      // R2 upload failed — fall through and keep using the local copy, which is still safely on disk.
    }
  }
  pendingUploads.set(uploadId, { path: file.path, storage: "local", name: file.originalname, size: file.size, createdAt: Date.now(), canCopy });
  res.json({ ok: true, uploadId, name: file.originalname, size: file.size });
});

// ---- R2 chunked/resumable upload (Streamza Loop) — bytes go phone -> R2 directly via presigned URLs;
// the Oracle VM only ever orchestrates these three calls, never touches the video bytes. Web Studio is
// untouched — it keeps using the local-disk /pending-upload above. See r2.js for the S3-client details.
// r2Unavailable (not a 5xx — this is an expected, normal outcome once the free-tier budget is spent)
// tells the client to fall back to the local-disk upload path instead of treating it as a hard error.
app.post("/r2/multipart/create", (req, res) => {
  if (!readSession(req)) return res.status(401).json({ error: "Sign in first." });
  const size = Number(req.body.size) || 0;
  if (!r2HasBudget(size)) return res.json({ ok: false, r2Unavailable: true, error: "Cloud upload is at its free-tier limit right now." });
  const name = (req.body.name || "video.mp4").toString();
  const contentType = (req.body.contentType || "video/mp4").toString();
  const key = crypto.randomBytes(16).toString("hex");
  r2.createMultipart(key, contentType)
    .then((uploadId) => res.json({ ok: true, r2Key: key, r2UploadId: uploadId, name }))
    .catch((e) => res.status(500).json({ error: "Couldn't start the upload: " + e.message }));
});

app.post("/r2/multipart/part-url", (req, res) => {
  if (!r2.R2_ENABLED) return res.status(503).json({ error: "Cloud upload isn't configured yet." });
  if (!readSession(req)) return res.status(401).json({ error: "Sign in first." });
  const { r2Key, r2UploadId, partNumber } = req.body;
  if (!r2Key || !r2UploadId || !partNumber) return res.status(400).json({ error: "Missing multipart fields." });
  r2.presignPartUrl(r2Key, r2UploadId, Number(partNumber))
    .then((url) => res.json({ ok: true, url }))
    .catch((e) => res.status(500).json({ error: "Couldn't presign the upload: " + e.message }));
});

app.post("/r2/multipart/complete", async (req, res) => {
  if (!r2.R2_ENABLED) return res.status(503).json({ error: "Cloud upload isn't configured yet." });
  if (!readSession(req)) return res.status(401).json({ error: "Sign in first." });
  const { r2Key, r2UploadId, parts, name, size } = req.body;
  if (!r2Key || !r2UploadId || !Array.isArray(parts) || !parts.length) return res.status(400).json({ error: "Missing multipart fields." });
  try {
    await r2.completeMultipart(r2Key, r2UploadId, parts);
    const getUrl = await r2.presignGetUrl(r2Key);
    const codecs = await probeCodecs(getUrl);
    if (codecs.video && codecs.video !== "h264") { r2.deleteObject(r2Key); return res.status(400).json({ error: `Your video is ${codecs.video.toUpperCase()} — please export as MP4 with H.264 video so it can stream instantly (no re-encode).` }); }
    if (codecs.audio && codecs.audio !== "aac") { r2.deleteObject(r2Key); return res.status(400).json({ error: `Your audio is ${codecs.audio.toUpperCase()} — please use AAC audio (MP4 = H.264 + AAC).` }); }
    const canCopy = await canStreamCopy(getUrl, codecs);
    pendingUploads.set(r2Key, { r2Key, storage: "r2", name: name || "video.mp4", size: Number(size) || 0, createdAt: Date.now(), canCopy });
    r2UsedBytes += Number(size) || 0;
    res.json({ ok: true, uploadId: r2Key, name: name || "video.mp4", size: Number(size) || 0 });
  } catch (e) {
    res.status(500).json({ error: "Couldn't finish the upload: " + e.message });
  }
});

app.post("/r2/multipart/abort", (req, res) => {
  if (!r2.R2_ENABLED) return res.json({ ok: true });
  const { r2Key, r2UploadId } = req.body;
  if (r2Key && r2UploadId) r2.abortMultipart(r2Key, r2UploadId);
  res.json({ ok: true });
});

// Cheap pre-check so a client can skip straight to the local-disk upload path instead of always
// attempting R2 first and only finding out from /r2/multipart/create that the budget's spent. Not
// authoritative on its own — /r2/multipart/create re-checks with the real file size, since a status
// check with no size in mind can't know if this *specific* upload would tip it over the cap.
app.get("/r2/status", (_req, res) => res.json({ available: r2HasBudget(0) }));

app.post("/start", rateLimit(8, 60000), upload.single("video"), async (req, res) => {
  const file = req.file;
  const reuseId = (req.body.fileId || "").trim();
  const pendingId = (req.body.pendingUploadId || "").trim();
  const email = (req.body.email || "").trim();
  const loop = req.body.loop === "true" || req.body.loop === "on";
  const bail = (code, error) => { if (file) rm(file.path); return res.status(code).json({ error }); };

  if (!EMAIL_RE.test(email)) return bail(400, "Enter a valid email to claim a slot.");
  if (req.body.agree !== "true" && req.body.agree !== "on") return bail(400, "Please confirm you have the rights to stream this content.");

  // Source = a fresh upload, an already pre-uploaded (auto-uploaded on file-select) video, or one of
  // the account's saved videos (no re-upload needed either way). R2-backed sources (Streamza Loop's
  // chunked uploads) resolve to a freshly presigned GET URL instead of a local path — everything below
  // this block treats srcPath as an opaque string ffmpeg/ffprobe can read from either way.
  let srcPath, srcName, srcSize, isNew = false, skipCodecCheck = false, srcCanCopy = null, srcStorage = "local", srcR2Key = null;
  if (file) {
    srcPath = file.path; srcName = file.originalname; srcSize = file.size; isNew = true;
  } else if (pendingId && pendingUploads.has(pendingId)) {
    const p = pendingUploads.get(pendingId);
    srcName = p.name; srcSize = p.size; isNew = true; skipCodecCheck = true;
    srcCanCopy = !!p.canCopy;
    if (p.storage === "r2") { srcStorage = "r2"; srcR2Key = p.r2Key; srcPath = await r2.presignGetUrl(p.r2Key); }
    else { srcPath = p.path; }
  } else if (reuseId) {
    const u = libFor(email).find((x) => x.id === reuseId);
    if (!u) return res.status(400).json({ error: "That saved video is no longer available — please upload it again." });
    srcName = u.name; srcSize = u.size;
    srcCanCopy = typeof u.canCopy === "boolean" ? u.canCopy : null; // null = saved before this existed — probe once below
    if (u.storage === "r2") { srcStorage = "r2"; srcR2Key = u.id; srcPath = await r2.presignGetUrl(u.id); }
    else { srcPath = libPath(u.id); }
  } else {
    return res.status(400).json({ error: "No video file uploaded." });
  }

  // destinations (multistream): JSON `dests` [{url,key}], else a single rtmpUrl/streamKey
  let rawDests = [];
  try { rawDests = JSON.parse(req.body.dests || "[]"); } catch (_) {}
  if (!Array.isArray(rawDests) || !rawDests.length) rawDests = [{ url: req.body.rtmpUrl, key: req.body.streamKey }];
  const norm = rawDests
    .map((d) => ({ url: (d.url || "").trim().replace(/\/+$/, ""), key: (d.key || "").trim() }))
    .filter((d) => /^rtmps?:\/\//i.test(d.url));
  if (!norm.length) return bail(400, "Add at least one destination whose URL starts with rtmp:// or rtmps://");

  // codec check — only for fresh uploads (pre-uploaded/saved/reused videos already passed at upload time).
  // Also decides stream-copy vs re-encode here — this is what lets a compliant 4K+ upload go out at its
  // original quality instead of always being downscaled to fit what this box can re-encode in real time.
  if (isNew && !skipCodecCheck) {
    const codecs = await probeCodecs(srcPath);
    if (codecs.video && codecs.video !== "h264") return bail(400, `Your video is ${codecs.video.toUpperCase()} — please export as MP4 with H.264 video so it can stream instantly (no re-encode).`);
    if (codecs.audio && codecs.audio !== "aac") return bail(400, `Your audio is ${codecs.audio.toUpperCase()} — please use AAC audio (MP4 = H.264 + AAC).`);
    srcCanCopy = await canStreamCopy(srcPath, codecs);
  } else if (srcCanCopy === null) {
    // a saved video from before this feature existed — probe once now, then it's cached going forward
    srcCanCopy = await canStreamCopy(srcPath, await probeCodecs(srcPath));
  }

  // honor the specific slot the user tapped, if it's still free; otherwise take the next free one.
  const wanted = Number(req.body.slot);
  const slot = (wanted >= 1 && wanted <= SLOT_COUNT && slots[wanted - 1] && !slots[wanted - 1].busy)
    ? slots[wanted - 1]
    : slots.find((s) => !s.busy);
  if (!slot) return bail(409, `All ${SLOT_COUNT} slots are full right now. Please wait for one to free up.`);

  if (pendingId) pendingUploads.delete(pendingId); // now committed to a slot — no longer "pending"
  saveLead(email, "stream");
  const maxDests = canMultistream(email) ? MULTISTREAM_MAX : 1;  // multistream only on the $10 plan
  const limited = norm.length > maxDests;                        // user asked for more than their plan allows
  const use = norm.slice(0, maxDests);
  const targets = use.map((d) => (d.key ? `${d.url}/${d.key}` : d.url));

  const token = crypto.randomBytes(12).toString("hex");
  const useCopy = RELAY_COPY || !!srcCanCopy;
  const args = ["-re"];
  if (loop) args.push("-stream_loop", "-1");
  // R2 input needs explicit reconnect handling — a local file read never drops, but a network GET can.
  if (srcStorage === "r2") args.push("-reconnect", "1", "-reconnect_streamed", "1", "-reconnect_delay_max", "2", "-reconnect_at_eof", "1");
  args.push("-i", srcPath, "-map", "0:v:0", "-map", "0:a:0?");
  if (useCopy) {
    args.push("-c", "copy");
  } else {
    // Re-encode so YouTube gets a keyframe every 2s (the #1 cause of "not receiving enough video") and a
    // constant frame rate (the #1 cause of a screen-recorded source getting stuck on "Preparing...").
    args.push(
      "-c:v", "libx264", "-preset", RELAY_PRESET, "-pix_fmt", "yuv420p", "-r", String(RELAY_FPS),
      "-force_key_frames", "expr:gte(t,n_forced*2)", "-g", String(RELAY_FPS * 2), "-sc_threshold", "0",
      "-b:v", RELAY_VBITRATE, "-maxrate", RELAY_VBITRATE, "-bufsize", "7000k"
    );
    if (RELAY_MAXH > 0) args.push("-vf", `scale=-2:'min(${RELAY_MAXH},ih)'`);
    args.push("-c:a", "aac", "-b:a", "128k", "-ar", "44100", "-ac", "2");
  }
  if (targets.length === 1) args.push("-f", "flv", targets[0]);
  else args.push("-f", "tee", targets.map((t) => `[f=flv:onfail=ignore]${t}`).join("|")); // fan out to all platforms

  // Signed in via Google (session cookie matches the claimed email), not just typed into the form —
  // this, or an active subscription, is what earns the video a spot in "Your recent videos".
  const signedIn = readSession(req) === email.toLowerCase();
  Object.assign(slot, {
    busy: true, email, dests: use.map((d) => d.url), dest: use[0].url, destsFull: use, loop: !!loop,
    file: srcName, filePath: srcStorage === "local" ? srcPath : null, fileSize: srcSize, storage: srcStorage, r2Key: srcR2Key,
    startedAt: Date.now(), expiresAt: Date.now() + SLOT_MS, token, log: [],
    stopping: false, restartCount: 0, signedIn,
  });
  // Videos are saved for reuse (appear under "Your recent videos") for anyone signed in.
  if (isNew) libAdd(email, srcStorage === "r2" ? srcR2Key : path.basename(srcPath), srcName, srcSize, signedIn, srcCanCopy, srcStorage); else libTouch(reuseId, signedIn, srcCanCopy);
  slog(slot, `Slot ${slot.id} claimed — 24h${use.length > 1 ? ` · multistream ×${use.length}` : ""} ${loop ? "(loop) " : ""}${srcName} [${useCopy ? "stream-copy, original quality" : "re-encoded"}]`);
  launchFfmpeg(slot, args);
  res.json({ ok: true, slot: slot.id, token, expiresAt: slot.expiresAt, destinations: use.length, multistreamLimited: limited });
});

app.post("/stop", (req, res) => {
  const t = req.body.token || req.query.token;
  const s = slots.find((x) => x.token === t && x.busy);
  if (s) { noteEnding(s, "stopped", null); s.stopping = true; try { s.proc ? s.proc.kill("SIGINT") : release(s); } catch (_) { release(s); } }
  res.json({ ok: true });
});

// Saved videos — list this email's reusable uploads, newest first. Available to subscribers, and to any
// signed-in Google account (verified by session, not just the typed email) for that account's own videos.
app.get("/myuploads", (req, res) => {
  const email = (req.query.email || "").trim();
  const subscribed = isSubscribed(email);
  const signedIn = !!email && readSession(req) === email.toLowerCase();
  const retentionMs = libRetentionMs(email);
  const retentionDays = Math.round(retentionMs / 86400000);
  const authorized = subscribed || signedIn;
  const uploads = authorized
    ? libFor(email).sort((a, b) => b.lastUsedAt - a.lastUsedAt)
        .map((u) => ({
          id: u.id, name: u.name, size: u.size, uploadedAt: u.uploadedAt,
          // minutes, not days — the free (never-subscribed) tier's window is only 1 hour, so day
          // granularity would round that up to a misleading "deletes in 1d".
          expiresInMinutes: Math.max(0, Math.ceil((retentionMs - (Date.now() - u.lastUsedAt)) / 60000)),
        }))
    : [];
  res.json({ subscribed, signedIn, retentionDays, uploads });
});

// Stream back a saved video's bytes so re-using it actually shows a preview instead of the blank
// placeholder (the browser has no local File object for a server-saved pick, unlike a fresh upload).
// Same authorization as /myuploads — supports Range requests so the <video> element can seek/scrub.
app.get("/myuploads/:id/file", async (req, res) => {
  const email = (req.query.email || "").trim();
  const subscribed = isSubscribed(email);
  const signedIn = !!email && readSession(req) === email.toLowerCase();
  if (!subscribed && !signedIn) return res.status(403).end();
  const u = libFor(email).find((x) => x.id === req.params.id);
  if (!u) return res.status(404).end();
  if (u.storage === "r2") {
    try { return res.redirect(302, await r2.presignGetUrl(u.id, 600)); } catch (_) { return res.status(500).end(); }
  }
  res.sendFile(libPath(u.id), { headers: { "Content-Type": "video/mp4" } });
});

// Remove a saved video (only the owner's, and not while it's streaming).
app.post("/deleteupload", (req, res) => {
  const email = (req.body.email || "").trim().toLowerCase();
  const id = (req.body.fileId || "").trim();
  const live = new Set(slots.filter((s) => s.filePath || s.r2Key).map((s) => s.filePath ? path.basename(s.filePath) : s.r2Key));
  const u = library.find((x) => x.id === id && x.email === email);
  if (!u) return res.json({ ok: false, error: "Not found." });
  if (live.has(id)) return res.json({ ok: false, error: "That video is streaming right now — stop it first." });
  rmVideo(u); library = library.filter((x) => x.id !== id); saveLib();
  res.json({ ok: true });
});

// Product IDs for the Android app's Play Billing plan-picker — public/client-safe.
app.get("/billing/config", (_req, res) =>
  res.json({ enabled: googlePlay.PLAY_BILLING_ENABLED, productSingle: PLAY_PRODUCT_SINGLE, productMulti: PLAY_PRODUCT_MULTI }));

// Verifies a Play Billing purchase server-side and, on success, marks the buyer's email in the same
// `subscribers` Map Web Studio already reads via isSubscribed()/canMultistream() — one subscription,
// unlocks both. Email comes from the session (readSession), never from the request body, so a
// purchase token can't be credited to an arbitrary email.
app.post("/billing/verify-purchase", rateLimit(10, 60000), async (req, res) => {
  if (!googlePlay.PLAY_BILLING_ENABLED) return res.status(503).json({ error: "Billing verification isn't configured yet." });
  const email = readSession(req);
  if (!email) return res.status(401).json({ error: "Sign in first." });
  const purchaseToken = (req.body.purchaseToken || "").toString();
  if (!purchaseToken) return res.status(400).json({ error: "Missing purchase token." });
  try {
    const sub = await googlePlay.getSubscription(purchaseToken);
    const state = sub.subscriptionState;
    if (state !== "SUBSCRIPTION_STATE_ACTIVE" && state !== "SUBSCRIPTION_STATE_IN_GRACE_PERIOD") {
      return res.status(400).json({ error: "This subscription isn't active." });
    }
    const productId = (sub.lineItems && sub.lineItems[0] && sub.lineItems[0].productId) || "";
    const tier = productId === PLAY_PRODUCT_MULTI ? "multi" : "single";
    try { await googlePlay.acknowledgePurchase(productId, purchaseToken); } catch (_) {} // no-op if already acknowledged
    subscribers.set(email, tier); saveSubs();
    portals.set(email, `https://play.google.com/store/account/subscriptions?sku=${encodeURIComponent(productId)}&package=${encodeURIComponent(googlePlay.PACKAGE_NAME)}`); savePortals();
    res.json({ ok: true, tier });
  } catch (e) {
    res.status(500).json({ error: "Couldn't verify that purchase: " + e.message });
  }
});

// Studio polls this after checkout to know when the webhook has activated the subscription.
app.get("/subscribed", (req, res) => {
  const email = req.query.email || "";
  const t = tierOf(email);
  res.json({ active: !!t, tier: t, multi: t === "multi" });
});

// owner's slot detail (by token)
app.get("/status", (req, res) => {
  const token = req.query.token || "";
  const s = slots.find((x) => x.token === token && x.busy);
  if (!s) {
    const r = recentEndings.get(token);
    return res.json({ running: false, endReason: r ? r.reason : null, endMessage: r ? r.message : null });
  }
  res.json({
    running: true, slot: s.id, file: s.file, fileId: s.filePath ? path.basename(s.filePath) : (s.r2Key || null),
    dest: s.dest, dests: s.dests, destsFull: s.destsFull, loop: !!s.loop, email: s.email,
    uptime: Math.floor((Date.now() - s.startedAt) / 1000),
    secondsLeft: Math.max(0, Math.floor((s.expiresAt - Date.now()) / 1000)),
    log: s.log.slice(-14),
  });
});

// Stream back the slot's currently-playing video to its own owner (matching manage token = same trust
// level /stop already requires) — lets the studio show a real "what's actually live right now" preview
// on resume, instead of an empty player that just says LIVE and leaves the user guessing.
app.get("/live-preview", async (req, res) => {
  const token = req.query.token || "";
  const s = slots.find((x) => x.token === token && x.busy);
  if (!s || (!s.filePath && !s.r2Key)) return res.status(404).end();
  if (s.r2Key) {
    try { return res.redirect(302, await r2.presignGetUrl(s.r2Key, 600)); } catch (_) { return res.status(500).end(); }
  }
  res.sendFile(s.filePath, { headers: { "Content-Type": "video/mp4" } });
});

app.post("/waitlist", rateLimit(6, 60000), (req, res) => {
  const ok = saveLead((req.body.email || "").trim(), req.body.source || "waitlist");
  if (!ok) return res.status(400).json({ error: "Enter a valid email." });
  res.json({ ok: true });
});

// ---- Owner admin (key-gated) ----
function adminOk(req) { return (req.query.key || req.get("x-admin-key") || "") === ADMIN_KEY; }
function readLeads() {
  try {
    const lines = fs.readFileSync(LEADS_FILE, "utf8").trim().split("\n");
    lines.shift(); // header row
    return lines.filter(Boolean).map((l) => {
      const [email, source, timestamp] = l.split(",");
      return { email, source, timestamp };
    });
  } catch (_) { return []; }
}

// CSV export of all captured emails.
app.get("/owner/leads", (req, res) => {
  if (!adminOk(req)) return res.status(401).send("Unauthorized");
  res.type("text/plain").send(fs.existsSync(LEADS_FILE) ? fs.readFileSync(LEADS_FILE, "utf8") : "");
});

// Dashboard data: users, waitlist, live streams, slot usage.
app.get("/admin/api/overview", (req, res) => {
  if (!adminOk(req)) return res.status(401).json({ error: "Unauthorized" });
  const leads = readLeads();
  const waitlist = leads.filter((l) => (l.source || "").toLowerCase().includes("wait"));
  const now = Date.now();
  const active = slots.filter((s) => s.busy).map((s) => ({
    id: s.id, email: s.email, file: s.file, dest: s.dest, dests: s.dests,
    uptime: Math.floor((now - s.startedAt) / 1000),
    secondsLeft: Math.max(0, Math.floor((s.expiresAt - now) / 1000)),
  }));
  res.json({
    slotsTotal: SLOT_COUNT,
    slotsBusy: active.length,
    slotsFree: SLOT_COUNT - active.length,
    totalLeads: leads.length,
    totalWaitlist: waitlist.length,
    totalSubscribers: subscribers.size,
    totalMulti: [...subscribers.values()].filter((t) => t === "multi").length,
    playBillingEnabled: googlePlay.PLAY_BILLING_ENABLED,
    active,
    recentLeads: leads.slice(-25).reverse(),
  });
});

// Admin override: stop any slot by id.
app.post("/admin/api/stop", (req, res) => {
  if (!adminOk(req)) return res.status(401).json({ error: "Unauthorized" });
  const id = Number(req.query.slot || (req.body && req.body.slot));
  const s = slots.find((x) => x.id === id && x.busy);
  if (s) { noteEnding(s, "stopped", null); s.stopping = true; try { s.proc ? s.proc.kill("SIGINT") : release(s); } catch (_) { release(s); } }
  res.json({ ok: true });
});

// friendly 404 for unknown paths
app.use((req, res) => res.status(404).type("html").set("X-Robots-Tag", "noindex").send(
  '<!doctype html><meta charset="utf-8"><title>404 — Streamza</title>' +
  '<meta name="robots" content="noindex">' +
  '<body style="margin:0;background:#000;color:#fff;font-family:-apple-system,Segoe UI,Roboto,sans-serif;text-align:center;padding:90px 20px">' +
  '<h1 style="font-size:64px;margin:0;color:#FF3B30">404</h1><p style="color:#9b9ba3">That page doesn\'t exist.</p>' +
  '<p><a href="/" style="color:#FF3B30;font-weight:700;text-decoration:none">← Back to Streamza</a> &nbsp;·&nbsp; <a href="/studio" style="color:#fff;text-decoration:none">Web Studio</a></p></body>'));

app.listen(PORT, "0.0.0.0", () => console.log(`Streamza Relay (${SLOT_COUNT} slots, free 24h streaming — beta) listening on :${PORT}`));
