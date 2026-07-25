package kr.ac.unist.bus.push.apns

import kr.ac.unist.bus.config.PushProperties
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty
import org.springframework.stereotype.Component
import java.nio.charset.StandardCharsets
import java.security.KeyFactory
import java.security.PrivateKey
import java.security.Signature
import java.security.spec.PKCS8EncodedKeySpec
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.Base64

fun interface ApnsAuthorizationTokenProvider {
    fun token(): String
}

@Component
@ConditionalOnProperty(
    prefix = "unist-bus.push.apns",
    name = ["enabled"],
    havingValue = "true",
)
class ApnsJwtProvider(
    properties: PushProperties,
    private val clock: Clock,
) : ApnsAuthorizationTokenProvider {
    private val keyId = properties.apns.keyId
    private val teamId = properties.apns.teamId
    private val privateKey = parsePrivateKey(properties.apns.authKey)
    private var cached: CachedJwt? = null

    @Synchronized
    override fun token(): String {
        val now = clock.instant()
        cached?.takeIf { Duration.between(it.createdAt, now) < CACHE_DURATION }?.let {
            return it.value
        }
        val value = createToken(now)
        cached = CachedJwt(value, now)
        return value
    }

    private fun createToken(now: Instant): String {
        val header = base64Url("""{"alg":"ES256","kid":"$keyId"}""")
        val claims = base64Url("""{"iss":"$teamId","iat":${now.epochSecond}}""")
        val content = "$header.$claims"
        val signature = Signature.getInstance("SHA256withECDSA").run {
            initSign(privateKey)
            update(content.toByteArray(StandardCharsets.UTF_8))
            sign()
        }
        return "$content.${Base64.getUrlEncoder().withoutPadding().encodeToString(derToJose(signature))}"
    }

    private fun base64Url(value: String): String =
        Base64.getUrlEncoder().withoutPadding()
            .encodeToString(value.toByteArray(StandardCharsets.UTF_8))

    private fun parsePrivateKey(pem: String): PrivateKey {
        val normalized = pem.replace("\\n", "\n")
            .replace("-----BEGIN PRIVATE KEY-----", "")
            .replace("-----END PRIVATE KEY-----", "")
            .replace("\\s".toRegex(), "")
        return try {
            val bytes = Base64.getDecoder().decode(normalized)
            KeyFactory.getInstance("EC").generatePrivate(PKCS8EncodedKeySpec(bytes))
        } catch (_: Exception) {
            throw IllegalStateException("APNs 인증키 형식이 올바르지 않습니다.")
        }
    }

    private fun derToJose(der: ByteArray): ByteArray {
        var index = 0
        require(der[index++].toInt() == 0x30) { "ECDSA 서명 형식이 올바르지 않습니다." }
        index += encodedLengthBytes(der, index)
        require(der[index++].toInt() == 0x02) { "ECDSA R 값이 없습니다." }
        val rLengthBytes = encodedLengthBytes(der, index)
        val rLength = decodeLength(der, index)
        index += rLengthBytes
        val r = der.copyOfRange(index, index + rLength)
        index += rLength
        require(der[index++].toInt() == 0x02) { "ECDSA S 값이 없습니다." }
        val sLengthBytes = encodedLengthBytes(der, index)
        val sLength = decodeLength(der, index)
        index += sLengthBytes
        val s = der.copyOfRange(index, index + sLength)
        return unsigned32(r) + unsigned32(s)
    }

    private fun encodedLengthBytes(bytes: ByteArray, index: Int): Int {
        val first = bytes[index].toInt() and 0xff
        return if (first and 0x80 == 0) 1 else 1 + (first and 0x7f)
    }

    private fun decodeLength(bytes: ByteArray, index: Int): Int {
        val first = bytes[index].toInt() and 0xff
        if (first and 0x80 == 0) return first
        val count = first and 0x7f
        var length = 0
        repeat(count) { offset ->
            length = (length shl 8) or (bytes[index + 1 + offset].toInt() and 0xff)
        }
        return length
    }

    private fun unsigned32(value: ByteArray): ByteArray {
        val stripped = value.dropWhile { it == 0.toByte() }.toByteArray()
        require(stripped.size <= 32) { "ECDSA 정수 길이가 올바르지 않습니다." }
        return ByteArray(32 - stripped.size) + stripped
    }

    private data class CachedJwt(val value: String, val createdAt: Instant)

    private companion object {
        val CACHE_DURATION: Duration = Duration.ofMinutes(50)
    }
}
