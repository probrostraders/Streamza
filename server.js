/**
 * Streamza Relay — "claim a slot, upload a video -> stream to RTMP" (Node + FFmpeg `-c copy`).
 *
 * Model: SLOT_COUNT concurrent slots. Tapping a free slot starts a free 15-minute TRIAL (no payment).
 * "Upgrade to 24 hours" extends it (SLOT_MS). Each claim returns a private manage-token (stored in the
 * browser) to monitor/stop/upgrade that slot. The expiry watchdog stops trials/slots when they run out.
 *
 * Payment: PAYMENTS_LIVE=false → /upgrade just records interest ("coming soon"). When the Paddle
 * checkout is ready, set PAYMENTS_LIVE=true and implement verifyPayment() — that's the only change.
 */
const express = require("express");
const multer = require("multer");
const { spawn } = require("child_process");
const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const PORT = process.env.PORT || 3000;
const ADMIN_KEY = process.env.ADMIN_KEY || "streamza-admin";
const SLOT_COUNT = Number(process.env.SLOT_COUNT) || 10; // max safe on the free 1GB micro (-c copy is light; RAM + outbound bandwidth are the limit). Raise via env on a bigger instance.
const SLOT_MS = 24 * 60 * 60 * 1000;  // full paid duration (24h)
const TRIAL_MS = Number(process.env.TRIAL_MS) || 15 * 60 * 1000; // free 15-minute trial on every claim
// Anti-abuse: reserve some slots only subscribers can take, and rate-limit free trials per IP, so
// free-trial spam can never starve paying customers.
const RESERVED_FOR_SUBS = Number(process.env.RESERVED_FOR_SUBS) || 3;       // slots only subscribers can claim
const MAX_FREE_SLOTS = Math.max(1, SLOT_COUNT - RESERVED_FOR_SUBS);         // free trials may use at most this many
const TRIAL_COOLDOWN_MS = Number(process.env.TRIAL_COOLDOWN_MS) || 30 * 60 * 1000; // 1 free trial per IP / 30 min
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
// --- Lemon Squeezy (two subscription tiers) — set via env once your store is ready ---
const LS_STORE = process.env.LS_STORE || "";                   // store subdomain, e.g. "streamza"
const LS_VARIANT_ID = process.env.LS_VARIANT_ID || "";         // SINGLE plan variant ($4.99/mo, 1 platform)
const LS_VARIANT_MULTI = process.env.LS_VARIANT_MULTI || "";   // MULTISTREAM plan variant ($10/mo, up to N platforms)
const LS_WEBHOOK_SECRET = process.env.LS_WEBHOOK_SECRET || ""; // webhook signing secret
const LS_PRICE_LABEL = process.env.LS_PRICE_LABEL || "";       // e.g. "$4.99/mo"
const LS_PRICE_LABEL_MULTI = process.env.LS_PRICE_LABEL_MULTI || ""; // e.g. "$10/mo"
const MULTISTREAM_MAX = Number(process.env.MULTISTREAM_MAX) || 3;    // max simultaneous platforms on the multistream plan
const ckurl = (v) => (LS_STORE && v) ? `https://${LS_STORE}.lemonsqueezy.com/checkout/buy/${v}` : "";
const LS_CHECKOUT = ckurl(LS_VARIANT_ID);
const LS_CHECKOUT_MULTI = ckurl(LS_VARIANT_MULTI);
const PAYMENTS_LIVE = !!(LS_CHECKOUT && LS_WEBHOOK_SECRET);     // on when the single-plan checkout + webhook are configured
const BETA_FREE = !PAYMENTS_LIVE;     // admin label: paid subscriptions not enabled yet
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
function tierOf(email) { return subscribers.get((email || "").trim().toLowerCase()) || null; }
function isSubscribed(email) { return !!tierOf(email); }            // any plan → full 24h
function canMultistream(email) { return tierOf(email) === "multi"; } // only the $10 plan

// Lemon Squeezy customer-portal links per email (captured from subscription webhooks) so a signed-in
// user can cancel / update their card. Falls back to the generic LS "my orders" magic-link page.
const PORTAL_FILE = path.join(DATA_DIR, "portals.json");
const LS_MY_ORDERS = process.env.LS_MY_ORDERS || "https://app.lemonsqueezy.com/my-orders";
let portals = new Map();
try { const raw = JSON.parse(fs.readFileSync(PORTAL_FILE, "utf8")) || {}; for (const [e, u] of Object.entries(raw)) portals.set(e, u); } catch (_) {}
function savePortals() { try { fs.writeFileSync(PORTAL_FILE, JSON.stringify(Object.fromEntries(portals))); } catch (_) {} }
function portalFor(email) { return portals.get((email || "").trim().toLowerCase()) || LS_MY_ORDERS; }
// The signed-in user's currently active slot — lets them resume control of their stream from any device.
function mySlot(email) {
  const e = (email || "").trim().toLowerCase();
  const s = slots.find((x) => x.busy && (x.email || "").toLowerCase() === e);
  if (!s) return null;
  return { id: s.id, trial: s.trial, token: s.token, live: !!s.proc, file: s.file,
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
app.use(express.json({ verify: (req, _res, buf) => { req.rawBody = buf; } })); // keep raw body for webhook signature checks

// --- tiny in-memory per-IP rate limiter (abuse guard) ---
const rlHits = new Map();
const trialCooldown = new Map(); // ip -> last free-trial start (ms), for the per-IP trial cooldown
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
const NOINDEX_PREFIXES = ["/admin", "/auth", "/pay", "/owner", "/lemonsqueezy",
  "/status", "/slots", "/start", "/stop", "/upgrade", "/subscribed",
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
const LIB_UNUSED_MS = (Number(process.env.LIB_UNUSED_DAYS) || 30) * 86400000;        // subscriber retention
const LIB_UNUSED_MS_FREE = (Number(process.env.LIB_UNUSED_DAYS_FREE) || 7) * 86400000; // signed-in (free) retention
let library = []; // [{ id, email, name, size, uploadedAt, lastUsedAt, signedIn }]
try { library = JSON.parse(fs.readFileSync(LIB_FILE, "utf8")) || []; } catch (_) { library = []; }
const saveLib = () => { try { fs.writeFileSync(LIB_FILE, JSON.stringify(library)); } catch (_) {} };
const libPath = (id) => path.join(UPLOAD_DIR, id);
const libExists = (u) => { try { return fs.existsSync(libPath(u.id)); } catch (_) { return false; } };
const libFor = (email) => { const e = (email || "").trim().toLowerCase(); return library.filter((u) => u.email === e && libExists(u)); };
const libRetentionMs = (email) => (isSubscribed(email) ? LIB_UNUSED_MS : LIB_UNUSED_MS_FREE);
function libAdd(email, fileId, name, size, signedIn) {
  const e = (email || "").trim().toLowerCase();
  library = library.filter((u) => u.id !== fileId); // de-dupe (also refreshes lastUsedAt on reuse)
  library.push({ id: fileId, email: e, name: name || "video.mp4", size: size || 0, uploadedAt: Date.now(), lastUsedAt: Date.now(), signedIn: !!signedIn });
  const mine = library.filter((u) => u.email === e).sort((a, b) => b.lastUsedAt - a.lastUsedAt);
  mine.slice(LIB_MAX_PER_USER).forEach((u) => { rm(libPath(u.id)); library = library.filter((x) => x.id !== u.id); }); // keep newest N
  saveLib();
}
const libTouch = (fileId, signedIn) => { const u = library.find((x) => x.id === fileId); if (u) { u.lastUsedAt = Date.now(); if (signedIn) u.signedIn = true; saveLib(); } };

// ---- 5 slots ----
const slots = Array.from({ length: SLOT_COUNT }, (_, i) => ({
  id: i + 1, busy: false, trial: true, email: null, dest: null, dests: [], file: null, filePath: null, fileSize: 0,
  startedAt: 0, expiresAt: 0, proc: null, token: null, log: [],
  stopping: false, restartCount: 0, ffmpegArgs: null, signedIn: false,
}));
function slog(s, line) { if (!line) return; s.log.push(line); if (s.log.length > 60) s.log.shift(); }
function release(s) {
  // Signed-in users (subscriber or Google account) KEEP their video (reusable — no re-upload);
  // anonymous free-trial uploads are removed.
  if (s.filePath) {
    if (isSubscribed(s.email) || s.signedIn) libAdd(s.email, path.basename(s.filePath), s.file, s.fileSize, s.signedIn);
    else rm(s.filePath);
  }
  Object.assign(s, {
    busy: false, trial: true, email: null, dest: null, dests: [], file: null, filePath: null, fileSize: 0,
    startedAt: 0, expiresAt: 0, proc: null, token: null, log: [],
    stopping: false, restartCount: 0, ffmpegArgs: null, signedIn: false,
  });
}
// Spawns (or re-spawns) the ffmpeg relay process for a slot. If it dies unexpectedly mid-stream — an RTMP
// hiccup, not a manual /stop or slot expiry — reconnect automatically instead of ending the whole slot,
// so "Loop the video" really does keep streaming until the user stops it or the slot/trial runs out.
function launchFfmpeg(slot, args) {
  slot.ffmpegArgs = args;
  const proc = spawn("ffmpeg", args);
  slot.proc = proc;
  proc.on("error", (e) => {
    slog(slot, "FFmpeg failed to start: " + e.message);
    console.log(`[slot ${slot.id}] ffmpeg spawn error: ${e.message}`);
    release(slot);
  });
  proc.stderr.on("data", (d) => slog(slot, d.toString().trim().split("\n").pop()));
  proc.on("exit", (code) => {
    slog(slot, `FFmpeg stopped (exit ${code}).`);
    if (code) console.log(`[slot ${slot.id}] ffmpeg exit ${code} → ${slot.log.slice(-5).join(" | ")}`);
    const canReconnect = !slot.stopping && slot.busy && slot.expiresAt && Date.now() < slot.expiresAt
      && slot.restartCount < FFMPEG_MAX_RESTARTS;
    if (canReconnect) {
      slot.restartCount++;
      slog(slot, `Reconnecting… (attempt ${slot.restartCount}/${FFMPEG_MAX_RESTARTS})`);
      setTimeout(() => { if (slot.busy && !slot.stopping) launchFfmpeg(slot, slot.ffmpegArgs); }, FFMPEG_RESTART_DELAY_MS);
    } else {
      release(slot);
    }
  });
}

// expiry watchdog: stop+free any slot past its 24h
setInterval(() => {
  const now = Date.now();
  for (const s of slots) {
    if (s.busy && s.expiresAt && now > s.expiresAt) {
      slog(s, s.trial ? "Free 15-minute trial ended — upgrade to 24 hours to keep streaming." : "Slot expired (24h) — stopping. Renew to continue.");
      s.stopping = true;
      try { if (s.proc) s.proc.kill("SIGINT"); else release(s); } catch (_) { release(s); }
    }
  }
  // saved-video cleanup: drop a library entry once its owner is neither subscribed nor was signed in
  // when it was saved, the file is gone, or it's passed its retention window (30d subscriber / 7d signed-in
  // free — see LIB_UNUSED_MS / LIB_UNUSED_MS_FREE); never touch a file that's streaming right now.
  try {
    const live = new Set(slots.filter((s) => s.filePath).map((s) => path.basename(s.filePath)));
    library = library.filter((u) => {
      if (live.has(u.id)) return true;
      if (!libExists(u)) return false;
      const eligible = isSubscribed(u.email) || u.signedIn;
      if (!eligible || now - u.lastUsedAt > libRetentionMs(u.email)) { rm(libPath(u.id)); return false; }
      return true;
    });
    // global disk budget — evict the least-recently-used (non-live) saved videos until under the cap
    let total = library.reduce((n, u) => n + (u.size || 0), 0);
    if (total > LIB_MAX_TOTAL) {
      for (const u of [...library].sort((a, b) => a.lastUsedAt - b.lastUsedAt)) {
        if (total <= LIB_MAX_TOTAL) break;
        if (live.has(u.id)) continue;
        rm(libPath(u.id)); library = library.filter((x) => x.id !== u.id); total -= (u.size || 0);
      }
    }
    saveLib();
    // delete true orphans (files belonging to nothing) older than 30 min — failed/aborted uploads
    const known = new Set(library.map((u) => u.id));
    for (const f of fs.readdirSync(UPLOAD_DIR)) {
      if (live.has(f) || known.has(f)) continue;
      const fp = path.join(UPLOAD_DIR, f);
      try { if (now - fs.statSync(fp).mtimeMs > 30 * 60 * 1000) fs.unlinkSync(fp); } catch (_) {}
    }
  } catch (_) {}
  // prune stale rate-limit + trial-cooldown records
  for (const [ip, rec] of rlHits) { if (now > rec.reset) rlHits.delete(ip); }
  for (const [ip, t] of trialCooldown) { if (now - t > TRIAL_COOLDOWN_MS) trialCooldown.delete(ip); }
}, 20000);

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

app.post("/start", rateLimit(8, 60000), upload.single("video"), async (req, res) => {
  const file = req.file;
  const reuseId = (req.body.fileId || "").trim();
  const email = (req.body.email || "").trim();
  const loop = req.body.loop === "true" || req.body.loop === "on";
  const bail = (code, error) => { if (file) rm(file.path); return res.status(code).json({ error }); };

  if (!EMAIL_RE.test(email)) return bail(400, "Enter a valid email to claim a slot.");
  if (req.body.agree !== "true" && req.body.agree !== "on") return bail(400, "Please confirm you have the rights to stream this content.");

  // Source = a fresh upload OR one of the subscriber's saved videos (no re-upload needed).
  let srcPath, srcName, srcSize, isNew = false;
  if (file) {
    srcPath = file.path; srcName = file.originalname; srcSize = file.size; isNew = true;
  } else if (reuseId) {
    const u = libFor(email).find((x) => x.id === reuseId);
    if (!u) return res.status(400).json({ error: "That saved video is no longer available — please upload it again." });
    srcPath = libPath(u.id); srcName = u.name; srcSize = u.size;
  } else {
    return res.status(400).json({ error: "No video file uploaded." });
  }

  // Free-trial anti-abuse: per-IP cooldown + reserve slots for subscribers, so trial spam can never
  // block paying customers. (Subscribers skip both checks entirely.)
  const paid = isSubscribed(email);
  if (!paid) {
    const last = trialCooldown.get(req.ip) || 0;
    if (Date.now() - last < TRIAL_COOLDOWN_MS) {
      const mins = Math.ceil((TRIAL_COOLDOWN_MS - (Date.now() - last)) / 60000);
      return bail(429, `You just used a free trial. Try again in ~${mins} min — or subscribe for unlimited 24-hour streaming with no wait.`);
    }
    if (slots.filter((s) => s.busy && s.trial).length >= MAX_FREE_SLOTS) {
      return bail(409, "All free-trial slots are busy right now. Subscribe for a guaranteed 24-hour slot, or try again shortly.");
    }
  }

  // destinations (multistream): JSON `dests` [{url,key}], else a single rtmpUrl/streamKey
  let rawDests = [];
  try { rawDests = JSON.parse(req.body.dests || "[]"); } catch (_) {}
  if (!Array.isArray(rawDests) || !rawDests.length) rawDests = [{ url: req.body.rtmpUrl, key: req.body.streamKey }];
  const norm = rawDests
    .map((d) => ({ url: (d.url || "").trim().replace(/\/+$/, ""), key: (d.key || "").trim() }))
    .filter((d) => /^rtmps?:\/\//i.test(d.url));
  if (!norm.length) return bail(400, "Add at least one destination whose URL starts with rtmp:// or rtmps://");

  // codec check — only for fresh uploads (saved/reused videos already passed at upload time)
  if (isNew) {
    const codecs = await probeCodecs(srcPath);
    if (codecs.video && codecs.video !== "h264") return bail(400, `Your video is ${codecs.video.toUpperCase()} — please export as MP4 with H.264 video so it can stream instantly (no re-encode).`);
    if (codecs.audio && codecs.audio !== "aac") return bail(400, `Your audio is ${codecs.audio.toUpperCase()} — please use AAC audio (MP4 = H.264 + AAC).`);
  }

  // honor the specific slot the user tapped, if it's still free; otherwise take the next free one.
  const wanted = Number(req.body.slot);
  const slot = (wanted >= 1 && wanted <= SLOT_COUNT && slots[wanted - 1] && !slots[wanted - 1].busy)
    ? slots[wanted - 1]
    : slots.find((s) => !s.busy);
  if (!slot) return bail(409, `All ${SLOT_COUNT} slots are full right now. Please wait for one to free up.`);

  saveLead(email, "stream");
  const maxDests = canMultistream(email) ? MULTISTREAM_MAX : 1;  // multistream only on the $10 plan
  const limited = norm.length > maxDests;                        // user asked for more than their plan allows
  const use = norm.slice(0, maxDests);
  const targets = use.map((d) => (d.key ? `${d.url}/${d.key}` : d.url));

  const token = crypto.randomBytes(12).toString("hex");
  const args = ["-re"];
  if (loop) args.push("-stream_loop", "-1");
  args.push("-i", srcPath, "-map", "0:v:0", "-map", "0:a:0?");
  if (RELAY_COPY) {
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
    busy: true, trial: !paid, email, dests: use.map((d) => d.url), dest: use[0].url,
    file: srcName, filePath: srcPath, fileSize: srcSize,
    startedAt: Date.now(), expiresAt: Date.now() + (paid ? SLOT_MS : TRIAL_MS), token, log: [],
    stopping: false, restartCount: 0, signedIn,
  });
  // Subscribers' and signed-in users' videos are saved for reuse (appear under "Your recent videos");
  // reused ones refresh their spot instead of duplicating.
  if (paid || signedIn) { if (isNew) libAdd(email, path.basename(srcPath), srcName, srcSize, signedIn); else libTouch(reuseId, signedIn); }
  if (!paid) trialCooldown.set(req.ip, Date.now());   // start this IP's free-trial cooldown
  slog(slot, `Slot ${slot.id} claimed — ${paid ? "24h" : `free ${TRIAL_MS / 60000}-min trial`}${use.length > 1 ? ` · multistream ×${use.length}` : ""} ${loop ? "(loop) " : ""}${srcName}`);
  launchFfmpeg(slot, args);
  res.json({ ok: true, slot: slot.id, token, trial: slot.trial, expiresAt: slot.expiresAt, destinations: use.length, multistreamLimited: limited });
});

app.post("/stop", (req, res) => {
  const t = req.body.token || req.query.token;
  const s = slots.find((x) => x.token === t && x.busy);
  if (s) { s.stopping = true; try { s.proc ? s.proc.kill("SIGINT") : release(s); } catch (_) { release(s); } }
  res.json({ ok: true });
});

// Saved videos — list this email's reusable uploads, newest first. Available to subscribers, and to any
// signed-in Google account (verified by session, not just the typed email) for that account's own videos.
app.get("/myuploads", (req, res) => {
  const email = (req.query.email || "").trim();
  const subscribed = isSubscribed(email);
  const signedIn = !!email && readSession(req) === email.toLowerCase();
  const retentionDays = Math.round(libRetentionMs(email) / 86400000);
  const authorized = subscribed || signedIn;
  const uploads = authorized
    ? libFor(email).sort((a, b) => b.lastUsedAt - a.lastUsedAt)
        .map((u) => ({
          id: u.id, name: u.name, size: u.size, uploadedAt: u.uploadedAt,
          expiresInDays: Math.max(0, Math.ceil((libRetentionMs(email) - (Date.now() - u.lastUsedAt)) / 86400000)),
        }))
    : [];
  res.json({ subscribed, signedIn, retentionDays, uploads });
});

// Stream back a saved video's bytes so re-using it actually shows a preview instead of the blank
// placeholder (the browser has no local File object for a server-saved pick, unlike a fresh upload).
// Same authorization as /myuploads — supports Range requests so the <video> element can seek/scrub.
app.get("/myuploads/:id/file", (req, res) => {
  const email = (req.query.email || "").trim();
  const subscribed = isSubscribed(email);
  const signedIn = !!email && readSession(req) === email.toLowerCase();
  if (!subscribed && !signedIn) return res.status(403).end();
  const u = libFor(email).find((x) => x.id === req.params.id);
  if (!u) return res.status(404).end();
  res.sendFile(libPath(u.id), { headers: { "Content-Type": "video/mp4" } });
});

// Remove a saved video (only the owner's, and not while it's streaming).
app.post("/deleteupload", (req, res) => {
  const email = (req.body.email || "").trim().toLowerCase();
  const id = (req.body.fileId || "").trim();
  const live = new Set(slots.filter((s) => s.filePath).map((s) => path.basename(s.filePath)));
  const u = library.find((x) => x.id === id && x.email === email);
  if (!u) return res.json({ ok: false, error: "Not found." });
  if (live.has(id)) return res.json({ ok: false, error: "That video is streaming right now — stop it first." });
  rm(libPath(id)); library = library.filter((x) => x.id !== id); saveLib();
  res.json({ ok: true });
});

// Extend a running free trial to the full 24 hours. If the user's email already has an active
// subscription, extend immediately. Otherwise tell the UI to open Paddle checkout (or "coming soon").
app.post("/upgrade", rateLimit(15, 60000), (req, res) => {
  const t = req.body.token || req.query.token;
  const s = slots.find((x) => x.token === t && x.busy);
  if (!s) return res.status(404).json({ error: "No active stream found for this session." });
  if (isSubscribed(s.email)) {
    s.trial = false;
    s.expiresAt = s.startedAt + SLOT_MS; // full 24h measured from when the stream started
    slog(s, "Upgraded to the full 24 hours (subscription active).");
    return res.json({ ok: true, expiresAt: s.expiresAt });
  }
  if (!PAYMENTS_LIVE) { saveLead(s.email, "upgrade-interest"); return res.json({ ok: false, comingSoon: true }); }
  return res.json({ ok: false, needsCheckout: true }); // UI opens Paddle checkout, then retries
});

// Checkout config for the studio (the buy URL is public).
app.get("/pay/config", (_req, res) =>
  res.json({
    enabled: PAYMENTS_LIVE,
    checkoutUrl: LS_CHECKOUT, priceLabel: LS_PRICE_LABEL,
    checkoutMultiUrl: LS_CHECKOUT_MULTI, priceLabelMulti: LS_PRICE_LABEL_MULTI,
    multiEnabled: !!LS_CHECKOUT_MULTI, maxDests: MULTISTREAM_MAX,
  }));

// Studio polls this after checkout to know when the webhook has activated the subscription.
app.get("/subscribed", (req, res) => { const t = tierOf(req.query.email || ""); res.json({ active: !!t, tier: t, multi: t === "multi" }); });

// Lemon Squeezy webhook — verifies X-Signature, then adds/removes the subscriber by email.
function verifyLsSig(req) {
  if (!LS_WEBHOOK_SECRET || !req.rawBody) return false;
  try {
    const sig = req.get("X-Signature") || "";
    const mac = crypto.createHmac("sha256", LS_WEBHOOK_SECRET).update(req.rawBody).digest("hex");
    return sig.length === mac.length && crypto.timingSafeEqual(Buffer.from(mac), Buffer.from(sig));
  } catch (_) { return false; }
}
app.post("/lemonsqueezy/webhook", (req, res) => {
  if (!verifyLsSig(req)) return res.status(401).send("bad signature");
  const evt = req.body || {};
  const name = (evt.meta && evt.meta.event_name) || "";
  const custom = (evt.meta && evt.meta.custom_data) || {};
  const attrs = (evt.data && evt.data.attributes) || {};
  const email = ((custom.relay_email || attrs.user_email || "") + "").trim().toLowerCase();
  // tier: the purchased product/variant name is authoritative; fall back to the tier we tagged at checkout
  const pname = ((attrs.variant_name || "") + " " + (attrs.product_name || "")).toLowerCase();
  const tier = (/multi/.test(pname) || custom.tier === "multi") ? "multi" : "single";
  // Only trust attrs.status as a SUBSCRIPTION status when the payload is actually a subscription
  // object (data.type === "subscriptions"). subscription_payment_success carries a
  // subscription-invoice instead, whose own "status" (e.g. "paid") means something different —
  // treating it the same here was wiping out subscribers seconds after subscription_created had
  // just correctly added them.
  const isSubscriptionObject = evt.data && evt.data.type === "subscriptions";
  if (email) {
    if (/^subscription_/.test(name) && isSubscriptionObject) {
      const status = (attrs.status || "").toLowerCase(); // active, on_trial, paused, past_due, cancelled, expired, unpaid
      if (status === "active" || status === "on_trial") subscribers.set(email, tier);
      else subscribers.delete(email);
      saveSubs();
      const portalUrl = (attrs.urls && (attrs.urls.customer_portal || attrs.urls.update_payment_method)) || "";
      if (portalUrl) { portals.set(email, portalUrl); savePortals(); }
    } else if (name === "order_created") { subscribers.set(email, tier); saveSubs(); }
  }
  res.json({ ok: true });
});

// owner's slot detail (by token)
app.get("/status", (req, res) => {
  const s = slots.find((x) => x.token === (req.query.token || "") && x.busy);
  if (!s) return res.json({ running: false });
  res.json({
    running: true, slot: s.id, file: s.file, dest: s.dest, dests: s.dests, trial: s.trial, email: s.email,
    uptime: Math.floor((Date.now() - s.startedAt) / 1000),
    secondsLeft: Math.max(0, Math.floor((s.expiresAt - Date.now()) / 1000)),
    log: s.log.slice(-14),
  });
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
    paymentsLive: PAYMENTS_LIVE,
    betaFree: BETA_FREE,
    active,
    recentLeads: leads.slice(-25).reverse(),
  });
});

// Admin override: stop any slot by id.
app.post("/admin/api/stop", (req, res) => {
  if (!adminOk(req)) return res.status(401).json({ error: "Unauthorized" });
  const id = Number(req.query.slot || (req.body && req.body.slot));
  const s = slots.find((x) => x.id === id && x.busy);
  if (s) { s.stopping = true; try { s.proc ? s.proc.kill("SIGINT") : release(s); } catch (_) { release(s); } }
  res.json({ ok: true });
});

// friendly 404 for unknown paths
app.use((req, res) => res.status(404).type("html").set("X-Robots-Tag", "noindex").send(
  '<!doctype html><meta charset="utf-8"><title>404 — Streamza</title>' +
  '<meta name="robots" content="noindex">' +
  '<body style="margin:0;background:#000;color:#fff;font-family:-apple-system,Segoe UI,Roboto,sans-serif;text-align:center;padding:90px 20px">' +
  '<h1 style="font-size:64px;margin:0;color:#FF3B30">404</h1><p style="color:#9b9ba3">That page doesn\'t exist.</p>' +
  '<p><a href="/" style="color:#FF3B30;font-weight:700;text-decoration:none">← Back to Streamza</a> &nbsp;·&nbsp; <a href="/studio" style="color:#fff;text-decoration:none">Web Studio</a></p></body>'));

app.listen(PORT, "0.0.0.0", () => console.log(`Streamza Relay (${SLOT_COUNT} slots, ${TRIAL_MS / 60000}-min trial) listening on :${PORT}`));
