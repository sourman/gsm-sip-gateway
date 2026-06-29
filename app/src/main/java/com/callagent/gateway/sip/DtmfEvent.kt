package com.callagent.gateway.sip

import java.nio.ByteBuffer
import java.nio.ByteOrder

/**
 * RFC 4733 telephone-event RTP payload.
 *
 * Each DTMF event is sent as a 3-packet sequence on the negotiated RTP stream
 * using the telephone-event payload type (101 in our SDP offer):
 *
 *   1. "start" packet  — End-of-Event flag clear, volume + duration
 *   2. "start" packet  — identical retransmit (2× start packets)
 *   3. "end" packet    — End-of-Event flag SET, duration = full event length
 *
 * The 4-byte payload layout (RFC 4733 §2 / RFC 2833 §3.5):
 *
 *       0                   1                   2                   3
 *       0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1 2 3 4 5 6 7 8 9 0 1
 *      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *      |     event     |E|R| volume    |          duration             |
 *      +-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+-+
 *
 * - event (8 bits):  DTMF code 0–15 (digits 0-9, *, #, A-D)
 * - E (1 bit):       End-of-Event flag
 * - R (1 bit):       Reserved (must be 0)
 * - volume (6 bits): power level in dBm0, 0–63, clamped to [0,36]; typical 10
 * - duration (16 bits): event duration in RTP timestamp units (8 kHz → ms × 8)
 *
 * Timestamps: each event starts on a fresh timestamp; the start packets carry
 * the event's start timestamp and each subsequent packet's duration field grows
 * to reflect how long the event has been held.  The end packet keeps the same
 * start timestamp but carries the full final duration and sets the Marker bit.
 *
 * This file provides the pure encode/decode of one telephone-event payload and
 * a [Sequence] describing the 3-packet transmission.  RTP framing itself
 * (header, sequence number, SSRC) is the responsibility of RtpSession —
 * see [SipCall.consumeDtmfEvents] for the inter-thread contract.
 */
data class DtmfEvent(
    /** RFC 4733 event code (0–15). See [codeForDigit]. */
    val event: Int,
    /** End-of-Event flag. False for start packets, true for the final packet. */
    val endOfEvent: Boolean,
    /** Audio power level in dBm0 (0–36 clamped). Typical DTMF: 10. */
    val volume: Int = 10,
    /** Duration in RTP timestamp units (8 kHz clock → 1 ms = 8 units). */
    val duration: Int
) {
    /** Encode one telephone-event payload (4 bytes, big-endian). */
    fun encode(): ByteArray {
        val buf = ByteBuffer.allocate(4).order(ByteOrder.BIG_ENDIAN)
        val eFlag = if (endOfEvent) 0x80 else 0x00
        val vol = (volume and 0x3F)
        val secondByte = (eFlag or vol).toByte()
        buf.put(event.toByte())
        buf.put(secondByte)
        buf.putShort(duration.toShort())
        return buf.array()
    }

    companion object {
        /**
         * Decode one telephone-event payload (≥4 bytes).
         * Returns null if the payload is too short or malformed.
         */
        fun decode(payload: ByteArray): DtmfEvent? {
            if (payload.size < 4) return null
            val buf = ByteBuffer.wrap(payload, 0, 4).order(ByteOrder.BIG_ENDIAN)
            val event = buf.get().toInt() and 0xFF
            val b1 = buf.get().toInt() and 0xFF
            val endOfEvent = (b1 and 0x80) != 0
            val volume = b1 and 0x3F
            val duration = buf.short.toInt() and 0xFFFF
            return DtmfEvent(event, endOfEvent, volume, duration)
        }

        /** Map a DTMF digit character to its RFC 4733 event code. */
        fun codeForDigit(digit: Char): Int = when (digit) {
            in '0'..'9' -> digit - '0'
            '*' -> 10
            '#' -> 11
            'A', 'a' -> 12
            'B', 'b' -> 13
            'C', 'c' -> 14
            'D', 'd' -> 15
            else -> throw IllegalArgumentException("Not a DTMF digit: '$digit'")
        }

        /** Inverse of [codeForDigit]. Returns null for codes outside 0–15. */
        fun digitForCode(code: Int): Char? = when (code) {
            in 0..9 -> ('0' + code)
            10 -> '*'
            11 -> '#'
            12 -> 'A'
            13 -> 'B'
            14 -> 'C'
            15 -> 'D'
            else -> null
        }

        /**
         * Build the 3-packet RFC 4733 sequence for one DTMF digit.
         *
         * @param digit      the digit to send
         * @param durationMs event duration in milliseconds (default 200, per G.711 DTMF)
         * @param clockRate  RTP clock rate (8 kHz for telephone-event/8000)
         * @param volumeDbm0 power level 0–36 dBm0 (default 10)
         */
        fun sequenceFor(
            digit: Char,
            durationMs: Int = 200,
            clockRate: Int = 8000,
            volumeDbm0: Int = 10
        ): Sequence {
            val code = codeForDigit(digit)
            val totalDuration = (durationMs * clockRate / 1000).coerceAtLeast(1)
            val interimDuration = (totalDuration / 3).coerceAtLeast(1)
            // 2× start packets (end=0, growing duration) + 1× end packet (end=1, full duration)
            return Sequence(listOf(
                DtmfEvent(code, endOfEvent = false, volume = volumeDbm0, duration = interimDuration),
                DtmfEvent(code, endOfEvent = false, volume = volumeDbm0, duration = interimDuration * 2),
                DtmfEvent(code, endOfEvent = true,  volume = volumeDbm0, duration = totalDuration)
            ))
        }
    }

    /**
     * The ordered set of telephone-event payloads that make up one complete
     * DTMF tone, plus the marker bit policy for the RTP packets carrying them.
     */
    data class Sequence(val packets: List<DtmfEvent>) {
        init { require(packets.size == 3) { "RFC 4733 DTMF sequence is exactly 3 packets, got ${packets.size}" } }
        /** The RTP Marker bit MUST be set on the first packet of an event. */
        val markerOnFirst get() = true
        /** Each packet occupies one 20 ms RTP frame slot (telephone-event timing). */
        val frameIntervalMs get() = 20
        /** The RTP timestamp of the first packet; subsequent packets keep it. */
        val firstPacketTimestampDelta get() = 0
    }
}
