package com.mingming.detox

import android.content.Intent
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.OnBackPressedCallback
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

class BlockActivity : ComponentActivity() {

    companion object {
        const val EXTRA_PKG = "pkg"
        const val EXTRA_REASON = "reason"
        const val REASON_LOCKED = "locked"
        const val REASON_LIMIT = "limit"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val pkg = intent.getStringExtra(EXTRA_PKG) ?: ""
        val reason = intent.getStringExtra(EXTRA_REASON) ?: REASON_LOCKED
        val label = runCatching {
            packageManager.getApplicationLabel(packageManager.getApplicationInfo(pkg, 0)).toString()
        }.getOrDefault(pkg)

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = goHome()
        })

        setContent {
            MaterialTheme { BlockScreen(label, reason, ::goHome) }
        }
    }

    private fun goHome() {
        startActivity(
            Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_HOME)
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        finish()
    }
}

@Composable
private fun BlockScreen(label: String, reason: String, onHome: () -> Unit) {
    Surface(Modifier.fillMaxSize(), color = MaterialTheme.colorScheme.primaryContainer) {
        Column(
            Modifier.fillMaxSize().padding(32.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("🔒", fontSize = 64.sp)
            Spacer(Modifier.height(16.dp))
            Text(label, fontSize = 24.sp, fontWeight = FontWeight.Bold)
            Spacer(Modifier.height(8.dp))
            Text(
                if (reason == BlockActivity.REASON_LIMIT) "오늘 사용 시간을 다 썼어요.\n내일 다시 만나요."
                else "지금은 잠겨 있는 앱이에요.",
                textAlign = TextAlign.Center
            )
            Spacer(Modifier.height(32.dp))
            Button(onClick = onHome) { Text("홈으로") }
        }
    }
}
