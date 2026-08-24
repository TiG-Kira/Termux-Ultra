import zipfile
import os

apk_path = r'D:\KiTerminal-UX\app\build\outputs\apk\debug\app-debug.apk'
print(f'APK exists: {os.path.exists(apk_path)}')
print(f'APK size: {os.path.getsize(apk_path) / 1024 / 1024:.1f} MB')

with zipfile.ZipFile(apk_path, 'r') as z:
    # Find the classes.dex files
    dex_files = [f for f in z.namelist() if f.endswith('.dex')]
    print(f'\nDEX files: {dex_files}')
    
    # Search for our changed strings in the APK
    search_strings = [
        'hf-mirror.com',
        'ButtonDefaults.buttonColors',
        'provider != "local"',
        'Download failed:',
        'UnknownHostException',
        'SSLException',
    ]
    
    # Read all dex files and search
    for dex in dex_files:
        data = z.read(dex)
        for s in search_strings:
            encoded = s.encode('utf-8')
            if encoded in data:
                print(f'  FOUND in {dex}: "{s}"')
            else:
                print(f'  MISSING in {dex}: "{s}"')
