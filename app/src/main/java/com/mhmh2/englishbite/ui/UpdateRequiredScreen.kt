package com.mhmh2.englishbite.ui

import android.content.Intent
import android.net.Uri
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp

private const val PACKAGE = "com.mhmh2.englishbite"

/** Shown instead of the whole app when this build is older than the config file's
 * min_version_code - i.e. the server has changed in a way this version can't cope with. */
@Composable
fun UpdateRequiredScreen() {
    val context = LocalContext.current
    Column(
        modifier = Modifier.fillMaxSize().padding(32.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("업데이트가 필요해요", style = MaterialTheme.typography.headlineMedium, textAlign = TextAlign.Center)
        Spacer(Modifier.height(12.dp))
        Text(
            "새 버전에서만 사용할 수 있는 기능이 추가되었어요. 업데이트한 뒤에 다시 이용해주세요.",
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center
        )
        Spacer(Modifier.height(32.dp))
        Button(
            onClick = {
                val market = Intent(Intent.ACTION_VIEW, Uri.parse("market://details?id=$PACKAGE"))
                val web = Intent(Intent.ACTION_VIEW, Uri.parse("https://play.google.com/store/apps/details?id=$PACKAGE"))
                runCatching { context.startActivity(market) }.onFailure { runCatching { context.startActivity(web) } }
            },
            modifier = Modifier.fillMaxWidth().height(48.dp)
        ) {
            Text("Play 스토어에서 업데이트")
        }
    }
}
