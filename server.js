/**
 * Streamza Relay — upload a video, stream it to any RTMP/RTMPS destination via FFmpeg.
 *
 * Free-beta product backend:
 *  - one stream at a time (fits the free 1 GB micro; FFmpeg `-c copy`, no transcode → H.264+AAC source)
 *  - email is required to go live → that's our real-user / lead capture (no payment infra yet)
 *  - /waitlist captures "notify me about Pro" emails
 *  - /admin/leads (key-gated) lets the owner export the signup list
 *
 * Monetization later: flip on Paddle/Lemon Squeezy checkout, gate Pro limits by the saved email.
 */
const express = require("express");
const multer = require("multer");
const { spawn } = require("child_process");
const fs = require("fs");
const path = require("path");

const PORT = process.env.PORT || 3000;
const ADMIN_KEY = process.env.ADMIN_KEY || "streamza-admin";
const UPLOAD_DIR = path.join(__dirname, "uploads");
const DATA_DIR = path.join(__dirname, "data");
const LEADS_FILE = path.join(DATA_DIR, "leads.csv");
fs.mkdirSync(UPLOAD_DIR, { recursive: true });
fs.mkdirSync(DATA_DIR, { recursive: true });
if (!fs.existsSync(LEADS_FILE)) fs.writeFileSync(LEADS_FILE, "email,source,timestamp\n");

// Clear leftover uploads from a previous run (a restart kills any in-flight stream).
try { for (const f of fs.readdirSync(UPLOAD_DIR)) fs.unlinkSync(path.join(UPLOAD_DIR, f)); } catch (_) {}

const upload = multer({ dest: UPLOAD_DIR, limits: { fileSize: 4 * 1024 * 1024 * 1024 } }); // 4 GB

const app = express();
app.use(express.json());
app.use(express.static(path.join(__dirname, "public")));

const EMAIL_RE = /^[^\s@]+@[^\s@]+\.[^\s@]+$/;
function saveLead(email, source) {
  if (!email || !EMAIL_RE.test(email)) return false;
  try {
    const line = `${email.replace(/[",\n]/g, "")},${source},${new Date().toISOString()}\n`;
    fs.appendFileSync(LEADS_FILE, line);
  } catch (_) {}
  return true;
}

// ---- single-stream state ----
let proc = null;
let state = { running: false, startedAt: null, file: null, dest: null, loop: false, log: [] };
function log(line) { if (!line) return; state.log.push(line); if (state.log.length > 80) state.log.shift(); }
function rm(p) { try { fs.unlinkSync(p); } catch (_) {} }

app.post("/start", upload.single("video"), (req, res) => {
  const email = (req.body.email || "").trim();
  if (!EMAIL_RE.test(email)) { if (req.file) rm(req.file.path); return res.status(400).json({ error: "Enter a valid email to go live." }); }
  if (req.body.agree !== "true" && req.body.agree !== "on") { if (req.file) rm(req.file.path); return res.status(400).json({ error: "Please confirm you have the rights to stream this content." }); }
  if (proc) { if (req.file) rm(req.file.path); return res.status(409).json({ error: "The server is busy with another stream (one at a time during beta). Please try again shortly." }); }

  const file = req.file;
  const url = (req.body.rtmpUrl || "").trim().replace(/\/+$/, "");
  const key = (req.body.streamKey || "").trim();
  const loop = req.body.loop === "true" || req.body.loop === "on";
  if (!file) return res.status(400).json({ error: "No video file uploaded." });
  if (!/^rtmps?:\/\//i.test(url)) { rm(file.path); return res.status(400).json({ error: "RTMP URL must start with rtmp:// or rtmps://" }); }

  saveLead(email, "stream");
  const dest = key ? `${url}/${key}` : url;
  const args = ["-re"];
  if (loop) args.push("-stream_loop", "-1");
  args.push("-i", file.path, "-c", "copy", "-f", "flv", dest);

  state = { running: true, startedAt: Date.now(), file: file.originalname, dest: url, loop, log: [] };
  log(`Starting ${loop ? "(looping) " : ""}${file.originalname} -> ${url}`);
  proc = spawn("ffmpeg", args);
  proc.stderr.on("data", (d) => log(d.toString().trim().split("\n").pop()));
  proc.on("exit", (code) => { log(`FFmpeg stopped (exit ${code}).`); state.running = false; rm(file.path); proc = null; });
  res.json({ ok: true });
});

app.post("/stop", (req, res) => { if (proc) proc.kill("SIGINT"); res.json({ ok: true }); });

app.get("/status", (req, res) => {
  res.json({
    running: state.running, file: state.file, dest: state.dest, loop: state.loop,
    uptime: state.startedAt && state.running ? Math.floor((Date.now() - state.startedAt) / 1000) : 0,
    log: state.log.slice(-14),
  });
});

// "Notify me about Pro" / early access capture.
app.post("/waitlist", (req, res) => {
  const ok = saveLead((req.body.email || "").trim(), req.body.source || "waitlist");
  if (!ok) return res.status(400).json({ error: "Enter a valid email." });
  res.json({ ok: true });
});

// Owner-only: export the signup list. e.g. /admin/leads?key=YOUR_ADMIN_KEY
app.get("/admin/leads", (req, res) => {
  if ((req.query.key || "") !== ADMIN_KEY) return res.status(401).send("Unauthorized");
  res.type("text/plain").send(fs.existsSync(LEADS_FILE) ? fs.readFileSync(LEADS_FILE, "utf8") : "");
});

app.listen(PORT, "0.0.0.0", () => console.log(`Streamza Relay listening on :${PORT}`));
