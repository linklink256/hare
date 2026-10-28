package me.rerere.rikkahub.service

import kotlin.uuid.Uuid

/** Only ordinary/root conversations may drive the floating window, even if a child is also a lead. */
internal suspend fun floatingMainConversations(
    running: Set<Uuid>,
    isTeamMember: suspend (Uuid) -> Boolean,
): Set<Uuid> {
    val main = linkedSetOf<Uuid>()
    for (id in running) {
        if (!isTeamMember(id)) main.add(id)
    }
    return main
}
