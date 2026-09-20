package com.mhmh2.englishbite.ui

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.AccountCircle
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel

/** Reached only from the catalog's account icon - never shown automatically, since every
 * feature in the app already works without an account. Google Sign-In isn't wired up yet (it
 * needs an OAuth client ID set up in Google Cloud Console first); this is email/password +
 * nickname only for now. */
@Composable
fun LoginScreen(
    onBack: () -> Unit,
    viewModel: AuthViewModel = viewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .statusBarsPadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            IconButton(onClick = onBack) {
                Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "닫기")
            }
        }

        Spacer(Modifier.height(16.dp))

        // Showing the login form again to someone already logged in, and quietly bouncing them
        // straight back out the moment the screen noticed uiState.isLoggedIn was already true,
        // is what "pressing the account icon does nothing" turned out to be - fixed by giving
        // "already logged in" its own view instead of ever auto-navigating off this screen.
        if (uiState.isLoggedIn) {
            LoggedInView(nickname = uiState.nickname.orEmpty(), email = uiState.email.orEmpty(), onLogout = viewModel::logout)
        } else {
            AuthForm(viewModel = viewModel, uiState = uiState)
        }
    }
}

@Composable
private fun LoggedInView(nickname: String, email: String, onLogout: () -> Unit) {
    Icon(
        imageVector = Icons.Default.AccountCircle,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        modifier = Modifier.size(56.dp)
    )
    Spacer(Modifier.height(12.dp))
    Text(text = nickname, style = MaterialTheme.typography.headlineSmall)
    Spacer(Modifier.height(4.dp))
    Text(text = email, style = MaterialTheme.typography.bodyMedium, color = MaterialTheme.colorScheme.onSurfaceVariant)
    Spacer(Modifier.height(24.dp))
    OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
        Text("로그아웃")
    }
}

@Composable
private fun AuthForm(viewModel: AuthViewModel, uiState: AuthUiState) {
    var isSignupMode by remember { mutableStateOf(false) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var passwordConfirm by remember { mutableStateOf("") }
    var nickname by remember { mutableStateOf("") }

    val passwordsMatch = !isSignupMode || password == passwordConfirm
    val canSubmit = !uiState.isLoading && email.isNotBlank() && password.isNotBlank() &&
        (!isSignupMode || (nickname.isNotBlank() && passwordConfirm.isNotBlank() && passwordsMatch))

    Text(
        text = if (isSignupMode) "회원가입" else "로그인",
        style = MaterialTheme.typography.headlineLarge
    )
    Spacer(Modifier.height(8.dp))
    Text(
        text = "로그인하지 않아도 모든 기능을 그대로 쓸 수 있어요. 로그인하면 여러 " +
            "기기에서 단어장이 동기화돼요.",
        style = MaterialTheme.typography.bodyMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant
    )

    Spacer(Modifier.height(32.dp))

    OutlinedTextField(
        value = email,
        onValueChange = { email = it },
        label = { Text("이메일") },
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email),
        modifier = Modifier.fillMaxWidth()
    )
    Spacer(Modifier.height(12.dp))
    if (isSignupMode) {
        OutlinedTextField(
            value = nickname,
            onValueChange = { nickname = it },
            label = { Text("닉네임") },
            singleLine = true,
            modifier = Modifier.fillMaxWidth()
        )
        Spacer(Modifier.height(12.dp))
    }
    OutlinedTextField(
        value = password,
        onValueChange = { password = it },
        label = { Text("비밀번호") },
        singleLine = true,
        visualTransformation = PasswordVisualTransformation(),
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
        modifier = Modifier.fillMaxWidth()
    )
    if (isSignupMode) {
        Spacer(Modifier.height(12.dp))
        OutlinedTextField(
            value = passwordConfirm,
            onValueChange = { passwordConfirm = it },
            label = { Text("비밀번호 확인") },
            singleLine = true,
            isError = passwordConfirm.isNotEmpty() && !passwordsMatch,
            visualTransformation = PasswordVisualTransformation(),
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
            modifier = Modifier.fillMaxWidth()
        )
        if (passwordConfirm.isNotEmpty() && !passwordsMatch) {
            Spacer(Modifier.height(4.dp))
            Text(
                text = "비밀번호가 일치하지 않아요.",
                color = MaterialTheme.colorScheme.error,
                style = MaterialTheme.typography.bodySmall
            )
        }
    }

    uiState.error?.let { error ->
        Spacer(Modifier.height(12.dp))
        Text(
            text = error,
            color = MaterialTheme.colorScheme.error,
            style = MaterialTheme.typography.bodySmall
        )
    }

    Spacer(Modifier.height(24.dp))

    Button(
        onClick = {
            if (isSignupMode) viewModel.signup(email, nickname, password)
            else viewModel.login(email, password)
        },
        enabled = canSubmit,
        modifier = Modifier.fillMaxWidth().height(48.dp)
    ) {
        if (uiState.isLoading) {
            CircularProgressIndicator(
                modifier = Modifier.size(20.dp),
                color = MaterialTheme.colorScheme.onPrimary
            )
        } else {
            Text(if (isSignupMode) "회원가입" else "로그인")
        }
    }

    Spacer(Modifier.height(12.dp))

    TextButton(
        onClick = {
            isSignupMode = !isSignupMode
            viewModel.clearError()
        },
        modifier = Modifier.fillMaxWidth()
    ) {
        Text(if (isSignupMode) "이미 계정이 있으신가요? 로그인" else "계정이 없으신가요? 회원가입")
    }
}
