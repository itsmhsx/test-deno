from pathlib import Path

p = Path('app/src/main/java/com/mhsx/actorsticker/MainActivityV040.java')
s = p.read_text()

if 'import androidx.media3.common.MimeTypes;' not in s:
    s = s.replace(
        'import androidx.media3.common.MediaItem;\n',
        'import androidx.media3.common.MediaItem;\nimport androidx.media3.common.MimeTypes;\n',
    )

old = '''int shortSide = Math.max(360, Math.min(s.sourceWidth(), s.sourceHeight()));
                    int bitrate = recommendedBitrate(shortSide);'''
new = '''int shortSide = Math.max(360, Math.min(s.sourceWidth(), s.sourceHeight()));
                    int bitrate = V050ExportPolicy.requestedBitrate(shortSide, prefs());'''
if old not in s:
    raise SystemExit('bitrate source pattern missing')
s = s.replace(old, new, 1)

old = 'Transformer.Builder builder = new Transformer.Builder(this);'
new = '''Transformer.Builder builder = new Transformer.Builder(this);
                String codec050 = V050ExportPolicy.normalizedCodec(prefs());
                if ("HEVC".equals(codec050)) builder.setVideoMimeType(MimeTypes.VIDEO_H265);
                else if ("AVC".equals(codec050)) builder.setVideoMimeType(MimeTypes.VIDEO_H264);'''
if old not in s:
    raise SystemExit('transformer builder pattern missing')
s = s.replace(old, new, 1)

begin = s.index('    private List<ProSegment> buildProSegments(')
end = s.index('    private float score(', begin)
method = r'''    private List<ProSegment> buildProSegments(List<ActorScanStore.Hit> hits, long duration,
                                              long clipMs, long sampleMs) {
        ArrayList<ProSegment> out = new ArrayList<>();
        if (hits == null || hits.isEmpty() || duration < MIN_CLIP_MS) return out;
        ArrayList<ActorScanStore.Hit> sorted = new ArrayList<>(hits);
        sorted.sort(Comparator.comparingLong(h -> h.t));
        long sceneGap = Math.max(1200L, Math.max(sampleMs * 3L, prefs().getLong("scene_gap_ms", 2400L)));
        boolean bestMoment = prefs().getBoolean("best_moment_v050", true);
        int i = 0;
        while (i < sorted.size()) {
            int j = i;
            while (j + 1 < sorted.size() && sorted.get(j + 1).t - sorted.get(j).t <= sceneGap) j++;
            long rawStart = Math.max(0L, sorted.get(i).t - 450L);
            long rawEnd = Math.min(duration, sorted.get(j).t + 850L);

            if (rawEnd - rawStart <= clipMs) {
                if (duration >= clipMs) {
                    long center = (rawStart + rawEnd) / 2L;
                    rawStart = Math.max(0L, Math.min(duration - clipMs, center - clipMs / 2L));
                    rawEnd = rawStart + clipMs;
                }
                if (rawEnd - rawStart >= MIN_CLIP_MS) {
                    ArrayList<ActorScanStore.Hit> wh = new ArrayList<>();
                    for (int k=i;k<=j;k++) if (sorted.get(k).t >= rawStart-1200 && sorted.get(k).t <= rawEnd+1200) wh.add(sorted.get(k));
                    if (!wh.isEmpty()) out.add(new ProSegment(rawStart, rawEnd, wh, score(wh, rawEnd-rawStart, sampleMs)));
                }
            } else if (bestMoment && duration >= clipMs) {
                ProSegment best = null;
                for (int anchor=i; anchor<=j; anchor++) {
                    long cs = sorted.get(anchor).t - clipMs/2L;
                    cs = Math.max(rawStart, Math.min(rawEnd - clipMs, cs));
                    cs = Math.max(0L, Math.min(duration - clipMs, cs));
                    long ce = cs + clipMs;
                    ArrayList<ActorScanStore.Hit> wh = new ArrayList<>();
                    for (int k=i;k<=j;k++) if (sorted.get(k).t >= cs-900 && sorted.get(k).t <= ce+900) wh.add(sorted.get(k));
                    if (wh.isEmpty()) continue;
                    float q = score(wh, clipMs, sampleMs);
                    double centerPenalty = Math.abs(sorted.get(anchor).t - (cs + clipMs/2L)) / (double)Math.max(1L, clipMs);
                    q = (float)Math.max(0, q - centerPenalty * 8.0);
                    ProSegment candidate = new ProSegment(cs, ce, wh, q);
                    if (best == null || candidate.quality > best.quality) best = candidate;
                }
                if (best != null) out.add(best);
            } else {
                long pos = rawStart;
                while (pos < rawEnd) {
                    long ce = Math.min(rawEnd, pos + clipMs);
                    if (ce - pos < MIN_CLIP_MS) break;
                    ArrayList<ActorScanStore.Hit> wh = new ArrayList<>();
                    for (int k=i;k<=j;k++) if (sorted.get(k).t >= pos-1200 && sorted.get(k).t <= ce+1200) wh.add(sorted.get(k));
                    if (!wh.isEmpty()) out.add(new ProSegment(pos, ce, wh, score(wh, ce-pos, sampleMs)));
                    pos = ce;
                }
            }
            i = j + 1;
        }
        return out;
    }

'''
s = s[:begin] + method + s[end:]

old = '''if (duplicateFilter) segments = removeOverlaps(segments);
                    int clipNo = 0;
                    for (ProSegment s : segments) {'''
new = '''if (duplicateFilter) segments = removeOverlaps(segments);
                    HashSet<Long> visualHashes050 = new HashSet<>();
                    int clipNo = 0;
                    for (ProSegment s : segments) {
                        long[] refined050 = V050SceneRefiner.refine(this, video, s.startMs, s.endMs, duration, p.getBoolean("exact_duration_v050", false));
                        if (refined050[1] - refined050[0] >= MIN_CLIP_MS) s = new ProSegment(refined050[0], refined050[1], s.hits, s.quality);
                        long hash050 = V050SceneRefiner.perceptualHash(this, video, (s.startMs + s.endMs) / 2L);
                        if (duplicateFilter && V050SceneRefiner.isNearDuplicate(hash050, visualHashes050, 5)) { report.add("SKIP perceptual duplicate " + displayName(video) + " @" + s.startMs); continue; }
                        if (hash050 != Long.MIN_VALUE) visualHashes050.add(hash050);'''
if old not in s:
    raise SystemExit('segment loop pattern missing')
s = s.replace(old, new, 1)

old = '''if (s.endMs - s.startMs < MIN_CLIP_MS) continue;
                        if (s.endMs - s.startMs > clipSec * 1000L + 25) {'''
new = '''if (s.endMs - s.startMs < MIN_CLIP_MS) continue;
                        if (p.getBoolean("exact_duration_v050", false) && Math.abs((s.endMs - s.startMs) - clipSec * 1000L) > 120L) {
                            report.add("SKIP exact-duration guard " + displayName(video) + " @" + s.startMs);
                            continue;
                        }
                        if (weakFilter && V050SceneRefiner.visualQuality(this, video, (s.startMs + s.endMs)/2L) < 0.20f) {
                            report.add("SKIP dark/blur scene " + displayName(video) + " @" + s.startMs);
                            continue;
                        }
                        if (s.endMs - s.startMs > clipSec * 1000L + 25) {'''
if old not in s:
    raise SystemExit('duration guard pattern missing')
s = s.replace(old, new, 1)

s = s.replace('.setTitle("v0.4 Export result")', '.setTitle("v0.5 Export result")')
p.write_text(s)

# MainActivity inherits Activity.setProgress(int), so a private helper with the
# same signature is illegal Java. Rename local helper references in the CI build
# workspace, but keep ProgressBar.setProgress(int) unchanged.
p50 = Path('app/src/main/java/com/mhsx/actorsticker/MainActivityV050.java')
s50 = p50.read_text()
s50 = s50.replace('setProgress(', 'setJobProgress050(')
s50 = s50.replace('((ProgressBar)f.get(this)).setJobProgress050(', '((ProgressBar)f.get(this)).setProgress(')
p50.write_text(s50)

print('v0.5 export + compile compatibility patch applied')
