package com.example.refine_loop

import android.content.Intent
import android.provider.Settings
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel

class MainActivity : FlutterActivity() {
    private val CHANNEL = "refine_loop/accessibility"

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)
        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "isServiceEnabled" ->
                        result.success(RefineAccessibilityService.isRunning)

                    "openAccessibilitySettings" -> {
                        startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                        result.success(true)
                    }

                    "startLoop" -> {
                        val task = call.argument<String>("task") ?: ""
                        val prompt = call.argument<String>("reviewPrompt") ?: ""
                        val rounds = call.argument<Int>("rounds") ?: 3
                        RefineAccessibilityService.instance
                            ?.startLoop(task, prompt, rounds)
                        result.success(true)
                    }

                    "stopLoop" -> {
                        RefineAccessibilityService.instance?.stopLoop()
                        result.success(true)
                    }

                    "getStatus" ->
                        result.success(
                            RefineAccessibilityService.instance?.getStatus()
                                ?: "الخدمة غير متصلة"
                        )

                    else -> result.notImplemented()
                }
            }
    }
}
