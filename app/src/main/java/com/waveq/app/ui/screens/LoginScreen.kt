package com.waveq.app.ui.screens

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.waveq.app.ui.components.*
import com.waveq.app.ui.theme.*

@Composable
fun LoginScreen(
    onSignIn: (email: String, password: String) -> Unit,
    onDemoCitizen: () -> Unit,
    onDemoOperator: () -> Unit,
    onForgotPassword: () -> Unit = {},
) {
    var tab by remember { mutableIntStateOf(0) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var remember by remember { mutableStateOf(false) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(
                Brush.verticalGradient(
                    listOf(Color(0xFFFDF2F2), AppBackground),
                ),
            )
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(48.dp))
        AlertLogo(size = 56.dp)
        Spacer(Modifier.height(16.dp))
        Text("Disaster Reporting", style = AppTypography.headlineSmall, color = BrandRed)
        Spacer(Modifier.height(6.dp))
        Text(
            "Emergency response platform",
            style = AppTypography.bodyMedium,
            color = TextSecondary,
        )
        Spacer(Modifier.height(24.dp))

        AppCard(modifier = Modifier.fillMaxWidth()) {
            Column(Modifier.padding(Dimens.cardPadding)) {
                SegmentedTabs(
                    options = listOf("Login", "Sign Up"),
                    selectedIndex = tab,
                    onSelect = { tab = it },
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(Dimens.sectionSpacing))

                LabeledField(
                    label = "Email Address",
                    value = email,
                    onValueChange = { email = it },
                    placeholder = "you@example.com",
                )
                Spacer(Modifier.height(Dimens.cardSpacing))

                LabeledField(
                    label = "Password",
                    value = password,
                    onValueChange = { password = it },
                    placeholder = "Enter your password",
                    visualTransformation = if (showPassword) VisualTransformation.None
                    else PasswordVisualTransformation(),
                    trailingIcon = {
                        IconButton(onClick = { showPassword = !showPassword }) {
                            Icon(
                                if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                                contentDescription = if (showPassword) "Hide password" else "Show password",
                                tint = TextSecondary,
                            )
                        }
                    },
                )

                if (tab == 1) {
                    Spacer(Modifier.height(Dimens.cardSpacing))
                    LabeledField(
                        label = "Confirm Password",
                        value = confirm,
                        onValueChange = { confirm = it },
                        placeholder = "Re-enter your password",
                        visualTransformation = PasswordVisualTransformation(),
                    )
                }

                Spacer(Modifier.height(Dimens.cardSpacing))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(
                        checked = remember,
                        onCheckedChange = { remember = it },
                        colors = CheckboxDefaults.colors(checkedColor = BrandRed),
                    )
                    Text("Remember me", style = AppTypography.bodyMedium, color = TextPrimary)
                    Spacer(Modifier.weight(1f))
                    Text(
                        "Forgot password?",
                        style = AppTypography.bodyMedium,
                        color = BrandRed,
                        modifier = Modifier.clickable(onClick = onForgotPassword),
                    )
                }

                Spacer(Modifier.height(Dimens.sectionSpacing))
                PrimaryButton(
                    text = if (tab == 0) "Sign In" else "Create Account",
                    onClick = { onSignIn(email, password) },
                )
            }
        }

        Spacer(Modifier.height(Dimens.sectionSpacing))

        GradientPanel(modifier = Modifier.fillMaxWidth()) {
            Text("Quick Demo Access", style = AppTypography.titleMedium, color = Color.White)
            Spacer(Modifier.height(10.dp))
            Text(
                "Try the system instantly",
                style = AppTypography.bodyMedium,
                color = Color.White.copy(alpha = 0.92f),
            )
            Spacer(Modifier.height(Dimens.sectionSpacing))
            OutlineOnColorButton("Demo as Citizen", onDemoCitizen, leadingIcon = Icons.Filled.Person)
            Spacer(Modifier.height(10.dp))
            OutlineOnColorButton("Demo as Operator", onDemoOperator, leadingIcon = Icons.Filled.Shield)
        }

        Spacer(Modifier.height(32.dp))
    }
}