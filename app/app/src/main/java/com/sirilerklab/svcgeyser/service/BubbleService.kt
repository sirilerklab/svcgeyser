package com.sirilerklab.svcgeyser.service

import android.animation.ObjectAnimator
import android.animation.PropertyValuesHolder
import android.app.AlertDialog
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.IBinder
import android.text.InputType
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.EditText
import android.widget.FrameLayout
import android.widget.ImageView
import android.widget.LinearLayout
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import com.sirilerklab.svcgeyser.R
import com.sirilerklab.svcgeyser.network.GroupInfo
import com.sirilerklab.svcgeyser.network.GroupType
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
        val COL_OUTLINE = 0xFFE7E0EC.toInt()
        val COL_PRIMARY_CONTAINER = 0xFFEADDFF.toInt()
    }

    private val scope = MainScope()
    private var wm: WindowManager? = null
    private var root: FrameLayout? = null
    private var bubbleBtn: ImageView? = null
    private var panel: LinearLayout? = null
    private var headerRow: LinearLayout? = null
    private var roomLabel: TextView? = null
    private var statusDot: View? = null
    private var muteBtn: ImageView? = null
    private var deafenBtn: ImageView? = null
    private var speakerBtn: ImageView? = null
    private var channelsScroll: ScrollView? = null
    private var channelsContainer: LinearLayout? = null
    private var createBtn: TextView? = null
    private var emptyLabel: TextView? = null
    private lateinit var params: WindowManager.LayoutParams
    private var expanded = false
    private var pulseAnimator: ObjectAnimator? = null
    private var lastJoinError: String? = null

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

        panel = buildPanel(dp, dm.widthPixels, dm.heightPixels)
        panel!!.visibility = View.GONE
        panel!!.alpha = 0f

        root!!.addView(bubbleBtn)
        root!!.addView(panel)

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

    private fun buildPanel(dp: Float, screenWidth: Int, screenHeight: Int): LinearLayout {
        val pad = (12 * dp).toInt()
        val panelWidth = (280 * dp).toInt()
        val maxListHeight = channelListMaxHeight(dp, screenWidth, screenHeight)

        return LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = roundRect(COL_SURFACE, 16 * dp)
            elevation = 8 * dp
            setPadding(pad, pad, pad, pad)
            layoutParams = FrameLayout.LayoutParams(panelWidth, FrameLayout.LayoutParams.WRAP_CONTENT)

            headerRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                )
                statusDot = View(context).apply {
                    background = circle(COL_MUTED)
                    layoutParams = LinearLayout.LayoutParams((8 * dp).toInt(), (8 * dp).toInt()).apply {
                        rightMargin = (8 * dp).toInt()
                    }
                }
                addView(statusDot)
                roomLabel = TextView(context).apply {
                    text = "Not in channel"
                    textSize = 13f
                    setTypeface(null, Typeface.BOLD)
                    setTextColor(COL_ON_SURFACE)
                    maxLines = 1
                    layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
                }
                addView(roomLabel)
                addView(iconBtn("✕", dp, COL_MUTED) { collapse() })
            }
            addView(headerRow)

            addView(divider(dp))

            val audioRow = LinearLayout(context).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER
                setPadding(0, (4 * dp).toInt(), 0, (4 * dp).toInt())
                muteBtn = iconImage(R.drawable.ic_mic_on, dp) { BubbleController.onToggleMute?.invoke() }
                addView(muteBtn)
                deafenBtn = iconImage(R.drawable.ic_hearing_on, dp) { BubbleController.onToggleDeafen?.invoke() }
                addView(deafenBtn)
                speakerBtn = iconImage(R.drawable.ic_headphones, dp) { BubbleController.onToggleSpeaker?.invoke() }
                addView(speakerBtn)
            }
            addView(audioRow)

            addView(divider(dp))

            createBtn = TextView(context).apply {
                text = "+ Create channel"
                textSize = 13f
                gravity = Gravity.CENTER
                setTypeface(null, Typeface.BOLD)
                setTextColor(COL_PURPLE)
                background = roundRect(COL_PRIMARY_CONTAINER, 10 * dp)
                setPadding((12 * dp).toInt(), (10 * dp).toInt(), (12 * dp).toInt(), (10 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = (8 * dp).toInt() }
                setOnClickListener { showCreateDialog() }
            }
            addView(createBtn)

            addView(sectionLabel("CHANNELS", dp))

            channelsScroll = ScrollView(context).apply {
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    maxListHeight,
                )
                channelsContainer = LinearLayout(context).apply {
                    orientation = LinearLayout.VERTICAL
                    layoutParams = LinearLayout.LayoutParams(
                        LinearLayout.LayoutParams.MATCH_PARENT,
                        LinearLayout.LayoutParams.WRAP_CONTENT,
                    )
                }
                addView(channelsContainer)
            }
            addView(channelsScroll)

            emptyLabel = TextView(context).apply {
                text = "No channels yet"
                textSize = 12f
                setTextColor(COL_MUTED)
                setPadding(0, (4 * dp).toInt(), 0, 0)
                visibility = View.GONE
            }
            addView(emptyLabel)

            attachPanelDragHandler()
        }
    }

    private fun channelListMaxHeight(dp: Float, screenWidth: Int, screenHeight: Int): Int {
        val isLandscape = screenWidth > screenHeight
        return if (isLandscape) {
            (96 * dp).toInt()
        } else {
            (screenHeight * 0.28f).toInt().coerceIn((96 * dp).toInt(), (200 * dp).toInt())
        }
    }

    private fun updatePanelLayout() {
        val dm = resources.displayMetrics
        val dp = dm.density
        val maxH = channelListMaxHeight(dp, dm.widthPixels, dm.heightPixels)
        channelsScroll?.layoutParams = (channelsScroll?.layoutParams as? LinearLayout.LayoutParams)?.apply {
            height = maxH
        }
        clampPanelOnScreen(dm.widthPixels, dm.heightPixels)
        panel?.requestLayout()
        root?.let { wm?.updateViewLayout(it, params) }
    }

    private fun clampPanelOnScreen(screenWidth: Int, screenHeight: Int) {
        val panelView = panel ?: return
        panelView.measure(
            View.MeasureSpec.makeMeasureSpec((280 * resources.displayMetrics.density).toInt(), View.MeasureSpec.EXACTLY),
            View.MeasureSpec.makeMeasureSpec(0, View.MeasureSpec.UNSPECIFIED),
        )
        val panelH = panelView.measuredHeight
        val maxY = (screenHeight - panelH).coerceAtLeast(0)
        if (params.y > maxY) {
            params.y = maxY
            savePosition()
        }
        val panelW = panelView.measuredWidth
        val maxX = (screenWidth - panelW).coerceAtLeast(0)
        if (params.x > maxX) {
            params.x = maxX
            savePosition()
        }
    }

    private fun sectionLabel(text: String, dp: Float): TextView {
        return TextView(this).apply {
            this.text = text
            textSize = 10f
            setTextColor(COL_MUTED)
            setPadding(0, (6 * dp).toInt(), 0, (4 * dp).toInt())
        }
    }

    private fun divider(dp: Float): View {
        return View(this).apply {
            setBackgroundColor(COL_OUTLINE)
            layoutParams = LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT,
                (1 * dp).toInt(),
            ).apply {
                topMargin = (6 * dp).toInt()
                bottomMargin = (6 * dp).toInt()
            }
        }
    }

    private fun iconImage(drawableRes: Int, dp: Float, onClick: () -> Unit): ImageView {
        return ImageView(this).apply {
            setImageResource(drawableRes)
            scaleType = ImageView.ScaleType.CENTER_INSIDE
            val size = (36 * dp).toInt()
            val pad = (6 * dp).toInt()
            setPadding(pad, pad, pad, pad)
            layoutParams = LinearLayout.LayoutParams(size, size)
            setOnClickListener { onClick() }
        }
    }

    private fun iconBtn(label: String, dp: Float, color: Int, onClick: () -> Unit): TextView {
        return TextView(this).apply {
            text = label
            textSize = 14f
            gravity = Gravity.CENTER
            setTextColor(color)
            val size = (32 * dp).toInt()
            layoutParams = LinearLayout.LayoutParams(size, size)
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

    private fun attachPanelDragHandler() {
        headerRow?.setOnTouchListener { _, e ->
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
                    if (hasMoved) savePosition()
                    true
                }
                else -> false
            }
        }
    }

    private fun expand() {
        expanded = true
        updatePanelLayout()
        root!!.setOnTouchListener(null)
        bubbleBtn!!.animate().scaleX(0f).scaleY(0f).alpha(0f).setDuration(150).withEndAction {
            bubbleBtn!!.visibility = View.GONE
        }.start()
        panel!!.visibility = View.VISIBLE
        panel!!.animate().scaleX(1f).scaleY(1f).alpha(1f).setDuration(200)
            .setInterpolator(AccelerateDecelerateInterpolator()).start()
    }

    private fun collapse() {
        expanded = false
        panel!!.animate().scaleX(0.9f).scaleY(0.9f).alpha(0f).setDuration(150).withEndAction {
            panel!!.visibility = View.GONE
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
                combine(
                    BubbleController.groups,
                    BubbleController.currentRoom,
                    BubbleController.inGame,
                    BubbleController.isMuted,
                    BubbleController.isDeafened,
                ) { groups, room, inGame, muted, deafened ->
                    BubbleUiPartial(groups, room, inGame, muted, deafened)
                },
                BubbleController.speakerOn,
                BubbleController.joinError,
            ) { partial, speakerOn, joinError ->
                PanelState(
                    groups = partial.groups,
                    room = partial.room,
                    inGame = partial.inGame,
                    isMuted = partial.isMuted,
                    isDeafened = partial.isDeafened,
                    speakerOn = speakerOn,
                    joinError = joinError,
                )
            }.collect { state ->
                if (state.joinError != null && state.joinError != lastJoinError) {
                    Toast.makeText(this@BubbleService, "Join failed: ${state.joinError}", Toast.LENGTH_SHORT).show()
                    lastJoinError = state.joinError
                    BubbleController.onClearJoinError?.invoke()
                }
                if (state.joinError == null) lastJoinError = null
                refreshUI(state)
            }
        }
    }

    private data class BubbleUiPartial(
        val groups: List<GroupInfo>,
        val room: String?,
        val inGame: Boolean,
        val isMuted: Boolean,
        val isDeafened: Boolean,
    )

    private data class PanelState(
        val groups: List<GroupInfo>,
        val room: String?,
        val inGame: Boolean,
        val isMuted: Boolean,
        val isDeafened: Boolean,
        val speakerOn: Boolean,
        val joinError: String?,
    )

    private fun refreshUI(state: PanelState) {
        val color = when {
            state.isMuted -> COL_RED
            state.room != null -> COL_GREEN
            else -> COL_PURPLE
        }
        bubbleBtn?.background = circle(color)
        statusDot?.background = circle(color)

        roomLabel?.text = when {
            state.room != null -> state.room
            state.inGame -> "Not in channel"
            else -> "Waiting for player…"
        }

        muteBtn?.setImageResource(if (state.isMuted) R.drawable.ic_mic_off else R.drawable.ic_mic_on)
        deafenBtn?.setImageResource(if (state.isDeafened) R.drawable.ic_hearing_off else R.drawable.ic_hearing_on)
        speakerBtn?.setImageResource(if (state.speakerOn) R.drawable.ic_speaker_on else R.drawable.ic_headphones)

        val canManageChannels = state.inGame
        createBtn?.visibility = if (canManageChannels && state.room == null) View.VISIBLE else View.GONE
        createBtn?.alpha = if (canManageChannels) 1f else 0.5f

        rebuildChannelList(state)

        if (state.room != null && !state.isMuted && !expanded) {
            startPulse()
        } else {
            stopPulse()
        }
    }

    private fun rebuildChannelList(state: PanelState) {
        val container = channelsContainer ?: return
        val dp = resources.displayMetrics.density
        container.removeAllViews()

        if (!state.inGame) {
            emptyLabel?.visibility = View.VISIBLE
            emptyLabel?.text = "Join the Minecraft server first"
            return
        }

        if (state.groups.isEmpty()) {
            emptyLabel?.visibility = View.VISIBLE
            emptyLabel?.text = "No channels yet — create one below"
            return
        }

        emptyLabel?.visibility = View.GONE

        for (group in state.groups) {
            val isCurrent = state.room == group.name
            val canJoin = state.room == null

            val row = LinearLayout(this).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                background = roundRect(
                    if (isCurrent) COL_PRIMARY_CONTAINER else Color.TRANSPARENT,
                    8 * dp,
                )
                setPadding((8 * dp).toInt(), (6 * dp).toInt(), (8 * dp).toInt(), (6 * dp).toInt())
                layoutParams = LinearLayout.LayoutParams(
                    LinearLayout.LayoutParams.MATCH_PARENT,
                    LinearLayout.LayoutParams.WRAP_CONTENT,
                ).apply { bottomMargin = (4 * dp).toInt() }
            }

            val prefix = if (group.hasPassword) "🔒 " else "🔊 "
            val nameView = TextView(this).apply {
                text = "$prefix${group.name}"
                textSize = 13f
                setTextColor(COL_ON_SURFACE)
                maxLines = 1
                layoutParams = LinearLayout.LayoutParams(0, LinearLayout.LayoutParams.WRAP_CONTENT, 1f)
            }
            row.addView(nameView)

            val actionBtn = TextView(this).apply {
                textSize = 12f
                setTypeface(null, Typeface.BOLD)
                gravity = Gravity.CENTER
                setPadding((10 * dp).toInt(), (4 * dp).toInt(), (10 * dp).toInt(), (4 * dp).toInt())
            }

            if (isCurrent) {
                actionBtn.text = "Leave"
                actionBtn.setTextColor(COL_RED)
                actionBtn.setOnClickListener { BubbleController.onLeave?.invoke() }
            } else {
                actionBtn.text = "Join"
                actionBtn.setTextColor(if (canJoin) COL_PURPLE else COL_MUTED)
                actionBtn.isEnabled = canJoin
                actionBtn.alpha = if (canJoin) 1f else 0.4f
                actionBtn.setOnClickListener {
                    if (!canJoin) return@setOnClickListener
                    if (group.hasPassword) showPasswordDialog(group.name)
                    else BubbleController.onJoin?.invoke(group.name, null)
                }
            }
            row.addView(actionBtn)
            container.addView(row)
        }
    }

    private fun showOverlayDialog(builder: AlertDialog.Builder): AlertDialog {
        val dialog = builder.create()
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            dialog.window?.setType(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY)
        }
        dialog.show()
        return dialog
    }

    private fun showPasswordDialog(groupName: String) {
        val input = EditText(this).apply {
            hint = "Password"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
            setPadding(48, 32, 48, 16)
        }
        showOverlayDialog(
            AlertDialog.Builder(this)
                .setTitle("Join $groupName")
                .setView(input)
                .setPositiveButton("Join") { _, _ ->
                    val pw = input.text?.toString()?.trim().orEmpty()
                    if (pw.isNotBlank()) BubbleController.onJoin?.invoke(groupName, pw)
                    else Toast.makeText(this, "Password required", Toast.LENGTH_SHORT).show()
                }
                .setNegativeButton("Cancel", null),
        )
    }

    private fun showCreateDialog() {
        val dp = resources.displayMetrics.density
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding((20 * dp).toInt(), (12 * dp).toInt(), (20 * dp).toInt(), (4 * dp).toInt())
        }
        val nameInput = EditText(this).apply {
            hint = "Channel name"
            inputType = InputType.TYPE_CLASS_TEXT
        }
        val pwInput = EditText(this).apply {
            hint = "Password (optional)"
            inputType = InputType.TYPE_CLASS_TEXT or InputType.TYPE_TEXT_VARIATION_PASSWORD
        }
        layout.addView(nameInput)
        layout.addView(pwInput)

        val typeLabel = TextView(this).apply {
            text = "Group type"
            setPadding(0, (12 * dp).toInt(), 0, 0)
        }
        layout.addView(typeLabel)

        // One radio button per GroupType (Normal / Open / Isolation), default Isolation.
        val typeButtons = LinkedHashMap<Int, GroupType>()
        val typeGroup = RadioGroup(this).apply { orientation = RadioGroup.VERTICAL }
        GroupType.entries.forEach { type ->
            val id = View.generateViewId()
            typeButtons[id] = type
            typeGroup.addView(RadioButton(this).apply {
                this.id = id
                text = type.label
            })
        }
        typeButtons.entries.first { it.value == GroupType.ISOLATED }.let { typeGroup.check(it.key) }
        layout.addView(typeGroup)

        showOverlayDialog(
            AlertDialog.Builder(this)
                .setTitle("Create channel")
                .setView(layout)
                .setPositiveButton("Create") { _, _ ->
                    val name = nameInput.text?.toString()?.trim().orEmpty()
                    if (name.isBlank()) {
                        Toast.makeText(this, "Name required", Toast.LENGTH_SHORT).show()
                        return@setPositiveButton
                    }
                    val pw = pwInput.text?.toString()?.trim().orEmpty()
                    val type = typeButtons[typeGroup.checkedRadioButtonId] ?: GroupType.ISOLATED
                    BubbleController.onCreateChannel?.invoke(name, pw.ifBlank { null }, type)
                }
                .setNegativeButton("Cancel", null),
        )
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
