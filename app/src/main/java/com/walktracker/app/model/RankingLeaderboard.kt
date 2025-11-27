package com.walktracker.app.model

import com.google.firebase.Timestamp

/**
 * Firestore의 'rankings' 컬렉션에 저장될 비정규화된 랭킹 문서 모델입니다.
 * 이 문서는 백엔드 스크립트에 의해 주기적으로 집계 및 업데이트됩니다.
 */
data class RankingLeaderboard(
    val leaderboard: List<RankingEntry> = emptyList(),
    val totalParticipants: Int = 0,
    val updatedAt: Timestamp? = null,
    val period: String = "",
    val periodKey: String = ""
)

/**
 * RankingLeaderboard 문서의 leaderboard 필드에 포함될 개별 사용자 랭킹 정보입니다.
 * 이 모델은 기존 RankingEntry와 동일한 구조를 가집니다.
 */

