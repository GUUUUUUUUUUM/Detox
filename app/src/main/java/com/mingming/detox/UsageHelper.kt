package com.mingming.detox

import android.app.AppOpsManager
import android.app.usage.UsageEvents
import android.app.usage.UsageStatsManager
import android.content.Context
import android.os.Process
import java.util.Calendar

object UsageHelper {

    fun hasUsageAccess(context: Context): Boolean {
        val ops = context.getSystemService(Context.APP_OPS_SERVICE) as AppOpsManager
        val mode = ops.unsafeCheckOpNoThrow(
            AppOpsManager.OPSTR_GET_USAGE_STATS, Process.myUid(), context.packageName
        )
        return mode == AppOpsManager.MODE_ALLOWED
    }

    private fun startOfToday(): Long = Calendar.getInstance().apply {
        set(Calendar.HOUR_OF_DAY, 0); set(Calendar.MINUTE, 0)
        set(Calendar.SECOND, 0); set(Calendar.MILLISECOND, 0)
    }.timeInMillis

    /** 오늘 0시부터 지금까지 앱별 화면 사용 시간(ms). 이벤트 기반이라 자정 경계가 정확하다. */
    fun todayUsage(context: Context): Map<String, Long> {
        val usm = context.getSystemService(Context.USAGE_STATS_SERVICE) as UsageStatsManager
        val start = startOfToday()
        val now = System.currentTimeMillis()
        val events = usm.queryEvents(start, now)
        val totals = HashMap<String, Long>()
        val resumedAt = HashMap<String, Long>()
        val e = UsageEvents.Event()
        while (events.hasNextEvent()) {
            events.getNextEvent(e)
            val pkg = e.packageName ?: continue
            when (e.eventType) {
                UsageEvents.Event.ACTIVITY_RESUMED -> resumedAt.putIfAbsent(pkg, e.timeStamp)
                UsageEvents.Event.ACTIVITY_PAUSED,
                UsageEvents.Event.ACTIVITY_STOPPED -> {
                    val from = resumedAt.remove(pkg) ?: continue
                    totals[pkg] = (totals[pkg] ?: 0L) + (e.timeStamp - from)
                }
            }
        }
        // 아직 열려 있는 앱
        for ((pkg, from) in resumedAt) totals[pkg] = (totals[pkg] ?: 0L) + (now - from)
        return totals
    }

    fun todayUsage(context: Context, pkg: String): Long = todayUsage(context)[pkg] ?: 0L
}
