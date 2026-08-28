package com.csworkout.app

data class MatchTeam(
    val id: String = "",
    val name: String = "",
    val logo: String = "",
    val seriesScore: Int = 0,
)

data class ScheduleMatch(
    val id: String,
    val team1: MatchTeam,
    val team2: MatchTeam,
    val tournament: String,
    val stage: String,
    val bestOf: Int,
    val scheduledAtMillis: Long,
    val status: MatchStatus,
    val currentMap: Int? = null,
)

enum class MatchStatus { LIVE, UPCOMING }

data class PlayerRow(
    val id: String,
    val name: String,
    val teamSlot: String,
    val kills: Int = 0,
    val deaths: Int = 0,
)

data class MatchSnapshot(
    val matchId: String,
    val title: String,
    val tournament: String,
    val mapNumber: Int,
    val mapName: String,
    val mapStatus: String,
    val team1: MatchTeam,
    val team2: MatchTeam,
    val team1MapScore: Int,
    val team2MapScore: Int,
    val t1Side: String,
    val t2Side: String,
    val players: List<PlayerRow>,
    val boutKey: String,
)

data class EventRow(
    val updateVersion: Long,
    val rawLog: String,
    val mapName: String,
)

data class LiveEvent(
    val type: String,
    val title: String,
    val detail: String = "",
    val createdAt: Long = System.currentTimeMillis(),
)

data class WorkoutPlanItem(
    val actionId: String,
    val action: String,
    val account: String,
    val level: Int,
    val levelLabel: String,
    val units: Double,
    val unitLabel: String,
    val estimatedDU: Double,
    val instruction: String,
    val tempo: String,
    val source: String,
)

data class WorkoutAccountState(
    val outstandingDU: Double = 0.0,
    val grossDU: Double = 0.0,
    val paidDU: Double = 0.0,
    val fatigue: Double = 0.0,
    val bankrupt: Boolean = false,
    val reopenUsed: Boolean = false,
    val plan: List<WorkoutPlanItem> = emptyList(),
)

data class WorkoutPublicState(
    val enabled: Boolean = false,
    val matchId: String = "",
    val player: String = "",
    val mapNumber: Int = 1,
    val abs: WorkoutAccountState = WorkoutAccountState(),
    val legs: WorkoutAccountState = WorkoutAccountState(),
    val writtenOffDU: Double = 0.0,
)
