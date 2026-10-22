package ocitools;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;

public final class Platform {
    private Platform() { }
    public static boolean windows() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).startsWith("windows"); }
    public static boolean mac() { return System.getProperty("os.name").toLowerCase(Locale.ROOT).contains("mac"); }
    public static Path path(String value) {
        if (value.equals("~")) return Paths.get(System.getProperty("user.home"));
        if (value.startsWith("~/") || value.startsWith("~\\")) value = System.getProperty("user.home") + value.substring(1);
        return Paths.get(value).toAbsolutePath().normalize();
    }
    public static String line(String v, String name) {
        String s = v.trim();
        if (s.isEmpty() || s.indexOf('\n') >= 0 || s.indexOf('\r') >= 0 || s.indexOf('\0') >= 0) throw new IllegalArgumentException(name + " is empty or contains control characters");
        return s;
    }
    public static String token(String v, String name) {
        String s = line(v,name);
        if (s.startsWith("-") || !s.matches("[A-Za-z0-9._:-]+")) throw new IllegalArgumentException(name + " contains unsupported characters");
        return s;
    }
    public static int port(int p) { if (p < 1 || p > 65535) throw new IllegalArgumentException("Port must be between 1 and 65535"); return p; }
    public static String executable(String name) throws IOException {
        String override = System.getProperty("ocitools." + name);
        if (override != null) {
            Path p = path(override); if (Files.isRegularFile(p)) return p.toString();
            throw new IOException("Executable not found: " + p);
        }
        Map<String,String> environment = System.getenv();
        List<String> dirs = new ArrayList<>(Arrays.asList(environmentValue(environment,"PATH", "").split(java.util.regex.Pattern.quote(File.pathSeparator))));
        if (windows()) {
            dirs.add(environmentValue(environment,"WINDIR", "C:\\Windows") + "\\System32\\OpenSSH");
            if (name.equals("oci")) {
                dirs.add(Paths.get(environmentValue(environment,"ProgramFiles(x86)","C:\\Program Files (x86)"),"Oracle","oci_cli").toString());
                dirs.add(Paths.get(environmentValue(environment,"ProgramFiles","C:\\Program Files"),"Oracle","oci_cli").toString());
            }
        }
        else dirs.addAll(Arrays.asList("/opt/homebrew/bin","/usr/local/bin","/usr/bin","/bin"));
        String found = findExecutable(name,dirs);
        if (found != null) return found;
        throw new IOException("Cannot find " + name + " in this application's PATH or standard install locations. Restart the app after changing PATH, or set -Docitools." + name + "=/absolute/path/to/executable");
    }
    static String environmentValue(Map<String,String> environment,String name,String fallback) {
        if (environment.containsKey(name)) return environment.get(name);
        if (windows()) for (Map.Entry<String,String> entry : environment.entrySet()) {
            if (entry.getKey().equalsIgnoreCase(name)) return entry.getValue();
        }
        return fallback;
    }
    static String findExecutable(String name,List<String> dirs) {
        // Prefer real executables: launching arbitrary .cmd wrappers would require cmd.exe quoting.
        for (String dir : dirs) {
            String clean = dir.trim();
            if (clean.startsWith("\"") && clean.endsWith("\"") && clean.length() >= 2) clean = clean.substring(1,clean.length()-1);
            if (clean.isEmpty()) continue;
            for (String suffix : windows() ? new String[]{".exe",""} : new String[]{""}) {
                try {
                    Path p = Paths.get(clean,name + suffix);
                    if (Files.isRegularFile(p) && (windows() || Files.isExecutable(p))) return p.toAbsolutePath().toString();
                } catch (InvalidPathException ignored) { /* Ignore a malformed entry and continue through PATH. */ }
            }
        }
        return null;
    }
    public static List<String> javaCommand() {
        List<String> a = new ArrayList<>();
        a.add(Paths.get(System.getProperty("java.home"),"bin",windows() ? "java.exe" : "java").toString());
        a.add("-cp");
        StringJoiner cp = new StringJoiner(File.pathSeparator);
        for (String entry : System.getProperty("java.class.path").split(java.util.regex.Pattern.quote(File.pathSeparator))) cp.add(path(entry).toString());
        a.add(cp.toString()); a.add("ocitools.Main"); return a;
    }
    public static String quote(String s, boolean windowsShell) {
        return windowsShell ? "'" + s.replace("'","''") + "'" : "'" + s.replace("'","'\\''") + "'";
    }
    public static String displayCommand(List<String> a) {
        if(windows()) {
            StringJoiner nativeArgs=new StringJoiner(" ");
            for(int i=1;i<a.size();i++) nativeArgs.add(windowsArgument(a.get(i)));
            // Start-Process preserves native quotes in both Windows PowerShell 5.1 and PowerShell 7.
            return "Start-Process -NoNewWindow -Wait -FilePath "+quote(a.get(0),true)+" -ArgumentList "+quote(nativeArgs.toString(),true);
        }
        StringJoiner j = new StringJoiner(" "); for (String s : a) j.add(quote(s,false)); return j.toString();
    }
    static String windowsArgument(String s) {
        StringBuilder b=new StringBuilder("\""); int slashes=0;
        for(char c:s.toCharArray()) {
            if(c=='\\') { slashes++; continue; }
            for(int n=0;n<slashes*(c=='"'?2:1)+(c=='"'?1:0);n++) b.append('\\');
            slashes=0; b.append(c);
        }
        for(int n=0;n<slashes*2;n++) b.append('\\');
        return b.append('"').toString();
    }
    /** Java 8's Windows ProcessBuilder does not escape embedded argument quotes for CRT programs. */
    static ProcessBuilder builder(List<String> command) {
        if(!windows() || "false".equalsIgnoreCase(System.getProperty("jdk.lang.Process.allowAmbiguousCommands"))) return new ProcessBuilder(command);
        List<String> encoded=new ArrayList<>(command);
        for(int i=1;i<encoded.size();i++) {
            String s=encoded.get(i); if(s.indexOf('"')<0) continue;
            encoded.set(i,windowsArgument(s));
        }
        return new ProcessBuilder(encoded);
    }
    public static void terminal(List<String> command, String terminalApp) throws Exception {
        List<String> runner = javaCommand(); runner.add("--terminal");
        runner.add(Base64.getEncoder().encodeToString(Json.write(command).getBytes(StandardCharsets.UTF_8)));
        if (windows()) {
            String script = displayCommand(runner);
            new ProcessBuilder("powershell.exe","-NoProfile","-Command","Start-Process powershell.exe -ArgumentList '-NoProfile','-NoExit','-EncodedCommand','" +
                Base64.getEncoder().encodeToString(script.getBytes(StandardCharsets.UTF_16LE)) + "'").start();
        } else if (mac()) {
            String script = "iterm".equals(terminalApp) ?
                "on run argv\ntell application \"iTerm\"\nactivate\nset w to (create window with default profile)\ntell current session of w to write text (item 1 of argv)\nend tell\nend run" :
                "on run argv\ntell application \"Terminal\"\nactivate\ndo script (item 1 of argv)\nend tell\nend run";
            run(Arrays.asList("/usr/bin/osascript","-e",script,displayCommand(runner)),null,20);
        } else {
            IOException missing = null;
            for (String terminal : Arrays.asList("x-terminal-emulator","gnome-terminal","konsole","xfce4-terminal","xterm")) {
                try {
                    List<String> a = new ArrayList<>(); a.add(executable(terminal));
                    if (terminal.equals("gnome-terminal")) a.add("--");
                    else if (terminal.equals("xfce4-terminal")) a.add("-x");
                    else a.add("-e");
                    a.addAll(runner); new ProcessBuilder(a).start(); return;
                } catch (IOException e) { missing = e; }
            }
            throw new IOException("No supported terminal found; install xterm or copy the SSH command",missing);
        }
    }
    public static final class Result {
        public final int status; public final String out,err;
        Result(int status,String out,String err) { this.status=status; this.out=out; this.err=err; }
        public String success() throws IOException {
            if (status != 0) throw new IOException("Command exited " + status + ": " + err.trim() + (out.isEmpty() ? "" : "\n" + out.trim()));
            return out;
        }
    }
    public static Result run(List<String> a, Map<String,String> env, int timeoutSeconds) throws Exception {
        ProcessBuilder b = builder(a);
        if (env != null) { b.environment().clear(); b.environment().putAll(env); }
        Process p = b.start(); p.getOutputStream().close();
        FutureTask<String> out = collect(p.getInputStream()), err = collect(p.getErrorStream());
        try {
            if (!p.waitFor(timeoutSeconds,TimeUnit.SECONDS)) throw new IOException("Command timed out after " + timeoutSeconds + " seconds");
            return new Result(p.exitValue(),out.get(),err.get());
        } finally { if (p.isAlive()) stop(p); }
    }
    static FutureTask<String> collect(InputStream stream) {
        FutureTask<String> result = new FutureTask<>(() -> {
            ByteArrayOutputStream b = new ByteArrayOutputStream(); byte[] buf = new byte[8192]; int n;
            try (InputStream in = stream) {
                while ((n = in.read(buf)) != -1) { if (b.size() + n > 10 * 1024 * 1024) throw new IOException("Command output exceeds 10 MiB"); b.write(buf,0,n); }
            }
            return new String(b.toByteArray(),StandardCharsets.UTF_8);
        });
        Thread t = new Thread(result,"process-output"); t.setDaemon(true); t.start(); return result;
    }
    public static void stop(Process p) throws InterruptedException, IOException {
        p.destroy(); if (!p.waitFor(5,TimeUnit.SECONDS)) { p.destroyForcibly(); if (!p.waitFor(5,TimeUnit.SECONDS)) throw new IOException("SSH process did not stop"); }
    }
}
