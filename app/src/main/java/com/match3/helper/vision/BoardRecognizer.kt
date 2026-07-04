package com.match3.helper.vision

import android.graphics.Bitmap
import android.graphics.Color
import android.util.Log
import com.match3.helper.model.BoardState

/**
 * 高性能棋盘图像识别器 V2
 * 优化点：
 * 1. Bitmap.getPixels() 批量读取 → 零 JNI 调用
 * 2. 整数 RGB 距离分类 → 无浮点运算
 * 3. 预计算参考颜色（从真实截图提取）
 * 4. 像素缓冲区复用 → 减少 GC
 * 5. 5 点十字采样 + 加权投票
 *
 * 理论速度提升：约 50-100 倍（原方案每帧 ~150 次 JNI + 浮点运算）
 */
class BoardRecognizer {

    companion object {
        private const val TAG = "BoardRecognizer"
        const val ROWS = 7
        const val COLS = 7

        /**
         * 参考颜色（RGB），从真实游戏截图提取的平均值
         * 顺序：PINK, GREEN, YELLOW, BLUE, RED
         * 对应 type 值：1, 2, 3, 4, 5
         */
        private val REF_COLORS = arrayOf(
            intArrayOf(227, 168, 236), // PINK (type=1)
            intArrayOf(171, 239, 131), // GREEN (type=2)
            intArrayOf(251, 244, 136), // YELLOW (type=3)
            intArrayOf(101, 217, 234), // BLUE (type=4)
            intArrayOf(246, 125, 113), // RED (type=5)
        )

        // 背景阈值：RGB最大值 < 60 或 色差 < 15 视为背景/空白
        private const val BG_MAX_THRESHOLD = 60
        private const val BG_DIFF_THRESHOLD = 15
    }

    /** 棋盘区域（屏幕绝对坐标） */
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

        fun getCellCenter(row: Int, col: Int): Pair<Float, Float> {
            val cx = left + col * cellWidth + cellWidth / 2f
            val cy = top + row * cellHeight + cellHeight / 2f
            return Pair(cx, cy)
        }
    }

    var boardRegion: BoardRegion? = null

    // 像素缓冲区（复用，避免每帧 GC）
    private var pixelBuffer: IntArray? = null
    private var bufferRegionWidth: Int = 0

    // 预计算的采样偏移（region 相对坐标）
    // 形状：samples[row][col] = IntArray(5) = [中心, 上, 下, 左, 右] 的像素索引
    private var sampleIndices: Array<Array<IntArray>>? = null

    // 颜色校准数据（用户可动态更新）
    private var calibratedColors: Array<IntArray> = REF_COLORS.copyOf()

    /**
     * 校准棋盘区域
     */
    fun calibrate(left: Int, top: Int, right: Int, bottom: Int) {
        val region = BoardRegion(left, top, right, bottom)
        boardRegion = region
        bufferRegionWidth = region.width

        // 重新计算采样索引
        precomputeSampleIndices(region)

        // 分配/复用像素缓冲区
        val bufferSize = region.width * region.height
        if (pixelBuffer == null || pixelBuffer!!.size < bufferSize) {
            pixelBuffer = IntArray(bufferSize)
        }

        Log.i(TAG, "棋盘已校准: $region, 采样点=${COLS * ROWS * 5}个")
    }

    /**
     * 使用当前截图自动校准颜色参考值
     * 从棋盘区域提取每个格子的中心颜色，按类型聚类更新参考值
     * 调用时机：校准棋盘后，或用户手动触发
     */
    fun autoCalibrateColors(bitmap: Bitmap) {
        val region = boardRegion ?: return
        val w = region.width
        val h = region.height
        val pixels = IntArray(w * h)
        bitmap.getPixels(pixels, 0, w, region.left, region.top, w, h)

        val cellW = w / COLS.toFloat()
        val cellH = h / ROWS.toFloat()

        // 收集每种颜色的样本
        val colorSamples = Array(5) { mutableListOf<IntArray>() }

        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                val cx = (c * cellW + cellW / 2).toInt()
                val cy = (r * cellH + cellH / 2).toInt()
                val px = pixels[cy * w + cx]

                // 跳过背景
                val rr = Color.red(px)
                val gg = Color.green(px)
                val bb = Color.blue(px)
                if (maxOf(rr, gg, bb) < BG_MAX_THRESHOLD || maxOf(rr, gg, bb) - minOf(rr, gg, bb) < BG_DIFF_THRESHOLD) {
                    continue
                }

                // 用当前参考颜色分类，归类样本
                var bestType = -1
                var bestDist = Int.MAX_VALUE
                for (i in 0 until 5) {
                    val ref = calibratedColors[i]
                    val dist = (rr - ref[0]) * (rr - ref[0]) +
                            (gg - ref[1]) * (gg - ref[1]) +
                            (bb - ref[2]) * (bb - ref[2])
                    if (dist < bestDist) {
                        bestDist = dist
                        bestType = i
                    }
                }

                if (bestType >= 0) {
                    colorSamples[bestType].add(intArrayOf(rr, gg, bb))
                }
            }
        }

        // 更新参考颜色为聚类平均值
        for (i in 0 until 5) {
            val samples = colorSamples[i]
            if (samples.size >= 3) {
                val avgR = samples.sumOf { it[0] } / samples.size
                val avgG = samples.sumOf { it[1] } / samples.size
                val avgB = samples.sumOf { it[2] } / samples.size
                calibratedColors[i] = intArrayOf(avgR, avgG, avgB)
                Log.i(TAG, "颜色校准 [${i+1}]: ${calibratedColors[i].contentToString()}, 样本数=${samples.size}")
            }
        }
    }

    /**
     * 从截屏中识别棋盘状态
     * 核心：一次性 getPixels() + 纯整数运算
     */
    fun recognize(bitmap: Bitmap): BoardState? {
        val region = boardRegion ?: return null
        val indices = sampleIndices ?: return null
        val buffer = pixelBuffer ?: return null
        val w = bufferRegionWidth

        val startTime = System.nanoTime()

        // 1. 一次性读取整个棋盘区域到缓冲区（仅 1 次 JNI 调用）
        bitmap.getPixels(buffer, 0, w, region.left, region.top, region.width, region.height)

        val grid = Array(ROWS) { IntArray(COLS) }

        // 2. 遍历每个格子，用预计算索引采样 + 整数距离分类
        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                val idxs = indices[r][c]
                val centerPixel = buffer[idxs[0]]

                // 快速背景检查（中心点）
                if (isBackground(centerPixel)) {
                    grid[r][c] = BoardState.EMPTY
                    continue
                }

                // 5 点采样投票
                val votes = IntArray(5) // 索引 0=PINK, 1=GREEN, 2=YELLOW, 3=BLUE, 4=RED
                for (s in 0 until idxs.size) {
                    val px = buffer[idxs[s]]
                    val typeIdx = classifyPixelInt(px) // 返回 0-4 的 type index
                    if (typeIdx >= 0) votes[typeIdx]++
                }

                // 取票数最多的类型
                var bestIdx = 0
                var bestVotes = votes[0]
                for (i in 1 until 5) {
                    if (votes[i] > bestVotes) {
                        bestVotes = votes[i]
                        bestIdx = i
                    }
                }

                // 如果票数不足（<2），退回到中心点最近邻
                if (bestVotes < 2) {
                    bestIdx = classifyPixelInt(centerPixel).coerceAtLeast(0)
                }

                grid[r][c] = bestIdx + 1 // 转换为 BoardState type (1-5)
            }
        }

        val elapsed = (System.nanoTime() - startTime) / 1_000_000f
        Log.i(TAG, "识别耗时: ${elapsed}ms")

        val board = BoardState(grid)
        Log.d(TAG, "识别结果:\n$board")
        return board
    }

    // ==================== 内部高性能方法 ====================

    /**
     * 预计算所有格子的采样像素索引
     * 采样点：中心 + 十字形 4 点（上、下、左、右）
     * 偏移距离：格子短边的 15%，最少 2 像素
     */
    private fun precomputeSampleIndices(region: BoardRegion) {
        val w = region.width
        val h = region.height
        val cellW = w / COLS.toFloat()
        val cellH = h / ROWS.toFloat()
        val offset = (minOf(cellW, cellH) * 0.15f).toInt().coerceAtLeast(2)

        val indices = Array(ROWS) { Array(COLS) { IntArray(5) } }

        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                val cx = (c * cellW + cellW / 2).toInt()
                val cy = (r * cellH + cellH / 2).toInt()

                // 确保采样点在边界内
                val up = (cy - offset).coerceIn(0, h - 1)
                val down = (cy + offset).coerceIn(0, h - 1)
                val left = (cx - offset).coerceIn(0, w - 1)
                val right = (cx + offset).coerceIn(0, w - 1)

                indices[r][c] = intArrayOf(
                    cy * w + cx,      // 中心
                    up * w + cx,      // 上
                    down * w + cx,    // 下
                    cy * w + left,    // 左
                    cy * w + right,   // 右
                )
            }
        }

        sampleIndices = indices
        Log.i(TAG, "预计算采样索引完成: ${ROWS * COLS} 格子 × 5 点")
    }

    /**
     * 整数 RGB 距离分类
     * 返回：类型索引 0=PINK, 1=GREEN, 2=YELLOW, 3=BLUE, 4=RED
     * 如果像素太暗/太灰，返回 -1
     */
    private fun classifyPixelInt(pixel: Int): Int {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF

        // 快速背景检查
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        if (max < BG_MAX_THRESHOLD || max - min < BG_DIFF_THRESHOLD) {
            return -1
        }

        // 整数平方距离比较（无需 sqrt，避免浮点）
        var bestIdx = 0
        var bestDist = Int.MAX_VALUE

        for (i in 0 until 5) {
            val ref = calibratedColors[i]
            val dr = r - ref[0]
            val dg = g - ref[1]
            val db = b - ref[2]
            val dist = dr * dr + dg * dg + db * db
            if (dist < bestDist) {
                bestDist = dist
                bestIdx = i
            }
        }

        return bestIdx
    }

    /**
     * 检查像素是否可能是背景
     */
    private fun isBackground(pixel: Int): Boolean {
        val r = (pixel shr 16) and 0xFF
        val g = (pixel shr 8) and 0xFF
        val b = pixel and 0xFF
        val max = maxOf(r, g, b)
        val min = minOf(r, g, b)
        return max < BG_MAX_THRESHOLD || max - min < BG_DIFF_THRESHOLD
    }

    /**
     * 手动更新某种颜色的参考值
     * 用于用户自定义颜色校准
     */
    fun setReferenceColor(type: Int, r: Int, g: Int, b: Int) {
        val idx = when (type) {
            BoardState.PINK -> 0
            BoardState.GREEN -> 1
            BoardState.YELLOW -> 2
            BoardState.BLUE -> 3
            BoardState.RED -> 4
            else -> return
        }
        calibratedColors[idx] = intArrayOf(r, g, b)
        Log.i(TAG, "手动设置参考颜色 [$type]: ($r, $g, $b)")
    }

    /**
     * 获取当前参考颜色（用于调试/显示）
     */
    fun getReferenceColors(): Map<Int, IntArray> {
        return mapOf(
            BoardState.PINK to calibratedColors[0],
            BoardState.GREEN to calibratedColors[1],
            BoardState.YELLOW to calibratedColors[2],
            BoardState.BLUE to calibratedColors[3],
            BoardState.RED to calibratedColors[4],
        )
    }

    /**
     * 自动检测棋盘区域（简化版：基于颜色密度）
     */
    fun autoDetectBoard(bitmap: Bitmap): BoardRegion? {
        val width = bitmap.width
        val height = bitmap.height
        val step = 8 // 大步长降采样扫描

        var minX = width
        var minY = height
        var maxX = 0
        var maxY = 0
        var gemCount = 0

        // 扫描整个图片找宝石像素
        for (y in 0 until height step step) {
            for (x in 0 until width step step) {
                val pixel = bitmap.getPixel(x, y)
                if (!isBackground(pixel)) {
                    minX = minOf(minX, x)
                    minY = minOf(minY, y)
                    maxX = maxOf(maxX, x)
                    maxY = maxOf(maxY, y)
                    gemCount++
                }
            }
        }

        if (gemCount < 20) {
            Log.w(TAG, "未检测到足够宝石像素")
            return null
        }

        // 稍微扩大边界
        val padding = 8
        val region = BoardRegion(
            left = (minX - padding).coerceAtLeast(0),
            top = (minY - padding).coerceAtLeast(0),
            right = (maxX + padding).coerceAtMost(width),
            bottom = (maxY + padding).coerceAtMost(height)
        )

        calibrate(region.left, region.top, region.right, region.bottom)
        Log.i(TAG, "自动检测到棋盘: $region")
        return region
    }
}
