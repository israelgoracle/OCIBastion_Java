package ocitools;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.DateTimeException;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.regex.*;

public final class OciClient {
    private final Map<String,Object> settings; private final ProxySupport.Endpoint proxy;
    interface Executor { String run(List<String> command,Map<String,String> environment,int timeout) throws Exception; }
    private final Executor executor;
    public OciClient(Map<String,Object> settings,ProxySupport.Endpoint proxy) { this(settings,proxy,(a,e,t) -> Platform.run(a,e,t).success()); }
    OciClient(Map<String,Object> settings,ProxySupport.Endpoint proxy,Executor executor) { this.settings=settings; this.proxy=proxy; this.executor=executor; }
    public static Map<String,Map<String,String>> profiles(String path) throws IOException {
        Map<String,Map<String,String>> profiles=new LinkedHashMap<>(); Map<String,String> current=null;
        for(String line:Files.readAllLines(Platform.path(path),StandardCharsets.UTF_8)) {
            String s=line.trim();
            if(s.startsWith("[") && s.endsWith("]")) { current=new LinkedHashMap<>(); profiles.put(s.substring(1,s.length()-1),current); }
            else if(current!=null && !s.startsWith("#") && !s.startsWith(";") && s.contains("=")) {
                int i=s.indexOf('='); current.put(s.substring(0,i).trim(),s.substring(i+1).trim());
            }
        }
        return profiles;
    }
    Map<String,Object> call(String... args) throws Exception { return call(Arrays.asList(args)); }
    Map<String,Object> call(List<String> args) throws Exception {
        List<String> a=new ArrayList<>(Arrays.asList(Platform.executable("oci"),"--config-file",Platform.path(Json.string(settings,"configPath","")).toString(),
            "--profile",Platform.line(Json.string(settings,"profile","DEFAULT"),"OCI profile"),"--output","json")); a.addAll(args);
        return Json.object(Json.parse(executor.run(a,commandEnvironment(System.getenv(),proxy),360)));
    }
    static Map<String,String> commandEnvironment(Map<String,String> inherited,ProxySupport.Endpoint proxy) {
        Map<String,String> env=new HashMap<>(inherited);
        for(String key:Arrays.asList("http_proxy","HTTP_PROXY","https_proxy","HTTPS_PROXY","all_proxy","ALL_PROXY")) env.remove(key);
        // OCI CLI tests presence, not the value: even "False" enables a console prompt.
        env.keySet().removeIf(key -> Platform.windows() ? key.equalsIgnoreCase("OCI_CLI_AUTO_PROMPT") : key.equals("OCI_CLI_AUTO_PROMPT"));
        if(proxy!=null) { env.put("http_proxy","http://"+proxy.address()); env.put("https_proxy","http://"+proxy.address()); env.remove("no_proxy"); env.remove("NO_PROXY"); }
        return env;
    }
    public static final class Page {
        public final String tenancy,next; public final List<Object> records;
        Page(String tenancy,String next,List<Object> records) { this.tenancy=tenancy; this.next=next; this.records=records; }
    }
    public Page compartments(String page) throws Exception {
        Map<String,Map<String,String>> profiles=profiles(Json.string(settings,"configPath",""));
        Map<String,String> profile=profiles.get(Json.string(settings,"profile","DEFAULT"));
        String tenancy=profile==null ? "" : profile.getOrDefault("tenancy",profiles.getOrDefault("DEFAULT",Collections.emptyMap()).getOrDefault("tenancy",""));
        Platform.line(tenancy,"Tenancy in OCI profile");
        List<String> a=new ArrayList<>(Arrays.asList("iam","compartment","list","--compartment-id",tenancy,"--compartment-id-in-subtree","true","--access-level","ACCESSIBLE","--limit","100"));
        if(page!=null) a.addAll(Arrays.asList("--page",page));
        Map<String,Object> result=call(a); List<Object> records=new ArrayList<>();
        for(Object v:Json.array(result.get("data"))) if("ACTIVE".equals(Json.object(v).get("lifecycle-state"))) records.add(v);
        return new Page(tenancy,Json.string(result,"opc-next-page",null),records);
    }
    public List<Object> bastions() throws Exception {
        return Json.array(call("bastion","bastion","list","--compartment-id",Platform.line(Json.string(settings,"compartmentID",""),"Compartment OCID"),"--all").get("data"));
    }
    public static final class Session {
        public final String id,target; public final Instant expires;
        Session(String id,String target,Instant expires) { this.id=id; this.target=target; this.expires=expires; }
    }
    static final class SessionDataException extends IOException {
        SessionDataException(String message,Throwable cause) { super(message,cause); }
    }
    static Instant parseSessionTime(String value) throws SessionDataException {
        try {
            // OCI emits ISO timestamps with numeric offsets and up to nanosecond precision.
            // Java 8 Instant.parse accepts Z but rejects offsets such as +00:00.
            return OffsetDateTime.parse(value,DateTimeFormatter.ISO_OFFSET_DATE_TIME).toInstant();
        } catch(DateTimeException e) {
            throw new SessionDataException("OCI returned an invalid session creation timestamp: "+value+". Reconnection stopped to avoid creating repeated sessions.",e);
        }
    }
    public Session getSession(String id) throws Exception {
        Map<String,Object> data=Json.object(call("bastion","session","get","--session-id",id).get("data"));
        if(!"ACTIVE".equals(data.get("lifecycle-state")) || !Objects.equals(settings.get("bastionID"),data.get("bastion-id"))) return null;
        String command=Json.string(Json.object(data.get("ssh-metadata")),"command","");
        Matcher m=Pattern.compile("[A-Za-z0-9._-]+@[A-Za-z0-9._-]+").matcher(command); String target=null;
        while(m.find()) target=m.group(); if(target==null) throw new IOException("OCI session has no SSH target");
        Instant expiry=null; String created=Json.string(data,"time-created",null);
        if(created!=null && data.get("session-ttl-in-seconds")!=null) expiry=parseSessionTime(created).plusSeconds(Json.integer(data,"session-ttl-in-seconds",0));
        return new Session(Json.string(data,"id",""),target,expiry);
    }
    public Session createSession(String publicKey) throws Exception {
        String name="OCIBastion_Java-"+UUID.randomUUID(); Map<String,Object> key=new LinkedHashMap<>(); key.put("publicKeyContent",publicKey.trim());
        call("bastion","session","create-session-create-dynamic-port-forwarding-session-target-resource-details",
            "--bastion-id",Json.string(settings,"bastionID",""),"--key-type","PUB","--key-details",Json.write(key),
            "--session-ttl-in-seconds",Integer.toString(Json.integer(settings,"ttlSeconds",10800)),"--display-name",name,
            "--wait-for-state","SUCCEEDED","--max-wait-seconds","300","--wait-interval-seconds","5");
        for(int i=0;i<12;i++) {
            for(Object v:Json.array(call("bastion","session","list","--bastion-id",Json.string(settings,"bastionID",""),"--all").get("data"))) {
                Map<String,Object> row=Json.object(v);
                if(name.equals(row.get("display-name")) && "ACTIVE".equals(row.get("lifecycle-state"))) {
                    Session s=getSession(Json.string(row,"id","")); if(s!=null) return s;
                }
            }
            Thread.sleep(2000);
        }
        throw new IOException("Session was created but did not become active; check OCI before retrying");
    }
}
