package kr.ac.unist.bus.ui

import kr.ac.unist.bus.model.StopArrival

internal val MAIN_ROUTE_NAMES = listOf("513", "713", "743", "753", "1115")

internal data class MainRouteListItem(
    val routeName: String,
    val arrival: StopArrival?,
) {
    val canTrack: Boolean
        get() = arrival != null
}

internal fun mainRouteListItems(
    arrivals: List<StopArrival>,
): List<MainRouteListItem> {
    val seenRoutes = mutableSetOf<String>()
    val actualArrivals = arrivals
        .filter { item ->
            item.arrival.routeName in MAIN_ROUTE_NAMES &&
                seenRoutes.add(item.arrival.routeName)
        }
        .map { item ->
            MainRouteListItem(
                routeName = item.arrival.routeName,
                arrival = item,
            )
        }

    return actualArrivals + MAIN_ROUTE_NAMES
        .filterNot(seenRoutes::contains)
        .map { routeName ->
            MainRouteListItem(
                routeName = routeName,
                arrival = null,
            )
        }
}
