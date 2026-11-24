package com.mingming.detox

import android.content.Context
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * 규칙 저장소.
 * target 은 앱 패키지명, 또는 "shorts:youtube", "reels:instagram", "web:도메인" 같은 콘텐츠 대상.
 */
class RuleStore(context: Context) {
    private val prefs = context.getSharedPreferences("rules", Context.MODE_PRIVATE)

    fun limitMinutes(target: String): Int = prefs.getInt("limit_$target", 0)

    fun setLimitMinutes(target: String, minutes: Int) {
        prefs.edit().apply {
            if (minutes <= 0) remove("limit_$target") else putInt("limit_$target", minutes)
        }.apply()
    }

    fun isLocked(pkg: String): Boolean = prefs.getStringSet("locked", emptySet())!!.contains(pkg)

    fun setLocked(pkg: String, locked: Boolean) {
        val set = prefs.getStringSet("locked", emptySet())!!.toMutableSet()
        if (locked) set.add(pkg) else set.remove(pkg)
        prefs.edit().putStringSet("locked", set).apply()
    }

    // ---- 웹 도메인 ----
    fun domains(): List<String> = prefs.getStringSet("domains", emptySet())!!.sorted()

    fun addDomain(domain: String, minutes: Int) {
        val set = prefs.getStringSet("domains", emptySet())!!.toMutableSet()
        set.add(domain)
        prefs.edit().putStringSet("domains", set).apply()
        setLimitMinutes("web:$domain", minutes)
    }

    fun removeDomain(domain: String) {
        val set = prefs.getStringSet("domains", emptySet())!!.toMutableSet()
        set.remove(domain)
        prefs.edit().putStringSet("domains", set).remove("limit_web:$domain").apply()
    }

    // ---- 콘텐츠 대상의 오늘 사용 시간 (접근성 서비스가 직접 잰다) ----
    private fun today() = SimpleDateFormat("yyyyMMdd", Locale.US).format(Date())

    fun usedToday(target: String): Long = prefs.getLong("used_${today()}_$target", 0L)

    fun addUsage(target: String, ms: Long) {
        val key = "used_${today()}_$target"
        prefs.edit().putLong(key, prefs.getLong(key, 0L) + ms).apply()
    }

    fun clearOldUsage() {
        val keep = "used_${today()}_"
        val edit = prefs.edit()
        prefs.all.keys.filter { it.startsWith("used_") && !it.startsWith(keep) }.forEach { edit.remove(it) }
        edit.apply()
    }

    // ---- 비공개 저장소용 깃허브 토큰 (공개 저장소면 비워 둠) ----
    var githubToken: String
        get() = prefs.getString("gh_token", "") ?: ""
        set(v) = prefs.edit().putString("gh_token", v.trim()).apply()

    companion object {
        const val SHORTS = "shorts:youtube"
        const val REELS = "reels:instagram"

        fun label(target: String): String = when {
            target == SHORTS -> "유튜브 쇼츠"
            target == REELS -> "인스타그램 릴스"
            target.startsWith("web:") -> target.removePrefix("web:")
            else -> target
        }

        /** "https://m.Naver.com/abc" → "m.naver.com". 주소가 아니면 null. */
        fun hostOf(text: String): String? {
            var t = text.trim().lowercase()
            if (t.isEmpty() || t.contains(' ')) return null
            t = t.substringAfter("://")
            t = t.substringBefore('/').substringBefore('?').substringBefore('#').substringBefore(':')
            return if (t.contains('.')) t else null
        }

        /** 사용자가 입력한 도메인을 정리: "https://www.youtube.com/" → "youtube.com" */
        fun normalizeDomain(input: String): String? = hostOf(input)?.removePrefix("www.")

        fun hostMatches(host: String, domain: String) = host == domain || host.endsWith(".$domain")
    }
}
