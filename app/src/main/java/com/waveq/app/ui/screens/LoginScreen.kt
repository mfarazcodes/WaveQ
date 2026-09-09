package com.waveq.app.ui.screens

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.Shield
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.waveq.app.R
import com.waveq.app.auth.UserRole
import com.waveq.app.ui.components.*
import com.waveq.app.ui.theme.*

@Composable
fun LoginScreen(
    onSignIn: (email: String, password: String, role: UserRole) -> Unit,
    onDemoCitizen: () -> Unit,
    onDemoOperator: () -> Unit,
    onForgotPassword: () -> Unit = {},
) {
    var tab by remember { mutableIntStateOf(0) }
    var email by remember { mutableStateOf("") }
    var password by remember { mutableStateOf("") }
    var confirm by remember { mutableStateOf("") }
    var showPassword by remember { mutableStateOf(false) }
    var selectedRole by remember { mutableStateOf(UserRole.CITIZEN) }

    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = Dimens.screenPadding),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Spacer(Modifier.height(Dimens.heroSpacing))
        AlertLogo(size = 52.dp)
        Spacer(Modifier.height(Dimens.sectionSpacing))
        Text(
            stringResource(R.string.app_name),
            style = AppTypography.headlineSmall,
            color = MaterialTheme.colorScheme.onSurface,
        )
        Spacer(Modifier.height(8.dp))
        Text(
            "Emergency response platform",
            style = AppTypography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
        )

        Spacer(Modifier.height(Dimens.heroSpacing))
        SegmentedTabs(
            options = listOf("Login", "Sign Up"),
            selectedIndex = tab,
            onSelect = { tab = it },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(Dimens.sectionSpacing))
        LabeledField(
            label = "Email",
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
            visualTransformation = if (showPassword) VisualTransformation.None else PasswordVisualTransformation(),
            trailingIcon = {
                IconButton(onClick = { showPassword = !showPassword }) {
                    Icon(
                        if (showPassword) Icons.Filled.VisibilityOff else Icons.Filled.Visibility,
                        contentDescription = if (showPassword) "Hide password" else "Show password",
                        tint = MaterialTheme.appExtraColors.textTertiary,
                    )
                }
            },
        )
        if (tab == 1) {
            Spacer(Modifier.height(Dimens.cardSpacing))
            LabeledField(
                label = "Confirm password",
                value = confirm,
                onValueChange = { confirm = it },
                placeholder = "Re-enter your password",
                visualTransformation = PasswordVisualTransformation(),
            )
        }

        Spacer(Modifier.height(Dimens.sectionSpacing))
        MicroLabel("Demo role", modifier = Modifier.align(Alignment.Start))
        Spacer(Modifier.height(Dimens.fieldSpacing))
        SegmentedTabs(
            options = UserRole.entries.map { it.label },
            selectedIndex = UserRole.entries.indexOf(selectedRole),
            onSelect = { selectedRole = UserRole.entries[it] },
            modifier = Modifier.fillMaxWidth(),
        )

        Spacer(Modifier.height(Dimens.sectionSpacing))
        PrimaryButton(
            text = if (tab == 0) "Sign in" else "Create account",
            onClick = { onSignIn(email, password, selectedRole) },
        )
        Spacer(Modifier.height(Dimens.cardSpacing))
        Text(
            "Forgot password?",
            style = AppTypography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.clickable(onClick = onForgotPassword),
        )

        Spacer(Modifier.height(Dimens.sectionSpacing))
        MicroLabel("Or try a demo", modifier = Modifier.align(Alignment.Start))
        Spacer(Modifier.height(Dimens.cardSpacing))
        SecondaryButton("Demo as citizen", onDemoCitizen, leadingIcon = Icons.Filled.Person)
        Spacer(Modifier.height(Dimens.cardSpacing))
        SecondaryButton("Demo as operator", onDemoOperator, leadingIcon = Icons.Filled.Shield)

        Spacer(Modifier.height(Dimens.sectionSpacing))
    }
}
