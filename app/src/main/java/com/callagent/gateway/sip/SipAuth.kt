package com.callagent.gateway.sip

import java.security.MessageDigest

/**
 * SIP Digest Authentication (RFC 2617).
 * Ported from the Python SIP implementation.
 */
object SipAuth {

    /** Parse WWW-Authenticate header parameters */
    fun parseChallenge(msg: SipMessage): AuthParams? {
        val authHeader = msg.header("www-authenticate") ?: msg.header("proxy-authenticate")
            ?: return null

        if (!authHeader.lowercase().startsWith("digest")) return null

        val params = mutableMapOf<String, String>()
        // Strip "Digest " prefix (case-insensitive)
        val digestIdx = authHeader.lowercase().indexOf("digest")
        val paramStr = if (digestIdx >= 0) authHeader.substring(digestIdx + 6).trim() else return null
        paramStr.split(",")
            .forEach { pair ->
                val eq = pair.indexOf('=')
                if (eq > 0) {
                    val key = pair.substring(0, eq).trim().lowercase()
                    val value = pair.substring(eq + 1).trim().trim('"')
                    params[key] = value
                }
            }

        val realm = params["realm"] ?: return null
        val nonce = params["nonce"] ?: return null
        return AuthParams(realm, nonce, params["opaque"], params["qop"])
    }

    /** Build Authorization header for REGISTER */
    fun buildAuthHeader(
        method: String,
        uri: String,
        username: String,
        password: String,
        params: AuthParams,
        isProxyAuth: Boolean = false
    ): String {
        val ha1 = md5("$username:${params.realm}:$password")
        val ha2 = md5("$method:$uri")

        val headerName = if (isProxyAuth) "Proxy-Authorization" else "Authorization"

        val response = if (params.qop != null) {
            val cnonce = md5(System.nanoTime().toString()).substring(0, 16)
            val nc = "00000001"
            md5("$ha1:${params.nonce}:$nc:$cnonce:${params.qop}:$ha2").let { resp ->
                return buildString {
                    append("$headerName: Digest username=\"$username\", ")
                    append("realm=\"${params.realm}\", ")
                    append("nonce=\"${params.nonce}\", ")
                    append("uri=\"$uri\", ")
                    append("qop=${params.qop}, ")
                    append("nc=$nc, ")
                    append("cnonce=\"$cnonce\", ")
                    append("response=\"$resp\", ")
                    append("algorithm=MD5")
                    if (params.opaque != null) append(", opaque=\"${params.opaque}\"")
                    append("\r\n")
                }
            }
        } else {
            md5("$ha1:${params.nonce}:$ha2")
        }

        return buildString {
            append("$headerName: Digest username=\"$username\", ")
            append("realm=\"${params.realm}\", ")
            append("nonce=\"${params.nonce}\", ")
            append("uri=\"$uri\", ")
            append("response=\"$response\", ")
            append("algorithm=MD5")
            if (params.opaque != null) append(", opaque=\"${params.opaque}\"")
            append("\r\n")
        }
    }

    /** Build Authorization header for INVITE */
    fun buildInviteAuthHeader(
        uri: String,
        username: String,
        password: String,
        params: AuthParams
    ): String = buildAuthHeader("INVITE", uri, username, password, params)

    private fun md5(input: String): String {
        val digest = MessageDigest.getInstance("MD5")
        val bytes = digest.digest(input.toByteArray())
        return bytes.joinToString("") { "%02x".format(it) }
    }

    data class AuthParams(
        val realm: String,
        val nonce: String,
        val opaque: String?,
        val qop: String?
    )
}
