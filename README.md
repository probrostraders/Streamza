# Streamza — Website & Legal Pages

A complete, self-contained marketing + legal website for the **Streamza** Android app.
It satisfies what Google Play requires before you publish (a public **Privacy Policy URL**, plus
Terms, Support, and a **Data Deletion** page), and gives you a professional landing page.

No frameworks, no build step, no dependencies — just static HTML/CSS. Deploys to Vercel as-is.

**Owner:** Probros — Sohaib Yasin · Punjab, Pakistan · probrostraders@gmail.com
**Domain:** https://streamza.live

## Files

| File | Purpose |
|------|---------|
| `index.html` | Landing page (hero, features, platforms, how-it-works, FAQ) |
| `privacy.html` | **Privacy Policy** — the page Google Play *requires* |
| `terms.html` | Terms of Service (incl. subscription terms) |
| `support.html` | Help, FAQ, per-platform stream-key guide, contact |
| `data-deletion.html` | Data deletion instructions + request (Play wants a public URL) |
| `styles.css` | Shared dark theme matching the app |

---

## ✅ Already filled in
- Developer/company: **Probros** (operated by **Sohaib Yasin**)
- Location / governing law: **Punjab, Pakistan**
- Contact email: **probrostraders@gmail.com**
- Canonical domain: **https://streamza.live**

## Still to do before/after launch
1. **Google Play link** — in `index.html`, the "Get it on Google Play" buttons currently point to
   `#download` ("coming soon"). Once the app is live, replace them with
   `https://play.google.com/store/apps/details?id=com.streamapp`.
2. *(Optional, more professional)* Use **`support@streamza.live`** instead of the Gmail. Since you'll
   own the domain, set up free email forwarding (Cloudflare Email Routing or improvmx.com) to forward
   `support@streamza.live` → your Gmail, then search-replace `probrostraders@gmail.com` across the files.
3. *(Optional)* Decide branding — the site says **Streamza** (matches the app name on Play). If you
   rename the app to **Streamza**, tell me and I'll rebrand the whole site.

---

## Deploy: GitHub → Vercel → streamza.live

### 1. Push to GitHub
1. Create a free **GitHub** account → **New repository** (e.g. `streamza-site`), set it **Public**.
2. Upload everything in this `pro Stream` folder to the repo root (or use `git push`).
   - The files must be at the **repo root** (so `index.html` is at the top level).

### 2. Deploy on Vercel
1. Go to **vercel.com** → sign in with GitHub.
2. **Add New → Project** → import your `streamza-site` repo.
3. Framework preset: **Other** (it's a static site). **No build command, no output dir** needed.
4. Click **Deploy**. You instantly get a URL like `https://streamza-site.vercel.app`.

### 3. Connect your domain
1. Buy **streamza.live** (Namecheap, Porkbun, Cloudflare, etc.).
2. In Vercel → your project → **Settings → Domains** → add `streamza.live` (and `www.streamza.live`).
3. Vercel shows the DNS records to set — add them at your domain registrar (or point the nameservers
   to Vercel). HTTPS is automatic and free.
4. Done: your pages are live at `https://streamza.live/`, `https://streamza.live/privacy.html`, etc.

> Tip: keep editing on GitHub — every push auto-deploys to Vercel.

---

## What to paste into Google Play Console

After the domain is live:

| Play Console field | URL |
|--------------------|-----|
| **Privacy Policy** (App content → Privacy policy) | `https://streamza.live/privacy.html` |
| **Data deletion** (Data safety → "URL to request deletion") | `https://streamza.live/data-deletion.html` |
| **Store listing → Website** (optional) | `https://streamza.live/` |
| **Support email** | probrostraders@gmail.com |

Also link **Privacy Policy** from inside the app — the Settings screen has a "Privacy Policy" row;
point it at `https://streamza.live/privacy.html`.

---

## Keep the Privacy Policy accurate (important)

Google's **Data Safety** form must match this Privacy Policy. The current policy reflects an app that:
- has **no accounts**, collects **no personal data on a server**,
- sends your stream **directly** to the platform you choose,
- stores stream keys & settings **locally** on the device,
- uses **Cloudflare** only for the optional speed test, and
- uses **Google Play Billing + RevenueCat** for subscriptions.

➡️ **If you later add any SDK that collects data** (Firebase Analytics, Crashlytics, ads, server-based
push), you **must** update (1) the **Third-party services** + **What we collect** sections in
`privacy.html`, and (2) your **Data Safety** answers in Play Console.

---

## Preview locally
Double-click `index.html` — it opens in your browser and works offline.

© 2026 Streamza / Probros.
