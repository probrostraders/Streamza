/**
 * Streamza Relay — 5-slot "pay-per-slot" streaming relay (upload a video -> RTMP via FFmpeg).
 *
 * Model: 5 concurrent slots. A user claims the first free slot and streams for 24 hours, then the
 * slot auto-expires (stream stops, slot frees) and they're prompted to renew. Each claim returns a
 * private manage-token (stored in the browser) used to monitor/stop/renew that slot.
 *
 * Payment: BETA_FREE=true means anyone with an email can claim a slot (free beta). When your Lemon
 * Squeezy / Paddle account is ready, set BETA_FREE=false and implement verifyPayment() to check the
 * order/license — that's the only change needed to turn on the $1 / 24h paywall.
 */
const express = require("express");
const multer = require("multer");
const { spawn } = require("child_process");
const crypto = require("crypto");
const fs = require("fs");
const path = require("path");

const PORT = process.env.PORT || 3000;
const ADMIN_KEY = process.env.ADMIN_KEY || "streamza-admin";
const SLOT_COUNT = 5;
const SLOT_MS = 24 * 60 * 60 * 1000; // 24 hours
const BETA_FREE = true;              // <-- flip to false to require the $1 payment (verifyPayment)

const UPLOAD_DIR = path.join(__dirname, "uploads");
const DATA_DIR = path.join(__dirname, "data");
const LEADS_FILE = path.join(DATA_DIR, "leads.csv");
fs.mkdirSync(UPLOAD_DIR, { recursive: true });
fs.mkdirSync(DATA_DIR, { recursive: true });
if (!fs.existsSync(LEADS_FILE)) fs.writeFileSync(LEADS_FILE, "email,source,timestamp\n");
try { for (const f of fs.readdirSync(UPLOAD_DIR)) fs.unlinkSync(path.join(UPLOAD_DIR, f)); } catch (_) {}

const upload = multer({ dest: UPLOAD_DIR, limits: { fileSize: 4 * 1024 * 1024 * 1024 } });
const app = express();
app.use(express.json());
app.use(express.static(path.join(__dirname, "public")));

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
function saveLead(email, source) {
  if (!EMAIL_RE.test(email || "")) return false;
  try { fs.appendFileSync(LEADS_FILE, `${email.replace(/[",\n]/g, "")},${source},${new Date().toISOString()}\n`); } catch (_) {}
  return true;
}
function rm(p) { try { fs.unlinkSync(p); } catch (_) {} }

// ---- 5 slots ----
const slots = Array.from({ length: SLOT_COUNT }, (_, i) => ({
  id: i + 1, busy: false, email: null, dest: null, file: null, filePath: null,
  startedAt: 0, expiresAt: 0, proc: null, token: null, log: [],
}));
function slog(s, line) { if (!line) return; s.log.push(line); if (s.log.length > 60) s.log.shift(); }
function release(s) {
  if (s.filePath) rm(s.filePath);
  Object.assign(s, { busy: false, email: null, dest: null, file: null, filePath: null, startedAt: 0, expiresAt: 0, proc: null, token: null, log: [] });
}

// expiry watchdog: stop+free any slot past its 24h
setInterval(() => {
  const now = Date.now();
  for (const s of slots) {
    if (s.busy && s.expiresAt && now > s.expiresAt) {
      slog(s, "Slot expired (24h) — stopping. Renew to continue.");
      try { if (s.proc) s.proc.kill("SIGINT"); else release(s); } catch (_) { release(s); }
    }
  }
}, 20000);

// Payment hook — replace with a real Lemon Squeezy/Paddle order/license check when live.
function verifyPayment(req) {
  if (BETA_FREE) return true;
  // TODO: validate req.body.license / order id against Lemon Squeezy API, ensure unused, etc.
  return false;
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

app.post("/start", upload.single("video"), (req, res) => {
  const email = (req.body.email || "").trim();
  if (!EMAIL_RE.test(email)) { if (req.file) rm(req.file.path); return res.status(400).json({ error: "Enter a valid email to claim a slot." }); }
  if (req.body.agree !== "true" && req.body.agree !== "on") { if (req.file) rm(req.file.path); return res.status(400).json({ error: "Please confirm you have the rights to stream this content." }); }
  if (!verifyPayment(req)) { if (req.file) rm(req.file.path); return res.status(402).json({ error: "Payment required for a 24-hour slot." }); }

  const slot = slots.find((s) => !s.busy);
  if (!slot) { if (req.file) rm(req.file.path); return res.status(409).json({ error: "All 5 slots are full right now. Please wait for one to free up." }); }

  const file = req.file;
  const url = (req.body.rtmpUrl || "").trim().replace(/\/+$/, "");
  const key = (req.body.streamKey || "").trim();
  const loop = req.body.loop === "true" || req.body.loop === "on";
  if (!file) return res.status(400).json({ error: "No video file uploaded." });
  if (!/^rtmps?:\/\//i.test(url)) { rm(file.path); return res.status(400).json({ error: "RTMP URL must start with rtmp:// or rtmps://" }); }

  saveLead(email, "stream");
  const token = crypto.randomBytes(12).toString("hex");
  const dest = key ? `${url}/${key}` : url;
  const args = ["-re"];
  if (loop) args.push("-stream_loop", "-1");
  args.push("-i", file.path, "-c", "copy", "-f", "flv", dest);

  Object.assign(slot, {
    busy: true, email, dest: url, file: file.originalname, filePath: file.path,
    startedAt: Date.now(), expiresAt: Date.now() + SLOT_MS, token, log: [],
  });
  slog(slot, `Slot ${slot.id} claimed — streaming ${loop ? "(looping) " : ""}${file.originalname}`);
  slot.proc = spawn("ffmpeg", args);
  slot.proc.stderr.on("data", (d) => slog(slot, d.toString().trim().split("\n").pop()));
  slot.proc.on("exit", (code) => { slog(slot, `FFmpeg stopped (exit ${code}).`); release(slot); });
  res.json({ ok: true, slot: slot.id, token, expiresAt: slot.expiresAt });
});

app.post("/stop", (req, res) => {
  const t = req.body.token || req.query.token;
  const s = slots.find((x) => x.token === t && x.busy);
  if (s) { try { s.proc ? s.proc.kill("SIGINT") : release(s); } catch (_) { release(s); } }
  res.json({ ok: true });
});

// owner's slot detail (by token)
app.get("/status", (req, res) => {
  const s = slots.find((x) => x.token === (req.query.token || "") && x.busy);
  if (!s) return res.json({ running: false });
  res.json({
    running: true, slot: s.id, file: s.file, dest: s.dest,
    uptime: Math.floor((Date.now() - s.startedAt) / 1000),
    secondsLeft: Math.max(0, Math.floor((s.expiresAt - Date.now()) / 1000)),
    log: s.log.slice(-14),
  });
});

app.post("/waitlist", (req, res) => {
  const ok = saveLead((req.body.email || "").trim(), req.body.source || "waitlist");
  if (!ok) return res.status(400).json({ error: "Enter a valid email." });
  res.json({ ok: true });
});

app.get("/admin/leads", (req, res) => {
  if ((req.query.key || "") !== ADMIN_KEY) return res.status(401).send("Unauthorized");
  res.type("text/plain").send(fs.existsSync(LEADS_FILE) ? fs.readFileSync(LEADS_FILE, "utf8") : "");
});

app.listen(PORT, "0.0.0.0", () => console.log(`Streamza Relay (5 slots) listening on :${PORT}`));
