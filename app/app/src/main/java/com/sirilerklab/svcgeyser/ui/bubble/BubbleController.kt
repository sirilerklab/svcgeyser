package com.sirilerklab.svcgeyser.ui.bubble

import com.sirilerklab.svcgeyser.network.GroupInfo
import kotlinx.coroutines.flow.MutableStateFlow

object BubbleController {
    val groups      = MutableStateFlow<List<GroupInfo>>(emptyList())
    val currentRoom = MutableStateFlow<String?>(null)
    val inGame      = MutableStateFlow(false)
    val isMuted     = MutableStateFlow(false)
    val isDeafened  = MutableStateFlow(false)
    val speakerOn   = MutableStateFlow(false)

    var onJoin: ((name: String, password: String?) -> Unit)? = null
    var onLeave: (() -> Unit)? = null
    var onToggleMute: (() -> Unit)? = null
    var onToggleDeafen: (() -> Unit)? = null
    var onToggleSpeaker: (() -> Unit)? = null
}
