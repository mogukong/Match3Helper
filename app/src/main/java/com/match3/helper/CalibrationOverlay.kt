package com.match3.helper

import android.content.Context
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.RectF
import android.view.MotionEvent
import android.view.View

/**
 * 棋盘校准覆盖层
 * 用户通过触摸拖动来框选棋盘区域
 */
class CalibrationOverlay(
    context: Context,
    private val onCalibrationComplete: (Int, Int, Int, Int) -> Unit
) : View(context) {

    private val paint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val rectPaint = Paint(Paint.ANTI_ALIAS_FLAG)
    private val textPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    private var startX = 0f
    private var startY = 0f
    private var currentX = 0f
    private var currentY = 0f
    private var isDragging = false
    private var selectionRect = RectF()
    private var isComplete = false

    init {
        paint.color = Color.parseColor("#3300FF00") // 半透明绿
        paint.style = Paint.Style.FILL

        rectPaint.color = Color.parseColor("#FF00FF00") // 绿色边框
        rectPaint.style = Paint.Style.STROKE
        rectPaint.strokeWidth = 4f

        textPaint.color = Color.WHITE
        textPaint.textSize = 48f
        textPaint.textAlign = Paint.Align.CENTER
        textPaint.setShadowLayer(4f, 2f, 2f, Color.BLACK)
    }

    override fun onDraw(canvas: Canvas) {
        super.onDraw(canvas)

        // 半透明遮罩
        canvas.drawColor(Color.parseColor("#66000000"))

        if (isDragging || isComplete) {
            // 清除选中区域的遮罩
            canvas.drawRect(selectionRect, paint)
            canvas.drawRect(selectionRect, rectPaint)

            // 显示提示文字
            val centerX = selectionRect.centerX()
            val centerY = selectionRect.centerY()
            canvas.drawText("松开手指完成校准", centerX, centerY - 30, textPaint)
            canvas.drawText(
                "${selectionRect.width().toInt()} x ${selectionRect.height().toInt()}",
                centerX, centerY + 30, textPaint
            )
        } else {
            // 显示引导文字
            canvas.drawText("按住屏幕左上角拖动到右下角", width / 2f, height / 2f - 30, textPaint)
            canvas.drawText("框选整个棋盘区域", width / 2f, height / 2f + 30, textPaint)
        }
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        when (event.action) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.x
                startY = event.y
                currentX = event.x
                currentY = event.y
                isDragging = true
                isComplete = false
                updateRect()
                invalidate()
                return true
            }
            MotionEvent.ACTION_MOVE -> {
                if (isDragging) {
                    currentX = event.x
                    currentY = event.y
                    updateRect()
                    invalidate()
                }
                return true
            }
            MotionEvent.ACTION_UP -> {
                if (isDragging) {
                    currentX = event.x
                    currentY = event.y
                    isDragging = false
                    isComplete = true
                    updateRect()
                    invalidate()

                    // 延迟一点再回调，让用户看到最终框选结果
                    postDelayed({
                        val left = selectionRect.left.toInt().coerceAtLeast(0)
                        val top = selectionRect.top.toInt().coerceAtLeast(0)
                        val right = selectionRect.right.toInt().coerceAtMost(width)
                        val bottom = selectionRect.bottom.toInt().coerceAtMost(height)
                        onCalibrationComplete(left, top, right, bottom)
                    }, 300)
                }
                return true
            }
        }
        return super.onTouchEvent(event)
    }

    private fun updateRect() {
        val left = minOf(startX, currentX)
        val top = minOf(startY, currentY)
        val right = maxOf(startX, currentX)
        val bottom = maxOf(startY, currentY)
        selectionRect.set(left, top, right, bottom)
    }
}
