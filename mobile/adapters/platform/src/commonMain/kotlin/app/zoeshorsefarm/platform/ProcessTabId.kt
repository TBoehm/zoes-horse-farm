package app.zoeshorsefarm.platform

import kotlin.uuid.Uuid

/**
 * Identity of this app process for the crash guard (rule 4). Web: one id per browser tab. On a
 * phone there is one process, so the id lives as long as the process: [id] is created on first
 * use and then stays the same.
 *
 * Note for the guard: a restart after a crash is a NEW process with a new id, so a leftover
 * "rendering" mark of the crashed run looks like another live instance for the first seconds
 * (the guard's heartbeat window). Pass `tabId = null` to the guard when only one instance can
 * run (phones), which turns that check off.
 */
class ProcessTabId(
    private val createId: () -> String = { Uuid.random().toString() },
) {
    val id: String by lazy(createId)

    companion object {
        /** The id of this process, shared by everything that asks. */
        val app = ProcessTabId()
    }
}
