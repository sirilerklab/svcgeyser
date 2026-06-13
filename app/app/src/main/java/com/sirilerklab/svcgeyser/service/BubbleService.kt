package com.sirilerklab.svcgeyser.service

import android.app.PendingIntent
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
import android.widget.Button
import android.widget.FrameLayout
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.core.content.ContextCompat
import com.sirilerklab.svcgeyser.MainActivity
import com.sirilerklab.svcgeyser.network.GroupInfo
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

        // Material 3 light theme palette (hardcoded for use outside a themed Context)
        val COL_PURPLE           = 0xFF6650A4.toInt()
        val COL_GREEN            = 0xFF4CAF50.toInt()
        val COL_SURFACE          = 0xFFFFFBFE.toInt()
        val COL_ON_SURFACE       = 0xFF1C1B1F.toInt()
        val COL_MUTED            = 0xFF49454F.toInt()
        val COL_DIVIDER          = 0xFFE7E0EC.toInt()
        val COL_TONAL_CONTAINER  = 0xFFEADDFF.toInt()
        val COL_TONAL_ON         = 0xFF21005D.toInt()
        val COL_ERROR            = 0xFFB3261E.toInt()
    }

    private val scope = MainScope()
    private var wm: WindowManager? = null
    private var root: FrameLayout? = null
    private var bubbleBtn: TextView? = null
    private var card: LinearLayout? = null
    private var groupList: LinearLayout? = null
    private var statusLabel: TextView? = null
    private lateinit var params: WindowManager.LayoutParams
    private var expanded = false

    // Drag state — use rawX/rawY (screen coords) to avoid coordinate shift when window moves
    private var dragInitWinX = 0; private var dragInitWinY = 0
    private var dragInitTouchX = 0f; private var dragInitTouchY = 0f
    private var hasMoved = false

    // ---- Lifecycle -------------------------------------------------------

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int) = START_NOT_STICKY

    override fun onCreate() {
        wm = getSystemService(WINDOW_SERVICE) as WindowManager
        buildWindow()
        observe()
    }

    override fun onDestroy() {
        scope.cancel()
        root?.let { runCatching { wm?.removeView(it) } }
        root = null
    }

    override fun onBind(intent: Intent?): IBinder? = null

    // ---- Window setup ----------------------------------------------------

    private fun buildWindow() {
        val dm = resources.displayMetrics
        val dp = dm.density

        root = FrameLayout(this)

        // -- Collapsed bubble (circle) --
        val circleSize = (56 * dp).toInt()
        bubbleBtn = TextView(this).apply {
            text = "🎙"
            textSize = 22f
            gravity = Gravity.CENTER
            setTextColor(Color.WHITE)
            background = circle(COL_PURPLE)
            layoutParams = FrameLayout.LayoutParams(circleSize, circleSize)
        }

        // -- Expanded card --
        card = buildCard(dp)
        card!!.visibility = View.GONE

        root!!.addView(bubbleBtn)
        root!!.addView(card)

        params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity  = Gravity.TOP or Gravity.LEFT
            x        = dm.widthPixels - (72 * dp).toInt()
            y        = (200 * dp).toInt()
        }

        wm!!.addView(root, params)
        attachCollapsedTouchHandler()
    }

    private fun buildCard(dp: Float): LinearLayout {
        val pad = (12 * dp).toInt()
        val w   = (240 * dp).toInt()

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background  = roundRect(COL_SURFACE, 12 * dp)
            elevation   = 8 * dp
            layoutParams = FrameLayout.LayoutParams(w, FrameLayout.LayoutParams.WRAP_CONTENT)

            // Title row
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                setPadding(pad, (8 * dp).toInt(), (4 * dp).toInt(), 0)

                addView(TextView(context).apply {
                    text = "SVCGeyser"
                    textSize = 14f
                    setTypeface(typeface, android.graphics.Typeface.BOLD)
                    setTextColor(COL_ON_SURFACE)
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                })

                addView(Button(context).apply {
                    text = "✕"
                    textSize = 16f
                    setTextColor(COL_MUTED)
                    background = null
                    setPadding(pad, 0, pad, 0)
                    setOnClickListener { collapse() }
                })
            })

            // Status
            statusLabel = TextView(context).apply {
                textSize = 11f
                setTextColor(COL_MUTED)
                setPadding(pad, (4 * dp).toInt(), pad, (8 * dp).toInt())
            }
            addView(statusLabel)

            // Divider
            addView(View(context).apply {
                setBackgroundColor(COL_DIVIDER)
                layoutParams = LinearLayout.LayoutParams(LinearLayout.LayoutParams.MATCH_PARENT, 1)
            })

            // Section header
            addView(TextView(context).apply {
                text = "VOICE CHANNELS"
                textSize = 10f
                setTextColor(COL_MUTED)
                setPadding(pad, (8 * dp).toInt(), pad, (2 * dp).toInt())
            })

            // Scrollable group list
            groupList = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
            }
            addView(ScrollView(context).apply {
                isVerticalScrollBarEnabled = false
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    (200 * dp).toInt(),
                )
                addView(groupList)
            })
        }
    }

    // ---- Touch: drag (collapsed) ----------------------------------------

    private fun attachCollapsedTouchHandler() {
        root!!.setOnTouchListener { _, e ->
            when (e.action) {
                MotionEvent.ACTION_DOWN -> {
                    dragInitWinX   = params.x; dragInitWinY   = params.y
                    dragInitTouchX = e.rawX;   dragInitTouchY = e.rawY
                    hasMoved       = false
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
                    if (!hasMoved) expand()
                    true
                }
                else -> false
            }
        }
    }

    // ---- Expand / collapse -----------------------------------------------

    private fun expand() {
        expanded = true
        root!!.setOnTouchListener(null) // let card buttons receive clicks
        bubbleBtn!!.visibility = View.GONE
        card!!.visibility      = View.VISIBLE

        // Snap card to top-right corner so it's always visible
        val dm  = resources.displayMetrics
        val dp  = dm.density
        params.x = dm.widthPixels - (240 * dp).toInt() - (12 * dp).toInt()
        params.y = (80 * dp).toInt()
        wm?.updateViewLayout(root, params)
    }

    private fun collapse() {
        expanded = false
        card!!.visibility      = View.GONE
        bubbleBtn!!.visibility = View.VISIBLE
        attachCollapsedTouchHandler()
    }

    // ---- State observation -----------------------------------------------

    private fun observe() {
        scope.launch {
            combine(
                BubbleController.groups,
                BubbleController.currentRoom,
                BubbleController.inGame,
            ) { g, r, i -> Triple(g, r, i) }.collect { (groups, currentRoom, inGame) ->
                refreshUI(groups, currentRoom, inGame)
            }
        }
    }

    private fun refreshUI(groups: List<GroupInfo>, currentRoom: String?, inGame: Boolean) {
        val dp = resources.displayMetrics.density

        // Bubble color
        bubbleBtn?.background = circle(if (currentRoom != null) COL_GREEN else COL_PURPLE)

        // Status text
        statusLabel?.text = when {
            currentRoom != null -> "🔊 $currentRoom"
            inGame              -> "In game — choose a room"
            else                -> "Waiting for Bedrock player…"
        }

        // Rebuild group rows
        val layout = groupList ?: return
        layout.removeAllViews()

        if (groups.isEmpty()) {
            layout.addView(emptyLabel(dp))
        } else {
            groups.forEach { g -> layout.addView(groupRow(g, currentRoom, inGame, dp)) }
        }
    }

    private fun emptyLabel(dp: Float): TextView = TextView(this).apply {
        text = "No rooms available."
        textSize = 12f
        setTextColor(COL_MUTED)
        val p = (12 * dp).toInt()
        setPadding(p, p, p, p)
    }

    private fun groupRow(group: GroupInfo, currentRoom: String?, inGame: Boolean, dp: Float): View {
        val isCurrent = currentRoom == group.name
        val hPad      = (12 * dp).toInt()
        val vPad      = (5 * dp).toInt()

        return LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(hPad, vPad, (4 * dp).toInt(), vPad)
            if (isCurrent) background = rect(COL_TONAL_CONTAINER)

            // Icon
            addView(TextView(context).apply {
                text = if (group.hasPassword) "🔒" else "🔊"
                textSize = 13f
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { rightMargin = (8 * dp).toInt() }
            })

            // Name
            addView(TextView(context).apply {
                text = group.name
                textSize = 13f
                setTextColor(if (isCurrent) COL_TONAL_ON else COL_ON_SURFACE)
                if (isCurrent) setTypeface(typeface, android.graphics.Typeface.BOLD)
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            })

            // Action button
            addView(Button(context).apply {
                val p = (4 * dp).toInt()
                setPadding(p, 0, p, 0)
                background = null

                if (isCurrent) {
                    text = "Leave"
                    setTextColor(COL_ERROR)
                    setOnClickListener {
                        BubbleController.onLeave?.invoke()
                        collapse()
                    }
                } else if (group.hasPassword) {
                    // Locked rooms: bring app to foreground for password entry
                    text = "Open"
                    setTextColor(COL_PURPLE)
                    isEnabled = inGame && currentRoom == null
                    alpha = if (isEnabled) 1f else 0.4f
                    setOnClickListener {
                        startActivity(Intent(applicationContext, MainActivity::class.java).apply {
                            flags = Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_NEW_TASK
                        })
                        collapse()
                    }
                } else {
                    text = "Join"
                    setTextColor(COL_PURPLE)
                    isEnabled = inGame && currentRoom == null
                    alpha = if (isEnabled) 1f else 0.4f
                    setOnClickListener {
                        BubbleController.onJoin?.invoke(group.name, null)
                        collapse()
                    }
                }
            })
        }
    }

    // ---- Drawable helpers ------------------------------------------------

    private fun circle(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.OVAL; setColor(color)
    }

    private fun roundRect(color: Int, radius: Float) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; setColor(color); cornerRadius = radius
    }

    private fun rect(color: Int) = GradientDrawable().apply {
        shape = GradientDrawable.RECTANGLE; setColor(color)
    }

}
