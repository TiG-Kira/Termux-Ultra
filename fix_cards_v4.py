"""
Fix ProcessListCard: add isEditMode + onClick navigation to ProcessListActivity.
Also remove accidentally-added val context from non-process cards.
"""

KT = r'D:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\OverviewScreen.kt'
with open(KT, 'r', encoding='utf-8') as f:
    c = f.read()

fixes = []

# 1. Fix ProcessListCard's OverviewCardContainer call
# Find the ProcessListCard function
idx_func = c.find('fun ProcessListCard(')
if idx_func < 0:
    fixes.append("ERROR: ProcessListCard not found!")
else:
    # Find its OverviewCardContainer call
    end_search = c.find('// Process List Card Content', idx_func)
    if end_search < 0:
        end_search = c.find('private fun ProcessListContent', idx_func)
    if end_search < 0:
        end_search = idx_func + 3000
    
    func_area = c[idx_func:end_search]
    
    # Find: OverviewCardContainer(card = card, onEditClick = onEditClick) {
    old_call = 'OverviewCardContainer(card = card, onEditClick = onEditClick) {'
    new_call = '''OverviewCardContainer(
            card = card,
            onEditClick = onEditClick,
            isEditMode = isEditMode,
            onClick = {
                val intent = android.content.Intent(context, com.termux.app.activities.ProcessListActivity::class.java)
                context.startActivity(intent)
            }
        ) {'''
    
    if old_call in func_area:
        # Replace only within ProcessListCard
        new_func_area = func_area.replace(old_call, new_call, 1)
        c = c[:idx_func] + new_func_area + c[idx_func + len(func_area):]
        fixes.append("1: Fixed ProcessListCard - added isEditMode + navigation onClick")
    else:
        fixes.append("1: ProcessListCard call pattern not found")
        # Show what's there
        call_idx = func_area.find('OverviewCardContainer(')
        if call_idx >= 0:
            fixes.append(f"   Found at offset {call_idx}: {repr(func_area[call_idx:call_idx+100])}")

# 2. Make sure ProcessListCard has val context
# Check if it already has one
if 'val context = LocalContext.current' in c[idx_func:idx_func+500]:
    fixes.append("2: ProcessListCard already has val context")
else:
    # Add val context after the opening brace of ProcessListCard
    # Find: fun ProcessListCard(...) { ...
    sig_end = c.find(') {', idx_func)
    if sig_end >= 0:
        brace_pos = sig_end + 1
        # Make sure there's a newline and indent before adding
        insert = '\n    val context = LocalContext.current'
        c = c[:brace_pos] + insert + c[brace_pos:]
        fixes.append("2: Added val context to ProcessListCard")

# 3. Check if CPU/GPU/Memory cards have unwanted val context
# Find CpuMonitorCard
idx_cpu = c.find('fun CpuMonitorCard(')
if idx_cpu >= 0:
    if 'val context = LocalContext.current' in c[idx_cpu:idx_cpu+300]:
        fixes.append("3: WARNING - CpuMonitorCard has unwanted val context!")
        # Remove it
        import re
        cpu_area = c[idx_cpu:idx_cpu+500]
        # Remove: \n    val context = LocalContext.current (only the first occurrence)
        cleaned = re.sub(r'\n    val context = LocalContext\.current', '', cpu_area, count=1)
        c = c[:idx_cpu] + cleaned + c[idx_cpu+500:]
        fixes.append("   Removed val context from CpuMonitorCard")
    else:
        fixes.append("3: CpuMonitorCard doesn't have unwanted val context ✅")

idx_gpu = c.find('fun GpuMonitorCard(')
if idx_gpu >= 0:
    if 'val context = LocalContext.current' in c[idx_gpu:idx_gpu+300]:
        fixes.append("4: WARNING - GpuMonitorCard has unwanted val context!")
        import re
        gpu_area = c[idx_gpu:idx_gpu+500]
        cleaned = re.sub(r'\n    val context = LocalContext\.current', '', gpu_area, count=1)
        c = c[:idx_gpu] + cleaned + c[idx_gpu+500:]
        fixes.append("   Removed val context from GpuMonitorCard")
    else:
        fixes.append("4: GpuMonitorCard doesn't have unwanted val context ✅")

idx_mem = c.find('fun MemoryMonitorCard(')
if idx_mem >= 0:
    if 'val context = LocalContext.current' in c[idx_mem:idx_mem+300]:
        fixes.append("5: WARNING - MemoryMonitorCard has unwanted val context!")
        import re
        mem_area = c[idx_mem:idx_mem+500]
        cleaned = re.sub(r'\n    val context = LocalContext\.current', '', mem_area, count=1)
        c = c[:idx_mem] + cleaned + c[idx_mem+500:]
        fixes.append("   Removed val context from MemoryMonitorCard")
    else:
        fixes.append("5: MemoryMonitorCard doesn't have unwanted val context ✅")

# 4. Verify final state
nav_count = c.count('ProcessListActivity::class.java')
fixes.append(f"6: Final ProcessListActivity navigation count: {nav_count}")

with open(KT, 'w', encoding='utf-8') as f:
    f.write(c)

print("\n".join(fixes))
print("\nDone!")