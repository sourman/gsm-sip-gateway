package com.callagent.gateway.sip

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * SDP offer and codec-selector tests.
 *
 * Pins two pieces of the SDP negotiation path:
 *  1. The SDP we offer (built by [SipBuilder] via reflection of its private
 *     `buildSdp`) advertises PCMU (PT 0) so μ-law trunks can negotiate it.
 *  2. The remote-answer codec selector ([SipMessage.sdpPreferredPayloadType])
 *     picks PCMA first, PCMU second, G.722 last — so a μ-law-only remote
 *     answers PT 0 instead of falling back to G.722.
 *
 * The PCMU codec path in [com.callagent.gateway.rtp.RtpSession] only becomes
 * reachable once both halves are correct, so these tests guard against the
 * "PCMU fix is dead code" regression.
 */
class SdpBuilderTest {

    /** Build a SIP message whose body carries the given SDP, via the parser. */
    private fun messageWithSdp(sdp: String): SipMessage {
        val raw =
            "SIP/2.0 200 OK\r\n" +
                "Via: SIP/2.0/UDP 127.0.0.1:5060\r\n" +
                "Call-ID: 1\r\n\r\n" +
                sdp
        return SipMessage.parse(raw)!!
    }

    /**
     * Exercise SipBuilder.buildSdp indirectly through invite(), which is the
     * only public function that embeds an SDP offer in its body.
     */
    private fun offerSdp(): String {
        val invite = SipBuilder.invite(
            targetUri = "sip:agent@pbx.example.com",
            username = "gateway",
            domain = "pbx.example.com",
            localIp = "192.0.2.10",
            localPort = 5060,
            callId = "call-1",
            cseq = 1,
            localRtpPort = 5004
        )
        // Body is everything after the blank line separating headers.
        return invite.substringAfter("\r\n\r\n")
    }

    // ---- SDP offer -------------------------------------------------------

    @Test
    fun offerIncludesPcmuInMediaLine() {
        val sdp = offerSdp()
        val mLine = sdp.lineSequence().firstOrNull { it.startsWith("m=audio") }
        assertTrue("m=audio line missing: $sdp", mLine != null)
        // m=audio <port> RTP/AVP 0 8 9 101 — PT 0 must appear as a token.
        val tokens = mLine!!.split(" ")
        assertTrue(
            "m=audio must list payload type 0 (PCMU); got: $mLine",
            tokens.contains("0")
        )
    }

    @Test
    fun offerListsPcmuFirstAmongAudioCodecs() {
        val sdp = offerSdp()
        val mLine = sdp.lineSequence().first { it.startsWith("m=audio") }
        // Format: m=audio <port> RTP/AVP <pt0> <pt1> ...
        val audioPts = mLine.split(" ").drop(3)
        assertEquals(
            "PCMU (0) must be the first offered audio codec so offer order " +
                "prefers it on servers that respect ordering; got: $mLine",
            "0",
            audioPts.first()
        )
    }

    @Test
    fun offerIncludesRtpmapForPcmu() {
        val sdp = offerSdp()
        assertTrue(
            "SDP must contain a=rtpmap:0 PCMU/8000; got:\n$sdp",
            sdp.contains("a=rtpmap:0 PCMU/8000")
        )
    }

    @Test
    fun offerStillIncludesPcmaG722AndTelephoneEvent() {
        val sdp = offerSdp()
        assertTrue(sdp.contains("a=rtpmap:8 PCMA/8000"))
        assertTrue(sdp.contains("a=rtpmap:9 G722/8000"))
        assertTrue(sdp.contains("a=rtpmap:101 telephone-event/8000"))
        assertTrue(sdp.contains("a=fmtp:101 0-16"))
    }

    // ---- Codec selector --------------------------------------------------

    @Test
    fun selectorPrefersPcmaWhenBothPcmaAndPcmuOffered() {
        val msg = messageWithSdp(
            "v=0\r\n" +
                "c=IN IP4 192.0.2.1\r\n" +
                "m=audio 4000 RTP/AVP 0 8 9 101\r\n"
        )
        assertEquals(8, msg.sdpPreferredPayloadType)
    }

    @Test
    fun selectorReturnsPcmuWhenRemoteAnswersMuLawOnly() {
        // Regression guard: a North-American μ-law-only trunk answering PT 0
        // must negotiate PCMU (0), NOT silently fall back to G.722 (9).
        val msg = messageWithSdp(
            "v=0\r\n" +
                "c=IN IP4 192.0.2.1\r\n" +
                "m=audio 4000 RTP/AVP 0 101\r\n"
        )
        assertEquals(0, msg.sdpPreferredPayloadType)
    }

    @Test
    fun selectorReturnsG722WhenOnlyWidebandOffered() {
        val msg = messageWithSdp(
            "v=0\r\n" +
                "c=IN IP4 192.0.2.1\r\n" +
                "m=audio 4000 RTP/AVP 9 101\r\n"
        )
        assertEquals(9, msg.sdpPreferredPayloadType)
    }

    @Test
    fun selectorDoesNotCrashWhenNoKnownCodecOffered() {
        // No G.711 / G.722 present — selector must not throw and must return a
        // stable PT (the documented PCMA default) rather than a silent G.722
        // fallback.
        val msg = messageWithSdp(
            "v=0\r\n" +
                "c=IN IP4 192.0.2.1\r\n" +
                "m=audio 4000 RTP/AVP 101\r\n"
        )
        assertEquals(8, msg.sdpPreferredPayloadType)
    }
}
