package kr.ac.unist.bus.data

import kr.ac.unist.bus.model.ArrivalSnapshot

fun interface ArrivalRepository {
    suspend fun fetch(): ArrivalSnapshot
}
