from pathlib import Path
import re

# v0.8.1 runs after all compatibility patches and restores the hotfix launcher/version.
gradle = Path('app/build.gradle')
s = gradle.read_text()
s = re.sub(r'versionCode\s+\d+', 'versionCode 11', s, count=1)
s = re.sub(r"versionName\s+'[^']+'", "versionName '0.8.1'", s, count=1)
gradle.write_text(s)

manifest = Path('app/src/main/AndroidManifest.xml')
s = manifest.read_text()
# The branch source already launches V081. Guard against older patch layers changing it later.
s = s.replace('android:name=".MainActivityV080"\n            android:exported="true"', 'android:name=".MainActivityV081"\n            android:exported="true"', 1)
if '<activity android:name=".MainActivityV080" android:exported="false" />' not in s:
    marker = '<activity android:name=".MainActivityV070" android:exported="false" />'
    s = s.replace(marker, '<activity android:name=".MainActivityV080" android:exported="false" />\n        ' + marker, 1)
manifest.write_text(s)

# Background scanner startup heartbeat: the UI watchdog now knows the service really entered onStartCommand.
bg = Path('app/src/main/java/com/mhsx/actorsticker/BackgroundScanService.java')
s = bg.read_text()
s = s.replace('new Intent(this, MainActivityV080.class)', 'new Intent(this, MainActivityV081.class)')
s = s.replace('new Intent(this, MainActivityV050.class)', 'new Intent(this, MainActivityV081.class)')
needle = '''        boolean refresh = ACTION_REFRESH.equals(action);\n        cancel.set(false);\n        startForeground(NOTIFICATION_ID, notification("Preparing actor scan…", 0, true));'''
replacement = '''        boolean refresh = ACTION_REFRESH.equals(action);\n        cancel.set(false);\n        updateState(true, 1, "Starting Actor Scan service…");\n        startForeground(NOTIFICATION_ID, notification("Preparing actor scan…", 1, true));'''
if needle in s:
    s = s.replace(needle, replacement, 1)
else:
    raise SystemExit('Background scan startup insertion point missing')
bg.write_text(s)

# Hotfix implementation guards.
service = Path('app/src/main/java/com/mhsx/actorsticker/V080ExportService.java').read_text()
for marker in ['pauseRequested=false;', 'kickRequested=true;', 'v080_export_heartbeat', 'MainActivityV081.class']:
    if marker not in service:
        raise SystemExit('Missing render hotfix marker: ' + marker)

queue = Path('app/src/main/java/com/mhsx/actorsticker/V080ExportQueue.java').read_text()
if 'kickRenderer()' not in queue or 'startForegroundService' not in queue:
    raise SystemExit('Queue auto-start hotfix missing')

ui = Path('app/src/main/java/com/mhsx/actorsticker/MainActivityV081.java').read_text()
for marker in ['showSafeActors', 'startReliableScan', 'scanWatchdog', 'Safe Gallery v0.8.1']:
    if marker not in ui:
        raise SystemExit('Missing v0.8.1 UI marker: ' + marker)

print('v0.8.1 queue-start, scan-watchdog and safe-gallery patch applied')
