package kr.ac.unist.bus.arrival.application

import kr.ac.unist.bus.arrival.domain.ArrivalBatch
import org.springframework.stereotype.Component
import java.util.concurrent.ConcurrentHashMap

@Component
class ArrivalSnapshotCache {
    private val batches = ConcurrentHashMap<String, ArrivalBatch>()

    fun get(stopId: String): ArrivalBatch? = batches[stopId]

    fun put(batch: ArrivalBatch) {
        batches[batch.stop.id] = batch
    }
}
