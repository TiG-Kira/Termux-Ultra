import re

with open(r'D:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\OverviewScreen.kt', 'r', encoding='utf-8') as f:
    lines = f.readlines()
    content = f.read()

# === ISSUE 1: CPU/GPU duplicate labels ===
print('=== CPU Monitor Card ===')
for i, line in enumerate(lines):
    if 'fun CpuMonitorCard(' in line:
        for j in range(i, min(i+150, len(lines))):
            print(f'{j+1}: {lines[j].rstrip()}')
        break

print()
print('=== GPU Monitor Card (loadLabel area) ===')
# Find the GPU card's progress bar section
idx = content.find('loadLabel ?: ""')
if idx >= 0:
    start = max(0, idx - 500)
    end = min(len(content), idx + 500)
    print(content[start:end])

print()
print('=== Memory Monitor Card ===')
for i, line in enumerate(lines):
    if 'fun MemoryMonitorCard(' in line:
        for j in range(i, min(i+120, len(lines))):
            print(f'{j+1}: {lines[j].rstrip()}')
        break