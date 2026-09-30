package com.example.refine_loop

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.os.Looper
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class RefineAccessibilityService : AccessibilityService() {

    companion object {
        var instance: RefineAccessibilityService? = null
            private set
        val isRunning: Boolean get() = instance != null

        // ════════════ TODO: عبّئ هذه لكل تطبيق تستهدفه ════════════
        const val EXECUTOR_PACKAGE = "TODO_PACKAGE_QWEN"
        const val REVIEWER_PACKAGE = "TODO_PACKAGE_GEMINI"
        const val INPUT_FIELD_ID   = "TODO_INPUT_FIELD_ID"
        const val SEND_BUTTON_ID   = "TODO_SEND_BUTTON_ID"
        // ═══════════════════════════════════════════════════════════

        const val RESPONSE_STABLE_MS = 6000L
        const val POLL_INTERVAL_MS   = 1500L
        const val MAX_WAIT_MS        = 180_000L
        const val MAX_SAFETY_ROUNDS  = 25
    }

    private val worker = HandlerThread("LoopWorker").apply { start() }
    private val bg = Handler(worker.looper)

    @Volatile private var running = false
    @Volatile private var status = "الخدمة متصلة وجاهزة"

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        status = "الخدمة متصلة"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        worker.quitSafely()
        super.onDestroy()
    }

    fun getStatus() = status

    fun startLoop(task: String, reviewPrompt: String, rounds: Int) {
        if (running) return
        running = true
        bg.post { runLoop(task, reviewPrompt, rounds) }
    }

    fun stopLoop() {
        running = false
        status = "تم الإيقاف يدوياً"
    }

    private fun runLoop(task: String, reviewPrompt: String, rounds: Int) {
        val cap = if (rounds <= 0) MAX_SAFETY_ROUNDS else rounds
        var current = task
        var round = 0

        status = "إرسال المهمة للمنفّذ..."
        openApp(EXECUTOR_PACKAGE); sleep(2500)
        typeText(current); pressSend()
        current = waitForResponse() ?: return abort("لا يوجد رد من المنفّذ")

        while (running && round < cap) {
            round++

            status = "الدورة $round: إرسال للمراجع..."
            openApp(REVIEWER_PACKAGE); sleep(2500)
            typeText("$reviewPrompt\n\n$current"); pressSend()
            val feedback = waitForResponse() ?: return abort("لا يوجد رد من المراجع")

            if (feedback.take(80).contains("معتمد")) {
                status = "✅ اعتمد المراجع العمل — اكتملت الحلقة"
                running = false
                return
            }

            status = "الدورة $round: التحسين بناءً على الملاحظات..."
            openApp(EXECUTOR_PACKAGE); sleep(2500)
            typeText("ملاحظات المراجع:\n$feedback\n\nحسّن هذا العمل:\n$current")
            pressSend()
            current = waitForResponse() ?: return abort("لا يوجد رد تحسين")
        }

        status = if (rounds <= 0) "⛔ وصلت حد الأمان للدورات غير المحدودة"
                 else "🏁 اكتملت جميع الدورات"
        running = false
    }

    private fun abort(msg: String) {
        status = "⛔ $msg"
        running = false
    }

    private fun sleep(ms: Long) {
        try { Thread.sleep(ms) } catch (_: InterruptedException) {}
    }

    private fun openApp(pkg: String) {
        try {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
            } else {
                status = "التطبيق غير موجود: $pkg"
            }
        } catch (e: Exception) {
            status = "خطأ في فتح التطبيق: ${e.message}"
        }
    }

    private fun typeText(text: String) {
        try {
            val root = rootInActiveWindow ?: return
            val input = root.findAccessibilityNodeInfosByViewId(INPUT_FIELD_ID)
                .firstOrNull() ?: return
            input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
                )
            }
            input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)
        } catch (e: Exception) {
            status = "خطأ في الكتابة: ${e.message}"
        }
    }

    private fun pressSend() {
        try {
            val root = rootInActiveWindow ?: return
            root.findAccessibilityNodeInfosByViewId(SEND_BUTTON_ID)
                .firstOrNull()
                ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (_: Exception) {}
    }

    private fun waitForResponse(): String? {
        var last = ""
        var stable = 0L
        var total = 0L
        while (running && total < MAX_WAIT_MS) {
            sleep(POLL_INTERVAL_MS); total += POLL_INTERVAL_MS
            val cur = extractLastMessage() ?: continue
            if (cur == last && cur.isNotBlank()) {
                stable += POLL_INTERVAL_MS
                if (stable >= RESPONSE_STABLE_MS) return cur
            } else {
                stable = 0; last = cur
            }
        }
        return null
    }

    private fun extractLastMessage(): String? {
        return try {
            val root = rootInActiveWindow ?: return null
            val texts = mutableListOf<String>()
            collectTexts(root, texts)
            texts.lastOrNull()
        } catch (_: Exception) { null }
    }

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String>) {
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            if (child != null) collectTexts(child, out)
        }
    }
}
