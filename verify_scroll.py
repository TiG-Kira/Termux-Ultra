with open(r'd:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\OverviewScreen.kt', 'r', encoding='utf-8') as f:
    lines = f.readlines()

# Find the line containing 'val scrollBehavior = MiuixScrollBehavior()'
for i, line in enumerate(lines):
    if 'val scrollBehavior = MiuixScrollBehavior()' in line:
        print(f"Found scrollBehavior at line {i+1}")
        print(f"Content: {repr(line)}")
        # Print next few lines
        for j in range(i, min(i+10, len(lines))):
            print(f"  {j+1}: {repr(lines[j])}")
        break
