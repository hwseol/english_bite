package com.mhmh2.englishbite.ui

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp

@Composable
fun HomeScreen(
    isLoading: Boolean,
    errorMessage: String?,
    onSubmit: (String) -> Unit
) {
    var url by remember { mutableStateOf("") }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        verticalArrangement = Arrangement.Center
    ) {
        Text("EnglishBite", style = MaterialTheme.typography.headlineMedium)
        Text(
            "CNN·BBC 유튜브 링크를 붙여넣고 학습을 시작하세요",
            style = MaterialTheme.typography.bodyMedium
        )

        androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 24.dp))

        OutlinedTextField(
            value = url,
            onValueChange = { url = it },
            label = { Text("유튜브 URL") },
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
        )

        androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 12.dp))

        Button(
            onClick = { onSubmit(url) },
            enabled = url.isNotBlank() && !isLoading,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (isLoading) "처리 중... (처음 보는 영상은 몇 분 걸릴 수 있어요)" else "학습 시작")
        }

        if (isLoading) {
            androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 16.dp))
            Column(
                modifier = Modifier.fillMaxWidth(),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                CircularProgressIndicator()
            }
        }

        if (errorMessage != null) {
            androidx.compose.foundation.layout.Spacer(Modifier.padding(top = 12.dp))
            Text(
                "오류: $errorMessage",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
