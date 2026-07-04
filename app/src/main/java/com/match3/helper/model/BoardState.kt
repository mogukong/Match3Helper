package com.match3.helper.model

/**
 * 棋盘状态：7x7 的二维数组
 *
 * 棋子类型编码：
 *   0  = 空 (EMPTY)
 *   1  = 粉色 (PINK)
 *   2  = 绿色 (GREEN)
 *   3  = 黄色 (YELLOW)
 *   4  = 蓝色 (BLUE)
 *   5  = 红色 (RED)
 *   10 = 行火箭 (ROW_ROCKET) — 触发消除整行
 *   20 = 列火箭 (COL_ROCKET) — 触发消除整列
 *   30 = 炸弹 (BOMB) — 触发消除 3×3 范围
 *   40 = 彩虹球 (RAINBOW) — 与任意交换触发全屏/同色消除
 */
class BoardState(val grid: Array<IntArray>) {

    companion object {
        const val EMPTY = 0
        const val PINK = 1
        const val GREEN = 2
        const val YELLOW = 3
        const val BLUE = 4
        const val RED = 5

        // 特殊棋子
        const val ROW_ROCKET = 10    // 行火箭：触发消除整行
        const val COL_ROCKET = 20    // 列火箭：触发消除整列
        const val BOMB = 30          // 炸弹：触发消除 3×3
        const val RAINBOW = 40       // 彩虹球：与任意交换触发全屏/同色消除

        const val ROWS = 7
        const val COLS = 7

        fun isBaseGem(type: Int): Boolean = type in 1..5
        fun isSpecialGem(type: Int): Boolean = type in listOf(ROW_ROCKET, COL_ROCKET, BOMB, RAINBOW)
        fun isRocket(type: Int): Boolean = type == ROW_ROCKET || type == COL_ROCKET

        fun getColorName(type: Int): String = when (type) {
            PINK -> "粉"
            GREEN -> "绿"
            YELLOW -> "黄"
            BLUE -> "蓝"
            RED -> "红"
            ROW_ROCKET -> "行火箭"
            COL_ROCKET -> "列火箭"
            BOMB -> "炸弹"
            RAINBOW -> "彩虹球"
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

    operator fun get(row: Int, col: Int): Int = grid[row][col]
    operator fun set(row: Int, col: Int, value: Int) { grid[row][col] = value }

    fun isAdjacent(a: Pair<Int, Int>, b: Pair<Int, Int>): Boolean {
        val dr = kotlin.math.abs(a.first - b.first)
        val dc = kotlin.math.abs(a.second - b.second)
        return dr + dc == 1
    }

    fun canEliminateAfterSwap(pos1: Pair<Int, Int>, pos2: Pair<Int, Int>): Boolean {
        if (!isAdjacent(pos1, pos2)) return false
        val copy = this.copy()
        val temp = copy[pos1.first, pos1.second]
        copy[pos1.first, pos1.second] = copy[pos2.first, pos2.second]
        copy[pos2.first, pos2.second] = temp
        return copy.findEliminations().isNotEmpty()
    }

    // ==================== 核心消除逻辑 ====================

    /**
     * 消除组信息，包含形状和类型
     */
    data class ElimGroup(
        val cells: List<Pair<Int, Int>>,
        val colorType: Int,       // 基础颜色 1-5
        val direction: Direction, // HORIZONTAL, VERTICAL, L_SHAPE, T_SHAPE, CROSS
        val size: Int
    ) {
        enum class Direction { HORIZONTAL, VERTICAL, L_SHAPE, T_SHAPE, CROSS, LINE_4, LINE_5 }
    }

    /**
     * 找出所有消除组合，并识别形状
     */
    fun findEliminations(): List<ElimGroup> {
        val groups = mutableListOf<ElimGroup>()
        val visited = Array(ROWS) { BooleanArray(COLS) }

        // 扫描行
        for (r in 0 until ROWS) {
            var c = 0
            while (c < COLS) {
                if (grid[r][c] == EMPTY || !isBaseGem(grid[r][c])) { c++; continue }
                val type = grid[r][c]
                var end = c + 1
                while (end < COLS && grid[r][end] == type) end++
                val len = end - c
                if (len >= 3) {
                    val cells = (c until end).map { Pair(r, it) }
                    cells.forEach { visited[it.first][it.second] = true }
                    val dir = when (len) {
                        4 -> ElimGroup.Direction.LINE_4
                        5 -> ElimGroup.Direction.LINE_5
                        else -> ElimGroup.Direction.HORIZONTAL
                    }
                    groups.add(ElimGroup(cells, type, dir, len))
                }
                c = end
            }
        }

        // 扫描列
        for (c in 0 until COLS) {
            var r = 0
            while (r < ROWS) {
                if (grid[r][c] == EMPTY || !isBaseGem(grid[r][c])) { r++; continue }
                val type = grid[r][c]
                var end = r + 1
                while (end < ROWS && grid[end][c] == type) end++
                val len = end - r
                if (len >= 3) {
                    val cells = (r until end).map { Pair(it, c) }
                    cells.forEach { visited[it.first][it.second] = true }
                    val dir = when (len) {
                        4 -> ElimGroup.Direction.LINE_4
                        5 -> ElimGroup.Direction.LINE_5
                        else -> ElimGroup.Direction.VERTICAL
                    }
                    groups.add(ElimGroup(cells, type, dir, len))
                }
                r = end
            }
        }

        return groups
    }

    // ==================== 模拟一次交换 ====================

    /**
     * 模拟一次交换并执行消除（含特殊棋子、连锁、道具）
     */
    fun simulateSwapAndEliminate(pos1: Pair<Int, Int>, pos2: Pair<Int, Int>): SwapResult {
        val copy = this.copy()
        val t1 = copy[pos1.first, pos1.second]
        val t2 = copy[pos2.first, pos2.second]

        // 执行交换
        copy[pos1.first, pos1.second] = t2
        copy[pos2.first, pos2.second] = t1

        var totalScore = 0
        var chainCount = 0
        var rowRockets = 0
        var colRockets = 0
        var bombs = 0
        var rainbows = 0
        var stepsGained = 0
        var specialTriggers = 0       // 特殊棋子被触发次数
        var comboDestroyed = 0        // 组合消除（火箭+火箭等）

        // 先检查是否是特殊棋子交换（最高优先级）
        if (isSpecialGem(t1) || isSpecialGem(t2)) {
            val specialScore = copy.triggerSpecialSwap(pos1, pos2, t1, t2)
            totalScore += specialScore
            specialTriggers++
            chainCount++
        }

        // 连锁消除循环
        while (true) {
            val groups = copy.findEliminations()
            if (groups.isEmpty()) break

            chainCount++
            val allEliminated = mutableSetOf<Pair<Int, Int>>()

            // 处理每个消除组
            for (group in groups) {
                allEliminated.addAll(group.cells)

                // 计分
                val count = group.cells.size
                totalScore += when (count) {
                    3 -> 300
                    4 -> 600
                    else -> count * 150
                }

                // 4+ 消除 → 增加步数
                if (count >= 4) {
                    stepsGained++
                }

                // 根据形状生成特殊棋子（放在交换位置或组中心）
                when (group.direction) {
                    ElimGroup.Direction.LINE_4 -> {
                        // 4连：根据方向生成行/列火箭
                        val genPos = findGenerationPos(group, pos1, pos2)
                        if (group.direction == ElimGroup.Direction.HORIZONTAL ||
                            group.cells.all { it.first == group.cells[0].first }) {
                            // 水平4连 → 行火箭
                            copy[genPos.first, genPos.second] = ROW_ROCKET
                            rowRockets++
                        } else {
                            // 垂直4连 → 列火箭
                            copy[genPos.first, genPos.second] = COL_ROCKET
                            colRockets++
                        }
                    }
                    ElimGroup.Direction.LINE_5 -> {
                        // 5连直线 → 彩虹球
                        val genPos = findGenerationPos(group, pos1, pos2)
                        copy[genPos.first, genPos.second] = RAINBOW
                        rainbows++
                    }
                    else -> {
                        // 检查是否是 L/T 形（5个，需要行+列交叉）
                        // 简化：3连只计分，不生成特殊棋子
                        // 实际游戏中 L/T 形需要额外检测，这里简化
                    }
                }
            }

            // 消除所有匹配的棋子
            allEliminated.forEach { (r, c) ->
                // 如果被消除的是特殊棋子，触发其效果
                val gemType = copy[r, c]
                if (isSpecialGem(gemType) && !allEliminated.contains(Pair(r, c))) {
                    // 已在消除集中，效果已在上面处理
                }
                copy[r, c] = EMPTY
            }

            // 下落
            copy.applyGravity()
        }

        return SwapResult(
            score = totalScore,
            chainCount = chainCount,
            rowRockets = rowRockets,
            colRockets = colRockets,
            bombs = bombs,
            rainbows = rainbows,
            stepsGained = stepsGained,
            specialTriggers = specialTriggers,
            comboDestroyed = comboDestroyed,
            finalBoard = copy
        )
    }

    // ==================== 特殊棋子交换触发 ====================

    /**
     * 触发两个特殊棋子的交换效果
     * 返回：这次特殊交换的得分
     */
    private fun triggerSpecialSwap(pos1: Pair<Int, Int>, pos2: Pair<Int, Int>, t1: Int, t2: Int): Int {
        var score = 0

        // 组合效果矩阵
        when {
            // 彩虹球 + 彩虹球 = 全屏消除
            t1 == RAINBOW && t2 == RAINBOW -> {
                for (r in 0 until ROWS) {
                    for (c in 0 until COLS) {
                        if (grid[r][c] != EMPTY) {
                            grid[r][c] = EMPTY
                            score += 200
                        }
                    }
                }
                score += 5000
            }
            // 彩虹球 + 基础颜色 = 消除所有该颜色
            t1 == RAINBOW && isBaseGem(t2) -> {
                val targetColor = t2
                for (r in 0 until ROWS) {
                    for (c in 0 until COLS) {
                        if (grid[r][c] == targetColor) {
                            grid[r][c] = EMPTY
                            score += 150
                        }
                    }
                }
                score += 2000
                // 彩虹球自身也消除
                grid[pos1.first, pos1.second] = EMPTY
                grid[pos2.first, pos2.second] = EMPTY
            }
            t2 == RAINBOW && isBaseGem(t1) -> {
                val targetColor = t1
                for (r in 0 until ROWS) {
                    for (c in 0 until COLS) {
                        if (grid[r][c] == targetColor) {
                            grid[r][c] = EMPTY
                            score += 150
                        }
                    }
                }
                score += 2000
                grid[pos1.first, pos1.second] = EMPTY
                grid[pos2.first, pos2.second] = EMPTY
            }
            // 火箭 + 火箭 = 十字消除（交换点为中心）
            isRocket(t1) && isRocket(t2) -> {
                val cr = pos1.first
                val cc = pos1.second
                // 消除整行整列
                for (c in 0 until COLS) {
                    if (grid[cr][c] != EMPTY) { grid[cr][c] = EMPTY; score += 100 }
                }
                for (r in 0 until ROWS) {
                    if (grid[r][cc] != EMPTY) { grid[r][cc] = EMPTY; score += 100 }
                }
                score += 1500
            }
            // 火箭 + 炸弹 = 3行3列消除
            (isRocket(t1) && t2 == BOMB) || (t1 == BOMB && isRocket(t2)) -> {
                val cr = pos1.first
                val cc = pos1.first
                for (r in (cr - 1).coerceAtLeast(0)..(cr + 1).coerceAtMost(ROWS - 1)) {
                    for (c in 0 until COLS) {
                        if (grid[r][c] != EMPTY) { grid[r][c] = EMPTY; score += 100 }
                    }
                }
                for (c in (cc - 1).coerceAtLeast(0)..(cc + 1).coerceAtMost(COLS - 1)) {
                    for (r in 0 until ROWS) {
                        if (grid[r][c] != EMPTY) { grid[r][c] = EMPTY; score += 100 }
                    }
                }
                score += 2500
            }
            // 炸弹 + 炸弹 = 大范围消除
            t1 == BOMB && t2 == BOMB -> {
                val cr = pos1.first
                val cc = pos1.second
                for (r in (cr - 2).coerceAtLeast(0)..(cr + 2).coerceAtMost(ROWS - 1)) {
                    for (c in (cc - 2).coerceAtLeast(0)..(cc + 2).coerceAtMost(COLS - 1)) {
                        if (grid[r][c] != EMPTY) { grid[r][c] = EMPTY; score += 100 }
                    }
                }
                score += 3000
            }
            // 彩虹球 + 火箭/炸弹 = 所有该颜色变成对应特殊棋子并触发
            t1 == RAINBOW && isRocket(t2) -> {
                // 随机选择一种颜色全部变成行火箭并触发
                // 简化：消除所有基础颜色棋子
                for (r in 0 until ROWS) {
                    for (c in 0 until COLS) {
                        if (isBaseGem(grid[r][c])) {
                            grid[r][c] = EMPTY
                            score += 150
                        }
                    }
                }
                score += 3000
            }
            t1 == RAINBOW && t2 == BOMB -> {
                for (r in 0 until ROWS) {
                    for (c in 0 until COLS) {
                        if (isBaseGem(grid[r][c])) {
                            grid[r][c] = EMPTY
                            score += 150
                        }
                    }
                }
                score += 3500
            }
            // 单个火箭触发
            isRocket(t1) -> {
                val cr = pos1.first
                val cc = pos1.second
                if (t1 == ROW_ROCKET) {
                    for (c in 0 until COLS) {
                        if (grid[cr][c] != EMPTY) { grid[cr][c] = EMPTY; score += 80 }
                    }
                } else {
                    for (r in 0 until ROWS) {
                        if (grid[r][cc] != EMPTY) { grid[r][cc] = EMPTY; score += 80 }
                    }
                }
                score += 500
            }
            // 单个炸弹触发
            t1 == BOMB -> {
                val cr = pos1.first
                val cc = pos1.second
                for (r in (cr - 1).coerceAtLeast(0)..(cr + 1).coerceAtMost(ROWS - 1)) {
                    for (c in (cc - 1).coerceAtLeast(0)..(cc + 1).coerceAtMost(COLS - 1)) {
                        if (grid[r][c] != EMPTY) { grid[r][c] = EMPTY; score += 80 }
                    }
                }
                score += 800
            }
            isRocket(t2) -> {
                val cr = pos2.first
                val cc = pos2.second
                if (t2 == ROW_ROCKET) {
                    for (c in 0 until COLS) {
                        if (grid[cr][c] != EMPTY) { grid[cr][c] = EMPTY; score += 80 }
                    }
                } else {
                    for (r in 0 until ROWS) {
                        if (grid[r][cc] != EMPTY) { grid[r][cc] = EMPTY; score += 80 }
                    }
                }
                score += 500
            }
            t2 == BOMB -> {
                val cr = pos2.first
                val cc = pos2.second
                for (r in (cr - 1).coerceAtLeast(0)..(cr + 1).coerceAtMost(ROWS - 1)) {
                    for (c in (cc - 1).coerceAtLeast(0)..(cc + 1).coerceAtMost(COLS - 1)) {
                        if (grid[r][c] != EMPTY) { grid[r][c] = EMPTY; score += 80 }
                    }
                }
                score += 800
            }
        }

        return score
    }

    // ==================== 道具模拟 ====================

    /**
     * 模拟使用"刷新"道具：随机重排所有非空棋子
     * 返回：刷新后的棋盘（随机）
     */
    fun simulateRefresh(): BoardState {
        val copy = this.copy()
        val gems = mutableListOf<Int>()
        val positions = mutableListOf<Pair<Int, Int>>()

        for (r in 0 until ROWS) {
            for (c in 0 until COLS) {
                if (copy[r, c] != EMPTY) {
                    gems.add(copy[r, c])
                    positions.add(Pair(r, c))
                }
            }
        }

        gems.shuffle()
        positions.forEachIndexed { i, (r, c) ->
            copy[r, c] = gems[i]
        }

        return copy
    }

    /**
     * 模拟使用"锤击"道具：敲掉指定位置的棋子
     * 返回：敲掉后的棋盘 + 连锁得分
     */
    fun simulateHammer(row: Int, col: Int): HammerResult {
        val copy = this.copy()
        var score = 0
        var chainCount = 0

        // 敲掉指定棋子
        if (copy[row, col] != EMPTY) {
            // 如果敲掉的是特殊棋子，触发效果
            if (isSpecialGem(copy[row, col])) {
                score += triggerSpecialAt(copy, row, col, copy[row, col])
            } else {
                copy[row, col] = EMPTY
                score += 50
            }
        }

        // 下落并检查连锁
        copy.applyGravity()

        while (true) {
            val groups = copy.findEliminations()
            if (groups.isEmpty()) break
            chainCount++
            for (group in groups) {
                score += group.cells.size * 100
            }
            val allCells = groups.flatMap { it.cells }.toSet()
            allCells.forEach { (r, c) -> copy[r, c] = EMPTY }
            copy.applyGravity()
        }

        return HammerResult(copy, score, chainCount)
    }

    private fun triggerSpecialAt(board: BoardState, row: Int, col: Int, type: Int): Int {
        var score = 0
        when (type) {
            ROW_ROCKET -> {
                for (c in 0 until COLS) {
                    if (board[row, c] != EMPTY) { board[row, c] = EMPTY; score += 80 }
                }
                score += 500
            }
            COL_ROCKET -> {
                for (r in 0 until ROWS) {
                    if (board[r, col] != EMPTY) { board[r, col] = EMPTY; score += 80 }
                }
                score += 500
            }
            BOMB -> {
                for (r in (row - 1).coerceAtLeast(0)..(row + 1).coerceAtMost(ROWS - 1)) {
                    for (c in (col - 1).coerceAtLeast(0)..(col + 1).coerceAtMost(COLS - 1)) {
                        if (board[r, c] != EMPTY) { board[r, c] = EMPTY; score += 80 }
                    }
                }
                score += 800
            }
            RAINBOW -> {
                // 敲掉彩虹球：消除所有颜色最多的棋子
                val colorCounts = IntArray(6)
                for (r in 0 until ROWS) {
                    for (c in 0 until COLS) {
                        if (isBaseGem(board[r, c])) {
                            colorCounts[board[r, c]]++
                        }
                    }
                }
                val maxColor = colorCounts.indices.maxByOrNull { colorCounts[it] } ?: 1
                for (r in 0 until ROWS) {
                    for (c in 0 until COLS) {
                        if (board[r, c] == maxColor) {
                            board[r, c] = EMPTY
                            score += 150
                        }
                    }
                }
                score += 2000
            }
        }
        return score
    }

    // ==================== 工具方法 ====================

    private fun findGenerationPos(group: ElimGroup, swapPos1: Pair<Int, Int>, swapPos2: Pair<Int, Int>): Pair<Int, Int> {
        // 优先放在交换位置，否则放组中心
        val swap1InGroup = group.cells.contains(swapPos1)
        val swap2InGroup = group.cells.contains(swapPos2)
        return when {
            swap1InGroup -> swapPos1
            swap2InGroup -> swapPos2
            else -> group.cells[group.cells.size / 2]
        }
    }

    /** 重力下落 */
    fun applyGravity() {
        for (c in 0 until COLS) {
            val nonEmpty = mutableListOf<Int>()
            for (r in ROWS - 1 downTo 0) {
                if (grid[r][c] != EMPTY) {
                    nonEmpty.add(grid[r][c])
                }
            }
            for (r in ROWS - 1 downTo 0) {
                val idx = ROWS - 1 - r
                grid[r][c] = if (idx < nonEmpty.size) nonEmpty[idx] else EMPTY
            }
        }
    }

    override fun toString(): String {
        return grid.joinToString("\n") { row ->
            row.joinToString(" ") { getColorName(it) }
        }
    }

    // ==================== 数据类 ====================

    data class SwapResult(
        val score: Int,
        val chainCount: Int,
        val rowRockets: Int,        // 生成行火箭数
        val colRockets: Int,        // 生成列火箭数
        val bombs: Int,             // 生成炸弹数
        val rainbows: Int,          // 生成彩虹球数
        val stepsGained: Int,       // 增加步数
        val specialTriggers: Int,   // 特殊棋子触发次数
        val comboDestroyed: Int,    // 组合消除次数
        val finalBoard: BoardState
    )

    data class HammerResult(
        val board: BoardState,
        val score: Int,
        val chainCount: Int
    )
}
