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
 * 悬浮窗提示层
 * 在屏幕上画箭头和高亮来指示最优走法
 */
class HintOverlay(private val context: Context) {

    companion object {
        private const val TAG = "HintOverlay"
    }

    private val windowManager = context.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private var overlayView: HintView? = null

    /**
     * 显示走法提示
     * @param move 最优走法
     * @param boardRegion 棋盘区域（用于计算屏幕坐标）
     */
    fun showHint(move: GameMove, boardRegion: BoardRecognizer.BoardRegion) {
        removeHint()

        val (fromX, fromY) = boardRegion.getCellCenter(move.fromRow, move.fromCol)
        val (toX, toY) = boardRegion.getCellCenter(move.toRow, move.toCol)

        // 创建覆盖全屏的透明悬浮窗
        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.MATCH_PARENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCHABLE or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        )
        params.gravity = Gravity.TOP or Gravity.START

        val hintView = HintView(context, fromX, fromY, toX, toY, boardRegion)
        overlayView = hintView
        windowManager.addView(hintView, params)

        Log.i(TAG, "显示提示: (${move.fromRow},${move.fromCol}) → (${move.toRow},${move.toCol})")
    }

    /**
     * 移除提示
     */
    fun removeHint() {
        overlayView?.let {
            try {
                windowManager.removeView(it)
            } catch (e: Exception) {
                // 可能已经被移除
            }
            overlayView = null
        }
    }

    /**
     * 自定义绘制视图：画箭头和高亮圈
     */
    private class HintView(
        context: Context,
        private val fromX: Float,
        private val fromY: Float,
        private val toX: Float,
        private val toY: Float,
        private val boardRegion: BoardRecognizer.BoardRegion
    ) : View(context) {

        private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val arrowPaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val circlePaint = Paint(Paint.ANTI_ALIAS_FLAG)
        private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

        init {
            arrowPaint.color = Color.parseColor("#FFFFD700") // 金色
            arrowPaint.strokeWidth = 8f
            arrowPaint.style = Paint.Style.STROKE
            arrowPaint.strokeCap = Paint.Cap.ROUND

            circlePaint.color = Color.parseColor("#FF00FF00") // 绿色
            circlePaint.style = Paint.Style.STROKE
            circlePaint.strokeWidth = 6f

            textPaint.color = Color.WHITE
            textPaint.textSize = 36f
            textPaint.textAlign = Paint.Align.CENTER
            textPaint.setShadowLayer(4f, 2f, 2f, Color.BLACK)
        }

        override fun onDraw(canvas: Canvas) {
            super.onDraw(canvas)

            val cellRadius = minOf(boardRegion.cellWidth, boardRegion.cellHeight) * 0.35f

            // 画两个高亮圆圈
            circlePaint.color = Color.parseColor("#FF00FF00")
            circlePaint.strokeWidth = 6f
            canvas.drawCircle(fromX, fromY, cellRadius, circlePaint)

            circlePaint.color = Color.parseColor("#FFFF6600")
            canvas.drawCircle(toX, toY, cellRadius, circlePaint)

            // 画连接箭头
            drawArrow(canvas, fromX, fromY, toX, toY)

            // 画标签
            textPaint.color = Color.WHITE
            canvas.drawText("交换", (fromX + toX) / 2, (fromY + toY) / 2 - 20, textPaint)
        }

        private fun drawArrow(canvas: Canvas, x1: Float, y1: Float, x2: Float, y2: Float) {
            // 缩短起点和终点（不画到圆心）
            val offset = 20f
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

            // 画线
            canvas.drawLine(sx, sy, ex, ey, arrowPaint)

            // 画箭头
            val arrowLen = 25f
            val arrowAngle = Math.PI / 6 // 30度

            val angle = kotlin.math.atan2((ey - sy).toDouble(), (ex - sx).toDouble())

            val arrow1X = (ex - arrowLen * kotlin.math.cos(angle - arrowAngle)).toFloat()
            val arrow1Y = (ey - arrowLen * kotlin.math.sin(angle - arrowAngle)).toFloat()
            val arrow2X = (ex - arrowLen * kotlin.math.cos(angle + arrowAngle)).toFloat()
            val arrow2Y = (ey - arrowLen * kotlin.math.sin(angle + arrowAngle)).toFloat()

            canvas.drawLine(ex, ey, arrow1X, arrow1Y, arrowPaint)
            canvas.drawLine(ex, ey, arrow2X, arrow2Y, arrowPaint)
        }
    }
}
