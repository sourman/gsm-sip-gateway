package com.callagent.gateway.net

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertNotNull
import org.junit.Test
import java.lang.reflect.Method
import java.nio.ByteBuffer

/**
 * STUN client (RFC 5389) response parsing tests.
 *
 * [StunClient.parseResponse] and the field constants are private. We exercise
 * the parser via reflection so the production source is left untouched (the
 * parser is pure and side-effect free; only `discover()`/`queryServer` touch
 * the network).
 *
 * Test vector: RFC 5389 §6 example XOR-MAPPED-ADDRESS encoding for the
 * mapped endpoint 192.0.2.1:32853.
 *   magic cookie = 0x2112A442
 *   xorPort  = 0xA147  (32853 ^ 0x2112)
 *   xorAddr  = 0xE112A643  (0xC0000201 ^ 0x2112A442)
 */
class StunClientTest {

    private val MAGIC_COOKIE = 0x2112A442

    private fun parseResponseMethod(): Method {
        val m = StunClient::class.java.getDeclaredMethod(
            "parseResponse",
            ByteArray::class.java,
            Int::class.javaPrimitiveType,
            ByteArray::class.java
        )
        m.isAccessible = true
        return m
    }

    private fun parseResponse(data: ByteArray, txId: ByteArray): StunClient.StunResult? {
        @Suppress("UNCHECKED_CAST")
        val result = parseResponseMethod().invoke(StunClient, data, data.size, txId) as StunClient.StunResult?
        return result
    }

    /**
     * Build a STUN Binding Success Response carrying an XOR-MAPPED-ADDRESS
     * attribute for the given IP/port, with the supplied transaction ID.
     */
    private fun buildBindingSuccess(
        txId: ByteArray,
        ipParts: IntArray,
        port: Int
    ): ByteArray {
        val xorPort = port xor (MAGIC_COOKIE ushr 16)
        val ip = (ipParts[0] shl 24) or (ipParts[1] shl 16) or (ipParts[2] shl 8) or ipParts[3]
        val xorAddr = ip xor MAGIC_COOKIE

        // 20-byte header + 4-byte attr header + 8-byte XOR-MAPPED-ADDRESS value
        val bb = ByteBuffer.allocate(32)
        bb.putShort(0x0101)            // Binding Success Response
        bb.putShort(12)               // message length = 4 (attr hdr) + 8 (value)
        bb.putInt(MAGIC_COOKIE)
        bb.put(txId)                  // 12 bytes
        // XOR-MAPPED-ADDRESS attribute (type 0x0020)
        bb.putShort(0x0020)
        bb.putShort(8)                // attr value length
        bb.put(0)                     // reserved
        bb.put(0x01)                  // family IPv4
        bb.putShort(xorPort.toShort())
        bb.putInt(xorAddr)
        return bb.array()
    }

    @Test
    fun parseResponseXorMappedAddressRfc5389Example() {
        // RFC 5389 §6: a Binding Request from 192.0.2.1:32853 with magic
        // cookie 0x2112A442 produces XOR-MAPPED-ADDRESS with
        //   xorPort = 0x2835  (32853 ^ 0x2112)
        //   xorAddr = 0xE112A443  (0xC0A80001 ^ 0x2112A442)
        val txId = ByteArray(12) { (it + 1).toByte() }
        val response = buildBindingSuccess(txId, intArrayOf(192, 0, 2, 1), 32853)

        val result = parseResponse(response, txId)
        assertNotNull("parseResponse returned null for valid XOR-MAPPED-ADDRESS", result)
        assertEquals("192.0.2.1", result!!.publicIp)
        assertEquals(32853, result.publicPort)
    }

    @Test
    fun parseResponseRejectsWrongTransactionId() {
        val txId = ByteArray(12) { (it + 1).toByte() }
        val response = buildBindingSuccess(txId, intArrayOf(192, 0, 2, 1), 32853)
        val wrongTxId = ByteArray(12) { (it + 2).toByte() }
        assertNull(parseResponse(response, wrongTxId))
    }

    @Test
    fun parseResponseRejectsShortPacket() {
        assertNull(parseResponse(ByteArray(10), ByteArray(12)))
    }

    @Test
    fun parseResponseRejectsWrongMessageType() {
        val txId = ByteArray(12)
        val bb = ByteBuffer.allocate(20)
        bb.putShort(0x0001)            // not a Binding Success (0x0101)
        bb.putShort(0)
        bb.putInt(MAGIC_COOKIE)
        bb.put(txId)
        assertNull(parseResponse(bb.array(), txId))
    }

    @Test
    fun parseResponseRejectsWrongMagicCookie() {
        val txId = ByteArray(12)
        val bb = ByteBuffer.allocate(20)
        bb.putShort(0x0101)
        bb.putShort(0)
        bb.putInt(0xDEADBEEF.toInt())         // wrong cookie
        bb.put(txId)
        assertNull(parseResponse(bb.array(), txId))
    }

    @Test
    fun parseResponseFallsBackToMappedAddress() {
        // A non-XOR MAPPED-ADDRESS (type 0x0001) must also be parsed.
        val txId = ByteArray(12) { (it + 1).toByte() }
        val port = 5060
        val ipParts = intArrayOf(10, 0, 0, 1)

        val bb = ByteBuffer.allocate(32)
        bb.putShort(0x0101)
        bb.putShort(12)
        bb.putInt(MAGIC_COOKIE)
        bb.put(txId)
        bb.putShort(0x0001)            // MAPPED-ADDRESS
        bb.putShort(8)
        bb.put(0)
        bb.put(0x01)                    // IPv4
        bb.putShort(port.toShort())
        for (b in ipParts) bb.put(b.toByte())

        val result = parseResponse(bb.array(), txId)
        assertNotNull(result)
        assertEquals("10.0.0.1", result!!.publicIp)
        assertEquals(5060, result.publicPort)
    }

    @Test
    fun parseResponseSkipsUnknownAttributes() {
        // Unknown attribute before XOR-MAPPED-ADDRESS must be skipped
        // (with 4-byte padding alignment).
        val txId = ByteArray(12) { (it + 1).toByte() }
        val xorPort = 32853 xor (MAGIC_COOKIE ushr 16)
        val ip = (192 shl 24) or (0 shl 16) or (2 shl 8) or 1
        val xorAddr = ip xor MAGIC_COOKIE

        val bb = ByteBuffer.allocate(20 + 8 + 4 + 4 + 8) // header + unknown(attr) + xor attr
        bb.putShort(0x0101)
        bb.putShort(24)                 // 8 (unknown) + 4 (pad?) + 12 (xor attr)
        bb.putInt(MAGIC_COOKIE)
        bb.put(txId)
        // Unknown attribute: type 0x9999, length 5 (needs 3 bytes padding)
        bb.putShort(0x9999.toShort())
        bb.putShort(5)
        bb.put(byteArrayOf(0x01, 0x02, 0x03, 0x04, 0x05))
        // padding to 8 bytes (5 + 3)
        bb.put(byteArrayOf(0x00, 0x00, 0x00))
        // XOR-MAPPED-ADDRESS
        bb.putShort(0x0020)
        bb.putShort(8)
        bb.put(0)
        bb.put(0x01)
        bb.putShort(xorPort.toShort())
        bb.putInt(xorAddr)

        val result = parseResponse(bb.array(), txId)
        assertNotNull(result)
        assertEquals("192.0.2.1", result!!.publicIp)
        assertEquals(32853, result.publicPort)
    }
}