with open(r'd:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\OverviewScreen.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# Find ALL occurrences of ') { padding ->'
import re
matches = [(m.start(), m.end()) for m in re.finditer(r'\) \{ padding ->', content)]
print(f"Found {len(matches)} occurrences of padding ->")
for i, (start, end) in enumerate(matches):
    context = content[start:start+50]
    print(f"  {i}: pos={start}, context={repr(context)}")

# The second occurrence should be the one we want to replace
# Let's find the content between the second ') { padding ->' and its matching '}'
