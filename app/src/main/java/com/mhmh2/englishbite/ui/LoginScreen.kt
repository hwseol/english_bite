package com.mhmh2.englishbite.ui

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
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
import androidx.compose.material3.AlertDialog
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
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.mhmh2.englishbite.BuildConfig

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
            LoggedInView(
                nickname = uiState.nickname.orEmpty(),
                email = uiState.email.orEmpty(),
                uiState = uiState,
                onLogout = viewModel::logout,
                onDelete = viewModel::deleteAccount,
                onChangePassword = viewModel::changePassword,
                onClearError = viewModel::clearError
            )
        } else {
            AuthForm(viewModel = viewModel, uiState = uiState)
        }

        // Always available, logged in or not - the one place testers (and later users) can tell
        // the developer something is wrong. Opens the mail app with the app/device details
        // already filled in, so "it didn't work" arrives with the information needed to look.
        Spacer(Modifier.height(24.dp))
        val context = LocalContext.current
        TextButton(
            onClick = { openFeedbackMail(context) },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("문의 · 의견 보내기")
        }
        Spacer(Modifier.height(24.dp))
    }
}

@Composable
private fun LoggedInView(
    nickname: String,
    email: String,
    uiState: AuthUiState,
    onLogout: () -> Unit,
    onDelete: (String) -> Unit,
    onChangePassword: (String, String) -> Unit,
    onClearError: () -> Unit
) {
    var showChangeDialog by remember { mutableStateOf(false) }
    var oldPassword by remember { mutableStateOf("") }
    var newPassword by remember { mutableStateOf("") }
    var newPasswordConfirm by remember { mutableStateOf("") }
    val newPasswordsMatch = newPassword == newPasswordConfirm

    // Close the dialog once the change went through (the view model sets the notice then).
    LaunchedEffect(uiState.notice) {
        if (uiState.notice != null) {
            showChangeDialog = false
            oldPassword = ""
            newPassword = ""
            newPasswordConfirm = ""
        }
    }

    if (showChangeDialog) {
        val closeChange = {
            showChangeDialog = false
            oldPassword = ""
            newPassword = ""
            newPasswordConfirm = ""
            onClearError()
        }
        AlertDialog(
            onDismissRequest = closeChange,
            title = { Text("비밀번호 변경") },
            text = {
                Column {
                    OutlinedTextField(
                        value = oldPassword,
                        onValueChange = { oldPassword = it },
                        label = { Text("현재 비밀번호") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newPassword,
                        onValueChange = { newPassword = it },
                        label = { Text("새 비밀번호 (8자 이상)") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = newPasswordConfirm,
                        onValueChange = { newPasswordConfirm = it },
                        label = { Text("새 비밀번호 확인") },
                        singleLine = true,
                        isError = newPasswordConfirm.isNotEmpty() && !newPasswordsMatch,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    val message = when {
                        uiState.error != null -> uiState.error
                        newPasswordConfirm.isNotEmpty() && !newPasswordsMatch -> "새 비밀번호가 일치하지 않아요."
                        else -> null
                    }
                    message?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onChangePassword(oldPassword, newPassword) },
                    enabled = !uiState.isLoading && oldPassword.isNotBlank() &&
                        newPassword.isNotBlank() && newPasswordsMatch
                ) { Text("변경") }
            },
            dismissButton = { TextButton(onClick = closeChange) { Text("취소") } }
        )
    }

    var showDeleteDialog by remember { mutableStateOf(false) }
    var deletePassword by remember { mutableStateOf("") }

    if (showDeleteDialog) {
        AlertDialog(
            onDismissRequest = {
                showDeleteDialog = false
                deletePassword = ""
                onClearError()
            },
            title = { Text("회원 탈퇴") },
            text = {
                Column {
                    Text("계정이 영구적으로 삭제되며 되돌릴 수 없어요. 계속하려면 비밀번호를 입력해주세요.")
                    Spacer(Modifier.height(12.dp))
                    OutlinedTextField(
                        value = deletePassword,
                        onValueChange = { deletePassword = it },
                        label = { Text("비밀번호") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                        modifier = Modifier.fillMaxWidth()
                    )
                    uiState.error?.let {
                        Spacer(Modifier.height(8.dp))
                        Text(it, color = MaterialTheme.colorScheme.error, style = MaterialTheme.typography.bodySmall)
                    }
                }
            },
            confirmButton = {
                TextButton(
                    onClick = { onDelete(deletePassword) },
                    enabled = !uiState.isLoading && deletePassword.isNotBlank()
                ) {
                    Text("탈퇴", color = MaterialTheme.colorScheme.error)
                }
            },
            dismissButton = {
                TextButton(onClick = {
                    showDeleteDialog = false
                    deletePassword = ""
                    onClearError()
                }) { Text("취소") }
            }
        )
    }

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
    uiState.notice?.let {
        Spacer(Modifier.height(12.dp))
        Text(it, color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.bodyMedium)
    }
    Spacer(Modifier.height(24.dp))
    OutlinedButton(onClick = { showChangeDialog = true }, modifier = Modifier.fillMaxWidth()) {
        Text("비밀번호 변경")
    }
    Spacer(Modifier.height(8.dp))
    OutlinedButton(onClick = onLogout, modifier = Modifier.fillMaxWidth()) {
        Text("로그아웃")
    }
    Spacer(Modifier.height(8.dp))
    TextButton(onClick = { showDeleteDialog = true }, modifier = Modifier.fillMaxWidth()) {
        Text("회원 탈퇴", color = MaterialTheme.colorScheme.error)
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
        text = "로그인하지 않아도 모든 기능을 그대로 쓸 수 있어요. 계정을 만들어 두면 " +
            "앞으로 추가될 기기 간 단어장 동기화를 바로 쓸 수 있어요.",
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

    if (!isSignupMode) {
        val context = LocalContext.current
        TextButton(
            onClick = {
                // No mail service behind the server, so recovery goes through the developer:
                // this opens the user's mail app with the request already addressed.
                val intent = Intent(Intent.ACTION_SENDTO).apply {
                    data = Uri.parse("mailto:")
                    putExtra(Intent.EXTRA_EMAIL, arrayOf("mhmh2090@gmail.com"))
                    putExtra(Intent.EXTRA_SUBJECT, "[EnglishBite] 비밀번호 재설정 요청")
                    putExtra(
                        Intent.EXTRA_TEXT,
                        "가입한 이메일 주소: ${email.trim()}\n\n비밀번호를 잊어버려서 재설정을 요청합니다. " +
                            "(이 메일을 보내신 주소가 가입한 이메일과 같아야 처리돼요.)"
                    )
                }
                runCatching { context.startActivity(intent) }
            },
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("비밀번호를 잊으셨나요?")
        }
    }

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

private fun openFeedbackMail(context: Context) {
    val intent = Intent(Intent.ACTION_SENDTO).apply {
        data = Uri.parse("mailto:")
        putExtra(Intent.EXTRA_EMAIL, arrayOf("mhmh2090@gmail.com"))
        putExtra(Intent.EXTRA_SUBJECT, "[EnglishBite] 문의 · 의견")
        putExtra(
            Intent.EXTRA_TEXT,
            "\n\n---\n(아래는 문제를 찾는 데 필요한 정보예요. 지우지 말아주세요)\n" +
                "앱 버전: ${BuildConfig.VERSION_NAME} (${BuildConfig.VERSION_CODE})\n" +
                "기기: ${Build.MANUFACTURER} ${Build.MODEL} / Android ${Build.VERSION.RELEASE}\n"
        )
    }
    runCatching { context.startActivity(intent) }
}
