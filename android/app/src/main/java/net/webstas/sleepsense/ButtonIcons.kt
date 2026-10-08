package net.webstas.sleepsense

import android.widget.Button
import androidx.annotation.DrawableRes
import com.google.android.material.button.MaterialButton

/** Puts a Material icon before a button's text. */
fun Button.setIcon(@DrawableRes icon: Int) {
    if (this is MaterialButton) {
        setIconResource(icon)
        iconPadding = (8 * resources.displayMetrics.density).toInt()
    } else {
        setCompoundDrawablesRelativeWithIntrinsicBounds(icon, 0, 0, 0)
        compoundDrawablePadding = (8 * resources.displayMetrics.density).toInt()
        compoundDrawableTintList = textColors
    }
}
