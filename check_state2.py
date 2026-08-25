with open(r'D:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\OverviewScreen.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# 1. GPU card - check progress bar section
print('=== GPU card progress bar section ===')
idx = content.find('OverviewCardContainer(card = card, onEditClick = onEditClick) {')
# Find the second one (GPU card's container)
second_idx = content.find('OverviewCardContainer(card = card, onEditClick = onEditClick) {', idx + 1)
if second_idx > 0:
    section = content[second_idx:second_idx+1500]
    # Find the Row with loadLabel
    load_idx = section.find('loadLabel')
    if load_idx >= 0:
        print(section[max(0,load_idx-100):load_idx+200])

print()
# 2. Memory card - check progress section
print('=== Memory card progress section ===')
mem_idx = content.find('fun MemoryMonitorCard(')
if mem_idx >= 0:
    mem_section = content[mem_idx:mem_idx+2000]
    # Find the second Row (progress row)
    row_matches = [m.start() for m in re.finditer(r'Row\(', mem_section)]
    if len(row_matches) >= 2:
        second_row = row_matches[1]
        print(mem_section[second_row:second_row+400])

print()
# 3. Grid span section  
print('=== Grid span section ===')
span_idx = content.find('span = { card ->')
if span_idx >= 0:
    print(content[span_idx:span_idx+200])

print()
# 4. TopAppBar current state
print('=== TopAppBar current state ===')
topbar_idx = content.find('val isDarkTheme = isSystemInDarkTheme()')
if topbar_idx >= 0:
    print(content[topbar_idx:topbar_idx+500])