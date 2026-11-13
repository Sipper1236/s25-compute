package dev.rayyan.compute;

import android.app.*;
import android.content.*;
import android.os.IBinder;
import org.json.JSONObject;
import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.*;
import java.util.concurrent.*;

public class ComputeService extends Service {
    private ServerSocket server;
    private final java.util.concurrent.atomic.AtomicBoolean cancelRequested = new java.util.concurrent.atomic.AtomicBoolean();
    private final ExecutorService workers = Executors.newSingleThreadExecutor();
    private volatile boolean workerBusy;
    private android.os.PowerManager.WakeLock wakeLock;
    private final Map<String, Future<?>> futures = new ConcurrentHashMap<>();
    private final Map<String, JSONObject> jobs = new ConcurrentHashMap<>();
    public static synchronized String token(Context c) {
        android.content.SharedPreferences p = c.getSharedPreferences("server", MODE_PRIVATE);
        String key = p.getString("token", null);
        if (key == null) { key = UUID.randomUUID().toString() + UUID.randomUUID(); p.edit().putString("token", key).commit(); }
        return key;
    }
    @Override public IBinder onBind(Intent i) { return null; }
    @Override public int onStartCommand(Intent i, int flags, int id) {
        if (server != null) return START_NOT_STICKY;
        NotificationManager nm = getSystemService(NotificationManager.class);
        nm.createNotificationChannel(new NotificationChannel("compute", "Compute service", NotificationManager.IMPORTANCE_LOW));
        PendingIntent open = PendingIntent.getActivity(this,0,new Intent(this,MainActivity.class),PendingIntent.FLAG_IMMUTABLE);
        startForeground(1, new Notification.Builder(this,"compute").setContentTitle("S25 Compute is available").setContentText("Private local job API on port 8787").setSmallIcon(android.R.drawable.stat_notify_sync).setContentIntent(open).build());
        try { server = new ServerSocket(8787, 8, InetAddress.getByName("127.0.0.1")); }
        catch (IOException e) { stopSelf(); return START_NOT_STICKY; }
        new Thread(() -> { while (!server.isClosed()) { try { handle(server.accept()); } catch (IOException ignored) {} } }, "compute-api").start();
        return START_NOT_STICKY;
    }
    private synchronized void handle(Socket socket) {
        try (Socket s = socket) {
            s.setSoTimeout(3000);
            BufferedReader reader = new BufferedReader(new InputStreamReader(new LimitedInputStream(s.getInputStream()), StandardCharsets.US_ASCII));
            String first = reader.readLine(); if (first == null) return;
            String[] request = first.split(" ");
            if(request.length!=3 || !request[2].equals("HTTP/1.1")) { reply(s,400,new JSONObject().put("error","Expected HTTP/1.1 request")); return; } String auth = ""; int total = first.length();
            for (String line; (line = reader.readLine()) != null && !line.isEmpty();) {
                total += line.length(); if (total > 8192) { reply(s,413,new JSONObject()); return; }
                if (line.regionMatches(true,0,"Authorization:",0,14)) auth = line.substring(14).trim();
            }
            if (!MessageDigest.isEqual(auth.getBytes(StandardCharsets.UTF_8), ("Bearer " + token(this)).getBytes(StandardCharsets.UTF_8))) { reply(s,401,new JSONObject().put("error","unauthorized")); return; }
            if (request.length < 2) { reply(s,400,new JSONObject()); return; }
            android.net.Uri uri = android.net.Uri.parse(request[1]);
            String path = uri.getPath();
            int[] input = new int[1024]; for(int n=0;n<input.length;n++) input[n]=n;
            String values=uri.getQueryParameter("values");
            if(values!=null) {
                String[] parts=values.split(",",-1);
                if(parts.length>512) { reply(s,400,new JSONObject().put("error","At most 512 integers")); return; }
                input=new int[parts.length];
                try { for(int n=0;n<parts.length;n++) input[n]=Integer.parseInt(parts[n]); }
                catch(NumberFormatException e) { reply(s,400,new JSONObject().put("error","Expected signed 32-bit integers")); return; }
            }
            final int[] gpuInput=input;
            final String text=uri.getQueryParameter("text");
            if (request[0].equals("GET") && path.equals("/capabilities")) {
                reply(s,200,new JSONObject().put("cpuThreads",Runtime.getRuntime().availableProcessors()).put("gpuBackend","OpenGL ES 3.1").put("gpuExecutionVerified",false).put("vulkanDeclared",getPackageManager().hasSystemFeature("android.hardware.vulkan.level")).put("tasks",new org.json.JSONArray().put("sha256").put("gpu-vector"))); return;
            }
            if (request[0].equals("POST") && (path.equals("/jobs/sha256") || path.equals("/jobs/gpu-vector"))) {
                if (workerBusy) { reply(s,429,new JSONObject().put("error","worker busy")); return; }
                if (jobs.size() >= 32) { jobs.clear(); futures.clear(); }
                String id = UUID.randomUUID().toString(); jobs.put(id,new JSONObject().put("status","running"));
                cancelRequested.set(false); workerBusy = true;
                android.os.PowerManager power=(android.os.PowerManager)getSystemService(POWER_SERVICE);
                wakeLock=power.newWakeLock(android.os.PowerManager.PARTIAL_WAKE_LOCK,"S25Compute:job"); wakeLock.acquire(600000);
                futures.put(id, workers.submit(() -> { try { if (path.endsWith("gpu-vector")) runGpu(id,gpuInput); else runJob(id,text); } finally { if(wakeLock!=null && wakeLock.isHeld()) wakeLock.release(); workerBusy = false; } })); reply(s,202,new JSONObject().put("id",id)); return;
            }
            if (request[0].equals("DELETE") && path.startsWith("/jobs/")) {
                String id = path.substring(6); Future<?> future = futures.get(id);
                if (future == null) { reply(s,404,new JSONObject()); return; }
                if (!jobs.get(id).optString("status").equals("running") && !jobs.get(id).optString("status").equals("cancelling")) { reply(s,409,jobs.get(id)); return; }
                cancelRequested.set(true); jobs.put(id,new JSONObject().put("status","cancelling"));
                reply(s,200,jobs.get(id)); return;
            }
            if (request[0].equals("GET") && path.startsWith("/jobs/")) { JSONObject job = jobs.get(path.substring(6)); reply(s,job == null ? 404 : 200,job == null ? new JSONObject() : job); return; }
            reply(s,404,new JSONObject());
        } catch (Exception ignored) { }
    }
    private static class LimitedInputStream extends FilterInputStream {
        private int remaining = 8192;
        LimitedInputStream(InputStream in) { super(in); }
        @Override public int read() throws IOException { if (--remaining < 0) throw new IOException("Request too large"); return in.read(); }
        @Override public int read(byte[] b, int off, int len) throws IOException { if (remaining <= 0) throw new IOException("Request too large"); int n = in.read(b,off,Math.min(len,remaining)); if(n>0) remaining-=n; return n; }
    }
    private synchronized void finish(String id,JSONObject result) {
        JSONObject previous=jobs.get(id);
        if(previous!=null && previous.optString("status").equals("cancelling")) {
            try { result.put("status","cancelled"); } catch(Exception ignored) {}
        }
        jobs.put(id,result);
    }
    private void runGpu(String id,int[] input) {
        try { JSONObject result = GpuCompute.run(input).put("status", cancelRequested.get() ? "cancelled" : "complete");
            if (!Thread.currentThread().isInterrupted()) finish(id,result);
        } catch (Exception e) { try { finish(id,new JSONObject().put("status","failed").put("error",e.toString())); } catch (Exception ignored) {} }
    }
    private void runJob(String id,String text) {
        try {
            long start = System.nanoTime(); MessageDigest digest = MessageDigest.getInstance("SHA-256"); byte[] block = new byte[65536]; new java.security.SecureRandom().nextBytes(block);
            if(text!=null) block=text.getBytes(StandardCharsets.UTF_8);
            int count = 0;
            for (; count < (text==null ? 4096 : 1) && !cancelRequested.get() && !Thread.currentThread().isInterrupted(); count++) {
                digest.update(block);
                if(count%64==0) {
                    JSONObject progress=new JSONObject().put("status","running").put("progress",(count+1)*100/(text==null?4096:1));
                    jobs.computeIfPresent(id,(key,old) -> old.optString("status").equals("cancelling") ? old : progress);
                }
            }
            StringBuilder hex = new StringBuilder(); for (byte b : digest.digest()) hex.append(String.format("%02x", b & 255));
            finish(id,new JSONObject().put("status",(cancelRequested.get() || Thread.currentThread().isInterrupted()) ? "cancelled" : "complete").put("bytes",(long) count * block.length).put("elapsedMs",(System.nanoTime()-start)/1000000).put("sha256",hex.toString()));
        } catch (Exception e) { try { finish(id,new JSONObject().put("status","failed").put("error",e.toString())); } catch (Exception ignored) {} }
    }
    private void reply(Socket s, int code, JSONObject json) throws IOException {
        byte[] body = json.toString().getBytes(StandardCharsets.UTF_8);
        s.getOutputStream().write(("HTTP/1.1 " + code + " Result\r\nContent-Type: application/json\r\nContent-Length: " + body.length + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); s.getOutputStream().write(body); s.getOutputStream().flush();
    }
    @Override public void onDestroy() { try { if (server != null) server.close(); } catch (IOException ignored) {} cancelRequested.set(true); workers.shutdownNow(); if(wakeLock!=null && wakeLock.isHeld()) wakeLock.release(); super.onDestroy(); }
}
