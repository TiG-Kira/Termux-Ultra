package com.termux.app.compose

import android.content.Context
import android.content.Intent
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.*
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.R
import com.google.android.material.snackbar.Snackbar
import com.termux.app.compose.launchBiometricAuth
import com.termux.app.utils.SnackbarHelper
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SnackbarHost
import top.yukonga.miuix.kmp.basic.SnackbarHostState
import top.yukonga.miuix.kmp.basic.SnackbarDuration
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.overlay.OverlayDialog
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.preference.CheckboxPreference
import top.yukonga.miuix.kmp.preference.SwitchPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme
import java.io.File

/**
 * 「开发者模式」设置页（独立 Activity）。
 *
 * 归类自原 SettingsScreen 的 Termux Agent 分组：开发者模式总开关（重命名为「启用开发者模式」），
 * 以及除完整对话记录外的全部原开发者功能——自定义 System Prompt、自定义技能、无限制模式、Root 自动 su。
 * 所有 OverlayDialog 均置于 Scaffold 内部，确保正常显示。
 */
@Composable
fun AiTermuxDeveloperScreen(onBack: () -> Unit) {
    val context = LocalContext.current
    val glassPage = rememberGlassPageBackdrop()
    val scrollBehavior = MiuixScrollBehavior()
    val snackbarHostState = remember { SnackbarHostState() }
    val scope = rememberCoroutineScope()

    fun showSnackbar(message: String, isLong: Boolean = false) {
        scope.launch {
            snackbarHostState.showSnackbar(
                message = message,
                duration = if (isLong) SnackbarDuration.Long else SnackbarDuration.Short
            )
        }
    }

    var aiDeveloperMode by remember { mutableStateOf(AiTermuxPrefs.isDeveloperMode(context)) }
    var useCustomSystemPrompt by remember { mutableStateOf(AiTermuxPrefs.isUsingCustomSystemPrompt(context)) }
    var systemPromptSource by remember { mutableStateOf("") }
    var unlimitedMode by remember { mutableStateOf(AiTermuxPrefs.isUnlimitedMode(context)) }
    var rootAutoShell by remember { mutableStateOf(AiTermuxPrefs.isRootAutoShell(context)) }

    var showSystemPromptEditor by remember { mutableStateOf(false) }
    var showSystemPromptFilePicker by remember { mutableStateOf(false) }
    var showSystemPromptRestoreConfirm by remember { mutableStateOf(false) }
    var showInternalPromptPicker by remember { mutableStateOf(false) }
    var showCustomSkillManager by remember { mutableStateOf(false) }
    var showAddEditSkillDialog by remember { mutableStateOf(false) }
    var editingSkill by remember { mutableStateOf<CustomSkill?>(null) }
    var skillsRefreshKey by remember { mutableStateOf(0) }
    var showUnlimitedModeConfirm by remember { mutableStateOf(false) }

    // 系统文件选择器：从外部选取自定义 System Prompt 文件
    val systemPromptFileLauncher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let {
            try {
                val inputStream = context.contentResolver.openInputStream(uri)
                val content = inputStream?.bufferedReader()?.use { it.readText() } ?: ""
                if (content.isNotBlank()) {
                    AiTermuxPrefs.setCustomSystemPrompt(context, content)
                    AiTermuxPrefs.setUseCustomSystemPrompt(context, true)
                    useCustomSystemPrompt = true
                    val fileName = uri.lastPathSegment?.substringAfterLast("/") ?: "custom_prompt.md"
                    systemPromptSource = fileName
                    showSnackbar(context.getString(R.string.custom_prompt_loaded))
                } else {
                    showSnackbar(context.getString(R.string.file_empty))
                }
            } catch (e: Exception) {
                showSnackbar(context.getString(R.string.read_file_failed, e.message))
            }
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0, 0, 0, 0),
        topBar = {
            GlassTopAppBar(
                title = stringResource(R.string.developer_settings_title),
                backdrop = glassPage.backdrop,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    GlassIconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixGlassIcons.ChevronBackward,
                            contentDescription = stringResource(R.string.back),
                            tint = MiuixTheme.colorScheme.onSurface,
                            modifier = Modifier.height(24.dp)
                        )
                    }
                }
            )
        }
    ) { padding ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .then(glassPage.contentModifier)
                .padding(pagePaddingWithoutTop(padding))
        ) {
            LazyColumn(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = standaloneContentPadding(padding, bottom = 16.dp)
            ) {
                item(key = "card_developer_switch") {
                    SettingCard {
                        SwitchPreference(
                            title = stringResource(R.string.enable_developer_mode),
                            summary = stringResource(R.string.developer_mode_desc),
                            checked = aiDeveloperMode,
                            onCheckedChange = {
                                aiDeveloperMode = it
                                AiTermuxPrefs.setDeveloperMode(context, it)
                            },
                            startAction = {
                                SettingIcon(Icons.Rounded.Build, stringResource(R.string.enable_developer_mode))
                            }
                        )
                    }
                }

                if (aiDeveloperMode) {
                    item(key = "card_developer_features") {
                        SettingCard {
                            Column {
                                // ---------- 自定义 System Prompt ----------
                                if (useCustomSystemPrompt) {
                                    ArrowPreference(
                                        title = stringResource(R.string.use_official_prompt),
                                        summary = stringResource(
                                            R.string.currently_using_source,
                                            systemPromptSource.ifBlank { stringResource(R.string.custom_file) }
                                        ),
                                        onClick = { showSystemPromptRestoreConfirm = true },
                                        startAction = {
                                            SettingIcon(Icons.Rounded.Restore, stringResource(R.string.use_official_prompt))
                                        }
                                    )
                                } else {
                                    ArrowPreference(
                                        title = stringResource(R.string.use_custom_prompt),
                                        summary = stringResource(R.string.load_prompt_from_file),
                                        onClick = { showSystemPromptFilePicker = true },
                                        startAction = {
                                            SettingIcon(Icons.Rounded.Edit, stringResource(R.string.use_custom_prompt))
                                        }
                                    )
                                }
                                ArrowPreference(
                                    title = stringResource(R.string.custom_skills),
                                    summary = stringResource(R.string.custom_skill_create_manage),
                                    onClick = { showCustomSkillManager = true },
                                    startAction = {
                                        SettingIcon(Icons.Rounded.Code, stringResource(R.string.custom_skills))
                                    }
                                )

                                // ---------- 无限制模式 ----------
                                SwitchPreference(
                                    title = stringResource(R.string.unrestricted_mode),
                                    summary = if (unlimitedMode) {
                                        stringResource(R.string.fallback_enabled_desc)
                                    } else {
                                        stringResource(R.string.unrestricted_mode_desc)
                                    },
                                    checked = unlimitedMode,
                                    onCheckedChange = { newValue ->
                                        if (newValue) {
                                            showUnlimitedModeConfirm = true
                                        } else {
                                            unlimitedMode = false
                                            AiTermuxPrefs.setUnlimitedMode(context, false)
                                        }
                                    },
                                    startAction = {
                                        SettingIcon(Icons.Rounded.Shield, stringResource(R.string.unrestricted_mode))
                                    }
                                )
                                if (unlimitedMode) {
                                    SwitchPreference(
                                        title = stringResource(R.string.root_exec_agent),
                                        summary = stringResource(R.string.root_auto_su_desc),
                                        checked = rootAutoShell,
                                        onCheckedChange = {
                                            rootAutoShell = it
                                            AiTermuxPrefs.setRootAutoShell(context, it)
                                        },
                                        startAction = {
                                            SettingIcon(Icons.Rounded.AdminPanelSettings, stringResource(R.string.root_exec_agent))
                                        }
                                    )
                                }
                            }
                        }
                    }
                }
            }

            // ---------- 本页所有 OverlayDialog 必须位于 Scaffold 内部 ----------
            AgentSystemPromptEditorDialog(show = showSystemPromptEditor, onDismiss = { showSystemPromptEditor = false }, context = context)
            AgentSystemPromptFilePickerDialog(
                show = showSystemPromptFilePicker,
                onDismiss = { showSystemPromptFilePicker = false },
                onPickBuiltin = { showSystemPromptFilePicker = false; showInternalPromptPicker = true },
                onPickSystem = { showSystemPromptFilePicker = false; systemPromptFileLauncher.launch(arrayOf("text/markdown", "text/plain", "*/*")) }
            )
            TermuxInternalFilePicker(
                show = showInternalPromptPicker,
                title = stringResource(R.string.select_prompt_file),
                fileExtensions = listOf("md", "txt"),
                onDismiss = { showInternalPromptPicker = false },
                onFileSelected = { path ->
                    showInternalPromptPicker = false
                    try {
                        val file = File(path.replace("\$HOME", TERMUX_HOME_ABS))
                        if (file.exists() && file.isFile) {
                            val content = file.readText()
                            if (content.isNotBlank()) {
                                AiTermuxPrefs.setCustomSystemPrompt(context, content)
                                AiTermuxPrefs.setUseCustomSystemPrompt(context, true)
                                useCustomSystemPrompt = true
                                systemPromptSource = file.name
                                showSnackbar(context.getString(R.string.custom_prompt_loaded))
                            } else {
                                showSnackbar(context.getString(R.string.file_empty))
                            }
                        }
                    } catch (e: Exception) {
                        showSnackbar(context.getString(R.string.read_file_failed, e.message))
                    }
                }
            )
            AgentSystemPromptRestoreDialog(
                show = showSystemPromptRestoreConfirm,
                onDismiss = { showSystemPromptRestoreConfirm = false },
                context = context,
                onRestored = {
                    useCustomSystemPrompt = false
                    systemPromptSource = ""
                },
                showSnackbar = { msg -> showSnackbar(msg) }
            )
            AgentCustomSkillManagerDialog(
                show = showCustomSkillManager,
                onDismiss = { showCustomSkillManager = false },
                context = context,
                onAddEdit = { skill ->
                    editingSkill = skill
                    showAddEditSkillDialog = true
                },
                onDeleted = { skillsRefreshKey++ },
                refreshKey = skillsRefreshKey
            )
            AgentAddEditSkillDialog(
                show = showAddEditSkillDialog,
                onDismiss = { showAddEditSkillDialog = false },
                context = context,
                editingSkill = editingSkill,
                onSaved = { skillsRefreshKey++ }
            )
            AgentUnlimitedModeConfirmDialog(
                show = showUnlimitedModeConfirm,
                onDismiss = { showUnlimitedModeConfirm = false },
                context = context,
                onEnable = {
                    unlimitedMode = true
                    AiTermuxPrefs.setUnlimitedMode(context, true)
                }
            )

            SnackbarHost(state = snackbarHostState)
        }
    }
}

/** 自定义 System Prompt 编辑器。 */
@Composable
private fun AgentSystemPromptEditorDialog(show: Boolean, onDismiss: () -> Unit, context: Context) {
    var systemPromptText by remember(show) { mutableStateOf(AiTermuxPrefs.getConfig(context).customSystemPrompt) }
    OverlayDialog(
        title = stringResource(R.string.edit_system_prompt),
        summary = stringResource(R.string.custom_extra_instructions_desc),
        show = show,
        onDismissRequest = onDismiss,
        content = {
            Box(
                modifier = Modifier
                    .heightIn(max = 400.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                TextField(
                    value = systemPromptText,
                    onValueChange = { systemPromptText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 150.dp),
                    label = stringResource(R.string.custom_prompt_hint),
                    useLabelAsPlaceholder = true,
                    maxLines = Int.MAX_VALUE,
                    minLines = 5
                )
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = stringResource(R.string.reset_default),
                    onClick = {
                        systemPromptText = ""
                        val cfg = AiTermuxPrefs.getConfig(context)
                        AiTermuxPrefs.saveConfig(context, cfg.copy(customSystemPrompt = ""))
                    },
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(16.dp))
                TextButton(
                    text = stringResource(R.string.save),
                    onClick = {
                        val cfg = AiTermuxPrefs.getConfig(context)
                        AiTermuxPrefs.saveConfig(context, cfg.copy(customSystemPrompt = systemPromptText))
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    )
}

/** 自定义 System Prompt 文件来源选择（内置 / 系统选择器）。 */
@Composable
private fun AgentSystemPromptFilePickerDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    onPickBuiltin: () -> Unit,
    onPickSystem: () -> Unit
) {
    OverlayDialog(
        title = stringResource(R.string.select_prompt_file),
        summary = stringResource(R.string.custom_prompt_pick_md),
        show = show,
        onDismissRequest = onDismiss,
        content = {
            Column {
                Text(
                    text = stringResource(R.string.file_picker_choice),
                    style = TextStyle(fontSize = 14.sp)
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    TextButton(
                        text = stringResource(R.string.termux_builtin),
                        onClick = onPickBuiltin,
                        modifier = Modifier.weight(1f)
                    )
                    TextButton(
                        text = stringResource(R.string.system_picker),
                        onClick = onPickSystem,
                        modifier = Modifier.weight(1f)
                    )
                }
            }
        }
    )
}

/** 还原官方 System Prompt 确认。 */
@Composable
private fun AgentSystemPromptRestoreDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    context: Context,
    onRestored: () -> Unit,
    showSnackbar: (String) -> Unit
) {
    OverlayDialog(
        title = stringResource(R.string.restore_official_prompt),
        summary = stringResource(R.string.restore_official_prompt_confirm),
        show = show,
        onDismissRequest = onDismiss,
        content = {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.End
            ) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = onDismiss
                )
                Spacer(Modifier.width(12.dp))
                TextButton(
                    text = stringResource(R.string.confirm_restore),
                    onClick = {
                        AiTermuxPrefs.setUseCustomSystemPrompt(context, false)
                        onRestored()
                        onDismiss()
                        showSnackbar(context.getString(R.string.switched_official_prompt))
                    },
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    )
}

/** 自定义技能管理（列出 / 删除 / 新建）。 */
@Composable
private fun AgentCustomSkillManagerDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    context: Context,
    onAddEdit: (CustomSkill?) -> Unit,
    onDeleted: () -> Unit,
    refreshKey: Int
) {
    OverlayDialog(
        title = stringResource(R.string.custom_skills),
        summary = stringResource(R.string.custom_skill_manage_desc),
        show = show,
        onDismissRequest = onDismiss,
        content = {
            val customSkills = remember(refreshKey) { AiTermuxPrefs.getCustomSkills(context) }
            if (customSkills.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(120.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = stringResource(R.string.no_custom_skills),
                        fontSize = 14.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            } else {
                Box(
                    modifier = Modifier
                        .heightIn(max = 350.dp)
                        .verticalScroll(rememberScrollState())
                ) {
                    Column {
                        customSkills.forEach { skill ->
                            Card(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .padding(vertical = 4.dp)
                            ) {
                                Column(
                                    modifier = Modifier.padding(12.dp)
                                ) {
                                    Text(
                                        text = skill.name,
                                        fontSize = 15.sp,
                                        fontWeight = FontWeight.Bold,
                                        color = MiuixTheme.colorScheme.onSurface
                                    )
                                    if (skill.description.isNotBlank()) {
                                        Text(
                                            text = skill.description,
                                            fontSize = 13.sp,
                                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                                            modifier = Modifier.padding(top = 4.dp)
                                        )
                                    }
                                    Row(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(top = 8.dp),
                                        horizontalArrangement = Arrangement.End
                                    ) {
                                        TextButton(
                                            text = stringResource(R.string.edit),
                                            onClick = { onAddEdit(skill) }
                                        )
                                        Spacer(Modifier.width(8.dp))
                                        TextButton(
                                            text = stringResource(R.string.delete),
                                            onClick = {
                                                AiTermuxPrefs.deleteCustomSkill(context, skill.id)
                                                onDeleted()
                                            }
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = stringResource(R.string.off),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(16.dp))
                TextButton(
                    text = stringResource(R.string.add_skill),
                    onClick = { onAddEdit(null) },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    )
}

/** 添加 / 编辑自定义技能。 */
@Composable
private fun AgentAddEditSkillDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    context: Context,
    editingSkill: CustomSkill?,
    onSaved: () -> Unit
) {
    var skillName by remember { mutableStateOf("") }
    var skillDescription by remember { mutableStateOf("") }
    var skillSystemPrompt by remember { mutableStateOf("") }
    var skillJson by remember { mutableStateOf("") }
    var skillImplementationType by remember { mutableStateOf("shell_command") }

    val implOptions = listOf(
        "shell_command" to stringResource(R.string.shell_command),
        "open_activity" to stringResource(R.string.open_page),
        "send_broadcast" to stringResource(R.string.send_broadcast),
        "custom" to stringResource(R.string.custom)
    )

    val implJsonTemplates = mapOf(
        "shell_command" to """{"skillType":"CUSTOM_COMMAND","params":{"command":"ls -la ~"}}""",
        "open_activity" to """{"skillType":"CUSTOM_COMMAND","params":{"activityClass":"com.example.MyActivity","extras":{"key":"value"}}}""",
        "send_broadcast" to """{"skillType":"CUSTOM_COMMAND","params":{"action":"com.example.MY_ACTION","extras":{"key":"value"}}}""",
        "custom" to """{"skillType":"CUSTOM_COMMAND","params":{"key":"value"}}"""
    )

    LaunchedEffect(editingSkill) {
        editingSkill?.let { skill ->
            skillName = skill.name
            skillDescription = skill.description
            skillSystemPrompt = skill.systemPrompt
            skillJson = skill.skillJson
            skillImplementationType = skill.implementationType
        } ?: run {
            skillName = ""
            skillDescription = ""
            skillSystemPrompt = ""
            skillJson = ""
            skillImplementationType = "shell_command"
        }
    }

    OverlayDialog(
        title = if (editingSkill != null) stringResource(R.string.edit_skill) else stringResource(R.string.add_custom_skill),
        summary = stringResource(R.string.custom_skill_create_desc),
        show = show,
        onDismissRequest = onDismiss,
        content = {
            Box(
                modifier = Modifier
                    .heightIn(max = 520.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Column {
                    TextField(
                        value = skillName,
                        onValueChange = { skillName = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = stringResource(R.string.skill_name_hint),
                        useLabelAsPlaceholder = true,
                        singleLine = true
                    )
                    Spacer(Modifier.height(8.dp))
                    TextField(
                        value = skillDescription,
                        onValueChange = { skillDescription = it },
                        modifier = Modifier.fillMaxWidth(),
                        label = stringResource(R.string.skill_description),
                        useLabelAsPlaceholder = true,
                        singleLine = false,
                        maxLines = 2
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.implementation),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Spacer(Modifier.height(4.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(6.dp)
                    ) {
                        implOptions.forEach { (value, label) ->
                            val selected = skillImplementationType == value
                            TextButton(
                                text = label,
                                onClick = {
                                    skillImplementationType = value
                                    if (skillJson.isBlank() || skillJson == implJsonTemplates.values.first()) {
                                        skillJson = implJsonTemplates[value] ?: ""
                                    }
                                },
                                modifier = Modifier.weight(1f),
                                colors = if (selected) ButtonDefaults.textButtonColorsPrimary() else ButtonDefaults.textButtonColors()
                            )
                        }
                    }
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.skill_invocation_desc),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    TextField(
                        value = skillJson,
                        onValueChange = { skillJson = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp),
                        label = stringResource(R.string.impl_example_prefix, implJsonTemplates[skillImplementationType] ?: ""),
                        useLabelAsPlaceholder = true,
                        maxLines = Int.MAX_VALUE,
                        minLines = 3
                    )
                    Spacer(Modifier.height(8.dp))
                    Text(
                        text = stringResource(R.string.impl_notes_hint),
                        fontSize = 12.sp,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    TextField(
                        value = skillSystemPrompt,
                        onValueChange = { skillSystemPrompt = it },
                        modifier = Modifier
                            .fillMaxWidth()
                            .heightIn(min = 60.dp),
                        label = stringResource(R.string.skill_impl_detail_hint),
                        useLabelAsPlaceholder = true,
                        maxLines = Int.MAX_VALUE,
                        minLines = 3
                    )
                }
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.SpaceBetween) {
                TextButton(
                    text = stringResource(R.string.cancel),
                    onClick = onDismiss,
                    modifier = Modifier.weight(1f)
                )
                Spacer(Modifier.width(16.dp))
                TextButton(
                    text = stringResource(R.string.save),
                    onClick = {
                        if (skillName.isBlank()) return@TextButton
                        val existing = editingSkill
                        if (existing != null) {
                            AiTermuxPrefs.updateCustomSkill(
                                context, existing.copy(
                                    name = skillName,
                                    description = skillDescription,
                                    systemPrompt = skillSystemPrompt,
                                    skillJson = skillJson,
                                    implementationType = skillImplementationType
                                )
                            )
                        } else {
                            AiTermuxPrefs.addCustomSkill(
                                context, CustomSkill(
                                    name = skillName,
                                    description = skillDescription,
                                    systemPrompt = skillSystemPrompt,
                                    skillJson = skillJson,
                                    implementationType = skillImplementationType
                                )
                            )
                        }
                        onSaved()
                        onDismiss()
                    },
                    modifier = Modifier.weight(1f),
                    colors = ButtonDefaults.textButtonColorsPrimary()
                )
            }
        }
    )
}

/** 无限制模式二次确认弹窗（含生物识别）。 */
@Composable
private fun AgentUnlimitedModeConfirmDialog(
    show: Boolean,
    onDismiss: () -> Unit,
    context: Context,
    onEnable: () -> Unit
) {
    var unlimitedCheckboxChecked by remember(show) { mutableStateOf(false) }
    var isUnlimitedAuthenticating by remember { mutableStateOf(false) }
    LaunchedEffect(show) {
        if (!show) {
            unlimitedCheckboxChecked = false
            isUnlimitedAuthenticating = false
        }
    }
    OverlayDialog(
        show = show,
        onDismissRequest = onDismiss,
        title = stringResource(R.string.enable_unrestricted),
        summary = stringResource(R.string.unrestricted_mode_banner),
        content = {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 4.dp)
            ) {
                Text(
                    text = stringResource(R.string.unrestricted_mode_warning),
                    fontSize = 13.sp,
                    color = MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    lineHeight = 20.sp,
                    modifier = Modifier.padding(bottom = 12.dp)
                )
                CheckboxPreference(
                    title = stringResource(R.string.confirm_unrestricted),
                    checked = unlimitedCheckboxChecked,
                    onCheckedChange = { unlimitedCheckboxChecked = it },
                    modifier = Modifier.fillMaxWidth()
                )
                Spacer(Modifier.height(16.dp))
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(20.dp)
                ) {
                    Button(
                        onClick = onDismiss,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            color = Color.Transparent
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.cancel),
                            color = MiuixTheme.colorScheme.onSurface,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                    Button(
                        onClick = {
                            isUnlimitedAuthenticating = true
                            val activity = context as? androidx.fragment.app.FragmentActivity
                            if (activity != null) {
                                launchBiometricAuth(activity) { success ->
                                    isUnlimitedAuthenticating = false
                                    if (success) {
                                        onEnable()
                                        onDismiss()
                                    } else {
                                        val msg = context.getString(R.string.risk_command_biometric_prompt)
                                        SnackbarHelper.show(context, msg, Snackbar.LENGTH_SHORT)
                                    }
                                }
                            } else {
                                onEnable()
                                onDismiss()
                            }
                        },
                        enabled = unlimitedCheckboxChecked && !isUnlimitedAuthenticating,
                        modifier = Modifier.weight(1f),
                        colors = ButtonDefaults.buttonColors(
                            color = if (unlimitedCheckboxChecked && !isUnlimitedAuthenticating)
                                Color(0xFFD32F2F) else Color(0xFFBDBDBD)
                        )
                    ) {
                        Text(
                            text = stringResource(R.string.confirm_enable),
                            color = Color.White,
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium
                        )
                    }
                }
            }
        }
    )
}

/** 打开「开发者模式」设置页。 */
fun openAiTermuxDeveloper(context: Context) {
    context.startActivity(Intent(context, com.termux.app.activities.AiTermuxDeveloperActivity::class.java))
}
