with open(r'D:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\OverviewScreen.kt', 'r', encoding='utf-8') as f:
    lines = f.readlines()

# Check current state of GPU card progress section
print('=== GPU Card progress (lines 1900-1925) ===')
for i in range(1899, min(1925, len(lines))):
    print(f'{i+1}: {lines[i].rstrip()}')

print()
print('=== Memory Card progress (lines 2007-2035) ===')
for i in range(2006, min(2035, len(lines))):
    print(f'{i+1}: {lines[i].rstrip()}')

print()
print('=== TopAppBar section (lines 825-870) ===')
for i in range(824, min(870, len(lines))):
    print(f'{i+1}: {lines[i].rstrip()}')

print()
# Check if migration function exists
print('=== migrateCardSizes check ===')
with open(r'D:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\OverviewScreen.kt', 'r', encoding='utf-8') as f:
    content = f.read()
    
if 'migrateCardSizes' in content:
    idx = content.find('migrateCardSizes')
    print(f'migrateCardSizes found at index {idx}')
    print(content[idx:idx+200])
else:
    print('❌ migrateCardSizes NOT FOUND!')

# Check grid span
print()
print('=== Grid span (lines 885-892) ===')
for i in range(884, min(892, len(lines))):
    print(f'{i+1}: {lines[i].rstrip()}')