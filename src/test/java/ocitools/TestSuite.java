package ocitools;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.regex.Pattern;

/** Offline regression suite; no OCI credentials, cloud requests or third-party libraries. */
public final class TestSuite {
    private static int checks;
    private static Path project;
    private interface Checked { void run() throws Exception; }
    private static void check(boolean result,String name) { checks++; if(!result) throw new AssertionError(name); }
    private static void rejects(Checked action,String name) throws Exception {
        try { action.run(); } catch(Exception expected) { checks++; return; } throw new AssertionError("Expected rejection: "+name);
    }
    private static Map<String,Object> obj(String s) { return Json.object(Json.parse(s)); }
    private static Map<String,Object> saved(String id,String name,Map<String,Object> s) { Map<String,Object> m=new LinkedHashMap<>(); m.put("id",id); m.put("name",name); m.put("snapshot",Configuration.snapshot(s)); return m; }
    public static void main(String[] args) throws Exception {
        if(args.length==2 && args[0].equals("--oci-noninteractive")) {
            Path emptyConfig=Paths.get(args[1],"build","noninteractive-test-config");
            Files.write(emptyConfig,Arrays.asList("[DEFAULT]","region=eu-frankfurt-1"),StandardCharsets.UTF_8);
            Map<String,Object> s=Configuration.defaults(); s.put("configPath",emptyConfig.toAbsolutePath().toString());
            OciClient client=new OciClient(s,null,(command,env,timeout) -> {
                if(env.keySet().stream().anyMatch(key -> key.equalsIgnoreCase("OCI_CLI_AUTO_PROMPT"))) throw new AssertionError("Interactive mode must be absent");
                Platform.Result result=Platform.run(command,env,30); result.success();
                if(!result.out.contains("Usage:")) throw new AssertionError("Expected noninteractive CLI help");
                System.out.println("Installed OCI CLI completed noninteractive command help with exit code "+result.status+"; no credentials or cloud requests used");
                return "{\"data\":[]}";
            });
            client.call("iam","region","list","--help"); return;
        }
        if(args.length==1 && args[0].equals("--oci-discovery")) {
            String executable=Platform.executable("oci");
            System.out.println("OCI executable: "+executable);
            System.out.println("OCI version: "+Platform.run(Arrays.asList(executable,"--version"),null,20).success().trim());
            return;
        }
        if(args.length>0 && args[0].equals("--child")) {
            if(args[1].equals("echo")) { System.out.print(Json.write(Arrays.asList(Arrays.copyOfRange(args,2,args.length)))); return; }
            if(args[1].equals("sleep")) { Thread.sleep(30000); return; }
            for(int i=0;i<20000;i++) { System.out.println("stdout-"+i); System.err.println("stderr-"+i); } return;
        }
        project=Paths.get(args[0]).toAbsolutePath();
        System.setProperty("ocitools.home",Files.createTempDirectory(project.resolve("build"),"test-settings-").toString());
        String java=Paths.get(System.getProperty("java.home"),"bin",Platform.windows()?"java.exe":"java").toString();
        System.setProperty("ocitools.oci",java); System.setProperty("ocitools.ssh",java);
        json(); archives(); commands(); storage(); executableDiscovery(); proxy(); oci(); processes(java); ports(); nativeSshProxy(java);
        System.out.println(checks+" offline checks passed");
    }
    private static void json() throws Exception {
        Map<String,Object> data=obj("{\"a\":[1,true,null,\"x\\n\\u00e9\"],\"p\":\"C:\\\\a'b\"}");
        check(data.equals(Json.parse(Json.write(data))),"JSON round trip");
        for(String bad:Arrays.asList("{\"a\":1,\"a\":2}","[1,]","{} garbage","01","1e9999","\"raw\nline\"","{\"a\":}")) rejects(() -> Json.parse(bad),"malformed JSON");
        rejects(() -> Json.integer(obj("{\"port\":1.5}"),"port",0),"fractional port");
        rejects(() -> Json.string(obj("{\"profile\":123}"),"profile",""),"string type");
    }
    private static void executableDiscovery() throws Exception {
        Map<String,String> env=new HashMap<>(); env.put("Path","example-directory"); env.put("windir","example-windows");
        if(Platform.windows()) {
            check(Platform.environmentValue(env,"PATH","").equals("example-directory"),"Windows Path variable is case insensitive");
            check(Platform.environmentValue(env,"WINDIR","").equals("example-windows"),"Windows environment lookup handles mixed case");
        } else check(Platform.environmentValue(env,"PATH","absent").equals("absent"),"Unix PATH stays case sensitive");
        Path dir=Files.createTempDirectory(Configuration.dataDirectory(),"CLI with spaces ");
        Path executable=dir.resolve(Platform.windows()?"fake-oci.exe":"fake-oci"); Files.write(executable,new byte[0]);
        if(!Platform.windows()) executable.toFile().setExecutable(true,true);
        check(Platform.findExecutable("fake-oci",Arrays.asList("", "invalid\0path", " \""+dir+"\" ")).equals(executable.toAbsolutePath().toString()),"discovery tolerates quoted spaced paths and malformed earlier entries");
        check(Platform.findExecutable("fake-missing",Collections.singletonList(dir.toString()))==null,"missing executable is not falsely detected");
        check(Platform.environmentValue(env,"MISSING","fallback").equals("fallback"),"missing environment variable fallback");
    }
    private static void archives() throws Exception {
        Map<String,Object> s=Configuration.defaults(); s.put("profile","EDITED");
        String id=UUID.randomUUID().toString(); Configuration.saved(s).add(saved(id,"Café",s)); s.put("selectedSavedConnectionID",id);
        Map<String,Object> active=Configuration.archive(s,true); Configuration.validateArchive(active);
        check(!active.containsKey("current"),"named export has no current");
        check(Json.object(Json.object(Configuration.saved(active).get(0)).get("snapshot")).get("profile").equals("EDITED"),"active export uses visible changes");
        s.put("profile","UNCHANGED");
        Map<String,Object> merged=Configuration.merge(s,active);
        check(merged.get("profile").equals("UNCHANGED"),"named-only import preserves current");
        Map<String,Object> incoming=Json.copy(active); Map<String,Object> entry=Json.object(Configuration.saved(incoming).get(0));
        entry.put("name"," CAFE "); entry.put("id",UUID.randomUUID().toString());
        check(Configuration.conflicts(s,incoming).size()==1,"case/diacritic conflict detection");
        merged=Configuration.merge(s,incoming);
        check(Configuration.saved(merged).size()==1 && Json.object(Configuration.saved(merged).get(0)).get("id").equals(id),"merge retains existing ID");
        Map<String,Object> collision=Json.copy(active); Json.object(Configuration.saved(collision).get(0)).put("name","Another");
        merged=Configuration.merge(s,collision);
        check(Configuration.saved(merged).size()==2 && !Json.object(Configuration.saved(merged).get(1)).get("id").equals(id),"different-name ID collision remapped");
        Map<String,Object> all=Configuration.archive(s,false); Configuration.validateArchive(all);
        merged=Configuration.merge(Configuration.defaults(),all);
        check(merged.get("profile").equals("UNCHANGED") && merged.get("selectedSavedConnectionID").equals(id),"all export current and selection");
        Map<String,Object> plain=Configuration.defaults(); plain.put("profile","NEW");
        merged=Configuration.merge(s,Configuration.archive(plain,true));
        check(merged.get("profile").equals("NEW") && Configuration.saved(merged).size()==1,"unnamed current import preserves saved");
        plain.put("ownedSessionID","session"); plain.put("ownedSessionKeyFingerprint","fp"); Map<String,Object> different=Configuration.snapshot(plain); different.put("bastionID","different"); Configuration.apply(plain,different);
        check(!plain.containsKey("ownedSessionID"),"identity change invalidates owned session");
        rejects(() -> Configuration.validateArchive(obj("{\"formatVersion\":99,\"savedConnections\":[]}")),"archive version");
        rejects(() -> Configuration.validateArchive(obj("{\"formatVersion\":2,\"savedConnections\":[]}")),"empty archive");
        Map<String,Object> duplicate=Json.copy(active); Configuration.saved(duplicate).add(Json.copy(Json.object(Configuration.saved(duplicate).get(0))));
        rejects(() -> Configuration.validateArchive(duplicate),"duplicate connection");
        Map<String,Object> badSelected=Json.copy(all); badSelected.put("selectedSavedConnectionID",UUID.randomUUID().toString()); rejects(() -> Configuration.validateArchive(badSelected),"unknown selected connection");
        Map<String,Object> legacy=Configuration.defaults(); legacy.remove("proxyMode"); legacy.put("proxyHost","proxy.example");
        check(Configuration.snapshot(legacy).get("proxyMode").equals("manual"),"legacy manual proxy default");
        for(String fixture:Arrays.asList("legacy-v1.json","named-v2.json")) {
            Map<String,Object> a=Configuration.read(project.resolve("src/test/fixtures").resolve(fixture)); Configuration.validateArchive(a);
            check(Configuration.saved(Configuration.merge(Configuration.defaults(),a)).size()==1,"portable fixture "+fixture);
        }
        // Exercise the sanitized macOS fixtures when that project is checked out alongside this one.
        Path source=project.getParent().resolve("OCIBastion_MacOs"); int fixtures=0;
        for(String file:Arrays.asList("Tests/LegacyArchive.json","Tests/NamedOnlyArchive.json","Tests/FromScriptArchive.json")) {
            Path p=source.resolve(file);
            if(Files.exists(p)) { Map<String,Object> a=Configuration.read(p); Configuration.validateArchive(a); Configuration.merge(Configuration.defaults(),a); fixtures++; }
        }
        if(Files.isDirectory(source)) check(fixtures==3,"all three original Swift archive fixtures");
        check(!Json.write(Configuration.archive(plain,false)).contains("ownedSession"),"exports exclude session ownership");
    }
    private static void commands() throws Exception {
        Map<String,Object> d=obj("{\"host\":\"10.0.0.9\",\"user\":\"opc\",\"port\":22,\"sshPort\":22,\"privateKeyPath\":\"key with space'quote\",\"localPort\":19081,\"remoteHost\":\"::1\",\"remotePort\":9081}");
        List<String> cmd=Ssh.destination(d,20000,true);
        check(cmd.contains("127.0.0.1:19081:[::1]:9081") && cmd.contains("-N"),"IPv6 local forwarding bound to loopback");
        check(cmd.contains("opc@10.0.0.9") && cmd.contains("-p"),"destination SSH target");
        check(cmd.stream().anyMatch(v -> v.startsWith("ProxyCommand=") && v.contains("--proxy") && v.contains("socks") && v.contains("20000")),"portable SOCKS helper");
        check(cmd.stream().noneMatch(v -> v.startsWith("HostKeyAlgorithms=")),"legacy host key disabled by default");
        d.put("allowLegacyRsaHostKey",true);
        check(Ssh.destination(d,20000,true).contains("HostKeyAlgorithms=+ssh-rsa"),"local tunnel permits legacy RSA only when selected");
        check(Ssh.destination(d,20000,false).contains("HostKeyAlgorithms=+ssh-rsa"),"destination permits legacy RSA only when selected");
        check(Ssh.destination(d,20000,true).stream().noneMatch(v -> v.contains("ssh-dss") || v.startsWith("PubkeyAccepted")),"host compatibility does not enable DSA or alter user authentication");
        d.put("allowLegacyRsaUserSignature",true);
        check(Ssh.destination(d,20000,true).contains("PubkeyAcceptedKeyTypes=+ssh-rsa"),"local tunnel can opt into legacy RSA user signatures");
        check(Ssh.destination(d,20000,false).contains("PubkeyAcceptedKeyTypes=+ssh-rsa"),"destination can opt into legacy RSA user signatures");
        d.put("allowLegacyRsaHostKey",false);
        check(!Ssh.destination(d,20000,true).contains("HostKeyAlgorithms=+ssh-rsa") && Ssh.destination(d,20000,true).contains("PubkeyAcceptedKeyTypes=+ssh-rsa"),"user-signature compatibility is independent of host-key compatibility");
        d.put("allowLegacyRsaHostKey",true);
        Map<String,Object> legacySettings=Configuration.defaults(); Json.array(legacySettings.get("tunnels")).add(Json.copy(d));
        Map<String,Object> exported=Configuration.archive(legacySettings,false); Configuration.validateArchive(exported);
        Map<String,Object> restored=Configuration.merge(Configuration.defaults(),Json.object(Json.parse(Json.write(exported))));
        check(Json.bool(Json.object(Json.array(restored.get("tunnels")).get(0)),"allowLegacyRsaHostKey",false),"per-tunnel compatibility survives archive round trip");
        check(Json.bool(Json.object(Json.array(restored.get("tunnels")).get(0)),"allowLegacyRsaUserSignature",false),"per-tunnel user-signature option survives archive round trip");
        d.put("allowLegacyRsaUserSignature","true"); rejects(() -> Ssh.destination(d,20000,true),"user-signature compatibility flag must be boolean");
        d.put("allowLegacyRsaUserSignature",false);
        check(Ssh.destination(d,20000,true).stream().noneMatch(v -> v.startsWith("PubkeyAccepted")),"user-signature compatibility can be switched off");
        Map<String,Object> invalidUserSignature=Json.copy(legacySettings);
        Json.object(Json.array(invalidUserSignature.get("tunnels")).get(0)).put("allowLegacyRsaUserSignature",1);
        rejects(() -> Configuration.validateSnapshot(invalidUserSignature),"import rejects invalid user-signature compatibility flag");
        d.put("allowLegacyRsaHostKey","true"); rejects(() -> Ssh.destination(d,20000,true),"compatibility flag must be boolean");
        Json.object(Json.array(legacySettings.get("tunnels")).get(0)).put("allowLegacyRsaHostKey","true");
        rejects(() -> Configuration.validateSnapshot(legacySettings),"import rejects invalid compatibility flag");
        d.put("allowLegacyRsaHostKey",false); check(!Ssh.destination(d,20000,true).contains("HostKeyAlgorithms=+ssh-rsa"),"compatibility can be switched off");
        check(Platform.quote("a'b",false).equals("'a'\\''b'"),"POSIX shell quoting");
        check(Platform.quote("a'b",true).equals("'a''b'"),"PowerShell quoting");
        Map<String,Object> settings=Configuration.defaults(); settings.put("privateKeyPath","key");
        cmd=Ssh.bastion("session@bastion.example",settings,new ProxySupport.Endpoint("proxy.example",80,"test"),"owner");
        check(cmd.contains("SetEnv=OCIBASTION_TUNNEL_ID=owner") && cmd.contains("127.0.0.1:2222"),"ownership marker and loopback SOCKS");
        check(cmd.stream().anyMatch(v -> v.startsWith("ProxyCommand=") && v.contains("http")),"HTTP CONNECT helper");
        check(cmd.contains("BatchMode=yes") && cmd.contains("IdentitiesOnly=yes"),"unattended bastion SSH options");
        check(cmd.stream().noneMatch(v -> v.startsWith("HostKeyAlgorithms=")),"Bastion host key defaults remain unchanged");
        check(cmd.stream().noneMatch(v -> v.startsWith("PubkeyAccepted")),"Bastion user-signature defaults remain unchanged");
        d.put("host","-oProxyCommand=bad"); rejects(() -> Ssh.destination(d,20000,false),"SSH option injection");
        d.put("host","host;touch bad"); rejects(() -> Ssh.destination(d,20000,false),"shell injection");
        d.put("host","10.0.0.1"); d.put("remotePort",65536); rejects(() -> Ssh.destination(d,20000,true),"invalid forwarding port");
        rejects(() -> Ssh.bastion("bad@host;bad",settings,null,null),"invalid bastion target");
    }
    private static void storage() throws Exception {
        Map<String,Object> s=Configuration.defaults(); s.put("profile","storage"); Configuration.save(s);
        check(Configuration.load().get("profile").equals("storage"),"settings save/load");
        Path dir=Configuration.dataDirectory();
        try(java.util.stream.Stream<Path> files=Files.list(dir)) { check(files.noneMatch(p -> p.getFileName().toString().endsWith(".tmp")),"atomic write cleans temporary files"); }
        Path f=dir.resolve("export.json"); Configuration.write(f,Configuration.archive(s,false)); check(Configuration.read(f).get("formatVersion").equals(2L),"export persisted");
        check(Files.isReadable(f),"owner can read restricted file");
        if(Files.getFileAttributeView(f,java.nio.file.attribute.PosixFileAttributeView.class)!=null) check(Files.getPosixFilePermissions(f).equals(java.nio.file.attribute.PosixFilePermissions.fromString("rw-------")),"POSIX owner-only mode");
    }
    private static void proxy() throws Exception {
        check(ProxySupport.fromUrl("proxy.example:8080","test").port==8080,"proxy URL");
        check(ProxySupport.fromUrl("http://user:secret@proxy.example:80","test")==null,"proxy credentials rejected");
        check(ProxySupport.fromUrl("https://proxy.example:80","test")==null,"TLS proxy unsupported");
        check(ProxySupport.fromPac("return 'PROXY proxy.example:3128; DIRECT';").port==3128,"static PAC proxy");
        check(ProxySupport.fromPac("var proxies = 'proxy.example:3129';").port==3129,"legacy PAC proxies assignment");
        Map<String,Object> direct=Configuration.defaults(); direct.put("proxyMode","direct"); check(ProxySupport.resolve(direct)==null,"explicit direct ignores environment");
        direct.put("proxyMode","manual"); direct.put("proxyHost","proxy.example"); direct.put("proxyPort",3128); check(ProxySupport.resolve(direct).port==3128,"manual proxy resolution");
        handshake("http",false); handshake("http",true); handshake("socks",false); handshake("socks",true);
    }
    private static void handshake(String mode,boolean reject) throws Exception {
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(5000);
            FutureTask<Void> peer=new FutureTask<>(() -> {
                try(Socket s=server.accept()) {
                    s.setSoTimeout(5000); DataInputStream in=new DataInputStream(s.getInputStream()); OutputStream out=s.getOutputStream();
                    if(mode.equals("http")) {
                        ByteArrayOutputStream request=new ByteArrayOutputStream(); int c;
                        while((c=in.read())!=-1) { request.write(c); if(request.toString("US-ASCII").endsWith("\r\n\r\n")) break; }
                        if(!request.toString("US-ASCII").startsWith("CONNECT target.example:22 HTTP/1.1")) throw new IOException("Wrong CONNECT request");
                        out.write((reject?"HTTP/1.1 407 Authentication Required\r\n\r\n":"HTTP/1.1 200 Connection established\r\n\r\nZ").getBytes(StandardCharsets.US_ASCII));
                    } else {
                        byte[] hello=new byte[3]; in.readFully(hello); if(!Arrays.equals(hello,new byte[]{5,1,0})) throw new IOException("Wrong SOCKS greeting");
                        out.write(new byte[]{5,(byte)(reject?255:0)}); out.flush(); if(reject) return null;
                        byte[] header=new byte[5]; in.readFully(header); if(header[0]!=5 || header[1]!=1 || header[3]!=3) throw new IOException("Wrong SOCKS request");
                        byte[] target=new byte[header[4]&255]; in.readFully(target); if(!new String(target,StandardCharsets.US_ASCII).equals("target.example") || in.readUnsignedShort()!=22) throw new IOException("Wrong SOCKS target");
                        out.write(new byte[]{5,0,0,1,127,0,0,1,0,22,'Z'});
                    }
                    out.flush();
                    if(!reject) { int received=in.read(); if(received!='Q') throw new IOException("Tunnel byte missing"); }
                }
                return null;
            }); Thread t=new Thread(peer,"mock-proxy"); t.setDaemon(true); t.start();
            if(reject) rejects(() -> ProxySupport.connect(mode,"127.0.0.1",server.getLocalPort(),"target.example",22),mode+" rejects handshake");
            else try(Socket socket=ProxySupport.connect(mode,"127.0.0.1",server.getLocalPort(),"target.example",22)) {
                socket.setSoTimeout(5000); check(socket.getInputStream().read()=='Z',mode+" preserves first tunneled byte"); socket.getOutputStream().write('Q'); socket.getOutputStream().flush();
            }
            peer.get(6,TimeUnit.SECONDS); check(true,mode+" proxy exchange");
        }
    }
    private static void oci() throws Exception {
        check(OciClient.parseSessionTime("2026-10-21T14:26:24.473000+00:00").equals(java.time.Instant.parse("2026-10-21T14:26:24.473Z")),"exact reported OCI timestamp on Java 8");
        check(OciClient.parseSessionTime("2026-10-21T16:26:24.473000+02:00").equals(java.time.Instant.parse("2026-10-21T14:26:24.473Z")),"nonzero OCI timezone offset normalized to UTC");
        check(OciClient.parseSessionTime("2026-10-21T14:26:24+00:00").equals(java.time.Instant.parse("2026-10-21T14:26:24Z")),"OCI timestamp without fractional seconds");
        check(OciClient.parseSessionTime("2026-10-21T14:26:24.123456789Z").getNano()==123456789,"OCI nanoseconds and Z suffix preserved");
        try { OciClient.parseSessionTime("invalid-time"); throw new AssertionError("Invalid timestamp accepted"); }
        catch(OciClient.SessionDataException e) { check(TunnelManager.requiresIntervention(e),"invalid timestamp stops automatic session creation retries"); }
        rejects(() -> OciClient.parseSessionTime("2026-10-21T14:26:24"),"timestamp without timezone rejected");
        for(String value:Arrays.asList("True","False","")) {
            Map<String,String> inherited=new HashMap<>(); inherited.put("OCI_CLI_AUTO_PROMPT",value); inherited.put("OCI_CLI_AUTH","security_token");
            Map<String,String> sanitized=OciClient.commandEnvironment(inherited,null);
            check(!sanitized.containsKey("OCI_CLI_AUTO_PROMPT"),"interactive variable removed regardless of value");
            check(inherited.containsKey("OCI_CLI_AUTO_PROMPT") && "security_token".equals(sanitized.get("OCI_CLI_AUTH")),"sanitizing child environment preserves parent and authentication");
        }
        if(Platform.windows()) {
            Map<String,String> inherited=new HashMap<>(); inherited.put("oci_cli_auto_prompt","False");
            check(OciClient.commandEnvironment(inherited,null).isEmpty(),"Windows interactive environment lookup is case insensitive");
        }
        Path config=Configuration.dataDirectory().resolve("oci-config"); Files.write(config,Arrays.asList("[DEFAULT]","tenancy=ocid1.tenancy.test","[CUSTOM]","region=eu-frankfurt-1"),StandardCharsets.UTF_8);
        check(OciClient.profiles(config.toString()).size()==2,"OCI profile parsing");
        Map<String,Object> s=Configuration.defaults(); s.put("configPath",config.toString()); s.put("profile","CUSTOM"); s.put("bastionID","bastion-id");
        List<List<String>> calls=new ArrayList<>();
        final String[] created={"2026-10-21T14:26:24.473000+00:00"};
        OciClient client=new OciClient(s,new ProxySupport.Endpoint("proxy.example",3128,"test"),(a,env,timeout) -> {
            calls.add(new ArrayList<>(a)); check(a.contains("json") && a.contains("CUSTOM"),"explicit OCI JSON and profile");
            check(!env.containsKey("OCI_CLI_AUTO_PROMPT") && !a.contains("--cli-auto-prompt") && !a.contains("-i"),"all OCI calls avoid interactive mode");
            check("http://proxy.example:3128".equals(env.get("https_proxy")) && !env.containsKey("HTTPS_PROXY"),"OCI proxy environment");
            if(a.contains("compartment")) return "{\"data\":[{\"id\":\"child\",\"name\":\"Child\",\"lifecycle-state\":\"ACTIVE\"},{\"id\":\"deleted\",\"lifecycle-state\":\"DELETED\"}],\"opc-next-page\":\"page-2\"}";
            if(a.contains("create-session-create-dynamic-port-forwarding-session-target-resource-details")) return "{\"data\":{}}";
            if(a.contains("list") && a.contains("session")) {
                String name=calls.stream().filter(c -> c.contains("--display-name")).findFirst().get().get(calls.stream().filter(c -> c.contains("--display-name")).findFirst().get().indexOf("--display-name")+1);
                return "{\"data\":[{\"id\":\"session-id\",\"display-name\":"+Json.write(name)+",\"lifecycle-state\":\"ACTIVE\"}]}";
            }
            if(a.contains("get")) return "{\"data\":{\"id\":\"session-id\",\"bastion-id\":\"bastion-id\",\"lifecycle-state\":\"ACTIVE\",\"time-created\":"+Json.write(created[0])+",\"session-ttl-in-seconds\":1800,\"ssh-metadata\":{\"command\":\"ssh -N -D 2222 session-id@host.example\"}}}";
            return "{\"data\":[]}";
        });
        OciClient.Page page=client.compartments("next-token");
        check(page.tenancy.equals("ocid1.tenancy.test") && page.records.size()==1 && page.next.equals("page-2"),"compartment filtering, default inheritance and pagination");
        check(calls.get(0).contains("next-token") && calls.get(0).contains("100"),"compartment request page and limit");
        OciClient.Session session=client.createSession("ssh-ed25519 AAA test");
        check(session.id.equals("session-id") && session.target.equals("session-id@host.example"),"created session and SSH metadata");
        check(session.expires.toString().equals("2026-10-21T14:56:24.473Z"),"session expiry from real OCI offset timestamp");
        check(calls.stream().anyMatch(a -> a.contains("SUCCEEDED") && a.contains("PUB") && a.contains("10800")),"dynamic session creation arguments");
        s.put("bastionID","different"); check(client.getSession("session-id")==null,"owned session belongs to requested bastion");
        s.put("bastionID","bastion-id"); created[0]="invalid-time";
        try { client.getSession("session-id"); throw new AssertionError("Malformed OCI timestamp accepted"); }
        catch(OciClient.SessionDataException e) { check(TunnelManager.requiresIntervention(e),"malformed owned session response requires intervention"); }
    }
    private static List<String> child(String java,String mode) {
        return Arrays.asList(java,"-cp",System.getProperty("java.class.path"),"ocitools.TestSuite","--child",mode);
    }
    private static void processes(String java) throws Exception {
        List<String> echo=new ArrayList<>(child(java,"echo"));
        List<String> tricky=Arrays.asList("simple","path with space","ProxyCommand=\"C:/Program Files/Java/bin/java.exe\" -cp \"C:/a b\" %h %p","{\"publicKeyContent\":\"ssh-ed25519 AAA comment\"}","quotes\"and\\slashes\\","\"wrapped\"");
        echo.addAll(tricky); check(Json.parse(Platform.run(echo,null,10).success()).equals(tricky),"native argument round trip including embedded quotes and JSON");
        if(Platform.windows()) {
            Path capture=Configuration.dataDirectory().resolve("powershell-copy.txt");
            String script=Platform.displayCommand(echo)+" -RedirectStandardOutput "+Platform.quote(capture.toString(),true);
            Platform.run(Arrays.asList("powershell.exe","-NoProfile","-EncodedCommand",Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE))),null,15).success();
            check(Json.parse(new String(Files.readAllBytes(capture),StandardCharsets.UTF_8).trim()).equals(tricky),"copied PowerShell command preserves native arguments");
        }
        Platform.Result result=Platform.run(child(java,"output"),null,15);
        check(result.status==0 && result.out.contains("stdout-19999") && result.err.contains("stderr-19999"),"drains stdout/stderr concurrently");
        long start=System.nanoTime(); rejects(() -> Platform.run(child(java,"sleep"),null,1),"process timeout");
        check(TimeUnit.NANOSECONDS.toSeconds(System.nanoTime()-start)<8,"timeout terminates child promptly");
        FutureTask<Boolean> cancel=new FutureTask<>(() -> { try { Platform.run(child(java,"sleep"),null,60); return false; } catch(InterruptedException e) { return true; } });
        Thread thread=new Thread(cancel,"cancellation-test"); thread.start(); Thread.sleep(400); thread.interrupt();
        check(cancel.get(8,TimeUnit.SECONDS),"process wait is cancellable");
        Map<String,String> inherited=new HashMap<>(System.getenv()); inherited.put("OCI_TOOLS_TEST","1");
        check(Platform.run(child(java,"output"),inherited,15).status==0,"explicit process environment");
    }
    private static void ports() throws Exception {
        int port; try(ServerSocket s=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) { port=s.getLocalPort(); rejects(() -> TunnelManager.freePort(port),"occupied port leaves listener intact"); check(!s.isClosed(),"unrelated listener survives"); }
        TunnelManager.freePort(port); check(true,"free port accepted");
    }
    private static void nativeSshProxy(String java) throws Exception {
        System.clearProperty("ocitools.ssh");
        String actual;
        try { actual=Platform.executable("ssh"); } catch(IOException e) { System.out.println("Native OpenSSH integration skipped: ssh not installed"); System.setProperty("ocitools.ssh",java); return; }
        Map<String,Object> legacy=obj("{\"host\":\"192.0.2.10\",\"user\":\"opc\",\"privateKeyPath\":\"test-key\",\"allowLegacyRsaHostKey\":true}");
        List<String> effective=Ssh.destination(legacy,2222,false);
        effective.addAll(1,Arrays.asList("-G","-F",Platform.windows()?"NUL":"/dev/null"));
        String config=Platform.run(effective,null,10).success();
        check(Pattern.compile("(?m)^hostkeyalgorithms [^\\r\\n]*[, ]ssh-rsa(?:,|\\r?$)").matcher(config).find(),"native OpenSSH accepts appended legacy RSA host algorithm without connecting");
        legacy.put("allowLegacyRsaUserSignature",true);
        effective=Ssh.destination(legacy,2222,false); effective.addAll(1,Arrays.asList("-G","-F",Platform.windows()?"NUL":"/dev/null"));
        String userConfig=Platform.run(effective,null,10).success();
        check(Pattern.compile("(?m)^pubkeyaccepted(?:algorithms|keytypes) [^\\r\\n]*[, ]ssh-rsa(?:,|\\r?$)").matcher(userConfig).find(),"native OpenSSH accepts legacy RSA user-signature option alias without connecting");
        try(ServerSocket server=new ServerSocket(0,1,InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(10000);
            FutureTask<Void> peer=new FutureTask<>(() -> {
                try(Socket s=server.accept()) {
                    s.setSoTimeout(10000); InputStream in=s.getInputStream(); OutputStream out=s.getOutputStream();
                    ByteArrayOutputStream request=new ByteArrayOutputStream(); int c;
                    while((c=in.read())!=-1) { request.write(c); if(request.toString("US-ASCII").endsWith("\r\n\r\n")) break; }
                    if(!request.toString("US-ASCII").startsWith("CONNECT bastion.example:22 HTTP/1.1")) throw new IOException("Native SSH proxy CONNECT missing");
                    out.write("HTTP/1.1 200 Connected\r\n\r\nSSH-2.0-OCIBastion_Java-Test\r\n".getBytes(StandardCharsets.US_ASCII)); out.flush();
                    ByteArrayOutputStream banner=new ByteArrayOutputStream();
                    while((c=in.read())!=-1 && c!='\n') { banner.write(c); if(banner.size()>512) throw new IOException("Unexpected SSH banner"); }
                    if(!banner.toString("US-ASCII").startsWith("SSH-2.0-")) throw new IOException("Native SSH bytes did not pass through Java helper");
                }
                return null;
            }); Thread thread=new Thread(peer,"native-ssh-proxy"); thread.setDaemon(true); thread.start();
            Map<String,Object> s=Configuration.defaults(); s.put("privateKeyPath",project.resolve("build/nonexistent-test-key").toString());
            List<String> command=Ssh.bastion("test-session@bastion.example",s,new ProxySupport.Endpoint("127.0.0.1",server.getLocalPort(),"test"),"test-token");
            command.addAll(1,Arrays.asList("-v","-F",Platform.windows()?"NUL":"/dev/null","-o","ConnectTimeout=8","-o","UserKnownHostsFile="+project.resolve("build/test-known-hosts")));
            Platform.Result result=Platform.run(command,null,15);
            try { peer.get(12,TimeUnit.SECONDS); }
            catch(Exception e) { throw new AssertionError("Native SSH ProxyCommand failed: "+result.err,e); }
            check(result.status!=0,"native OpenSSH reached mock target and reported its deliberate disconnect");
        } finally { System.setProperty("ocitools.ssh",java); }
    }
}
