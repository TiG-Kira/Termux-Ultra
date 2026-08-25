with open(r'd:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\OverviewScreen.kt', 'r', encoding='utf-8') as f:
    lines = f.readlines()

# Search for "Tips" in the file
print("Searching for 'Tips'...")
for i, line in enumerate(lines):
    if 'Tips' in line and 'Agent' in line:
        print(f"  Line {i+1}: {repr(line)}")

# Also check the last 30 lines
print("\nLast 30 lines:")
for i in range(max(0, len(lines)-30), len(lines)):
    print(f"  {i+1}: {repr(lines[i])}")
