package com.kestrel.vpn.data.local.entity
import androidx.room.Entity
import androidx.room.PrimaryKey
import com.kestrel.vpn.domain.model.HuntPhase
import com.kestrel.vpn.domain.model.HuntState
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
@Entity(tableName = "hunt_state")
data class HuntStateEntity(
    @PrimaryKey
    val networkKey: String,
    val phase: String,
    val contenderKeysJson: String,
    val testedInRoundKeysJson: String,
    val winnerKey: String?,
    val roundTotal: Int,
    val updatedAtMs: Long
) {
    fun toDomain(json: Json): HuntState = HuntState(
        networkKey = networkKey,
        phase = runCatching { HuntPhase.valueOf(phase) }.getOrDefault(HuntPhase.IDLE),
        contenderKeys = runCatching {
            json.decodeFromString<List<String>>(contenderKeysJson)
        }.getOrDefault(emptyList()),
        testedInRoundKeys = runCatching {
            json.decodeFromString<List<String>>(testedInRoundKeysJson)
        }.getOrDefault(emptyList()),
        winnerKey = winnerKey,
        roundTotal = roundTotal,
        updatedAtMs = updatedAtMs
    )
    companion object {
        fun fromDomain(state: HuntState, json: Json): HuntStateEntity = HuntStateEntity(
            networkKey = state.networkKey,
            phase = state.phase.name,
            contenderKeysJson = json.encodeToString(state.contenderKeys),
            testedInRoundKeysJson = json.encodeToString(state.testedInRoundKeys),
            winnerKey = state.winnerKey,
            roundTotal = state.roundTotal,
            updatedAtMs = state.updatedAtMs
        )
    }
}
