package com.match3.helper.ai

import com.match3.helper.model.BoardState
import com.match3.helper.model.GameMove

/**
 * 消消乐 AI 决策引擎 V2
 * 支持特殊棋子（火箭/炸弹/彩虹球）和道具（刷新/锤击）的完整决策
 *
 * 优先级体系：
 *  1. 特殊棋子组合触发（最高优先级）
 *  2. 生成彩虹球（5连）
 *  3. 生成炸弹（L/T 5连）
 *  4. 生成火箭（4连）
 *  5. 连锁消除 + 步数奖励
 *  6. 道具使用（无好走法时）
 */
class GameAI {

    companion object {
        // 特殊棋子组合触发得分（最高优先级）
        const val RAINBOW_RAINBOW = 10000.0   // 彩虹球+彩虹球 = 全屏
        const val RAINBOW_COLOR = 8000.0      // 彩虹球+颜色 = 全色消除
        const val ROCKET_ROCKET = 5000.0      // 火箭+火箭 = 十字
        const val ROCKET_BOMB = 4000.0        // 火箭+炸弹 = 3行3列
        const val BOMB_BOMB = 3500.0          // 炸弹+炸弹 = 大范围
        const val RAINBOW_ROCKET = 3000.0     // 彩虹球+火箭 = 全色变火箭
        const val RAINBOW_BOMB = 3000.0       // 彩虹球+炸弹 = 全色变炸弹
        const val SINGLE_ROCKET = 800.0       // 单个火箭触发
        const val SINGLE_BOMB = 1000.0        // 单个炸弹触发

        // 特殊棋子生成得分
        const val GEN_RAINBOW = 2500.0      // 生成彩虹球
        const val GEN_BOMB = 1800.0          // 生成炸弹
        const val GEN_ROCKET = 1200.0        // 生成火箭

        // 基础得分
        const val BASE_SCORE_WEIGHT = 1.0
        const val CHAIN_WEIGHT = 500.0       // 连锁奖励
        const val STEP_WEIGHT = 800.0         // 步数奖励（更珍贵了）

        // 道具评估
        const val REFRESH_MIN_SCORE = 200.0  // 普通走法低于此分时考虑刷新
        const val HAMMER_BONUS = 600.0       // 锤击能触发连锁的额外奖励
    }

    // ==================== 主决策入口 ====================

    /**
     * 综合决策：普通交换、特殊棋子组合、道具使用
     * 返回：最优行动建议
     */
    fun findBestAction(board: BoardState): ActionSuggestion? {
        // 1. 先检查是否有特殊棋子组合（最高优先级）
        val specialCombo = findSpecialComboMoves(board)
        if (specialCombo.isNotEmpty()) {
            return ActionSuggestion(
                type = ActionType.SWAP,
                move = specialCombo.first().move,
                score = specialCombo.first().score,
                reason = specialCombo.first().reason
            )
        }

        // 2. 评估所有普通交换
        val normalMoves = findAllValidMoves(board)
        if (normalMoves.isNotEmpty()) {
            val best = normalMoves.first()
            return ActionSuggestion(
                type = ActionType.SWAP,
                move = best.move,
                score = best.score,
                reason = best.reason
            )
        }

        // 3. 没有有效走法，考虑道具
        // 评估刷新
        val refreshScore = evaluateRefresh(board)
        if (refreshScore > 0) {
            return ActionSuggestion(
                type = ActionType.REFRESH,
                move = null,
                score = refreshScore,
                reason = "无有效走法，建议使用刷新道具"
            )
        }

        // 评估锤击（每个空格子的价值）
        val hammer = evaluateHammer(board)
        if (hammer != null) {
            return ActionSuggestion(
                type = ActionType.HAMMER,
                move = null,
                score = hammer.score,
                reason = "建议锤击位置 (${hammer.row},${hammer.col})"
            )
        }

        return null
    }

    // ==================== 特殊棋子组合检测 ====================

    /**
     * 检测棋盘上是否有特殊棋子可以组合触发
     * 返回：按优先级排序的组合走法
     */
    private fun findSpecialComboMoves(board: BoardState): List<ScoredCombo> {
        val combos = mutableListOf<ScoredCombo>()

        for (r in 0 until BoardState.ROWS) {
            for (c in 0 until BoardState.COLS) {
                val type = board[r, c]
                if (!BoardState.isSpecialGem(type)) continue

                // 检查四个相邻方向
                val neighbors = listOf(
                    Pair(r - 1, c), Pair(r + 1, c),
                    Pair(r, c - 1), Pair(r, c + 1)
                )

                for (nr in neighbors) {
                    if (nr.first !in 0 until BoardState.ROWS || nr.second !in 0 until BoardState.COLS) continue
                    val neighborType = board[nr.first, nr.second]

                    // 彩虹球 + 彩虹球
                    if (type == BoardState.RAINBOW && neighborType == BoardState.RAINBOW) {
                        combos.add(ScoredCombo(
                            GameMove(r, c, nr.first, nr.second),
                            RAINBOW_RAINBOW,
                            "🌈+🌈 全屏消除！"
                        ))
                    }
                    // 彩虹球 + 基础颜色
                    else if (type == BoardState.RAINBOW && BoardState.isBaseGem(neighborType)) {
                        combos.add(ScoredCombo(
                            GameMove(r, c, nr.first, nr.second),
                            RAINBOW_COLOR,
                            "🌈+${BoardState.getColorName(neighborType)} 全色消除"
                        ))
                    }
                    else if (neighborType == BoardState.RAINBOW && BoardState.isBaseGem(type)) {
                        combos.add(ScoredCombo(
                            GameMove(r, c, nr.first, nr.second),
                            RAINBOW_COLOR,
                            "🌈+${BoardState.getColorName(type)} 全色消除"
                        ))
                    }
                    // 火箭 + 火箭
                    else if (BoardState.isRocket(type) && BoardState.isRocket(neighborType)) {
                        combos.add(ScoredCombo(
                            GameMove(r, c, nr.first, nr.second),
                            ROCKET_ROCKET,
                            "🚀+🚀 十字消除"
                        ))
                    }
                    // 火箭 + 炸弹
                    else if (BoardState.isRocket(type) && neighborType == BoardState.BOMB) {
                        combos.add(ScoredCombo(
                            GameMove(r, c, nr.first, nr.second),
                            ROCKET_BOMB,
                            "🚀+💣 3行3列大消除"
                        ))
                    }
                    else if (type == BoardState.BOMB && BoardState.isRocket(neighborType)) {
                        combos.add(ScoredCombo(
                            GameMove(r, c, nr.first, nr.second),
                            ROCKET_BOMB,
                            "💣+🚀 3行3列大消除"
                        ))
                    }
                    // 炸弹 + 炸弹
                    else if (type == BoardState.BOMB && neighborType == BoardState.BOMB) {
                        combos.add(ScoredCombo(
                            GameMove(r, c, nr.first, nr.second),
                            BOMB_BOMB,
                            "💣+💣 大范围爆炸"
                        ))
                    }
                    // 彩虹球 + 火箭/炸弹（单向）
                    else if (type == BoardState.RAINBOW && BoardState.isRocket(neighborType)) {
                        combos.add(ScoredCombo(
                            GameMove(r, c, nr.first, nr.second),
                            RAINBOW_ROCKET,
                            "🌈+🚀 全色变火箭"
                        ))
                    }
                    else if (type == BoardState.RAINBOW && neighborType == BoardState.BOMB) {
                        combos.add(ScoredCombo(
                            GameMove(r, c, nr.first, nr.second),
                            RAINBOW_BOMB,
                            "🌈+💣 全色变炸弹"
                        ))
                    }
                    // 单个特殊棋子触发（与基础颜色交换）
                    else if (BoardState.isRocket(type) && BoardState.isBaseGem(neighborType)) {
                        combos.add(ScoredCombo(
                            GameMove(r, c, nr.first, nr.second),
                            SINGLE_ROCKET,
                            "🚀触发"
                        ))
                    }
                    else if (type == BoardState.BOMB && BoardState.isBaseGem(neighborType)) {
                        combos.add(ScoredCombo(
                            GameMove(r, c, nr.first, nr.second),
                            SINGLE_BOMB,
                            "💣触发"
                        ))
                    }
                }
            }
        }

        return combos.sortedByDescending { it.score }
    }

    // ==================== 普通交换评估 ====================

    /**
     * 评估所有普通交换走法
     */
    fun findAllValidMoves(board: BoardState): List<ScoredMove> {
        val moves = mutableListOf<ScoredMove>()

        // 水平交换
        for (r in 0 until BoardState.ROWS) {
            for (c in 0 until BoardState.COLS - 1) {
                val pos1 = Pair(r, c)
                val pos2 = Pair(r, c + 1)
                if (board.canEliminateAfterSwap(pos1, pos2)) {
                    val result = board.simulateSwapAndEliminate(pos1, pos2)
                    val score = evaluateSwapResult(result)
                    val reason = buildReason(result)
                    moves.add(ScoredMove(
                        move = GameMove(r, c, r, c + 1),
                        score = score,
                        result = result,
                        reason = reason
                    ))
                }
            }
        }

        // 垂直交换
        for (r in 0 until BoardState.ROWS - 1) {
            for (c in 0 until BoardState.COLS) {
                val pos1 = Pair(r, c)
                val pos2 = Pair(r + 1, c)
                if (board.canEliminateAfterSwap(pos1, pos2)) {
                    val result = board.simulateSwapAndEliminate(pos1, pos2)
                    val score = evaluateSwapResult(result)
                    val reason = buildReason(result)
                    moves.add(ScoredMove(
                        move = GameMove(r, c, r + 1, c),
                        score = score,
                        result = result,
                        reason = reason
                    ))
                }
            }
        }

        return moves.sortedByDescending { it.score }
    }

    /**
     * 评估一次交换结果的综合得分
     */
    private fun evaluateSwapResult(result: BoardState.SwapResult): Double {
        var score = result.score * BASE_SCORE_WEIGHT

        // 连锁奖励
        if (result.chainCount > 1) {
            score += result.chainCount * CHAIN_WEIGHT
        }

        // 步数奖励（非常珍贵）
        score += result.stepsGained * STEP_WEIGHT

        // 生成特殊棋子奖励
        score += result.rainbows * GEN_RAINBOW
        score += result.bombs * GEN_BOMB
        score += result.rowRockets * GEN_ROCKET
        score += result.colRockets * GEN_ROCKET

        // 特殊棋子触发奖励
        score += result.specialTriggers * 500.0

        // 组合消除奖励
        score += result.comboDestroyed * 1000.0

        return score
    }

    /**
     * 生成走法原因说明
     */
    private fun buildReason(result: BoardState.SwapResult): String {
        val parts = mutableListOf<String>()
        if (result.rainbows > 0) parts.add("生成🌈x${result.rainbows}")
        if (result.bombs > 0) parts.add("生成💣x${result.bombs}")
        if (result.rowRockets > 0) parts.add("生成行🚀x${result.rowRockets}")
        if (result.colRockets > 0) parts.add("生成列🚀x${result.colRockets}")
        if (result.chainCount > 1) parts.add("${result.chainCount}连消")
        if (result.stepsGained > 0) parts.add("+${result.stepsGained}步")
        if (result.specialTriggers > 0) parts.add("特殊触发")

        return if (parts.isEmpty()) "基础消除" else parts.joinToString(" ")
    }

    // ==================== 道具评估 ====================

    /**
     * 评估是否值得使用刷新道具
     * 返回：>0 表示建议使用，值越大优先级越高
     */
    private fun evaluateRefresh(board: BoardState): Double {
        // 刷新后找最优走法
        val refreshed = board.simulateRefresh()
        val moves = findAllValidMoves(refreshed)
        val bestScore = moves.firstOrNull()?.score ?: 0.0

        // 如果刷新后的最优走法分值足够高，则建议使用
        return if (bestScore > REFRESH_MIN_SCORE) bestScore * 0.8 else 0.0
    }

    /**
     * 评估锤击道具的最佳使用位置
     */
    private fun evaluateHammer(board: BoardState): HammerSuggestion? {
        var bestScore = 0.0
        var bestRow = -1
        var bestCol = -1

        for (r in 0 until BoardState.ROWS) {
            for (c in 0 until BoardState.COLS) {
                if (board[r, c] == BoardState.EMPTY) continue

                val hammerResult = board.simulateHammer(r, c)
                var score = hammerResult.score.toDouble()

                // 如果敲掉的是特殊棋子，额外奖励
                if (BoardState.isSpecialGem(board[r, c])) {
                    score += 500.0
                }

                // 如果触发连锁，额外奖励
                if (hammerResult.chainCount > 1) {
                    score += hammerResult.chainCount * HAMMER_BONUS
                }

                if (score > bestScore) {
                    bestScore = score
                    bestRow = r
                    bestCol = c
                }
            }
        }

        return if (bestScore > 100) HammerSuggestion(bestRow, bestCol, bestScore) else null
    }

    // ==================== 数据类 ====================

    data class ScoredMove(
        val move: GameMove,
        val score: Double,
        val result: BoardState.SwapResult,
        val reason: String
    )

    data class ScoredCombo(
        val move: GameMove,
        val score: Double,
        val reason: String
    )

    data class ActionSuggestion(
        val type: ActionType,
        val move: GameMove?,
        val score: Double,
        val reason: String
    )

    enum class ActionType { SWAP, REFRESH, HAMMER }

    data class HammerSuggestion(
        val row: Int,
        val col: Int,
        val score: Double
    )
}
