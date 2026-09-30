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

                    "listApps" -> {
                        val pm = packageManager
                        val list = pm.getInstalledApplications(0)
                            .mapNotNull { app ->
                                if (pm.getLaunchIntentForPackage(app.packageName) == null)
                                    null
                                else mapOf(
                                    "package" to app.packageName,
                                    "label" to app.loadLabel(pm).toString()
                                )
                            }
                            .sortedBy { it["label"] }
                        result.success(list)
                    }

                    "startCalibration" -> {
                        val pkg = call.argument<String>("package") ?: ""
                        RefineAccessibilityService.instance?.startCalibration(pkg)
                        result.success(true)
                    }

                    "isCalibrated" -> {
                        val pkg = call.argument<String>("package") ?: ""
                        result.success(
                            RefineAccessibilityService.instance?.isCalibrated(pkg)
                                ?: false
                        )
                    }

                    "startLoop" -> {
                        val task = call.argument<String>("task") ?: ""
                        val prompt = call.argument<String>("reviewPrompt") ?: ""
                        val rounds = call.argument<Int>("rounds") ?: 3
                        val executor = call.argument<String>("executorPackage") ?: ""
                        val reviewer = call.argument<String>("reviewerPackage") ?: ""
                        RefineAccessibilityService.instance
                            ?.startLoop(task, prompt, rounds, executor, reviewer)
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
