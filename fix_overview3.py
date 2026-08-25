with open(r'd:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\OverviewScreen.kt', 'r', encoding='utf-8') as f:
    lines = f.readlines()

# The LazyVerticalGrid is at line 978 (0-indexed: 977)
# We need to replace lines 972-1023 (0-indexed: 971-1022)
# which is from ') { padding ->' to the closing '    }' before '}'

# Let's find the exact range
start_idx = None
end_idx = None

for i, line in enumerate(lines):
    if i >= 970 and ') { padding ->' in line:
        start_idx = i
    if start_idx is not None and i > start_idx and line.strip() == '}' and i < start_idx + 60:
        # Check if this is the closing brace for the Scaffold content
        if i + 1 < len(lines) and lines[i+1].strip() == '}':
            end_idx = i
            break

if start_idx and end_idx:
    print(f'Found section from line {start_idx+1} to {end_idx+1}')
    print(f'First line: {repr(lines[start_idx])}')
    print(f'Last line: {repr(lines[end_idx])}')
    
    # New content
    new_lines = [
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
    new_file_lines = lines[:start_idx] + new_lines + lines[end_idx+1:]
    
    with open(r'd:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\OverviewScreen.kt', 'w', encoding='utf-8') as f:
        f.writelines(new_file_lines)
    
    print('Successfully replaced!')
else:
    print(f'Could not find section: start_idx={start_idx}, end_idx={end_idx}')
    # Manual search
    for i, line in enumerate(lines):
        if ') { padding ->' in line:
            print(f'Found padding at line {i+1}: {repr(line)}')
