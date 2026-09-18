package com.meshwhisper.core.identity

import com.meshwhisper.core.crypto.PureCryptoEngine
import java.net.URLDecoder
import java.net.URLEncoder

/**
 * Protocol v2 Node QR format defined in NEXTGEN/01_VNEXT_PROTOCOL_FROZEN.md:
 * meshwhisper://node/v2?ik=<ikPubHex>&ek=<ekPubHex>&kv=<keyVersion>&nb=<notBefore>&ibc=<ibcSigHex>&alias=<encodedAlias>
 */
data class NodeQrData(
    val ikPub: ByteArray,
    val ekPub: ByteArray,
    val keyVersion: Long,
    val notBefore: Long,
    val ibcSignature: ByteArray,
    val alias: String
) {
    val identityHash: ByteArray by lazy { PureCryptoEngine.deriveIdentityHash(ikPub) }
    val nodeId64: Long by lazy { PureCryptoEngine.deriveNodeId64(identityHash) }

    init {
        require(ikPub.size == 32) { "ikPub must be 32 bytes, got ${ikPub.size}" }
        require(ekPub.size == 32) { "ekPub must be 32 bytes, got ${ekPub.size}" }
        require(keyVersion in 1L..0xFFFFFFFFL) { "keyVersion must be in 1..0xFFFFFFFF, got $keyVersion" }
        require(notBefore in 0L..0xFFFFFFFFL) { "notBefore must be in 0..0xFFFFFFFF, got $notBefore" }
        require(ibcSignature.size == 64) { "ibcSignature must be 64 bytes, got ${ibcSignature.size}" }
    }

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (javaClass != other?.javaClass) return false
        other as NodeQrData
        return ikPub.contentEquals(other.ikPub) &&
                ekPub.contentEquals(other.ekPub) &&
                keyVersion == other.keyVersion &&
                notBefore == other.notBefore &&
                ibcSignature.contentEquals(other.ibcSignature) &&
                alias == other.alias
    }

    override fun hashCode(): Int {
        var result = ikPub.contentHashCode()
        result = 31 * result + ekPub.contentHashCode()
        result = 31 * result + keyVersion.hashCode()
        result = 31 * result + notBefore.hashCode()
        result = 31 * result + ibcSignature.contentHashCode()
        result = 31 * result + alias.hashCode()
        return result
    }
}

object NodeQrCodec {

    private const val SCHEME_PREFIX = "meshwhisper://node/v2?"

    /**
     * Encodes NodeQrData into canonical frozen v2 QR URI string.
     */
    fun encode(data: NodeQrData): String {
        val ikHex = PureCryptoEngine.bytesToHex(data.ikPub).lowercase()
        val ekHex = PureCryptoEngine.bytesToHex(data.ekPub).lowercase()
        val ibcHex = PureCryptoEngine.bytesToHex(data.ibcSignature).lowercase()
        val encodedAlias = URLEncoder.encode(data.alias, "UTF-8")
        return "${SCHEME_PREFIX}ik=$ikHex&ek=$ekHex&kv=${data.keyVersion}&nb=${data.notBefore}&ibc=$ibcHex&alias=$encodedAlias"
    }

    /**
     * Decodes and validates a v2 QR URI string.
     * Returns null if scheme/path is invalid, parameters are missing, malformed, or out of bounds.
     */
    fun decode(uriString: String): NodeQrData? {
        if (!uriString.startsWith("meshwhisper://node/v2")) {
            return null
        }
        val queryStart = uriString.indexOf('?')
        if (queryStart < 0) return null
        val query = uriString.substring(queryStart + 1)
        val params = query.split("&").associate { part ->
            val eq = part.indexOf('=')
            if (eq >= 0) {
                part.substring(0, eq) to part.substring(eq + 1)
            } else {
                part to ""
            }
        }

        val ikHex = params["ik"] ?: return null
        val ekHex = params["ek"] ?: return null
        val kvStr = params["kv"] ?: return null
        val nbStr = params["nb"] ?: "0"
        val ibcHex = params["ibc"] ?: return null
        val rawAlias = params["alias"] ?: ""

        if (ikHex.length != 64 || ekHex.length != 64 || ibcHex.length != 128) {
            return null
        }

        return try {
            val ikPub = PureCryptoEngine.hexToBytes(ikHex)
            val ekPub = PureCryptoEngine.hexToBytes(ekHex)
            val ibcSig = PureCryptoEngine.hexToBytes(ibcHex)
            val keyVersion = kvStr.toLong()
            val notBefore = nbStr.toLong()
            val alias = URLDecoder.decode(rawAlias, "UTF-8")

            if (keyVersion !in 1L..0xFFFFFFFFL || notBefore !in 0L..0xFFFFFFFFL) {
                return null
            }

            NodeQrData(
                ikPub = ikPub,
                ekPub = ekPub,
                keyVersion = keyVersion,
                notBefore = notBefore,
                ibcSignature = ibcSig,
                alias = alias
            )
        } catch (e: Exception) {
            null
        }
    }
}
