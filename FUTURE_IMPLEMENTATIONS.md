# Future Implementations

This document tracks planned features and architectural upgrades for the gsm2sip Gateway.

## SIP Server Mode (PBX/Registrar)

**Concept:**
Allow the Android app to act as a standalone SIP Server (PBX) on the local network. Instead of the app acting as a client connecting to an external Asterisk server, SIP softphones (like Zoiper, MicroSIP) connect _directly_ to the Android phone's IP address.

**Benefits:**

- **Zero External Infrastructure:** No need to host or maintain an external Asterisk/FreePBX server.
- **Plug and Play:** Users just install the app, connect Zoiper to the phone's Wi-Fi IP, and start making calls.
- **Lower Latency:** Audio and signaling stay entirely within the local network (LAN).

**Technical Requirements:**

1. **SIP Registrar Implementation**
   - Bind a UDP socket to port `5060` on the device's local IP.
   - Listen for `REGISTER` requests from SIP clients.
   - Validate credentials and store the client's IP and Port mapping.
   - Respond with `200 OK` and handle registration expirations.

2. **B2BUA (Back-to-Back User Agent)**
   - Act as the middleman for calls.
   - **Outbound (Zoiper → GSM):** Receive `INVITE` from Zoiper, respond with `100 Trying` / `183 Session Progress`, extract the destination number, command `GsmCallManager` to dial, and bridge the RTP.
   - **Inbound (GSM → Zoiper):** Upon receiving a GSM call, generate an `INVITE` request and send it to the registered Zoiper client. Handle the `180 Ringing` and `200 OK` responses to establish the bridge.

3. **RTP Relaying / Handling**
   - The app's `RtpSession` will need to bridge the audio between the Android hardware (Voice TX/RX) and the connected Zoiper client on the LAN.
   - Ensure `SDP` parsing correctly extracts the Zoiper client's RTP port and IP.

4. **UI Integration**
   - Add a toggle in the Settings tab: **"Mode: SIP Client (Asterisk)"** vs **"Mode: SIP Server (Standalone)"**.
   - In Server Mode, display the device's Local IP address prominently so the user knows what IP to enter into Zoiper.

**Challenges to Consider:**

- **Battery Optimization:** The app must maintain a WakeLock and run as a Foreground Service to keep port `5060` listening in the background without Android putting it to sleep.
- **Network Changes:** The phone's local IP might change if it reconnects to Wi-Fi. The app needs to handle IP changes gracefully and update its bindings.

Note this app is building by AI
