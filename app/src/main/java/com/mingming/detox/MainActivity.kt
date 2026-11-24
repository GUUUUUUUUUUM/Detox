package com.mingming.detox

import android.content.Context
import android.content.Intent
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.drawable.Drawable
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
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.compose.ui.platform.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

data class AppRow(
    val pkg: String,
    val label: String,
    val icon: ImageBitmap,
    val usedMs: Long,
    val limitMin: Int,
    val locked: Boolean,
)

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
    val canvas = Canvas(bmp)
    setBounds(0, 0, size, size)
    draw(canvas)
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

private fun fmt(ms: Long): String {
    val m = ms / 60_000
    return if (m >= 60) "${m / 60}시간 ${m % 60}분" else "${m}분"
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen() {
    val context = LocalContext.current
    val rules = remember { RuleStore(context) }
    var usageOk by remember { mutableStateOf(UsageHelper.hasUsageAccess(context)) }
    var a11yOk by remember { mutableStateOf(isAccessibilityOn(context)) }
    var apps by remember { mutableStateOf<List<AppRow>?>(null) }
    var refreshKey by remember { mutableIntStateOf(0) }
    var editing by remember { mutableStateOf<AppRow?>(null) }

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
        apps = withContext(Dispatchers.Default) { loadApps(context) }
    }

    Scaffold(topBar = { TopAppBar(title = { Text("디지털 디톡스") }) }) { pad ->
        Column(Modifier.padding(pad).fillMaxSize()) {
            if (!usageOk) PermissionCard(
                "사용 기록 접근 권한이 필요해요",
                "앱별 사용 시간을 계산하려면 켜 주세요."
            ) { context.startActivity(Intent(Settings.ACTION_USAGE_ACCESS_SETTINGS)) }
            if (!a11yOk) PermissionCard(
                "접근성 서비스를 켜 주세요",
                "설정 > 접근성 > 설치된 앱 > 디지털 디톡스. 앱 차단에 필요해요."
            ) { context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)) }

            val list = apps
            if (list == null) {
                Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
            } else {
                LazyColumn(Modifier.fillMaxSize()) {
                    items(list, key = { it.pkg }) { app ->
                        AppItem(
                            app,
                            onClick = { editing = app },
                            onLockChange = { locked ->
                                rules.setLocked(app.pkg, locked)
                                refreshKey++
                            }
                        )
                        HorizontalDivider()
                    }
                }
            }
        }
    }

    editing?.let { app ->
        LimitDialog(
            app = app,
            onDismiss = { editing = null },
            onSave = { minutes ->
                rules.setLimitMinutes(app.pkg, minutes)
                editing = null
                refreshKey++
            }
        )
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
private fun AppItem(app: AppRow, onClick: () -> Unit, onLockChange: (Boolean) -> Unit) {
    Row(
        Modifier.fillMaxWidth().clickable(onClick = onClick).padding(horizontal = 16.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Image(app.icon, contentDescription = null, modifier = Modifier.size(40.dp))
        Spacer(Modifier.width(12.dp))
        Column(Modifier.weight(1f)) {
            Text(app.label, fontWeight = FontWeight.Medium)
            val limitText = if (app.limitMin > 0) " / 제한 ${fmt(app.limitMin * 60_000L)}" else ""
            Text("오늘 ${fmt(app.usedMs)}$limitText", style = MaterialTheme.typography.bodySmall)
            if (app.limitMin > 0) {
                LinearProgressIndicator(
                    progress = { (app.usedMs / (app.limitMin * 60_000f)).coerceIn(0f, 1f) },
                    modifier = Modifier.fillMaxWidth().padding(top = 4.dp)
                )
            }
        }
        Spacer(Modifier.width(8.dp))
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Switch(checked = app.locked, onCheckedChange = onLockChange)
            Text("잠금", style = MaterialTheme.typography.labelSmall)
        }
    }
}

@Composable
private fun LimitDialog(app: AppRow, onDismiss: () -> Unit, onSave: (Int) -> Unit) {
    var text by remember { mutableStateOf(if (app.limitMin > 0) app.limitMin.toString() else "") }
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("${app.label} 하루 제한") },
        text = {
            Column {
                OutlinedTextField(
                    value = text,
                    onValueChange = { v -> text = v.filter { it.isDigit() }.take(4) },
                    label = { Text("분 (비우면 제한 없음)") },
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    listOf(15, 30, 60, 120).forEach { m ->
                        AssistChip(onClick = { text = m.toString() }, label = { Text("${m}분") })
                    }
                }
            }
        },
        confirmButton = { TextButton(onClick = { onSave(text.toIntOrNull() ?: 0) }) { Text("저장") } },
        dismissButton = {
            TextButton(onClick = { onSave(0) }) { Text("제한 해제") }
        }
    )
}
