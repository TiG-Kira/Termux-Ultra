import zipfile
import os

apk_path = r'D:\KiTerminal-UX\app\build\outputs\apk\debug\termux-ultra_debug_universal.apk'
print(f'APK size: {os.path.getsize(apk_path) / 1024 / 1024:.1f} MB')

with zipfile.ZipFile(apk_path, 'r') as z:
    dex_files = [f for f in z.namelist() if f.endswith('.dex')]
    print(f'DEX files: {dex_files}')
    
    search_strings = [
        'hf-mirror.com',
        'ButtonDefaults',
        'provider != ',
        'Download failed',
        'UnknownHostException',
        'SSLException',
        'MiuixTheme.colorScheme.primary',
        'SnackbarHelper.show',
        'getSelectedModel',
        'apiBaseUrl = ',
    ]
    
    for dex in dex_files:
        data = z.read(dex)
        for s in search_strings:
            encoded = s.encode('utf-8')
            if encoded in data:
                print(f'  FOUND: "{s}"')
            else:
                print(f'  MISSING: "{s}"')
