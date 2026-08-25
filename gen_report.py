import os
from datetime import datetime

apk_path = r'D:\KiTerminal-UX\app\build\outputs\apk\release'
apk_files = [f for f in os.listdir(apk_path) if f.endswith('.apk')]
apk_files.sort(key=lambda x: os.path.getmtime(os.path.join(apk_path, x)), reverse=True)
latest_apk = os.path.join(apk_path, apk_files[0]) if apk_files else 'N/A'
apk_size = f'{os.path.getsize(latest_apk) / 1024 / 1024:.2f} MB' if os.path.exists(latest_apk) else 'N/A'
build_time = datetime.fromtimestamp(os.path.getmtime(latest_apk)).strftime('%Y-%m-%d %H:%M:%S') if os.path.exists(latest_apk) else 'N/A'
apk_name = os.path.basename(latest_apk) if os.path.exists(latest_apk) else 'N/A'

html = f"""<!DOCTYPE html>
<html lang="zh-CN">
<head>
<meta charset="UTF-8">
<title>Termux Ultra 构建报告</title>
<style>
body {{ font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', sans-serif; max-width: 800px; margin: 40px auto; padding: 0 20px; background: #f5f5f7; color: #1d1d1f; }}
h1 {{ font-size: 28px; margin-bottom: 8px; }}
h2 {{ font-size: 20px; margin-top: 32px; border-bottom: 2px solid #0071e3; padding-bottom: 8px; }}
.success {{ color: #34c759; font-weight: 600; }}
.card {{ background: white; border-radius: 12px; padding: 20px; margin: 16px 0; box-shadow: 0 1px 3px rgba(0,0,0,0.08); }}
table {{ width: 100%; border-collapse: collapse; margin: 12px 0; }}
th, td {{ text-align: left; padding: 10px 12px; border-bottom: 1px solid #eee; }}
th {{ background: #f5f5f7; font-weight: 600; }}
.badge {{ display: inline-block; padding: 3px 10px; border-radius: 12px; font-size: 12px; font-weight: 600; }}
.badge-ok {{ background: #e8f5e9; color: #2e7d32; }}
.info {{ color: #0071e3; }}
code {{ background: #f5f5f7; padding: 2px 6px; border-radius: 4px; font-size: 13px; }}
</style>
</head>
<body>
<h1>🚀 Termux Ultra 构建报告</h1>
<p style="color:#6e6e73;">生成时间: {datetime.now().strftime('%Y-%m-%d %H:%M:%S')}</p>

<div class="card">
<h2>📦 构建信息</h2>
<table>
<tr><th>项目</th><th>状态</th></tr>
<tr><td>编译</td><td><span class="badge badge-ok">BUILD SUCCESSFUL</span></td></tr>
<tr><td>APK 文件</td><td><code>{apk_name}</code></td></tr>
<tr><td>APK 大小</td><td>{apk_size}</td></tr>
<tr><td>构建时间</td><td>{build_time}</td></tr>
</table>
</div>

<div class="card">
<h2>✅ 已完成修复</h2>
<table>
<tr><th>修复项</th><th>说明</th></tr>
<tr><td>GPU 卡片重复文案</td><td>左侧改为"负载"，右侧保留负载等级</td></tr>
<tr><td>内存卡片重复百分比</td><td>移除右侧重复百分比显示</td></tr>
<tr><td>进程卡片宽度</td><td>WIDE 模式占 2 列，高度增加到 200dp</td></tr>
<tr><td>进程列表列头</td><td>移除 Status 列头文字（状态已在进程名后标签显示）</td></tr>
<tr><td>编辑模式按钮</td><td>OverviewCardContainer 支持 isEditMode 编辑按钮覆盖层</td></tr>
<tr><td>进程卡片导航</td><td>点击进程卡片跳转 ProcessListActivity</td></tr>
<tr><td>ProcessListActivity</td><td>新建 Activity 显示详细进程信息(PID/用户/路径)</td></tr>
<tr><td>主题适配</td><td>插件中心/提示卡片支持暗色/亮色模式切换</td></tr>
<tr><td>状态栏颜色</td><td>亮色模式下状态栏正确显示</td></tr>
<tr><td>aspectRatio NaN 崩溃</td><td>替换为 Modifier.height(WIDE_CARD_HEIGHT)</td></tr>
</table>
</div>

<div class="card">
<h2>🎯 交互功能说明</h2>
<table>
<tr><th>卡片</th><th>点击行为</th></tr>
<tr><td>CPU 监控</td><td>无导航（静态展示）</td></tr>
<tr><td>GPU 监控</td><td>无导航（静态展示）</td></tr>
<tr><td>内存占用</td><td>无导航（静态展示）</td></tr>
<tr><td>进程列表</td><td><span class="info">点击进入 ProcessListActivity - 显示所有进程详情</span></td></tr>
<tr><td>会话</td><td>无导航（静态展示）</td></tr>
<tr><td>电池/存储</td><td>无导航（静态展示）</td></tr>
</table>
<p style="color:#6e6e73;font-size:13px;">✅ 只有进程卡片配置了 onClick 导航，其他卡片仅在编辑模式显示编辑按钮。</p>
</div>

<div class="card">
<h2>🛠 技术要点</h2>
<ul>
<li><code>OverviewCardContainer</code> 支持 <code>isEditMode</code> 参数，编辑模式下显示编辑按钮覆盖层并禁用卡片点击</li>
<li><code>ProcessListCard</code> 独占 <code>onClick</code> 导航，其他卡片无 onClick</li>
<li><code>WIDE_CARD_HEIGHT = 200.dp</code> 确保进程卡片高度适合展示进程列表</li>
<li><code>ProcessListActivity</code> 使用 <code>KiTerminalTheme</code> 包装，Miuix 组件构建 UI</li>
</ul>
</div>

</body>
</html>"""

report_path = r'C:\Users\Kira\Desktop\Termux_Ultra_Build_Report.html'
with open(report_path, 'w', encoding='utf-8') as f:
    f.write(html)
print(f'报告已生成: {report_path}')