package com.kachat.app.models

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue

// Creating a group and adding members (iOS GroupActionSheets.swift, owner 2026-11-24): every
// transaction is reported as it goes out (setting up, inviting each person, the recovery copy, the
// photo), so a slow network reads as "working", not "broken".

/** How far one step of a group action has got, as [com.kachat.app.repository.GroupRepository]
 *  reports it (iOS `GroupChatService.GroupActionPhase`). */
sealed interface GroupActionPhase {
    data object Started : GroupActionPhase
    data object Finished : GroupActionPhase
    data class Failed(val error: Throwable) : GroupActionPhase
}

/** The keys of the steps that aren't a member's address (iOS `GroupChatService.GroupActionStep`,
 *  plus the create screen's photo step). */
object GroupActionStep {
    const val SET_UP = "#setup"
    const val RECOVERY_COPY = "#recovery"
    const val PHOTO = "#photo"
}

/** One step of creating a group or changing its members, as it happens. `key` is a member's
 *  address, or a [GroupActionStep] (iOS `GroupActionReport`). */
typealias GroupActionReport = suspend (key: String, phase: GroupActionPhase) -> Unit

/** The steps of one group action and how far each has got (iOS `GroupActionProgress`). Snapshot
 *  state: change it on the main thread. */
class GroupActionProgress(val headline: String, steps: List<Step>) {

    sealed interface State {
        data object Waiting : State
        data object Working : State
        data object Done : State
        data class Failed(val message: String) : State
    }

    data class Step(
        val id: String,
        val title: String,
        val detail: String? = null,
        val state: State = State.Waiting,
    )

    val steps = mutableStateListOf<Step>().apply { addAll(steps) }

    var isFinished by mutableStateOf(false)
        private set

    val doneCount: Int get() = steps.count { it.state == State.Done }
    val failedCount: Int get() = steps.count { it.state is State.Failed }
    val settledCount: Int get() = steps.count { it.state != State.Waiting && it.state != State.Working }

    private fun indexOf(id: String): Int = steps.indexOfFirst { it.id.equals(id, ignoreCase = true) }

    fun set(id: String, state: State, detail: String? = null) {
        val index = indexOf(id).takeIf { it >= 0 } ?: return
        steps[index] = steps[index].copy(state = state, detail = detail ?: steps[index].detail)
    }

    fun setDetail(id: String, detail: String?) {
        val index = indexOf(id).takeIf { it >= 0 } ?: return
        steps[index] = steps[index].copy(detail = detail)
    }

    /** A step's news from the repository (its key is the step's id). */
    fun apply(key: String, phase: GroupActionPhase, errorMessage: (Throwable) -> String) {
        when (phase) {
            GroupActionPhase.Started -> set(key, State.Working)
            GroupActionPhase.Finished -> set(key, State.Done)
            is GroupActionPhase.Failed -> set(key, State.Failed(errorMessage(phase.error)))
        }
    }

    /** Anything still waiting or working when the action ends failed with it ([message], else
     *  "Not sent", which the caller passes translated). */
    fun finish(message: String) {
        for (index in steps.indices) {
            val state = steps[index].state
            if (state == State.Waiting || state == State.Working) steps[index] = steps[index].copy(state = State.Failed(message))
        }
        isFinished = true
    }
}
