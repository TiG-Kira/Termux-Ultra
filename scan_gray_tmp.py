# -*- coding: utf-8 -*-
import io

p = r'd:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\TerminalListScreen.kt'
c = io.open(p, encoding='utf-8').read()
lines = c.split('\n')
for i, line in enumerate(lines, 1):
    s = line.rstrip()
    if any(k in s for k in ['termuxService =', 'TermuxService.bindService', 'getCurrentSession', 'currentSession']):
        print(f'TerminalListScreen {i}: {s[:140]}')

print()
p2 = r'd:\KiTerminal-UX\app\src\main\java\com\termux\app\MainActivity.kt'
c2 = io.open(p2, encoding='utf-8').read()
lines2 = c2.split('\n')
for i, line in enumerate(lines2, 1):
    s = line.rstrip()
    if 'TerminalListScreen(' in s or 'currentSession' in s:
        print(f'MainActivity {i}: {s[:140]}')

print()
# TerminalDetailScreen L1141 所在函数作用域：找 L147 函数结束位置（简化：搜 L240-1150 之间的 fun 定义）
p3 = r'd:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\TerminalDetailScreen.kt'
c3 = io.open(p3, encoding='utf-8').read()
lines3 = c3.split('\n')
for i, line in enumerate(lines3, 1):
    if i > 1150:
        break
    s = line.rstrip()
    if s.startswith('fun ') or s.startswith('private fun ') or s.startswith('public fun ') or (s.startswith('@Composable') and i > 240):
        print(f'TerminalDetailScreen {i}: {s[:100]}')
