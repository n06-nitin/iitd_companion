package com.xlonlabs.iitdcompanion

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.slideInVertically
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.School
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

@Composable
fun SetupScreen(initial: Creds?, onSaved: (Creds) -> Unit, onCancel: (() -> Unit)?) {
    var kerberos by remember { mutableStateOf(initial?.kerberos ?: "") }
    var password by remember { mutableStateOf(initial?.password ?: "") }
    var show by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    var visible by remember { mutableStateOf(false) }
    LaunchedEffect(Unit) { visible = true }

    Column(
        Modifier
            .fillMaxSize()
            .safeDrawingPadding()
            .imePadding()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 24.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        AnimatedVisibility(
            visible = visible,
            enter = fadeIn(tween(500)) + slideInVertically(tween(500, easing = FastOutSlowInEasing)) { it / 8 }
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                Box(
                    Modifier.size(84.dp).clay(radius = 26.dp, depth = 12.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(Icons.Rounded.School, contentDescription = null, tint = Bone, modifier = Modifier.size(42.dp))
                }
                Spacer(Modifier.height(24.dp))
                Text(
                    "IITD Companion", color = Bone, fontSize = 34.sp, lineHeight = 38.sp,
                    fontFamily = Display, fontWeight = FontWeight.ExtraBold, textAlign = TextAlign.Center
                )
                Spacer(Modifier.height(6.dp))
                Text("One login for all your portals", color = Muted, fontSize = 15.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(14.dp))
                PortalDots()
                Spacer(Modifier.height(28.dp))

                ClayCard {
                    Field(
                        value = kerberos,
                        onValueChange = { kerberos = it; error = null },
                        label = "Kerberos ID",
                        placeholder = "e.g. me2240525",
                        keyboardType = KeyboardType.Ascii
                    )
                    Spacer(Modifier.height(14.dp))
                    Field(
                        value = password,
                        onValueChange = { password = it; error = null },
                        label = "Password",
                        placeholder = "Kerberos password",
                        keyboardType = KeyboardType.Password,
                        visual = if (show) VisualTransformation.None else PasswordVisualTransformation('•'),
                        trailing = {
                            Text(
                                if (show) "Hide" else "Show",
                                color = Muted, fontSize = 13.sp, fontWeight = FontWeight.SemiBold,
                                modifier = Modifier
                                    .clip(RoundedCornerShape(8.dp))
                                    .clickableNoRipple { show = !show }
                                    .padding(horizontal = 12.dp, vertical = 8.dp)
                            )
                        }
                    )

                    AnimatedVisibility(error != null) {
                        Text(
                            error ?: "", color = Danger, fontSize = 13.sp,
                            modifier = Modifier.padding(top = 12.dp, start = 4.dp)
                        )
                    }

                    Spacer(Modifier.height(22.dp))
                    ClayButton(
                        text = "Save & continue",
                        color = Bone,
                        contentColor = Ink,
                        modifier = Modifier.fillMaxWidth()
                    ) {
                        if (kerberos.isBlank() || password.isBlank()) {
                            error = "Enter your Kerberos ID and password."
                        } else onSaved(Creds(kerberos.trim(), password))
                    }
                    if (onCancel != null) {
                        Spacer(Modifier.height(8.dp))
                        Text(
                            "Cancel", color = Muted, fontSize = 14.sp,
                            modifier = Modifier
                                .align(Alignment.CenterHorizontally)
                                .clip(RoundedCornerShape(8.dp))
                                .clickableNoRipple { onCancel() }
                                .padding(10.dp)
                        )
                    }
                }
                Spacer(Modifier.height(22.dp))
                Text("Saved only on this phone", color = Muted, fontSize = 12.sp, textAlign = TextAlign.Center)
                Spacer(Modifier.height(4.dp))
                Text("Unofficial · not affiliated with IIT Delhi", color = Faint, fontSize = 11.sp,
                    textAlign = TextAlign.Center)
            }
        }
    }
}

/** The four portals, each in its own hue — the same colours the tab dock uses. */
@Composable
private fun PortalDots() {
    Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
        Portal.entries.forEach { p ->
            Box(Modifier.size(8.dp).clip(CircleShape).clay(p.accent, radius = 4.dp, depth = 0.dp))
        }
    }
}
