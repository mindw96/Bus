package kr.ac.unist.bus.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.ElevatedCard
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import kr.ac.unist.bus.model.ArrivalStatus
import kr.ac.unist.bus.model.ArrivalText
import kr.ac.unist.bus.model.StopArrival
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ArrivalsScreen(
    state: ArrivalsUiState,
    snackbarHostState: SnackbarHostState,
    onRefresh: () -> Unit,
    onToggleTracking: (StopArrival) -> Unit,
    modifier: Modifier = Modifier,
) {
    var now by remember { mutableStateOf(Instant.now()) }

    LaunchedEffect(Unit) {
        while (isActive) {
            delay(1_000)
            now = Instant.now()
        }
    }

    Scaffold(
        modifier = modifier.fillMaxSize(),
        snackbarHost = { SnackbarHost(snackbarHostState) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = "UNIST Bus",
                            fontWeight = FontWeight.Bold,
                        )
                        Text(
                            text = "울산과학기술원 주요 노선",
                            style = MaterialTheme.typography.labelMedium,
                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                        )
                    }
                },
                actions = {
                    TextButton(
                        onClick = onRefresh,
                        enabled = !state.isRefreshing,
                    ) {
                        Text(if (state.isRefreshing) "갱신 중" else "새로고침")
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.surface,
                ),
            )
        },
    ) { innerPadding ->
        LazyColumn(
            contentPadding = PaddingValues(
                start = 16.dp,
                top = innerPadding.calculateTopPadding() + 8.dp,
                end = 16.dp,
                bottom = innerPadding.calculateBottomPadding() + 24.dp,
            ),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            item {
                LiveDataHeader(generatedAt = state.generatedAt)
            }

            items(
                items = state.arrivals,
                key = { it.arrival.routeName },
            ) { item ->
                ArrivalCard(
                    item = item,
                    now = now,
                    isTracking = state.trackedTripId == item.arrival.tripId,
                    onToggleTracking = { onToggleTracking(item) },
                )
            }
        }
    }
}

@Composable
private fun LiveDataHeader(
    generatedAt: Instant?,
    modifier: Modifier = Modifier,
) {
    ElevatedCard(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.secondaryContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = "주요 정류장 3곳",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text(
                        text = "통합 도착 정보 · 5개 노선",
                        style = MaterialTheme.typography.bodyMedium,
                    )
                }

                Surface(
                    color = MaterialTheme.colorScheme.tertiaryContainer,
                    shape = RoundedCornerShape(999.dp),
                ) {
                    Text(
                        text = "실시간",
                        modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                        style = MaterialTheme.typography.labelLarge,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }

            Text(
                text = "40234 · 40233 · 40235",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSecondaryContainer,
            )

            if (generatedAt != null) {
                Text(
                    text = "마지막 업데이트 ${TIME_FORMATTER.format(generatedAt)}",
                    style = MaterialTheme.typography.labelMedium,
                    color = MaterialTheme.colorScheme.onSecondaryContainer,
                )
            }
        }
    }
}

@Composable
private fun ArrivalCard(
    item: StopArrival,
    now: Instant,
    isTracking: Boolean,
    onToggleTracking: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val arrival = item.arrival
    val statusText = ArrivalText.status(item, now)
    val eventName = item.countdownKind.eventName
    val accessibilityText = buildString {
        append("${arrival.routeName}번, ${arrival.destination} 방면, ")
        append("${item.stop.name} 정류장 ${item.stop.id}, $statusText")
    }

    ElevatedCard(
        modifier = modifier
            .fillMaxWidth()
            .semantics { contentDescription = accessibilityText },
        colors = CardDefaults.elevatedCardColors(
            containerColor = MaterialTheme.colorScheme.surfaceContainer,
        ),
    ) {
        Column(
            modifier = Modifier.padding(18.dp),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(
                    text = "${arrival.routeName}번",
                    style = MaterialTheme.typography.headlineMedium,
                    fontWeight = FontWeight.ExtraBold,
                    color = MaterialTheme.colorScheme.onSurface,
                )

                Spacer(modifier = Modifier.width(10.dp))

                Text(
                    text = "${arrival.destination} 방면",
                    modifier = Modifier.weight(1f),
                    style = MaterialTheme.typography.titleMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )

                if (arrival.status == ArrivalStatus.DELAYED) {
                    Surface(
                        color = MaterialTheme.colorScheme.errorContainer,
                        shape = RoundedCornerShape(999.dp),
                    ) {
                        Text(
                            text = "지연",
                            modifier = Modifier.padding(horizontal = 9.dp, vertical = 5.dp),
                            style = MaterialTheme.typography.labelLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onErrorContainer,
                        )
                    }
                }
            }

            Text(
                text = "${item.stop.name} · ${item.stop.id}",
                style = MaterialTheme.typography.bodyLarge,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )

            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp)),
            ) {
                Column(
                    verticalArrangement = Arrangement.spacedBy(4.dp),
                ) {
                    Text(
                        text = statusText,
                        style = MaterialTheme.typography.headlineSmall,
                        fontWeight = FontWeight.ExtraBold,
                        color = when {
                            statusText == "데이터 지연" -> MaterialTheme.colorScheme.error
                            else -> MaterialTheme.colorScheme.onSurface
                        },
                    )
                    Text(
                        text = "$eventName 예정 ${TIME_FORMATTER.format(arrival.estimatedArrivalAt)}",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.SemiBold,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                }
            }

            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)

            if (isTracking) {
                OutlinedButton(
                    onClick = onToggleTracking,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Text(
                        text = "추적 중단",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            } else {
                Button(
                    onClick = onToggleTracking,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(52.dp),
                ) {
                    Text(
                        text = "잠금화면에서 보기",
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                }
            }
        }
    }
}

private val TIME_FORMATTER: DateTimeFormatter = DateTimeFormatter
    .ofPattern("a h:mm", Locale.KOREAN)
    .withZone(ZoneId.systemDefault())
