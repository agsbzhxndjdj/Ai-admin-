package com.example.refine_loop

import android.accessibilityservice.AccessibilityService
import android.accessibilityservice.GestureDescription
import android.content.Intent
import android.graphics.Path
import android.graphics.Rect
import android.os.Bundle
import android.os.Handler
import android.os.HandlerThread
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo
import android.view.accessibility.AccessibilityWindowInfo
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

class RefineAccessibilityService : AccessibilityService() {

    companion object {
        var instance: RefineAccessibilityService? = null
            private set
        val isRunning: Boolean get() = instance != null

        const val RESPONSE_STABLE_MS = 6000L
        const val POLL_INTERVAL_MS = 1500L
        const val MAX_WAIT_MS = 180_000L
        const val MAX_SAFETY_ROUNDS = 25

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

        status = "إرسال المهمة للمنفّذ..."
        if (!openApp(executorPkg)) return abort("التطبيق غير موجود: $executorPkg")
        sleep(3000)
        if (!typeAndSend(task)) return abort("تعذّرت الكتابة أو الإرسال في المنفّذ")
        current = waitForResponse() ?: return abort("لا يوجد رد من المنفّذ")

        while (running && round < cap) {
            round++

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

    // ─────────── كتابة + إرسال باكتشاف ذكي لزر الإرسال ───────────

    private fun typeAndSend(text: String): Boolean {
        return try {
            var root = rootInActiveWindow ?: return false
            val input = findInputField(root) ?: return false
            val inputBounds = boundsOf(input)

            // لقطة للأزرار قبل الكتابة (لكشف الزر الذي يظهر بعدها)
            val before = clickableSnapshot(root)

            input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
                )
            }
            if (!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
            sleep(1500)

            root = rootInActiveWindow ?: return false
            val candidates = buildSendCandidates(root, inputBounds, before)

            // جرّب المرشحين بالترتيب حتى يفرغ حقل الإدخال (= نجح الإرسال)
            for (candidate in candidates) {
                val clicked = candidate.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                        tapNode(candidate)
                if (!clicked) continue
                sleep(1200)
                val r = rootInActiveWindow ?: return true
                val inp = findInputField(r) ?: return true
                if (inp.text?.toString().isNullOrBlank()) return true
            }
            false
        } catch (e: Exception) {
            status = "خطأ في الكتابة/الإرسال: ${e.message}"
            false
        }
    }

    private fun buildSendCandidates(
        root: AccessibilityNodeInfo,
        inputBounds: Rect,
        before: List<Pair<Rect, Boolean>>,
    ): List<AccessibilityNodeInfo> {
        val clickables = mutableListOf<AccessibilityNodeInfo>()
        collectClickables(root, clickables)
        val usable = clickables.filter { !isInput(it) && isSmall(it) }

        // 1) كلمة مفتاحية في النص أو الوصف أو اسم المورد
        val byKeyword = usable.firstOrNull { node ->
            val hay =
                "${node.text} ${node.contentDescription} ${node.viewIdResourceName}".lowercase()
            SEND_KEYWORDS.any { hay.contains(it.lowercase()) }
        }

        // 2) زر جديد ظهر بعد الكتابة، أو كان معطّلاً وتفعّل
        val appeared = usable
            .filter { node ->
                val b = boundsOf(node)
                val prev = before.firstOrNull { it.first == b }
                prev == null || (!prev.second && node.isEnabled)
            }
            .sortedBy { distanceToInput(it, inputBounds) }

        // 3) أي زر صغير ملاصق لحقل الإدخال
        val nearby = usable
            .filter { isNearInput(it, inputBounds) }
            .sortedBy { distanceToInput(it, inputBounds) }

        return (listOfNotNull(byKeyword) + appeared + nearby).distinct()
    }

    // ─────────── أدوات مساعدة ───────────

    private fun boundsOf(node: AccessibilityNodeInfo): Rect =
        Rect().also { node.getBoundsInScreen(it) }

    private fun isInput(node: AccessibilityNodeInfo): Boolean =
        node.className?.toString()?.contains("EditText") == true

    private fun isSmall(node: AccessibilityNodeInfo): Boolean {
        val b = boundsOf(node)
        return b.width() in 1..450 && b.height() in 1..450
    }

    private fun isNearInput(node: AccessibilityNodeInfo, inputBounds: Rect): Boolean {
        val b = boundsOf(node)
        return b.top < inputBounds.bottom + 250 && b.bottom > inputBounds.top - 250
    }

    private fun distanceToInput(node: AccessibilityNodeInfo, inputBounds: Rect): Int {
        val b = boundsOf(node)
        val dx = maxOf(inputBounds.left - b.right, b.left - inputBounds.right, 0)
        val dy = maxOf(inputBounds.top - b.bottom, b.top - inputBounds.bottom, 0)
        return dx * dx + dy * dy
    }

    private fun clickableSnapshot(root: AccessibilityNodeInfo): List<Pair<Rect, Boolean>> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        collectClickables(root, out)
        return out.map { boundsOf(it) to it.isEnabled }
    }

    private fun collectClickables(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        // تجاهل نافذة لوحة المفاتيح بالكامل
        if (node.window?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return
        if (node.isClickable) out.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectClickables(it, out) }
        }
    }

    // لمسة حقيقية على الشاشة إذا فشل الضغط العادي
    private fun tapNode(node: AccessibilityNodeInfo): Boolean {
        return try {
            val b = boundsOf(node)
            val path = Path().apply { moveTo(b.exactCenterX(), b.exactCenterY()) }
            val gesture = GestureDescription.Builder()
                .addStroke(GestureDescription.StrokeDescription(path, 0, 100))
                .build()
            val latch = CountDownLatch(1)
            var ok = false
            dispatchGesture(gesture, object : GestureResultCallback() {
                override fun onCompleted(g: GestureDescription?) { ok = true; latch.countDown() }
                override fun onCancelled(g: GestureDescription?) { latch.countDown() }
            }, null)
            latch.await(2, TimeUnit.SECONDS)
            ok
        } catch (_: Exception) { false }
    }

    private fun findInputField(root: AccessibilityNodeInfo): AccessibilityNodeInfo? {
        val queue = ArrayDeque<AccessibilityNodeInfo>()
        queue.add(root)
        while (queue.isNotEmpty()) {
            val node = queue.removeFirst()
            if (isInput(node)) return node
            for (i in 0 until node.childCount) {
                node.getChild(i)?.let { queue.add(it) }
            }
        }
        return null
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
