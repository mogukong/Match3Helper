package com.match3.helper.ai

import com.match3.helper.model.BoardState
import com.match3.helper.model.GameMove

/**
 * 消消乐 AI 决策引擎
 * 遍历所有可能的相邻交换，评估得分，返回最优走法
 */
class GameAI {

    companion object {
        /** 权重配置 */
        const val SCORE_WEIGHT = 1.0        // 基础得分权重
        const val CHAIN_WEIGHT = 300.0      // 连锁消除额外奖励
        const val SPECIAL_WEIGHT = 500.0    // 生成特殊棋子奖励
        const val STEP_WEIGHT = 400.0       // 增加步数奖励
        const val FOUR_PLUS_BONUS = 200.0   // 4+消除额外奖励
    }

    /**
     * 评估一次走法的综合得分
     */
    private fun evaluateMove(result: BoardState.SwapResult): Double {
        var score = result.score * SCORE_WEIGHT

        // 连锁奖励
        if (result.chainCount > 1) {
            score += result.chainCount * CHAIN_WEIGHT
        }

        // 特殊棋子奖励
        score += result.specialGenerated * SPECIAL_WEIGHT

        // 步数奖励（非常珍贵）
        score += result.stepsGained * STEP_WEIGHT

        // 4+消除总数奖励
        if (result.specialGenerated > 0) {
            score += result.specialGenerated * FOUR_PLUS_BONUS
        }

        return score
    }

    /**
     * 为当前棋盘状态找出所有合法走法，并按得分排序
     */
    fun findAllValidMoves(board: BoardState): List<ScoredMove> {
        val moves = mutableListOf<ScoredMove>()

        // 遍历所有相邻交换
        // 水平交换
        for (r in 0 until BoardState.ROWS) {
            for (c in 0 until BoardState.COLS - 1) {
                val pos1 = Pair(r, c)
                val pos2 = Pair(r, c + 1)
                if (board.canEliminateAfterSwap(pos1, pos2)) {
                    val result = board.simulateSwapAndEliminate(pos1, pos2)
                    val score = evaluateMove(result)
                    moves.add(ScoredMove(
                        move = GameMove(r, c, r, c + 1),
                        score = score,
                        result = result
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
                    val score = evaluateMove(result)
                    moves.add(ScoredMove(
                        move = GameMove(r, c, r + 1, c),
                        score = score,
                        result = result
                    ))
                }
            }
        }

        // 按得分降序排列
        return moves.sortedByDescending { it.score }
    }

    /**
     * 找出最优走法
     */
    fun findBestMove(board: BoardState): ScoredMove? {
        val moves = findAllValidMoves(board)
        return moves.firstOrNull()
    }

    /**
     * 获取前N个最优走法
     */
    fun findTopMoves(board: BoardState, n: Int): List<ScoredMove> {
        return findAllValidMoves(board).take(n)
    }

    data class ScoredMove(
        val move: GameMove,
        val score: Double,
        val result: BoardState.SwapResult
    ) {
        override fun toString(): String {
            return "Move $move | Score: ${score.toInt()} | Chain: ${result.chainCount}x | " +
                    "Special: ${result.specialGenerated} | Steps+: ${result.stepsGained}"
        }
    }
}
