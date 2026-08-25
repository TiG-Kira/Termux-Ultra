with open(r'd:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\OverviewScreen.kt', 'r', encoding='utf-8') as f:
    lines = f.readlines()

# Check lines around 1080-1100
print("Lines 1080-1100:")
for i in range(1079, min(1100, len(lines))):
    print(f"  {i+1}: {repr(lines[i])}")
