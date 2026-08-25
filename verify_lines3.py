with open(r'd:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\OverviewScreen.kt', 'r', encoding='utf-8') as f:
    lines = f.readlines()

# Find the end of OverviewScreen function
# Search for the pattern of closing braces before TipsAgentCard
print("Searching for end of OverviewScreen...")
for i in range(len(lines) - 1, max(0, len(lines) - 50), -1):
    if 'Tips & Agent Card' in lines[i]:
        print(f"Found Tips marker at line {i+1}")
        # Print lines before it
        for j in range(max(0, i-10), i+5):
            print(f"  {j+1}: {repr(lines[j])}")
        break
