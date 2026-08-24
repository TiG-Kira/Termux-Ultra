content = r'''private fun CpuMonitorCard(
    card: OverviewCardConfig,
    usage: Float,
    temperature: Float,
    history: List<Float>,
    isEditMode: Boolean,
    onEditClick: () -> Unit
) {
    val isDark = isSystemInDarkTheme()
    val cpuMaxCapacity = remember { getCpuMaxCapacity() }
    val usageColor = getUsageColor(usage, cpuMaxCapacity)
    val config = LocalConfiguration.current
    val cardHeight = ((config.screenWidthDp - 40) / 2).dp
    val ratio = if (cpuMaxCapacity > 0f) usage / cpuMaxCapacity else usage / 100f
    val iconTint = when {
        ratio < 0.5f -> Color(0xFF4CAF50)
        ratio < 0.8f -> Color(0xFFFF9800)
        else -> Color(0xFFF44336)
    }
    val gradient = if (isDark) {
        Brush.linearGradient(listOf(Color(0xFF1C1C1E), Color(0xFF2C2C2E)))
    } else {
        Brush.linearGradient(listOf(Color(0xFFFAFAFA), Color(0xFFF5F5F7)))
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .height(cardHeight)
            .clip(RoundedCornerShape(20.dp))
    ) {
        PremiumCardBackground(gradient = gradient) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp)
            ) {
                PremiumCardHeader(
                    icon = Icons.Rounded.Memory,
                    title = stringResource(R.string.overview_card_cpu),
                    iconTint = iconTint,
                    isEditMode = isEditMode,
                    onEditClick = onEditClick
                )

                Spacer(modifier = Modifier.height(10.dp))

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .weight(1f),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.weight(1f)
                    ) {
                        Text(
                            text = "%",
                            fontSize = 36.sp,
                            fontWeight = FontWeight.Bold,
                            color = usageColor
                        )
                        if (temperature > 0f) {
                            Spacer(modifier = Modifier.height(2.dp))
                            Text(
                                text = stringResource(R.string.overview_cpu_temp, temperature),
                                fontSize = 11.sp,
                                fontWeight = FontWeight.Medium,
                                color = MiuixTheme.colorScheme.onSurfaceVariantSummary
                            )
                        }
                    }
                    Column(
                        horizontalAlignment = Alignment.Start,
                        modifier = Modifier.weight(2f)
                    ) {
                        UsageChart(
                            data = history,
                            color = usageColor,
                            modifier = Modifier.fillMaxWidth().height(52.dp),
                            maxValue = cpuMaxCapacity
                        )
                    }
                }
            }
        }
    }
}
'''
with open(r'd:\KiTerminal-UX\cpu_card_new.kt', 'w', encoding='utf-8') as f:
    f.write(content)
print('written')
