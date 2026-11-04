package net.webstas.sleepsense

import android.content.Context
import android.content.res.Configuration
import android.view.HapticFeedbackConstants
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CompoundButton
import androidx.core.content.edit

/** Look-and-feel preferences of the main screen, and its small shared touches. */
object UiPrefs {
    private const val PREFS = "ui"
    private const val KEY_TRUE_BLACK = "true_black"
    private const val KEY_COLLAPSED = "collapsed"

    fun trueBlack(context: Context) = context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getBoolean(KEY_TRUE_BLACK, false)

    fun setTrueBlack(context: Context, on: Boolean) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putBoolean(KEY_TRUE_BLACK, on) }
    }

    /** Lays the true black colors over the dark theme, if chosen. Call before setContentView. */
    fun applyTheme(activity: android.app.Activity) {
        val dark = activity.resources.configuration.uiMode and Configuration.UI_MODE_NIGHT_MASK == Configuration.UI_MODE_NIGHT_YES
        if (dark && trueBlack(activity)) activity.theme.applyStyle(R.style.TrueBlack, true)
    }

    private fun collapsed(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getStringSet(KEY_COLLAPSED, emptySet())!!

    fun isCollapsed(context: Context, card: String) = card in collapsed(context)

    fun setCollapsed(context: Context, card: String, collapsed: Boolean) {
        val now = collapsed(context).toMutableSet().apply { if (collapsed) add(card) else remove(card) }
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit { putStringSet(KEY_COLLAPSED, now) }
    }
}

/** A light tick as a finger lands on any button or switch below [root]. */
fun attachHaptics(root: View) {
    if (root is Button || root is CompoundButton) {
        root.setOnTouchListener { v, event ->
            if (event.actionMasked == MotionEvent.ACTION_DOWN) v.performHapticFeedback(HapticFeedbackConstants.KEYBOARD_TAP)
            false
        }
    }
    if (root is ViewGroup) for (i in 0 until root.childCount) attachHaptics(root.getChildAt(i))
}
