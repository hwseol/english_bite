package com.mhmh2.englishbite

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.Scaffold
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import com.mhmh2.englishbite.ui.HomeScreen
import com.mhmh2.englishbite.ui.StudyScreen
import com.mhmh2.englishbite.ui.StudyViewModel
import com.mhmh2.englishbite.ui.UiState
import com.mhmh2.englishbite.ui.theme.EnglishBiteTheme

class MainActivity : ComponentActivity() {
    private val viewModel: StudyViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            EnglishBiteTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    val state by viewModel.uiState.collectAsState()

                    when (val current = state) {
                        is UiState.Success -> StudyScreen(
                            result = current.result,
                            onBack = { viewModel.reset() }
                        )
                        else -> HomeScreen(
                            isLoading = state is UiState.Loading,
                            errorMessage = (state as? UiState.Error)?.message,
                            onSubmit = { url -> viewModel.submitUrl(url) }
                        )
                    }
                }
            }
        }
    }
}
