package com.mingming.detox

import android.content.Context

/** 앱별 하루 제한(분)과 수동 잠금 목록을 SharedPreferences에 저장한다. */
class RuleStore(context: Context) {
    private val prefs = context.getSharedPreferences("rules", Context.MODE_PRIVATE)

    fun limitMinutes(pkg: String): Int = prefs.getInt("limit_$pkg", 0)

    fun setLimitMinutes(pkg: String, minutes: Int) {
        prefs.edit().apply {
            if (minutes <= 0) remove("limit_$pkg") else putInt("limit_$pkg", minutes)
        }.apply()
    }

    fun isLocked(pkg: String): Boolean = prefs.getStringSet("locked", emptySet())!!.contains(pkg)

    fun setLocked(pkg: String, locked: Boolean) {
        val set = prefs.getStringSet("locked", emptySet())!!.toMutableSet()
        if (locked) set.add(pkg) else set.remove(pkg)
        prefs.edit().putStringSet("locked", set).apply()
    }
}
