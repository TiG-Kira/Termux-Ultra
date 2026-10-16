package com.termux.app.compose

import android.content.Intent
import androidx.activity.result.ActivityResultLauncher
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.termux.R
import com.termux.app.activities.PackageTaskActivity
import kotlinx.coroutines.launch
import top.yukonga.miuix.kmp.basic.Button
import top.yukonga.miuix.kmp.basic.ButtonDefaults
import top.yukonga.miuix.kmp.basic.Card as MiuixCard
import top.yukonga.miuix.kmp.basic.Icon
import top.yukonga.miuix.kmp.basic.MiuixScrollBehavior
import top.yukonga.miuix.kmp.basic.Scaffold
import top.yukonga.miuix.kmp.basic.Text
import top.yukonga.miuix.kmp.glass.GlassIconButton
import top.yukonga.miuix.kmp.glass.GlassTopAppBar
import top.yukonga.miuix.kmp.icon.glass.ChevronBackward
import top.yukonga.miuix.kmp.icon.glass.Download
import top.yukonga.miuix.kmp.icon.glass.MiuixGlassIcons
import top.yukonga.miuix.kmp.icon.glass.Refresh
import top.yukonga.miuix.kmp.preference.ArrowPreference
import top.yukonga.miuix.kmp.theme.MiuixTheme

private val AccentBlue = Color(0xFF2563EB)
private val UpgradeOrange = Color(0xFFEA580C)

/**
 * 软件包升级页：列出所有可升级的已安装包，承接从软件包管理页迁移而来的「升级所有包」。
 *
 * - 列表数据由调用方（PackageManagerScreen）持有并透传：任务结果经其 taskLauncher 统一刷新，
 *   顶栏刷新与后台任务完成（LiveUpdateState 观察）时主动重查 apt 后经 onUpgradableListChanged 回写；
 * - 点击单个包进入详情页升级；底部按钮批量升级全部可升级包（包名为空的 UPGRADE 任务）。
 */
@Composable
fun PackageUpgradeScreen(
    upgradableList: List<PkgRepo.UpgradableInfo>,
    navBarBottomPadding: Dp,
    taskLauncher: ActivityResultLauncher<Intent>,
    onBack: () -> Unit,
    onOpenDetail: (PackageInfo) -> Unit,
    onUpgradableListChanged: (List<PkgRepo.UpgradableInfo>) -> Unit
) {
    val context = LocalContext.current
    val listState = rememberLazyListState()
    // 本页位于 MainScreen 取景层之外，自建一层供玻璃顶栏折射页面内容
    val glassPage = rememberGlassPageBackdrop()
    val scrollBehavior = MiuixScrollBehavior()
    val colorScheme = MiuixTheme.colorScheme
    val scope = rememberCoroutineScope()
    var isRefreshing by remember { mutableStateOf(false) }

    // 后台任务完成（用户提前离开任务页、结果不会经 taskLauncher 回来）→ 静默重查可升级列表
    val pkgStateSnap by LiveUpdateState.pkgState.collectAsState()
    var lastFinished by remember { mutableStateOf<LiveUpdateState.PkgState?>(null) }
    LaunchedEffect(pkgStateSnap) {
        val s = pkgStateSnap
        if (s != null && s.finished && s !== lastFinished) {
            lastFinished = s
            onUpgradableListChanged(PkgRepo.getUpgradable(context))
        }
    }

    fun refresh() {
        if (isRefreshing) return
        isRefreshing = true
        scope.launch {
            onUpgradableListChanged(PkgRepo.getUpgradable(context))
            isRefreshing = false
        }
    }

    Scaffold(
        contentWindowInsets = androidx.compose.foundation.layout.WindowInsets(0),
        topBar = {
            GlassTopAppBar(
                title = "软件包升级",
                subtitle = if (upgradableList.isNotEmpty()) "${upgradableList.size} 个软件包可升级" else "",
                isContentScrolled = listState.canScrollBackward,
                backdrop = glassPage.backdrop,
                scrollBehavior = scrollBehavior,
                navigationIcon = {
                    GlassIconButton(onClick = onBack) {
                        Icon(
                            imageVector = MiuixGlassIcons.ChevronBackward,
                            contentDescription = stringResource(R.string.back),
                            tint = colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                },
                actions = {
                    GlassIconButton(onClick = { refresh() }) {
                        Icon(
                            imageVector = MiuixGlassIcons.Refresh,
                            contentDescription = stringResource(R.string.refresh_sources),
                            tint = colorScheme.onSurface,
                            modifier = Modifier.size(24.dp)
                        )
                    }
                }
            )
        }
    ) { innerPadding ->
        Box(
            modifier = Modifier
                .then(glassPage.contentModifier)
                .fillMaxSize()
        ) {
            LazyColumn(
                state = listState,
                modifier = Modifier.fillMaxSize().nestedScroll(scrollBehavior.nestedScrollConnection),
                contentPadding = standaloneContentPadding(
                    innerPadding,
                    start = 12.dp,
                    end = 12.dp,
                    top = 6.dp,
                    bottom = navBarBottomPadding + if (upgradableList.isEmpty()) 16.dp else 92.dp
                ),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (upgradableList.isEmpty()) {
                    item {
                        EmptyStateView(
                            message = if (isRefreshing) "正在检查可升级的软件包 ..."
                                      else "没有可升级的软件包，所有包均已是最新版本"
                        )
                    }
                } else {
                    items(upgradableList, key = { it.name }) { info ->
                        UpgradablePackageCard(
                            info = info,
                            onClick = {
                                onOpenDetail(
                                    PackageInfo(
                                        name = info.name,
                                        version = info.currentVersion,
                                        isInstalled = true,
                                        upgradeToVersion = info.newVersion
                                    )
                                )
                            }
                        )
                    }
                }
            }

            // 底部「升级所有包」— 自软件包管理页顶栏迁移而来，本页主操作
            if (upgradableList.isNotEmpty()) {
                Box(
                    modifier = Modifier
                        .align(Alignment.BottomCenter)
                        .fillMaxWidth()
                        .background(colorScheme.surfaceContainer)
                        .padding(
                            start = 16.dp,
                            end = 16.dp,
                            top = 12.dp,
                            bottom = 12.dp + navBarBottomPadding
                        )
                ) {
                    Button(
                        onClick = {
                            taskLauncher.launch(
                                PackageTaskActivity.newTaskIntent(
                                    context,
                                    LiveUpdateState.PkgOperation.UPGRADE,
                                    ""
                                )
                            )
                        },
                        modifier = Modifier.fillMaxWidth(),
                        colors = ButtonDefaults.buttonColors(color = AccentBlue)
                    ) {
                        Text(
                            text = "${stringResource(R.string.upgrade_all_packages)}（${upgradableList.size}）",
                            fontSize = 15.sp,
                            fontWeight = FontWeight.Medium,
                            color = Color.White
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun UpgradablePackageCard(
    info: PkgRepo.UpgradableInfo,
    onClick: () -> Unit
) {
    MiuixCard(modifier = Modifier.fillMaxWidth()) {
        ArrowPreference(
            title = info.name,
            summary = when {
                info.currentVersion.isNotBlank() && info.newVersion.isNotBlank() ->
                    "v${info.currentVersion} → v${info.newVersion}"
                info.newVersion.isNotBlank() -> "可升级至 v${info.newVersion}"
                else -> "有可用更新"
            },
            onClick = onClick,
            startAction = {
                Icon(
                    imageVector = MiuixGlassIcons.Download,
                    contentDescription = null,
                    tint = UpgradeOrange,
                    modifier = Modifier.size(22.dp)
                )
            },
            endActions = {
                Box(
                    modifier = Modifier
                        .background(
                            color = UpgradeOrange.copy(alpha = 0.12f),
                            shape = RoundedCornerShape(12.dp)
                        )
                        .padding(horizontal = 10.dp, vertical = 4.dp)
                ) {
                    Text(
                        text = "可升级",
                        fontSize = 12.sp,
                        color = UpgradeOrange
                    )
                }
            }
        )
    }
}
