package ocitools;

import java.io.*;
import java.lang.reflect.*;
import java.nio.file.*;
import java.util.*;

/** Java 9+ ProcessHandle used reflectively so the app can also run on Java 8. */
final class OwnedProcess {
    private static final Path FILE=Configuration.dataDirectory().resolve("owned-tunnel.json");
    private OwnedProcess() { }
    static void record(Process process,String token,int port,String target) throws Exception {
        try {
            long pid=((Number)Process.class.getMethod("pid").invoke(process)).longValue();
            Class<?> handle=Class.forName("java.lang.ProcessHandle"), info=Class.forName("java.lang.ProcessHandle$Info");
            Object h=((Optional<?>)handle.getMethod("of",long.class).invoke(null,pid)).get();
            Object metadata=handle.getMethod("info").invoke(h);
            Optional<?> started=(Optional<?>)info.getMethod("startInstant").invoke(metadata);
            if(!started.isPresent()) return;
            Map<String,Object> m=new LinkedHashMap<>(); m.put("pid",pid); m.put("token",token); m.put("port",port); m.put("target",target); m.put("started",started.get().toString());
            Configuration.write(FILE,m);
        } catch (ClassNotFoundException | NoSuchMethodException e) { /* Java 8: only current Process objects can be stopped. */ }
    }
    static void clear(String token) throws IOException {
        if(Files.exists(FILE) && token.equals(Configuration.read(FILE).get("token"))) Files.deleteIfExists(FILE);
    }
    static void reclaim() throws Exception {
        if(!Files.exists(FILE)) return;
        Map<String,Object> m=Configuration.read(FILE);
        try {
            Class<?> handle=Class.forName("java.lang.ProcessHandle"), info=Class.forName("java.lang.ProcessHandle$Info");
            Object raw=m.get("pid"); if(!(raw instanceof Number)) return;
            Optional<?> found=(Optional<?>)handle.getMethod("of",long.class).invoke(null,((Number)raw).longValue());
            if(!found.isPresent()) { Files.deleteIfExists(FILE); return; }
            Object h=found.get(), metadata=handle.getMethod("info").invoke(h);
            Optional<?> started=(Optional<?>)info.getMethod("startInstant").invoke(metadata);
            Optional<?> executable=(Optional<?>)info.getMethod("command").invoke(metadata);
            Optional<?> args=(Optional<?>)info.getMethod("arguments").invoke(metadata);
            if(!started.isPresent() || !started.get().toString().equals(m.get("started")) || !executable.isPresent() || !args.isPresent()) return;
            String name=Paths.get(executable.get().toString()).getFileName().toString();
            List<String> a=Arrays.asList((String[])args.get());
            if(!name.equalsIgnoreCase(Platform.windows()?"ssh.exe":"ssh") ||
                !a.contains("SetEnv=OCIBASTION_TUNNEL_ID="+m.get("token")) || !a.contains("127.0.0.1:"+m.get("port")) || !a.contains(m.get("target"))) return;
            handle.getMethod("destroy").invoke(h);
            for(int i=0;i<50;i++) {
                if(!(Boolean)handle.getMethod("isAlive").invoke(h)) { Files.deleteIfExists(FILE); return; }
                Thread.sleep(100);
            }
            throw new IOException("Previous owned SSH process did not stop; release its port before reconnecting");
        } catch(ClassNotFoundException e) { /* Fail closed on Java 8: port check reports the listener. */ }
    }
}
