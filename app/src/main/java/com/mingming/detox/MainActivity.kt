package com.mingming.detox

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.Image
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

data class AppRow(
    val pkg: String,
    val label: String,
    val icon: ImageBitmap,
    val usedMs: Long,
    val limitMin: Int,
    val locked: Boolean,
)

/** 쇼츠·릴스·웹 도메인 같은 콘텐츠 규칙 한 줄 */
data class ContentRow(val target: String, val usedMs: Long, val limitMin: Int)

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MaterialTheme { MainScreen() } }
    }
}

fun isAccessibilityOn(context: Context): Boolean {
    val enabled = Settings.Secure.getString(
        context.contentResolver, Settings.Secure.ENABLED_ACCESSIBILITY_SERVICES
    ) ?: return false
    return enabled.split(':').any { it.startsWith(context.packageName + "/") }
}

private fun Drawable.toImageBitmap(): ImageBitmap {
    val size = 96
    val bmp = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)
    setBounds(0, 0, size, size)
    draw(Canvas(bmp))
    return bmp.asImageBitmap()
}

private fun loadApps(context: Context): List<AppRow> {
    val pm = context.packageManager
    val rules = RuleStore(context)
    val usage = if (UsageHelper.hasUsageAccess(context)) UsageHelper.todayUsage(context) else emptyMap()
    val launcher = Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_LAUNCHER)
    return pm.queryIntentActivities(launcher, 0)
        .map { it.activityInfo.applicationInfo }
        .distinctBy { it.packageName }
        .filter { it.packageName != context.packageName }
        .map {
            AppRow(
                pkg = it.packageName,
                label = pm.getApplicationLabel(it).toString(),
                icon = pm.getApplicationIcon(it).toImageBitmap(),
                usedMs = usage[it.packageName] ?: 0L,
                limitMin = rules.limitMinutes(it.packageName),
                locked = rules.isLocked(it.packageName),
            )
        }
        .sortedWith(compareByDescending<AppRow> { it.limitMin > 0 || it.locked }.thenByDescending { it.usedMs })
}

private fun loadContent(rules: RuleStore): Pair<List<ContentRow>, List<ContentRow>> {
    fun row(t: String) = ContentRow(t, rules.usedToday(t), rules.limitMinutes(t))
    return listOf(row(RuleStore.SHORTS), row(RuleStore.REELS)) to rules.domains().map { row("web:$it") }
}

private fun fmt(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60}시간 ${m % 60}분" else "${m}분"
}

/** 수정 대상: 앱, 콘텐츠, 또는 새 도메인 추가 */
private sealed interface Editing {
    data class App(val row: AppRow) : Editing
    data class Content(val row: ContentRow) : Editing
    data object NewDomain : Editing
    data object Token : Editing
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val rules = remember { RuleStore(context) }
    var usageOk by remember { mutableStateOf(UsageHelper.hasUsageAccess(context)) }
    var a11yOk by remember { mutableStateOf(isAccessibilityOn(context)) }
    var apps by remember { mutableStateOf<List<AppRow>?>(null) }
    var content by remember { mutableStateOf(loadContent(rules)) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<Editing?>(null) }
    var updateStatus by remember { mutableStateOf<String?>(null) }
    var updating by remember { mutableStateOf(false) }

    // 설정 화면에서 돌아오면 권한 상태와 사용량을 새로고침
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_RESUME) {
                usageOk = UsageHelper.hasUsageAccess(context)
                a11yOk = isAccessibilityOn(context)
                refreshKey++
            }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    LaunchedEffect(refreshKey) {
        content = loadContent(rules)
        apps = withContext(Dispatchers.Default) { loadApps(context) }
    }

    fun checkUpdate() {
        if (!context.packageManager.canRequestPackageInstalls()) {
            updateStatus = "이 앱의 '알 수 없는 앱 설치'를 허용한 뒤 다시 눌러 주세요."
            context.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${context.packageName}"))
            )
            return
        }
        updating = true
        updateStatus = "확인 중…"
        scope.launch {
            val result = withContext(Dispatchers.IO) {
                runCatching {
                    val rel = Updater.latest(rules.githubToken)
                    if (rel.build <= BuildConfig.VERSION_CODE) return@runCatching "이미 최신이에요 (build-${BuildConfig.VERSION_CODE})"
                    val apk = File(context.cacheDir, "update.apk")
                    Updater.download(rel, rules.githubToken, apk)
                    Updater.install(context, apk)
                    "build-${rel.build} 설치를 시작했어요"
                }.getOrElse { "업데이트 실패: ${it.message}" }
            }
            updateStatus = result
            updating = false
        }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("디지털 디톡스") }) }) { pad ->
        LazyColumn(Modifier.padding(pad).fillMaxSize()) {
            if (!usageOk) item {
                PermissionCard("사용 기록 접근 권한이 필요해요", "앱별 사용 시간을 계산하려면 켜 주세요.") {
                    context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS))
                }
            }
            if (!a11yOk) item {
                PermissionCard(
                    "접근성 서비스를 켜 주세요",
                    "설정 > 접근성 > 설치된 앱 > 디지털 디톡스. 차단과 쇼츠·웹 감지에 필요해요."
                ) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }
            }

            item {
                UpdateCard(
                    status = updateStatus,
                    busy = updating,
                    onCheck = ::checkUpdate,
                    onToken = { editing = Editing.Token }
                )
            }

            item { SectionHeader("쇼츠 · 릴스") }
            items(content.first, key = { it.target }) { row ->
                ContentItem(row) { editing = Editing.Content(row) }
                HorizontalDivider()
            }

            item {
                SectionHeader("웹사이트", action = "+ 추가") { editing = Editing.NewDomain }
            }
            if (content.second.isEmpty()) item {
                Text(
                    "제한할 도메인을 추가해 보세요. 크롬·삼성 인터넷·웨일 등에서 동작해요.",
                    Modifier.padding(horizontal = 16.dp, vertical = 8.dp),
                    style = MaterialTheme.typography.bodySmall
                )
            }
            items(content.second, key = { it.target }) { row ->
                ContentItem(row) { editing = Editing.Content(row) }
                HorizontalDivider()
            }

            item { SectionHeader("앱") }
            val list = apps
            if (list == null) item {
                Box(Modifier.fillMaxWidth().padding(24.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            } else items(list, key = { it.pkg }) { app ->
                AppItem(
                    app,
                    onClick = { editing = Editing.App(app) },
                    onLockChange = { locked ->
                        rules.setLocked(app.pkg, locked)
                        refreshKey++
                    }
                )
                HorizontalDivider()
            }
        }
    }

    when (val e = editing) {
        is Editing.App -> LimitDialog(
            title = "${e.row.label} 하루 제한",
            initial = e.row.limitMin,
            clearText = "제한 해제",
            onDismiss = { editing = null },
            onSave = { rules.setLimitMinutes(e.row.pkg, it); editing = null; refreshKey++ },
        )
        is Editing.Content -> {
            val isWeb = e.row.target.startsWith("web:")
            LimitDialog(
                title = "${RuleStore.label(e.row.target)} 하루 제한",
                initial = e.row.limitMin,
                clearText = if (isWeb) "삭제" else "제한 해제",
                onDismiss = { editing = null },
                onSave = { m ->
                    if (isWeb && m <= 0) rules.removeDomain(e.row.target.removePrefix("web:"))
                    else rules.setLimitMinutes(e.row.target, m)
                    editing = null; refreshKey++
                },
            )
        }
        Editing.NewDomain -> DomainDialog(
            onDismiss = { editing = null },
            onSave = { d, m -> rules.addDomain(d, m); editing = null; refreshKey++ },
        )
        Editing.Token -> TokenDialog(
            initial = rules.githubToken,
            onDismiss = { editing = null },
            onSave = { rules.githubToken = it; editing = null },
        )
        null -> Unit
    }
}

@Composable
private fun SectionHeader(title: String, action: String? = null, onAction: () -> Unit = {}) {
    Row(
        Modifier.fillMaxWidth().padding(start = 16.dp, end = 8.dp, top = 20.dp, bottom = 4.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(title, style = MaterialTheme.typography.titleSmall, color = MaterialTheme.colorScheme.primary,
            modifier = Modifier.weight(1f))
        if (action != null) TextButton(onClick = onAction) { Text(action) }
    }
}

@Composable
private fun PermissionCard(title: String, body: String, onClick: () -> Unit) {
    Card(
        Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp).clickable(onClick = onClick),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer)
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(title, fontWeight = FontWeight.Bold)
            Text(body, style = MaterialTheme.typography.bodySmall)
            Text("눌러서 설정 열기", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun UpdateCard(status: String?, busy: Boolean, onCheck: () -> Unit, onToken: () -> Unit) {
    Card(Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 6.dp)) {
        Column(Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Column(Modifier.weight(1f)) {
                    Text("현재 버전 build-${BuildConfig.VERSION_CODE}", fontWeight = FontWeight.Medium)
                    if (status != null) Text(status, style = MaterialTheme.typography.bodySmall)
                }
                Button(onClick = onCheck, enabled = !busy) { Text("업데이트") }
            }
            TextButton(onClick = onToken, contentPadding = PaddingValues(0.dp)) {
                Text("깃허브 토큰 (비공개 저장소일 때만)", style = MaterialTheme.typography.labelSmall)
            }
        }
    }
}

@Composable
private fun UsageLine(usedMs: Long, limitMin: Int) {
    val limitText = if (limitMin > 0) " / 제한 ${fmt(limitMin * 60_000L)}" else " · 제한 없음"
    Text("오늘 ${fmt(usedMs)}$limitText", style = MaterialTheme.typography.bodySmall)
    if (limitMin > 0) {
        LinearProgressIndicator(
            progress = { (usedMs / (limitMin * 60_000f)).coerceIn(0f, 1f) },
            modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
        )
    }
}

@Composable
private fun ContentItem(row: ContentRow, onClick: () -> Unit) {
    Column(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 12.dp)
    ) {
        Text(RuleStore.label(row.target), fontWeight = FontWeight.Medium)
        UsageLine(row.usedMs, row.limitMin)
    }
}

@Composable
private fun AppItem(app: AppRow, onClick: () -> Unit, onLockChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(app.icon, contentDescription = null, modifier = Modifier.size(40.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, fontWeight = FontWeight.Medium)
            UsageLine(app.usedMs, app.limitMin)
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Switch(checked = app.locked, onCheckedChange = onLockChange)
            Text("잠금", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun MinutesField(text: String, onChange: (String) -> Unit) {
    Column {
        OutlinedTextField(
            value = text,
            onValueChange = { v -> onChange(v.filter { it.isDigit() }.take(4)) },
            label = { Text("하루 몇 분") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )
        Spacer(Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            listOf(10, 30, 60, 120).forEach { m ->
                AssistChip(onClick = { onChange(m.toString()) }, label = { Text("${m}분") })
            }
        }
    }
}

@Composable
private fun LimitDialog(
    title: String, initial: Int, clearText: String,
    onDismiss: () -> Unit, onSave: (Int) -> Unit,
) {
    var text by remember { mutableStateOf(if (initial > 0) initial.toString() else "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = { MinutesField(text) { text = it } },
        confirmButton = { TextButton(onClick = { onSave(text.toIntOrNull() ?: 0) }) { Text("저장") } },
        dismissButton = { TextButton(onClick = { onSave(0) }) { Text(clearText) } }
    )
}

@Composable
private fun DomainDialog(onDismiss: () -> Unit, onSave: (String, Int) -> Unit) {
    var domain by remember { mutableStateOf("") }
    var minutes by remember { mutableStateOf("30") }
    val normalized = RuleStore.normalizeDomain(domain)
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("웹사이트 제한 추가") },
        text = {
            Column {
                OutlinedTextField(
                    value = domain,
                    onValueChange = { domain = it },
                    label = { Text("도메인 (예: youtube.com)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Uri),
                    singleLine = true,
                    supportingText = { if (normalized != null) Text("하위 도메인 포함: *.$normalized") }
                )
                Spacer(Modifier.height(8.dp))
                MinutesField(minutes) { minutes = it }
            }
        },
        confirmButton = {
            TextButton(
                enabled = normalized != null && (minutes.toIntOrNull() ?: 0) > 0,
                onClick = { onSave(normalized!!, minutes.toInt()) }
            ) { Text("추가") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}

@Composable
private fun TokenDialog(initial: String, onDismiss: () -> Unit, onSave: (String) -> Unit) {
    var text by remember { mutableStateOf(initial) }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("깃허브 토큰") },
        text = {
            Column {
                Text(
                    "저장소가 비공개일 때만 필요해요. Detox 저장소 Contents 읽기 권한만 있는 토큰을 넣어 주세요.",
                    style = MaterialTheme.typography.bodySmall
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(value = text, onValueChange = { text = it }, singleLine = true,
                    label = { Text("github_pat_…") })
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text) }) { Text("저장") } },
        dismissButton = { TextButton(onClick = onDismiss) { Text("취소") } }
    )
}
