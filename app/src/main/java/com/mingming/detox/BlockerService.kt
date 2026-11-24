package com.mingming.detox

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.view.accessibility.AccessibilityEvent

/**
 * 앞에 열린 앱을 감시한다. 잠긴 앱이거나 오늘 제한을 넘긴 앱이면 홈으로 보내고 차단 화면을 띄운다.
 * 앱을 계속 켜 둔 채 제한 시간이 지나는 경우를 위해 30초마다 다시 확인한다.
 */
class BlockerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private var currentPkg: String? = null
    private lateinit var rules: RuleStore

    private val ignored by lazy {
        setOf(packageName, "com.android.systemui", "android", "com.android.settings") + launcherPackages()
    }

    private val periodic = object : Runnable {
        override fun run() {
            currentPkg?.let { check(it) }
            handler.postDelayed(this, 30_000)
        }
    }

    override fun onServiceConnected() {
        rules = RuleStore(this)
        handler.post(periodic)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        if (event.eventType != AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) return
        val pkg = event.packageName?.toString() ?: return
        // 키보드 같은 오버레이 창은 무시하고, 실제 앱 전환만 기록
        if (pkg.contains("inputmethod") || pkg.contains("keyboard")) return
        currentPkg = pkg
        check(pkg)
    }

    private fun check(pkg: String) {
        if (pkg in ignored) return
        val reason = when {
            rules.isLocked(pkg) -> BlockActivity.REASON_LOCKED
            else -> {
                val limit = rules.limitMinutes(pkg)
                if (limit > 0 && UsageHelper.hasUsageAccess(this) &&
                    UsageHelper.todayUsage(this, pkg) >= limit * 60_000L
                ) BlockActivity.REASON_LIMIT else null
            }
        } ?: return

        performGlobalAction(GLOBAL_ACTION_HOME)
        startActivity(
            Intent(this, BlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(BlockActivity.EXTRA_PKG, pkg)
                .putExtra(BlockActivity.EXTRA_REASON, reason)
        )
        currentPkg = null
    }

    private fun launcherPackages(): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.queryIntentActivities(home, 0)
            .map { it.activityInfo.packageName }.toSet()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(periodic)
        super.onDestroy()
    }
}
