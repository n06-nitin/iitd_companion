package com.xlonlabs.iitdcompanion

import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.detectHorizontalDragGestures
import androidx.compose.runtime.Stable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.input.pointer.util.VelocityTracker as ComposeVelocityTracker
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.abs
import kotlin.math.sign

/**
 * Interactive tab switching. [offset] is how far (px) the current page has been
 * dragged: negative moves it left to reveal the tab on the right. Pages read
 * [positionOf] in their graphics layer, so a drag never recomposes.
 *
 * The tabs form a ring, like the faces of the cube: past the last tab comes the
 * first again. A new drag can catch a turn that's still settling and carry on
 * from there, so quick repeated swipes chain round the ring.
 */
@Stable
class TabSwiper(private val scope: CoroutineScope, initial: Portal) {
    private val tabs = Portal.entries

    var current by mutableStateOf(initial)
        private set
    /** The tab being revealed, if any. */
    var target by mutableStateOf<Portal?>(null)
        private set
    var offset by mutableFloatStateOf(0f)
        private set
    var width = 1f

    /** Signed steps round the ring from [current] to [target]: ±1 for a swipe, up to ±2 for a tap. */
    private var step by mutableIntStateOf(0)
    private var raw = 0f // finger travel
    private var job: Job? = null
    private var generation = 0
    /** Where the running animation will land, if it's turning to another tab. */
    private var landing: Portal? = null

    val progress get() = (abs(offset) / width).coerceIn(0f, 1f)

    /** The tab that would be shown if the finger lifted now. Changes once per swipe, not per frame. */
    val visual by derivedStateOf { target?.takeIf { progress > 0.5f } ?: current }

    /** -1..1 horizontal position of [p] as a cube face, or null when off screen. */
    fun positionOf(p: Portal): Float? {
        val f = (offset / width).coerceIn(-1f, 1f)
        return when (p) {
            current -> f
            target -> f + if (f < 0f) 1f else -1f
            else -> null
        }
    }

    /**
     * Fractional tab index for the sliding dock pill. It can run past either end
     * (-1..size) while wrapping round; the dock draws the overflow on the other side.
     */
    fun indicator(): Float {
        val from = tabs.indexOf(current).toFloat()
        return if (target == null) from else from + progress * step
    }

    /** Stop a running turn where it is, keeping every face in place on screen. */
    fun interrupt() {
        if (job?.isActive != true) return
        job?.cancel()
        job = null
        generation++
        val to = landing
        landing = null
        if (to != null && to == target && offset != 0f) {
            // it was turning to `to`: make that the current face, re-expressing the offset from its side
            val from = current
            current = to
            offset -= sign(offset) * width
            target = from
            step = -step
        }
        raw = offset
    }

    fun dragBy(dx: Float) {
        interrupt()
        raw += dx
        if (raw == 0f) {
            target = null
            offset = 0f
            return
        }
        step = if (raw < 0f) 1 else -1
        target = tabs[(tabs.indexOf(current) + step).mod(tabs.size)]
        offset = raw.coerceIn(-width, width)
    }

    fun release(velocity: Float) {
        if (job?.isActive == true) return
        val t = target
        val flung = abs(velocity) > 700f && sign(velocity) == sign(offset)
        val flungBack = abs(velocity) > 700f && sign(velocity) == -sign(offset)
        val commit = t != null && !flungBack && (abs(offset) > width * 0.22f || flung)
        val end = if (commit) sign(offset) * width else 0f
        run(if (commit) t else null) {
            animate(offset, end, velocity,
                spring(dampingRatio = if (commit) 1f else 0.6f, stiffness = 380f), block = it)
        }
    }

    /** Tapped a tab: turn the short way round the ring towards it. */
    fun goTo(p: Portal) {
        finishNow()
        if (p == current) return
        val ahead = (tabs.indexOf(p) - tabs.indexOf(current)).mod(tabs.size)
        step = if (ahead <= tabs.size / 2) ahead else ahead - tabs.size
        target = p
        val end = if (step > 0) -width else width
        run(p) { animate(0f, end, 0f, tween(420, easing = FastOutSlowInEasing), block = it) }
    }

    /** Jump a running turn straight to where it was going. */
    private fun finishNow() {
        if (job?.isActive != true) return
        job?.cancel()
        job = null
        generation++
        settle(landing)
    }

    private fun run(to: Portal?, anim: suspend ((Float, Float) -> Unit) -> Unit) {
        val gen = ++generation
        landing = to
        job = scope.launch {
            anim { v, _ -> if (gen == generation) offset = v }
            if (gen == generation) settle(to)
        }
    }

    private fun settle(to: Portal?) {
        if (to != null) current = to
        offset = 0f
        raw = 0f
        target = null
        landing = null
        step = 0
    }
}

/** Swipe on Compose chrome (top bar, dock). */
fun Modifier.swipeTabs(swiper: TabSwiper) = pointerInput(swiper) {
    val tracker = ComposeVelocityTracker()
    detectHorizontalDragGestures(
        onDragStart = { tracker.resetTracking(); swiper.interrupt() },
        onDragEnd = { swiper.release(tracker.calculateVelocity().x) },
        onDragCancel = { swiper.release(0f) },
    ) { change, dx ->
        tracker.addPosition(change.uptimeMillis, change.position)
        change.consume()
        swiper.dragBy(dx)
    }
}

/**
 * Swipe over the pages. Sits on the container that holds every tab's WebView,
 * so a gesture survives faces coming and going mid-turn. It watches touches
 * before the page does (Initial pass) and only takes over a quick, mostly
 * horizontal single-finger swipe that [pageWants] doesn't claim; consuming the
 * change makes Compose send the WebView a cancel.
 */
fun Modifier.swipePages(
    swiper: TabSwiper,
    onDown: () -> Unit,
    pageWants: (scrollDir: Int) -> Boolean,
) = pointerInput(swiper) {
    val slop = viewConfiguration.touchSlop * 1.5f
    val longPress = viewConfiguration.longPressTimeoutMillis
    awaitEachGesture {
        val down = awaitFirstDown(requireUnconsumed = false, pass = PointerEventPass.Initial)
        onDown() // the page's touchstart reports after this
        val tracker = ComposeVelocityTracker()
        tracker.addPosition(down.uptimeMillis, down.position)
        var travel = Offset.Zero
        var swiping = false
        while (true) {
            val event = awaitPointerEvent(PointerEventPass.Initial)
            if (!swiping && event.changes.size > 1) break // pinch-zoom belongs to the page
            val change = event.changes.firstOrNull { it.id == down.id } ?: break
            tracker.addPosition(change.uptimeMillis, change.position)
            if (!change.pressed) {
                if (swiping) {
                    change.consume()
                    swiper.release(tracker.calculateVelocity().x)
                }
                break
            }
            val delta = change.position - change.previousPosition
            if (swiping) {
                change.consume()
                swiper.dragBy(delta.x)
                continue
            }
            travel += delta
            if (abs(travel.y) > slop && abs(travel.y) > abs(travel.x)) break // vertical scroll
            if (abs(travel.x) > slop && abs(travel.x) > 1.3f * abs(travel.y)) {
                // a slow press-then-drag is text selection; leave it to the page
                val quick = change.uptimeMillis - down.uptimeMillis < longPress
                if (!quick || pageWants(if (travel.x < 0) 1 else -1)) break
                swiping = true
                change.consume()
                swiper.dragBy(0f) // catch a turn that's still settling
            }
        }
    }
}
