package com.sirilerklab.svcgeyser.service

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.os.IBinder
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.TextView
import com.sirilerklab.svcgeyser.ui.bubble.BubbleController
import kotlinx.coroutines.MainScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlin.math.abs

class BubbleService : Service() {

    companion object {
        fun start(ctx: Context) = ctx.startService(Intent(ctx, BubbleService::class.java))
        fun stop(ctx: Context)  = ctx.stopService(Intent(ctx, BubbleService::class.java))

        private const val PREFS = "bubble_prefs"
        private const val KEY_X = "pos_x"
        private const val KEY_Y = "pos_y"

        val COL_PURPLE = 0xFF6650A4.toInt()
        val COL_GREEN  = 0xFF4CAF50.toInt()
        val COL_RED    = 0xFFB3261E.toInt()
        val COL_SURFACE = 0xFFFFFBFE.toInt()
        val COL_ON_SURFACE = 0xFF1C1B1F.toInt()
        val COL_MUTED = 0xFF49454F.toInt()
    }

    private val scope = MainScope()
    private var wm: WindowManager? = null
    private var root: FrameLayout? = null
    private var bubbleBtn: ImageView? = null
    private var pill: LinearLayout? = null
    private var roomLabel: TextView? = null
    private var statusDot: View? = null
    private var leaveBtn: TextView? = null
    private lateinit var params: WindowManager.LayoutParams
    private var expanded = false
    private var pulseAnimator: ObjectAnimator? = null

    private var dragInitWinX = 0
    private var dragInitWinY = 0
    private var dragInitTouchX = 0f
    private var dragInitTouchY = 0f
    private var hasMoved = false

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    override fun onCreate() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildWindow()
        observe()
    }

    override fun onDestroy() {
        pulseAnimator?.cancel()
        scope.cancel()
        savePosition()
        root?.let { runCatching { wm?.removeView(it) } }
        root = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    private fun buildWindow() {
        val dm = resources.displayMetrics
        val dp = dm.density
        val circleSize = (40 * dp).toInt()

        root = FrameLayout(this)

        bubbleBtn = ImageView(this).apply {
            setImageResource(android.R.drawable.ic_btn_speak_now)
            setColorFilter(Color.WHITE)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            setPadding((8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt(), (8 * dp).toInt())
            background = circle(COL_PURPLE)
            elevation = 4 * dp
            layoutParams = FrameLayout.LayoutParams(circleSize, circleSize)
        }

        pill = buildPill(dp)
        pill!!.visibility = View.GONE
        pill!!.alpha = 0f

        root!!.addView(bubbleBtn)
        root!!.addView(pill)

        val prefs = getSharedPreferences(PREFS, MODE_PRIVATE)
        val defaultX = dm.widthPixels - (56 * dp).toInt()
        val defaultY = (200 * dp).toInt()

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = prefs.getInt(KEY_X, defaultX)
            y = prefs.getInt(KEY_Y, defaultY)
        }

        wm!!.addView(root, params)
        attachCollapsedTouchHandler()
    }

    private fun buildPill(dp: Float): LinearLayout {
        val hPad = (10 * dp).toInt()
        val vPad = (6 * dp).toInt()

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = roundRect(COL_SURFACE, 22 * dp)
            elevation = 6 * dp
            setPadding(hPad, vPad, hPad, vPad)
            layoutParams = FrameLayout.LayoutParams(
                (220 * dp).toInt(),
                FrameLayout.LayoutParams.WRAP_CONTENT,
            )

            statusDot = View(context).apply {
                background = circle(COL_MUTED)
                layoutParams = LinearLayout.LayoutParams((8 * dp).toInt(), (8 * dp).toInt()).apply {
                    rightMargin = (6 * dp).toInt()
                }
            }
            addView(statusDot)

            roomLabel = TextView(context).apply {
                text = "Not in channel"
                textSize = 12f
                setTextColor(COL_ON_SURFACE)
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            addView(roomLabel)

            addView(iconBtn("M", dp) { BubbleController.onToggleMute?.invoke() })
            addView(iconBtn("D", dp) { BubbleController.onToggleDeafen?.invoke() })
            leaveBtn = iconBtn("L", dp) {
                BubbleController.onLeave?.invoke()
                collapse()
            }
            addView(leaveBtn)
            addView(iconBtn("✕", dp) { collapse() })
        }
    }

    private fun iconBtn(label: String, dp: Float, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 11f
            gravity = Gravity.CENTER
            setTextColor(COL_PURPLE)
            val size = (28 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size).apply {
                leftMargin = (2 * dp).toInt()
            }
            setOnClickListener { onClick() }
        }
    }

    private fun attachCollapsedTouchHandler() {
        root!!.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragInitWinX = params.x
                    dragInitWinY = params.y
                    dragInitTouchX = e.rawX
                    dragInitTouchY = e.rawY
                    hasMoved = false
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    val dx = e.rawX - dragInitTouchX
                    val dy = e.rawY - dragInitTouchY
                    if (abs(dx) > 6 || abs(dy) > 6) {
                        hasMoved = true
                        params.x = (dragInitWinX + dx).toInt().coerceAtLeast(0)
                        params.y = (dragInitWinY + dy).toInt().coerceAtLeast(0)
                        wm?.updateViewLayout(root, params)
                    }
                    true
                }
                MotionEvent.ACTION_UP -> {
                    if (!hasMoved) expand() else savePosition()
                    true
                }
                else -> false
            }
        }
    }

    private fun expand() {
        expanded = true
        root!!.setOnTouchListener(null)
        bubbleBtn!!.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(150).withEndAction {
            bubbleBtn!!.visibility = View.GONE
        }.start()
        pill!!.visibility = View.VISIBLE
        pill!!.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(200)
            .setInterpolator(AccelerateDecelerateInterpolator()).start()
    }

    private fun collapse() {
        expanded = false
        pill!!.animate().scaleX(0.8f).scaleY(0.8f).alpha(0f).setDuration(150).withEndAction {
            pill!!.visibility = View.GONE
            bubbleBtn!!.visibility = View.VISIBLE
            bubbleBtn!!.scaleX = 1f
            bubbleBtn!!.scaleY = 1f
            bubbleBtn!!.alpha = 1f
            attachCollapsedTouchHandler()
        }.start()
        savePosition()
    }

    private fun observe() {
        scope.launch {
            combine(
                BubbleController.currentRoom,
                BubbleController.inGame,
                BubbleController.isMuted,
                BubbleController.isDeafened,
            ) { room, inGame, muted, _ ->
                Quad(room, inGame, muted)
            }.collect { (room, inGame, muted) ->
                refreshUI(room, inGame, muted)
            }
        }
    }

    private data class Quad<A, B, C>(val a: A, val b: B, val c: C)

    private fun refreshUI(currentRoom: String?, inGame: Boolean, isMuted: Boolean) {
        val color = when {
            isMuted -> COL_RED
            currentRoom != null -> COL_GREEN
            else -> COL_PURPLE
        }
        bubbleBtn?.background = circle(color)

        statusDot?.background = circle(color)
        roomLabel?.text = when {
            currentRoom != null -> currentRoom
            inGame -> "Not in channel"
            else -> "Waiting…"
        }
        leaveBtn?.visibility = if (currentRoom != null) View.VISIBLE else View.GONE

        // Pulse when in-room and unmuted
        if (currentRoom != null && !isMuted && !expanded) {
            startPulse()
        } else {
            stopPulse()
        }
    }

    private fun startPulse() {
        if (pulseAnimator?.isRunning == true) return
        bubbleBtn?.let { btn ->
            pulseAnimator = ObjectAnimator.ofPropertyValuesHolder(
                btn,
                PropertyValuesHolder.ofFloat(View.SCALE_X, 1f, 1.08f, 1f),
                PropertyValuesHolder.ofFloat(View.SCALE_Y, 1f, 1.08f, 1f),
                PropertyValuesHolder.ofFloat(View.ALPHA, 1f, 0.85f, 1f),
            ).apply {
                duration = 1500
                repeatCount = ObjectAnimator.INFINITE
                interpolator = AccelerateDecelerateInterpolator()
                start()
            }
        }
    }

    private fun stopPulse() {
        pulseAnimator?.cancel()
        pulseAnimator = null
        bubbleBtn?.scaleX = 1f
        bubbleBtn?.scaleY = 1f
        bubbleBtn?.alpha = 1f
    }

    private fun savePosition() {
        getSharedPreferences(PREFS, MODE_PRIVATE).edit()
            .putInt(KEY_X, params.x)
            .putInt(KEY_Y, params.y)
            .apply()
    }

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL
        setColor(color)
    }

    private fun roundRect(color: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE
        setColor(color)
        cornerRadius = radius
    }
}
