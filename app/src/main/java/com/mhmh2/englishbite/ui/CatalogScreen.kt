package com.mhmh2.englishbite.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import coil.compose.AsyncImage
import com.mhmh2.englishbite.data.CatalogItem

private fun formatViewCount(count: Long): String = when {
    count >= 100_000_000 -> "%.1f억회".format(count / 100_000_000.0)
    count >= 10_000 -> "%.1f만회".format(count / 10_000.0)
    else -> "${count}회"
}

private fun formatDuration(seconds: Int): String {
    val m = seconds / 60
    val s = seconds % 60
    return "%d:%02d".format(m, s)
}

private fun formatUploadTime(unixTimestamp: Long): String {
    if (unixTimestamp <= 0) return ""
    val ageMinutes = (System.currentTimeMillis() / 1000 - unixTimestamp) / 60
    return when {
        ageMinutes < 1 -> "방금 전"
        ageMinutes < 60 -> "${ageMinutes}분 전"
        ageMinutes < 24 * 60 -> "${ageMinutes / 60}시간 전"
        else -> "${ageMinutes / (24 * 60)}일 전"
    }
}

@Composable
fun CatalogScreen(
    state: CatalogState,
    channelFilter: String?,
    sort: CatalogSort,
    onChannelFilterChange: (String?) -> Unit,
    onSortChange: (CatalogSort) -> Unit,
    onSelect: (CatalogItem) -> Unit,
    onManualUrlEntry: () -> Unit
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Text(
            text = "EnglishBite",
            style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
            modifier = Modifier.padding(horizontal = 20.dp, vertical = 16.dp)
        )

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 20.dp)
        ) {
            item {
                FilterChip(
                    selected = channelFilter == null,
                    onClick = { onChannelFilterChange(null) },
                    label = { Text("전체") },
                    colors = chipColors()
                )
            }
            items(listOf("CNN", "BBC News", "Bloomberg")) { channel ->
                FilterChip(
                    selected = channelFilter == channel,
                    onClick = { onChannelFilterChange(channel) },
                    label = { Text(channel) },
                    colors = chipColors()
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        LazyRow(
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(horizontal = 20.dp)
        ) {
            item {
                FilterChip(
                    selected = sort == CatalogSort.POPULAR,
                    onClick = { onSortChange(CatalogSort.POPULAR) },
                    label = { Text("인기순") },
                    colors = chipColors()
                )
            }
            item {
                FilterChip(
                    selected = sort == CatalogSort.LATEST,
                    onClick = { onSortChange(CatalogSort.LATEST) },
                    label = { Text("최신순") },
                    colors = chipColors()
                )
            }
        }

        Spacer(Modifier.height(8.dp))

        when (state) {
            is CatalogState.Loading -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }

            is CatalogState.Error -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                Text(
                    "목록을 불러오지 못했어요: ${state.message}",
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(24.dp)
                )
            }

            is CatalogState.Loaded -> {
                if (state.items.isEmpty()) {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            "아직 준비된 영상이 없어요",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        items(state.items, key = { it.video_id }) { item ->
                            CatalogCard(item = item, onClick = { onSelect(item) })
                        }
                        item {
                            TextButton(onClick = onManualUrlEntry, modifier = Modifier.fillMaxWidth()) {
                                Text("URL 직접 입력하기")
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun chipColors() = FilterChipDefaults.filterChipColors(
    selectedContainerColor = MaterialTheme.colorScheme.primary,
    selectedLabelColor = MaterialTheme.colorScheme.onPrimary
)

@Composable
private fun CatalogCard(item: CatalogItem, onClick: () -> Unit) {
    Column(modifier = Modifier.fillMaxWidth().clickable(onClick = onClick)) {
        Box(
            modifier = Modifier
                .fillMaxWidth()
                .aspectRatio(16f / 9f)
                .clip(RoundedCornerShape(12.dp))
                .background(MaterialTheme.colorScheme.surfaceVariant)
        ) {
            AsyncImage(
                model = item.thumbnail,
                contentDescription = item.title,
                contentScale = ContentScale.Crop,
                modifier = Modifier.fillMaxSize()
            )
            Text(
                text = formatDuration(item.duration),
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onPrimary,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .padding(6.dp)
                    .background(Color.Black.copy(alpha = 0.7f), RoundedCornerShape(4.dp))
                    .padding(horizontal = 6.dp, vertical = 2.dp)
            )
        }

        Spacer(Modifier.height(8.dp))

        Row(verticalAlignment = Alignment.Top) {
            Box(
                modifier = Modifier
                    .size(28.dp)
                    .clip(RoundedCornerShape(6.dp))
                    .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = item.channel.take(1),
                    color = MaterialTheme.colorScheme.onPrimary,
                    style = MaterialTheme.typography.labelLarge
                )
            }
            Spacer(Modifier.width(10.dp))
            Column {
                Text(
                    text = item.title,
                    style = MaterialTheme.typography.titleLarge,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis
                )
                Spacer(Modifier.height(2.dp))
                Text(
                    text = "${item.channel} · ${formatViewCount(item.view_count)} · ${formatUploadTime(item.timestamp)}",
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}
