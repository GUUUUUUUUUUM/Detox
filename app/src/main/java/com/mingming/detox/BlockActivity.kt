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
        const val EXTRA_LABEL = "label"
        const val EXTRA_GO_HOME = "go_home"
        const val EXTRA_REASON = "reason"
        const val REASON_LOCKED = "locked"
        const val REASON_LIMIT = "limit"
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val label = intent.getStringExtra(EXTRA_LABEL) ?: ""
        val reason = intent.getStringExtra(EXTRA_REASON) ?: REASON_LOCKED
        val goHome = intent.getBooleanExtra(EXTRA_GO_HOME, true)
        val leave: () -> Unit = if (goHome) ::goHome else ::finish

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = leave()
        })

        setContent {
            MaterialTheme { BlockScreen(label, reason, if (goHome) "홈으로" else "돌아가기", leave) }
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
private fun BlockScreen(label: String, reason: String, buttonText: String, onLeave: () -> Unit) {
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
            Button(onClick = onLeave) { Text(buttonText) }
        }
    }
}
