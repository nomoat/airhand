package ai.rebyte.mobile

import kotlin.math.abs

/** Screen-pixel geometry, independent of Android so gesture/overlay collisions can be tested. */
internal object OverlayPlacement {
    data class Box(val left: Int, val top: Int, val right: Int, val bottom: Int) {
        val width get() = right - left
        val height get() = bottom - top
        fun intersects(other: Box) = left < other.right && right > other.left && top < other.bottom && bottom > other.top
    }
    fun clamp(box: Box, area: Box): Box {
        val w = minOf(box.width, area.width)
        val h = minOf(box.height, area.height)
        val x = box.left.coerceIn(area.left, area.right - w)
        val y = box.top.coerceIn(area.top, area.bottom - h)
        return Box(x, y, x + w, y + h)
    }
    fun avoid(current: Box, area: Box, target: Box): Box? {
        val fitted = clamp(current, area)
        if (!fitted.intersects(target)) return fitted
        // Try each side of the target, then the four corners. Never shrink the Stop control.
        val xs = listOf(fitted.left, area.left, area.right - fitted.width, target.left - fitted.width, target.right)
        val ys = listOf(fitted.top, area.top, area.bottom - fitted.height, target.top - fitted.height, target.bottom)
        return xs.flatMap { x -> ys.map { y -> clamp(Box(x, y, x + fitted.width, y + fitted.height), area) } }
            .distinct().filterNot { it.intersects(target) }
            .minByOrNull { abs(it.left - fitted.left) + abs(it.top - fitted.top) }
    }
}
