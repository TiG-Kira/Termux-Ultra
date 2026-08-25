with open(r'd:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\OverviewScreen.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# Find the LAST occurrence of ') { padding ->'
import re
matches = [(m.start(), m.end()) for m in re.finditer(r'\) \{ padding ->', content)]
print(f"Found {len(matches)} occurrences of padding ->")

if len(matches) >= 2:
    # Use the second occurrence
    start_pos = matches[1][0]
    print(f"Using second occurrence at position {start_pos}")
    print(f"Context: {repr(content[start_pos:start_pos+100])}")
elif len(matches) == 1:
    # Use the only occurrence
    start_pos = matches[0][0]
    print(f"Using only occurrence at position {start_pos}")
    print(f"Context: {repr(content[start_pos:start_pos+100])}")
else:
    print("No occurrences found!")
    exit(1)
