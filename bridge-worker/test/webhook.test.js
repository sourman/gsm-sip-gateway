import { describe, expect, it } from "vitest";
import worker, {
  verifyWebhook,
  base64Encode,
  base64Decode,
} from "../src/index.js";

// Svix-compatible HMAC signing. Reproduces what OpenAI's webhook sender does:
// the secret is the base64-decoded suffix after "whsec_", the signed string is
// "<webhook-id>.<timestamp>.<rawBody>", and the signature is base64(HMAC-SHA256).
// Keeping this local makes the tests deterministic without depending on the
// Svix library, and any drift between this helper and the verifier is itself a
// signal that the verification contract changed.
async function sign({ webhookId, timestamp, body, secret }) {
  const keyMaterial = secret.startsWith("whsec_")
    ? base64Decode(secret.slice(6))
    : new TextEncoder().encode(secret);
  const key = await crypto.subtle.importKey(
    "raw",
    keyMaterial,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"],
  );
  const signedPayload = `${webhookId}.${timestamp}.${body}`;
  const mac = await crypto.subtle.sign(
    "HMAC",
    key,
    new TextEncoder().encode(signedPayload),
  );
  const b64 = base64Encode(new Uint8Array(mac));
  return `v1,${b64}`;
}

function headers({ webhookId, timestamp, signature }) {
  return new Headers({
    "webhook-id": webhookId,
    "webhook-timestamp": String(timestamp),
    "webhook-signature": signature,
  });
}

// Synthetic test-only secrets — never use production whsec_ values here.
const SECRET = "whsec_dGVzdC13ZWJob29rLXNlY3JldC1rZXktMDEyMzQ1Njc4OTA=";
const ALT_SECRET = "whsec_YWx0ZXJuYXRlLXRlc3Qtc2VjcmV0LWtleS0wOTg3NjU0MzIx";
const BODY = JSON.stringify({
  type: "realtime.call.incoming",
  data: { id: "call_abc", call_id: "call_abc", sip_headers: [] },
});
const WEBHOOK_ID = "msg_wBx4kVJ5pXmNqL7tR3sZ";

function nowTs() {
  return Math.floor(Date.now() / 1000);
}

describe("verifyWebhook", () => {
  it("accepts a valid Svix signature using a whsec_ secret", async () => {
    const ts = nowTs();
    const signature = await sign({
      webhookId: WEBHOOK_ID,
      timestamp: ts,
      body: BODY,
      secret: SECRET,
    });
    const event = await verifyWebhook(
      BODY,
      headers({ webhookId: WEBHOOK_ID, timestamp: ts, signature }),
      SECRET,
    );
    expect(event.type).toBe("realtime.call.incoming");
    expect(event.data.call_id).toBe("call_abc");
  });

  it("rejects a signature produced with a different secret", async () => {
    const ts = nowTs();
    const signature = await sign({
      webhookId: WEBHOOK_ID,
      timestamp: ts,
      body: BODY,
      secret: ALT_SECRET,
    });
    await expect(
      verifyWebhook(
        BODY,
        headers({ webhookId: WEBHOOK_ID, timestamp: ts, signature }),
        SECRET,
      ),
    ).rejects.toThrow("no valid signature");
  });

  it("rejects a stale timestamp (>300s old)", async () => {
    const ts = nowTs() - 400;
    const signature = await sign({
      webhookId: WEBHOOK_ID,
      timestamp: ts,
      body: BODY,
      secret: SECRET,
    });
    await expect(
      verifyWebhook(
        BODY,
        headers({ webhookId: WEBHOOK_ID, timestamp: ts, signature }),
        SECRET,
      ),
    ).rejects.toThrow("timestamp out of tolerance");
  });

  it("rejects a tampered body even with a valid signature header", async () => {
    const ts = nowTs();
    const signature = await sign({
      webhookId: WEBHOOK_ID,
      timestamp: ts,
      body: BODY,
      secret: SECRET,
    });
    const tampered = BODY.replace("call_abc", "call_xyz");
    await expect(
      verifyWebhook(
        tampered,
        headers({ webhookId: WEBHOOK_ID, timestamp: ts, signature }),
        SECRET,
      ),
    ).rejects.toThrow("no valid signature");
  });

  it("rejects a base64url-encoded signature (current contract is standard base64)", async () => {
    // The verifier compares against standard base64 (with '+' and '/'). The
    // base64url alphabet ('-' and '_') differs only when the HMAC's base64 form
    // actually contains a special char, so pick an id whose signature does —
    // otherwise the rewrite is a no-op and the assertion is meaningless.
    const ts = nowTs();
    let webhookId = WEBHOOK_ID;
    let b64 = "";
    for (let attempt = 0; attempt < 16; attempt++) {
      const sig = await sign({
        webhookId,
        timestamp: ts,
        body: BODY,
        secret: SECRET,
      });
      b64 = sig.split(",")[1];
      if (b64.includes("+") || b64.includes("/")) break;
      webhookId = `${WEBHOOK_ID}_${attempt}`;
    }
    const b64url = b64.replace(/\+/g, "-").replace(/\//g, "_");
    // If OpenAI ever switches to base64url, this signed value stops matching and
    // verification must be updated deliberately, not silently.
    await expect(
      verifyWebhook(
        BODY,
        headers({ webhookId, timestamp: ts, signature: `v1,${b64url}` }),
        SECRET,
      ),
    ).rejects.toThrow("no valid signature");
  });
});

describe("worker fetch /twiml", () => {
  const env = {
    OPENAI_PROJECT_ID: "proj_Cy8aafQOE2xGPfAh45zFq0VG",
    TRUNK_DIAL_NUMBER: "+17402185427",
  };

  it("emits answerOnBridge=\"true\" on the Dial", async () => {
    const res = await worker.fetch(
      new Request("https://sip-webhook.loom.li/twiml"),
      env,
      { waitUntil() {} },
    );
    expect(res.status).toBe(200);
    const xml = await res.text();
    expect(xml).toContain('answerOnBridge="true"');
  });

  it("dials the trunk number from env.TRUNK_DIAL_NUMBER", async () => {
    const res = await worker.fetch(
      new Request("https://sip-webhook.loom.li/twiml"),
      env,
      { waitUntil() {} },
    );
    const xml = await res.text();
    expect(xml).toContain(`<Number>${env.TRUNK_DIAL_NUMBER}</Number>`);
  });
});

describe("worker fetch /outbound-test", () => {
  it("plays the sample MP3 on the PSTN leg", async () => {
    const res = await worker.fetch(
      new Request("https://sip-webhook.loom.li/outbound-test"),
      {},
      { waitUntil() {} },
    );
    expect(res.status).toBe(200);
    const xml = await res.text();
    expect(xml).toContain("<Play>https://demo.twilio.com/docs/classic.mp3</Play>");
  });
});

describe("worker fetch POST /", () => {
  it("returns 400 when a valid-signed event has data.id but no call_id", async () => {
    const env = {
      OPENAI_API_KEY: "sk-test",
      OPENAI_WEBHOOK_SECRET: SECRET,
    };
    const body = JSON.stringify({
      type: "realtime.call.incoming",
      data: { id: "call_abc" },
    });
    const ts = nowTs();
    const signature = await sign({
      webhookId: WEBHOOK_ID,
      timestamp: ts,
      body,
      secret: SECRET,
    });
    const res = await worker.fetch(
      new Request("https://sip-webhook.loom.li/", {
        method: "POST",
        headers: headers({
          webhookId: WEBHOOK_ID,
          timestamp: ts,
          signature,
        }),
        body,
      }),
      env,
      { waitUntil() {} },
    );
    // Verifies cleanly (signature is valid) but is rejected at the call_id
    // check — the dashboard "test webhook" shape sends data.id only.
    expect(res.status).toBe(400);
  });
});
