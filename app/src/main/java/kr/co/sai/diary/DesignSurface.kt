package kr.co.sai.diary

import android.content.Context
import android.view.View
import android.widget.FrameLayout

/** Figma's 2560 × 1380 canvas mapped to a 1024 × 552 vehicle viewport.
 * Native controls remain interactive; fit-center letterboxing preserves artwork geometry.
 */
class DesignSurface(context: Context) : FrameLayout(context) {
    private data class Slot(val x: Float, val y: Float, val w: Float, val h: Float)
    private val slots = mutableMapOf<View, Slot>()
    fun place(view: View, x: Number, y: Number, w: Number, h: Number) {
        slots[view] = Slot(x.toFloat(), y.toFloat(), w.toFloat(), h.toFloat())
        addView(view)
    }
    override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
        val w = MeasureSpec.getSize(widthMeasureSpec)
        val h = MeasureSpec.getSize(heightMeasureSpec)
        setMeasuredDimension(w, h)
        val scale = minOf(w / 1024f, h / 552f)
        slots.forEach { (view, slot) ->
            if (view is android.widget.TextView) {
                val baseSize = view.getTag(kr.co.sai.diary.R.id.design_text_size) as? Float
                if (baseSize != null) view.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX, baseSize * scale)
            }
            view.measure(MeasureSpec.makeMeasureSpec((slot.w * scale).toInt(), MeasureSpec.EXACTLY),
                MeasureSpec.makeMeasureSpec((slot.h * scale).toInt(), MeasureSpec.EXACTLY))
        }
    }
    override fun onLayout(changed: Boolean, l: Int, t: Int, r: Int, b: Int) {
        val scale = minOf(width / 1024f, height / 552f)
        val dx = (width - 1024 * scale) / 2f
        val dy = (height - 552 * scale) / 2f
        slots.forEach { (view, s) ->
            val x = (dx + s.x * scale).toInt(); val y = (dy + s.y * scale).toInt()
            view.layout(x, y, x + view.measuredWidth, y + view.measuredHeight)
        }
    }
}
