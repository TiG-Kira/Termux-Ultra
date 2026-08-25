with open(r'd:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\OverviewScreen.kt', 'r', encoding='utf-8') as f:
    lines = f.readlines()

# Find the line numbers that contain 'LazyVerticalGrid('
grid_lines = []
for i, line in enumerate(lines):
    if 'LazyVerticalGrid(' in line:
        grid_lines.append(i)

print(f'Found LazyVerticalGrid at lines: {[x+1 for x in grid_lines]}')

# Find lines containing ') { padding ->'
padding_lines = []
for i, line in enumerate(lines):
    if ') { padding ->' in line:
        padding_lines.append(i)

print(f'Found padding -> at lines: {[x+1 for x in padding_lines]}')

# Find lines containing '// Calculate waterfall layout'
comment_lines = []
for i, line in enumerate(lines):
    if '// Calculate waterfall layout' in line:
        comment_lines.append(i)

print(f'Found waterfall comment at lines: {[x+1 for x in comment_lines]}')
