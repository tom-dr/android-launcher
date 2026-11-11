package com.tom.magify.launcher

import android.content.Context
import android.util.AttributeSet
import android.view.GestureDetector
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout
import kotlin.math.abs

/** Lets the home page recognize flings across all content while preserving taps. */
class GestureLinearLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {
    enum class Direction { UP, DOWN, RIGHT }

    /** Returns true when the parent handled the fling; false lets the child scroll normally. */
    var onDirectionalFling: ((Direction) -> Boolean)? = null

    /** Called on every ACTION_DOWN, before any child sees the gesture. */
    var onGestureStart: (() -> Unit)? = null

    /**
     * Whether a sideways drag may be taken away from the view it started on
     * (false while e.g. the search field is in use, so text selection works).
     */
    var canClaimHorizontalSwipe: (() -> Boolean)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var downX = 0f
    private var downY = 0f
    // Once the children got an ACTION_CANCEL, the rest of the gesture is ours.
    private var childrenCancelled = false

    private var detectedDirection: Direction? = null
    private val detector = GestureDetector(context, object : GestureDetector.SimpleOnGestureListener() {
        override fun onDown(event: MotionEvent) = true

        override fun onFling(
            first: MotionEvent?,
            last: MotionEvent,
            velocityX: Float,
            velocityY: Float
        ): Boolean {
            val start = first ?: return false
            val vertical = abs(last.y - start.y)
            val horizontal = abs(last.x - start.x)
            if (vertical > horizontal * 2f) {
                if (start.y - last.y > 80 && -velocityY > 800) {
                    detectedDirection = Direction.UP
                    return true
                }
                if (last.y - start.y > 80 && velocityY > 800) {
                    detectedDirection = Direction.DOWN
                    return true
                }
            } else if (last.x - start.x > 80 && velocityX > 800) {
                detectedDirection = Direction.RIGHT
                return true
            }
            return false
        }
    })

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                onGestureStart?.invoke()
                downX = event.x
                downY = event.y
                childrenCancelled = false
            }
            MotionEvent.ACTION_MOVE -> if (!childrenCancelled) {
                // Nothing on the home page scrolls sideways: a horizontal
                // drag is a page swipe, never a press on what's under the
                // thumb — otherwise the quota card's or an icon's long-press
                // menu pops up mid-swipe.
                val dx = abs(event.x - downX)
                val dy = abs(event.y - downY)
                if (dx > touchSlop && dx > dy * 2f && canClaimHorizontalSwipe?.invoke() != false) {
                    cancelChildren(event)
                }
            }
        }
        detector.onTouchEvent(event)
        val direction = detectedDirection
        if (direction != null) {
            detectedDirection = null
            if (onDirectionalFling?.invoke(direction) == true) {
                // The UP is kept from the children: without a CANCEL they'd
                // still think the finger is down and fire a long-press later.
                if (!childrenCancelled) cancelChildren(event)
                return true
            }
        }
        if (childrenCancelled) return true
        return super.dispatchTouchEvent(event)
    }

    private fun cancelChildren(event: MotionEvent) {
        childrenCancelled = true
        val cancel = MotionEvent.obtain(event).apply { action = MotionEvent.ACTION_CANCEL }
        super.dispatchTouchEvent(cancel)
        cancel.recycle()
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean = false
}
