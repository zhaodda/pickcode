package com.pickcode.app.overlay

import android.animation.Animator
import android.animation.AnimatorListenerAdapter
import android.animation.ObjectAnimator
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.graphics.PixelFormat
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
import android.widget.TextView
import android.widget.Toast
import com.pickcode.app.R
import com.pickcode.app.data.model.CodeRecord

/**
 * 灵动岛悬浮窗管理器
 * 在屏幕顶部居中显示一个胶囊形悬浮窗，展示识别到的验证码
 */
class OverlayManager(private val context: Context) {

    private val wm = context.getSystemService(WindowManager::class.java)
    private val handler = Handler(Looper.getMainLooper())
    private var overlayView: View? = null
    private var dismissRunnable: Runnable? = null

    companion object {
        private const val AUTO_DISMISS_MS = 15_000L
    }

    fun showCode(record: CodeRecord) {
        handler.post { displayOverlay(record) }
    }

    fun showNoResult() {
        handler.post { displayMessage("未找到验证码", isError = false) }
    }

    fun showError() {
        handler.post { displayMessage("识别失败，请重试", isError = true) }
    }

    fun dismiss() {
        handler.post { removeOverlay() }
    }

    private fun displayOverlay(record: CodeRecord) {
        removeOverlay()

        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.overlay_island, null)

        view.findViewById<TextView>(R.id.tvCode).text = record.code
        view.findViewById<TextView>(R.id.tvType).text = "${record.codeType.emoji} ${record.codeType.label}"

        view.setOnClickListener {
            copyToClipboard(record.code)
            removeOverlay()
        }

        view.setOnLongClickListener {
            removeOverlay()
            true
        }

        addToWindow(view)
        animateIn(view)
        scheduleDismiss()
    }

    private fun displayMessage(msg: String, isError: Boolean) {
        removeOverlay()
        val inflater = LayoutInflater.from(context)
        val view = inflater.inflate(R.layout.overlay_island, null)
        view.findViewById<TextView>(R.id.tvCode).text = msg
        view.findViewById<TextView>(R.id.tvType).text = if (isError) "⚠️ 错误" else "ℹ️ 提示"
        view.setOnClickListener { removeOverlay() }
        addToWindow(view)
        animateIn(view)
        handler.postDelayed({ removeOverlay() }, 3000)
    }

    private fun addToWindow(view: View) {
        val type = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O)
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY
        else @Suppress("DEPRECATION") WindowManager.LayoutParams.TYPE_PHONE

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            type,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
                    WindowManager.LayoutParams.FLAG_NOT_TOUCH_MODAL or
                    WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN,
            PixelFormat.TRANSLUCENT
        ).apply {
            gravity = Gravity.TOP or Gravity.CENTER_HORIZONTAL
            y = 48  // 留出状态栏
        }

        overlayView = view
        try { wm.addView(view, params) } catch (_: Exception) {}
    }

    private fun animateIn(view: View) {
        view.translationY = -120f
        view.alpha = 0f
        val ty = ObjectAnimator.ofFloat(view, "translationY", -120f, 0f)
        val al = ObjectAnimator.ofFloat(view, "alpha", 0f, 1f)
        ty.duration = 320
        al.duration = 250
        ty.start()
        al.start()
    }

    private fun scheduleDismiss() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        dismissRunnable = Runnable { removeOverlay() }
        handler.postDelayed(dismissRunnable!!, AUTO_DISMISS_MS)
    }

    private fun removeOverlay() {
        dismissRunnable?.let { handler.removeCallbacks(it) }
        val v = overlayView ?: return
        overlayView = null
        val ao = ObjectAnimator.ofFloat(v, "alpha", 1f, 0f)
        ao.duration = 200
        ao.addListener(object : AnimatorListenerAdapter() {
            override fun onAnimationEnd(animation: Animator) {
                try { wm.removeView(v) } catch (_: Exception) {}
            }
        })
        ao.start()
    }

    private fun copyToClipboard(text: String) {
        val cm = context.getSystemService(ClipboardManager::class.java)
        cm.setPrimaryClip(ClipData.newPlainText("验证码", text))
        Toast.makeText(context, "已复制：$text", Toast.LENGTH_SHORT).show()
    }
}
