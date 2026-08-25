with open(r'd:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\OverviewScreen.kt', 'r', encoding='utf-8') as f:
    lines = f.readlines()

# Find the line containing ') { padding ->'
# We want the one that is the content lambda of the Scaffold
# It should be after the topBar closing

scaffold_content_start = None
for i, line in enumerate(lines):
    if ') { padding ->' in line:
        scaffold_content_start = i
        break

if scaffold_content_start is None:
    print("Could not find ') { padding ->'!")
    exit(1)

print(f"Found ') {{ padding ->' at line {scaffold_content_start + 1}")

# Find the matching closing brace for the content lambda
# After ') { padding ->', we need to find the matching '    }' that closes the content lambda
# Then the next '}' closes the outer Box
brace_depth = 0
content_end = None
found_first_brace = False

for i in range(scaffold_content_start, len(lines)):
    stripped = lines[i].strip()
    
    if not found_first_brace:
        # We're looking for the LazyVerticalGrid opening
        if '{' in stripped and 'LazyVerticalGrid' in stripped:
            found_first_brace = True
            brace_depth = 1
        continue
    
    # Count braces
    brace_depth += stripped.count('{') - stripped.count('}')
    
    if brace_depth <= 0 and stripped == '}':
        # This should close the LazyVerticalGrid content
        # Next line should close the content lambda
        content_end = i + 1  # The line with '    }'
        break

if content_end is None:
    print("Could not find end of content section!")
    # Manual search
    for i in range(scaffold_content_start, min(scaffold_content_start + 60, len(lines))):
        print(f"  {i+1}: {repr(lines[i])}")
    exit(1)

print(f"Content section from line {scaffold_content_start + 1} to {content_end + 1}")
print(f"Start: {repr(lines[scaffold_content_start])}")
print(f"End: {repr(lines[content_end])}")

# New content to replace
new_content = [
    '        ) { padding ->\n',
    '        val orderedCards = remember(filteredCards) {\n',
    '            calculateWaterfallOrder(filteredCards)\n',
    '        }\n',
    '        \n',
    '        LazyVerticalGrid(\n',
    '            state = lazyGridState,\n',
    '            columns = GridCells.Fixed(2),\n',
    '            modifier = Modifier\n',
    '                .fillMaxSize()\n',
    '                .padding(padding)\n',
    '                .nestedScroll(scrollBehavior.nestedScrollConnection),\n',
    '            horizontalArrangement = Arrangement.spacedBy(8.dp),\n',
    '            verticalArrangement = Arrangement.spacedBy(8.dp),\n',
    '            contentPadding = PaddingValues(top = 8.dp, bottom = navBarBottomPadding + 16.dp, start = 16.dp, end = 16.dp)\n',
    '        ) {\n',
    '            // Header item (App Logo + Title + Version)\n',
    '            item(span = { GridItemSpan(2) }) {\n',
    '                val appIcon = remember {\n',
    '                    ContextCompat.getDrawable(context, R.mipmap.ic_launcher)\n',
    '                        ?.toBitmap()\n',
    '                        ?.asImageBitmap()\n',
    '                        ?.let { BitmapPainter(it) }\n',
    '                }\n',
    '                Column(\n',
    '                    modifier = Modifier\n',
    '                        .fillMaxWidth()\n',
    '                        .graphicsLayer {\n',
    '                            alpha = headerAlpha\n',
    '                        }\n',
    '                        .padding(top = 16.dp, bottom = 8.dp),\n',
    '                    horizontalAlignment = Alignment.CenterHorizontally\n',
    '                ) {\n',
    '                    Box(\n',
    '                        modifier = Modifier\n',
    '                            .size(72.dp)\n',
    '                            .clip(RoundedCornerShape(16.dp))\n',
    '                            .background(\n',
    '                                if (isDarkTheme) Color.White.copy(alpha = 0.15f)\n',
    '                                else Color.White.copy(alpha = 0.35f)\n',
    '                            ),\n',
    '                        contentAlignment = Alignment.Center\n',
    '                    ) {\n',
    '                        if (appIcon != null) {\n',
    '                            Image(\n',
    '                                painter = appIcon,\n',
    '                                contentDescription = "Logo",\n',
    '                                modifier = Modifier.size(56.dp)\n',
    '                            )\n',
    '                        } else {\n',
    '                            Icon(\n',
    '                                painter = painterResource(R.drawable.ic_terminal),\n',
    '                                contentDescription = "Logo",\n',
    '                                modifier = Modifier.size(40.dp),\n',
    '                                tint = Color.White\n',
    '                            )\n',
    '                        }\n',
    '                    }\n',
    '                    \n',
    '                    Spacer(modifier = Modifier.height(12.dp))\n',
    '                    \n',
    '                    Text(\n',
    '                        text = stringResource(R.string.app_name),\n',
    '                        fontSize = 24.sp,\n',
    '                        fontWeight = FontWeight.Bold,\n',
    '                        color = Color.White\n',
    '                    )\n',
    '                    \n',
    '                    Spacer(modifier = Modifier.height(4.dp))\n',
    '                    \n',
    '                    Text(\n',
    '                        text = "v$currentVersion",\n',
    '                        fontSize = 14.sp,\n',
    '                        color = Color.White.copy(alpha = 0.8f)\n',
    '                    )\n',
    '                }\n',
    '            }\n',
    '            \n',
    '            items(\n',
    '                items = orderedCards,\n',
    '                span = { card ->\n',
    '                    if (card.size == CardSize.WIDE) GridItemSpan(2) else GridItemSpan(1)\n',
    '                }\n',
    '            ) { card ->\n',
    '                CardItem(\n',
    '                    card = card,\n',
    '                    context = context,\n',
    '                    isEditMode = isEditMode,\n',
    '                    cpuUsage = cpuUsage,\n',
    '                    cpuTemperature = cpuTemperature,\n',
    '                    gpuUsage = gpuUsage,\n',
    '                    memUsage = memUsage,\n',
    '                    memTotalKb = memTotalKb,\n',
    '                    cpuHistory = cpuHistory,\n',
    '                    gpuHistory = gpuHistory,\n',
    '                    memHistory = memHistory,\n',
    '                    processList = processList,\n',
    '                    runningSessions = runningSessions,\n',
    '                    stoppedSessions = stoppedSessions,\n',
    '                    sessions = sessions,\n',
    '                    isWakeLockEnabled = isWakeLockEnabled,\n',
    '                    onSessionClick = onSessionClick,\n',
    '                    onStopAllSessions = onStopAllSessions,\n',
    '                    onNewTerminal = onNewTerminal,\n',
    '                    onExecuteScript = onExecuteScript,\n',
    '                    selectedCardId = selectedCardId,\n',
    '                    onCardSelected = { selectedCardId = it },\n',
    '                    onShowCardSettings = { showCardSettings = true },\n',
    '                    onUpdateCard = { updatedCard ->\n',
    '                        cards = cards.map { if (it.id == updatedCard.id) updatedCard else it }\n',
    '                    }\n',
    '                )\n',
    '            }\n',
    '        }\n',
    '    }\n',
    '}\n',
]

# Replace the section
new_lines = lines[:scaffold_content_start] + new_content + lines[content_end + 1:]

with open(r'd:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\OverviewScreen.kt', 'w', encoding='utf-8') as f:
    f.writelines(new_lines)

print("Successfully replaced!")
print(f"Replaced lines {scaffold_content_start + 1} to {content_end + 1}")
