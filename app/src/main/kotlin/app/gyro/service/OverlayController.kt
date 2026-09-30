package app.gyro.service

import android.annotation.SuppressLint
import android.content.Context
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.MotionEvent
import android.view.WindowManager
import android.widget.LinearLayout
import android.widget.TextView
import app.gyro.data.RideSnapshot
import app.gyro.protocol.TiltbackPredictor
import kotlin.math.roundToInt

/**
 * Small draggable bubble with speed and battery that floats over maps or music apps.
 * Plain Views: a window added by a service has no Compose lifecycle owner.
 */
class OverlayController(private val context: Context) {

    private val windowManager = context.getSystemService(WindowManager::class.java)
    private var root: LinearLayout? = null
    private var speedView: TextView? = null
    private var infoView: TextView? = null
    private var background: GradientDrawable? = null
    private var params: WindowManager.LayoutParams? = null

    val isShowing: Boolean get() = root != null

    fun canShow(): Boolean = Settings.canDrawOverlays(context)

    @SuppressLint("ClickableViewAccessibility")
    fun show() {
        if (root != null || !canShow()) return
        val bg = GradientDrawable().apply {
            cornerRadius = dp(18f)
            setColor(0xE60B0F14.toInt())
            setStroke(dp(2f).roundToInt(), ACCENT)
        }
        val speed = TextView(context).apply {
            setTextColor(0xFFFFFFFF.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 34f)
            typeface = Typeface.DEFAULT_BOLD
            gravity = Gravity.CENTER
            text = "—"
        }
        val info = TextView(context).apply {
            setTextColor(0xFFB8C4D0.toInt())
            setTextSize(TypedValue.COMPLEX_UNIT_SP, 12f)
            gravity = Gravity.CENTER
        }
        val layout = LinearLayout(context).apply {
            orientation = LinearLayout.VERTICAL
            background = bg
            val pad = dp(10f).roundToInt()
            setPadding(pad + 4, pad, pad + 4, pad)
            addView(speed)
            addView(info)
        }
        val lp = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT,
        ).apply {
            gravity = Gravity.TOP or Gravity.START
            x = dp(16f).roundToInt()
            y = dp(160f).roundToInt()
        }
        var downX = 0f
        var downY = 0f
        var startX = 0
        var startY = 0
        layout.setOnTouchListener { v, e ->
            when (e.actionMasked) {
                MotionEvent.ACTION_DOWN -> {
                    downX = e.rawX; downY = e.rawY; startX = lp.x; startY = lp.y
                    true
                }
                MotionEvent.ACTION_MOVE -> {
                    lp.x = startX + (e.rawX - downX).roundToInt()
                    lp.y = startY + (e.rawY - downY).roundToInt()
                    runCatching { windowManager.updateViewLayout(v, lp) }
                    true
                }
                else -> false
            }
        }
        runCatching { windowManager.addView(layout, lp) }.onSuccess {
            root = layout
            speedView = speed
            infoView = info
            background = bg
            params = lp
        }
    }

    fun hide() {
        root?.let { runCatching { windowManager.removeView(it) } }
        root = null
        speedView = null
        infoView = null
        background = null
    }

    fun update(s: RideSnapshot) {
        val t = s.wheel?.telemetry
        speedView?.text = if (t != null && s.live) t.absSpeedKmh.roundToInt().toString() else "—"
        val battery = t?.batteryPercent?.roundToInt()?.let { "$it%" } ?: "—"
        val temp = t?.maxTemperatureC?.roundToInt()?.let { " · $it°" } ?: ""
        infoView?.text = "$battery$temp"
        val color = when (s.tiltback?.level) {
            TiltbackPredictor.Level.CRITICAL -> 0xFFFF5A5F.toInt()
            TiltbackPredictor.Level.WARNING -> 0xFFFFB547.toInt()
            else -> ACCENT
        }
        background?.setStroke(dp(if (color == ACCENT) 2f else 4f).roundToInt(), color)
    }

    private fun dp(v: Float): Float = TypedValue.applyDimension(TypedValue.COMPLEX_UNIT_DIP, v, context.resources.displayMetrics)

    private companion object {
        const val ACCENT = 0xFF00E0B8.toInt()
    }
}
