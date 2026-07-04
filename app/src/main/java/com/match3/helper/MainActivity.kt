package com.match3.helper

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.media.projection.MediaProjectionManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import android.util.Log
import android.view.*
import android.widget.Button
import android.widget.TextView
import android.widget.Toast

/**
 * 主界面：权限引导、棋盘校准、辅助开关
 */
class MainActivity : Activity() {

    companion object {
        private const val TAG = "MainActivity"
        private const val REQUEST_OVERLAY = 100
        private const val REQUEST_PROJECTION = 101
        private const val REQUEST_ACCESSIBILITY = 102
    }

    private lateinit var tvStatus: TextView
    private lateinit var btnAccessibility: Button
    private lateinit var btnCalibrate: Button
    private lateinit var btnStart: Button
    private lateinit var btnStop: Button
    private lateinit var tvHint: TextView

    private var isCalibrating = false
    private var calibrationView: CalibrationOverlay? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        initViews()
        updateUIState()
    }

    override fun onResume() {
        super.onResume()
        updateUIState()
    }

    private fun initViews() {
        tvStatus = findViewById(R.id.tv_status)
        btnAccessibility = findViewById(R.id.btn_enable_accessibility)
        btnCalibrate = findViewById(R.id.btn_calibrate)
        btnStart = findViewById(R.id.btn_start)
        btnStop = findViewById(R.id.btn_stop)
        tvHint = findViewById(R.id.tv_hint)

        btnAccessibility.setOnClickListener { requestAccessibility() }
        btnCalibrate.setOnClickListener { startCalibration() }
        btnStart.setOnClickListener { startAssist() }
        btnStop.setOnClickListener { stopAssist() }
    }

    private fun updateUIState() {
        val hasAccessibility = isAccessibilityServiceEnabled()
        val hasOverlay = Settings.canDrawOverlays(this)
        val service = GameAssistService.instance
        val isCalibrated = service?.isCalibrated() ?: false
        val isRunning = service != null && service.javaClass.name.contains("GameAssist")

        tvStatus.text = buildString {
            appendLine("无障碍服务: ${if (hasAccessibility) "✅" else "❌"}")
            appendLine("悬浮窗权限: ${if (hasOverlay) "✅" else "❌"}")
            appendLine("棋盘校准: ${if (isCalibrated) "✅" else "❌"}")
        }

        btnAccessibility.isEnabled = !hasAccessibility
        btnCalibrate.isEnabled = hasAccessibility && !isCalibrating
        btnStart.isEnabled = hasAccessibility && isCalibrated && !isRunning
        btnStop.isEnabled = isRunning
    }

    // ==================== 权限请求 ====================

    private fun requestAccessibility() {
        val intent = Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)
        startActivityForResult(intent, REQUEST_ACCESSIBILITY)
        Toast.makeText(this, "请找到「消消乐助手」并开启", Toast.LENGTH_LONG).show()
    }

    private fun requestOverlayPermission() {
        if (!Settings.canDrawOverlays(this)) {
            val intent = Intent(
                Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                Uri.parse("package:$packageName")
            )
            startActivityForResult(intent, REQUEST_OVERLAY)
        }
    }

    private fun requestScreenCapture() {
        val projectionManager = getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        startActivityForResult(projectionManager.createScreenCaptureIntent(), REQUEST_PROJECTION)
    }

    // ==================== 校准流程 ====================

    private fun startCalibration() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }

        isCalibrating = true
        Toast.makeText(this, "请在屏幕上框选棋盘区域（左上角按住拖动到右下角）", Toast.LENGTH_LONG).show()

        val overlay = CalibrationOverlay(this) { left, top, right, bottom ->
            finishCalibration(left, top, right, bottom)
        }
        calibrationView = overlay

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        windowManager.addView(overlay, params)
    }

    private fun finishCalibration(left: Int, top: Int, right: Int, bottom: Int) {
        // 移除校准覆盖层
        calibrationView?.let {
            val windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {}
            calibrationView = null
        }

        isCalibrating = false

        // 保存校准结果到服务
        GameAssistService.instance?.calibrateBoard(left, top, right, bottom)
            ?: run {
                // 服务可能还没启动，保存到静态变量
                CalibrationData.left = left
                CalibrationData.top = top
                CalibrationData.right = right
                CalibrationData.bottom = bottom
            }

        tvHint.text = "棋盘已校准: (${left},${top}) - (${right},${bottom})"
        Toast.makeText(this, "校准完成！", Toast.LENGTH_SHORT).show()
        updateUIState()
    }

    object CalibrationData {
        var left = 0
        var top = 0
        var right = 0
        var bottom = 0
    }

    // ==================== 辅助开关 ====================

    private fun startAssist() {
        if (!Settings.canDrawOverlays(this)) {
            requestOverlayPermission()
            return
        }

        val service = GameAssistService.instance
        if (service == null) {
            Toast.makeText(this, "请先开启无障碍服务", Toast.LENGTH_SHORT).show()
            return
        }

        // 如果有待处理的校准数据，先应用
        if (!service.isCalibrated() && CalibrationData.right > 0) {
            service.calibrateBoard(
                CalibrationData.left, CalibrationData.top,
                CalibrationData.right, CalibrationData.bottom
            )
        }

        if (!service.isCalibrated()) {
            Toast.makeText(this, "请先校准棋盘", Toast.LENGTH_SHORT).show()
            return
        }

        // 请求截屏权限
        requestScreenCapture()
    }

    private fun stopAssist() {
        GameAssistService.instance?.stopAssist()
        tvHint.text = "辅助已停止"
        updateUIState()
    }

    // ==================== Activity结果处理 ====================

    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)

        when (requestCode) {
            REQUEST_ACCESSIBILITY -> {
                updateUIState()
            }
            REQUEST_OVERLAY -> {
                updateUIState()
            }
            REQUEST_PROJECTION -> {
                if (resultCode == RESULT_OK && data != null) {
                    // 保存MediaProjection数据
                    GameAssistService.pendingProjectionResultCode = resultCode
                    GameAssistService.pendingProjectionIntent = data

                    // 启动辅助
                    GameAssistService.instance?.startAssist(this)
                    tvHint.text = "辅助运行中..."
                    updateUIState()
                } else {
                    Toast.makeText(this, "需要截屏权限才能运行", Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    private fun isAccessibilityServiceEnabled(): Boolean {
        val serviceName = "$packageName/${GameAssistService::class.java.canonicalName}"
        val enabledServices = Settings.Secure.getString(contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES)
            ?: return false
        return enabledServices.contains(serviceName)
    }
}
