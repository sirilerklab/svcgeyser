package com.sirilerklab.svcgeyser.ui.navigation

/**
 * App navigation destinations. Phase 0 scaffold: routes are static strings; later
 * phases may switch to typed/parameterized routes (e.g. carrying the session token
 * or server address) as the real flow lands.
 */
sealed class Screen(val route: String) {
    data object Login : Screen("login")
    data object ServerConnect : Screen("server_connect")
    data object RoomList : Screen("room_list")
}
