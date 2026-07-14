package com.termux.app.compose

import androidx.activity.compose.BackHandler
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.TopAppBar
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.CircularProgressIndicator
import com.termux.R

@Composable
fun OobeScreen(
    permissionStatus: String,
    isNextEnabled: Boolean,
    isBootstrapping: Boolean,
    onGrantAllPermissions: () -> Unit,
    onComplete: () -> Unit
) {
    BackHandler {
    }

    Scaffold(
        topBar = {
            TopAppBar(title = stringResource(R.string.oobe_title))
        }
    ) { padding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(padding),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Image(
                painter = painterResource(R.drawable.ic_foreground),
                contentDescription = stringResource(R.string.app_name),
                modifier = Modifier.size(120.dp),
                colorFilter = androidx.compose.ui.graphics.ColorFilter.tint(Color.Black)
            )

            Text(
                text = stringResource(R.string.oobe_description),
                fontSize = 16.sp,
                color = Color.Gray,
                textAlign = TextAlign.Center,
                modifier = Modifier.padding(top = 32.dp, bottom = 48.dp)
            )

            Card(
                modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                        .padding(bottom = 24.dp)
            ) {
                Text(
                    text = permissionStatus,
                    fontSize = 14.sp,
                    color = Color.Gray,
                    textAlign = TextAlign.Center,
                    modifier = Modifier.padding(16.dp)
                )
            }

            if (isBootstrapping) {
                Column(
                    horizontalAlignment = Alignment.CenterHorizontally,
                    modifier = Modifier.padding(bottom = 16.dp)
                ) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(48.dp)
                    )
                    Text(
                        text = stringResource(R.string.bootstrap_installer_body),
                        fontSize = 14.sp,
                        color = Color.Gray,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(top = 16.dp)
                    )
                }
            } else {
                Button(
                    onClick = onGrantAllPermissions,
                    modifier = Modifier
                            .fillMaxWidth()
                            .padding(horizontal = 24.dp)
                            .padding(bottom = 16.dp)
                ) {
                    Text(stringResource(R.string.oobe_grant_permissions))
                }

                Button(
                    onClick = onComplete,
                    enabled = isNextEnabled,
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 24.dp)
                ) {
                    Text(stringResource(R.string.oobe_next))
                }
            }
        }
    }
}