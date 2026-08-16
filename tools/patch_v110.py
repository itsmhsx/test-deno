from pathlib import Path
import re

# Version + network permission for the remote license server.
gradle=Path('app/build.gradle')
s=gradle.read_text()
s=re.sub(r'versionCode\s+\d+','versionCode 14',s,count=1)
s=re.sub(r"versionName\s+'[^']+'","versionName '1.1.0'",s,count=1)
gradle.write_text(s)

manifest=Path('app/src/main/AndroidManifest.xml')
s=manifest.read_text()
s=s.replace('<uses-permission android:name="android.permission.INTERNET" tools:node="remove" />','<uses-permission android:name="android.permission.INTERNET" />',1)
# v1.0 patch has made MainActivityV100 the launcher. Make the license gate the only exported launcher.
needle='android:name=".MainActivityV100"\n            android:exported="true"'
if needle not in s: raise SystemExit('v110 launcher marker missing')
s=s.replace(needle,'android:name=".LicenseGateActivity"\n            android:exported="true"',1)
marker='<activity android:name=".MainActivityV090" android:exported="false" />'
insert='<activity android:name=".MainActivityV110" android:exported="false" />\n        <activity android:name=".MainActivityV100" android:exported="false" />\n        '+marker
if '<activity android:name=".MainActivityV110"' not in s:
    if marker not in s: raise SystemExit('v110 activity insertion marker missing')
    s=s.replace(marker,insert,1)
manifest.write_text(s)

# Avoid any API-level dependent String.repeat fallback and make the rare missing-ANDROID_ID
# case unique per installation instead of collapsing every such device to the same fingerprint.
lic=Path('app/src/main/java/com/mhsx/actorsticker/V110LicenseManager.java')
s=lic.read_text()
old='''            String id=Settings.Secure.getString(c.getContentResolver(),Settings.Secure.ANDROID_ID);\n            if(id==null||id.trim().isEmpty())id="unknown";\n            return hex(MessageDigest.getInstance("SHA-256").digest(("ASC110|"+c.getPackageName()+"|"+id).getBytes(StandardCharsets.UTF_8)));\n        }catch(Throwable e){return "0".repeat(64);}'''
new='''            String id=Settings.Secure.getString(c.getContentResolver(),Settings.Secure.ANDROID_ID);\n            if(id==null||id.trim().isEmpty()){SharedPreferences fp=c.getSharedPreferences(PREF,Context.MODE_PRIVATE);id=fp.getString("fallback_device_id","");if(id.isEmpty()){id=UUID.randomUUID().toString();fp.edit().putString("fallback_device_id",id).apply();}}\n            return hex(MessageDigest.getInstance("SHA-256").digest(("ASC110|"+c.getPackageName()+"|"+id).getBytes(StandardCharsets.UTF_8)));\n        }catch(Throwable e){try{return hex(MessageDigest.getInstance("SHA-256").digest(("ASC110|"+c.getPackageName()+"|fallback").getBytes(StandardCharsets.UTF_8)));}catch(Throwable ignored){return "0000000000000000000000000000000000000000000000000000000000000000";}}'''
if old not in s: raise SystemExit('v110 deviceHash hardening marker missing')
s=s.replace(old,new,1)
lic.write_text(s)

# Every foreground work entry point also checks the signed local entitlement,
# so WorkManager/notifications cannot bypass the launcher gate.
bg=Path('app/src/main/java/com/mhsx/actorsticker/BackgroundScanService.java')
s=bg.read_text()
needle='''    @Override public int onStartCommand(Intent intent, int flags, int startId) {\n        String action = intent == null ? ACTION_START : intent.getAction();'''
repl='''    @Override public int onStartCommand(Intent intent, int flags, int startId) {\n        if (!V110LicenseManager.local(this).valid) { stopSelf(startId); return START_NOT_STICKY; }\n        String action = intent == null ? ACTION_START : intent.getAction();'''
if needle not in s: raise SystemExit('v110 scan service guard marker missing')
s=s.replace(needle,repl,1).replace('MainActivityV100.class','LicenseGateActivity.class')
bg.write_text(s)

svc=Path('app/src/main/java/com/mhsx/actorsticker/V080ExportService.java')
s=svc.read_text()
needle='''    @Override public int onStartCommand(Intent intent,int flags,int startId){\n        String a=intent==null?ACTION_START:intent.getAction();'''
repl='''    @Override public int onStartCommand(Intent intent,int flags,int startId){\n        if (!V110LicenseManager.local(this).valid) { stopSelf(startId); return START_NOT_STICKY; }\n        String a=intent==null?ACTION_START:intent.getAction();'''
if needle not in s: raise SystemExit('v110 export service guard marker missing')
s=s.replace(needle,repl,1).replace('MainActivityV100.class','LicenseGateActivity.class')
svc.write_text(s)

# Baseline profile: gate + signed token verification + v1.1 shell.
prof=Path('app/src/main/baseline-prof.txt')
s=prof.read_text()
for line in [
    'HSPLcom/mhsx/actorsticker/LicenseGateActivity;->onCreate(Landroid/os/Bundle;)V',
    'Lcom/mhsx/actorsticker/V110LicenseManager;',
    'HSPLcom/mhsx/actorsticker/MainActivityV110;->onCreate(Landroid/os/Bundle;)V'
]:
    if line not in s:s+=line+'\n'
prof.write_text(s)
print('v1.1 licensing gate, signed entitlement and service guards applied')
