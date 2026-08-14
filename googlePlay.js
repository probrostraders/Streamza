/**
 * Google Play Developer API client — verifies Streamza Loop's subscription purchases server-side so a
 * purchase in the app can unlock the same `subscribers` entry Web Studio already reads (see server.js).
 *
 * Needs a service account key with "View financial data" access to this app, granted from Play Console
 * -> Setup -> API access (that flow also links/creates the Google Cloud project this key belongs to).
 * Point GOOGLE_PLAY_SERVICE_ACCOUNT_JSON at the downloaded key file's path (or
 * GOOGLE_PLAY_SERVICE_ACCOUNT_JSON_INLINE at the raw JSON contents) via env var — same pattern as the
 * other secrets in this project, never committed. Until that's set, PLAY_BILLING_ENABLED is false and
 * /billing/verify-purchase (server.js) just returns "not configured yet" rather than erroring.
 */
const fs = require("fs");
const { JWT } = require("google-auth-library");

const PACKAGE_NAME = process.env.ANDROID_PACKAGE_NAME || "com.streamza.loop";
const KEY_FILE = process.env.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON || "";
const KEY_JSON_INLINE = process.env.GOOGLE_PLAY_SERVICE_ACCOUNT_JSON_INLINE || "";

let credentials = null;
try {
  if (KEY_JSON_INLINE) credentials = JSON.parse(KEY_JSON_INLINE);
  else if (KEY_FILE && fs.existsSync(KEY_FILE)) credentials = JSON.parse(fs.readFileSync(KEY_FILE, "utf8"));
} catch (_) {}

const PLAY_BILLING_ENABLED = !!(credentials && credentials.client_email && credentials.private_key);

const client = PLAY_BILLING_ENABLED
  ? new JWT({
      email: credentials.client_email,
      key: credentials.private_key,
      scopes: ["https://www.googleapis.com/auth/androidpublisher"],
    })
  : null;

// Current state of a subscription purchase. Response includes subscriptionState (we care about
// SUBSCRIPTION_STATE_ACTIVE / SUBSCRIPTION_STATE_IN_GRACE_PERIOD) and lineItems[0].productId (which
// of the two products — single/multi — this purchase is for).
async function getSubscription(purchaseToken) {
  const url = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PACKAGE_NAME}/purchases/subscriptionsv2/tokens/${encodeURIComponent(purchaseToken)}`;
  const res = await client.request({ url });
  return res.data;
}

// Acknowledgement is still on the v1/legacy path even for subscriptionsv2-tracked purchases — Google
// has never introduced a v2 acknowledge endpoint. Must be called within 3 days of a fresh purchase or
// Google auto-refunds it; safe to call again on an already-acknowledged purchase (server.js swallows
// the resulting error rather than treating a redundant ack as a real failure).
async function acknowledgePurchase(productId, purchaseToken) {
  const url = `https://androidpublisher.googleapis.com/androidpublisher/v3/applications/${PACKAGE_NAME}/purchases/subscriptions/${encodeURIComponent(productId)}/tokens/${encodeURIComponent(purchaseToken)}:acknowledge`;
  await client.request({ url, method: "POST", data: {} });
}

module.exports = { PLAY_BILLING_ENABLED, PACKAGE_NAME, getSubscription, acknowledgePurchase };
