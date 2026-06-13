package com.sirilerklab.svcgeyser.ui.bubble

import com.sirilerklab.svcgeyser.network.GroupInfo
import kotlinx.coroutines.flow.MutableStateFlow

/**
 * Singleton bridge between AppViewModel and BubbleService.
 * All writes happen on the main thread; BubbleService observes via StateFlows.
 */
object BubbleController {
    val groups      = MutableStateFlow<List<GroupInfo>>(emptyList())
    val currentRoom = MutableStateFlow<String?>(null)
    val inGame      = MutableStateFlow(false)

    // Callbacks wired by AppViewModel.init; called from BubbleService click handlers on main thread.
    var onJoin:  ((name: String, password: String?) -> Unit)? = null
    var onLeave: (() -> Unit)? = null
}
