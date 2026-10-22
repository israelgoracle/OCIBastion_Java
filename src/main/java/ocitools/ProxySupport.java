package ocitools;

import java.io.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;

public final class ProxySupport {
    private ProxySupport() { }
    public static final class Endpoint {
        public final String host; public final int port; public final String source;
        public Endpoint(String h,int p,String source) { host=Platform.token(h,"Proxy host"); port=Platform.port(p); this.source=source; }
        public String address() { return (host.contains(":") ? "[" + host + "]" : host) + ":" + port; }
        public String toString() { return source + ": " + address(); }
    }
    public static Endpoint fromUrl(String value,String source) {
        try {
            URI u = URI.create(value.contains("://") ? value : "http://" + value);
            if (!"http".equalsIgnoreCase(u.getScheme()) || u.getUserInfo() != null || u.getHost() == null) return null;
            return new Endpoint(u.getHost().replace("[","").replace("]",""),u.getPort() == -1 ? 80 : u.getPort(),source);
        } catch (IllegalArgumentException e) { return null; }
    }
    public static Endpoint fromPac(String pac) {
        // Deliberately matches the legacy static PROXY/proxies convention; never executes downloaded JavaScript.
        Matcher line = Pattern.compile("(?im)^.*proxies\\s*=.*$").matcher(pac);
        if (line.find()) {
            Matcher m = Pattern.compile("([A-Za-z0-9._-]+):(\\d{1,5})").matcher(line.group());
            if (m.find()) return new Endpoint(m.group(1),Integer.parseInt(m.group(2)),"WPAD static proxy");
        }
        Matcher m = Pattern.compile("(?i)\\bPROXY\\s+([A-Za-z0-9._-]+):(\\d{1,5})").matcher(pac);
        return m.find() ? new Endpoint(m.group(1),Integer.parseInt(m.group(2)),"WPAD static proxy") : null;
    }
    public static Endpoint resolve(Map<String,Object> s) throws Exception {
        String mode = Json.string(s,"proxyMode",Json.string(s,"proxyHost","").isEmpty() ? "automatic" : "manual");
        if (mode.equals("direct")) return null;
        if (mode.equals("manual")) return new Endpoint(Json.string(s,"proxyHost",""),Json.integer(s,"proxyPort",0),"Manual");
        for (String key : Arrays.asList("http_proxy","HTTP_PROXY","https_proxy","HTTPS_PROXY")) {
            String value = System.getenv(key);
            if (value != null && !value.trim().isEmpty()) {
                Endpoint p = fromUrl(value,key);
                if (p == null) throw new IOException("Unsupported proxy in " + key + "; choose manual HTTP proxy or direct mode");
                return p;
            }
        }
        ProxySelector selector = ProxySelector.getDefault();
        if (selector != null) for (Proxy p : selector.select(URI.create("https://iaas.oraclecloud.com"))) {
            if (p.type() == Proxy.Type.HTTP && p.address() instanceof InetSocketAddress) {
                InetSocketAddress a = (InetSocketAddress)p.address(); return new Endpoint(a.getHostString(),a.getPort(),"System proxy");
            }
        }
        HttpURLConnection c = null;
        try {
            c = (HttpURLConnection)new URL("http://wpad/wpad.dat").openConnection(Proxy.NO_PROXY);
            c.setConnectTimeout(2000); c.setReadTimeout(2000); c.setInstanceFollowRedirects(false);
            if (c.getResponseCode() != 200) return null;
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            try (InputStream in = c.getInputStream()) {
                byte[] buffer = new byte[4096]; int n;
                while ((n=in.read(buffer)) != -1) { if (b.size()+n > 1024*1024) throw new IOException("PAC file too large"); b.write(buffer,0,n); }
            }
            return fromPac(new String(b.toByteArray(),StandardCharsets.UTF_8));
        } catch (IOException | IllegalArgumentException e) { return null; }
        finally { if (c != null) c.disconnect(); }
    }
    /** OpenSSH ProxyCommand transport; stdout contains only raw tunnel bytes. */
    public static void relay(String[] args) throws Exception {
        if (args.length != 6) throw new IllegalArgumentException("--proxy http|socks proxyHost proxyPort targetHost targetPort");
        String mode=args[1], host=Platform.token(args[2],"Proxy host"), target=Platform.token(args[4],"Target host");
        int port=Platform.port(Integer.parseInt(args[3])), targetPort=Platform.port(Integer.parseInt(args[5]));
        try (Socket socket = connect(mode,host,port,target,targetPort)) {
            Thread upload = new Thread(() -> {
                try { pump(System.in,socket.getOutputStream()); socket.shutdownOutput(); }
                catch (IOException e) { try { socket.close(); } catch (IOException ignored) { } }
            },"proxy-upload"); upload.setDaemon(true); upload.start();
            pump(socket.getInputStream(),System.out);
        }
    }
    static Socket connect(String mode,String proxyHost,int proxyPort,String target,int targetPort) throws IOException {
        Socket socket = new Socket();
        try {
            socket.connect(new InetSocketAddress(proxyHost,proxyPort),15000); socket.setSoTimeout(15000);
            InputStream in=socket.getInputStream(); OutputStream out=socket.getOutputStream();
            if (mode.equals("http")) {
                String authority=(target.contains(":") ? "["+target+"]" : target)+":"+targetPort;
                out.write(("CONNECT "+authority+" HTTP/1.1\r\nHost: "+authority+"\r\n\r\n").getBytes(StandardCharsets.US_ASCII)); out.flush();
                ByteArrayOutputStream header = new ByteArrayOutputStream(); int c;
                while ((c=in.read()) != -1) {
                    header.write(c);
                    if (header.size()>16384) throw new IOException("HTTP proxy header too large");
                    byte[] bytes=header.toByteArray(); int n=bytes.length;
                    if (n>=4 && bytes[n-4]==13 && bytes[n-3]==10 && bytes[n-2]==13 && bytes[n-1]==10) break;
                }
                String response=new String(header.toByteArray(),StandardCharsets.US_ASCII);
                if (!response.matches("(?s)^HTTP/1\\.[01] 200(?: |\\r).*\\r\\n\\r\\n$")) throw new IOException("HTTP CONNECT rejected: "+response.split("\r\n")[0]);
            } else if (mode.equals("socks")) {
                out.write(new byte[]{5,1,0}); out.flush();
                if (read(in)!=5 || read(in)!=0) throw new IOException("SOCKS5 authentication method rejected");
                byte[] name=target.getBytes(StandardCharsets.US_ASCII);
                if (name.length>255) throw new IOException("SOCKS target name too long");
                out.write(new byte[]{5,1,0,3,(byte)name.length}); out.write(name); out.write(targetPort>>8); out.write(targetPort&255); out.flush();
                if (read(in)!=5 || read(in)!=0 || read(in)!=0) throw new IOException("SOCKS5 CONNECT rejected");
                int type=read(in), count=type==1 ? 4 : type==4 ? 16 : type==3 ? read(in) : -1;
                if (count<0) throw new IOException("Invalid SOCKS5 address type");
                for (int i=0;i<count+2;i++) read(in);
            } else throw new IOException("Unknown proxy mode: "+mode);
            socket.setSoTimeout(0); return socket;
        } catch (IOException | RuntimeException e) { socket.close(); throw e; }
    }
    private static int read(InputStream in) throws IOException { int c=in.read(); if(c<0) throw new EOFException("Proxy closed during handshake"); return c; }
    static void pump(InputStream in,OutputStream out) throws IOException {
        byte[] b=new byte[32768]; int n;
        while((n=in.read(b))!=-1) { out.write(b,0,n); out.flush(); if(out instanceof PrintStream && ((PrintStream)out).checkError()) throw new IOException("Proxy output pipe closed"); }
    }
}
