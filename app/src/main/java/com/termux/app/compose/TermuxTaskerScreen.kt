package com.termux.app.compose

import android.content.Context
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.R
import com.termux.shared.logger.Logger
import top.yukonga.miuix.kmp.basic.*
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

@Composable
fun TermuxTaskerScreen(
    onBack: () -> Unit,
    modifier: Modifier = Modifier
) {
    val context = LocalContext.current
    val scrollBehavior = MiuixScrollBehavior()

    var executable by remember { mutableStateOf("") }
    var arguments by remember { mutableStateOf("") }
    var workingDirectory by remember { mutableStateOf("") }
    var inTerminal by remember { mutableStateOf(false) }
    var showExecutableError by remember { mutableStateOf(false) }

    var executableAbsolutePath by remember { mutableStateOf("") }
    var workingDirectoryAbsolutePath by remember { mutableStateOf("") }
    var termuxAccessibleWarning by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        termuxAccessibleWarning = checkTermuxAccessibility(context)
    }

    LaunchedEffect(executable) {
        executableAbsolutePath = if (executable.isNotBlank()) {
            getAbsolutePathForExecutable(context, executable)
        } else ""
        showExecutableError = executable.isBlank()
    }

    LaunchedEffect(workingDirectory) {
        workingDirectoryAbsolutePath = if (workingDirectory.isNotBlank()) {
            getAbsolutePathForDirectory(context, workingDirectory)
        } else ""
    }

    Scaffold(
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {
            TopAppBar(
                title = stringResource(R.string.title_tasker_settings),
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    Box(
                        modifier = Modifier
                            .size(40.dp)
                            .clip(CircleShape)
                            .clickable { onBack() },
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = MiuixIcons.Back,
                            contentDescription = context.getString(R.string.back),
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                actions = {
                    TextButton(
                        text = stringResource(R.string.save),
                        onClick = {
                            onBack()
                        }
                    )
                }
            )
        }
    ) { padding ->
        Box(
            modifier = modifier
                .fillMaxSize()
                .padding(padding)
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = PaddingValues(bottom = 92.dp)
            ) {
                termuxAccessibleWarning?.let { warning ->
                    item {
                        WarningCard(text = warning)
                    }
                }

                item { SmallTitle(text = stringResource(R.string.tasker_executable_header)) }
                item {
                    SettingCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            TextField(
                                value = executable,
                                onValueChange = { executable = it },
                                label = stringResource(R.string.executable_path_hint),
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (showExecutableError) {
                                Text(
                                    text = stringResource(R.string.executable_required),
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.error,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                            if (executableAbsolutePath.isNotBlank()) {
                                Text(
                                    text = executableAbsolutePath,
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }

                item { SmallTitle(text = stringResource(R.string.tasker_arguments_header)) }
                item {
                    SettingCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            TextField(
                                value = arguments,
                                onValueChange = { arguments = it },
                                label = stringResource(R.string.arguments_hint),
                                modifier = Modifier.fillMaxWidth()
                            )
                        }
                    }
                }

                item { SmallTitle(text = stringResource(R.string.tasker_working_directory_header)) }
                item {
                    SettingCard {
                        Column(modifier = Modifier.padding(16.dp)) {
                            TextField(
                                value = workingDirectory,
                                onValueChange = { workingDirectory = it },
                                label = stringResource(R.string.working_directory_path_hint),
                                modifier = Modifier.fillMaxWidth()
                            )
                            if (workingDirectoryAbsolutePath.isNotBlank()) {
                                Text(
                                    text = workingDirectoryAbsolutePath,
                                    fontSize = 12.sp,
                                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                    modifier = Modifier.padding(top = 4.dp)
                                )
                            }
                        }
                    }
                }

                item { SmallTitle(text = stringResource(R.string.tasker_options_header)) }
                item {
                    SettingCard {
                        SwitchPreference(
                            title = stringResource(R.string.execute_in_terminal),
                            summary = null,
                            checked = inTerminal,
                            onCheckedChange = { inTerminal = it },
                            startAction = {
                                SettingIcon(R.drawable.ic_terminal)
                            }
                        )
                    }
                }

                item { Spacer(Modifier.height(16.dp)) }
            }
        }
    }
}

@Composable
private fun WarningCard(text: String) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
    ) {
        Text(
            text = text,
            fontSize = 13.sp,
            color = MiuixTheme.colorScheme.error,
            modifier = Modifier.padding(16.dp)
        )
    }
}

private fun checkTermuxAccessibility(context: Context): String? {
    return try {
        val termuxContext = context.createPackageContext("com.termux", Context.CONTEXT_IGNORE_SECURITY)
        val prefixDir = java.io.File(termuxContext.filesDir, "home")
        val termuxDir = java.io.File(prefixDir, ".termux")
        if (!termuxDir.exists()) {
            context.getString(R.string.termux_app_not_installed_or_disabled_warning)
        } else null
    } catch (_: Exception) {
        context.getString(R.string.termux_app_not_installed_or_disabled_warning)
    }
}

private fun getAbsolutePathForExecutable(context: Context, path: String): String {
    return try {
        val file = java.io.File(path)
        if (file.isAbsolute) {
            file.absolutePath
        } else {
            val taskerDir = java.io.File(context.filesDir, "home/.termux/tasker")
            java.io.File(taskerDir, path).absolutePath
        }
    } catch (_: Exception) {
        path
    }
}

private fun getAbsolutePathForDirectory(context: Context, path: String): String {
    return try {
        val file = java.io.File(path)
        if (file.isAbsolute) {
            file.absolutePath
        } else {
            val homeDir = java.io.File(context.filesDir, "home")
            java.io.File(homeDir, path).absolutePath
        }
    } catch (_: Exception) {
        path
    }
}
