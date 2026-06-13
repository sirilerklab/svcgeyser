package com.sirilerklab.svcgeyser.auth

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import com.sirilerklab.svcgeyser.MainActivity

/**
 * Transparent trampoline Activity that catches the svcgeyser://auth redirect from the
 * Microsoft login page (Chrome Custom Tabs) and delivers the auth code to
 * [OAuthCallbackHolder], then immediately finishes.
 */
class OAuthRedirectActivity : Activity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        // จำเป็นต้องใส่ เพื่ออัปเดต Intent ของ Activity ให้เป็นตัวล่าสุด
        setIntent(intent) 
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        val uri = intent?.data
        val code = uri?.getQueryParameter("code")
        val error = uri?.getQueryParameter("error")

        if (code != null) {
            deliver(code)
        } else if (error != null) {
            // กรณี OAuth ส่ง error กลับมา (เช่น access_denied)
            deliverError(Exception("OAuth Error: $error"))
        } else {
            // กรณีไม่มีทั้ง code และ error (อาจจะเปิดแอปเข้ามาลอย ๆ)
            deliverError(Exception("No auth code received"))
        }
        
        // Pop Chrome Custom Tab off the stack and bring MainActivity to the foreground.
        startActivity(
            Intent(this, MainActivity::class.java).apply {
                addFlags(Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
            }
        )
        finish()
    }

    private fun deliver(code: String) {
        OAuthCallbackHolder.pending?.complete(code)
        OAuthCallbackHolder.pending = null
    }

    private fun deliverError(exception: Throwable) {
        // แนะนำให้ฝั่ง OAuthCallbackHolder มีช่องทางรับ Error ด้วย 
        // เช่น ใช้ CompletableFuture.completeExceptionally(exception)
        OAuthCallbackHolder.pending?.completeExceptionally(exception)
        OAuthCallbackHolder.pending = null
    }
}