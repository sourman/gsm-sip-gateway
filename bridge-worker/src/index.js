/**
 * OpenAI Realtime SIP Connector webhook.
 *
 * Twilio Elastic SIP Trunk → sip:proj_<ID>@sip.api.openai.com (native SIP, no media hop).
 * OpenAI fires `realtime.call.incoming` to this webhook; we verify the signature,
 * accept the call with a realtime session config, then attach a monitoring WebSocket
 * to keep the session alive and log conversation events.
 */

const INSTRUCTIONS =
  "You are Alex, a friendly test endpoint for a GSM-to-SIP gateway. " +
  "You are on a long-lived call used to verify bidirectional audio. " +
  "Greet the caller briefly, then converse naturally. If the caller is silent, " +
  "occasionally prompt them. Keep responses short. Do not hang up.";

const GREETING = "Hello! This is the OpenAI Realtime test endpoint. Go ahead, I'm listening.";

const worker = {
  async fetch(request, env, ctx) {
    const url = new URL(request.url);

    if (request.method === "GET" && url.pathname === "/health") {
      return new Response("ok\n", { status: 200 });
    }

    // TwiML for outbound PSTN test: Twilio dials the gateway SIM and plays a
    // known MP3 on the PSTN leg so we can verify Twilio → GSM → SIP → OpenAI.
    if (request.method === "GET" && url.pathname === "/outbound-test") {
      const mp3 = `https://${url.host}/sample-speech-1m.mp3`;
      const twiml =
        `<?xml version="1.0" encoding="UTF-8"?>\n` +
        `<Response>\n` +
        `  <Play>${mp3}</Play>\n` +
        `  <Pause length="5"/>\n` +
        `</Response>`;
      return new Response(twiml, {
        status: 200,
        headers: { "Content-Type": "application/xml" },
      });
    }

    // TwiML returned to Twilio when the SIP domain receives an inbound INVITE.
    // Bridges the call to the OpenAI Realtime SIP connector via the Elastic SIP
    // Trunk, with dual-channel call recording preserved for evidence.
    //
    // answerOnBridge="true": with a SIP inbound leg Twilio answers the gateway
    // immediately by default and plays ringback while the <Dial> target rings —
    // which leaked a ringback tone to the caller right after the AI greeting.
    // Deferring the 200 OK until the OpenAI leg actually answers suppresses that
    // spurious ringback so the call stays bridged to the agent with silence-free
    // audio once connected.
    if (request.method === "GET" && url.pathname === "/twiml") {
      const dialNumber = env.TRUNK_DIAL_NUMBER;
      const callback = `https://${url.host}/recording`;
      const twiml =
        `<?xml version="1.0" encoding="UTF-8"?>\n` +
        `<Response>\n` +
        `  <Dial answerOnBridge="true" callerId="${dialNumber}" record="record-from-answer-dual" recordingStatusCallback="${callback}" trim="do-not-trim">\n` +
        `    <Number>${dialNumber}</Number>\n` +
        `  </Dial>\n` +
        `</Response>`;
      return new Response(twiml, {
        status: 200,
        headers: { "Content-Type": "application/xml" },
      });
    }

    // Recording status callback: log the recording URL for evidence collection.
    if (request.method === "POST" && url.pathname === "/recording") {
      const form = await request.formData();
      const recUrl = form.get("RecordingUrl");
      const callSid = form.get("CallSid");
      const duration = form.get("RecordingDuration");
      console.log(`Recording ready CallSid=${callSid} dur=${duration}s url=${recUrl}`);
      return new Response("", { status: 204 });
    }

    // OpenAI realtime.call.incoming webhook handler below.
    if (request.method !== "POST") {
      return new Response("Method Not Allowed", { status: 405 });
    }

    const required = ["OPENAI_API_KEY", "OPENAI_WEBHOOK_SECRET"];
    const missing = required.filter((k) => !env[k]);
    if (missing.length) {
      console.error("Missing secrets:", missing.join(","));
      return new Response("Server misconfigured", { status: 500 });
    }

    const rawBody = await request.text();

    let event;
    try {
      event = await verifyWebhook(rawBody, request.headers, env.OPENAI_WEBHOOK_SECRET);
    } catch (err) {
      console.error("Signature verification failed:", err.message, {
        "webhook-id": request.headers.get("webhook-id"),
        "webhook-timestamp": request.headers.get("webhook-timestamp"),
        "has-signature": request.headers.get("webhook-signature") !== null,
        "body-len": rawBody.length,
      });
      return new Response("Invalid signature", { status: 400 });
    }

    if (event.type !== "realtime.call.incoming") {
      console.log("Ignoring event type:", event.type);
      return new Response("", { status: 200 });
    }

    const callId = event.data?.call_id;
    if (!callId) {
      console.error("No call_id in event:", JSON.stringify(event));
      return new Response("No call_id", { status: 400 });
    }

    console.log(`Incoming call call_id=${callId} from=${JSON.stringify(event.data?.sip_headers?.find((h) => h.name === "From")?.value)}`);

    const acceptBody = {
      type: "realtime",
      model: env.REALTIME_MODEL || "gpt-realtime-2",
      instructions: INSTRUCTIONS,
      audio: {
        output: { voice: env.REALTIME_VOICE || "alloy" },
      },
    };

    const acceptResp = await fetch(
      `https://api.openai.com/v1/realtime/calls/${encodeURIComponent(callId)}/accept`,
      {
        method: "POST",
        headers: {
          Authorization: `Bearer ${env.OPENAI_API_KEY}`,
          "Content-Type": "application/json",
        },
        body: JSON.stringify(acceptBody),
      }
    );

    if (!acceptResp.ok) {
      const text = await acceptResp.text().catch(() => "");
      console.error(`Accept failed ${acceptResp.status} ${acceptResp.statusText}: ${text}`);
      return new Response("Accept failed", { status: 502 });
    }

    console.log(`Accepted call_id=${callId}; attaching monitor WebSocket in 1s`);
    ctx.waitUntil(monitorSession(callId, env));

    return new Response("", { status: 200 });
  },
};

async function monitorSession(callId, env) {
  await new Promise((r) => setTimeout(r, 1000));
  const wsUrl = `wss://api.openai.com/v1/realtime?call_id=${encodeURIComponent(callId)}`;
  let ws;
  try {
    ws = new WebSocket(wsUrl, [
      "realtime",
      "openai-insecure-api-key." + env.OPENAI_API_KEY,
      "openai-beta.realtime-v1",
    ]);
  } catch (err) {
    console.error("WebSocket ctor failed:", err.message);
    return;
  }

  let greeted = false;
  ws.addEventListener("open", () => {
    console.log(`WS open call_id=${callId}`);
    ws.send(JSON.stringify({
      type: "response.create",
      response: { instructions: `Say to the user: ${GREETING}` },
    }));
    greeted = true;
  });

  ws.addEventListener("message", (e) => {
    try {
      const msg = JSON.parse(e.data);
      const t = msg.type || "";
      if (
        t.startsWith("response.audio") ||
        t.startsWith("conversation.item") ||
        t.includes("transcription") ||
        t.startsWith("input_audio")
      ) {
        console.log(`WS evt call_id=${callId} type=${t} ${JSON.stringify(msg).slice(0, 300)}`);
      } else if (t === "error") {
        console.error(`WS error evt: ${JSON.stringify(msg).slice(0, 500)}`);
      }
    } catch (_) {}
  });

  ws.addEventListener("error", (e) => {
    console.error(`WS error call_id=${callId}: ${e?.message || "unknown"}`);
  });

  ws.addEventListener("close", (e) => {
    console.log(`WS closed call_id=${callId} code=${e.code} reason=${e.reason}`);
  });
}

async function verifyWebhook(rawBody, headers, secret) {
  const webhookId = headers.get("webhook-id");
  const webhookTimestamp = headers.get("webhook-timestamp");
  const webhookSignature = headers.get("webhook-signature");

  if (!webhookId || !webhookTimestamp || !webhookSignature) {
    throw new Error("missing webhook headers");
  }

  const now = Math.floor(Date.now() / 1000);
  const ts = Number(webhookTimestamp);
  if (!Number.isFinite(ts) || Math.abs(now - ts) > 300) {
    throw new Error("webhook timestamp out of tolerance");
  }

  const signedPayload = `${webhookId}.${webhookTimestamp}.${rawBody}`;

  const keyMaterial = secret.startsWith("whsec_")
    ? base64Decode(secret.slice(6))
    : new TextEncoder().encode(secret);
  const key = await crypto.subtle.importKey(
    "raw",
    keyMaterial,
    { name: "HMAC", hash: "SHA-256" },
    false,
    ["sign"]
  );
  const expected = await crypto.subtle.sign("HMAC", key, new TextEncoder().encode(signedPayload));
  const expectedB64 = base64Encode(new Uint8Array(expected));

  const sigs = webhookSignature.split(" ").filter(Boolean);
  const ok = sigs.some((sig) => {
    const [version, b64] = sig.split(",");
    return version === "v1" && constantTimeEqual(b64, expectedB64);
  });
  if (!ok) throw new Error("no valid signature");

  return JSON.parse(rawBody);
}

function base64Encode(bytes) {
  let s = "";
  for (const b of bytes) s += String.fromCharCode(b);
  return btoa(s);
}

function base64Decode(str) {
  const binary = atob(str);
  const bytes = new Uint8Array(binary.length);
  for (let i = 0; i < binary.length; i++) bytes[i] = binary.charCodeAt(i);
  return bytes;
}

function constantTimeEqual(a, b) {
  if (a.length !== b.length) return false;
  let diff = 0;
  for (let i = 0; i < a.length; i++) diff |= a.charCodeAt(i) ^ b.charCodeAt(i);
  return diff === 0;
}

export default worker;

export {
  verifyWebhook,
  base64Encode,
  base64Decode,
  constantTimeEqual,
};