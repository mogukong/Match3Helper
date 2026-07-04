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
 *   10 = 行火箭 (ROW_ROCKET) — 被移动时触发消除整行
 *   20 = 列火箭 (COL_ROCKET) — 被移动时触发消除整列
 *   30 = 炸弹 (BOMB) — 被移动时触发消除 3×3 范围
 *   40 = 彩虹球 (RAINBOW) — 与基础颜色交换时消除所有该颜色
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
        const val ROW_ROCKET = 10    // 行火箭
        const val COL_ROCKET = 20    // 列火箭
        const val BOMB = 30          // 炸弹
        const val RAINBOW = 40       // 彩虹球

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

    /**
     * 检查交换是否合法
     * 特殊棋子：移动即触发，总是合法
     * 普通棋子：交换后需形成 >=3 连消除
     */
    fun canEliminateAfterSwap(pos1: Pair<Int, Int>, pos2: Pair<Int, Int>): Boolean {
        if (!isAdjacent(pos1, pos2)) return false
        val t1 = this[pos1.first, pos1.second]
        val t2 = this[pos2.first, pos2.second]

        // 特殊棋子移动即触发，总是合法
        if (isSpecialGem(t1) || isSpecialGem(t2)) return true

        // 普通棋子检查交换后是否有消除
        val copy = this.copy()
        val temp = copy[pos1.first, pos1.second]
        copy[pos1.first, pos1.second] = copy[pos2.first, pos2.second]
        copy[pos2.first, pos2.second] = temp
        return copy.findEliminations().isNotEmpty()
    }

    // ==================== 消除检测 ====================

    data class ElimGroup(
        val cells: List<Pair<Int, Int>>,
        val colorType: Int,
        val direction: Direction,
        val size: Int
    ) {
        enum class Direction { HORIZONTAL, VERTICAL, L_SHAPE, T_SHAPE, CROSS, LINE_4, LINE_5 }
    }

    fun findEliminations(): List<ElimGroup> {
        val groups = mutableListOf<ElimGroup>()

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

    // ==================== 交换模拟 ====================

    /**
     * 模拟一次交换并执行消除
     *
     * 核心规则：特殊棋子移动即触发
     * - t1 被移动到 pos2 → t1 在 pos2 触发效果
     * - t2 被移动到 pos1 → t2 在 pos1 触发效果
     * - 两个都是特殊棋子 → 组合效果（覆盖单个效果）
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
        var specialTriggers = 0
        var comboDestroyed = 0
        var specialTriggered = false

        // === 阶段1：特殊棋子触发（移动即触发）===
        when {
            isSpecialGem(t1) && isSpecialGem(t2) -> {
                totalScore += copy.triggerSpecialCombo(pos1, pos2, t1, t2)
                specialTriggers++
                comboDestroyed++
                specialTriggered = true
            }
            isSpecialGem(t1) -> {
                // t1 被移动到了 pos2，在 pos2 触发
                totalScore += copy.triggerSingleSpecial(pos2, t1, t2)
                specialTriggers++
                specialTriggered = true
            }
            isSpecialGem(t2) -> {
                // t2 被移动到了 pos1，在 pos1 触发
                totalScore += copy.triggerSingleSpecial(pos1, t2, t1)
                specialTriggers++
                specialTriggered = true
            }
        }

        // === 阶段2：普通消除（仅当没有特殊棋子触发时）===
        if (!specialTriggered) {
            val groups = copy.findEliminations()
            if (groups.isNotEmpty()) {
                chainCount++
                val allEliminated = mutableSetOf<Pair<Int, Int>>()

                for (group in groups) {
                    allEliminated.addAll(group.cells)
                    val count = group.cells.size
                    totalScore += when (count) {
                        3 -> 300
                        4 -> 600
                        else -> count * 150
                    }
                    if (count >= 4) stepsGained++

                    when (group.direction) {
                        ElimGroup.Direction.LINE_4 -> {
                            val genPos = findGenerationPos(group, pos1, pos2)
                            if (group.cells.all { it.first == group.cells[0].first }) {
                                copy[genPos.first, genPos.second] = ROW_ROCKET
                                rowRockets++
                            } else {
                                copy[genPos.first, genPos.second] = COL_ROCKET
                                colRockets++
                            }
                        }
                        ElimGroup.Direction.LINE_5 -> {
                            val genPos = findGenerationPos(group, pos1, pos2)
                            copy[genPos.first, genPos.second] = RAINBOW
                            rainbows++
                        }
                        else -> { }
                    }
                }

                allEliminated.forEach { (r, c) -> copy[r, c] = EMPTY }
                copy.applyGravity()
            }
        }

        // === 阶段3：连锁消除 ===
        while (true) {
            val groups = copy.findEliminations()
            if (groups.isEmpty()) break

            chainCount++
            val allEliminated = mutableSetOf<Pair<Int, Int>>()

            for (group in groups) {
                allEliminated.addAll(group.cells)
                val count = group.cells.size
                totalScore += when (count) {
                    3 -> 300
                    4 -> 600
                    else -> count * 150
                }
                if (count >= 4) stepsGained++

                when (group.direction) {
                    ElimGroup.Direction.LINE_4 -> {
                        val genPos = findGenerationPos(group, pos1, pos2)
                        if (group.cells.all { it.first == group.cells[0].first }) {
                            copy[genPos.first, genPos.second] = ROW_ROCKET
                            rowRockets++
                        } else {
                            copy[genPos.first, genPos.second] = COL_ROCKET
                            colRockets++
                        }
                    }
                    ElimGroup.Direction.LINE_5 -> {
                        val genPos = findGenerationPos(group, pos1, pos2)
                        copy[genPos.first, genPos.second] = RAINBOW
                        rainbows++
                    }
                    else -> { }
                }
            }

            allEliminated.forEach { (r, c) -> copy[r, c] = EMPTY }
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

    // ==================== 特殊棋子触发 ====================

    /**
     * 单个特殊棋子被移动时触发的效果
     * @param pos 特殊棋子的新位置（交换后的位置）
     * @param specialType 特殊棋子类型
     * @param swappedType 被交换的另一个棋子类型
     */
    private fun triggerSingleSpecial(pos: Pair<Int, Int>, specialType: Int, swappedType: Int): Int {
        var score = 0
        val (r, c) = pos

        when (specialType) {
            ROW_ROCKET -> {
                for (cc in 0 until COLS) {
                    if (grid[r][cc] != EMPTY) {
                        grid[r][cc] = EMPTY
                        score += 100
                    }
                }
                score += 500
            }
            COL_ROCKET -> {
                for (rr in 0 until ROWS) {
                    if (grid[rr][c] != EMPTY) {
                        grid[rr][c] = EMPTY
                        score += 100
                    }
                }
                score += 500
            }
            BOMB -> {
                for (rr in (r - 1).coerceAtLeast(0)..(r + 1).coerceAtMost(ROWS - 1)) {
                    for (cc in (c - 1).coerceAtLeast(0)..(c + 1).coerceAtMost(COLS - 1)) {
                        if (grid[rr][cc] != EMPTY) {
                            grid[rr][cc] = EMPTY
                            score += 100
                        }
                    }
                }
                score += 800
            }
            RAINBOW -> {
                if (isBaseGem(swappedType)) {
                    for (rr in 0 until ROWS) {
                        for (cc in 0 until COLS) {
                            if (grid[rr][cc] == swappedType) {
                                grid[rr][cc] = EMPTY
                                score += 150
                            }
                        }
                    }
                    score += 2000
                }
                grid[r][c] = EMPTY
            }
        }

        return score
    }

    /**
     * 两个特殊棋子组合触发的效果
     */
    private fun triggerSpecialCombo(pos1: Pair<Int, Int>, pos2: Pair<Int, Int>, t1: Int, t2: Int): Int {
        var score = 0

        when {
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
            isRocket(t1) && isRocket(t2) -> {
                val cr = pos1.first
                val cc = pos1.second
                for (c in 0 until COLS) {
                    if (grid[cr][c] != EMPTY) { grid[cr][c] = EMPTY; score += 100 }
                }
                for (r in 0 until ROWS) {
                    if (grid[r][cc] != EMPTY) { grid[r][cc] = EMPTY; score += 100 }
                }
                score += 1500
            }
            (isRocket(t1) && t2 == BOMB) || (t1 == BOMB && isRocket(t2)) -> {
                val cr = pos1.first
                val cc = pos1.second
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
            t1 == RAINBOW && isRocket(t2) -> {
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
        }

        return score
    }

    // ==================== 道具 ====================

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

    fun simulateHammer(row: Int, col: Int): HammerResult {
        val copy = this.copy()
        var score = 0
        var chainCount = 0

        if (copy[row, col] != EMPTY) {
            if (isSpecialGem(copy[row, col])) {
                score += triggerSpecialAt(copy, row, col, copy[row, col])
            } else {
                copy[row, col] = EMPTY
                score += 50
            }
        }

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

    // ==================== 工具 ====================

    private fun findGenerationPos(group: ElimGroup, swapPos1: Pair<Int, Int>, swapPos2: Pair<Int, Int>): Pair<Int, Int> {
        val swap1InGroup = group.cells.contains(swapPos1)
        val swap2InGroup = group.cells.contains(swapPos2)
        return when {
            swap1InGroup -> swapPos1
            swap2InGroup -> swapPos2
            else -> group.cells[group.cells.size / 2]
        }
    }

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
        val rowRockets: Int,
        val colRockets: Int,
        val bombs: Int,
        val rainbows: Int,
        val stepsGained: Int,
        val specialTriggers: Int,
        val comboDestroyed: Int,
        val finalBoard: BoardState
    )

    data class HammerResult(
        val board: BoardState,
        val score: Int,
        val chainCount: Int
    )
}
