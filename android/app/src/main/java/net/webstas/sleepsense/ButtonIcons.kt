package net.webstas.sleepsense

import android.widget.Button
import androidx.annotation.DrawableRes

/** Puts a Material icon before a button's text, tinted with the text colour (so it suits light and dark). */
fun Button.setIcon(@DrawableRes icon: Int) {
    setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
    compoundDrawablePadding = (8 * resources.displayMetrics.density).toInt()
    compoundDrawableTintList = textColors
}
