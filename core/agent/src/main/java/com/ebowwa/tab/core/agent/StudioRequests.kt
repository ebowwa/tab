package com.ebowwa.tab.core.agent

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * A build request handed from the system agent to the UI.
 *
 * On-device generation is foreground-only (ML Kit GenAI fails with ErrorCode 30 in a
 * service), so an AppFunction cannot generate. It queues the prompt here and the running
 * Studio consumes it. It lives in :core:agent because both the AppFunctions service (in
 * :app) and the UI (in :feature:ledger) need it, and :app already depends on this module.
 */
object StudioRequests {
    private val _pending = MutableStateFlow<String?>(null)

    /** Observable so a Studio that is already running still picks the request up. */
    val pending: StateFlow<String?> = _pending.asStateFlow()

    fun queue(prompt: String) {
        _pending.value = prompt
    }

    fun consume() {
        _pending.value = null
    }

    fun peek(): String? = _pending.value
}
