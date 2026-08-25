"""
Fix 1: Add edit button to OverviewCardContainer
Fix 2: Update all call sites to pass isEditMode
Fix 3: Increase WIDE_CARD_HEIGHT for process card
"""

KT = r'D:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\OverviewScreen.kt'
with open(KT, 'r', encoding='utf-8') as f:
    c = f.read()

fixes = []

# Fix 1: Update OverviewCardContainer to support isEditMode
old_container = '''@Composable
private fun OverviewCardContainer(
    card: OverviewCardConfig,
    onEditClick: () -> Unit,
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
            .then(if (onClick != null && clickEnabled) Modifier.clickable { onClick() } else Modifier)
    ) {
        Column(
            modifier = Modifier
                .fillMaxSize()
                .background(surfaceColor)
                .padding(16.dp),
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            content()
        }
    }
}'''

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
}'''

if old_container in c:
    c = c.replace(old_container, new_container)
    fixes.append('1: OK - Updated OverviewCardContainer with isEditMode support')
else:
    fixes.append('1: FAILED - old container not found')
    idx = c.find('fun OverviewCardContainer(')
    if idx >= 0:
        fixes.append(f'  Found at {idx}: {repr(c[idx:idx+200])}')

# Fix 2: Update WIDE_CARD_HEIGHT
old_height = 'private val WIDE_CARD_HEIGHT = 160.dp'
new_height = 'private val WIDE_CARD_HEIGHT = 200.dp'
if old_height in c:
    c = c.replace(old_height, new_height)
    fixes.append('2: OK - Increased WIDE_CARD_HEIGHT from 160 to 200')
else:
    fixes.append('2: FAILED - height constant not found')

# Fix 3: Update call sites to pass isEditMode
# Pattern: OverviewCardContainer(card = card, onEditClick = onEditClick) {
# Need to add isEditMode = isEditMode

# Call sites that need updating:
# 1. CpuMonitorCard: OverviewCardContainer(card = card, onEditClick = onEditClick) {
# 2. GpuMonitorCard: same
# 3. MemoryMonitorCard: same
# 4. ProcessListCard: same
# 5. StopAllCard: OverviewCardContainer( card = card, onEditClick = onEditClick, onClick = ...
# 6. ResourceActionCard: same

# For cards that pass onEditClick but not isEditMode, we need to add isEditMode parameter
# The card functions already have isEditMode parameter

import re

# Match: OverviewCardContainer(...)
# We need to add isEditMode = isEditMode before the closing paren

# Pattern 1: Simple calls: OverviewCardContainer(card = card, onEditClick = onEditClick) {
old_simple = 'OverviewCardContainer(card = card, onEditClick = onEditClick) {'
new_simple = 'OverviewCardContainer(card = card, onEditClick = onEditClick, isEditMode = isEditMode) {'
count = c.count(old_simple)
if count > 0:
    c = c.replace(old_simple, new_simple)
    fixes.append(f'3a: OK - Updated {count} simple call sites')
else:
    fixes.append('3a: FAILED - no simple calls found')

# Pattern 2: Multi-line calls with onClick:
old_multi = '''OverviewCardContainer(
        card = card,
        onEditClick = onEditClick,
        onClick = { if (sessionCount > 0) showConfirmDialog = true }
    ) {'''
new_multi = '''OverviewCardContainer(
        card = card,
        onEditClick = onEditClick,
        isEditMode = isEditMode,
        onClick = { if (sessionCount > 0) showConfirmDialog = true }
    ) {'''
if old_multi in c:
    c = c.replace(old_multi, new_multi)
    fixes.append('3b: OK - Updated StopAllCard call site')
else:
    fixes.append('3b: FAILED')

# Pattern 3: ResourceActionCard multi-line call
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
    fixes.append('3c: OK - Updated ResourceActionCard call site')
else:
    fixes.append('3c: FAILED')

with open(KT, 'w', encoding='utf-8') as f:
    f.write(c)

print('Results:')
for fix in fixes:
    print(f'  {fix}')
print('Done')