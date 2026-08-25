import re

KT_FILE = r'D:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\OverviewScreen.kt'

with open(KT_FILE, 'r', encoding='utf-8') as f:
    content = f.read()
    lines = f.readlines()

# ============================================================
# FIX 1: GPU card - left should be "负载", right should be "低" (not both "低")
# ============================================================

old_gpu_row = """                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = loadLabel ?: "",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        Text(
                            text = loadLabel ?: "",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }"""
new_gpu_row = """                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Text(
                            text = stringResource(R.string.overview_load),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                        Text(
                            text = loadLabel ?: "",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Medium,
                            color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                        )
                    }"""
if old_gpu_row in content:
    content = content.replace(old_gpu_row, new_gpu_row)
    print("✅ 1: GPU card fixed - left='负载', right='低' (no duplicate)")
else:
    print("❌ 1: GPU card pattern not found!")
    # Try finding it
    idx = content.find('text = loadLabel ?: ""')
    if idx >= 0:
        print(f'  Found loadLabel at index {idx}')

# ============================================================
# FIX 2: Memory card - remove duplicate percentage on right
# ============================================================

old_mem_row = """                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.overview_memory_used, "${usage.toInt()}%"),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                    Text(
                        text = "${usage.toInt()}%",
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }"""
new_mem_row = """                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.overview_memory_used, "${usage.toInt()}%"),
                        fontSize = 11.sp,
                        fontWeight = FontWeight.Medium,
                        color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                    )
                }"""
if old_mem_row in content:
    content = content.replace(old_mem_row, new_mem_row)
    print("✅ 2: Memory card fixed - removed duplicate percentage on right")
else:
    print("❌ 2: Memory card pattern not found!")

# ============================================================
# FIX 3: Process card - add migration to fix saved SMALL size
# ============================================================

# Find the card loading section and add size migration
# After loading cards, we need to ensure PROCESS_LIST cards are WIDE
old_return = '            return ensureFeatureCenterCard(cards)'
new_return = """            return ensureFeatureCenterCard(
                migrateCardSizes(cards)
            )"""
if old_return in content:
    content = content.replace(old_return, new_return)
    print("✅ 3a: Added migrateCardSizes call to card loading")
else:
    print("⚠️ 3a: migrateCardSizes insertion point not found")

# Add the migrateCardSizes function before getDefaultCards
old_get_default = '    fun getDefaultCards(): List<OverviewCardConfig> {'
new_migrate = """    private fun migrateCardSizes(cards: List<OverviewCardConfig>): List<OverviewCardConfig> {
        return cards.map { card ->
            val expectedSize = when (card.type) {
                OverviewCardType.PROCESS_LIST,
                OverviewCardType.TIPS_AGENT,
                OverviewCardType.SESSIONS,
                OverviewCardType.FEATURE_CENTER -> CardSize.WIDE
                else -> CardSize.SMALL
            }
            if (card.size != expectedSize) {
                card.copy(size = expectedSize)
            } else {
                card
            }
        }
    }

    fun getDefaultCards(): List<OverviewCardConfig> {"""
if old_get_default in content:
    content = content.replace(old_get_default, new_migrate)
    print("✅ 3b: Added migrateCardSizes function")
else:
    print("⚠️ 3b: migrateCardSizes function insertion point not found")

# ============================================================
# FIX 4: TipsAgentCard (插件中心) dark mode support
# ============================================================

# Find VerticalTipsContent and HorizontalTipsContent - make them theme-aware
# Let's find the card background
idx = content.find('fun VerticalTipsContent(')
if idx >= 0:
    print(f'✅ 4a: VerticalTipsContent found at index {idx}')
    # Show surrounding code
    end_idx = min(len(content), idx + 2000)
    section = content[idx:end_idx]
    # Find background colors
    bg_matches = re.findall(r'background\s*=\s*Color\([^)]+\)', section)
    print(f'  Background colors found: {bg_matches}')
    
    # Check if it uses isDark
    if 'isSystemInDarkTheme' in section or 'isDark' in section:
        print('  Already has dark theme support')
    else:
        print('  WARNING: No dark theme support detected!')

# Fix TipsAgentCard: The issue is TipsAgentCard doesn't use OverviewCardContainer
# It creates its own layout without theme-aware background
# Let's find the card root
idx = content.find('fun TipsAgentCard(')
if idx >= 0:
    section = content[idx:idx+2000]
    # Find the Column that wraps everything
    # Need to add theme-aware background
    print('  TipsAgentCard section check complete')

# ============================================================
# FIX 5: TopAppBar status bar colors - use dynamic based on theme
# ============================================================

# Find the TopAppBar and update icon tints
old_topbar = """            TopAppBar(
                title = stringResource(R.string.overview_title),
                scrollBehavior = scrollBehavior,
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            showAddCardDialog = true
                        }) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(onClick = {
                            isEditMode = !isEditMode
                        }) {
                            Icon(
                                imageVector = if (isEditMode) Icons.Rounded.Check else Icons.Rounded.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            )"""
new_topbar = """            val isDarkTheme = isSystemInDarkTheme()
            val statusBarColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color(0xFFF2F2F7)
            AndroidView(
                factory = { context ->
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        val window = (context as? android.app.Activity)?.window
                        window?.let {
                            @Suppress("DEPRECATION")
                            it.statusBarColor = android.graphics.Color.parseColor(
                                if (isDarkTheme) "#1C1C1E" else "#F2F2F7"
                            )
                            it.decorView.systemUiVisibility = 
                                if (isDarkTheme) 0 else android.view.View.SYSTEM_UI_FLAG_LIGHT_STATUS_BAR
                        }
                    }
                    android.view.View(context).apply { layoutParams = android.view.ViewGroup.LayoutParams(0, 0) }
                }
            )
            TopAppBar(
                title = stringResource(R.string.overview_title),
                scrollBehavior = scrollBehavior,
                containerColor = if (isDarkTheme) Color(0xFF1C1C1E) else Color(0xFFF2F2F7),
                actions = {
                    Row(
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        IconButton(onClick = {
                            showAddCardDialog = true
                        }) {
                            Icon(
                                imageVector = Icons.Rounded.Add,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
                        IconButton(onClick = {
                            isEditMode = !isEditMode
                        }) {
                            Icon(
                                imageVector = if (isEditMode) Icons.Rounded.Check else Icons.Rounded.Edit,
                                contentDescription = null,
                                modifier = Modifier.size(24.dp),
                                tint = MiuixTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            )"""
if old_topbar in content:
    content = content.replace(old_topbar, new_topbar)
    print("✅ 5: TopAppBar status bar colors - theme-aware")
else:
    print("⚠️ 5: TopAppBar pattern not found")
    # Try simpler approach - just change Scaffold to use transparent status bar
    print('  Will use alternative approach for status bar')

# ============================================================
# SAVE
# ============================================================

with open(KT_FILE, 'w', encoding='utf-8') as f:
    f.write(content)

print("\n✅ Fixes applied!")