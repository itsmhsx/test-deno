package com.mhsx.actorsticker;

import android.app.Activity;
import android.app.ActivityManager;
import android.content.*;
import android.database.Cursor;
import android.media.MediaCodecInfo;
import android.media.MediaCodecList;
import android.media.MediaMetadataRetriever;
import android.net.Uri;
import android.os.*;
import android.provider.OpenableColumns;
import android.view.*;
import android.widget.*;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.*;
import java.util.*;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

public class MainActivity extends Activity {
    private static final int REQ_VIDEOS = 1001;
    private static final int REQ_REFS = 1002;
    private static final int REQ_OUTPUT = 1003;
    private static final long MIN_CLIP_MS = 2000L;

    private final ArrayList<Uri> videos = new ArrayList<>();
    private final ArrayList<Uri> refs = new ArrayList<>();
    private Uri outputTree;
    private LinearLayout content;
    private TextView statusBar;
    private SharedPreferences prefs;
    private final ExecutorService io = Executors.newSingleThreadExecutor();

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        prefs = getSharedPreferences("actor_sticker", MODE_PRIVATE);
        restoreState();
        buildShell();
        showQueue();
    }

    private int dp(int v) { return Math.round(v * getResources().getDisplayMetrics().density); }

    private TextView text(String s, int sp, boolean bold) {
        TextView v = new TextView(this);
        v.setText(s); v.setTextSize(sp); v.setTextColor(0xff172033);
        if (bold) v.setTypeface(null, 1);
        v.setPadding(dp(4), dp(6), dp(4), dp(6));
        return v;
    }

    private Button button(String s, View.OnClickListener c) {
        Button b = new Button(this); b.setText(s); b.setAllCaps(false); b.setOnClickListener(c);
        return b;
    }

    private void buildShell() {
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setBackgroundColor(0xfff4f6fa);
        TextView title = text("Actor Sticker Cutter • Android", 22, true); title.setPadding(dp(14),dp(14),dp(14),dp(4)); root.addView(title);
        TextView sub = text("Native APK preview • Android 8+ • 2s minimum clip guard", 12, false); sub.setPadding(dp(14),0,dp(14),dp(8)); root.addView(sub);

        HorizontalScrollView hs = new HorizontalScrollView(this); hs.setHorizontalScrollBarEnabled(false);
        LinearLayout tabs = new LinearLayout(this); tabs.setOrientation(LinearLayout.HORIZONTAL); tabs.setPadding(dp(8),0,dp(8),dp(4));
        tabs.addView(button("Queue", v -> showQueue()));
        tabs.addView(button("Actor", v -> showActor()));
        tabs.addView(button("Network Tools", v -> showNetwork()));
        tabs.addView(button("System Info", v -> showSystem()));
        tabs.addView(button("Settings", v -> showSettings()));
        hs.addView(tabs); root.addView(hs);

        ScrollView scroll = new ScrollView(this); content = new LinearLayout(this); content.setOrientation(LinearLayout.VERTICAL); content.setPadding(dp(12),dp(8),dp(12),dp(16)); scroll.addView(content);
        root.addView(scroll, new LinearLayout.LayoutParams(-1,0,1));
        statusBar = text("Ready", 12, false); statusBar.setBackgroundColor(0xffe8edf6); statusBar.setPadding(dp(12),dp(8),dp(12),dp(8)); root.addView(statusBar);
        setContentView(root);
    }

    private void clear(String heading) { content.removeAllViews(); content.addView(text(heading, 19, true)); }
    private void line(String s) { content.addView(text(s, 14, false)); }
    private void setStatus(String s) { runOnUiThread(() -> statusBar.setText(s)); }

    private void showQueue() {
        clear("Video Queue");
        content.addView(button("+ Add videos", v -> chooseVideos()));
        content.addView(button("Choose output folder", v -> chooseOutput()));
        line("Output: " + (outputTree == null ? "Not selected" : outputTree.toString()));
        line("Videos: " + videos.size());
        if (videos.isEmpty()) line("No videos in queue.");
        for (int i=0;i<videos.size();i++) {
            Uri u = videos.get(i); line(String.format(Locale.US, "%02d  %s", i+1, displayName(u)));
        }
        content.addView(button("Validate queue (2s guard)", v -> validateQueue()));
        content.addView(button("Clear queue", v -> { videos.clear(); saveState(); showQueue(); }));
        TextView note = text("Processing engine status: Android-native queue, metadata validation, persistent file access and output selection are active. Face/Smart-Crop inference will be integrated into this APK base after device validation.", 12, false);
        note.setBackgroundColor(0xfffff4d6); note.setPadding(dp(10),dp(10),dp(10),dp(10)); content.addView(note);
    }

    private void showActor() {
        clear("Actor / References");
        content.addView(button("Select reference photos", v -> chooseRefs()));
        line("Reference images: " + refs.size());
        for (Uri u: refs) line("• " + displayName(u));
        content.addView(button("Clear references", v -> { refs.clear(); saveState(); showActor(); }));
        line("Smart Select Actor: reserved for ONNX face engine module.");
        line("Reference data remains local on the device.");
    }

    private void showNetwork() {
        clear("Network Tools");
        CheckBox enabled = new CheckBox(this); enabled.setText("Use proxy for app downloads/tests"); enabled.setChecked(prefs.getBoolean("proxy_enabled", false)); content.addView(enabled);
        Spinner type = new Spinner(this); type.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, new String[]{"HTTP","SOCKS5"})); type.setSelection("SOCKS5".equals(prefs.getString("proxy_type","HTTP"))?1:0); content.addView(type);
        EditText host = edit("Proxy host / IP", prefs.getString("proxy_host","")); content.addView(host);
        EditText port = edit("Port", prefs.getString("proxy_port","10808")); port.setInputType(2); content.addView(port);
        EditText user = edit("Username (optional)", prefs.getString("proxy_user","")); content.addView(user);
        EditText pass = edit("Password (optional)", prefs.getString("proxy_pass","")); pass.setInputType(0x81); content.addView(pass);
        content.addView(button("Save proxy", v -> {
            prefs.edit().putBoolean("proxy_enabled",enabled.isChecked()).putString("proxy_type",type.getSelectedItem().toString())
                    .putString("proxy_host",host.getText().toString().trim()).putString("proxy_port",port.getText().toString().trim())
                    .putString("proxy_user",user.getText().toString()).putString("proxy_pass",pass.getText().toString()).apply();
            setStatus("Proxy settings saved");
        }));
        content.addView(button("Test connection / public IP", v -> testProxy(enabled.isChecked(), type.getSelectedItem().toString(), host.getText().toString().trim(), port.getText().toString().trim(), user.getText().toString(), pass.getText().toString())));
        line("Proxy is app-scoped; it does not change Android system proxy/VPN settings.");
    }

    private EditText edit(String hint, String value) { EditText e=new EditText(this); e.setHint(hint); e.setText(value); e.setSingleLine(true); return e; }

    private void showSystem() {
        clear("System Information");
        content.addView(button("Refresh", v -> showSystem()));
        line("Manufacturer: " + Build.MANUFACTURER);
        line("Model: " + Build.MODEL);
        line("Device: " + Build.DEVICE + " / " + Build.PRODUCT);
        line("Android: " + Build.VERSION.RELEASE + " (SDK " + Build.VERSION.SDK_INT + ")");
        line("ABIs: " + Arrays.toString(Build.SUPPORTED_ABIS));
        line("CPU cores: " + Runtime.getRuntime().availableProcessors());
        ActivityManager.MemoryInfo mi = new ActivityManager.MemoryInfo(); ((ActivityManager)getSystemService(ACTIVITY_SERVICE)).getMemoryInfo(mi);
        line(String.format(Locale.US,"RAM: %.1f GB total • %.1f GB free",mi.totalMem/1073741824.0,mi.availMem/1073741824.0));
        StatFs sf = new StatFs(getFilesDir().getAbsolutePath());
        line(String.format(Locale.US,"App storage: %.1f GB free / %.1f GB",sf.getAvailableBytes()/1073741824.0,sf.getTotalBytes()/1073741824.0));
        Intent bat = registerReceiver(null,new IntentFilter(Intent.ACTION_BATTERY_CHANGED));
        if (bat != null) { int level=bat.getIntExtra("level",-1), scale=bat.getIntExtra("scale",100); line("Battery: "+Math.round(level*100f/Math.max(1,scale))+"%"); }
        line("Hardware codecs:");
        try {
            MediaCodecInfo[] infos = new MediaCodecList(MediaCodecList.ALL_CODECS).getCodecInfos(); int shown=0;
            for (MediaCodecInfo info: infos) { if (shown>=16) break; for(String t:info.getSupportedTypes()) if(t.startsWith("video/")){ line("  " + (info.isEncoder()?"ENC ":"DEC ") + info.getName()+" • "+t); shown++; break; } }
        } catch(Exception e) { line("Codec query error: "+e.getMessage()); }
        line("App version: 0.1.0 (APK preview)");
    }

    private void showSettings() {
        clear("Settings");
        CheckBox dark = new CheckBox(this); dark.setText("Prefer dark UI (next launch)"); dark.setChecked(prefs.getBoolean("dark",false)); content.addView(dark);
        CheckBox keep = new CheckBox(this); keep.setText("Keep screen awake during processing"); keep.setChecked(prefs.getBoolean("keep_awake",true)); content.addView(keep);
        content.addView(button("Save settings", v -> { prefs.edit().putBoolean("dark",dark.isChecked()).putBoolean("keep_awake",keep.isChecked()).apply(); setStatus("Settings saved"); }));
        line("Minimum clip duration is locked to 2.0 seconds to avoid useless short sticker loops.");
        line("App data and references remain local unless you explicitly use a download/test action.");
    }

    private void chooseVideos() {
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("video/*"); i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true); i.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(i,REQ_VIDEOS);
    }
    private void chooseRefs() {
        Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT); i.setType("image/*"); i.putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true); i.addCategory(Intent.CATEGORY_OPENABLE); startActivityForResult(i,REQ_REFS);
    }
    private void chooseOutput() { Intent i=new Intent(Intent.ACTION_OPEN_DOCUMENT_TREE); i.addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION|Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION); startActivityForResult(i,REQ_OUTPUT); }

    @Override protected void onActivityResult(int requestCode,int resultCode,Intent data) {
        super.onActivityResult(requestCode,resultCode,data); if(resultCode!=RESULT_OK||data==null)return;
        int flags=data.getFlags()&(Intent.FLAG_GRANT_READ_URI_PERMISSION|Intent.FLAG_GRANT_WRITE_URI_PERMISSION);
        try {
            if(requestCode==REQ_OUTPUT){ outputTree=data.getData(); if(outputTree!=null)getContentResolver().takePersistableUriPermission(outputTree,flags); }
            else {
                ArrayList<Uri> dst=requestCode==REQ_VIDEOS?videos:refs;
                if(data.getClipData()!=null){ for(int x=0;x<data.getClipData().getItemCount();x++){Uri u=data.getClipData().getItemAt(x).getUri(); addUnique(dst,u); persist(u,Intent.FLAG_GRANT_READ_URI_PERMISSION);} }
                else if(data.getData()!=null){ Uri u=data.getData(); addUnique(dst,u); persist(u,Intent.FLAG_GRANT_READ_URI_PERMISSION); }
            }
        } catch(Exception ignored) {}
        saveState(); if(requestCode==REQ_REFS)showActor(); else showQueue();
    }
    private void persist(Uri u,int flags){ try{getContentResolver().takePersistableUriPermission(u,flags);}catch(Exception ignored){} }
    private void addUnique(ArrayList<Uri> list,Uri u){ if(!list.contains(u))list.add(u); }

    private void validateQueue() {
        if(videos.isEmpty()){Toast.makeText(this,"Add videos first",Toast.LENGTH_SHORT).show();return;}
        setStatus("Validating video durations…");
        io.execute(() -> {
            int valid=0,rejected=0,unknown=0; StringBuilder r=new StringBuilder();
            for(Uri u:videos){ long d=durationMs(u); if(d>0&&d<MIN_CLIP_MS){rejected++; r.append("REJECT <2s: ").append(displayName(u)).append('\n');} else if(d<=0){unknown++; r.append("UNKNOWN: ").append(displayName(u)).append('\n');} else {valid++; r.append(String.format(Locale.US,"OK %.1fs: %s\n",d/1000.0,displayName(u)));} }
            final String msg="Valid: "+valid+" • Rejected <2s: "+rejected+" • Unknown: "+unknown+"\n\n"+r;
            runOnUiThread(() -> new android.app.AlertDialog.Builder(this).setTitle("Queue validation").setMessage(msg).setPositiveButton("OK",null).show()); setStatus("Validation finished");
        });
    }

    private long durationMs(Uri u){ MediaMetadataRetriever m=new MediaMetadataRetriever(); try{m.setDataSource(this,u);String s=m.extractMetadata(MediaMetadataRetriever.METADATA_KEY_DURATION);return s==null?-1:Long.parseLong(s);}catch(Exception e){return -1;}finally{try{m.release();}catch(Exception ignored){}} }

    private String displayName(Uri u){
        try(Cursor c=getContentResolver().query(u,new String[]{OpenableColumns.DISPLAY_NAME},null,null,null)){if(c!=null&&c.moveToFirst())return c.getString(0);}catch(Exception ignored){} return u.getLastPathSegment()==null?u.toString():u.getLastPathSegment();
    }

    private void testProxy(boolean enabled,String type,String host,String portS,String user,String pass){
        setStatus("Testing network…"); io.execute(() -> {
            try {
                Proxy proxy=Proxy.NO_PROXY;
                if(enabled){int port=Integer.parseInt(portS); proxy=new Proxy("SOCKS5".equals(type)?Proxy.Type.SOCKS:Proxy.Type.HTTP,new InetSocketAddress(host,port)); if(!user.isEmpty()) Authenticator.setDefault(new Authenticator(){protected PasswordAuthentication getPasswordAuthentication(){return new PasswordAuthentication(user,pass.toCharArray());}});}
                HttpURLConnection c=(HttpURLConnection)new URL("https://api.ipify.org").openConnection(proxy); c.setConnectTimeout(12000);c.setReadTimeout(12000);c.setRequestProperty("User-Agent","ActorStickerAndroid/0.1");
                try(BufferedReader br=new BufferedReader(new InputStreamReader(c.getInputStream()))){String ip=br.readLine(); runOnUiThread(() -> new android.app.AlertDialog.Builder(this).setTitle("Connection OK").setMessage("Public IP: "+ip).setPositiveButton("OK",null).show());}
                setStatus("Network test OK");
            }catch(Exception e){runOnUiThread(() -> new android.app.AlertDialog.Builder(this).setTitle("Connection failed").setMessage(e.toString()).setPositiveButton("OK",null).show());setStatus("Network test failed");}
        });
    }

    private void saveState(){
        Set<String> vs=new LinkedHashSet<>(),rs=new LinkedHashSet<>();for(Uri u:videos)vs.add(u.toString());for(Uri u:refs)rs.add(u.toString());
        SharedPreferences.Editor e=prefs.edit().putStringSet("videos",vs).putStringSet("refs",rs); if(outputTree!=null)e.putString("output",outputTree.toString()); e.apply();
    }
    private void restoreState(){
        for(String s:prefs.getStringSet("videos",Collections.emptySet()))try{videos.add(Uri.parse(s));}catch(Exception ignored){}
        for(String s:prefs.getStringSet("refs",Collections.emptySet()))try{refs.add(Uri.parse(s));}catch(Exception ignored){}
        String out=prefs.getString("output",null);if(out!=null)outputTree=Uri.parse(out);
    }

    @Override protected void onDestroy(){ io.shutdownNow(); super.onDestroy(); }
}
