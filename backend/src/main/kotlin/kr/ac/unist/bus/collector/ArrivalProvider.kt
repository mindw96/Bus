package kr.ac.unist.bus.collector

import kr.ac.unist.bus.arrival.domain.ArrivalBatch
import kr.ac.unist.bus.stop.SupportedStop
import java.time.Instant

fun interface ArrivalProvider {
    fun fetch(stop: SupportedStop, observedAt: Instant): ArrivalBatch
}

class ArrivalProviderUnavailableException(
    message: String = "도착 정보 제공처에 연결할 수 없습니다.",
) : RuntimeException(message)
