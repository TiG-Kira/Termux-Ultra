"""
接入 LiveUpdateState 到包管理器：
  - PackageDetailScreen.runInstallUninstall 接入 startPkg/finishPkg
  - 进度弹窗加"后台运行"按钮
  - 后台操作完成后自动重新弹窗
  - PackageManagerScreen 的 update/upgradeAll 同样接入
"""
import os

BASE = r'd:\KiTerminal-UX\app\src\main\java\com\termux\app\compose'

errors = []

# ========== 1. PackageDetailScreen.kt ==========
PATH = os.path.join(BASE, 'PackageDetailScreen.kt')
with open(PATH, 'r', encoding='utf-8') as f:
    src = f.read()

# 1a. 改写 runInstallUninstall — 加 backgrounded 参数 + LiveUpdateState 接入
OLD_RUN = '''    fun runInstallUninstall(isInstall: Boolean, forceRemoveLock: Boolean = false) {
        progressTitle = if (isInstall) "正在安装 ${pkg.name}" else "正在卸载 ${pkg.name}"
        progressLog = ""
        progressSuccess = null
        showProgressDialog = true
        scope.launch {
            val result = if (isInstall) PkgRepo.install(context, pkg.name) else PkgRepo.uninstall(context, pkg.name)
            val ok = result.first
            val log = result.second
            if (!ok && !forceRemoveLock && isLockError(log)) {
                progressLog = log
                progressSuccess = false
                // Show lock dialog after showing the lock error briefly
                showProgressDialog = false
                pendingAction = { runInstallUninstall(isInstall, forceRemoveLock = true) }
                showLockDialog = true
            } else {
                progressLog = log
                progressSuccess = ok
            }
        }
    }'''

NEW_RUN = '''    fun runInstallUninstall(isInstall: Boolean, forceRemoveLock: Boolean = false, backgrounded: Boolean = false) {
        progressTitle = if (isInstall) "正在安装 ${pkg.name}" else "正在卸载 ${pkg.name}"
        progressLog = ""
        progressSuccess = null
        if (!backgrounded) showProgressDialog = true
        val op = if (isInstall) LiveUpdateState.PkgOperation.INSTALL else LiveUpdateState.PkgOperation.UNINSTALL
        LiveUpdateState.startPkg(op, pkg.name, backgrounded = backgrounded)
        scope.launch {
            val result = if (isInstall) PkgRepo.install(context, pkg.name) else PkgRepo.uninstall(context, pkg.name)
            val ok = result.first
            val log = result.second
            LiveUpdateState.finishPkg(ok)
            if (!ok && !forceRemoveLock && isLockError(log)) {
                progressLog = log
                progressSuccess = false
                if (backgrounded) {
                    // 后台运行遇到锁 — 重新弹窗让用户处理
                    showProgressDialog = true
                } else {
                    showProgressDialog = false
                }
                pendingAction = { runInstallUninstall(isInstall, forceRemoveLock = true, backgrounded = backgrounded) }
                showLockDialog = true
            } else {
                progressLog = log
                progressSuccess = ok
                if (backgrounded) showProgressDialog = true
            }
        }
    }'''

src = src.replace(OLD_RUN, NEW_RUN)
if NEW_RUN.split('\n')[0] not in src:
    errors.append('PackageDetailScreen: runInstallUninstall replace failed')

# 1b. 在进度弹窗的 Loading indicator 下方加"后台运行"按钮（后台模式）
OLD_LOADING = '''                        // Loading indicator
                        if (progressSuccess == null) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp), color = AccentBlue, strokeWidth = 3.dp)
                            }
                            Spacer(Modifier.height(12.dp))
                        }'''

NEW_LOADING = '''                        // Loading indicator
                        if (progressSuccess == null) {
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.Center) {
                                CircularProgressIndicator(modifier = Modifier.size(28.dp), color = AccentBlue, strokeWidth = 3.dp)
                            }
                            Spacer(Modifier.height(12.dp))
                            // 后台运行按钮（前台模式下允许用户切换到后台）
                            Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                TextButton(
                                    text = "后台运行",
                                    onClick = { showProgressDialog = false },
                                    modifier = Modifier.weight(1f)
                                )
                            }
                            Spacer(Modifier.height(4.dp))
                        }'''

src = src.replace(OLD_LOADING, NEW_LOADING)
if NEW_LOADING.split('\n')[0] not in src:
    errors.append('PackageDetailScreen: loading block replace failed')

with open(PATH, 'w', encoding='utf-8') as f:
    f.write(src)

print('PackageDetailScreen.kt 修改完成')

# ========== 2. PackageManagerScreen.kt ==========
PATH2 = os.path.join(BASE, 'PackageManagerScreen.kt')
with open(PATH2, 'r', encoding='utf-8') as f:
    src2 = f.read()

# 2a. 改 update 调用
OLD_UPDATE = '''                        onClick = {
                            progressTitle = "正在刷新软件源"
                            progressLog = ""
                            progressSuccess = null
                            showProgressDialog = true
                            scope.launch {
                                val (ok, log) = PkgRepo.update(context)
                                progressLog = log
                                progressSuccess = ok
                                if (ok) {
                                    installedList = PkgRepo.getInstalled(context)
                                    if (searchQuery.isBlank()) {
                                        availableList = PkgRepo.getAvailableAll(context)
                                    }
                                }
                            }
                        }'''

NEW_UPDATE = '''                        onClick = {
                            progressTitle = "正在刷新软件源"
                            progressLog = ""
                            progressSuccess = null
                            showProgressDialog = true
                            LiveUpdateState.startPkg(LiveUpdateState.PkgOperation.UPDATE, "", backgrounded = false)
                            scope.launch {
                                val (ok, log) = PkgRepo.update(context)
                                LiveUpdateState.finishPkg(ok)
                                progressLog = log
                                progressSuccess = ok
                                if (ok) {
                                    installedList = PkgRepo.getInstalled(context)
                                    if (searchQuery.isBlank()) {
                                        availableList = PkgRepo.getAvailableAll(context)
                                    }
                                }
                            }
                        }'''

src2 = src2.replace(OLD_UPDATE, NEW_UPDATE)

# 2b. 改 upgradeAll 调用
OLD_UPGRADE = '''                        onClick = {
                            progressTitle = "正在升级所有包"
                            progressLog = ""
                            progressSuccess = null
                            showProgressDialog = true
                            scope.launch {
                                val (ok, log) = PkgRepo.upgradeAll(context)
                                progressLog = log
                                progressSuccess = ok
                                if (ok) {
                                    installedList = PkgRepo.getInstalled(context)
                                }
                            }
                        }'''

NEW_UPGRADE = '''                        onClick = {
                            progressTitle = "正在升级所有包"
                            progressLog = ""
                            progressSuccess = null
                            showProgressDialog = true
                            LiveUpdateState.startPkg(LiveUpdateState.PkgOperation.UPGRADE, "", backgrounded = false)
                            scope.launch {
                                val (ok, log) = PkgRepo.upgradeAll(context)
                                LiveUpdateState.finishPkg(ok)
                                progressLog = log
                                progressSuccess = ok
                                if (ok) {
                                    installedList = PkgRepo.getInstalled(context)
                                }
                            }
                        }'''

src2 = src2.replace(OLD_UPGRADE, NEW_UPGRADE)

with open(PATH2, 'w', encoding='utf-8') as f:
    f.write(src2)

print('PackageManagerScreen.kt 修改完成')

# ========== 验证 ==========
print()
print('========== 验证落地 ==========')
for path, checks in [
    (PATH, ['LiveUpdateState.startPkg', 'LiveUpdateState.finishPkg', '后台运行']),
    (PATH2, ['LiveUpdateState.startPkg', 'LiveUpdateState.finishPkg']),
]:
    with open(path, 'r', encoding='utf-8') as f:
        c = f.read()
    for kw in checks:
        status = 'OK' if kw in c else 'MISS'
        print(f'  {os.path.basename(path)}: {kw} -> {status}')

if errors:
    print(f'\\n错误: {errors}')
else:
    print('\\n全部通过')
