package com.tom.magify.launcher

import android.content.Context
import android.util.AttributeSet
import android.view.MotionEvent
import android.view.ViewConfiguration
import android.widget.LinearLayout

/** Intercepts downward drags from any child and hands their distance to the sheet. */
class DraggableSheetLayout @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null,
    defStyleAttr: Int = 0
) : LinearLayout(context, attrs, defStyleAttr) {
    var onDownwardDragStart: ((Float) -> Unit)? = null
    var onDownwardDrag: ((Float) -> Unit)? = null
    var onDownwardDragEnd: ((Float, Boolean) -> Unit)? = null

    private val touchSlop = ViewConfiguration.get(context).scaledTouchSlop
    private var startX = 0f
    private var startY = 0f
    private var dragging = false

    // The grid asks its ancestors not to intercept while it handles a touch.
    // Keep interception available so a downward drag can start on any part
    // of the sheet, while upward gestures still remain with the RecyclerView.
    override fun requestDisallowInterceptTouchEvent(disallowIntercept: Boolean) {
        super.requestDisallowInterceptTouchEvent(false)
    }

    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val distanceDown = event.rawY - startY
                val distanceSideways = kotlin.math.abs(event.rawX - startX)
                if (!dragging && distanceDown > touchSlop && distanceDown > distanceSideways * 1.2f) {
                    val cancel = MotionEvent.obtain(event)
                    cancel.action = MotionEvent.ACTION_CANCEL
                    super.dispatchTouchEvent(cancel)
                    cancel.recycle()
                    dragging = true
                    onDownwardDragStart?.invoke(startY)
                    onDownwardDrag?.invoke(event.rawY)
                    return true
                }
            }
            MotionEvent.ACTION_UP -> {
                if (dragging) {
                    onDownwardDragEnd?.invoke(event.rawY - startY, false)
                    dragging = false
                    return true
                }
            }
            MotionEvent.ACTION_CANCEL -> {
                if (dragging) {
                    onDownwardDragEnd?.invoke(0f, true)
                    dragging = false
                    return true
                }
            }
        }
        if (dragging) {
            if (event.actionMasked == MotionEvent.ACTION_MOVE) {
                onDownwardDrag?.invoke(event.rawY)
            }
            return true
        }
        return super.dispatchTouchEvent(event)
    }

    override fun onInterceptTouchEvent(event: MotionEvent): Boolean {
        when (event.actionMasked) {
            MotionEvent.ACTION_DOWN -> {
                startX = event.rawX
                startY = event.rawY
                dragging = false
            }
            MotionEvent.ACTION_MOVE -> {
                val distanceDown = event.rawY - startY
                val distanceSideways = kotlin.math.abs(event.rawX - startX)
                if (!dragging && distanceDown > touchSlop && distanceDown > distanceSideways * 1.2f) {
                    dragging = true
                    onDownwardDragStart?.invoke(startY)
                    onDownwardDrag?.invoke(event.rawY)
                    return true
                }
            }
        }
        return false
    }

    override fun onTouchEvent(event: MotionEvent): Boolean {
        if (!dragging) return true
        when (event.actionMasked) {
            MotionEvent.ACTION_MOVE -> onDownwardDrag?.invoke(event.rawY)
            MotionEvent.ACTION_UP -> {
                onDownwardDragEnd?.invoke(event.rawY - startY, false)
                dragging = false
            }
            MotionEvent.ACTION_CANCEL -> {
                onDownwardDragEnd?.invoke(0f, true)
                dragging = false
            }
        }
        return true
    }
}
