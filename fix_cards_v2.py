"""
Comprehensive fix script for:
1. OverviewCardContainer - add isEditMode + edit button overlay
2. ProcessListCard - add navigation to ProcessListActivity
3. Update all call sites to pass isEditMode
4. Increase WIDE_CARD_HEIGHT
"""

KT = r'D:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\OverviewScreen.kt'
with open(KT, 'r', encoding='utf-8') as f:
    lines = f.readlines()

changes = []

# Find line numbers for key functions
container_start = None
container_end = None
wide_height_line = None

for i, line in enumerate(lines):
    if 'private fun OverviewCardContainer(' in line and container_start is None:
        container_start = i
    if container_start is not None and container_end is None:
        # Find matching closing brace
        stripped = line.strip()
        if stripped == '}' and i > container_start + 3:
            container_end = i
            break
    if 'private val WIDE_CARD_HEIGHT' in line:
        wide_height_line = i

changes.append(f"Found OverviewCardContainer at lines {container_start+1}-{container_end+1}")
changes.append(f"Found WIDE_CARD_HEIGHT at line {wide_height_line+1}")

# 1. Replace WIDE_CARD_HEIGHT
if wide_height_line is not None:
    old = lines[wide_height_line]
    lines[wide_height_line] = old.replace('160.dp', '200.dp')
    changes.append(f"1: Updated WIDE_CARD_HEIGHT: {old.strip()} -> {lines[wide_height_line].strip()}")

# 2. Replace OverviewCardContainer
if container_start is not None and container_end is not None:
    new_container = '''@Composable
private fun OverviewCardContainer(
    card: OverviewCardConfig,
    onEditClick: () -> Unit,
    isEditMode: Boolean = false,
    backgroundColor: Color? = null,
    onClick: (() -> Unit)? = null,
    clickEnabled: Boolean = true,
    content: @Composable ColumnScope.() -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val isWide = card.size == CardSize.WIDE || card.type == OverviewCardType.PROCESS_LIST
    val surfaceColor = backgroundColor ?: if (isDark) Color(0xFF1C1C1E) else Color(0xFFFAFAFA)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .then(if (isWide) Modifier.height(WIDE_CARD_HEIGHT) else Modifier.aspectRatio(1f))
            .clip(RoundedCornerShape(20.dp))
            .then(if (onClick != null && clickEnabled && !isEditMode) Modifier.clickable { onClick() } else Modifier)
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .background(surfaceColor)
                    .padding(16.dp),
                verticalArrangement = Arrangement.SpaceBetween
            ) {
                content()
            }
            if (isEditMode) {
                IconButton(
                    onClick = onEditClick,
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(4.dp)
                        .size(32.dp)
                ) {
                    Icon(
                        imageVector = Icons.Rounded.Edit,
                        contentDescription = null,
                        modifier = Modifier.size(18.dp),
                        tint = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }
            }
        }
    }
}
'''
    # Replace lines from container_start to container_end+1
    lines[container_start:container_end+1] = [new_container]
    changes.append("2: Replaced OverviewCardContainer with isEditMode + edit button")

# 3. Now re-read the file to update call sites
with open(KT, 'w', encoding='utf-8') as f:
    f.writelines(lines)

# Re-read for call site updates
with open(KT, 'r', encoding='utf-8') as f:
    c = f.read()

# 3a. Update simple calls: OverviewCardContainer(card = card, onEditClick = onEditClick) {
old_simple = 'OverviewCardContainer(card = card, onEditClick = onEditClick) {'
new_simple = 'OverviewCardContainer(card = card, onEditClick = onEditClick, isEditMode = isEditMode) {'
count = c.count(old_simple)
if count > 0:
    c = c.replace(old_simple, new_simple)
    changes.append(f"3a: Updated {count} simple call sites")
else:
    changes.append("3a: No simple call sites found")

# 3b. ProcessListCard needs onClick navigation
# Find: OverviewCardContainer(card = card, onEditClick = onEditClick) { inside ProcessListCard
# This should now be: OverviewCardContainer(card = card, onEditClick = onEditClick, isEditMode = isEditMode, onClick = { navigate }) {

# Since we already replaced all simple calls, ProcessListCard should also have isEditMode now.
# We need to add onClick to the ProcessListCard's call specifically.

# Let's find the ProcessListCard function and update its OverviewCardContainer call
# The pattern after our fix should be:
# OverviewCardContainer(card = card, onEditClick = onEditClick, isEditMode = isEditMode) {
# But ProcessListCard should have onClick added.

# Let's find the ProcessListCard area
idx_process = c.find('fun ProcessListCard(')
if idx_process >= 0:
    # Find the OverviewCardContainer call within ProcessListCard
    # Search within a reasonable range
    search_end = c.find('@Composable\nprivate fun ProcessListContent', idx_process)
    if search_end < 0:
        search_end = idx_process + 2000
    
    area = c[idx_process:search_end]
    
    # Find the OverviewCardContainer call in this area
    old_process_call = 'OverviewCardContainer(card = card, onEditClick = onEditClick, isEditMode = isEditMode) {'
    new_process_call = 'OverviewCardContainer(\n            card = card,\n            onEditClick = onEditClick,\n            isEditMode = isEditMode,\n            onClick = {\n                val intent = android.content.Intent(androidx.compose.ui.platform.LocalContext.current, com.termux.app.activities.ProcessListActivity::class.java)\n                androidx.compose.ui.platform.LocalContext.current.startActivity(intent)\n            }\n        ) {'
    
    # Actually this approach is complex. Let me use a simpler approach:
    # Add a context parameter to ProcessListCard and add onClick
    
    # Better approach: find the exact location of the call and add onClick
    call_idx = area.find('OverviewCardContainer(card = card, onEditClick = onEditClick, isEditMode = isEditMode) {')
    if call_idx >= 0:
        # We're inside ProcessListCard. Let's also add LocalContext.current
        # Find ProcessListCard function start and add context
        
        # Find: fun ProcessListCard(
        sig_idx = area.find('fun ProcessListCard(')
        sig_end = area.find(') {', sig_idx) + 3
        func_area = area[:sig_end]
        
        # Add val context after the opening brace
        # Find the first { after the signature
        brace_pos = func_area.find('{')
        if brace_pos >= 0:
            # Add: val context = LocalContext.current\n
            insert = '\n    val context = LocalContext.current'
            new_sig = func_area[:brace_pos+1] + insert + func_area[brace_pos+1:]
            c = c[:idx_process] + new_sig + c[idx_process + len(func_area):]
            changes.append("3b: Added val context to ProcessListCard")
        
        # Now update the OverviewCardContainer call to include onClick
        old_call = 'OverviewCardContainer(card = card, onEditClick = onEditClick, isEditMode = isEditMode) {'
        new_call = '''OverviewCardContainer(
            card = card,
            onEditClick = onEditClick,
            isEditMode = isEditMode,
            onClick = {
                val intent = android.content.Intent(context, com.termux.app.activities.ProcessListActivity::class.java)
                context.startActivity(intent)
            }
        ) {'''
        
        c = c.replace(old_call, new_call)
        changes.append("3c: Updated ProcessListCard OverviewCardContainer with onClick navigation")
    else:
        changes.append("3c: OverviewCardContainer call not found in ProcessListCard area")
else:
    changes.append("3b: ProcessListCard function not found")

# 3d. Update multi-line calls (StopAllCard, ResourceActionCard)
# These already have onClick, just need isEditMode added
# Find the multi-line patterns

# StopAllCard pattern:
old_stopall = '''OverviewCardContainer(
        card = card,
        onEditClick = onEditClick,
        onClick = { if (sessionCount > 0) showConfirmDialog = true }
    ) {'''
new_stopall = '''OverviewCardContainer(
        card = card,
        onEditClick = onEditClick,
        isEditMode = isEditMode,
        onClick = { if (sessionCount > 0) showConfirmDialog = true }
    ) {'''
if old_stopall in c:
    c = c.replace(old_stopall, new_stopall)
    changes.append("3d: Updated StopAllCard with isEditMode")
else:
    changes.append("3d: StopAllCard pattern not found (may already have isEditMode)")
    # Check if it already has isEditMode
    if 'isEditMode' in c[c.find('fun StopAllCard('):c.find('fun StopAllCard(')+500]:
        changes.append("   (already has isEditMode)")

# ResourceActionCard pattern:
old_res = '''OverviewCardContainer(
        card = card,
        onEditClick = onEditClick,
        onClick = {
            if (action != null) {
                onLaunchAction(action)
            } else {
                showSelectDialog = true
            }
        }
    ) {'''
new_res = '''OverviewCardContainer(
        card = card,
        onEditClick = onEditClick,
        isEditMode = isEditMode,
        onClick = {
            if (action != null) {
                onLaunchAction(action)
            } else {
                showSelectDialog = true
            }
        }
    ) {'''
if old_res in c:
    c = c.replace(old_res, new_res)
    changes.append("3e: Updated ResourceActionCard with isEditMode")
else:
    changes.append("3e: ResourceActionCard pattern not found")

# 4. Make sure Box and Alignment imports are present (for the edit button)
# Check if Box and Alignment are already imported
if 'import androidx.compose.foundation.layout.Box' not in c:
    # Add Box import
    c = c.replace(
        'import androidx.compose.foundation.layout.Arrangement',
        'import androidx.compose.foundation.layout.Arrangement\nimport androidx.compose.foundation.layout.Box'
    )
    changes.append("4: Added Box import")

if 'import androidx.compose.ui.Alignment' not in c:
    c = c.replace(
        'import androidx.compose.foundation.layout.Box\nimport androidx.compose.foundation.layout.Column',
        'import androidx.compose.foundation.layout.Box\nimport androidx.compose.foundation.layout.Column\nimport androidx.compose.ui.Alignment'
    )
    changes.append("4b: Added Alignment import")

with open(KT, 'w', encoding='utf-8') as f:
    f.write(c)

print("\n".join(changes))
print("\nDone!")