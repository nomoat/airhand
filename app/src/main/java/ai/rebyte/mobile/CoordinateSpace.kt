package ai.rebyte.mobile

import kotlin.math.roundToInt

object CoordinateSpace {
    fun toScreen(x: Int, y: Int, imageWidth: Int, imageHeight: Int, screenWidth: Int, screenHeight: Int): Pair<Int, Int> {
        require(imageWidth > 0 && imageHeight > 0 && screenWidth > 0 && screenHeight > 0) { "Observe the screen first." }
        require(x in 0 until imageWidth && y in 0 until imageHeight) { "Coordinates are outside the screenshot." }
        return (x.toDouble() * screenWidth / imageWidth).roundToInt().coerceAtMost(screenWidth - 1) to
            (y.toDouble() * screenHeight / imageHeight).roundToInt().coerceAtMost(screenHeight - 1)
    }
}
