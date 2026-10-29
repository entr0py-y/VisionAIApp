package com.visionaid.app.ui.auth

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.visionaid.app.ui.home.HomeUiState
import com.visionaid.app.ui.theme.Shapes

@Composable
fun AuthModal(
    uiState: HomeUiState,
    onDismiss: () -> Unit,
    onLogin: (String, String) -> Unit,
    onSignup: (String, String) -> Unit
) {
    var isLoginTab by remember { mutableStateOf(true) }
    var username by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }

    Dialog(onDismissRequest = onDismiss) {
        Card(
            shape = Shapes.medium,
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                // Tabs
                TabRow(selectedTabIndex = if (isLoginTab) 0 else 1) {
                    Tab(selected = isLoginTab, onClick = { isLoginTab = true }) {
                        Text("Sign In", modifier = Modifier.padding(16.dp))
                    }
                    Tab(selected = !isLoginTab, onClick = { isLoginTab = false }) {
                        Text("Sign Up", modifier = Modifier.padding(16.dp))
                    }
                }

                Spacer(modifier = Modifier.height(24.dp))

                OutlinedTextField(
                    value = username,
                    onValueChange = { username = it },
                    label = { Text("Username") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                
                Spacer(modifier = Modifier.height(16.dp))

                OutlinedTextField(
                    value = password,
                    onValueChange = { password = it },
                    label = { Text("Password") },
                    visualTransformation = PasswordVisualTransformation(),
                    keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password),
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )

                uiState.authError?.let { error ->
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(error, color = MaterialTheme.colorScheme.error, fontSize = 14.sp)
                }

                Spacer(modifier = Modifier.height(24.dp))

                Button(
                    onClick = {
                        if (isLoginTab) onLogin(username, password) else onSignup(username, password)
                    },
                    modifier = Modifier.fillMaxWidth(),
                    enabled = !uiState.isLoading && username.isNotBlank() && password.isNotBlank()
                ) {
                    if (uiState.isLoading) {
                        CircularProgressIndicator(modifier = Modifier.size(24.dp), color = MaterialTheme.colorScheme.onPrimary)
                    } else {
                        Text(if (isLoginTab) "Sign In" else "Create Account")
                    }
                }
            }
        }
    }
}
