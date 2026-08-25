with open(r'd:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\OverviewScreen.kt', 'r', encoding='utf-8') as f:
    lines = f.readlines()

# The section we want to replace starts at line 972 (0-indexed: 971)
# and ends at line 1023 (0-indexed: 1022)
# We want to keep lines 1024-1025 (the closing braces)

# Let me verify the exact content
print("Lines 971-1025 (0-indexed 970-1024):")
for i in range(970, min(1025, len(lines))):
    print(f"  {i+1}: {repr(lines[i])}")
