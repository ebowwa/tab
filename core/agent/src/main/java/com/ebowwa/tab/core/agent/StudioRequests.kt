package com.ebowwa.tab.core.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A request handed from the system agent to the UI.
 *
 * Two shapes, because there are two ways the assistant can drive Studio:
 * - [pending] carries a *prompt* for the on-device model to write. It cannot be generated
 *   inside the AppFunction itself: ML Kit GenAI is foreground-only and fails with ErrorCode
 *   30 in a service.
 * - [pendingHtml] carries a *finished game* the assistant wrote itself. No generation is
 *   needed, only validation and rendering.
 *
 * Lives in :core:agent because both the AppFunctions service (in :app) and the UI (in
 * :feature:ledger) need it.
 */
object StudioRequests {
    private val _pending = MutableStateFlow<String?>(null)
    private val _pendingHtml = MutableStateFlow<String?>(null)

    /** Observable so a Studio that is already running still picks the request up. */
    val pending: StateFlow<String?> = _pending.asStateFlow()
    val pendingHtml: StateFlow<String?> = _pendingHtml.asStateFlow()

    fun queue(prompt: String) {
        _pending.value = prompt
    }

    fun consume() {
        _pending.value = null
    }

    fun peek(): String? = _pending.value

    /** A finished game handed over by the system agent, ready to render. */
    fun queueHtml(html: String) {
        _pendingHtml.value = html
    }

    fun consumeHtml() {
        _pendingHtml.value = null
    }

    fun takeHtml(): String? = _pendingHtml.value.also { _pendingHtml.value = null }
}
