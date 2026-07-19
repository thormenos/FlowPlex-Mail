package com.fcarreau.flowplexmail.data

import android.content.Context

object AccountPrefs {
    private const val PREFS_NAME = "flowplex_prefs"
    private const val KEY_ACCOUNT_NAME = "account_name"

    fun save(context: Context, accountName: String) {
        context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .edit()
            .putString(KEY_ACCOUNT_NAME, accountName)
            .apply()
    }

    fun get(context: Context): String? {
        return context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            .getString(KEY_ACCOUNT_NAME, null)
    }
}
