package com.match3.helper.model

/**
 * 棋盘状态：7x7 的二维数组
 * 元素类型：
 *  0 = 空
 *  1 = 粉色六边形 (PINK)
 *  2 = 绿色三角形 (GREEN)
 *  3 = 黄色方形   (YELLOW)
 *  4 = 蓝色圆形   (BLUE)
 *  5 = 红色水滴   (RED)
 *  6+ = 特殊棋子（如火箭、炸弹、彩虹球等）
 */
class BoardState(val grid: Array<IntArray>) {
    companion object {
        const val EMPTY = 0
        const val PINK = 1
        const val GREEN = 2
        const val YELLOW = 3
        const val BLUE = 4
        const val RED = 5
        const val ROWS = 7
        const val COLS = 7

        fun getColorName(type: Int): String = when (type) {
            PINK -> "粉"
            GREEN -> "绿"
            YELLOW -> "黄"
            BLUE -> "蓝"
            RED -> "红"
            else -> "?"
        }
    }

    init {
        require(grid.size == ROWS && grid.all { it.size == COLS }) {
            "Board must be ${ROWS}x${COLS}"
        }
    }

    /** 深拷贝 */
    fun copy(): BoardState {
        val newGrid = Array(ROWS) { r -> IntArray(COLS) { c -> grid[r][c] } }
        return BoardState(newGrid)
    }

    /** 获取某个格子的类型 */
    operator fun get(row: Int, col: Int): Int = grid[row][col]

    /** 设置某个格子的类型 */
    operator fun set(row: Int, col: Int, value: Int) {
        grid[row][col] = value
    }

    /** 检查两个位置是否可以交换（相邻） */
    fun isAdjacent(a: Pair<Int, Int>, b: Pair<Int, Int>): Boolean {
        val dr = kotlin.math.abs(a.first - b.first)
        val dc = kotlin.math.abs(a.second - b.second)
        return dr + dc == 1
    }

    /** 检查某个交换是否能形成消除 */
    fun canEliminateAfterSwap(pos1: Pair<Int, Int>, pos2: Pair<Int, Int>): Boolean {
        if (!isAdjacent(pos1, pos2)) return false
        val copy = this.copy()
        // 交换
        val temp = copy[pos1.first, pos1.second]
        copy[pos1.first, pos1.second] = copy[pos2.first, pos2.second]
        copy[pos2.first, pos2.second] = temp
        // 检查是否有消除
        return copy.findEliminations().isNotEmpty()
    }

    /**
     * 找出所有消除组合
     * 返回：每行/列上连续 >=3 的同色格子的位置列表
     */
    fun findEliminations(): List<List<Pair<Int, Int>>> {
        val eliminations = mutableListOf<List<Pair<Int, Int>>>()
        val visited = Array(ROWS) { BooleanArray(COLS) }

        // 检查行
        for (r in 0 until ROWS) {
            var c = 0
            while (c < COLS) {
                if (grid[r][c] == EMPTY) { c++; continue }
                val type = grid[r][c]
                var end = c + 1
                while (end < COLS && grid[r][end] == type) end++
                if (end - c >= 3) {
                    val group = (c until end).map { Pair(r, it) }
                    group.forEach { visited[it.first][it.second] = true }
                    eliminations.add(group)
                }
                c = end
            }
        }

        // 检查列
        for (c in 0 until COLS) {
            var r = 0
            while (r < ROWS) {
                if (grid[r][c] == EMPTY) { r++; continue }
                val type = grid[r][c]
                var end = r + 1
                while (end < ROWS && grid[end][c] == type) end++
                if (end - r >= 3) {
                    val group = (r until end).map { Pair(it, c) }
                    group.forEach { visited[it.first][it.second] = true }
                    eliminations.add(group)
                }
                r = end
            }
        }

        return eliminations
    }

    /**
     * 模拟一次交换并执行消除（包括连锁）
     * 返回：这次操作的总得分
     */
    fun simulateSwapAndEliminate(pos1: Pair<Int, Int>, pos2: Pair<Int, Int>): SwapResult {
        val copy = this.copy()
        // 执行交换
        val temp = copy[pos1.first, pos1.second]
        copy[pos1.first, pos1.second] = copy[pos2.first, pos2.second]
        copy[pos2.first, pos2.second] = temp

        var totalScore = 0
        var chainCount = 0
        var specialGenerated = 0
        var stepsGained = 0

        // 连锁消除循环
        while (true) {
            val eliminations = copy.findEliminations()
            if (eliminations.isEmpty()) break

            chainCount++
            // 合并所有消除格子去重
            val allEliminated = mutableSetOf<Pair<Int, Int>>()
            eliminations.forEach { allEliminated.addAll(it) }

            // 计分
            val eliminatedCount = allEliminated.size
            val baseScore = when {
                eliminatedCount >= 5 -> eliminatedCount * 200
                eliminatedCount == 4 -> eliminatedCount * 150
                else -> eliminatedCount * 100
            }
            val chainMultiplier = if (chainCount > 1) (chainCount - 1) * 0.5 + 1.0 else 1.0
            totalScore += (baseScore * chainMultiplier).toInt()

            // 4个及以上消除 → 生成特殊棋子 + 增加步数
            eliminations.forEach { group ->
                if (group.size >= 4) {
                    specialGenerated++
                    stepsGained++
                }
            }

            // 标记消除格子
            val eliminatedPositions = allEliminated.toMutableSet()
            eliminatedPositions.forEach { (r, c) ->
                copy[r, c] = EMPTY
            }

            // 下落：每列中，非空格子下沉到底部
            for (c in 0 until COLS) {
                val nonEmpty = mutableListOf<Int>()
                for (r in ROWS - 1 downTo 0) {
                    if (copy[r, c] != EMPTY) {
                        nonEmpty.add(copy[r, c])
                    }
                }
                // 从底部填充
                for (r in ROWS - 1 downTo 0) {
                    val idx = ROWS - 1 - r
                    copy[r, c] = if (idx < nonEmpty.size) nonEmpty[idx] else EMPTY
                }
            }
        }

        return SwapResult(
            score = totalScore,
            chainCount = chainCount,
            specialGenerated = specialGenerated,
            stepsGained = stepsGained,
            finalBoard = copy
        )
    }

    override fun toString(): String {
        return grid.joinToString("\n") { row ->
            row.joinToString(" ") { getColorName(it) }
        }
    }

    data class SwapResult(
        val score: Int,
        val chainCount: Int,
        val specialGenerated: Int,
        val stepsGained: Int,
        val finalBoard: BoardState
    )
}
