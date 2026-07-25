package kr.ac.unist.bus.config

import org.springframework.boot.context.properties.ConfigurationProperties
import java.time.Duration

@ConfigurationProperties("unist-bus.tracking")
data class TrackingProperties(
    val storage: Storage = Storage.MEMORY,
    val encryptionKeyBase64: String = "",
    val firestoreCollection: String = "tracking-subscriptions",
) {
    enum class Storage {
        MEMORY,
        FIRESTORE,
    }

    override fun toString(): String =
        "TrackingProperties(storage=$storage, encryptionKeyBase64=<redacted>, " +
            "firestoreCollection=$firestoreCollection)"
}

@ConfigurationProperties("unist-bus.push")
data class PushProperties(
    val enabled: Boolean = false,
    val refreshKey: String = "",
    val etaChangeThreshold: Duration = Duration.ofSeconds(60),
    val matchWindow: Duration = Duration.ofMinutes(3),
    val iosHeartbeatInterval: Duration = Duration.ofSeconds(60),
    val deliveryClaimLease: Duration = Duration.ofSeconds(30),
    val maxSubscriptionLifetime: Duration = Duration.ofHours(2),
    val arrivalGracePeriod: Duration = Duration.ofMinutes(30),
    val firebase: Firebase = Firebase(),
    val fcm: Fcm = Fcm(),
    val apns: Apns = Apns(),
) {
    data class Firebase(
        val projectId: String = "",
    )

    data class Fcm(
        val enabled: Boolean = false,
    )

    data class Apns(
        val enabled: Boolean = false,
        val keyId: String = "",
        val teamId: String = "",
        val bundleId: String = "",
        val authKey: String = "",
    ) {
        override fun toString(): String =
            "Apns(enabled=$enabled, keyId=<redacted>, teamId=<redacted>, " +
                "bundleId=$bundleId, authKey=<redacted>)"
    }

    override fun toString(): String =
        "PushProperties(enabled=$enabled, refreshKey=<redacted>, " +
            "etaChangeThreshold=$etaChangeThreshold, matchWindow=$matchWindow, " +
            "iosHeartbeatInterval=$iosHeartbeatInterval, deliveryClaimLease=$deliveryClaimLease, " +
            "maxSubscriptionLifetime=$maxSubscriptionLifetime, " +
            "arrivalGracePeriod=$arrivalGracePeriod, firebase=$firebase, fcm=$fcm, apns=$apns)"
}
