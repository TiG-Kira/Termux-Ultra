"""
Fix: Remove wrongly-added ProcessListActivity navigation from CPU/GPU/Memory cards.
Only ProcessListCard should navigate to ProcessListActivity.
Also add isEditMode to SessionsCard.
"""

KT = r'D:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\OverviewScreen.kt'
with open(KT, 'r', encoding='utf-8') as f:
    c = f.read()

fixes = []

# 1. Fix SessionsCard (line ~1732): Add isEditMode, no onClick
old_sessions = '''OverviewCardContainer(
        card = card,
        onEditClick = onEditClick
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {'''
new_sessions = '''OverviewCardContainer(
        card = card,
        onEditClick = onEditClick,
        isEditMode = isEditMode
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(16.dp)
        ) {'''
if old_sessions in c:
    c = c.replace(old_sessions, new_sessions)
    fixes.append("1: Fixed SessionsCard - added isEditMode")
else:
    fixes.append("1: SessionsCard pattern not found")

# 2. Fix CpuMonitorCard: Remove ProcessListActivity onClick
old_cpu = '''OverviewCardContainer(
            card = card,
            onEditClick = onEditClick,
            isEditMode = isEditMode,
            onClick = {
                val intent = android.content.Intent(context, com.termux.app.activities.ProcessListActivity::class.java)
                context.startActivity(intent)
            }
        ) {'''
new_cpu = '''OverviewCardContainer(
            card = card,
            onEditClick = onEditClick,
            isEditMode = isEditMode
        ) {'''
if old_cpu in c:
    c = c.replace(old_cpu, new_cpu)
    fixes.append("2: Fixed CpuMonitorCard - removed wrong navigation")
else:
    fixes.append("2: CpuMonitorCard pattern not found (checking...)")
    # There are 3 occurrences of this pattern, first should be CPU
    count = c.count(old_cpu)
    fixes.append(f"   Found {count} occurrences of CPU-style navigation pattern")

# 3. Fix GpuMonitorCard: Same pattern (3 occurrences total)
# After fixing CPU, there should be 2 left
# Let's fix all non-ProcessListCard occurrences

# Actually let me count how many ProcessListActivity navigations exist
nav_count = c.count('ProcessListActivity::class.java')
fixes.append(f"3: Total ProcessListActivity navigation references: {nav_count}")

# There are 4: CPU, GPU, Memory, ProcessListCard
# We need to remove 3 (CPU, GPU, Memory) and keep 1 (ProcessListCard)

# The pattern is identical for all 4. Let's identify them by context.
# ProcessListCard has a 'val context' before it that the others might not have.
# Actually the script added val context to all of them via the ProcessListCard fix.
# So I need a different way to identify.

# Let me look at what's before each occurrence.
# They're in order: CPU, GPU, Memory, ProcessListCard

# Strategy: Replace the first 3 occurrences, keep the 4th.
# The old_cpu pattern appears 3 times (CPU, GPU, Memory).
# After fixing the first, there are 2 left that need fixing.

# Let me just replace all 3 remaining wrong ones
for i in range(3):
    if old_cpu in c:
        c = c.replace(old_cpu, new_cpu, 1)
        fixes.append(f"   Fixed occurrence #{i+1}")
    else:
        break

# Verify
nav_count_after = c.count('ProcessListActivity::class.java')
fixes.append(f"4: After fix, ProcessListActivity navigation references: {nav_count_after}")

# Also check if CPU/GPU/Memory cards got a 'val context = LocalContext.current' added
# by the ProcessListCard fix. If so, remove those.
val_ctx_count = c.count('\n    val context = LocalContext.current\n')
fixes.append(f"5: val context occurrences: {val_ctx_count}")

# The SessionsCard at line 1732 might also have been changed
# Let's verify it has isEditMode now
if 'OverviewCardContainer(\n        card = card,\n        onEditClick = onEditClick,\n        isEditMode = isEditMode\n    )' in c:
    fixes.append("6: SessionsCard isEditMode verified")
else:
    fixes.append("6: SessionsCard may not have isEditMode")

# StopAllCard
if 'onClick = { if (sessionCount > 0) showConfirmDialog = true }' in c:
    fixes.append("7: StopAllCard onClick verified (correct)")

# ResourceActionCard  
if 'onLaunchAction(action)' in c:
    fixes.append("8: ResourceActionCard onClick verified (correct)")

# ProcessListCard - should still have navigation
if c.count('ProcessListActivity::class.java') == 1:
    fixes.append("9: ProcessListCard navigation correctly preserved")

with open(KT, 'w', encoding='utf-8') as f:
    f.write(c)

print("\n".join(fixes))
print("\nDone!")