with open(r'd:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\OverviewScreen.kt', 'r', encoding='utf-8') as f:
    lines = f.readlines()

# The file has the following structure:
# - Lines up to ~971: The new Box structure with gradient, overlay, and Scaffold
# - Lines 972-1025: The OLD content section that needs to be replaced
# - Line 1026: Empty line
# - Lines 1027+: Next function (TipsAgentCard)

# Let's find the exact line numbers
# We need to find:
# 1. The line containing ') { padding ->' inside the Scaffold
# 2. The matching closing '    }' before '}'

scaffold_start = None
scaffold_end = None
found_scaffold = False
brace_count = 0

for i, line in enumerate(lines):
    if not found_scaffold and ') { padding ->' in line:
        scaffold_start = i
        found_scaffold = True
        brace_count = 0
        continue
    
    if found_scaffold:
        stripped = line.strip()
        # Count braces to find the matching close
        if stripped == '}' and brace_count == 0:
            scaffold_end = i
            break
        elif stripped.startswith('}') or stripped == '}':
            brace_count += 1
        if stripped == '}' and brace_count > 0:
            brace_count -= 1
            if brace_count == 0:
                scaffold_end = i
                break

if scaffold_start is not None and scaffold_end is not None:
    print(f"Found section from line {scaffold_start+1} to {scaffold_end+1}")
    print(f"Start: {repr(lines[scaffold_start])}")
    print(f"End: {repr(lines[scaffold_end])}")
else:
    print(f"Could not find section: start={scaffold_start}, end={scaffold_end}")
    # Let me just find all lines with relevant patterns
    for i, line in enumerate(lines):
        if 'LazyVerticalGrid' in line or ') { padding' in line or line.strip() == '}':
            if i >= 900 and i <= 1030:
                print(f"  Line {i+1}: {repr(line)}")
