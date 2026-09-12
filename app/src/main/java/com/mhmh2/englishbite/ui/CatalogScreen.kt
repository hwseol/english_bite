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
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material.icons.filled.Clear
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
    categoryFilter: String?,
    sort: CatalogSort,
    searchQuery: String,
    listState: LazyListState,
    onChannelFilterChange: (String?) -> Unit,
    onCategoryFilterChange: (String?) -> Unit,
    onSortChange: (CatalogSort) -> Unit,
    onSearchQueryChange: (String) -> Unit,
    onSelect: (CatalogItem) -> Unit,
    onOpenVocabulary: () -> Unit = {}
) {
    // listState is hoisted by the caller (survives navigating to a video and back, unlike a
    // rememberLazyListState() created here, which would reset to the top on every return trip).
    // A plain LaunchedEffect(sort, channelFilter) would have the same problem the scroll
    // position itself used to have: it reruns on every fresh composition of this screen,
    // including a return-from-video one where sort/channelFilter didn't actually change, and
    // would wrongly reset the scroll then too. Comparing against the last-seen values (seeded
    // from the current ones, so a fresh composition is never a false "change") tells a real
    // click apart from a plain recomposition.
    var lastSort by remember { mutableStateOf(sort) }
    var lastChannelFilter by remember { mutableStateOf(channelFilter) }
    var lastCategoryFilter by remember { mutableStateOf(categoryFilter) }
    LaunchedEffect(sort, channelFilter, categoryFilter) {
        if (sort != lastSort || channelFilter != lastChannelFilter || categoryFilter != lastCategoryFilter) {
            listState.scrollToItem(0)
        }
        lastSort = sort
        lastChannelFilter = channelFilter
        lastCategoryFilter = categoryFilter
    }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 8.dp)
        ) {
            Text(
                text = "EnglishBite",
                style = MaterialTheme.typography.headlineSmall.copy(fontWeight = FontWeight.Bold),
                modifier = Modifier.weight(1f).padding(vertical = 8.dp)
            )
            IconButton(onClick = onOpenVocabulary) {
                Icon(
                    imageVector = Icons.Default.Bookmark,
                    contentDescription = "내 단어장",
                    tint = MaterialTheme.colorScheme.primary
                )
            }
        }

        OutlinedTextField(
            value = searchQuery,
            onValueChange = onSearchQueryChange,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp),
            placeholder = { Text("영상 제목 검색") },
            leadingIcon = { Icon(Icons.Default.Search, contentDescription = null) },
            trailingIcon = {
                if (searchQuery.isNotEmpty()) {
                    IconButton(onClick = { onSearchQueryChange("") }) {
                        Icon(Icons.Default.Clear, contentDescription = "지우기")
                    }
                }
            },
            singleLine = true,
            shape = RoundedCornerShape(24.dp),
            keyboardOptions = KeyboardOptions.Default,
            colors = OutlinedTextFieldDefaults.colors(
                focusedBorderColor = MaterialTheme.colorScheme.primary
            )
        )

        Spacer(Modifier.height(12.dp))

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
            items(listOf("CNN", "BBC News", "Bloomberg", "The Economist", "Fox Business")) { channel ->
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
                    selected = categoryFilter == null,
                    onClick = { onCategoryFilterChange(null) },
                    label = { Text("모든 주제") },
                    colors = chipColors()
                )
            }
            items(listOf("정치", "경제", "사회", "스포츠")) { category ->
                FilterChip(
                    selected = categoryFilter == category,
                    onClick = { onCategoryFilterChange(category) },
                    label = { Text(category) },
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
                    selected = sort == CatalogSort.LATEST,
                    onClick = { onSortChange(CatalogSort.LATEST) },
                    label = { Text("최신순") },
                    colors = chipColors()
                )
            }
            item {
                FilterChip(
                    selected = sort == CatalogSort.POPULAR,
                    onClick = { onSortChange(CatalogSort.POPULAR) },
                    label = { Text("인기순") },
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
                    val hasActiveFilter = channelFilter != null || categoryFilter != null || searchQuery.isNotBlank()
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        Text(
                            if (hasActiveFilter) "조건에 맞는 영상이 없어요" else "아직 준비된 영상이 없어요",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                } else {
                    LazyColumn(
                        state = listState,
                        contentPadding = PaddingValues(horizontal = 20.dp, vertical = 8.dp),
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        items(state.items, key = { it.video_id }) { item ->
                            CatalogCard(item = item, onClick = { onSelect(item) })
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
