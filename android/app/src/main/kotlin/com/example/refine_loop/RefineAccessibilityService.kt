package com.example.refine_loop

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

class RefineAccessibilityService : AccessibilityService() {

    companion object {
        var instance: RefineAccessibilityService? = null
            private set
        val isRunning: Boolean get() = instance != null

        const val RESPONSE_STABLE_MS = 6000L
        const val POLL_INTERVAL_MS   = 1500L
        const val MAX_WAIT_MS        = 180_000L
        const val MAX_SAFETY_ROUNDS  = 25

        // كلمات يستخدمها التطبيق للتعرف على زر الإرسال في أي تطبيق
        private val SEND_KEYWORDS = listOf(
            "send", "إرسال", "ارسال", "أرسل", "ارسل", "submit", "发送"
        )
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

    fun startLoop(
        task: String,
        reviewPrompt: String,
        rounds: Int,
        executorPkg: String,
        reviewerPkg: String,
    ) {
        if (running) return
        running = true
        bg.post { runLoop(task, reviewPrompt, rounds, executorPkg, reviewerPkg) }
    }

    fun stopLoop() {
        running = false
        status = "تم الإيقاف يدوياً"
    }

    private fun runLoop(
        task: String,
        reviewPrompt: String,
        rounds: Int,
        executorPkg: String,
        reviewerPkg: String,
    ) {
        val cap = if (rounds <= 0) MAX_SAFETY_ROUNDS else rounds
        var current = ""
        var round = 0

        // ① المهمة للمنفّذ
        status = "إرسال المهمة للمنفّذ..."
        if (!openApp(executorPkg)) return abort("التطبيق غير موجود: $executorPkg")
        sleep(3000)
        if (!typeAndSend(task)) return abort("تعذّرت الكتابة أو الإرسال في المنفّذ")
        current = waitForResponse() ?: return abort("لا يوجد رد من المنفّذ")

        while (running && round < cap) {
            round++

            // ② المراجعة: أمرك المخصص + الأمر الأساسي + العمل
            status = "الدورة $round: إرسال للمراجع..."
            if (!openApp(reviewerPkg)) return abort("التطبيق غير موجود: $reviewerPkg")
            sleep(3000)
            val reviewMessage = buildString {
                append(reviewPrompt)
                append("\n\nلقد كان الأمر الأساسي: ")
                append(task)
                append("\n\nالعمل الناتج:\n")
                append(current)
                append("\n\nإذا كان العمل مكتملاً وممتازاً فارد بكلمة واحدة فقط: معتمد")
            }
            if (!typeAndSend(reviewMessage))
                return abort("تعذّرت الكتابة أو الإرسال في المراجع")
            val feedback = waitForResponse() ?: return abort("لا يوجد رد من المراجع")

            if (feedback.take(80).contains("معتمد")) {
                status = "✅ اعتمد المراجع العمل — اكتملت الحلقة"
                running = false
                return
            }

            // ③ التحسين
            status = "الدورة $round: التحسين بناءً على الملاحظات..."
            if (!openApp(executorPkg)) return abort("التطبيق غير موجود: $executorPkg")
            sleep(3000)
            if (!typeAndSend("ملاحظات المراجع:\n$feedback\n\nحسّن هذا العمل:\n$current"))
                return abort("تعذّرت الكتابة أو الإرسال في المنفّذ")
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

    private fun openApp(pkg: String): Boolean {
        return try {
            val intent = packageManager.getLaunchIntentForPackage(pkg)
            if (intent != null) {
                intent.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
                startActivity(intent)
                true
            } else false
        } catch (e: Exception) {
            status = "خطأ في فتح التطبيق: ${e.message}"
            false
        }
    }

    // يكتب النص ثم يضغط زر الإرسال — بدون أي معرّفات، اكتشاف تلقائي
    private fun typeAndSend(text: String): Boolean {
        return try {
            val root = rootInActiveWindow ?: return false
            val input = findInputField(root) ?: return false
            input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
                )
            }
            if (!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args))
                return false
            sleep(800) // مهلة ليظهر زر الإرسال

            val root2 = rootInActiveWindow ?: return false
            val send = findSendButton(root2) ?: return false
            send.performAction(AccessibilityNodeInfo.ACTION_CLICK)
        } catch (e: Exception) {
            status = "خطأ في الكتابة/الإرسال: ${e.message}"
            false
        }
    }

    // يبحث عن أي حقل إدخال (EditText) في الشاشة
    private fun findInputField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (node.className?.toString()?.contains("EditText") == true) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
    }

    // يبحث عن زر الإرسال بالكلمات المفتاحية
    private fun findSendButton(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val clickables = mutableListOf<AccessibilityNodeInfo>()
        collectClickables(root, clickables)
        return clickables.firstOrNull { node ->
            val text = "${node.text} ${node.contentDescription}".lowercase()
            SEND_KEYWORDS.any { text.contains(it.lowercase()) }
        }
    }

    private fun collectClickables(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (node.isClickable) out.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectClickables(it, out) }
        }
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
            // نفضّل آخر نص طويل (هو غالباً رد النموذج وليس أزرار الواجهة)
            texts.lastOrNull { it.length > 40 } ?: texts.lastOrNull()
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
