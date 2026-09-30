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
import android.widget.Toast
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit

private data class Snap(val bounds: Rect, val enabled: Boolean, val desc: String)
private data class Cand(val node: AccessibilityNodeInfo, val bounds: Rect, val desc: String)
private data class Calibration(val x: Int, val y: Int, val viewId: String?, val desc: String?)

class RefineAccessibilityService : AccessibilityService() {

    companion object {
        var instance: RefineAccessibilityService? = null
            private set
        val isRunning: Boolean get() = instance != null

        const val RESPONSE_STABLE_MS = 8000L
        const val POLL_INTERVAL_MS = 1500L
        const val MAX_WAIT_MS = 900_000L
        const val MAX_SAFETY_ROUNDS = 25

        private val SEND_KEYWORDS = listOf(
            "send", "إرسال", "ارسال", "أرسل", "ارسل", "submit", "发送"
        )
        private val EXCLUDE_KEYWORDS = listOf(
            "mic", "microphone", "voice", "audio", "record", "dictate",
            "camera", "photo", "image", "attach", "file",
            "صوت", "تسجيل", "مايك", "ميكروفون", "إملاء", "املاء", "تحدث",
            "كاميرا", "صورة", "صور", "مرفق", "ملف", "إضافة", "اضافة"
        )
    }

    private val worker = HandlerThread("LoopWorker").apply { start() }
    private val bg = Handler(worker.looper)
    private val prefs by lazy { getSharedPreferences("refine_loop", MODE_PRIVATE) }

    @Volatile private var running = false
    @Volatile private var status = "الخدمة متصلة وجاهزة"
    @Volatile private var calibrationTarget: String? = null

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        status = "الخدمة متصلة"
    }

    // ─────────── التقاط المعايرة ───────────
    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        val target = calibrationTarget ?: return
        if (event == null) return
        if (event.eventType != AccessibilityEvent.TYPE_VIEW_CLICKED &&
            event.eventType != AccessibilityEvent.TYPE_VIEW_LONG_CLICKED) return
        val node = event.source ?: return
        if (node.window?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return
        if (isInput(node)) return
        val b = boundsOf(node)
        if (b.width() <= 0 || b.height() <= 0) return
        saveCalibration(target, b, node.viewIdResourceName,
            "${node.text} ${node.contentDescription}")
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        instance = null
        worker.quitSafely()
        super.onDestroy()
    }

    fun getStatus() = status

    // ─────────── المعايرة ───────────

    fun isCalibrated(pkg: String) = prefs.contains("cal_$pkg")

    fun startCalibration(pkg: String) {
        calibrationTarget = pkg
        openApp(pkg)
        Toast.makeText(this,
            "اكتب أي نص ثم اضغط زر الإرسال لحفظ موضعه", Toast.LENGTH_LONG).show()
    }

    private fun saveCalibration(pkg: String, b: Rect, viewId: String?, desc: String?) {
        val cx = b.exactCenterX().toInt()
        val cy = b.exactCenterY().toInt()
        prefs.edit().putString("cal_$pkg", "$cx|$cy|${viewId ?: ""}|${desc ?: ""}").apply()
        calibrationTarget = null
        Toast.makeText(this, "✅ تم حفظ معايرة الزر لهذا التطبيق", Toast.LENGTH_LONG).show()
    }

    private fun getCalibration(pkg: String): Calibration? {
        val s = prefs.getString("cal_$pkg", null) ?: return null
        val p = s.split("|", 4)
        return try {
            Calibration(p[0].toInt(), p[1].toInt(),
                p.getOrNull(2)?.takeIf { it.isNotBlank() }, p.getOrNull(3))
        } catch (_: Exception) { null }
    }

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
        if (!typeAndSend(task, executorPkg)) return abort("تعذّرت الكتابة أو الإرسال في المنفّذ")
        status = "بانتظار رد المنفّذ (قد يطول بسبب التفكير)..."
        current = waitForResponse(task) ?: return abort("انتهت مهلة انتظار رد المنفّذ")

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
            if (!typeAndSend(reviewMessage, reviewerPkg))
                return abort("تعذّرت الكتابة أو الإرسال في المراجع")
            status = "الدورة $round: بانتظار رد المراجع..."
            val feedback = waitForResponse(reviewMessage)
                ?: return abort("انتهت مهلة انتظار رد المراجع")

            if (feedback.take(80).contains("معتمد")) {
                status = "✅ اعتمد المراجع العمل — اكتملت الحلقة"
                running = false
                return
            }

            status = "الدورة $round: التحسين بناءً على الملاحظات..."
            if (!openApp(executorPkg)) return abort("التطبيق غير موجود: $executorPkg")
            sleep(3000)
            val improveMessage = "ملاحظات المراجع:\n$feedback\n\nحسّن هذا العمل:\n$current"
            if (!typeAndSend(improveMessage, executorPkg))
                return abort("تعذّرت الكتابة أو الإرسال في المنفّذ")
            status = "الدورة $round: بانتظار الرد المحسّن..."
            current = waitForResponse(improveMessage)
                ?: return abort("انتهت مهلة انتظار الرد المحسّن")
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

    // ─────────── كتابة + إرسال (معايرة أولاً ثم تلقائي) ───────────

    private fun typeAndSend(text: String, pkg: String): Boolean {
        return try {
            var root = rootInActiveWindow ?: return false
            val input = findInputField(root) ?: return false
            val inputBounds = boundsOf(input)
            val before = snapshot(root)

            input.performAction(AccessibilityNodeInfo.ACTION_FOCUS)
            val args = Bundle().apply {
                putCharSequence(
                    AccessibilityNodeInfo.ACTION_ARGUMENT_SET_TEXT_CHARSEQUENCE, text
                )
            }
            if (!input.performAction(AccessibilityNodeInfo.ACTION_SET_TEXT, args)) return false
            sleep(1500)

            root = rootInActiveWindow ?: return false

            // 0) المعايرة المحفوظة — الأولوية القصوى
            getCalibration(pkg)?.let { cal ->
                if (tryCalibrated(root, cal)) {
                    sleep(1500)
                    val inp = findInputField(rootInActiveWindow ?: return true)
                    if (inp != null && inp.text?.toString().isNullOrBlank()) return true
                    if (inp == null) {
                        performGlobalAction(GLOBAL_ACTION_BACK); sleep(800)
                    }
                }
            }

            // ثم الاستراتيجيات التلقائية
            val candidates = buildSendCandidates(root, inputBounds, before)
            for (c in candidates) {
                val clicked = c.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                        tapAt(boundsOf(c))
                if (!clicked) continue
                sleep(1500)
                val r = rootInActiveWindow
                val inp = r?.let { findInputField(it) }
                if (inp != null && inp.text?.toString().isNullOrBlank()) return true
                if (inp == null) {
                    performGlobalAction(GLOBAL_ACTION_BACK); sleep(800)
                }
            }
            false
        } catch (e: Exception) {
            status = "خطأ في الكتابة/الإرسال: ${e.message}"
            false
        }
    }

    private fun tryCalibrated(root: AccessibilityNodeInfo, cal: Calibration): Boolean {
        cal.viewId?.let { id ->
            val node = try {
                root.findAccessibilityNodeInfosByViewId(id).firstOrNull()
            } catch (_: Exception) { null }
            if (node != null) {
                return node.performAction(AccessibilityNodeInfo.ACTION_CLICK) ||
                        tapAt(boundsOf(node))
            }
        }
        return tapAt(Rect(cal.x - 20, cal.y - 20, cal.x + 20, cal.y + 20))
    }

    private fun buildSendCandidates(
        root: AccessibilityNodeInfo,
        inputBounds: Rect,
        before: List<Snap>,
    ): List<AccessibilityNodeInfo> {
        val clickables = mutableListOf<AccessibilityNodeInfo>()
        collectClickables(root, clickables)

        val now = clickables
            .map { n -> Cand(n, boundsOf(n), "${n.text} ${n.contentDescription}") }
            .filter { !isInput(it.node) && isSmall(it.bounds) && !isExcluded(it.desc) }

        val c1 = now.firstOrNull { c ->
            SEND_KEYWORDS.any { c.desc.lowercase().contains(it.lowercase()) }
        }?.node

        val c2 = now
            .filter { c ->
                val prev = before.firstOrNull { it.bounds == c.bounds }
                prev == null || (!prev.enabled && c.node.isEnabled) || prev.desc != c.desc
            }
            .sortedBy { distance(it.bounds, inputBounds) }
            .map { it.node }

        val c3 = now
            .filter { c -> before.any { it.bounds == c.bounds } }
            .sortedBy { distance(it.bounds, inputBounds) }
            .map { it.node }

        val c4 = now
            .filter { isNearInput(it.bounds, inputBounds) }
            .sortedBy { distance(it.bounds, inputBounds) }
            .map { it.node }

        return (listOfNotNull(c1) + c2 + c3 + c4).distinct()
    }

    // ─────────── انتظار الرد ───────────

    private fun waitForResponse(sentText: String): String? {
        val sentNorm = norm(sentText)
        var last = ""
        var stable = 0L
        var total = 0L
        while (running && total < MAX_WAIT_MS) {
            sleep(POLL_INTERVAL_MS); total += POLL_INTERVAL_MS
            val cur = extractLastMessage(sentNorm) ?: continue
            if (cur == last) {
                stable += POLL_INTERVAL_MS
                if (stable >= RESPONSE_STABLE_MS) return cur
            } else {
                stable = 0; last = cur
            }
        }
        return null
    }

    private fun extractLastMessage(sentNorm: String): String? {
        return try {
            val root = rootInActiveWindow ?: return null
            val texts = mutableListOf<String>()
            collectTexts(root, texts)
            val ai = texts.filter { t ->
                val n = norm(t)
                n != sentNorm && !sentNorm.contains(n)
            }
            ai.lastOrNull { it.length > 40 } ?: ai.lastOrNull()
        } catch (_: Exception) { null }
    }

    private fun norm(s: String) = s.replace(Regex("\\s"), "")

    // ─────────── أدوات مساعدة ───────────

    private fun boundsOf(node: AccessibilityNodeInfo): Rect =
        Rect().also { node.getBoundsInScreen(it) }

    private fun isInput(node: AccessibilityNodeInfo): Boolean =
        node.className?.toString()?.contains("EditText") == true

    private fun isSmall(b: Rect): Boolean =
        b.width() in 1..450 && b.height() in 1..450

    private fun isNearInput(b: Rect, inputBounds: Rect): Boolean =
        b.top < inputBounds.bottom + 250 && b.bottom > inputBounds.top - 250

    private fun distance(b: Rect, inputBounds: Rect): Int {
        val dx = maxOf(inputBounds.left - b.right, b.left - inputBounds.right, 0)
        val dy = maxOf(inputBounds.top - b.bottom, b.top - inputBounds.bottom, 0)
        return dx * dx + dy * dy
    }

    private fun isExcluded(desc: String): Boolean {
        val d = desc.lowercase()
        return EXCLUDE_KEYWORDS.any { d.contains(it.lowercase()) }
    }

    private fun snapshot(root: AccessibilityNodeInfo): List<Snap> {
        val out = mutableListOf<AccessibilityNodeInfo>()
        collectClickables(root, out)
        return out.map { n ->
            Snap(boundsOf(n), n.isEnabled, "${n.text} ${n.contentDescription}")
        }
    }

    private fun collectClickables(node: AccessibilityNodeInfo, out: MutableList<AccessibilityNodeInfo>) {
        if (node.window?.type == AccessibilityWindowInfo.TYPE_INPUT_METHOD) return
        if (node.isClickable) out.add(node)
        for (i in 0 until node.childCount) {
            node.getChild(i)?.let { collectClickables(it, out) }
        }
    }

    private fun tapAt(b: Rect): Boolean {
        return try {
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

    private fun collectTexts(node: AccessibilityNodeInfo, out: MutableList<String>) {
        node.text?.toString()?.takeIf { it.isNotBlank() }?.let { out.add(it) }
        for (i in 0 until node.childCount) {
            val child = try { node.getChild(i) } catch (_: Exception) { null }
            if (child != null) collectTexts(child, out)
        }
    }
}
