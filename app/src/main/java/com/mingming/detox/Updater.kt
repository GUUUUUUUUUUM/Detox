package com.mingming.detox

import android.content.Context
import android.content.Intent
import androidx.core.content.FileProvider
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL

/** 깃허브 릴리스에서 새 빌드를 찾아 받아서 설치한다. (같은 서명키라 덮어쓰기 업데이트) */
object Updater {
    private const val REPO = "GUUUUUUUUUUM/Detox"

    data class Release(val build: Int, val assetApiUrl: String)

    fun latest(token: String): Release {
        val conn = open("https://api.github.com/repos/$REPO/releases/latest", token)
        conn.setRequestProperty("Accept", "application/vnd.github+json")
        val code = conn.responseCode
        if (code == 404) error("릴리스를 찾을 수 없어요. 저장소가 비공개면 토큰이 필요해요.")
        if (code != 200) error("깃허브 응답 오류 ($code)")
        val json = JSONObject(conn.inputStream.bufferedReader().readText())
        val build = json.getString("tag_name").substringAfterLast('-').toIntOrNull()
            ?: error("버전을 읽을 수 없어요")
        val assets = json.getJSONArray("assets")
        for (i in 0 until assets.length()) {
            val a = assets.getJSONObject(i)
            if (a.getString("name").endsWith(".apk")) return Release(build, a.getString("url"))
        }
        error("릴리스에 APK가 없어요")
    }

    fun download(release: Release, token: String, dest: File) {
        // API 주소로 요청하면 실제 파일 주소로 리다이렉트된다. 그 주소에는 토큰을 보내지 않는다.
        var conn = open(release.assetApiUrl, token)
        conn.setRequestProperty("Accept", "application/octet-stream")
        conn.instanceFollowRedirects = false
        if (conn.responseCode in 300..399) {
            val location = conn.getHeaderField("Location")
            conn.disconnect()
            conn = URL(location).openConnection() as HttpURLConnection
        }
        if (conn.responseCode != 200) error("다운로드 실패 (${conn.responseCode})")
        conn.inputStream.use { input -> dest.outputStream().use { input.copyTo(it) } }
    }

    /**
     * 받은 APK를 시스템 설치 화면에 넘긴다.
     * (PackageInstaller 세션으로 자기 자신을 업데이트하면 일부 폰에서 INSTALL_FAILED_ABORTED로 막혀서,
     *  브라우저에서 받은 파일을 여는 것과 같은 표준 방식으로 바꿨다.)
     */
    fun install(context: Context, apk: File) {
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.files", apk)
        val intent = Intent(Intent.ACTION_VIEW)
            .setDataAndType(uri, "application/vnd.android.package-archive")
            .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        context.startActivity(intent)
    }

    private fun open(url: String, token: String): HttpURLConnection {
        val conn = URL(url).openConnection() as HttpURLConnection
        conn.connectTimeout = 15_000
        conn.readTimeout = 60_000
        if (token.isNotBlank()) conn.setRequestProperty("Authorization", "Bearer $token")
        return conn
    }
}
