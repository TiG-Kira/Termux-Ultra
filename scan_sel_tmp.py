# -*- coding: utf-8 -*-
import io

files = [
    r'd:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\TerminalListScreen.kt',
    r'd:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\TerminalDetailScreen.kt',
]
for p in files:
    print('=' * 25, p.split('\\')[-1])
    c = io.open(p, encoding='utf-8').read()
    lines = c.split('\n')
    for i, line in enumerate(lines, 1):
        s = line.rstrip()
        if any(k in s for k in ['isCurrentSession', 'currentSession ==', '== currentSession', 'primaryContainer', 'primary.copy', 'containerColor']):
            print(f'{i}: {s[:150]}')
