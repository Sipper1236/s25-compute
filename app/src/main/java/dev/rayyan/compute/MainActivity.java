package dev.rayyan.compute;

import android.app.Activity;
import android.os.*;
import android.content.*;
import android.widget.*;
import java.net.*;
import java.io.*;
import java.nio.charset.StandardCharsets;
import org.json.JSONObject;

public class MainActivity extends Activity {
    private TextView status;
    private String activeJob;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private boolean visible;
    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        ScrollView scroll = new ScrollView(this);
        LinearLayout layout = new LinearLayout(this);
        layout.setOrientation(LinearLayout.VERTICAL); layout.setPadding(32,48,32,32); scroll.addView(layout);
        TextView title = new TextView(this); title.setText("S25 Compute"); title.setTextSize(28); layout.addView(title);
        TextView info = new TextView(this);
        info.setText("CPU and GPU worker\n\n1. Start this service.\n2. In TailSocks, select Proxy mode.\n3. Add tailnet-only Serve → http://127.0.0.1:8787\n\nNo Android VPN permission is used. Keep Funnel disabled.");
        layout.addView(info);
        button(layout,"Start service", () -> startForegroundService(new Intent(this,ComputeService.class)));
        button(layout,"Stop service", () -> { stopService(new Intent(this,ComputeService.class)); status.setText("Stopped"); activeJob=null; });
        button(layout,"Copy API token", () -> { ((ClipboardManager)getSystemService(CLIPBOARD_SERVICE)).setPrimaryClip(ClipData.newPlainText("Compute API token",ComputeService.token(this))); Toast.makeText(this,"Token copied",Toast.LENGTH_SHORT).show(); });
        button(layout,"Check capabilities", () -> request("GET","/capabilities",false));
        button(layout,"Run CPU job", () -> request("POST","/jobs/sha256",true));
        button(layout,"Run GPU job", () -> request("POST","/jobs/gpu-vector",true));
        button(layout,"Cancel current job", () -> { if(activeJob!=null) request("DELETE","/jobs/"+activeJob,false); });
        status = new TextView(this); status.setText("Start the service to begin."); status.setTextIsSelectable(true); status.setPadding(0,24,0,0); layout.addView(status);
        setContentView(scroll);
        if (Build.VERSION.SDK_INT >= 33 && checkSelfPermission("android.permission.POST_NOTIFICATIONS") != android.content.pm.PackageManager.PERMISSION_GRANTED) requestPermissions(new String[]{"android.permission.POST_NOTIFICATIONS"},1);
    }
    private void button(LinearLayout parent,String label,Runnable action) { Button b=new Button(this); b.setText(label); b.setOnClickListener(v -> action.run()); parent.addView(b); }
    private void request(String method,String path,boolean submission) {
        new Thread(() -> {
            HttpURLConnection c=null;
            try {
                c=(HttpURLConnection)new URL("http://127.0.0.1:8787"+path).openConnection();
                c.setConnectTimeout(2000); c.setReadTimeout(4000); c.setRequestMethod(method); c.setRequestProperty("Authorization","Bearer "+ComputeService.token(this));
                int code=c.getResponseCode(); InputStream stream=code<400?c.getInputStream():c.getErrorStream();
                ByteArrayOutputStream bytes=new ByteArrayOutputStream(); byte[] chunk=new byte[1024];
                if(stream!=null) try(InputStream in=stream) { for(int n;(n=in.read(chunk))!=-1;) bytes.write(chunk,0,n); }
                JSONObject json=new JSONObject(bytes.toString(StandardCharsets.UTF_8.name()));
                String message="HTTP "+code+"\n"+json.toString(2);
                runOnUiThread(() -> {
                    if(!visible) return; status.setText(message);
                    if(submission && code==202) { activeJob=json.optString("id"); poll(); }
                    else if(path.startsWith("/jobs/") && method.equals("GET") && (json.optString("status").equals("running") || json.optString("status").equals("cancelling"))) poll();
                });
            } catch(Exception e) { runOnUiThread(() -> { if(visible) status.setText("Service unavailable: "+e.getMessage()); }); }
            finally { if(c!=null)c.disconnect(); }
        },"compute-ui-request").start();
    }
    private void poll() { handler.postDelayed(() -> { if(visible && activeJob!=null) request("GET","/jobs/"+activeJob,false); },500); }
    @Override protected void onResume() { super.onResume(); visible=true; }
    @Override protected void onPause() { visible=false; handler.removeCallbacksAndMessages(null); super.onPause(); }
}
