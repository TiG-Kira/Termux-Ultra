with open(r'D:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\OverviewScreen.kt', 'r', encoding='utf-8') as f:
    lines = f.readlines()

# GPU card progress section (after my previous fix)
print('=== GPU card progress section ===')
for i in range(1895, min(1950, len(lines))):
    print(f'{i+1}: {lines[i].rstrip()}')

print()
# Process card area - check full width logic
print('=== ProcessListCard full ===')
for i, line in enumerate(lines):
    if 'fun ProcessListCard(' in line:
        for j in range(i, min(i+75, len(lines))):
            print(f'{j+1}: {lines[j].rstrip()}')
        break

print()
# TipsAgentCard (插件中心) 
print('=== TipsAgentCard ===')
for i, line in enumerate(lines):
    if 'fun TipsAgentCard(' in line:
        for j in range(i, min(i+120, len(lines))):
            print(f'{j+1}: {lines[j].rstrip()}')
        break

print()
# Status bar / TopAppBar area
print('=== TopAppBar & Status Bar area ===')
for i in range(834, min(870, len(lines))):
    print(f'{i+1}: {lines[i].rstrip()}')