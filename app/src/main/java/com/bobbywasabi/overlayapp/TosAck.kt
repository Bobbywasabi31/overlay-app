package com.bobbywasabi.overlayapp

import android.content.Context

/** Item 91: the ban-risk notice must be acknowledged before Auto can arm. */
object TosAck {
    private const val PREFS = "throw_assistant_prefs"
    private const val KEY_ACKNOWLEDGED = "tos_acknowledged"

    fun isAcknowledged(context: Context): Boolean =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .getBoolean(KEY_ACKNOWLEDGED, false)

    fun setAcknowledged(context: Context) {
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)
            .edit().putBoolean(KEY_ACKNOWLEDGED, true).apply()
    }

    const val EXTRA_SHOW_TOS = "show_tos"
}
