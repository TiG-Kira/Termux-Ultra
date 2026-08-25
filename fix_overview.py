with open(r'd:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\OverviewScreen.kt', 'r', encoding='utf-8') as f:
    content = f.read()

old_text = """    ) { padding ->
        // Calculate waterfall layout: reorder cards for optimal placement
        val orderedCards = remember(filteredCards) {
            calculateWaterfallOrder(filteredCards)
        }
        
        LazyVerticalGrid(
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = navBarBottomPadding + 16.dp, start = 16.dp, end = 16.dp)
        ) {
            items(
                items = orderedCards,
                span = { card ->
                    if (card.size == CardSize.WIDE) GridItemSpan(2) else GridItemSpan(1)
                }
            ) { card ->
                CardItem(
                    card = card,
                    context = context,
                    isEditMode = isEditMode,
                    cpuUsage = cpuUsage,
                    cpuTemperature = cpuTemperature,
                    gpuUsage = gpuUsage,
                    memUsage = memUsage,
                    memTotalKb = memTotalKb,
                    cpuHistory = cpuHistory,
                    gpuHistory = gpuHistory,
                    memHistory = memHistory,
                    processList = processList,
                    runningSessions = runningSessions,
                    stoppedSessions = stoppedSessions,
                    sessions = sessions,
                    isWakeLockEnabled = isWakeLockEnabled,
                    onSessionClick = onSessionClick,
                    onStopAllSessions = onStopAllSessions,
                    onNewTerminal = onNewTerminal,
                    onExecuteScript = onExecuteScript,
                    selectedCardId = selectedCardId,
                    onCardSelected = { selectedCardId = it },
                    onShowCardSettings = { showCardSettings = true },
                    onUpdateCard = { updatedCard ->
                        cards = cards.map { if (it.id == updatedCard.id) updatedCard else it }
                    }
                )
            }
        }
    }
}"""

new_text = """    ) { padding ->
        val orderedCards = remember(filteredCards) {
            calculateWaterfallOrder(filteredCards)
        }
        
        LazyVerticalGrid(
            state = lazyGridState,
            columns = GridCells.Fixed(2),
            modifier = Modifier
                .fillMaxSize()
                .padding(padding)
                .nestedScroll(scrollBehavior.nestedScrollConnection),
            horizontalArrangement = Arrangement.spacedBy(8.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
            contentPadding = PaddingValues(top = 8.dp, bottom = navBarBottomPadding + 16.dp, start = 16.dp, end = 16.dp)
        ) {
            // Header item (App Logo + Title + Version)
            item(span = { GridItemSpan(2) }) {
                val appIcon = remember {
                    ContextCompat.getDrawable(context, R.mipmap.ic_launcher)
                        ?.toBitmap()
                        ?.asImageBitmap()
                        ?.let { BitmapPainter(it) }
                }
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .graphicsLayer {
                            alpha = headerAlpha
                        }
                        .padding(top = 16.dp, bottom = 8.dp),
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(72.dp)
                            .clip(RoundedCornerShape(16.dp))
                            .background(
                                if (isDarkTheme) Color.White.copy(alpha = 0.15f)
                                else Color.White.copy(alpha = 0.35f)
                            ),
                        contentAlignment = Alignment.Center
                    ) {
                        if (appIcon != null) {
                            Image(
                                painter = appIcon,
                                contentDescription = "Logo",
                                modifier = Modifier.size(56.dp)
                            )
                        } else {
                            Icon(
                                painter = painterResource(R.drawable.ic_terminal),
                                contentDescription = "Logo",
                                modifier = Modifier.size(40.dp),
                                tint = Color.White
                            )
                        }
                    }
                    
                    Spacer(modifier = Modifier.height(12.dp))
                    
                    Text(
                        text = stringResource(R.string.app_name),
                        fontSize = 24.sp,
                        fontWeight = FontWeight.Bold,
                        color = Color.White
                    )
                    
                    Spacer(modifier = Modifier.height(4.dp))
                    
                    Text(
                        text = "v$currentVersion",
                        fontSize = 14.sp,
                        color = Color.White.copy(alpha = 0.8f)
                    )
                }
            }
            
            items(
                items = orderedCards,
                span = { card ->
                    if (card.size == CardSize.WIDE) GridItemSpan(2) else GridItemSpan(1)
                }
            ) { card ->
                CardItem(
                    card = card,
                    context = context,
                    isEditMode = isEditMode,
                    cpuUsage = cpuUsage,
                    cpuTemperature = cpuTemperature,
                    gpuUsage = gpuUsage,
                    memUsage = memUsage,
                    memTotalKb = memTotalKb,
                    cpuHistory = cpuHistory,
                    gpuHistory = gpuHistory,
                    memHistory = memHistory,
                    processList = processList,
                    runningSessions = runningSessions,
                    stoppedSessions = stoppedSessions,
                    sessions = sessions,
                    isWakeLockEnabled = isWakeLockEnabled,
                    onSessionClick = onSessionClick,
                    onStopAllSessions = onStopAllSessions,
                    onNewTerminal = onNewTerminal,
                    onExecuteScript = onExecuteScript,
                    selectedCardId = selectedCardId,
                    onCardSelected = { selectedCardId = it },
                    onShowCardSettings = { showCardSettings = true },
                    onUpdateCard = { updatedCard ->
                        cards = cards.map { if (it.id == updatedCard.id) updatedCard else it }
                    }
                )
            }
        }
    }
}"""

if old_text in content:
    new_content = content.replace(old_text, new_text)
    with open(r'd:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\OverviewScreen.kt', 'w', encoding='utf-8') as f:
        f.write(new_content)
    print('Successfully replaced!')
else:
    print('Old text not found!')
    # Debug output
    idx = content.find('LazyVerticalGrid(')
    if idx >= 0:
        print(f'Found LazyVerticalGrid at {idx}')
        print('Context around that area:')
        print(repr(content[idx-100:idx+200]))
