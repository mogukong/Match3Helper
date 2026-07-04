package com.match3.helper.model

/**
 * 表示一次走法：交换两个相邻格子
 */
data class GameMove(
    val fromRow: Int,
    val fromCol: Int,
    val toRow: Int,
    val toCol: Int
) {
    val from: Pair<Int, Int> get() = Pair(fromRow, fromCol)
    val to: Pair<Int, Int> get() = Pair(toRow, toCol)

    /** 检查两个走法是否等价（交换方向不同但结果一样） */
    fun isEquivalent(other: GameMove): Boolean {
        return (fromRow == other.fromRow && fromCol == other.fromCol &&
                toRow == other.toRow && toCol == other.toCol) ||
                (fromRow == other.toRow && fromCol == other.toCol &&
                toRow == other.fromRow && toCol == other.fromCol)
    }

    override fun toString(): String {
        return "(${fromRow},${fromCol}) ↔ (${toRow},${toCol})"
    }
}
