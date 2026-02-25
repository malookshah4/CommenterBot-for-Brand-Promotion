package com.codebage.commenterbot

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import kotlin.random.Random

class TikTokReplyService : AccessibilityService() {

    companion object {
        val status = mutableStateOf("Service not running")
        val logs = mutableStateListOf<String>()
        val botEnabled = mutableStateOf(false)
        val totalReplies = mutableStateOf(0)
        val videosProcessed = mutableStateOf(0)
        var instance: TikTokReplyService? = null

        private const val TIKTOK_PACKAGE = "com.zhiliaoapp.musically"
        private const val MAX_RETRIES = 3
        private const val MAX_LOG_LINES = 150
        private const val MAX_SCROLL_ATTEMPTS = 4
    }

    private val handler = Handler(Looper.getMainLooper())

    private var repliesInCurrentVideo = 0
    private var targetReplies = 0
    private var retryCount = 0
    private var scrollAttempts = 0
    private var pendingReply = ""
    private var pasteAttempt = 0
    private var sendAttempt = 0

    private var currentState = State.IDLE
    private var isTikTokForeground = false
    private var sessionStartTime = 0L

    private val timeFormat = SimpleDateFormat("HH:mm:ss", Locale.getDefault())

    enum class State {
        IDLE,
        FIND_COMMENTS_BUTTON,
        WAIT_COMMENTS_PANEL,
        FIND_REPLY_BUTTON,
        TAP_REPLY,
        WAIT_INPUT_FIELD,
        ENTER_TEXT,
        VERIFY_TEXT,
        TAP_SEND,
        WAIT_AFTER_SEND,
        SCROLL_COMMENTS,
        CLOSE_COMMENTS,
        SWIPE_NEXT_VIDEO,
        WAIT_NEXT_VIDEO
    }

    // ── Lifecycle ────────────────────────────────────────────────────────

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        ReplyManager.init(this, resources.getStringArray(R.array.promo_replies).toList())
        status.value = "Service ready — waiting"
        log("Service connected. ${ReplyManager.getBotReplies().size} bot replies active.")
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        if (event?.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return

        // Only track foreground changes for TikTok or well-known user apps.
        // Ignore ALL other packages (keyboards, system UI, clipboard toasts, etc.)
        // that fire window-state events but don't mean TikTok left foreground.
        if (pkg != TIKTOK_PACKAGE && !isLauncherOrUserApp(pkg)) return

        val wasForeground = isTikTokForeground
        isTikTokForeground = (pkg == TIKTOK_PACKAGE)

        if (isTikTokForeground && !wasForeground) {
            log("TikTok is in foreground")
            if (botEnabled.value && currentState == State.IDLE) {
                startBotLoop()
            }
        } else if (!isTikTokForeground && wasForeground) {
            log("TikTok left foreground — pausing (pkg=$pkg)")
        }
    }

    override fun onInterrupt() {
        log("Service interrupted")
        haltLoop()
    }

    override fun onDestroy() {
        super.onDestroy()
        instance = null
        haltLoop()
        status.value = "Service not running"
    }

    // ── Public controls ─────────────────────────────────────────────────

    fun startBot() {
        botEnabled.value = true
        totalReplies.value = 0
        videosProcessed.value = 0
        sessionStartTime = System.currentTimeMillis()
        if (isTikTokForeground) {
            startBotLoop()
        } else {
            status.value = "Bot enabled — open TikTok to begin"
            log("Bot enabled. Waiting for TikTok…")
        }
    }

    fun stopBot() {
        botEnabled.value = false
        haltLoop()
        status.value = "Bot stopped"
        log("Bot stopped by user.")
    }

    // ── Loop management ─────────────────────────────────────────────────

    private fun startBotLoop() {
        if (currentState != State.IDLE) return
        log("=== Bot loop started ===")
        transition(State.FIND_COMMENTS_BUTTON, 500)
    }

    private fun haltLoop() {
        handler.removeCallbacksAndMessages(null)
        currentState = State.IDLE
        repliesInCurrentVideo = 0
        retryCount = 0
        scrollAttempts = 0
        pasteAttempt = 0
        sendAttempt = 0
    }

    private fun transition(next: State, delayMs: Long = 0) {
        currentState = next
        handler.postDelayed({ executeState() }, delayMs)
    }

    private fun transition(next: State, delayMs: Int) = transition(next, delayMs.toLong())

    private fun executeState() {
        if (!botEnabled.value) {
            haltLoop(); status.value = "Bot stopped"; return
        }
        if (!isTikTokForeground) {
            currentState = State.IDLE; status.value = "Waiting for TikTok…"; return
        }

        // Auto-stop checks (only at safe transition points)
        if (currentState == State.FIND_REPLY_BUTTON || currentState == State.FIND_COMMENTS_BUTTON) {
            val settings = ReplyManager.botSettings.value
            if (settings.autoStopAfterReplies > 0 && totalReplies.value >= settings.autoStopAfterReplies) {
                log("Auto-stop: reached ${settings.autoStopAfterReplies} replies")
                stopBot(); return
            }
            if (settings.autoStopAfterMinutes > 0 && sessionStartTime > 0) {
                val elapsed = (System.currentTimeMillis() - sessionStartTime) / 60_000
                if (elapsed >= settings.autoStopAfterMinutes) {
                    log("Auto-stop: reached ${settings.autoStopAfterMinutes} minutes")
                    stopBot(); return
                }
            }
        }

        when (currentState) {
            State.IDLE                 -> {}
            State.FIND_COMMENTS_BUTTON -> onFindCommentsButton()
            State.WAIT_COMMENTS_PANEL  -> onWaitCommentsPanel()
            State.FIND_REPLY_BUTTON    -> onFindReplyButton()
            State.TAP_REPLY            -> onTapReply()
            State.WAIT_INPUT_FIELD     -> onWaitInputField()
            State.ENTER_TEXT           -> onEnterText()
            State.VERIFY_TEXT          -> onVerifyText()
            State.TAP_SEND             -> onTapSend()
            State.WAIT_AFTER_SEND      -> onWaitAfterSend()
            State.SCROLL_COMMENTS      -> onScrollComments()
            State.CLOSE_COMMENTS       -> onCloseComments()
            State.SWIPE_NEXT_VIDEO     -> onSwipeNextVideo()
            State.WAIT_NEXT_VIDEO      -> onWaitNextVideo()
        }
    }

    // ── State handlers ──────────────────────────────────────────────────

    private fun onFindCommentsButton() {
        status.value = "Finding comments…"
        val root = rootInActiveWindow ?: return retry(State.FIND_COMMENTS_BUTTON, "No window")

        if (hasCommentsDisabled(root)) {
            log("Comments OFF — skip")
            retryCount = 0; transition(State.SWIPE_NEXT_VIDEO, 200); return
        }

        val btn = findByContentDesc(root, "comment", partial = true)
            ?: findByText(root, "Comments")
            ?: findByContentDesc(root, "Comment", partial = true)

        if (btn != null) {
            val desc = btn.contentDescription?.toString() ?: ""
            log("Comment button desc: '$desc'")
            // Extract the comment count number from the description
            // e.g. "Read or add comments. 258 comments" → 258
            // e.g. "0 comments" → 0
            val countMatch = Regex("(\\d[\\d,]*)\\s*comment", RegexOption.IGNORE_CASE).find(desc)
            val commentCount = countMatch?.groupValues?.get(1)
                ?.replace(",", "")?.toIntOrNull() ?: -1
            if (commentCount == 0 || desc.trim() == "0") {
                log("0 comments — skip")
                retryCount = 0; transition(State.SWIPE_NEXT_VIDEO, 200); return
            }
            gestureTapNode(btn)
            retryCount = 0
            targetReplies = Random.nextInt(29, 41)
            repliesInCurrentVideo = 0
            scrollAttempts = 0
            log("Opened comments — target: $targetReplies")
            transition(State.WAIT_COMMENTS_PANEL, randomDelay(800, 1400))
        } else {
            retry(State.FIND_COMMENTS_BUTTON, "Comments button not found")
        }
    }

    private fun onWaitCommentsPanel() {
        status.value = "Waiting for comments…"
        val root = rootInActiveWindow ?: return retry(State.WAIT_COMMENTS_PANEL, "No window")

        if (hasCommentsDisabled(root)) {
            log("Comments disabled in panel")
            retryCount = 0; transition(State.CLOSE_COMMENTS, 150); return
        }

        val hasReplyBtn = findByText(root, "Reply") != null

        if (hasReplyBtn) {
            retryCount = 0; log("Comments panel open")
            transition(State.FIND_REPLY_BUTTON, 200)
        } else {
            if (retryCount >= 2) {
                log("Panel won't open — skip"); retryCount = 0
                transition(State.CLOSE_COMMENTS, 150); return
            }
            retry(State.WAIT_COMMENTS_PANEL, "Panel not visible")
        }
    }

    private fun onFindReplyButton() {
        if (repliesInCurrentVideo >= targetReplies) {
            log("Target reached ($targetReplies). Next video.")
            transition(State.CLOSE_COMMENTS, 200); return
        }

        // Check rate limits before starting a new reply
        if (!ReplyManager.canSendReply()) {
            if (ReplyManager.isDailyLimitHit()) {
                log("Daily limit reached. Stopping.")
                stopBot(); return
            }
            log("Hourly limit reached — waiting 60s")
            transition(State.FIND_REPLY_BUTTON, 60_000); return
        }

        status.value = "Reply ${repliesInCurrentVideo + 1}/$targetReplies"
        val root = rootInActiveWindow ?: return retry(State.FIND_REPLY_BUTTON, "No window")
        val btn = findByText(root, "Reply") ?: findByText(root, "reply")

        if (btn != null) {
            // Keyword filtering: if keywords are set, only reply to matching comments
            val keywords = ReplyManager.getActiveKeywords()
            if (keywords.isNotEmpty()) {
                val commentText = getCommentTextNearNode(btn)
                if (!keywords.any { kw -> commentText.contains(kw, ignoreCase = true) }) {
                    scrollAttempts++
                    if (scrollAttempts < MAX_SCROLL_ATTEMPTS) {
                        performSmallCommentScroll()
                        transition(State.FIND_REPLY_BUTTON, 500)
                    } else {
                        scrollAttempts = 0
                        transition(State.CLOSE_COMMENTS, 150)
                    }
                    return
                }
            }
            retryCount = 0; scrollAttempts = 0
            transition(State.TAP_REPLY, 100)
        } else if (scrollAttempts < MAX_SCROLL_ATTEMPTS) {
            scrollAttempts++
            transition(State.SCROLL_COMMENTS, 200)
        } else {
            log("No more reply buttons"); scrollAttempts = 0
            transition(State.CLOSE_COMMENTS, 150)
        }
    }

    private fun onTapReply() {
        val root = rootInActiveWindow ?: return retry(State.TAP_REPLY, "No window")
        val btn = findByText(root, "Reply") ?: findByText(root, "reply")
        if (btn != null) {
            gestureTapNode(btn)
            log("Tapped Reply")
            retryCount = 0
            transition(State.WAIT_INPUT_FIELD, randomDelay(400, 700))
        } else {
            retry(State.FIND_REPLY_BUTTON, "Reply vanished")
        }
    }

    private fun onWaitInputField() {
        status.value = "Waiting for input…"
        val root = rootInActiveWindow ?: return retry(State.WAIT_INPUT_FIELD, "No window")

        // Try finding input in current window first, then all windows
        val input = findReplyInputField(root) ?: findInputAcrossAllWindows()
        if (input != null) {
            val cls = input.className?.toString() ?: "?"
            val txt = input.text?.toString() ?: ""
            val hint = input.hintText?.toString() ?: ""
            log("Input found: cls=$cls text='${txt.take(15)}' hint='${hint.take(15)}' edit=${input.isEditable}")

            // Gesture tap the input to ensure proper focus
            gestureTapNode(input)

            retryCount = 0
            pasteAttempt = 0
            val botReplies = ReplyManager.getBotReplies()
            if (botReplies.isEmpty()) {
                log("No bot replies configured!"); haltLoop(); return
            }
            pendingReply = botReplies[Random.nextInt(botReplies.size)]
            pendingReply = ReplyManager.processTemplate(pendingReply)
            log("Selected reply: '${pendingReply.take(40)}…'")
            transition(State.ENTER_TEXT, 300)
        } else {
            if (retryCount == 0) tapInputArea()
            retry(State.WAIT_INPUT_FIELD, "Input not found")
        }
    }

    private fun onEnterText() {
        status.value = "Pasting reply…"
        val root = rootInActiveWindow ?: return retry(State.ENTER_TEXT, "No window")
        val input = findReplyInputField(root) ?: findInputAcrossAllWindows()
        if (input == null) {
            retry(State.ENTER_TEXT, "Input lost"); return
        }

        input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)

        when (pasteAttempt) {
            0 -> {
                // Attempt 1: ACTION_SET_TEXT directly (most reliable, replaces everything)
                log("Paste attempt 1: SET_TEXT")
                val args = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE,
                        pendingReply
                    )
                }
                input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
            }
            1 -> {
                // Attempt 2: Clear with SET_TEXT("") then clipboard paste
                log("Paste attempt 2: clear + paste")
                val clearArgs = Bundle().apply {
                    putCharSequence(
                        AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, ""
                    )
                }
                input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, clearArgs)
                setClipboard(pendingReply)
                input.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }
            2 -> {
                // Attempt 3: Select-all + clipboard paste
                log("Paste attempt 3: select-all + paste")
                setClipboard(pendingReply)
                val selArgs = Bundle().apply {
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_START_INT, 0)
                    putInt(AccessibilityNodeInfo.ACTION_ARGUMENT_SELECTION_END_INT, 99999)
                }
                input.performAction(AccessibilityNodeInfo.ACTION_SET_SELECTION, selArgs)
                input.performAction(AccessibilityNodeInfo.ACTION_PASTE)
            }
        }

        transition(State.VERIFY_TEXT, 400)
    }

    private fun onVerifyText() {
        val root = rootInActiveWindow ?: return retry(State.VERIFY_TEXT, "No window")
        val input = findReplyInputField(root) ?: findInputAcrossAllWindows()

        val currentText = input?.text?.toString() ?: ""
        log("Verify: field='${currentText.take(40)}'")

        // Check if the field contains OUR SPECIFIC reply (first 15 chars must match)
        val snippet = pendingReply.take(15)
        val hasOurText = currentText.length > 10 && currentText.startsWith(snippet, ignoreCase = true)

        if (hasOurText) {
            log("Text confirmed!")
            retryCount = 0; pasteAttempt = 0; sendAttempt = 0
            transition(State.TAP_SEND, 200)
        } else {
            pasteAttempt++
            if (pasteAttempt <= 2) {
                log("Wrong text: '${currentText.take(25)}' expected '${snippet}…' — retry")
                transition(State.ENTER_TEXT, 300)
            } else {
                log("Paste failed after 3 attempts — skip this comment")
                pasteAttempt = 0; retryCount = 0
                performGlobalAction(GLOBAL_ACTION_BACK)
                transition(State.FIND_REPLY_BUTTON, 500)
            }
        }
    }

    private fun onTapSend() {
        status.value = "Sending…"
        val root = rootInActiveWindow ?: return retry(State.TAP_SEND, "No window")
        val input = findReplyInputField(root) ?: findInputAcrossAllWindows()

        // Helper: tap a found send button and transition
        fun tapSend(btn: AccessibilityNodeInfo): Boolean {
            gestureTapNode(btn)
            btn.performAction(AccessibilityNodeInfo.ACTION_CLICK)
            transition(State.WAIT_AFTER_SEND, 800)
            return true
        }

        // ── Step 1: Resource ID search (most precise — known TikTok IDs) ──
        val byId = findSendByResourceId()
        if (byId != null) { tapSend(byId); return }

        // ── Step 2: Structural tree navigation (walk up from input, find rightmost button) ──
        if (input != null) {
            val byStructure = findSendByStructure(input)
            if (byStructure != null) { tapSend(byStructure); return }
        }

        // ── Step 3: Text/desc/viewId search across all windows ──
        val byText = findSendButtonAcrossAllWindows()
        if (byText != null) {
            val cls = byText.className?.toString()?.substringAfterLast(".") ?: "?"
            val desc = byText.contentDescription?.toString()?.take(30) ?: ""
            val txt = byText.text?.toString()?.take(20) ?: ""
            log("Send by text/desc: [$cls] txt='$txt' desc='$desc'")
            tapSend(byText); return
        }

        // ── Step 4: Position-based search below input ──
        if (input != null) {
            val sendBelow = findSendBelowInput(input)
            if (sendBelow != null) {
                val r = Rect(); sendBelow.getBoundsInScreen(r)
                log("Send below input: @(${r.left},${r.top})-(${r.right},${r.bottom})")
                tapSend(sendBelow); return
            }
        }

        if (input == null) {
            retry(State.TAP_SEND, "No input found"); return
        }

        val inputRect = Rect()
        input.getBoundsInScreen(inputRect)
        val dm = resources.displayMetrics

        // ── Step 5+: Fallback strategies when ALL node searches fail ──
        when (sendAttempt) {
            0 -> {
                log("Fallback #0: IME_ENTER")
                input.performAction(AccessibilityNodeInfo.AccessibilityAction.ACTION_IME_ENTER.id)
            }
            1 -> {
                val tapX = dm.widthPixels * 0.90f
                val tapY = inputRect.bottom.toFloat() + 25f
                log("Fallback #1: position tap ($tapX, $tapY)")
                performTapGesture(tapX, tapY)
            }
            2 -> {
                val tapX = dm.widthPixels * 0.88f
                val tapY = inputRect.bottom.toFloat() + 35f
                log("Fallback #2: position tap ($tapX, $tapY)")
                performTapGesture(tapX, tapY)
            }
            3 -> {
                val tapX = dm.widthPixels * 0.85f
                val tapY = inputRect.bottom.toFloat() + 20f
                log("Fallback #3: position tap ($tapX, $tapY)")
                performTapGesture(tapX, tapY)
            }
        }

        transition(State.WAIT_AFTER_SEND, 800)
    }

    private fun onWaitAfterSend() {
        val root = rootInActiveWindow

        // Check if our specific reply is still in the field.
        // After a successful send, TikTok clears the field but may show hint text
        // like "Add comment..." — so we check for OUR text, not just empty field.
        val input = if (root != null) findReplyInputField(root) else null
        val fieldText = input?.text?.toString() ?: ""
        val snippet = pendingReply.take(15)
        val stillHasOurReply = fieldText.startsWith(snippet, ignoreCase = true)

        if (!stillHasOurReply) {
            // SUCCESS — our reply text is gone, TikTok accepted the reply!
            repliesInCurrentVideo++
            totalReplies.value++
            ReplyManager.recordReply()
            sendAttempt = 0
            retryCount = 0
            log("Reply sent! (${repliesInCurrentVideo}/$targetReplies) [total:${totalReplies.value}]")
            // Scroll comments to move past the replied comment.
            // Do NOT press BACK — TikTok already returned to normal comment view,
            // and BACK would close the entire comments panel.
            performSmallCommentScroll()
            transition(State.FIND_REPLY_BUTTON, ReplyManager.getReplyDelay())
        } else {
            // FAILED — input still has text, send didn't work
            log("Post-send: field='${fieldText.take(30)}' (len=${fieldText.length})")
            sendAttempt++
            if (sendAttempt <= 3) {
                log("Send failed — trying strategy #$sendAttempt")
                transition(State.TAP_SEND, 400)
            } else {
                log("Send failed after 4 strategies — skip this comment")
                sendAttempt = 0
                retryCount = 0
                performGlobalAction(GLOBAL_ACTION_BACK)
                transition(State.FIND_REPLY_BUTTON, 600)
            }
        }
    }

    private fun onScrollComments() {
        val root = rootInActiveWindow
        if (root != null) {
            val scrollable = findScrollable(root)
            if (scrollable != null) scrollable.performAction(AccessibilityNodeInfo.ACTION_SCROLL_FORWARD)
            else performCommentScrollGesture()
        } else {
            performCommentScrollGesture()
        }
        transition(State.FIND_REPLY_BUTTON, 400)
    }

    private fun onCloseComments() {
        performGlobalAction(GLOBAL_ACTION_BACK)
        log("Closed comments")
        transition(State.SWIPE_NEXT_VIDEO, 300)
    }

    private fun onSwipeNextVideo() {
        performSwipeUp()
        videosProcessed.value++
        log("Next video (#${videosProcessed.value})")
        transition(State.WAIT_NEXT_VIDEO, randomDelay(800, 1500))
    }

    private fun onWaitNextVideo() {
        transition(State.FIND_COMMENTS_BUTTON, randomDelay(600, 1000))
    }

    // ── Input field detection (FIXED) ───────────────────────────────────

    /**
     * Finds the reply input field using multiple strategies.
     * Rejects nodes that are clearly counters (pure digit text).
     */
    private fun findReplyInputField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val dm = resources.displayMetrics
        val bottomHalf = dm.heightPixels / 2f

        // Helper: reject pure digit nodes (like counters showing "0", "123", etc.)
        fun isCounter(node: AccessibilityNodeInfo): Boolean {
            val txt = node.text?.toString() ?: return false
            return txt.isNotEmpty() && txt.length < 8 && txt.all { it.isDigit() || it == ',' || it == '.' }
        }

        // 1. Node that currently has input focus (most reliable after tapping Reply)
        val focused = root.findFocus(AccessibilityNodeInfo.FOCUS_INPUT)
        if (focused != null && !isCounter(focused)) {
            if (focused.isEditable || focused.className?.toString()?.contains("Edit", ignoreCase = true) == true) {
                return focused
            }
        }

        // 2. EditText or similar class at bottom half of screen
        val editTexts = collectNodes(root) { node ->
            val cls = node.className?.toString() ?: ""
            (cls.contains("EditText", ignoreCase = true) ||
             cls.contains("AutoComplete", ignoreCase = true) ||
             cls.contains("EditBox", ignoreCase = true)) && !isCounter(node)
        }
        val bottomEdit = editTexts.filter { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            rect.top > bottomHalf
        }.maxByOrNull { node ->
            val rect = Rect()
            node.getBoundsInScreen(rect)
            rect.top
        }
        if (bottomEdit != null) return bottomEdit
        if (editTexts.isNotEmpty()) return editTexts.first()

        // 3. Any editable node (TikTok may use custom view classes)
        val editable = traverse(root) { it.isEditable && !isCounter(it) }
        if (editable != null) return editable

        // 4. Node with hint text about commenting/replying
        val hintNode = traverse(root) { node ->
            val hint = node.hintText?.toString() ?: ""
            hint.contains("comment", ignoreCase = true) ||
            hint.contains("reply", ignoreCase = true) ||
            hint.contains("Add", ignoreCase = true) ||
            hint.contains("add", ignoreCase = true)
        }
        if (hintNode != null) return hintNode

        // 5. Any focused node at bottom that's not a counter
        if (focused != null && !isCounter(focused)) {
            val rect = Rect()
            focused.getBoundsInScreen(rect)
            if (rect.top > bottomHalf) return focused
        }

        // 6. Any focusable node at the very bottom of screen
        val veryBottom = dm.heightPixels * 0.85f
        return traverse(root) { node ->
            if (!node.isFocusable || isCounter(node)) return@traverse false
            val rect = Rect()
            node.getBoundsInScreen(rect)
            rect.top > veryBottom && node.className?.toString() != "android.widget.Button"
        }
    }

    /**
     * Collects all nodes matching a predicate.
     */
    private fun collectNodes(
        node: AccessibilityNodeInfo,
        predicate: (AccessibilityNodeInfo) -> Boolean
    ): List<AccessibilityNodeInfo> {
        val result = mutableListOf<AccessibilityNodeInfo>()
        if (predicate(node)) result.add(node)
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            result.addAll(collectNodes(child, predicate))
        }
        return result
    }

    private fun findInputAcrossAllWindows(): AccessibilityNodeInfo? {
        try {
            for (window in windows) {
                val windowRoot = window.root ?: continue
                val input = findReplyInputField(windowRoot)
                if (input != null) return input
            }
        } catch (_: Exception) { }
        return null
    }

    /** Searches ALL accessibility windows for a send/post button by text, desc, or viewId.
     *  Validates position: send button must be on the RIGHT side and BOTTOM half of screen.
     *  Skips keyboard windows and generic "post" viewId (matches poster avatars). */
    private fun findSendButtonAcrossAllWindows(): AccessibilityNodeInfo? {
        val dm = resources.displayMetrics
        val bottomHalf = dm.heightPixels / 2f
        try {
            for (window in windows) {
                if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                val wRoot = window.root ?: continue
                val btn = findByContentDesc(wRoot, "Send", partial = false)
                    ?: findByText(wRoot, "Send")
                    ?: findByText(wRoot, "Post")
                    ?: findByContentDesc(wRoot, "Post", partial = false)
                    ?: findNodeByViewId(wRoot, "send")
                    ?: findNodeByViewId(wRoot, "submit")
                if (btn != null) {
                    val r = Rect()
                    btn.getBoundsInScreen(r)
                    // Send button must be on the RIGHT side and in the BOTTOM half
                    if (r.left > dm.widthPixels * 0.5f && r.top > bottomHalf) return btn
                }
            }
        } catch (_: Exception) { }
        return null
    }

    /** Searches all windows by known TikTok resource IDs for the send button.
     *  This is the most reliable approach — immune to layout changes and position shifts. */
    private fun findSendByResourceId(): AccessibilityNodeInfo? {
        val knownIds = listOf(
            "com.zhiliaoapp.musically:id/cfm",       // Known TikTok send/confirm button
            "com.zhiliaoapp.musically:id/send",
            "com.zhiliaoapp.musically:id/submit",
            "com.zhiliaoapp.musically:id/send_btn",
            "com.zhiliaoapp.musically:id/btn_send"
        )
        try {
            for (window in windows) {
                if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                val wRoot = window.root ?: continue
                for (resId in knownIds) {
                    val nodes = wRoot.findAccessibilityNodeInfosByViewId(resId)
                    if (nodes.isNullOrEmpty()) continue
                    for (node in nodes) {
                        if (node.isClickable || node.isEnabled) {
                            val r = Rect()
                            node.getBoundsInScreen(r)
                            if (r.width() > 0 && r.height() > 0) {
                                log("Send by resourceId: $resId @(${r.left},${r.top})")
                                return node
                            }
                        }
                    }
                }
            }
        } catch (_: Exception) { }
        return null
    }

    /** Walks UP the accessibility tree from the input field to find the send button
     *  by structure: the rightmost clickable non-EditText node in the input's parent container.
     *  In TikTok's comment bar layout, send is always the rightmost button in the toolbar. */
    private fun findSendByStructure(input: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val dm = resources.displayMetrics
        val screenCenterX = dm.widthPixels / 2f
        var parent: AccessibilityNodeInfo? = input

        // Walk up to 5 parent levels to find the toolbar/container
        repeat(5) {
            parent = parent?.parent ?: return null
            val container = parent ?: return null

            // Collect clickable children that are NOT text fields
            val clickables = mutableListOf<AccessibilityNodeInfo>()
            for (i in 0 until container.childCount) {
                val child = container.getChild(i) ?: continue
                collectClickableLeaves(child, clickables)
            }

            // Filter: must be on right side of screen, not an EditText
            val rightSideBtns = clickables.filter { node ->
                val cls = node.className?.toString() ?: ""
                if (cls.contains("EditText", ignoreCase = true)) return@filter false
                val r = Rect()
                node.getBoundsInScreen(r)
                r.centerX() > screenCenterX && r.width() in 10..300 && r.height() in 10..300
            }

            if (rightSideBtns.isNotEmpty()) {
                // Pick the rightmost one — that's the send button
                val sendBtn = rightSideBtns.maxByOrNull { n -> val r = Rect(); n.getBoundsInScreen(r); r.centerX() }
                if (sendBtn != null) {
                    val r = Rect(); sendBtn.getBoundsInScreen(r)
                    val cls = sendBtn.className?.toString()?.substringAfterLast(".") ?: "?"
                    log("Send by structure: [$cls] @(${r.left},${r.top})-(${r.right},${r.bottom})")
                    return sendBtn
                }
            }
        }
        return null
    }

    /** Collects clickable leaf nodes (or nodes with small child count) from a subtree */
    private fun collectClickableLeaves(node: AccessibilityNodeInfo, result: MutableList<AccessibilityNodeInfo>) {
        if (node.isClickable) {
            result.add(node)
            return // Don't descend into clickable nodes — they are the targets
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectClickableLeaves(child, result)
        }
    }

    /** Finds the send button in the toolbar BELOW the input field.
     *  TikTok places a toolbar with [emoji bar][image][emoji][@]...[SEND] below the input.
     *  The emoji bar adds ~60-80px gap, so the send button can be up to 200px below input.
     *  IMPORTANT: Skip keyboard (IME) windows to avoid the keyboard's "+" button. */
    private fun findSendBelowInput(input: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val inputRect = Rect()
        input.getBoundsInScreen(inputRect)
        val dm = resources.displayMetrics
        val screenCenterX = dm.widthPixels / 2f
        try {
            for (window in windows) {
                if (window.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) continue
                val wRoot = window.root ?: continue
                val candidates = collectNodes(wRoot) { node ->
                    if (!node.isClickable) return@collectNodes false
                    val cls = node.className?.toString() ?: ""
                    if (cls.contains("EditText")) return@collectNodes false
                    val rect = Rect()
                    node.getBoundsInScreen(rect)
                    val centerX = rect.centerX().toFloat()
                    // Must be BELOW the input, CENTER on the right half, and reasonably sized
                    rect.top >= inputRect.bottom - 15 &&
                    rect.top <= inputRect.bottom + 200 &&
                    centerX > screenCenterX &&
                    rect.width() in 20..250 && rect.height() in 20..250
                }
                if (candidates.isNotEmpty()) {
                    return candidates.maxByOrNull { val r = Rect(); it.getBoundsInScreen(r); r.left }
                }
            }
        } catch (_: Exception) { }
        return null
    }

    // ── Comments-off detection ──────────────────────────────────────────

    private fun hasCommentsDisabled(root: AccessibilityNodeInfo): Boolean {
        val phrases = listOf(
            "Comments are turned off", "comments turned off", "Comments off",
            "commenting is turned off", "Comments are disabled", "disabled comment",
            "turned off comment", "comments have been disabled", "restricted comment"
        )
        for (phrase in phrases) {
            if (findByText(root, phrase) != null) return true
        }
        return traverse(root) { node ->
            val cd = node.contentDescription?.toString() ?: ""
            val txt = node.text?.toString() ?: ""
            phrases.any { p -> cd.contains(p, ignoreCase = true) || txt.contains(p, ignoreCase = true) }
        } != null
    }

    // ── Foreground detection helper ────────────────────────────────────

    /** Returns true only for packages that are clearly user-facing apps (launchers, browsers, etc.)
     *  This prevents system overlays (keyboards, toasts, etc.) from falsely marking TikTok as gone. */
    private fun isLauncherOrUserApp(pkg: String): Boolean {
        // Common launchers and home screens
        if (pkg.contains("launcher", ignoreCase = true)) return true
        if (pkg.contains("home", ignoreCase = true)) return true
        // Common app packages that would mean user switched away
        val knownApps = listOf(
            "com.android.settings", "com.google.android.apps",
            "com.instagram", "com.facebook", "com.whatsapp",
            "com.twitter", "com.snapchat", "com.spotify",
            "com.google.android.youtube", "com.android.chrome",
            "com.google.android.gm", "com.android.vending",
            "com.samsung.android.app"
        )
        return knownApps.any { pkg.startsWith(it, ignoreCase = true) }
    }

    // ── Retry ───────────────────────────────────────────────────────────

    private fun retry(state: State, reason: String) {
        retryCount++
        if (retryCount <= MAX_RETRIES) {
            log("$reason — retry $retryCount/$MAX_RETRIES")
            transition(state, randomDelay(300, 600))
        } else {
            log("$reason — skip")
            retryCount = 0
            val fallback = when (state) {
                State.FIND_COMMENTS_BUTTON -> State.SWIPE_NEXT_VIDEO
                State.WAIT_COMMENTS_PANEL  -> State.CLOSE_COMMENTS
                State.FIND_REPLY_BUTTON    -> State.CLOSE_COMMENTS
                State.TAP_REPLY            -> State.FIND_REPLY_BUTTON
                State.WAIT_INPUT_FIELD     -> State.FIND_REPLY_BUTTON
                State.ENTER_TEXT           -> State.FIND_REPLY_BUTTON
                State.VERIFY_TEXT          -> State.FIND_REPLY_BUTTON
                State.TAP_SEND             -> State.FIND_REPLY_BUTTON
                else                       -> State.FIND_COMMENTS_BUTTON
            }
            transition(fallback, 200)
        }
    }

    // ── Node helpers ────────────────────────────────────────────────────

    private fun findByText(root: AccessibilityNodeInfo, text: String): AccessibilityNodeInfo? {
        val nodes = root.findAccessibilityNodeInfosByText(text) ?: return null
        return nodes.firstOrNull { it.text?.toString().equals(text, ignoreCase = true) }
    }

    private fun findByContentDesc(
        root: AccessibilityNodeInfo, desc: String, partial: Boolean
    ): AccessibilityNodeInfo? {
        return traverse(root) { node ->
            val cd = node.contentDescription?.toString() ?: return@traverse false
            if (partial) cd.contains(desc, ignoreCase = true)
            else cd.equals(desc, ignoreCase = true)
        }
    }

    /** Finds a node whose viewIdResourceName contains the given keyword */
    private fun findNodeByViewId(root: AccessibilityNodeInfo, keyword: String): AccessibilityNodeInfo? {
        return traverse(root) { node ->
            val vid = node.viewIdResourceName ?: return@traverse false
            vid.contains(keyword, ignoreCase = true) && node.isClickable
        }
    }

    private fun findScrollable(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        return traverse(root) { it.isScrollable }
    }

    private fun traverse(
        node: AccessibilityNodeInfo, predicate: (AccessibilityNodeInfo) -> Boolean
    ): AccessibilityNodeInfo? {
        if (predicate(node)) return node
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            val match = traverse(child, predicate)
            if (match != null) return match
        }
        return null
    }

    /** Gesture tap a node at its screen center — much more reliable than ACTION_CLICK on TikTok */
    private fun gestureTapNode(node: AccessibilityNodeInfo) {
        val rect = Rect()
        node.getBoundsInScreen(rect)
        if (rect.width() > 0 && rect.height() > 0) {
            performTapGesture(rect.centerX().toFloat(), rect.centerY().toFloat())
        } else {
            node.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        }
    }

    // ── Keyword helpers ──────────────────────────────────────────────────

    /** Walks up the accessibility tree from a node to find surrounding comment text */
    private fun getCommentTextNearNode(node: AccessibilityNodeInfo): String {
        var container: AccessibilityNodeInfo = node
        repeat(3) { container = container.parent ?: return@repeat }
        val texts = mutableListOf<String>()
        collectTextFromTree(container, texts)
        return texts.joinToString(" ")
    }

    private fun collectTextFromTree(node: AccessibilityNodeInfo, result: MutableList<String>) {
        val text = node.text?.toString()
        if (!text.isNullOrBlank() && text.length > 5) {
            result.add(text)
        }
        for (i in 0 until node.childCount) {
            val child = node.getChild(i) ?: continue
            collectTextFromTree(child, result)
        }
    }

    // ── Clipboard ───────────────────────────────────────────────────────

    private fun setClipboard(text: String) {
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("r", text))
    }

    // ── Gestures ────────────────────────────────────────────────────────

    private fun performSwipeUp() {
        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val path = Path().apply {
            moveTo(cx, dm.heightPixels * 0.78f)
            lineTo(cx, dm.heightPixels * 0.22f)
        }
        dispatch(path, 280)
    }

    /** Small scroll (~15% of screen) to move past one comment */
    private fun performSmallCommentScroll() {
        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val path = Path().apply {
            moveTo(cx, dm.heightPixels * 0.55f)
            lineTo(cx, dm.heightPixels * 0.40f)
        }
        dispatch(path, 200)
    }

    private fun performCommentScrollGesture() {
        val dm = resources.displayMetrics
        val cx = dm.widthPixels / 2f
        val path = Path().apply {
            moveTo(cx, dm.heightPixels * 0.68f)
            lineTo(cx, dm.heightPixels * 0.38f)
        }
        dispatch(path, 220)
    }

    private fun performTapGesture(x: Float, y: Float) {
        val path = Path().apply { moveTo(x, y); lineTo(x + 0.5f, y + 0.5f) }
        dispatch(path, 60)
    }

    private fun tapInputArea() {
        val dm = resources.displayMetrics
        performTapGesture(dm.widthPixels / 2f, dm.heightPixels * 0.93f)
    }

    private fun dispatch(path: Path, durationMs: Long) {
        val gesture = GestureDescription.Builder()
            .addStroke(GestureDescription.StrokeDescription(path, 0, durationMs))
            .build()
        dispatchGesture(gesture, null, null)
    }

    // ── Utilities ───────────────────────────────────────────────────────

    private fun randomDelay(minMs: Int, maxMs: Int): Long =
        Random.nextLong(minMs.toLong(), maxMs.toLong() + 1)

    private fun log(msg: String) {
        val entry = "[${timeFormat.format(Date())}] $msg"
        handler.post {
            logs.add(0, entry)
            if (logs.size > MAX_LOG_LINES) logs.removeAt(logs.lastIndex)
        }
    }
}
