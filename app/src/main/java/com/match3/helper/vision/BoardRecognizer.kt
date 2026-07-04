package com.match3.helper.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.match3.helper.model.BoardState

/**
 * 棋盘图像识别器
 * 通过颜色采样将屏幕截图转换为棋盘状态
 */
class BoardRecognizer {

    companion object {
        private const val TAG = "BoardRecognizer"
        const val ROWS = 7
        const val COLS = 7

        // HSV 颜色分类阈值（需要根据实际截图微调）
        // 色相范围：0-360，饱和度：0-1，亮度：0-1
        private val COLOR_PROFILES = listOf(
            // 粉色/紫色六边形 - H约 280-330
            GemProfile(BoardState.PINK, "粉", hMin = 260f, hMax = 340f, sMin = 0.3f, vMin = 0.4f),
            // 绿色三角形 - H约 100-150
            GemProfile(BoardState.GREEN, "绿", hMin = 90f, hMax = 160f, sMin = 0.3f, vMin = 0.4f),
            // 黄色方形 - H约 40-70
            GemProfile(BoardState.YELLOW, "黄", hMin = 30f, hMax = 75f, sMin = 0.3f, vMin = 0.5f),
            // 蓝色圆形 - H约 190-240
            GemProfile(BoardState.BLUE, "蓝", hMin = 175f, hMax = 255f, sMin = 0.3f, vMin = 0.4f),
            // 红色水滴 - H约 0-20 或 340-360
            GemProfile(BoardState.RED, "红", hMin = 330f, hMax = 360f, sMin = 0.3f, vMin = 0.4f, hMin2 = 0f, hMax2 = 25f),
        )
    }

    data class GemProfile(
        val type: Int,
        val name: String,
        val hMin: Float,
        val hMax: Float,
        val sMin: Float = 0.2f,
        val vMin: Float = 0.3f,
        val hMin2: Float = -1f,
        val hMax2: Float = -1f
    ) {
        fun matches(h: Float, s: Float, v: Float): Boolean {
            if (s < sMin || v < vMin) return false
            val inRange1 = h in hMin..hMax
            val inRange2 = if (hMin2 >= 0) h in hMin2..hMax2 else false
            return inRange1 || inRange2
        }
    }

    /** 棋盘区域（在屏幕上的绝对坐标） */
    data class BoardRegion(
        val left: Int,
        val top: Int,
        val right: Int,
        val bottom: Int
    ) {
        val width: Int get() = right - left
        val height: Int get() = bottom - top
        val cellWidth: Float get() = width.toFloat() / COLS
        val cellHeight: Float get() = height.toFloat() / ROWS

        /** 获取某格子的中心坐标（屏幕绝对坐标） */
        fun getCellCenter(row: Int, col: Int): Pair<Float, Float> {
            val cx = left + col * cellWidth + cellWidth / 2f
            val cy = top + row * cellHeight + cellHeight / 2f
            return Pair(cx, cy)
        }
    }

    var boardRegion: BoardRegion? = null

    /**
     * 校准棋盘区域
     * 用户框选棋盘左上角和右下角后调用
     */
    fun calibrate(left: Int, top: Int, right: Int, bottom: Int) {
        boardRegion = BoardRegion(left, top, right, bottom)
        Log.i(TAG, "棋盘已校准: $boardRegion")
    }

    /**
     * 从截屏Bitmap中识别棋盘状态
     */
    fun recognize(bitmap: Bitmap): BoardState? {
        val region = boardRegion ?: return null

        val grid = Array(ROWS) { IntArray(COLS) }

        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                val (cx, cy) = region.getCellCenter(r, c)
                val type = classifyCell(bitmap, cx.toInt(), cy.toInt(), region.cellWidth.toInt())
                grid[r][c] = type
            }
        }

        val board = BoardState(grid)
        Log.i(TAG, "识别结果:\n$board")
        return board
    }

    /**
     * 分类单个格子
     * 在格子中心附近采样多个像素，用多数投票决定类型
     */
    private fun classifyCell(bitmap: Bitmap, cx: Int, cy: Int, cellSize: Int): Int {
        val sampleRadius = (cellSize * 0.25f).toInt().coerceAtLeast(3)
        val votes = mutableMapOf<Int, Int>()

        // 九宫格采样中心区域
        val offsets = listOf(0, -sampleRadius / 2, sampleRadius / 2)
        for (dy in offsets) {
            for (dx in offsets) {
                val x = (cx + dx).coerceIn(0, bitmap.width - 1)
                val y = (cy + dy).coerceIn(0, bitmap.height - 1)
                val pixel = bitmap.getPixel(x, y)
                val type = classifyPixel(pixel)
                votes[type] = (votes[type] ?: 0) + 1
            }
        }

        // 返回得票最多的类型
        return votes.maxByOrNull { it.value }?.key ?: BoardState.EMPTY
    }

    /**
     * 将单个像素分类为宝石类型
     */
    private fun classifyPixel(pixel: Int): Int {
        val r = Color.red(pixel)
        val g = Color.green(pixel)
        val b = Color.blue(pixel)

        val (h, s, v) = rgbToHsv(r, g, b)

        // 先检查是否是背景色（太浅或太灰）
        if (v < 0.25f || s < 0.15f) {
            return BoardState.EMPTY
        }

        // 匹配颜色配置
        for (profile in COLOR_PROFILES) {
            if (profile.matches(h, s, v)) {
                return profile.type
            }
        }

        // 如果都没匹配上，根据最接近的色相来判断
        return findClosestByHue(h, s, v)
    }

    /**
     * RGB转HSV
     */
    private fun rgbToHsv(r: Int, g: Int, b: Int): Triple<Float, Float, Float> {
        val rf = r / 255f
        val gf = g / 255f
        val bf = b / 255f

        val max = maxOf(rf, gf, bf)
        val min = minOf(rf, gf, bf)
        val diff = max - min

        val v = max
        val s = if (max == 0f) 0f else diff / max

        val h = when {
            diff == 0f -> 0f
            max == rf -> (60 * ((gf - bf) / diff) + 360) % 360
            max == gf -> (60 * ((bf - rf) / diff) + 120)
            else -> (60 * ((rf - gf) / diff) + 240)
        }

        return Triple(h, s, v)
    }

    /**
     * 根据色相找最接近的颜色类型
     */
    private fun findClosestByHue(h: Float, s: Float, v: Float): Int {
        if (s < 0.15f || v < 0.25f) return BoardState.EMPTY

        val hueDistances = COLOR_PROFILES.map { profile ->
            val center = when {
                profile.hMin2 >= 0 -> {
                    // 红色有两个区间，取较近的中心
                    val c1 = (profile.hMin + profile.hMax) / 2
                    val c2 = (profile.hMin2 + profile.hMax2) / 2
                    val d1 = hueDistance(h, c1)
                    val d2 = hueDistance(h, c2)
                    if (d1 < d2) c1 else c2
                }
                else -> (profile.hMin + profile.hMax) / 2
            }
            val dist = hueDistance(h, center)
            profile.type to dist
        }

        return hueDistances.minByOrNull { it.second }?.first ?: BoardState.EMPTY
    }

    private fun hueDistance(a: Float, b: Float): Float {
        val diff = kotlin.math.abs(a - b)
        return minOf(diff, 360f - diff)
    }

    /**
     * 自动检测棋盘区域（简化版：根据颜色特征找最密集的7x7网格区域）
     * 用户不方便手动校准时可以用这个
     */
    fun autoDetectBoard(bitmap: Bitmap): BoardRegion? {
        val width = bitmap.width
        val height = bitmap.height

        // 扫描整个图片，找出可能的宝石区域
        // 策略：找到宝石颜色密集分布的矩形区域
        val gemPixels = mutableListOf<Pair<Int, Int>>()

        val step = 4 // 降采样加速
        for (y in 0 until height step step) {
            for (x in 0 until width step step) {
                val pixel = bitmap.getPixel(x, y)
                val type = classifyPixel(pixel)
                if (type != BoardState.EMPTY) {
                    gemPixels.add(Pair(x, y))
                }
            }
        }

        if (gemPixels.size < 20) {
            Log.w(TAG, "未检测到足够宝石像素")
            return null
        }

        // 计算包围盒
        val minX = gemPixels.minOf { it.first }
        val maxX = gemPixels.maxOf { it.first }
        val minY = gemPixels.minOf { it.second }
        val maxY = gemPixels.maxOf { it.second }

        // 估算格子大小：假设是7x7，平均分配
        val estCellW = (maxX - minX) / COLS.toFloat()
        val estCellH = (maxY - minY) / ROWS.toFloat()

        // 稍微扩大一点边界
        val padding = (estCellW * 0.1f).toInt()
        val region = BoardRegion(
            left = (minX - padding).coerceAtLeast(0),
            top = (minY - padding).coerceAtLeast(0),
            right = (maxX + padding).coerceAtMost(width),
            bottom = (maxY + padding).coerceAtMost(height)
        )

        boardRegion = region
        Log.i(TAG, "自动检测到棋盘: $region")
        return region
    }
}
