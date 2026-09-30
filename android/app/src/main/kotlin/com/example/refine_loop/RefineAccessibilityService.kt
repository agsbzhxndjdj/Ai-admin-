package com.example.refine_loop

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import kotlinx.coroutines.*

class RefineAccessibilityService : AccessibilityService() {

    companion object {
        var instance: RefineAccessibilityService? = null
        val isRunning: Boolean get() = instance != null

        // ══════════════════════════════════════════════════════
        //  ⚠️ هذه القيم يجب تعبئتها لكل تطبيق تستهدفه.
        //  اكتشفها عبر: أدوات المطور → Layout Inspector
        //  أو تطبيق "Accessibility Inspector" من المتجر.
        // ══════════════════════════════════════════════════════
        const val EXECUTOR_PACKAGE = "com.qwen.app"          // TODO: حزمة تطبيق Qwen
        const val REVIEWER_PACKAGE = "com.google.android.apps.bard" // TODO: حزمة Gemini

        const val INPUT_FIELD_ID   = "TODO:input_field_id"   // TODO: معرّف حقل الإدخال
        const val SEND_BUTTON_ID   = "TODO:send_button_id"   // TODO: معرّف زر الإرسال
        // ══════════════════════════════════════════════════════

        const val RESPONSE_STABLE_MS = 6000L
        const val POLL_INTERVAL_MS   = 1500L
        const val MAX_WAIT_MS        = 180_000L
        const val MAX_SAFETY_ROUNDS  = 25
    }

    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    @Volatile private var running = false
    private var status = "الخدمة متصلة وجاهزة"

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        status = "الخدمة متصلة"
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {}
    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        scope.cancel()
        super.onDestroy()
    }

    fun getStatus() = status

    fun startLoop(task: String, reviewPrompt: String, rounds: Int) {
        if (running) return
        running = true
        scope.launch { runLoop(task, reviewPrompt, rounds) }
    }

    fun stopLoop() {
        running = false
        status = "تم الإيقاف يدوياً"
    }

    private suspend fun runLoop(task: String, reviewPrompt: String, rounds: Int) {
        val unlimited = rounds <= 0
        val cap = if (unlimited) MAX_SAFETY_ROUNDS else rounds
        var current = task
        var round = 0

        // ① أرسل المهمة للمنفّذ
        status = "إرسال المهمة للمنفّذ..."
        openApp(EXECUTOR_PACKAGE); delay(2500)
        typeText(current); pressSend()
        current = waitForResponse() ?: return abort("لا يوجد رد من المنفّذ")

        // ② حلقة المراجعة ↔ التحسين
        while (running && round < cap) {
            round++

            status = "الدورة $round: إرسال للمراجع..."
            openApp(REVIEWER_PACKAGE); delay(2500)
            typeText("$reviewPrompt\n\n$current"); pressSend()
            val feedback = waitForResponse() ?: return abort("لا يوجد رد من المراجع")

            if (feedback.take(80).contains("معتمد")) {
                status = "✅ المراجع اعتمد العمل — اكتملت الحلقة"
                running = false; return
            }

            status = "الدورة $round: التحسين بناءً على الملاحظات..."
            openApp(EXECUTOR_PACKAGE); delay(2500)
            typeText("ملاحظات المراجع:\n$feedback\n\nحسّن هذا العمل:\n$current")
            pressSend()
            current = waitForResponse() ?: return abort("لا يوجد رد تحسين")
        }

        status = if (round >= cap && unlimited)
            "⛔ وصلت حد الأمان للدورات غير المحدودة"
        else
            "🏁 اكتملت جميع الدورات"
        running = false
    }

    private fun abort(msg: String) {
        status = "⛔ $msg"
        running = false
    }

    // ─────────────── أدوات التحكم ───────────────

    private fun openApp(pkg: String) {
        val intent = packageManager.getLaunchIntentForPackage(pkg)
        if (intent != null) {
            intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            startActivity(intent)
        } else {
            status = "التطبيق غير موجود: $pkg"
        }
    }

    private fun typeText(text: String) {
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
    }

    private fun pressSend() {
        val root = rootInActiveWindow ?: return
        root.findAccessibilityNodeInfosByViewId(SEND_BUTTON_ID)
            .firstOrNull()
            ?.performAction(AccessibilityNodeInfo.ACTION_CLICK)
    }

    private suspend fun waitForResponse(): String? {
        var last = ""
        var stable = 0L
        var total = 0L
        while (running && total < MAX_WAIT_MS) {
            delay(POLL_INTERVAL_MS); total += POLL_INTERVAL_MS
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
        val root = rootInActiveWindow ?: return null
        val texts = mutableListOf<String>()
        collectTexts(root, texts)
        return texts.lastOrNull()
    }

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String>) {
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectTexts(it, out) }
        }
    }
}
