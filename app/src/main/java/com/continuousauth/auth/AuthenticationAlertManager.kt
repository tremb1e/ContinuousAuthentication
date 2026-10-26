package com.continuousauth.auth

import android.content.Context
import android.content.Intent
import android.content.res.Configuration
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.hardware.display.DisplayManager
import android.provider.Settings
import android.view.Gravity
import android.view.Display
import android.view.View
import android.view.WindowManager
import android.view.WindowInsets
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import com.continuousauth.ui.compose.MainComposeActivity
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/** A real Android application overlay; ordinary notifications are not substituted for it. */
@Singleton
class AuthenticationAlertManager @Inject constructor(@ApplicationContext private val context: Context) {
    private val windowContext by lazy {
        val display = requireNotNull(context.getSystemService(DisplayManager::class.java).getDisplay(Display.DEFAULT_DISPLAY))
        context.createDisplayContext(display)
            .createWindowContext(WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY, null)
    }
    private val windowManager by lazy { windowContext.getSystemService(WindowManager::class.java) }
    private var overlay: View? = null
    private var messageView: TextView? = null

    fun showFailure(appName: String): Boolean {
        if (!Settings.canDrawOverlays(context)) {
            dismiss()
            return false
        }
        val message = buildString {
            append("刚才的使用习惯与已了解的习惯有些不同。请确认是你本人在使用手机。")
            if (appName.isNotBlank()) append("\n当时使用：${appName.take(100)}")
            append("\n\n这不一定代表他人在使用，也不代表该应用有问题。")
        }
        if (overlay != null) {
            messageView?.text = message
            return true
        }
        val density = windowContext.resources.displayMetrics.density
        fun dp(value: Int) = (value * density).toInt()
        val dark = windowContext.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        val foreground = if (dark) Color.rgb(238, 242, 246) else Color.rgb(27, 35, 42)
        val secondary = if (dark) Color.rgb(211, 218, 225) else Color.rgb(57, 65, 73)
        val surface = if (dark) Color.rgb(30, 36, 43) else Color.rgb(250, 252, 255)
        val card = LinearLayout(windowContext).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(24), dp(22), dp(24), dp(16))
            background = GradientDrawable().apply {
                setColor(surface)
                cornerRadius = dp(24).toFloat()
            }
            elevation = dp(16).toFloat()
            importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_YES
        }
        card.addView(TextView(windowContext).apply {
            text = "持续认证提醒"
            textSize = 22f
            setTextColor(foreground)
            setTypeface(typeface, Typeface.BOLD)
        })
        val messageText = TextView(windowContext).apply {
            text = message
            textSize = 16f
            setTextColor(secondary)
            setPadding(0, dp(16), 0, dp(12))
        }
        card.addView(messageText)
        card.addView(Button(windowContext).apply {
            text = "查看认证记录"
            minHeight = dp(48)
            setTextColor(foreground)
            setOnClickListener {
                dismiss()
                runCatching {
                    context.startActivity(Intent(context, MainComposeActivity::class.java).apply {
                        addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        putExtra("open_authentication", true)
                    })
                }
            }
        })
        card.addView(Button(windowContext).apply {
            text = "知道了"
            minHeight = dp(48)
            setTextColor(foreground)
            setOnClickListener { dismiss() }
        })
        // Bound the whole card, including actions, so both buttons remain reachable with
        // large text, in landscape, and on smaller displays. Recalculate on each measure.
        val scroll = object : ScrollView(windowContext) {
            override fun onMeasure(widthMeasureSpec: Int, heightMeasureSpec: Int) {
                val metrics = windowManager.currentWindowMetrics
                val insets = metrics.windowInsets.getInsetsIgnoringVisibility(
                    WindowInsets.Type.systemBars() or WindowInsets.Type.displayCutout()
                )
                val availableHeight = (metrics.bounds.height() - insets.top - insets.bottom - dp(32)).coerceAtLeast(dp(80))
                super.onMeasure(widthMeasureSpec, MeasureSpec.makeMeasureSpec(availableHeight, MeasureSpec.AT_MOST))
            }
        }.apply {
            isFillViewport = false
            clipToPadding = false
            addView(card)
            addOnAttachStateChangeListener(object : View.OnAttachStateChangeListener {
                override fun onViewAttachedToWindow(view: View) = Unit
                override fun onViewDetachedFromWindow(view: View) {
                    if (this@AuthenticationAlertManager.overlay === view) {
                        this@AuthenticationAlertManager.overlay = null
                        messageView = null
                    }
                }
            })
        }
        val params = WindowManager.LayoutParams(
            (windowManager.currentWindowMetrics.bounds.width() - dp(40)).coerceIn(dp(160), dp(440)),
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or WindowManager.LayoutParams.FLAG_DIM_BEHIND,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.CENTER
            dimAmount = 0.32f
            title = "持续认证提醒"
        }
        return try {
            windowManager.addView(scroll, params)
            overlay = scroll
            messageView = messageText
            card.announceForAccessibility("持续认证提醒。请确认是你本人在使用手机。")
            true
        } catch (_: RuntimeException) {
            overlay = null
            messageView = null
            false
        }
    }

    fun dismiss() {
        overlay?.let { runCatching { windowManager.removeView(it) } }
        overlay = null
        messageView = null
    }
}
