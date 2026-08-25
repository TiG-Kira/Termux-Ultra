with open(r'd:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\OverviewScreen.kt', 'r', encoding='utf-8') as f:
    lines = f.readlines()

# Check lines 870-900
print("Lines 870-900:")
for i in range(869, min(900, len(lines))):
    print(f"  {i+1}: {repr(lines[i])}")
