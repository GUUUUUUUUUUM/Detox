package com.mingming.detox

import android.accessibilityservice.AccessibilityService
import android.content.Intent
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import android.view.accessibility.AccessibilityEvent
import android.view.accessibility.AccessibilityNodeInfo

/**
 * 1) 앱 단위: 잠긴 앱이나 하루 제한을 넘긴 앱을 막는다.
 * 2) 콘텐츠 단위: 유튜브 쇼츠, 인스타 릴스, 브라우저의 특정 도메인을 보고 있는 시간을 직접 재고,
 *    제한을 넘기면 그 화면만 빠져나오게 한다.
 */
class BlockerService : AccessibilityService() {

    private val handler = Handler(Looper.getMainLooper())
    private lateinit var rules: RuleStore
    private lateinit var cover: EntryCover
    private var lastCoverAt = 0L

    private var currentPkg: String? = null
    private var activeTarget: String? = null
    private var lastTick = 0L
    private var lastEventTick = 0L
    private var lastBlockAt = 0L
    private var tickCount = 0

    private val ignored by lazy {
        setOf(packageName, "com.android.systemui", "android", "com.android.settings") + launcherPackages()
    }

    private val periodic = object : Runnable {
        override fun run() {
            tick()
            currentPkg?.let { if (it == YOUTUBE || it == INSTAGRAM) updateCover(it) else cover.clear() }
            // 앱 단위 제한은 30초마다 다시 확인 (앱을 켜 둔 채 시간이 지나는 경우)
            if (++tickCount % 15 == 0) currentPkg?.let { checkApp(it) }
            handler.postDelayed(this, TICK_MS)
        }
    }

    override fun onServiceConnected() {
        rules = RuleStore(this)
        cover = EntryCover(this)
        rules.clearOldUsage()
        lastTick = SystemClock.elapsedRealtime()
        handler.post(periodic)
    }

    override fun onAccessibilityEvent(event: AccessibilityEvent) {
        val pkg = event.packageName?.toString() ?: return
        if (pkg == packageName) return // 내 덮개·차단 화면에서 나온 이벤트는 무시
        if (event.eventType == AccessibilityEvent.TYPE_WINDOW_STATE_CHANGED) {
            // 키보드 같은 오버레이 창은 무시하고, 실제 앱 전환만 기록
            if (pkg.contains("inputmethod") || pkg.contains("keyboard")) return
            currentPkg = pkg
            if (pkg != YOUTUBE && pkg != INSTAGRAM) cover.clear()
            checkApp(pkg)
        }
        // 시간이 끝났으면 쇼츠·릴스 입구를 덮개로 가린다 (스크롤 따라 자주 갱신)
        if (pkg == YOUTUBE || pkg == INSTAGRAM) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastCoverAt > 150) {
                lastCoverAt = now
                updateCover(pkg)
            }
        }
        // 쇼츠로 넘어가는 순간 등을 빨리 잡기 위해 이벤트 때도 확인 (너무 자주는 말고)
        if (pkg in WATCHED) {
            val now = SystemClock.elapsedRealtime()
            if (now - lastEventTick > 700) {
                lastEventTick = now
                tick()
            }
        }
    }

    // ---------- 앱 단위 ----------

    private fun checkApp(pkg: String) {
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
        showBlock(appLabel(pkg), reason, goHome = true)
        currentPkg = null
    }

    // ---------- 콘텐츠 단위 ----------

    private fun tick() {
        val now = SystemClock.elapsedRealtime()
        val pkg = currentPkg
        val target = if (pkg != null && pkg in WATCHED) {
            rootInActiveWindow?.let { root ->
                if (root.packageName?.toString() == pkg) detect(pkg, root) else null
            }
        } else null

        if (target != null && target == activeTarget) {
            rules.addUsage(target, (now - lastTick).coerceAtMost(TICK_MS + 1_000))
        }
        activeTarget = target
        lastTick = now
        if (target != null) enforce(target)
    }

    private fun updateCover(pkg: String) {
        val target = if (pkg == YOUTUBE) RuleStore.SHORTS else RuleStore.REELS
        val limit = rules.limitMinutes(target)
        val over = limit > 0 && rules.usedToday(target) >= limit * 60_000L
        val root = rootInActiveWindow
        if (!over || root == null || root.packageName?.toString() != pkg) {
            cover.clear()
            return
        }
        cover.update(pkg, root)
    }

    private fun detect(pkg: String, root: AccessibilityNodeInfo): String? = when (pkg) {
        YOUTUBE -> if (rules.limitMinutes(RuleStore.SHORTS) > 0 && anyVisible(root, SHORTS_IDS)) RuleStore.SHORTS else null
        INSTAGRAM -> if (rules.limitMinutes(RuleStore.REELS) > 0 && anyVisible(root, REELS_IDS)) RuleStore.REELS else null
        else -> BROWSERS[pkg]?.let { ids -> detectDomain(root, ids) }
    }

    private fun detectDomain(root: AccessibilityNodeInfo, ids: List<String>): String? {
        val domains = rules.domains()
        if (domains.isEmpty()) return null
        for (id in ids) {
            for (node in root.findAccessibilityNodeInfosByViewId(id)) {
                val host = RuleStore.hostOf(node.text?.toString() ?: continue) ?: continue
                val d = domains.firstOrNull { RuleStore.hostMatches(host, it) }
                if (d != null) return "web:$d"
            }
        }
        return null
    }

    private fun anyVisible(root: AccessibilityNodeInfo, ids: List<String>): Boolean =
        ids.any { id -> root.findAccessibilityNodeInfosByViewId(id).any { it.isVisibleToUser } }

    private fun enforce(target: String) {
        val limit = rules.limitMinutes(target)
        if (limit <= 0 || rules.usedToday(target) < limit * 60_000L) return
        val now = SystemClock.elapsedRealtime()
        if (now - lastBlockAt < 1_500) return
        lastBlockAt = now
        val isWeb = target.startsWith("web:")
        // 쇼츠·릴스는 뒤로가기로 그 화면만 빠져나오고, 웹은 홈으로 보낸다
        performGlobalAction(if (isWeb) GLOBAL_ACTION_HOME else GLOBAL_ACTION_BACK)
        showBlock(RuleStore.label(target), BlockActivity.REASON_LIMIT, goHome = isWeb)
        activeTarget = null
    }

    // ---------- 공통 ----------

    private fun showBlock(label: String, reason: String, goHome: Boolean) {
        startActivity(
            Intent(this, BlockActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
                .putExtra(BlockActivity.EXTRA_LABEL, label)
                .putExtra(BlockActivity.EXTRA_REASON, reason)
                .putExtra(BlockActivity.EXTRA_GO_HOME, goHome)
        )
    }

    private fun appLabel(pkg: String): String = runCatching {
        packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
    }.getOrDefault(pkg)

    private fun launcherPackages(): Set<String> {
        val home = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
        return packageManager.queryIntentActivities(home, 0)
            .map { it.activityInfo.packageName }.toSet()
    }

    override fun onInterrupt() {}

    override fun onDestroy() {
        handler.removeCallbacks(periodic)
        cover.clear()
        super.onDestroy()
    }

    companion object {
        private const val TICK_MS = 2_000L
        const val YOUTUBE = "com.google.android.youtube"
        const val INSTAGRAM = "com.instagram.android"

        // 앱 업데이트로 바뀔 수 있는 화면 ID들. 안 잡히면 여기를 고친다.
        val SHORTS_IDS = listOf(
            "$YOUTUBE:id/reel_recycler",
            "$YOUTUBE:id/reel_player_page_container",
            "$YOUTUBE:id/reel_watch_player",
        )
        val REELS_IDS = listOf(
            "$INSTAGRAM:id/clips_viewer_view_pager",
            "$INSTAGRAM:id/clips_viewer_container",
            "$INSTAGRAM:id/clips_video_container",
        )

        /** 브라우저 패키지 → 주소창 ID */
        val BROWSERS = mapOf(
            "com.android.chrome" to listOf("com.android.chrome:id/url_bar"),
            "com.sec.android.app.sbrowser" to listOf(
                "com.sec.android.app.sbrowser:id/location_bar_edit_text",
                "com.sec.android.app.sbrowser:id/custom_tab_toolbar_url_bar_text",
            ),
            "com.naver.whale" to listOf("com.naver.whale:id/url_bar"),
            "com.microsoft.emmx" to listOf("com.microsoft.emmx:id/url_bar"),
            "com.brave.browser" to listOf("com.brave.browser:id/url_bar"),
            "org.mozilla.firefox" to listOf("org.mozilla.firefox:id/mozac_browser_toolbar_url_view"),
        )

        val WATCHED = setOf(YOUTUBE, INSTAGRAM) + BROWSERS.keys
    }
}
