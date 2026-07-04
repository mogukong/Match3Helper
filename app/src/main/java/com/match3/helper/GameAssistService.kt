package com.match3.helper

import android.accessibilityservice.AccessibilityService
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.PixelFormat
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.Image
import android.media.ImageReader
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.util.DisplayMetrics
import android.util.Log
import android.view.accessibility.AccessibilityEvent
import androidx.core.app.NotificationCompat
import com.match3.helper.ai.GameAI
import com.match3.helper.overlay.HintOverlay
import com.match3.helper.vision.BoardRecognizer

/**
 * 消消乐辅助无障碍服务
 * 核心流程：截屏 → 识别棋盘 → AI计算 → 显示提示
 */
class GameAssistService : AccessibilityService() {

    companion object {
        private const val TAG = "GameAssistService"
        private const val CHANNEL_ID = "match3_helper_channel"
        private const val NOTIFICATION_ID = 1

        // 截屏间隔（毫秒）
        const val CAPTURE_INTERVAL = 2000L

        // MediaProjection 请求码
        const val REQUEST_CODE_PROJECTION = 1001

        // 外部传入的 Projection Intent
        var pendingProjectionIntent: Intent? = null
        var pendingProjectionResultCode: Int = 0

        var instance: GameAssistService? = null
            private set
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var imageReader: ImageReader? = null
    private val handler = Handler(Looper.getMainLooper())
    private val boardRecognizer = BoardRecognizer()
    private val gameAI = GameAI()
    private var hintOverlay: HintOverlay? = null

    // 辅助状态
    @Volatile
    private var isAssisting = false

    // 截屏任务
    private val captureRunnable = object : Runnable {
        override fun run() {
            if (isAssisting) {
                captureScreen()
                handler.postDelayed(this, CAPTURE_INTERVAL)
            }
        }
    }

    override fun onServiceConnected() {
        super.onServiceConnected()
        instance = this
        Log.i(TAG, "无障碍服务已连接")
        createNotificationChannel()
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent?) {
        // 可以在这里监听窗口变化，判断用户是否进入了游戏
    }

    override fun onInterrupt() {
        Log.i(TAG, "无障碍服务被中断")
        stopAssist()
    }

    override fun onDestroy() {
        super.onDestroy()
        stopAssist()
        instance = null
    }

    // ==================== 公共API ====================

    /**
     * 设置棋盘校准区域
     */
    fun calibrateBoard(left: Int, top: Int, right: Int, bottom: Int) {
        boardRecognizer.calibrate(left, top, right, bottom)
        Log.i(TAG, "棋盘校准完成: ($left,$top)-($right,$bottom)")
    }

    /**
     * 获取当前校准状态
     */
    fun isCalibrated(): Boolean = boardRecognizer.boardRegion != null

    /**
     * 开始辅助
     */
    fun startAssist(context: Context) {
        if (isAssisting) return
        if (!isCalibrated()) {
            Log.w(TAG, "棋盘未校准，无法开始辅助")
            return
        }

        // 初始化悬浮窗
        hintOverlay = HintOverlay(context)

        // 启动MediaProjection截屏
        val projectionIntent = pendingProjectionIntent
        if (projectionIntent == null) {
            Log.e(TAG, "未获取到MediaProjection授权")
            return
        }

        val projectionManager = context.getSystemService(Context.MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = projectionManager.getMediaProjection(pendingProjectionResultCode, projectionIntent)

        if (mediaProjection == null) {
            Log.e(TAG, "MediaProjection 初始化失败")
            return
        }

        setupImageReader()
        isAssisting = true
        handler.post(captureRunnable)

        Log.i(TAG, "辅助已启动")
    }

    /**
     * 停止辅助
     */
    fun stopAssist() {
        isAssisting = false
        handler.removeCallbacks(captureRunnable)

        hintOverlay?.removeHint()
        hintOverlay = null

        virtualDisplay?.release()
        virtualDisplay = null
        imageReader?.close()
        imageReader = null
        mediaProjection?.stop()
        mediaProjection = null

        Log.i(TAG, "辅助已停止")
    }

    /**
     * 手动触发一次截屏分析（调试用）
     */
    fun analyzeOnce() {
        if (isCalibrated()) {
            captureScreen()
        }
    }

    // ==================== 内部方法 ====================

    private fun setupImageReader() {
        val metrics = DisplayMetrics()
        val windowManager = getSystemService(Context.WINDOW_SERVICE) as android.view.WindowManager
        windowManager.defaultDisplay.getMetrics(metrics)

        val width = metrics.widthPixels
        val height = metrics.heightPixels
        val density = metrics.densityDpi

        // 创建 ImageReader
        imageReader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 2)

        // 创建 VirtualDisplay
        virtualDisplay = mediaProjection?.createVirtualDisplay(
            "Match3Capture",
            width, height, density,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            imageReader?.surface, null, handler
        )
    }

    private fun captureScreen() {
        val reader = imageReader ?: return

        try {
            val image: Image? = reader.acquireLatestImage()
            if (image == null) {
                Log.d(TAG, "获取图像失败")
                return
            }

            val bitmap = imageToBitmap(image)
            image.close()

            if (bitmap != null) {
                processScreenshot(bitmap)
                bitmap.recycle()
            }
        } catch (e: Exception) {
            Log.e(TAG, "截屏处理异常", e)
        }
    }

    private fun imageToBitmap(image: Image): Bitmap? {
        val planes = image.planes
        val buffer = planes[0].buffer
        val pixelStride = planes[0].pixelStride
        val rowStride = planes[0].rowStride
        val width = image.width
        val height = image.height

        // 处理可能的行填充
        val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        val rowPadding = rowStride - pixelStride * width

        // 逐行复制
        val pixels = IntArray(width * height)
        var offset = 0
        var index = 0

        for (i in 0 until height) {
            for (j in 0 until width) {
                // 读取RGBA并转为ARGB
                val r = buffer.get(offset).toInt() and 0xFF
                val g = buffer.get(offset + 1).toInt() and 0xFF
                val b = buffer.get(offset + 2).toInt() and 0xFF
                val a = buffer.get(offset + 3).toInt() and 0xFF
                pixels[index++] = (a shl 24) or (r shl 16) or (g shl 8) or b
                offset += pixelStride
            }
            offset += rowPadding
        }

        bitmap.setPixels(pixels, 0, width, 0, 0, width, height)
        return bitmap
    }

    private fun processScreenshot(bitmap: Bitmap) {
        // 1. 识别棋盘
        val board = boardRecognizer.recognize(bitmap)
        if (board == null) {
            Log.w(TAG, "棋盘识别失败")
            return
        }

        // 2. AI 综合决策（交换 / 道具）
        val action = gameAI.findBestAction(board)
        if (action == null) {
            Log.i(TAG, "未找到有效行动")
            hintOverlay?.removeHint()
            return
        }

        // 3. 根据行动类型显示不同提示
        val region = boardRecognizer.boardRegion
        if (region != null) {
            when (action.type) {
                GameAI.ActionType.SWAP -> {
                    action.move?.let { move ->
                        hintOverlay?.showSwapHint(move, region, action.reason)
                        Log.i(TAG, "交换提示: ${action.reason} | $move")
                    }
                }
                GameAI.ActionType.REFRESH -> {
                    hintOverlay?.showRefreshHint(action.reason)
                    Log.i(TAG, "道具提示: ${action.reason}")
                }
                GameAI.ActionType.HAMMER -> {
                    hintOverlay?.showHammerHint(action.reason)
                    Log.i(TAG, "道具提示: ${action.reason}")
                }
            }
        }
    }

    private fun createNotificationChannel() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                CHANNEL_ID,
                "消消乐助手",
                NotificationManager.IMPORTANCE_LOW
            )
            val manager = getSystemService(NotificationManager::class.java)
            manager.createNotificationChannel(channel)
        }
    }

    // ==================== 前台服务（用于保持MediaProjection）====================

    class ProjectionService : Service() {
        override fun onBind(intent: Intent?): IBinder? = null

        override fun onCreate() {
            super.onCreate()
            val notification = NotificationCompat.Builder(this, CHANNEL_ID)
                .setContentTitle("消消乐助手")
                .setContentText("截屏辅助运行中...")
                .setSmallIcon(android.R.drawable.ic_menu_info_details)
                .build()
            startForeground(NOTIFICATION_ID, notification)
        }
    }
}
