package com.sirilerklab.svcgeyser.ui.bubble

import com.sirilerklab.svcgeyser.network.GroupInfo
import com.sirilerklab.svcgeyser.network.GroupType
import com.sirilerklab.svcgeyser.network.RoomMember
import kotlinx.coroutines.flow.MutableStateFlow

object BubbleController {
    val groups      = MutableStateFlow<List<GroupInfo>>(emptyList())
    val currentRoom = MutableStateFlow<String?>(null)
    val inGame      = MutableStateFlow(false)
    val isMuted     = MutableStateFlow(false)
    val isDeafened  = MutableStateFlow(false)
    val speakerOn   = MutableStateFlow(false)
    val joinError   = MutableStateFlow<String?>(null)
    val roomMembers = MutableStateFlow<List<RoomMember>>(emptyList())
    val speakingUuids = MutableStateFlow<Set<String>>(emptySet())

    var onJoin: ((name: String, password: String?) -> Unit)? = null
    var onCreateChannel: ((name: String, password: String?, groupType: GroupType) -> Unit)? = null
    var onLeave: (() -> Unit)? = null
    var onToggleMute: (() -> Unit)? = null
    var onToggleDeafen: (() -> Unit)? = null
    var onToggleSpeaker: (() -> Unit)? = null
    var onClearJoinError: (() -> Unit)? = null
}
