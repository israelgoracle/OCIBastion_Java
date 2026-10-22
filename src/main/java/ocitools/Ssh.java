package ocitools;

import java.io.*;
import java.util.*;

public final class Ssh {
    private Ssh() { }
    static String forwardingHost(String host) { return host.contains(":") ? "["+host+"]" : host; }
    static String proxyCommand(String mode,String host,int port) {
        List<String> helper=Platform.javaCommand(); helper.addAll(Arrays.asList("--proxy",mode,host,Integer.toString(port),"%h","%p"));
        // OpenSSH runs this through its own platform shell, not PowerShell.
        StringJoiner j=new StringJoiner(" ");
        for (String s:helper) {
            if (s.equals("%h") || s.equals("%p")) j.add(s);
            else if (Platform.windows()) {
                if (s.contains("%") || s.contains("\"") || s.contains("\n") || s.contains("\r")) throw new IllegalArgumentException("Proxy helper path contains unsupported shell characters");
                j.add("\""+s.replace('\\','/')+"\"");
            } else j.add(Platform.quote(s,false));
        }
        return "ProxyCommand="+j;
    }
    static List<String> base(String key) throws IOException {
        String p=Platform.path(Platform.line(key,"SSH private key")).toString();
        List<String> a=new ArrayList<>(Arrays.asList(Platform.executable("ssh"),"-i",p,"-o","IdentitiesOnly=yes",
            "-o","ServerAliveInterval=30","-o","ServerAliveCountMax=3")); return a;
    }
    public static List<String> destination(Map<String,Object> d,int socksPort,boolean tunnel) throws IOException {
        String host=Platform.token(Json.string(d,"host",""),"SSH host"), user=Platform.token(Json.string(d,"user","opc"),"SSH user");
        int port=Platform.port(Json.integer(d,tunnel ? "sshPort" : "port",22)); Platform.port(socksPort);
        List<String> a=base(Json.string(d,"privateKeyPath",""));
        if (Json.bool(d,"allowLegacyRsaHostKey",false)) a.addAll(Arrays.asList("-o","HostKeyAlgorithms=+ssh-rsa"));
        // The older name remains an alias in current OpenSSH and supports our OpenSSH 7.8 baseline.
        if (Json.bool(d,"allowLegacyRsaUserSignature",false)) a.addAll(Arrays.asList("-o","PubkeyAcceptedKeyTypes=+ssh-rsa"));
        a.addAll(Arrays.asList("-o",proxyCommand("socks","127.0.0.1",socksPort)));
        if (tunnel) {
            String remote=Platform.token(Json.string(d,"remoteHost","127.0.0.1"),"Forward destination");
            int local=Platform.port(Json.integer(d,"localPort",0)), remotePort=Platform.port(Json.integer(d,"remotePort",0));
            a.addAll(Arrays.asList("-o","ExitOnForwardFailure=yes","-N","-L","127.0.0.1:"+local+":"+forwardingHost(remote)+":"+remotePort));
        }
        a.addAll(Arrays.asList("-p",Integer.toString(port),user+"@"+host)); return a;
    }
    public static List<String> bastion(String target,Map<String,Object> s,ProxySupport.Endpoint proxy,String token) throws IOException {
        String[] parts=target.split("@",-1); if(parts.length!=2) throw new IllegalArgumentException("Invalid Bastion SSH target");
        Platform.token(parts[0],"Session user"); Platform.token(parts[1],"Bastion host");
        List<String> a=base(Json.string(s,"privateKeyPath",""));
        a.addAll(Arrays.asList("-N","-D","127.0.0.1:"+Platform.port(Json.integer(s,"socksPort",2222)),"-p","22",
            "-o","BatchMode=yes","-o","ExitOnForwardFailure=yes","-o","StrictHostKeyChecking=accept-new"));
        if(token!=null) a.addAll(Arrays.asList("-o","SetEnv=OCIBASTION_TUNNEL_ID="+token));
        if(proxy!=null) a.addAll(Arrays.asList("-o",proxyCommand("http",proxy.host,proxy.port)));
        a.add(target); return a;
    }
    public static String fingerprint(String path) throws Exception {
        if(!java.nio.file.Files.isRegularFile(Platform.path(path))) throw new IOException("Key does not exist: "+path);
        String result=Platform.run(Arrays.asList(Platform.executable("ssh-keygen"),"-l","-E","sha256","-f",Platform.path(path).toString()),null,20).success();
        String[] parts=result.trim().split("\\s+"); if(parts.length<2 || !parts[1].startsWith("SHA256:")) throw new IOException("Cannot read SSH key fingerprint"); return parts[1];
    }
}
