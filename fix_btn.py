filepath = r'd:\KiTerminal-UX\app\src\main\java\com\termux\app\activities\AiTermuxActivity.kt'
with open(filepath, 'r', encoding='utf-8') as f:
    content = f.read()

# Fix button colors - use containerColor and contentColor explicitly
old = 'colors = ButtonDefaults.buttonColors(color = MiuixTheme.colorScheme.primary)'
new = 'colors = ButtonDefaults.buttonColors(containerColor = MiuixTheme.colorScheme.primary, contentColor = Color.White)'
content = content.replace(old, new)
print('Button colors fix:', 'OK' if old not in content else 'NOT FOUND')

with open(filepath, 'w', encoding='utf-8') as f:
    f.write(content)
print('Done.')
