package com.termux.app.compose

import com.google.gson.Gson

import android.content.Context
import androidx.compose.foundation.background
import androidx.compose.foundation.border
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
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentHeight
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Slider
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.termux.R
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.Card
import top.yukonga.miuix.kmp.basic.HorizontalDivider
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.LinearProgressIndicator
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.SmallTitle
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.basic.TextButton
import top.yukonga.miuix.kmp.basic.TextField
import top.yukonga.miuix.kmp.basic.TopAppBar
import androidx.compose.foundation.layout.WindowInsets
import top.yukonga.miuix.kmp.window.WindowDialog
import top.yukonga.miuix.kmp.icon.MiuixIcons
import top.yukonga.miuix.kmp.icon.extended.Back
import top.yukonga.miuix.kmp.theme.MiuixTheme

private const val TAG = "AiLocalTrainerScreen"

@Composable
fun AiLocalTrainerScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit
) {
    val ctx = LocalContext.current
    val scope = rememberCoroutineScope()
    val onlineReady = remember { mutableStateOf(false) }
    val hasLocal = remember { mutableStateOf(AiTermuxPrefs.getConfig(ctx).providerConfig.provider == "local") }
    val scrollBehavior = MiuixScrollBehavior()

    LaunchedEffect(Unit) {
        onlineReady.value = AiTermuxPrefs.isFallbackOnlineConfigReady(ctx)
    }

    KiTerminalTheme {
        Scaffold(
            modifier = modifier.fillMaxSize(),
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                TopAppBar(
                    title = "训练本地模型",
                    scrollBehavior = scrollBehavior,
                    navigationIcon = {
                        Box(
                            modifier = Modifier.size(40.dp).clip(CircleShape).clickable { onBack() },
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = MiuixIcons.Back,
                                contentDescription = "返回",
                                modifier = Modifier.size(24.dp),
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
                    }
                )
            }
        ) { padding ->
            if (!hasLocal.value) {
                NoLocalModelHint(Modifier.padding(padding))
            } else {
                TrainerBody(
                    Modifier
                        .padding(padding)
                        .fillMaxSize()
                        .nestedScroll(scrollBehavior.nestedScrollConnection),
                    ctx, onlineReady
                )
            }
        }
    }
}

@Composable
private fun NoLocalModelHint(modifier: Modifier) {
    Column(
        modifier.fillMaxSize().padding(24.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("尚未配置本地模型，无法训练。", fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.onSurface)
        Spacer(Modifier.height(8.dp))
        Text("请先前往设置页 → Termux Agent → 选择本地模型（llama 或 Ollama）。",
             fontSize = 12.sp)
    }
}

// ========== 主体 ==========
@Composable
private fun TrainerBody(
    modifier: Modifier,
    ctx: Context,
    onlineReady: MutableState<Boolean>
) {
    val scope = rememberCoroutineScope()
    val session = remember { mutableStateOf(AiTermuxPrefs.getLastTrainSession(ctx) ?: LocalTrainSession()) }
    val steps = remember { mutableStateListOf<Pair<Int, String>>() }
    val etaText = remember { mutableStateOf("等待开始…") }
    val currentTab = rememberSaveable { mutableStateOf(0) }
    val statusMsg = remember { mutableStateOf(session.value.status.ifBlank { "未开始" }) }
    val waitingRating = remember { mutableStateOf<LocalTrainerEvent.WaitingForUserRating?>(null) }
    val refreshTeacherChat = remember { mutableStateOf(0) }

    var job by remember { mutableStateOf<Job?>(null) }
    val cancelled = remember { mutableStateOf(false) }

    fun startOrResume() {
        if (job?.isActive == true) return
        if (session.value.status == "finished" || session.value.rounds.size >= session.value.targetRounds) {
            session.value = LocalTrainSession(targetRounds = session.value.targetRounds)
            AiTermuxPrefs.saveLastTrainSession(ctx, session.value)
        }
        if (session.value.teacher.isBlank()) {
            session.value = session.value.copy(teacher = if (onlineReady.value) "online_fallback" else "manual")
        }
        cancelled.value = false
        job = scope.launch(Dispatchers.IO) {
            AiLocalTrainer.runTraining(ctx, session.value) { cancelled.value }.collect { evt ->
                withContext(Dispatchers.Main.immediate) {
                    handleTrainerEvent(evt, steps, etaText, statusMsg, session, waitingRating, ctx)
                }
            }
        }
    }

    fun pause() {
        cancelled.value = true
        session.value.status = "paused"
        AiTermuxPrefs.saveLastTrainSession(ctx, session.value)
        statusMsg.value = "已暂停（可随时继续）"
        scope.launch {
            kotlinx.coroutines.delay(80)
            job?.cancelAndJoin()
            job = null
        }
    }

    fun resetAll() {
        scope.launch { job?.cancelAndJoin(); job = null }
        session.value = LocalTrainSession(targetRounds = session.value.targetRounds)
        steps.clear()
        etaText.value = "等待开始…"
        statusMsg.value = "未开始"
        waitingRating.value = null
        AiTermuxPrefs.saveLastTrainSession(ctx, session.value)
    }

    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val obs = LifecycleEventObserver { _, e ->
            if (e == Lifecycle.Event.ON_DESTROY) scope.launch { job?.cancelAndJoin(); job = null }
        }
        lifecycleOwner.lifecycle.addObserver(obs)
        onDispose { lifecycleOwner.lifecycle.removeObserver(obs) }
    }

    Column(
        modifier
            .fillMaxSize()
            .padding(horizontal = 16.dp)
    ) {
        Spacer(Modifier.height(8.dp))
        TabBar(currentTab)
        Spacer(Modifier.height(8.dp))

        Box(Modifier.weight(1f)) {
            when (currentTab.value) {
                0 -> StepsTab(
                    steps = steps,
                    session = session.value,
                    jobActive = job?.isActive == true,
                    statusMsg = statusMsg.value,
                    etaText = etaText.value,
                    onlineReady = onlineReady.value,
                    onTargetRoundsChange = { n ->
                        session.value = session.value.copy(targetRounds = n)
                        AiTermuxPrefs.saveLastTrainSession(ctx, session.value)
                    },
                    onTeacherToggle = { t -> session.value = session.value.copy(teacher = t) },
                    onStart = { startOrResume() }, onPause = { pause() }, onReset = { resetAll() },
                    onClearMemory = { AiTermuxPrefs.clearLearnedMemory(ctx) },
                    onClearTeacherChat = { 
                        AiLocalTrainer.clearTeacherChatHistory(ctx)
                        refreshTeacherChat.value += 1
                    }
                )
                1 -> ConversationTab(session)
                else -> TeacherChatTab(ctx, onlineReady, refreshTeacherChat.value)
            }
        }
    }

    ManualRatingDialog(
        data = waitingRating.value ?: LocalTrainerEvent.WaitingForUserRating(0, "", "", 60, "", ""),
        show = waitingRating.value != null,
        setShow = { show -> if (!show) waitingRating.value = null },
        onConfirm = { score, critique, patch ->
            val data = waitingRating.value ?: return@ManualRatingDialog
            AiLocalTrainer.provideUserRating(data.roundIndex, score, critique, patch)
            waitingRating.value = null
        },
        onDismiss = {
            // 用户选择跳过，用启发式建议继续
            val data = waitingRating.value ?: return@ManualRatingDialog
            AiLocalTrainer.provideUserRating(data.roundIndex, data.suggestedScore, data.suggestedCritique, data.suggestedMemoryPatch)
            waitingRating.value = null
        }
    )
}

// ========== 事件处理 ==========
private fun handleTrainerEvent(
    evt: LocalTrainerEvent,
    steps: androidx.compose.runtime.snapshots.SnapshotStateList<Pair<Int, String>>,
    etaText: MutableState<String>,
    statusMsg: MutableState<String>,
    session: MutableState<LocalTrainSession>,
    waitingRating: MutableState<LocalTrainerEvent.WaitingForUserRating?>,
    ctx: Context
) {
    when (evt) {
        is LocalTrainerEvent.StatusChanged -> {
            session.value.status = evt.status
            evt.message?.let { statusMsg.value = it }
        }
        is LocalTrainerEvent.EtaUpdated -> etaText.value = evt.etaText
        is LocalTrainerEvent.Step -> steps.add(evt.roundIndex to "【步骤】${evt.title}\n${evt.detail}")
        is LocalTrainerEvent.TeacherQuestion -> steps.add(evt.roundIndex to "【老师出题 - 第${evt.roundIndex}轮】\n${evt.text}")
        is LocalTrainerEvent.StudentAnswer -> steps.add(evt.roundIndex to "【学生回答 第${evt.roundIndex}轮 · ${evt.durationMs/1000}s】\n${evt.text}")
        is LocalTrainerEvent.TeacherCritique -> {
            val header = if (session.value.teacher == "online_fallback") "【在线老师评分 第${evt.roundIndex}轮 · ${evt.score}/100】" else "【用户评分 第${evt.roundIndex}轮 · ${evt.score}/100】"
            val bodySb = StringBuilder()
            bodySb.appendLine(evt.critique)
            if (evt.memoryPatch.isNotBlank()) { bodySb.appendLine(); bodySb.appendLine("→ System Prompt 记忆块追加："); bodySb.append(evt.memoryPatch) }
            val body = bodySb.toString()
            steps.add(evt.roundIndex to "$header\n$body")
        }
        is LocalTrainerEvent.RoundDone -> steps.add(evt.round.roundIndex to "【第${evt.round.roundIndex}轮完成】得分=${evt.round.score}，记忆块当前${evt.learnedNowCount} chars")
        is LocalTrainerEvent.ErrorOccurred -> { steps.add(evt.roundIndex to "❌ 错误（第${evt.roundIndex}轮）\n${evt.message}"); statusMsg.value = "错误：${evt.message}" }
        is LocalTrainerEvent.SessionSnapshot -> { session.value = evt.session; AiTermuxPrefs.saveLastTrainSession(ctx, evt.session) }
        is LocalTrainerEvent.WaitingForUserRating -> {
            steps.add(evt.roundIndex to "📝 等待用户评分 第${evt.roundIndex}轮 · 建议分=${evt.suggestedScore}/100\n${evt.suggestedCritique}")
            waitingRating.value = evt
        }
        is LocalTrainerEvent.TeacherFollowup -> steps.add(evt.roundIndex to "【老师追问 第${evt.roundIndex}轮】\n${evt.followupText}")
        is LocalTrainerEvent.StudentFollowupAnswer -> steps.add(evt.roundIndex to "【学生回答追问 第${evt.roundIndex}轮】\n${evt.answerText}")
    }
}

// ========== 顶部信息卡（进度/状态/ETA/老师/轮数） ==========
@Composable
private fun TopInfoCard(
    session: LocalTrainSession, statusMsg: String, etaText: String, onlineReady: Boolean,
    onTargetRoundsChange: (Int) -> Unit, onTeacherToggle: (String) -> Unit
) {
    val doneRounds = session.rounds.count { it.status == "done" }
    val total = session.targetRounds
    val progress = (doneRounds.toFloat() / total.toFloat()).coerceIn(0f, 1f)
    Card(
        modifier = Modifier.fillMaxWidth().wrapContentHeight().clip(RoundedCornerShape(16.dp))
    ) {
        Column(Modifier.padding(14.dp)) {
            Text("训练进度", fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                LinearProgressIndicator(progress = progress, modifier = Modifier.weight(1f).height(8.dp).clip(RoundedCornerShape(8.dp)))
                Spacer(Modifier.width(10.dp))
                Text("$doneRounds/$total", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(10.dp))
            HorizontalDivider(color = MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = 0.15f))
            Spacer(Modifier.height(8.dp))
            InfoRow("状态：", statusMsg, true)
            InfoRow("预计剩余时间：", etaText, false)
            if (session.avgRoundMs > 0L) InfoRow("单轮平均：", "${session.avgRoundMs/1000}s", false)
            val doneRoundsData = session.rounds.filter { it.status == "done" && it.score > 0 }
            if (doneRoundsData.isNotEmpty()) {
                val avg = doneRoundsData.map { it.score }.average().toInt()
                InfoRow("当前平均分：", "$avg / 100", false)
            }
            if (session.status == "finished" && doneRoundsData.isNotEmpty()) {
                Spacer(Modifier.height(6.dp))
                val avgFinal = doneRoundsData.map { it.score }.average().toInt()
                Text("🏁 训练完成 · 平均分 $avgFinal / 100", fontWeight = FontWeight.SemiBold, color = MiuixTheme.colorScheme.primary, fontSize = 13.sp)
                if (session.finalSummary.isNotBlank()) {
                    Spacer(Modifier.height(4.dp))
                    Text(session.finalSummary, fontSize = 12.sp)
                }
            }
            Spacer(Modifier.height(8.dp))
            Text("训练老师（谁来出题+评分）：", fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                val online = session.teacher == "online_fallback" && onlineReady
                val manual = session.teacher == "manual" || !onlineReady
                FilterChip2(
                    label = if (onlineReady) "在线全自动（推荐）" else "请先配置备用在线模型",
                    selected = online, enabled = onlineReady,
                    onClick = { if (onlineReady) onTeacherToggle("online_fallback") }
                )
                Spacer(Modifier.width(6.dp))
                FilterChip2(label = "用户手动评分", selected = manual, onClick = { onTeacherToggle("manual") })
            }
            Spacer(Modifier.height(8.dp))
            Text("总轮数：", fontSize = 12.sp)
            Spacer(Modifier.height(4.dp))
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.horizontalScroll(rememberScrollState())
            ) {
                (5..30 step 5).forEach { n ->
                    FilterChip2(
                        label = "$n 轮", selected = total == n,
                        modifier = Modifier.padding(end = 6.dp),
                        onClick = { onTargetRoundsChange(n) }
                    )
                }
            }
        }
    }
}

@Composable
private fun InfoRow(label: String, value: String, bold: Boolean) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        Text(label, fontSize = 12.sp)
        Text(value, fontSize = 12.sp, fontWeight = if (bold) FontWeight.SemiBold else FontWeight.Normal)
    }
}

// ========== Tab ==========
@Composable
private fun TabBar(currentTab: MutableState<Int>) {
    Row(
        Modifier.fillMaxWidth().height(48.dp).clip(RoundedCornerShape(12.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f)).padding(2.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        listOf("训练流程" to 0, "完整对话" to 1, "与老师对话" to 2).forEach { (t, i) ->
            val selected = currentTab.value == i
            Box(
                Modifier.weight(1f).fillMaxSize().clip(RoundedCornerShape(10.dp))
                    .background(if (selected) MiuixTheme.colorScheme.primaryContainer else Color.Transparent)
                    .clickable { currentTab.value = i },
                contentAlignment = Alignment.Center
            ) {
                Text(t,
                    fontWeight = if (selected) FontWeight.SemiBold else FontWeight.Normal,
                    color = if (selected) MiuixTheme.colorScheme.onPrimaryContainer else MiuixTheme.colorScheme.onSurfaceVariantSummary,
                    fontSize = 13.sp)
            }
        }
    }
}

// ========== 控制按钮 ==========
@Composable
private fun ControlBar(
    session: LocalTrainSession, jobActive: Boolean, onlineReady: Boolean, currentTab: Int,
    onStart: () -> Unit, onPause: () -> Unit, onReset: () -> Unit, onClearMemory: () -> Unit,
    onClearTeacherChat: () -> Unit
) {
    Row(verticalAlignment = Alignment.CenterVertically) {
        if (currentTab == 2) {
            // 老师对话 tab：隐藏继续/暂停/重开/清空教训，显示清空老师对话历史
            Button(
                onClick = onClearTeacherChat, modifier = Modifier.weight(1f).height(48.dp)
            ) {
                Text("清空老师对话历史")
            }
        } else if (!jobActive) {
            val disabled = session.teacher == "online_fallback" && !onlineReady
            val btnText = when {
                session.rounds.isNotEmpty() && session.status != "finished" -> "继续训练 (${session.rounds.size}/${session.targetRounds})"
                else -> "开始训练"
            }
            Button(
                onClick = onStart, modifier = Modifier.weight(1f).height(48.dp),
                enabled = !disabled
            ) {
                Text(btnText)
            }
            Spacer(Modifier.width(8.dp))
            TextButton(text = "重开", onClick = onReset, modifier = Modifier.height(48.dp))
            Spacer(Modifier.width(2.dp))
            TextButton(
                text = "清空教训", onClick = onClearMemory, modifier = Modifier.height(48.dp)
            )
        } else {
            Button(
                onClick = onPause, modifier = Modifier.weight(1f).height(48.dp)
            ) {
                Text("暂停")
            }
            Spacer(Modifier.width(8.dp))
            TextButton(text = "重开", onClick = onReset, modifier = Modifier.height(48.dp))
            Spacer(Modifier.width(2.dp))
            TextButton(
                text = "清空教训", onClick = onClearMemory, modifier = Modifier.height(48.dp)
            )
        }
    }
}

// ========== 流程 Tab ==========
@Composable
private fun StepsTab(
    steps: androidx.compose.runtime.snapshots.SnapshotStateList<Pair<Int, String>>,
    session: LocalTrainSession,
    jobActive: Boolean,
    statusMsg: String,
    etaText: String,
    onlineReady: Boolean,
    onTargetRoundsChange: (Int) -> Unit,
    onTeacherToggle: (String) -> Unit,
    onStart: () -> Unit,
    onPause: () -> Unit,
    onReset: () -> Unit,
    onClearMemory: () -> Unit,
    onClearTeacherChat: () -> Unit
) {
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(6.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp)
    ) {
        item(key = "top_info") {
            TopInfoCard(
                session = session, statusMsg = statusMsg, etaText = etaText,
                onlineReady = onlineReady,
                onTargetRoundsChange = onTargetRoundsChange,
                onTeacherToggle = onTeacherToggle
            )
        }
        item(key = "control_bar") {
            ControlBar(
                session = session, jobActive = jobActive, onlineReady = onlineReady, currentTab = 0,
                onStart = onStart, onPause = onPause, onReset = onReset,
                onClearMemory = onClearMemory, onClearTeacherChat = onClearTeacherChat
            )
        }
        if (steps.isEmpty()) {
            item(key = "empty") {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("训练还没有开始。")
                    Spacer(Modifier.height(6.dp))
                    Text("选择总轮数和老师类型，点击「开始训练」。", fontSize = 12.sp,
                         color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                }
            }
        } else {
            itemsIndexed(steps, key = { i, _ -> "step_${i}_${steps[i].first}" }) { _, (roundIdx, text) ->
                StepCard(roundIdx, text)
            }
        }
    }
}

@Composable
private fun StepCard(roundIdx: Int, text: String) {
    val header = text.takeWhile { it != '\n' }
    val body = text.drop(header.length).trim('\n')
    val bg = when {
        header.startsWith("❌") -> MiuixTheme.colorScheme.errorContainer.copy(alpha = 0.25f)
        header.startsWith("⏸️") -> MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        header.startsWith("【学生回答") -> MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.15f)
        header.startsWith("【在线老师评分") || header.startsWith("【用户评分") -> MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.3f)
        else -> MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f)
    }
    Card(
        modifier = Modifier.fillMaxWidth().wrapContentHeight().clip(RoundedCornerShape(12.dp)).background(bg)
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(header, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
            if (body.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(body, fontSize = 12.sp)
            }
        }
    }
}

// ========== 完整对话 Tab ==========
@Composable
private fun ConversationTab(session: MutableState<LocalTrainSession>) {
    val rounds = session.value.rounds
    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        verticalArrangement = Arrangement.spacedBy(8.dp),
        contentPadding = androidx.compose.foundation.layout.PaddingValues(bottom = 8.dp)
    ) {
        if (rounds.isEmpty()) {
            item(key = "empty") {
                Column(
                    Modifier.fillMaxSize(),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("暂无对话记录。")
                }
            }
        } else {
            itemsIndexed(rounds, key = { _, r -> "round_${r.roundIndex}" }) { _, round ->
                RoundConversationCard(round, session.value.teacher)
            }
        }
    }
}

@Composable
private fun RoundConversationCard(round: LocalTrainRound, teacher: String) {
    Card(
        modifier = Modifier.fillMaxWidth().wrapContentHeight().clip(RoundedCornerShape(14.dp))
            .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f))
    ) {
        Column(Modifier.padding(12.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text("第 ${round.roundIndex} 轮", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.width(8.dp))
                val scoreColor = when {
                    round.score >= 85 -> MiuixTheme.colorScheme.primary
                    round.score >= 60 -> MiuixTheme.colorScheme.secondary
                    else -> MiuixTheme.colorScheme.error
                }
                Box(
                    Modifier.clip(RoundedCornerShape(10.dp))
                        .background(scoreColor.copy(alpha = 0.2f))
                        .padding(horizontal = 8.dp, vertical = 2.dp)
                ) {
                    Text("评分 ${round.score}/100", fontSize = 12.sp, color = scoreColor, fontWeight = FontWeight.SemiBold)
                }
                Spacer(Modifier.width(8.dp))
                Text("用时 ${round.durationMs/1000}s", fontSize = 11.sp)
            }
            Bubble("老师出题", round.question, "teacher")
            Bubble("本地学生回答", round.studentAnswer, "student")
            if (round.critique.isNotBlank()) {
                val title = if (teacher == "online_fallback") "在线老师批改" else "用户批改"
                val contentSb = StringBuilder()
                contentSb.append(round.critique)
                if (round.memoryPatch.isNotBlank()) contentSb.append("\n\nSystem Prompt 记忆块追加：\n").append(round.memoryPatch)
                val content = contentSb.toString()
                Bubble(title, content, "critique")
            }
        }
    }
}

@Composable
private fun Bubble(title: String, body: String, role: String) {
    val (bg, align) = when (role) {
        "teacher" -> MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f) to Alignment.Start
        "student" -> MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.4f) to Alignment.End
        else      -> MiuixTheme.colorScheme.secondaryContainer.copy(alpha = 0.5f) to Alignment.Start
    }
    Spacer(Modifier.height(6.dp))
    Row(Modifier.fillMaxWidth(), horizontalArrangement = if (align == Alignment.End) Arrangement.End else Arrangement.Start) {
        Column(
            Modifier.wrapContentHeight().width(310.dp).clip(RoundedCornerShape(14.dp)).background(bg).padding(10.dp)
        ) {
            Text(title, fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text(body, fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurface)
        }
    }
}

// ========== 与在线老师对话 Tab ==========
@Composable
private fun TeacherChatTab(ctx: Context, onlineReady: MutableState<Boolean>, refreshTrigger: Int) {
    val scope = rememberCoroutineScope()
    val messages = remember { mutableStateListOf<Pair<String, String>>() } // (role, content)
    val input = remember { mutableStateOf("") }
    val isLoading = remember { mutableStateOf(false) }

    // 加载历史（同时监听清空触发）
    LaunchedEffect(Unit, refreshTrigger) {
        val history = AiLocalTrainer.getTeacherChatHistory(ctx)
        messages.clear()
        history.forEach { messages.add(it.role to it.content) }
    }

    Column(
        Modifier.fillMaxSize()
            .navigationBarsPadding()
            .imePadding()
    ) {
        if (!onlineReady.value) {
            Column(
                Modifier.fillMaxSize(),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("⚠️ 需要先配置备用在线大模型", fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(6.dp))
                Text("前往 设置 → Termux Agent → 备用在线模型 配置后即可使用", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
            return@Column
        }

        if (messages.isEmpty()) {
            Column(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.Center,
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text("👨‍🏫 在线老师", fontWeight = FontWeight.SemiBold, fontSize = 16.sp)
                Spacer(Modifier.height(8.dp))
                Text("和老师聊聊你想怎么训练本地大模型吧", fontSize = 13.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                Spacer(Modifier.height(4.dp))
                Text("比如：我想练习 root 权限控制、Ollama 部署等", fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
            }
        } else {
            LazyColumn(
                Modifier.fillMaxWidth().weight(1f),
                verticalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(vertical = 8.dp)
            ) {
                itemsIndexed(messages) { idx, (role, content) ->
                    val isUser = role == "user"
                    Row(
                        Modifier.fillMaxWidth(),
                        horizontalArrangement = if (isUser) Arrangement.End else Arrangement.Start
                    ) {
                        Card(
                            modifier = Modifier
                                .weight(1f, fill = false)
                                .clip(RoundedCornerShape(12.dp))
                                .background(if (isUser) MiuixTheme.colorScheme.primaryContainer else MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                        ) {
                            Text(
                                text = content,
                                modifier = Modifier.padding(12.dp),
                                fontSize = 13.sp,
                                color = if (isUser) MiuixTheme.colorScheme.onPrimaryContainer else MiuixTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
                if (isLoading.value) {
                    item {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Start) {
                            Card(
                                modifier = Modifier
                                    .clip(RoundedCornerShape(12.dp))
                                    .background(MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f))
                            ) {
                                Text("老师正在思考…", modifier = Modifier.padding(12.dp), fontSize = 12.sp, color = MiuixTheme.colorScheme.onSurfaceVariantSummary)
                            }
                        }
                    }
                }
            }
        }

        // 输入区
        Row(
            Modifier.fillMaxWidth().padding(vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            TextField(
                value = input.value,
                onValueChange = { input.value = it },
                modifier = Modifier.weight(1f).heightIn(min = 44.dp),
                label = "问老师一个训练问题…"
            )
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = {
                    val msg = input.value.trim()
                    if (msg.isBlank() || isLoading.value) return@Button
                    input.value = ""
                    messages.add("user" to msg)
                    isLoading.value = true
                    scope.launch(Dispatchers.IO) {
                        val reply = runCatching { AiLocalTrainer.chatWithTeacher(ctx, msg) }
                            .getOrElse { "❌ 与老师对话失败: ${it.message}" }
                        withContext(Dispatchers.Main) {
                            messages.add("assistant" to reply)
                            isLoading.value = false
                        }
                    }
                },
                modifier = Modifier.height(44.dp)
            ) {
                Text("发送")
            }
        }

    }
}

// ========== 手动评分 Dialog ==========
@Composable
private fun ManualRatingDialog(
    data: LocalTrainerEvent.WaitingForUserRating,
    show: Boolean,
    setShow: (Boolean) -> Unit,
    onConfirm: (score: Int, critique: String, patch: String) -> Unit,
    onDismiss: () -> Unit
) {
    var score by remember(show) { mutableStateOf(data.suggestedScore.toFloat()) }
    var critique by remember(show) { mutableStateOf(data.suggestedCritique) }
    var patch by remember(show) { mutableStateOf(data.suggestedMemoryPatch) }

    WindowDialog(
        show = show,
        onDismissRequest = { setShow(false); onDismiss() },
        title = "给学生本轮回答打分",
        summary = "本地模型没有配置备用在线大模型，因此需要您手动给出评分与改进建议。",
        content = {
            Column(Modifier.verticalScroll(rememberScrollState()).padding(vertical = 4.dp)) {
                Text("本地模型没有配置备用在线大模型，因此需要您手动给出评分与改进建议。系统已基于启发式提供了参考评分和建议补丁，可直接修改。", fontSize = 12.sp)
                Spacer(Modifier.height(10.dp))
                Text("题目：", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Text(data.question, fontSize = 12.sp)
                Spacer(Modifier.height(6.dp))
                Text("学生回答：", fontSize = 12.sp, fontWeight = FontWeight.SemiBold)
                Box(
                    Modifier.fillMaxWidth().height(140.dp).clip(RoundedCornerShape(8.dp))
                        .background(MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.2f)).padding(8.dp)
                        .verticalScroll(rememberScrollState())
                ) { Text(data.studentAnswer, fontSize = 12.sp) }

                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Text("评分：${score.toInt()}  ", fontSize = 13.sp, fontWeight = FontWeight.SemiBold)
                    Slider(
                        value = score, onValueChange = { score = it },
                        valueRange = 0f..100f, steps = 99, modifier = Modifier.weight(1f)
                    )
                }
                Spacer(Modifier.height(4.dp))
                Text("批评/批改理由（可直接修改）", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                TextField(
                    value = critique, onValueChange = { critique = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 140.dp),
                    label = "详细指出优缺点、正确命令应该是什么..."
                )
                Spacer(Modifier.height(8.dp))
                Text("教训记忆（追加到 System Prompt 末尾）", fontSize = 11.sp, fontWeight = FontWeight.SemiBold)
                Spacer(Modifier.height(2.dp))
                TextField(
                    value = patch, onValueChange = { patch = it },
                    modifier = Modifier.fillMaxWidth().heightIn(min = 110.dp),
                    label = "如：• 关于编造参数：列出多个命令参数时，提示用户用 man 确认。完美则可清空。"
                )
                Spacer(Modifier.height(10.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextButton(
                        text = "使用建议值（跳过手动评分）", onClick = { setShow(false); onDismiss() },
                        modifier = Modifier.weight(1f).height(48.dp)
                    )
                    Spacer(Modifier.width(8.dp))
                    Button(
                        onClick = { setShow(false); onConfirm(score.toInt(), critique, patch) },
                        modifier = Modifier.weight(1f).height(48.dp)
                    ) {
                        Text("提交评分 · 进入下一轮")
                    }
                }
            }
        }
    )
}

// ========== Chip（复用 LogViewerScreen.kt 中的风格，内部实现一份同名） ==========
@Composable
private fun FilterChip2(
    label: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    enabled: Boolean = true
) {
    val shape = RoundedCornerShape(100.dp)
    val bg = when {
        selected -> MiuixTheme.colorScheme.primaryContainer.copy(alpha = 0.85f)
        else -> MiuixTheme.colorScheme.surfaceVariant.copy(alpha = 0.7f)
    }
    val tc = when {
        selected -> MiuixTheme.colorScheme.onPrimaryContainer
        else -> MiuixTheme.colorScheme.onSurfaceVariantSummary.copy(alpha = if (enabled) 1f else 0.35f)
    }
    Box(
        modifier = modifier
            .clip(shape)
            .background(if (enabled) bg else bg.copy(alpha = 0.4f))
            .border(1.2.dp, if (selected) MiuixTheme.colorScheme.primary.copy(alpha = 0.3f) else Color.Transparent, shape)
            .clickable(enabled = enabled, onClick = onClick)
            .padding(horizontal = 12.dp, vertical = 7.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(label, fontSize = 12.sp, color = tc)
    }
}
