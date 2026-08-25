with open(r'd:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\OverviewScreen.kt', 'r', encoding='utf-8') as f:
    content = f.read()

# Find the section to replace: from "    Scaffold(" to the end of the OverviewScreen function
# The old section starts with the Scaffold call and ends with the closing braces before TipsAgentCard

old_section = '''    Scaffold(
        modifier = Modifier.fillMaxSize(),
        contentWindowInsets = WindowInsets(0, 0, 0, 0),
        topBar = {

            TopAppBar(
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
            )
        }
    ) { padding ->'''

if old_section in content:
    print("Found old section!")
    
    # New section with all the missing pieces
    new_section = '''    // Calculate scroll-based animation values
    val headerHeightPx = with(LocalDensity.current) { 120.dp.toPx() }
    val scrollProgress = (lazyGridState.firstVisibleItemIndex * 1000f + 
        lazyGridState.firstVisibleItemScrollOffset.toFloat()) / headerHeightPx
    val headerAlpha = (1f - scrollProgress.coerceIn(0f, 1f))
    val topBarAlpha = scrollProgress.coerceIn(0f, 1f)
    val backgroundAlpha = scrollProgress.coerceIn(0f, 1f)
    
    // Animate colors for TopAppBar and background
    val topBarColorAnim by animateColorAsState(
        targetValue = if (isDarkTheme) 
            Color.Black.copy(alpha = topBarAlpha) 
        else 
            Color.White.copy(alpha = topBarAlpha),
        label = "topBarColor"
    )
    val pageBackgroundColorAnim by animateColorAsState(
        targetValue = if (isDarkTheme) 
            Color(0xFF1C1C1E).copy(alpha = backgroundAlpha) 
        else 
            Color(0xFFF2F2F7).copy(alpha = backgroundAlpha),
        label = "pageBgColor"
    )
    
    val currentVersion = remember { BuildConfig.VERSION_NAME }
    
    Box(modifier = Modifier.fillMaxSize()) {
        // Animated gradient background (FeatureCenterCard style breathing)
        val lightGradient = Brush.verticalGradient(
            colors = listOf(
                lerp(Color(0xFF2563EB), Color(0xFF38BDF8), gradientFraction),
                lerp(Color(0xFF4F46E5), Color(0xFF818CF8), gradientFraction),
                lerp(Color(0xFF7C3AED), Color(0xFFE879F9), gradientFraction)
            )
        )
        val darkGradient = Brush.verticalGradient(
            colors = listOf(
                lerp(Color(0xFF1E3A5F), Color(0xFF0F172A), gradientFraction),
                lerp(Color(0xFF312E81), Color(0xFF1E1B4B), gradientFraction),
                lerp(Color(0xFF4C1D95), Color(0xFF1A1A2E), gradientFraction)
            )
        )
        
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(if (isDarkTheme) darkGradient else lightGradient)
        )
        
        // Solid color overlay that appears on scroll
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(pageBackgroundColorAnim)
        )
        
        Scaffold(
            modifier = Modifier.fillMaxSize(),
            containerColor = Color.Transparent,
            contentWindowInsets = WindowInsets(0, 0, 0, 0),
            topBar = {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .background(topBarColorAnim)
                ) {
                    TopAppBar(
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
                    )
                }
            }
        ) { padding ->'''
    
    # Replace the old section with the new section
    # But we also need to add the closing brace for the outer Box
    # Find the end of the OverviewScreen function
    
    # The content after the old section
    after_old = content[content.index(old_section) + len(old_section):]
    
    # Find the closing braces at the end of the OverviewScreen function
    # We need to find the pattern: lines ending with "}" before TipsAgentCard
    
    # Find the position of "// Tips & Agent Card" which marks the start of the next function
    tips_marker = "// ============================================================\n// Tips & Agent Card"
    if tips_marker in after_old:
        print("Found Tips & Agent Card marker")
        
        # Get the content before the marker
        before_tips = after_old[:after_old.index(tips_marker)]
        
        # Find the last few lines which should be closing braces
        # The structure should be:
        #         }    - closes LazyVerticalGrid
        #     }        - closes Scaffold content lambda
        # }            - closes OverviewScreen function
        
        # We need to change this to:
        #         }    - closes LazyVerticalGrid
        #     }        - closes Scaffold content lambda
        #     }        - closes outer Box
        # }            - closes OverviewScreen function
        
        lines = before_tips.split('\n')
        print(f"Lines before Tips: {len(lines)}")
        print("Last 10 lines:")
        for i, line in enumerate(lines[-10:]):
            print(f"  {len(lines)-10+i}: {repr(line)}")
        
        # Find the pattern to replace at the end
        # Looking for:
        # "        }" (closes LazyVerticalGrid)
        # "    }" (closes Scaffold content lambda)
        # "}" (closes OverviewScreen function)
        
        # Let's find the last few lines
        idx = len(lines) - 1
        while idx >= 0 and (lines[idx].strip() == '' or lines[idx].strip() == '}'):
            idx -= 1
        
        # The closing section starts from idx+1
        closing_lines = lines[idx+1:]
        print(f"Closing lines: {closing_lines}")
        
        # We need to ensure there are 4 closing braces:
        # 1. "        }" - closes LazyVerticalGrid
        # 2. "    }" - closes Scaffold content lambda
        # 3. "    }" - closes outer Box (NEED TO ADD)
        # 4. "}" - closes OverviewScreen function
        
        # Replace the content
        new_content = content[:content.index(old_section)] + new_section + after_old
        
        # Now fix the closing braces
        # Find the end of the OverviewScreen function in new_content
        # and add the missing brace for outer Box
        
        old_closing = '''        }
    }
}

// ============================================================
// Tips & Agent Card (Migrated from Terminal List)
// ============================================================'''
        
        new_closing = '''        }
    }
    }
}

// ============================================================
// Tips & Agent Card (Migrated from Terminal List)
// ============================================================'''
        
        if old_closing in new_content:
            new_content = new_content.replace(old_closing, new_closing)
            print("Replaced closing braces!")
        else:
            print("Could not find closing braces pattern")
            # Try to find it with different whitespace
            # Let's search for the pattern
            import re
            # Find the closing pattern
            pattern = r'(\s+\}\n\s+\}\n\}\n\n// =+//)\n// Tips & Agent Card'
            match = re.search(pattern, new_content)
            if match:
                print(f"Found match: {repr(match.group())}")
            else:
                print("No regex match")
        
        with open(r'd:\KiTerminal-UX\app\src\main\java\com\termux\app\compose\OverviewScreen.kt', 'w', encoding='utf-8') as f:
            f.write(new_content)
        
        print("Successfully replaced!")
    else:
        print("Could not find Tips & Agent Card marker")
else:
    print("Could not find old section!")
    # Debug: find the Scaffold line
    if 'Scaffold(' in content:
        print("'Scaffold(' found in content")
        # Find where it is
        idx = content.index('Scaffold(')
        print(f"Context around Scaffold: {repr(content[idx-50:idx+50])}")
