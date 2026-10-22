package ocitools;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

public final class TunnelManager implements AutoCloseable {
    public interface Listener {
        void status(String message,boolean connected,boolean running);
        void session(String id,String fingerprint);
    }
    private final Listener listener;
    private volatile Thread worker; private volatile boolean stopping; private volatile Process process;
    private volatile boolean connected; private volatile int socksPort; private volatile List<String> activeCommand;
    private String ownerToken;
    public TunnelManager(Listener listener) { this.listener=listener; }
    public boolean running() { return worker!=null; }
    public boolean connected() { return connected; }
    public int port() { if(!connected) throw new IllegalStateException("Connect Bastion first"); return socksPort; }
    public List<String> command() { if(activeCommand==null) throw new IllegalStateException("No active session"); return new ArrayList<>(activeCommand); }
    private void update(String text,boolean up) { connected=up; listener.status(text,up,worker!=null); }
    public synchronized void connect(Map<String,Object> settings) throws Exception {
        if(worker!=null) throw new IllegalStateException("Tunnel is already running");
        Map<String,Object> s=Json.copy(settings); validate(s);
        stopping=false; worker=new Thread(() -> loop(s),"bastion-connection"); worker.setDaemon(true); worker.start();
    }
    static void validate(Map<String,Object> s) throws IOException {
        for(String key:Arrays.asList("configPath","profile","bastionID","publicKeyPath","privateKeyPath")) Platform.line(Json.string(s,key,""),key);
        for(String key:Arrays.asList("configPath","publicKeyPath","privateKeyPath")) if(!Files.isRegularFile(Platform.path(Json.string(s,key,"")))) throw new IOException("File does not exist: "+s.get(key));
        Platform.port(Json.integer(s,"socksPort",2222)); int ttl=Json.integer(s,"ttlSeconds",10800);
        if(ttl<1800 || ttl>10800) throw new IllegalArgumentException("Session duration must be between 1800 and 10800 seconds");
        Platform.executable("oci"); Platform.executable("ssh"); Platform.executable("ssh-keygen");
    }
    private void loop(Map<String,Object> s) {
        int retry=1, failures=0; String failedID=""; boolean intervention=false;
        try {
            while(!stopping) {
                try {
                    OwnedProcess.reclaim(); freePort(Json.integer(s,"socksPort",2222));
                    update("Checking SSH keys",false);
                    String fingerprint=Ssh.fingerprint(Json.string(s,"publicKeyPath",""));
                    if(!fingerprint.equals(Ssh.fingerprint(Json.string(s,"privateKeyPath","")))) throw new PermanentFailure("Public and private Bastion keys do not match");
                    checkStopped(); update("Detecting proxy",false); ProxySupport.Endpoint proxy=ProxySupport.resolve(s); checkStopped();
                    update("Preparing OCI session"+(proxy==null?" (direct)":" via "+proxy),false);
                    OciClient client=new OciClient(s,proxy); OciClient.Session session=null;
                    String owned=Json.string(s,"ownedSessionID","");
                    if(!owned.isEmpty() && fingerprint.equals(s.get("ownedSessionKeyFingerprint")) && !(owned.equals(failedID)&&failures>=3)) {
                        try { session=client.getSession(owned); } catch(Exception e) { if(e instanceof InterruptedException || e instanceof OciClient.SessionDataException) throw e; }
                    }
                    if(session==null || session.expires!=null && session.expires.isBefore(Instant.now().plusSeconds(90))) {
                        session=client.createSession(new String(Files.readAllBytes(Platform.path(Json.string(s,"publicKeyPath",""))),StandardCharsets.UTF_8));
                        s.put("ownedSessionID",session.id); s.put("ownedSessionKeyFingerprint",fingerprint); listener.session(session.id,fingerprint);
                    }
                    checkStopped(); ownerToken=UUID.randomUUID().toString();
                    List<String> command=Ssh.bastion(session.target,s,proxy,ownerToken); socksPort=Json.integer(s,"socksPort",2222);
                    update("Opening SOCKS5 tunnel",false);
                    ProcessBuilder builder=Platform.builder(command); builder.redirectErrorStream(true);
                    process=builder.start(); process.getOutputStream().close();
                    FutureTask<String> output=Platform.collect(process.getInputStream());
                    OwnedProcess.record(process,ownerToken,socksPort,session.target);
                    awaitListener(process,socksPort,output); checkStopped(); activeCommand=command;
                    update("Connected: SOCKS5 127.0.0.1:"+socksPort+(session.expires==null?"":"; session expires "+session.expires),true); retry=1;
                    while(process.isAlive() && !stopping) { Thread.sleep(500); }
                    if(stopping) break;
                    String reason=output.get(5,TimeUnit.SECONDS).trim();
                    if(session.id.equals(failedID)) failures++; else { failedID=session.id; failures=1; }
                    if(reason.isEmpty()) reason="SSH tunnel exited";
                    if(permanent(reason)) throw new PermanentFailure(reason);
                    throw new IOException(reason);
                } catch(InterruptedException e) { Thread.currentThread().interrupt(); break; }
                catch(Exception e) {
                    if(stopping) break;
                    String message=e.getMessage()==null?e.toString():e.getMessage();
                    if(requiresIntervention(e)) { update("Action required: "+message,false); intervention=true; break; }
                    // Failed startup also counts toward retiring a repeatedly failing owned session.
                    String id=Json.string(s,"ownedSessionID","");
                    if(id.equals(failedID)) failures++; else { failedID=id; failures=1; }
                    update("Reconnecting in "+retry+"s: "+message,false);
                } finally {
                    connected=false; activeCommand=null;
                    cleanupProcess();
                }
                if(!stopping) { Thread.sleep(retry*1000L); retry=Math.min(retry*2,30); }
            }
        } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
        finally {
            // Clear interruption while waiting for child termination.
            Thread.interrupted(); cleanupProcess(); connected=false; activeCommand=null;
            synchronized(this) { worker=null; }
            if(intervention) listener.status("Action required; see connection log",false,false);
            else listener.status("Disconnected",false,false);
        }
    }
    private void cleanupProcess() {
        Process p=process;
        boolean interrupted=Thread.interrupted();
        try {
            if(p!=null && p.isAlive()) Platform.stop(p);
            if(ownerToken!=null) OwnedProcess.clear(ownerToken);
        } catch(Exception e) { stopping=true; listener.status("SSH cleanup failed: "+e.getMessage(),false,true); }
        finally {
            if(p==null || !p.isAlive()) { process=null; ownerToken=null; }
            if(interrupted) Thread.currentThread().interrupt();
        }
    }
    private void checkStopped() throws InterruptedException { if(stopping || Thread.currentThread().isInterrupted()) throw new InterruptedException(); }
    static void freePort(int port) throws IOException {
        try(ServerSocket test=new ServerSocket()) { test.setReuseAddress(false); test.bind(new InetSocketAddress("127.0.0.1",port)); }
        catch(IOException e) { throw new PermanentFailure("Port "+port+" is occupied. Release it or choose another SOCKS port; no unrelated process was stopped."); }
    }
    private void awaitListener(Process p,int port,FutureTask<String> output) throws Exception {
        for(int i=0;i<60;i++) {
            checkStopped(); if(!p.isAlive()) throw new IOException("SSH exited before opening SOCKS5: "+output.get(5,TimeUnit.SECONDS));
            try(Socket socket=new Socket()) {
                socket.connect(new InetSocketAddress("127.0.0.1",port),150); socket.setSoTimeout(300);
                socket.getOutputStream().write(new byte[]{5,1,0});
                if(socket.getInputStream().read()==5 && socket.getInputStream().read()==0 && p.isAlive()) return;
            } catch(IOException ignored) { }
            Thread.sleep(250);
        }
        throw new IOException("SSH did not open its SOCKS5 listener in time");
    }
    static boolean requiresIntervention(Exception e) {
        return e instanceof PermanentFailure || e instanceof OciClient.SessionDataException || permanent(e.getMessage()==null?e.toString():e.getMessage());
    }
    private static boolean permanent(String text) {
        String s=text.toLowerCase(Locale.ROOT);
        for(String marker:Arrays.asList("permission denied","host key verification failed","passphrase","notauthorizedornotfound","address already in use","cannot find","no such file","could not request local forwarding")) if(s.contains(marker)) return true;
        return false;
    }
    public synchronized void disconnect() { stopping=true; if(worker!=null) worker.interrupt(); }
    public void close() {
        Thread t=worker; disconnect();
        if(t!=null) try { t.join(12000); } catch(InterruptedException e) { Thread.currentThread().interrupt(); }
    }
    static final class PermanentFailure extends IOException { PermanentFailure(String m) { super(m); } }
}
