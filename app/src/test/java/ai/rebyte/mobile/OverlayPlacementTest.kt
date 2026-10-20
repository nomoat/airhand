package ai.rebyte.mobile

import ai.rebyte.mobile.OverlayPlacement.Box
import org.junit.Assert.*
import org.junit.Test

class OverlayPlacementTest {
    private val screen = Box(20, 100, 1060, 2320)
    private val top = Box(100, 100, 1000, 360)
    @Test fun targetUnderStopButtonMovesWholeControlWithoutResizingIt() {
        val tap = Box(850, 180, 910, 240)
        val moved = requireNotNull(OverlayPlacement.avoid(top, screen, tap))
        assertFalse(moved.intersects(tap))
        assertEquals(top.width, moved.width)
        assertEquals(top.height, moved.height)
        assertEquals(moved, OverlayPlacement.clamp(moved, screen))
    }
    @Test fun swipePathCannotPassThroughTheControl() {
        val path = Box(500, 140, 580, 1800)
        val moved = requireNotNull(OverlayPlacement.avoid(top, screen, path))
        assertFalse(moved.intersects(path))
        assertEquals(moved, OverlayPlacement.clamp(moved, screen))
    }
    @Test fun impossibleGestureIsRejectedInsteadOfHidingControls() {
        assertNull(OverlayPlacement.avoid(top, screen, screen))
    }
    @Test fun rotationClampsDraggedControlOnScreen() {
        val landscape = Box(100, 20, 2320, 1060)
        val dragged = Box(120, 2000, 1020, 2260)
        val moved = OverlayPlacement.clamp(dragged, landscape)
        assertEquals(1060, moved.bottom)
        assertEquals(900, moved.width)
        assertEquals(260, moved.height)
        assertEquals(top, OverlayPlacement.avoid(top, screen, Box(200, 1200, 500, 1600)))
    }
}
