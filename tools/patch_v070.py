from pathlib import Path
import re

# Background service: use the new two-pass incremental scanner and keep live notification progress.
bg = Path('app/src/main/java/com/mhsx/actorsticker/BackgroundScanService.java')
s = bg.read_text()
needle = '''    private void runScan(boolean refresh) {\n        ArrayList<Uri> videos = readVideos();'''
repl = '''    private void runScan(boolean refresh) {\n        if (prefs.getBoolean("speed_engine_v070", true)) {\n            V070Scanner.Result r = V070Scanner.run(this, prefs, store, cancel, refresh, (pct, text) -> {\n                updateState(true, pct, text);\n                notifyProgress(text, pct);\n            });\n            finishWith(r.status, r.complete, r.progress);\n            return;\n        }\n        ArrayList<Uri> videos = readVideos();'''
if needle not in s:
    raise SystemExit('Background runScan integration point missing')
s = s.replace(needle, repl, 1)
s = s.replace('new Intent(this, MainActivityV050.class)', 'new Intent(this, MainActivityV070.class)')
bg.write_text(s)

# Lazy/sampled thumbnail decoding instead of blocking Actor Gallery UI with full Bitmap decodes.
main = Path('app/src/main/java/com/mhsx/actorsticker/MainActivity.java')
s = main.read_text()
old = '''            Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());\n            if (b == null) continue;\n            ImageView iv = new ImageView(this);\n            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);\n            iv.setImageBitmap(b);'''
new = '''            ImageView iv = new ImageView(this);\n            iv.setScaleType(ImageView.ScaleType.CENTER_CROP);\n            if (prefs.getBoolean("lazy_thumbs_v070", true)) {\n                V070ThumbnailLoader.load(iv, f, dp(104));\n            } else {\n                Bitmap b = BitmapFactory.decodeFile(f.getAbsolutePath());\n                if (b != null) iv.setImageBitmap(b);\n            }'''
if old not in s:
    raise SystemExit('Actor Gallery thumbnail block missing')
s = s.replace(old, new, 1)
main.write_text(s)

# Performance export integrations are applied after v0.5/v0.6/v0.6.1 patches.
v40 = Path('app/src/main/java/com/mhsx/actorsticker/MainActivityV040.java')
s = v40.read_text()
old061 = '''                        String motion061 = p.getString("tracking_motion_v061", "Slow");\n                        if (p.getBoolean("dense_tracking_v060", true) && !motion061.startsWith("Fixed") && !"Manual fixed".equals(motion061)) {'''
if old061 in s:
    s = s.replace(old061, '''                        String motion061 = p.getString("tracking_motion_v061", "Slow");\n                        if (V070ExportPolicy.shouldDenseTrack(p)) {''', 1)
elif 'if (p.getBoolean("dense_tracking_v060", true)) {' in s:
    s = s.replace('if (p.getBoolean("dense_tracking_v060", true)) {', 'if (V070ExportPolicy.shouldDenseTrack(p)) {', 1)
else:
    raise SystemExit('dense tracking guard missing after v0.6.1 patch')

builder = '                Transformer.Builder builder = new Transformer.Builder(this);\n'
if builder not in s:
    raise SystemExit('Transformer builder integration point missing')
s = s.replace(builder, builder + '''                if (prefs().getBoolean("trim_opt_v070", true)) builder.experimentalSetTrimOptimizationEnabled(true);\n                if (prefs().getBoolean("controlled_encoder_v070", true)) builder.experimentalSetMaxFramesInEncoder(4);\n''', 1)

start = '''        File out = new File(dir, name);\n        if (out.exists()) out.delete();\n\n        float inputAspect = s.inputAspect();'''
if start not in s:
    raise SystemExit('export timing start point missing')
s = s.replace(start, '''        File out = new File(dir, name);\n        if (out.exists()) out.delete();\n        long perfExportStart070 = System.nanoTime();\n\n        float inputAspect = s.inputAspect();''', 1)
end = '''        if (!out.isFile() || out.length() == 0) throw new IOException("Empty export file");\n        return out;'''
if end not in s:
    raise SystemExit('export timing end point missing')
s = s.replace(end, '''        if (!out.isFile() || out.length() == 0) throw new IOException("Empty export file");\n        float elapsed070 = (System.nanoTime() - perfExportStart070) / 1_000_000f;\n        SharedPreferences pp070 = prefs();\n        pp070.edit().putFloat("perf_export_ms_v070", pp070.getFloat("perf_export_ms_v070", 0f) + elapsed070).apply();\n        return out;''', 1)
v40.write_text(s)

# Two-pass mode can be disabled for a full accurate single pass. Media3 FrameExtractor remains
# the v0.7 frame engine in both modes.
scan = Path('app/src/main/java/com/mhsx/actorsticker/V070Scanner.java')
s = scan.read_text()
old = '''                    TreeSet<Long> candidates = new TreeSet<>();\n                    double faceRatioSum=0; int faceRatioCount=0;\n                    float coarseMin = prefs.getFloat("min_face_v070", 0.065f);\n                    try (V070QuickFaceDetector quick = new V070QuickFaceDetector(coarseMin);'''
new = '''                    TreeSet<Long> candidates = new TreeSet<>();\n                    double faceRatioSum=0; int faceRatioCount=0;\n                    float coarseMin = prefs.getFloat("min_face_v070", 0.065f);\n                    boolean twoPass070 = prefs.getBoolean("two_pass_scan_v070", true);\n                    if (twoPass070) try (V070QuickFaceDetector quick = new V070QuickFaceDetector(coarseMin);'''
if old not in s:
    raise SystemExit('two-pass quick block missing')
s = s.replace(old, new, 1)
old = '''                    TreeSet<Long> fineTimes = new TreeSet<>();\n                    for(Long c:candidates) {\n                        long from=Math.max(0,c-m.coarseStep/2), to=Math.min(duration-1,c+m.coarseStep/2);\n                        for(long t=from;t<=to;t+=m.fineStep) fineTimes.add(t);\n                    }'''
new = '''                    TreeSet<Long> fineTimes = new TreeSet<>();\n                    if (twoPass070) {\n                        for(Long c:candidates) {\n                            long from=Math.max(0,c-m.coarseStep/2), to=Math.min(duration-1,c+m.coarseStep/2);\n                            for(long t=from;t<=to;t+=m.fineStep) fineTimes.add(t);\n                        }\n                    } else {\n                        for(long t=0;t<duration;t+=m.fineStep) fineTimes.add(t);\n                    }'''
if old not in s:
    raise SystemExit('two-pass fine time block missing')
s = s.replace(old, new, 1)
scan.write_text(s)

ui = Path('app/src/main/java/com/mhsx/actorsticker/MainActivityV070.java')
s = ui.read_text()
s = s.replace('        addToggle(box,"frame_extractor_v070",tr("Media3 FrameExtractor 1.10 fast sampling"),true);\n', '')
ui.write_text(s)

# Force v0.7 version after legacy compatibility patches.
gradle = Path('app/build.gradle')
s = gradle.read_text()
s = re.sub(r"versionCode\s+\d+", "versionCode 9", s, count=1)
s = re.sub(r"versionName\s+'[^']+'", "versionName '0.7.0'", s, count=1)
gradle.write_text(s)

print('v0.7 performance integration patch applied')
