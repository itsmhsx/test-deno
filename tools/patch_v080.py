from pathlib import Path
import re

# 1) Activate the bundled learned MobileFaceNet embedding, with the legacy
# descriptor retained as a safe offline fallback if the model cannot initialize.
face = Path('app/src/main/java/com/mhsx/actorsticker/FaceEngine.java')
s = face.read_text()
needle = '''    float[] descriptor(Bitmap src, Rect box) {\n        Rect r = padded(box, src.getWidth(), src.getHeight(), 0.15f);'''
repl = '''    float[] descriptor(Bitmap src, Rect box) {\n        float[] learnedV080 = V080FaceEmbedder.embedding(src, box);\n        if (learnedV080 != null && learnedV080.length == 192) return learnedV080;\n        Rect r = padded(box, src.getWidth(), src.getHeight(), 0.15f);'''
if needle not in s:
    raise SystemExit('FaceEngine descriptor integration point missing')
s = s.replace(needle, repl, 1)
face.write_text(s)

# 2) Identity dimensions changed from the handcrafted v0.7 vector to a learned
# 192-D embedding. Bump both cache formats so old fragments cannot be mixed.
store = Path('app/src/main/java/com/mhsx/actorsticker/ActorScanStore.java')
s = store.read_text()
s = s.replace('static final int FORMAT_VERSION = 5;', 'static final int FORMAT_VERSION = 6;', 1)
store.write_text(s)

scan = Path('app/src/main/java/com/mhsx/actorsticker/V070Scanner.java')
s = scan.read_text()
s = s.replace('private static final int FRAGMENT_FORMAT = 1;', 'private static final int FRAGMENT_FORMAT = 2;', 1)
s = s.replace('Cached v0.7 scan complete', 'Cached v0.8 learned-identity scan complete')
s = s.replace('v0.7 scan complete', 'v0.8 scan complete')
s = s.replace('v0.7 scan failed', 'v0.8 scan failed')
s = s.replace('v0.7 checkpoint kept', 'v0.8 checkpoint kept')
scan.write_text(s)

# 3) Background scan notifications return to the v0.8 launcher.
bg = Path('app/src/main/java/com/mhsx/actorsticker/BackgroundScanService.java')
s = bg.read_text()
s = s.replace('new Intent(this, MainActivityV070.class)', 'new Intent(this, MainActivityV080.class)')
s = s.replace('new Intent(this, MainActivityV050.class)', 'new Intent(this, MainActivityV080.class)')
bg.write_text(s)

# 4) The modern progress card should also reflect the new foreground render queue.
ui = Path('app/src/main/java/com/mhsx/actorsticker/MainActivityV060.java')
s = ui.read_text()
old = '''        boolean scan=p.getBoolean("bg_scan_running",false);\n        boolean export=p.getBoolean("export_pending_v050",false);\n        int base=baseProgress();\n        int progress; String stage,detail;\n        if(scan){progress=p.getInt("bg_scan_progress",0);stage=tr("Scanning actors");detail=p.getString("bg_scan_status",tr("Working"));}\n        else if(export||base>0){int dense=p.getInt("v060_track_progress",0);progress=Math.max(base, Math.min(99,dense));stage=tr("Analyzing / Tracking / Exporting");detail=p.getString("v060_track_status",tr("Processing video"));}'''
new = '''        boolean scan=p.getBoolean("bg_scan_running",false);\n        boolean export=p.getBoolean("export_pending_v050",false);\n        int render80=p.getInt("v080_export_progress",0);\n        String renderStatus80=p.getString("v080_export_status","");\n        int base=baseProgress();\n        int progress; String stage,detail;\n        if(scan){progress=p.getInt("bg_scan_progress",0);stage=tr("Scanning actors");detail=p.getString("bg_scan_status",tr("Working"));}\n        else if(export||base>0||(!renderStatus80.isEmpty()&&render80<100)){int dense=Math.max(p.getInt("v060_track_progress",0),render80);progress=Math.max(base, Math.min(99,dense));stage=tr("Analyzing / Tracking / Exporting");detail=renderStatus80.isEmpty()?p.getString("v060_track_status",tr("Processing video")):renderStatus80;}'''
if old not in s:
    raise SystemExit('v0.6 live progress integration point missing')
s = s.replace(old, new, 1)
ui.write_text(s)

# 5) Fix the v0.8 activity's checked exception import. Keep this in the patch so
# the branch remains easy to review and the generated build tree is deterministic.
main80 = Path('app/src/main/java/com/mhsx/actorsticker/MainActivityV080.java')
s = main80.read_text()
if 'import java.io.IOException;' not in s:
    s = s.replace('import java.io.File;\n', 'import java.io.File;\nimport java.io.IOException;\n', 1)
main80.write_text(s)

# 6) Force final version fields after all older patch layers have run.
gradle = Path('app/build.gradle')
s = gradle.read_text()
s = re.sub(r'versionCode\s+\d+', 'versionCode 10', s, count=1)
s = re.sub(r"versionName\s+'[^']+'", "versionName '0.8.0'", s, count=1)
gradle.write_text(s)

print('v0.8 learned identity, cache migration, progress and launcher integration patch applied')
