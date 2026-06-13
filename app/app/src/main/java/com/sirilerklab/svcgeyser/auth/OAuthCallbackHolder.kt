package com.sirilerklab.svcgeyser.auth

import kotlinx.coroutines.CompletableDeferred

/**
 * Singleton rendezvous between [OAuthRedirectActivity] (which delivers the auth code)
 * and [LiveOAuthHelper.signIn] (which is awaiting it).
 *
 * Only one sign-in can be in flight at a time; starting a second replaces the previous
 * deferred, abandoning any stale attempt.
 */
object OAuthCallbackHolder {
    @Volatile var pending: CompletableDeferred<String?>? = null
}
