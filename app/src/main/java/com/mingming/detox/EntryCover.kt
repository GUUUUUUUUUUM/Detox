package com.mingming.detox

import android.accessibilityservice.AccessibilityService
import android.content.Context
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.Rect
import android.view.Gravity
import android.view.WindowManager
import android.view.accessibility.AccessibilityNodeInfo
import android.widget.TextView

/**
 * 다른 앱의 화면을 직접 지울 수는 없어서, 쇼츠·릴스로 들어가는 입구(하단 탭, 피드의 쇼츠 칸) 위에
 * 접근성 오버레이를 덮어 안 보이고 안 눌리게 한다. 화면이 스크롤되면 위치를 다시 맞춘다.
 */
class EntryCover(private val service: AccessibilityService) {

    private val wm = service.getSystemService(Context.WINDOW_SERVICE) as WindowManager
    private val views = mutableListOf<TextView>()
    private val screenHeight = service.resources.displayMetrics.heightPixels

    fun clear() {
        views.forEach { runCatching { wm.removeView(it) } }
        views.clear()
    }

    /** 이 화면에서 덮어야 할 영역을 찾아 덮개를 맞춘다. */
    fun update(pkg: String, root: AccessibilityNodeInfo) {
        val rects = when (pkg) {
            BlockerService.YOUTUBE -> youtubeEntries(root)
            BlockerService.INSTAGRAM -> instagramEntries(root)
            else -> emptyList()
        }
        show(rects.distinct())
    }

    private fun youtubeEntries(root: AccessibilityNodeInfo): List<Rect> =
        labelled(root, setOf("shorts")).mapNotNull { coverFor(it) }

    private fun instagramEntries(root: AccessibilityNodeInfo): List<Rect> {
        val nodes = root.findAccessibilityNodeInfosByViewId("${BlockerService.INSTAGRAM}:id/clips_tab") +
            labelled(root, setOf("reels", "릴스"))
        return nodes.filter { it.isVisibleToUser }.mapNotNull { coverFor(it) }
    }

    /** 글자나 설명이 정확히 그 단어인 노드만 (영상 제목에 'shorts'가 들어간 경우는 제외) */
    private fun labelled(root: AccessibilityNodeInfo, words: Set<String>): List<AccessibilityNodeInfo> =
        words.flatMap { root.findAccessibilityNodeInfosByText(it) }.filter { n ->
            n.isVisibleToUser && listOfNotNull(n.text, n.contentDescription)
                .any { it.toString().trim().lowercase() in words }
        }

    /**
     * 덮을 범위 정하기: 피드 목록 안이면 그 목록 칸 전체(쇼츠 줄 전체),
     * 아니면 눌리는 버튼(하단 탭) 크기.
     */
    private fun coverFor(node: AccessibilityNodeInfo): Rect? {
        var cur: AccessibilityNodeInfo? = node
        var clickable: AccessibilityNodeInfo? = if (node.isClickable) node else null
        repeat(8) {
            val parent = cur?.parent ?: return@repeat
            if (parent.className?.toString()?.endsWith("RecyclerView") == true) {
                val r = Rect().also { cur!!.getBoundsInScreen(it) }
                if (r.height() in 1 until (screenHeight * 0.7).toInt()) return r
                return@repeat
            }
            if (clickable == null && parent.isClickable) clickable = parent
            cur = parent
        }
        val r = Rect().also { (clickable ?: node).getBoundsInScreen(it) }
        return if (r.width() > 0 && r.height() > 0) r else null
    }

    private fun show(rects: List<Rect>) {
        while (views.size > rects.size) runCatching { wm.removeView(views.removeAt(views.size - 1)) }
        rects.forEachIndexed { i, r ->
            val lp = params(r)
            if (i < views.size) {
                runCatching { wm.updateViewLayout(views[i], lp) }
            } else {
                val v = TextView(service).apply {
                    text = "오늘 쇼츠·릴스 끝"
                    setTextColor(Color.parseColor("#9AA0A6"))
                    textSize = 11f
                    gravity = Gravity.CENTER
                    setBackgroundColor(Color.parseColor("#202124"))
                    isClickable = true // 아래 화면으로 터치가 넘어가지 않게
                }
                if (runCatching { wm.addView(v, lp) }.isSuccess) views.add(v)
            }
        }
    }

    private fun params(r: Rect) = WindowManager.LayoutParams(
        r.width(), r.height(),
        WindowManager.LayoutParams.TYPE_ACCESSIBILITY_OVERLAY,
        WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or
            WindowManager.LayoutParams.FLAG_LAYOUT_IN_SCREEN or
            WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
        PixelFormat.TRANSLUCENT
    ).apply {
        gravity = Gravity.TOP or Gravity.START
        x = r.left
        y = r.top
    }
}
