package com.match3.helper.overlay

import android.content.Context
import android.graphics.*
import android.util.Log
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import com.match3.helper.model.GameMove
import com.match3.helper.vision.BoardRecognizer

/**
 * 悬浮窗提示层 V2
 * 支持三种提示类型：交换箭头、刷新道具、锤击道具
 */
class HintOverlay(private val context: Context) {

    companion object {
        private const val TAG = "HintOverlay"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: View? = null

    // ==================== 交换提示 ====================

    fun showSwapHint(move: GameMove, boardRegion: BoardRecognizer.BoardRegion, reason: String) {
        removeHint()

        val (fromX, fromY) = boardRegion.getCellCenter(move.fromRow, move.fromCol)
        val (toX, toY) = boardRegion.getCellCenter(move.toRow, move.toCol)

        val params = createFullScreenParams()
        val hintView = SwapHintView(context, fromX, fromY, toX, toY, boardRegion, reason)
        overlayView = hintView
        windowManager.addView(hintView, params)
    }

    // ==================== 刷新道具提示 ====================

    fun showRefreshHint(reason: String) {
        removeHint()

        val params = createFullScreenParams()
        val hintView = RefreshHintView(context, reason)
        overlayView = hintView
        windowManager.addView(hintView, params)
    }

    // ==================== 锤击道具提示 ====================

    fun showHammerHint(reason: String) {
        removeHint()

        val params = createFullScreenParams()
        val hintView = HammerHintView(context, reason)
        overlayView = hintView
        windowManager.addView(hintView, params)
    }

    // ==================== 移除提示 ====================

    fun removeHint() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (_: Exception) {
                // 可能已被移除
            }
            overlayView = null
        }
    }

    // ==================== 内部工具 ====================

    private fun createFullScreenParams(): WindowManager.LayoutParams {
        return WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.START
        }
    }

    // ==================== 交换提示视图 ====================

    private class SwapHintView(
        context: Context,
        private val fromX: Float,
        private val fromY: Float,
        private val toX: Float,
        private val toY: Float,
        private val boardRegion: BoardRecognizer.BoardRegion,
        private val reason: String
    ) : View(context) {

        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            arrowPaint.color = Color.parseColor("#FFFFD700") // 金色
            arrowPaint.strokeWidth = 10f
            arrowPaint.style = Paint.Style.STROKE
            arrowPaint.strokeCap = Paint.Cap.ROUND

            circlePaint.style = Paint.Style.STROKE
            circlePaint.strokeWidth = 8f

            textPaint.color = Color.WHITE
            textPaint.textSize = 40f
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.setShadowLayer(5f, 3f, 3f, Color.BLACK)
            textPaint.isFakeBoldText = true

            bgPaint.color = Color.parseColor("#AA000000")
            bgPaint.style = Paint.Style.FILL
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val cellRadius = minOf(boardRegion.cellWidth, boardRegion.cellHeight) * 0.38f

            // 画高亮圆圈
            circlePaint.color = Color.parseColor("#FF00FF00")
            canvas.drawCircle(fromX, fromY, cellRadius, circlePaint)
            circlePaint.color = Color.parseColor("#FFFF6600")
            canvas.drawCircle(toX, toY, cellRadius, circlePaint)

            // 画箭头
            drawArrow(canvas, fromX, fromY, toX, toY)

            // 画原因文字（在棋盘上方居中）
            val textY = boardRegion.top - 20f
            if (textY > 60) {
                // 文字背景
                val textBounds = Rect()
                textPaint.getTextBounds(reason, 0, reason.length, textBounds)
                val padding = 16f
                canvas.drawRoundRect(
                    width / 2f - textBounds.width() / 2f - padding,
                    textY - textBounds.height() - padding,
                    width / 2f + textBounds.width() / 2f + padding,
                    textY + padding,
                    20f, 20f, bgPaint
                )
                canvas.drawText(reason, width / 2f, textY, textPaint)
            }
        }

        private fun drawArrow(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
            val offset = 25f
            val dx = x2 - x1
            val dy = y2 - y1
            val len = kotlin.math.hypot(dx, dy)
            if (len < 1f) return

            val ux = dx / len
            val uy = dy / len
            val sx = x1 + ux * offset
            val sy = y1 + uy * offset
            val ex = x2 - ux * offset
            val ey = y2 - uy * offset

            canvas.drawLine(sx, sy, ex, ey, arrowPaint)

            val arrowLen = 30f
            val arrowAngle = Math.PI / 6
            val angle = kotlin.math.atan2((ey - sy).toDouble(), (ex - sx).toDouble())

            val a1x = (ex - arrowLen * kotlin.math.cos(angle - arrowAngle)).toFloat()
            val a1y = (ey - arrowLen * kotlin.math.sin(angle - arrowAngle)).toFloat()
            val a2x = (ex - arrowLen * kotlin.math.cos(angle + arrowAngle)).toFloat()
            val a2y = (ey - arrowLen * kotlin.math.sin(angle + arrowAngle)).toFloat()

            canvas.drawLine(ex, ey, a1x, a1y, arrowPaint)
            canvas.drawLine(ex, ey, a2x, a2y, arrowPaint)
        }
    }

    // ==================== 刷新道具提示视图 ====================

    private class RefreshHintView(context: Context, private val reason: String) : View(context) {

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val iconPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            bgPaint.color = Color.parseColor("#E63946") // 红色提示
            bgPaint.style = Paint.Style.FILL

            textPaint.color = Color.WHITE
            textPaint.textSize = 48f
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.setShadowLayer(4f, 2f, 2f, Color.BLACK)
            textPaint.isFakeBoldText = true

            iconPaint.color = Color.WHITE
            iconPaint.style = Paint.Style.STROKE
            iconPaint.strokeWidth = 6f
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val cx = width / 2f
            val cy = height / 3f

            // 画圆角矩形背景
            val rect = RectF(cx - 200f, cy - 60f, cx + 200f, cy + 60f)
            canvas.drawRoundRect(rect, 30f, 30f, bgPaint)

            // 画刷新图标（旋转箭头）
            drawRefreshIcon(canvas, cx - 120f, cy)

            // 画文字
            canvas.drawText("🔄 使用刷新道具", cx + 20f, cy - 10f, textPaint)
            canvas.drawText(reason, cx + 20f, cy + 30f, Paint().apply {
                color = Color.WHITE
                textSize = 28f
                textAlign = Paint.Align.CENTER
            })
        }

        private fun drawRefreshIcon(canvas: Canvas, cx: Float, cy: Float) {
            val radius = 30f
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.WHITE
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 5f

            val rect = RectF(cx - radius, cy - radius, cx + radius, cy + radius)
            canvas.drawArc(rect, 30f, 300f, false, paint)

            // 箭头
            val arrowX = cx + radius * 0.7f
            val arrowY = cy - radius * 0.5f
            canvas.drawLine(arrowX, arrowY, arrowX + 12f, arrowY - 8f, paint)
            canvas.drawLine(arrowX, arrowY, arrowX + 4f, arrowY + 10f, paint)
        }
    }

    // ==================== 锤击道具提示视图 ====================

    private class HammerHintView(context: Context, private val reason: String) : View(context) {

        private val bgPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            bgPaint.color = Color.parseColor("#F4A261") // 橙色提示
            bgPaint.style = Paint.Style.FILL

            textPaint.color = Color.WHITE
            textPaint.textSize = 48f
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.setShadowLayer(4f, 2f, 2f, Color.BLACK)
            textPaint.isFakeBoldText = true
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val cx = width / 2f
            val cy = height / 3f

            // 画圆角矩形背景
            val rect = RectF(cx - 200f, cy - 60f, cx + 200f, cy + 60f)
            canvas.drawRoundRect(rect, 30f, 30f, bgPaint)

            // 画锤击图标
            drawHammerIcon(canvas, cx - 120f, cy)

            // 画文字
            canvas.drawText("🔨 使用锤击道具", cx + 20f, cy - 10f, textPaint)
            canvas.drawText(reason, cx + 20f, cy + 30f, Paint().apply {
                color = Color.WHITE
                textSize = 28f
                textAlign = Paint.Align.CENTER
            })
        }

        private fun drawHammerIcon(canvas: Canvas, cx: Float, cy: Float) {
            val paint = Paint(Paint.ANTI_ALIAS_FLAG)
            paint.color = Color.WHITE
            paint.style = Paint.Style.FILL

            // 锤头
            canvas.drawRect(cx - 20f, cy - 25f, cx + 20f, cy - 5f, paint)
            // 锤柄
            paint.style = Paint.Style.STROKE
            paint.strokeWidth = 6f
            canvas.drawLine(cx, cy - 5f, cx + 10f, cy + 25f, paint)
        }
    }
}
