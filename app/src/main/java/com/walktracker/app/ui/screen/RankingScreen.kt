package com.walktracker.app.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.walktracker.app.model.RankingEntry
import com.walktracker.app.viewmodel.RankingPeriod
import com.walktracker.app.viewmodel.RankingUiState

@Composable
fun RankingScreen(
    rankingState: RankingUiState,
    onPeriodChange: (RankingPeriod) -> Unit,
    currentUserId: String? // 현재 사용자 ID를 전달받음
) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
    ) {
        // 상단 탭
        PeriodTabs(
            selectedPeriod = rankingState.selectedPeriod,
            onPeriodChange = onPeriodChange
        )

        // 내 순위 카드
        MyRankCard(myRank = rankingState.myRank, totalParticipants = rankingState.totalParticipants)

        Text(
            text = "매일 0시~1시 갱신",
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 8.dp),
            textAlign = TextAlign.End,
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
        )

        // 랭킹 리스트
        if (rankingState.isLoading) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator()
            }
        } else if (rankingState.error != null) {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Icon(
                        imageVector = Icons.Default.ErrorOutline,
                        contentDescription = null,
                        modifier = Modifier.size(64.dp),
                        tint = MaterialTheme.colorScheme.error
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = rankingState.error,
                        style = MaterialTheme.typography.bodyLarge,
                        color = MaterialTheme.colorScheme.error,
                        textAlign = TextAlign.Center
                    )
                }
            }
        } else {
            RankingList(rankings = rankingState.leaderboard, currentUserId = currentUserId)
        }
    }
}

@Composable
private fun PeriodTabs(
    selectedPeriod: RankingPeriod,
    onPeriodChange: (RankingPeriod) -> Unit
) {
    TabRow(
        selectedTabIndex = selectedPeriod.ordinal,
        containerColor = MaterialTheme.colorScheme.surface,
        contentColor = MaterialTheme.colorScheme.primary
    ) {
        Tab(
            selected = selectedPeriod == RankingPeriod.DAILY,
            onClick = { onPeriodChange(RankingPeriod.DAILY) },
            text = { Text("어제") }
        )
        Tab(
            selected = selectedPeriod == RankingPeriod.MONTHLY,
            onClick = { onPeriodChange(RankingPeriod.MONTHLY) },
            text = { Text("이번달") }
        )
        Tab(
            selected = selectedPeriod == RankingPeriod.YEARLY,
            onClick = { onPeriodChange(RankingPeriod.YEARLY) },
            text = { Text("올해") }
        )
    }
}

@Composable
private fun MyRankCard(myRank: RankingEntry?, totalParticipants: Int) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(16.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.primaryContainer
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 4.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Person,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(40.dp)
                )

                Spacer(modifier = Modifier.width(12.dp))

                Column {
                    Text(
                        text = "내 순위",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                    if (myRank != null) {
                        Text(
                            text = "${myRank.rank}위 / ${totalParticipants}명",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    } else {
                        Text(
                            text = "순위권 밖",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                }
            }

            Icon(
                imageVector = Icons.Default.TrendingUp,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(32.dp)
            )
        }
    }
}

@Composable
private fun RankingList(rankings: List<RankingEntry>, currentUserId: String?) {
    if (rankings.isEmpty()) {
        Box(
            modifier = Modifier.fillMaxSize(),
            contentAlignment = Alignment.Center
        ) {
            Column(
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = Icons.Default.EmojiEvents,
                    contentDescription = null,
                    modifier = Modifier.size(64.dp),
                    tint = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.3f)
                )
                Spacer(modifier = Modifier.height(16.dp))
                Text(
                    text = "아직 랭킹 데이터가 없습니다",
                    style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.6f)
                )
            }
        }
    } else {
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)
        ) {
            items(rankings, key = { it.userId }) { entry ->
                val isMyRank = entry.userId == currentUserId
                RankingItem(
                    entry = entry,
                    isMyRank = isMyRank
                )
                Spacer(modifier = Modifier.height(8.dp))
            }
        }
    }
}

@Composable
private fun RankingItem(
    entry: RankingEntry,
    isMyRank: Boolean
) {
    val rank = entry.rank
    val medalColor = when (rank) {
        1 -> Color(0xFFFFD700) // 금
        2 -> Color(0xFFC0C0C0) // 은
        3 -> Color(0xFFCD7F32) // 동
        else -> null
    }

    val backgroundColor = when {
        isMyRank -> MaterialTheme.colorScheme.primary.copy(alpha = 0.2f)
        rank <= 3 -> medalColor?.copy(alpha = 0.1f) ?: MaterialTheme.colorScheme.surfaceVariant
        else -> MaterialTheme.colorScheme.surfaceVariant
    }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(
            containerColor = backgroundColor
        ),
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // 순위 배지
            Box(
                modifier = Modifier
                    .size(40.dp)
                    .clip(CircleShape)
                    .background(
                        if (rank <= 3) {
                            medalColor ?: MaterialTheme.colorScheme.primaryContainer
                        } else {
                            MaterialTheme.colorScheme.primaryContainer
                        }
                    ),
                contentAlignment = Alignment.Center
            ) {
                if (rank <= 3 && medalColor != null) {
                    Icon(
                        imageVector = Icons.Default.EmojiEvents,
                        contentDescription = "Medal",
                        tint = medalColor.copy(alpha = 0.9f),
                        modifier = Modifier.size(32.dp)
                    )
                }
                Text(
                    text = rank.toString(),
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold,
                    color = if (rank <= 3) Color.Black.copy(alpha = 0.6f) else MaterialTheme.colorScheme.onPrimaryContainer
                )
            }

            Spacer(modifier = Modifier.width(16.dp))

            // 사용자 정보
            Column(
                modifier = Modifier.weight(1f)
            ) {
                Text(
                    text = entry.displayName,
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = if (isMyRank) FontWeight.Bold else FontWeight.SemiBold
                )
                Text(
                    text = String.format("%.2f km", entry.distance),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurface.copy(alpha = 0.7f)
                )
            }

            // 거리 아이콘
            Icon(
                imageVector = Icons.Default.DirectionsWalk,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.primary.copy(alpha = 0.6f),
                modifier = Modifier.size(24.dp)
            )
        }
    }
}
