from pathlib import Path
import re

# version
gradle=Path('app/build.gradle')
s=gradle.read_text()
s=re.sub(r'versionCode\s+\d+','versionCode 15',s,count=1)
s=re.sub(r"versionName\s+'[^']+'","versionName '1.2.0'",s,count=1)
gradle.write_text(s)

# manifest: notifications + v1.2 activity. License gate remains the only launcher.
manifest=Path('app/src/main/AndroidManifest.xml')
s=manifest.read_text()
if 'android.permission.POST_NOTIFICATIONS' not in s:
    marker='<uses-permission android:name="android.permission.INTERNET" />'
    if marker not in s: raise SystemExit('v120 INTERNET marker missing')
    s=s.replace(marker,marker+'\n    <uses-permission android:name="android.permission.POST_NOTIFICATIONS" />',1)
if '<activity android:name=".MainActivityV120" android:exported="false" />' not in s:
    marker='<activity android:name=".MainActivityV110" android:exported="false" />'
    if marker not in s: raise SystemExit('v120 MainActivityV110 manifest marker missing')
    s=s.replace(marker,'<activity android:name=".MainActivityV120" android:exported="false" />\n        '+marker,1)
manifest.write_text(s)

# license gate opens v1.2 shell and accurately discloses dashboard telemetry.
gate=Path('app/src/main/java/com/mhsx/actorsticker/LicenseGateActivity.java')
s=gate.read_text()
s=s.replace('v1.1 Licensing Edition','v1.2 Xiaomi + Admin Edition')
s=s.replace('new Intent(this,MainActivityV110.class)','new Intent(this,MainActivityV120.class)')
s=s.replace('License validation uses a one-way device hash. IMEI, phone number, contacts and media are not sent to the license server.',
'''License/usage services send a one-way device hash, app version, phone manufacturer/model, Android API level, license type and last-use time. IMEI, phone number, contacts, photos and videos are not sent.''')
gate.write_text(s)

# licensing client reports v1.2 and exposes only the already-signed entitlement token to the heartbeat client.
lic=Path('app/src/main/java/com/mhsx/actorsticker/V110LicenseManager.java')
s=lic.read_text().replace('req.put("appVersion","1.1.0")','req.put("appVersion","1.2.0")')
needle='''    static boolean hasStoredLifetimeKey(Context c){String x=loadSerial(c);return x!=null&&!x.isEmpty();}\n'''
insert='''    static boolean hasStoredLifetimeKey(Context c){String x=loadSerial(c);return x!=null&&!x.isEmpty();}\n    static String signedToken(Context c){return c.getSharedPreferences(PREF,Context.MODE_PRIVATE).getString("token","");}\n'''
if needle not in s: raise SystemExit('v120 signedToken marker missing')
s=s.replace(needle,insert,1)
lic.write_text(s)

# baseline profile for the new hot paths.
prof=Path('app/src/main/baseline-prof.txt')
s=prof.read_text()
for line in [
    'HSPLcom/mhsx/actorsticker/MainActivityV120;->onCreate(Landroid/os/Bundle;)V',
    'Lcom/mhsx/actorsticker/V120XiaomiTuner;',
    'Lcom/mhsx/actorsticker/V120UsageReporter;'
]:
    if line not in s: s+=line+'\n'
prof.write_text(s)
print('v1.2 Xiaomi / HyperOS optimization + admin heartbeat patch applied')
