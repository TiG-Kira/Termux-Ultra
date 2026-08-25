with open(r'd:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\OverviewScreen.kt', 'r', encoding='utf-8') as f:
    lines = f.readlines()

# Check lines 900-950
print("Lines 900-950:")
for i in range(899, min(950, len(lines))):
    print(f"  {i+1}: {repr(lines[i])}")
