/**
 * Thin wrapper around the Cloudflare R2 (S3-compatible) bucket used for Streamza Loop's video uploads.
 * Bytes flow phone -> R2 directly via presigned URLs — this module only ever orchestrates (creates
 * multipart uploads, presigns URLs, lists/deletes objects); it never touches the video bytes themselves,
 * so none of it costs the small Oracle VM any disk or bandwidth. See the Phase 2 plan for the full
 * rationale (android/streamza-loop is the only writer today — Web Studio keeps using local disk).
 */
const {
  S3Client,
  CreateMultipartUploadCommand,
  UploadPartCommand,
  CompleteMultipartUploadCommand,
  AbortMultipartUploadCommand,
  GetObjectCommand,
  PutObjectCommand,
  DeleteObjectCommand,
  ListObjectsV2Command,
} = require("@aws-sdk/client-s3");
const { getSignedUrl } = require("@aws-sdk/s3-request-presigner");

const ACCOUNT_ID = process.env.R2_ACCOUNT_ID || "";
const ACCESS_KEY_ID = process.env.R2_ACCESS_KEY_ID || "";
const SECRET_ACCESS_KEY = process.env.R2_SECRET_ACCESS_KEY || "";
const BUCKET = process.env.R2_BUCKET || "";

const R2_ENABLED = !!(ACCOUNT_ID && ACCESS_KEY_ID && SECRET_ACCESS_KEY && BUCKET);

const client = R2_ENABLED
  ? new S3Client({
      region: "auto",
      endpoint: `https://${ACCOUNT_ID}.r2.cloudflarestorage.com`,
      credentials: { accessKeyId: ACCESS_KEY_ID, secretAccessKey: SECRET_ACCESS_KEY },
    })
  : null;

async function createMultipart(key, contentType) {
  const r = await client.send(new CreateMultipartUploadCommand({ Bucket: BUCKET, Key: key, ContentType: contentType || "video/mp4" }));
  return r.UploadId;
}

async function presignPartUrl(key, uploadId, partNumber) {
  return getSignedUrl(
    client,
    new UploadPartCommand({ Bucket: BUCKET, Key: key, UploadId: uploadId, PartNumber: partNumber }),
    { expiresIn: 3600 },
  );
}

async function completeMultipart(key, uploadId, parts) {
  await client.send(
    new CompleteMultipartUploadCommand({
      Bucket: BUCKET,
      Key: key,
      UploadId: uploadId,
      MultipartUpload: { Parts: parts.map((p) => ({ ETag: p.etag, PartNumber: Number(p.partNumber) })) },
    }),
  );
}

async function abortMultipart(key, uploadId) {
  try {
    await client.send(new AbortMultipartUploadCommand({ Bucket: BUCKET, Key: key, UploadId: uploadId }));
  } catch (_) {}
}

// Default 25h expiry — must comfortably outlive SLOT_MS (24h) since a looping stream re-reads the
// same presigned URL for the slot's whole lifetime, including after an ffmpeg reconnect hours in.
async function presignGetUrl(key, expiresInSeconds = 90000) {
  return getSignedUrl(client, new GetObjectCommand({ Bucket: BUCKET, Key: key }), { expiresIn: expiresInSeconds });
}

async function deleteObject(key) {
  try {
    await client.send(new DeleteObjectCommand({ Bucket: BUCKET, Key: key }));
  } catch (_) {}
}

// Single-shot upload for a file the server already has in full on local disk (Web Studio's browser
// upload lands here first, same as always — this is the opportunistic "promote it to R2" step that
// runs afterward, only when there's budget for it; see r2HasBudget() in server.js). No multipart
// dance needed since there's nothing to chunk from the client side at this point.
async function uploadFile(key, bodyStream, size, contentType) {
  await client.send(new PutObjectCommand({ Bucket: BUCKET, Key: key, Body: bodyStream, ContentLength: size, ContentType: contentType || "video/mp4" }));
}

async function listAllKeys() {
  return (await listAllObjects()).map((o) => o.key);
}

// Same listing, but with sizes — used to both sweep orphans and recompute the true total bytes stored,
// so r2UsedBytes (server.js) self-heals every hour instead of slowly drifting from reality.
async function listAllObjects() {
  const objects = [];
  let token;
  do {
    const r = await client.send(new ListObjectsV2Command({ Bucket: BUCKET, ContinuationToken: token }));
    (r.Contents || []).forEach((o) => objects.push({ key: o.Key, size: o.Size || 0 }));
    token = r.IsTruncated ? r.NextContinuationToken : undefined;
  } while (token);
  return objects;
}

module.exports = {
  R2_ENABLED,
  createMultipart,
  presignPartUrl,
  completeMultipart,
  abortMultipart,
  presignGetUrl,
  deleteObject,
  uploadFile,
  listAllKeys,
  listAllObjects,
};
