package ocitools;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.nio.file.attribute.*;
import java.text.Normalizer;
import java.util.*;

/** Keeps the Swift archive field names and UUIDs for bidirectional migration. */
public final class Configuration {
    private Configuration() { }
    public static Map<String,Object> defaults() {
        Map<String,Object> m = new LinkedHashMap<>();
        for (String key : Arrays.asList("configPath","compartmentID","bastionID","publicKeyPath","privateKeyPath","proxyHost")) m.put(key, "");
        m.put("configPath", Paths.get(System.getProperty("user.home"),".oci","config").toString());
        m.put("profile", "DEFAULT"); m.put("socksPort", 2222); m.put("ttlSeconds",10800);
        m.put("proxyPort",0); m.put("proxyMode","automatic"); m.put("terminalApp","terminal");
        m.put("destinations",new ArrayList<>()); m.put("tunnels",new ArrayList<>());
        return m;
    }
    public static Path dataDirectory() {
        String override = System.getProperty("ocitools.home");
        if (override != null) return Paths.get(override);
        String home = System.getProperty("user.home");
        if (Platform.windows()) return Paths.get(System.getenv().getOrDefault("APPDATA",home),"OCIBastion_Java");
        if (Platform.mac()) return Paths.get(home,"Library","Application Support","OCIBastion_Java");
        return Paths.get(System.getenv().getOrDefault("XDG_CONFIG_HOME",Paths.get(home,".config").toString()),"OCIBastion_Java");
    }
    public static Map<String,Object> load() throws IOException {
        Path file = dataDirectory().resolve("settings.json");
        if (!Files.exists(file)) return defaults();
        Map<String,Object> loaded = read(file), m = defaults(); m.putAll(loaded);
        if (loaded.get("proxyMode") == null) m.put("proxyMode",Json.string(m,"proxyHost","").isEmpty()?"automatic":"manual");
        validateSnapshot(m); saved(m); return m;
    }
    public static Map<String,Object> read(Path file) throws IOException {
        if (Files.size(file) > 10 * 1024 * 1024) throw new IOException("Configuration exceeds 10 MiB");
        String content = new String(Files.readAllBytes(file),StandardCharsets.UTF_8);
        if (content.startsWith("\ufeff")) content = content.substring(1);
        return Json.object(Json.parse(content));
    }
    public static void save(Map<String,Object> m) throws IOException { write(dataDirectory().resolve("settings.json"),m); }
    public static void write(Path file, Map<String,Object> m) throws IOException {
        Path target = file.toAbsolutePath(); Files.createDirectories(target.getParent());
        Path temp = Files.createTempFile(target.getParent(),".ocitools-", ".tmp");
        try {
            restrict(temp);
            Files.write(temp,Json.write(m).getBytes(StandardCharsets.UTF_8));
            try { Files.move(temp,target,StandardCopyOption.ATOMIC_MOVE,StandardCopyOption.REPLACE_EXISTING); }
            catch (AtomicMoveNotSupportedException e) { Files.move(temp,target,StandardCopyOption.REPLACE_EXISTING); }
        } finally { Files.deleteIfExists(temp); }
    }
    static void restrict(Path file) throws IOException {
        PosixFileAttributeView posix = Files.getFileAttributeView(file,PosixFileAttributeView.class);
        if (posix != null) { posix.setPermissions(PosixFilePermissions.fromString("rw-------")); return; }
        AclFileAttributeView acl = Files.getFileAttributeView(file,AclFileAttributeView.class);
        if (acl != null) {
            UserPrincipal owner = Files.getOwner(file);
            acl.setAcl(Collections.singletonList(AclEntry.newBuilder().setType(AclEntryType.ALLOW).setPrincipal(owner)
                .setPermissions(EnumSet.allOf(AclEntryPermission.class)).build()));
        }
    }
    public static Map<String,Object> snapshot(Map<String,Object> settings) {
        Map<String,Object> m = defaults();
        for (String key : new ArrayList<>(m.keySet())) if (settings.containsKey(key)) m.put(key,settings.get(key));
        if (settings.get("proxyMode") == null) m.put("proxyMode",Json.string(m,"proxyHost","").isEmpty()?"automatic":"manual");
        return Json.copy(m);
    }
    public static void apply(Map<String,Object> settings, Map<String,Object> snapshot) {
        for (String key : Arrays.asList("configPath","profile","bastionID","publicKeyPath","privateKeyPath")) {
            if (!Objects.equals(settings.get(key),snapshot.get(key))) {
                settings.remove("ownedSessionID"); settings.remove("ownedSessionKeyFingerprint"); break;
            }
        }
        settings.putAll(snapshot(snapshot));
    }
    public static List<Object> saved(Map<String,Object> m) {
        if (m.get("savedConnections") == null) m.put("savedConnections",new ArrayList<>());
        return Json.array(m.get("savedConnections"));
    }
    public static String normalized(String name) {
        return Normalizer.normalize(name.trim(),Normalizer.Form.NFD).replaceAll("\\p{M}","").toLowerCase(Locale.ROOT);
    }
    public static Map<String,Object> selected(Map<String,Object> m) {
        for (Object v : saved(m)) if (Objects.equals(Json.object(v).get("id"),m.get("selectedSavedConnectionID"))) return Json.object(v);
        return null;
    }
    public static Map<String,Object> archive(Map<String,Object> settings, boolean activeOnly) {
        Map<String,Object> a = new LinkedHashMap<>(); a.put("formatVersion",2);
        Map<String,Object> selected = selected(settings);
        if (activeOnly && selected != null) {
            Map<String,Object> entry = Json.copy(selected); entry.put("snapshot",snapshot(settings));
            a.put("savedConnections",Collections.singletonList(entry));
        } else {
            a.put("current",snapshot(settings));
            a.put("savedConnections",activeOnly ? new ArrayList<>() : saved(settings));
            if (!activeOnly && selected != null) a.put("selectedSavedConnectionID",selected.get("id"));
        }
        return Json.copy(a);
    }
    public static void validateArchive(Map<String,Object> a) {
        int version = Json.integer(a,"formatVersion",0);
        if (version != 1 && version != 2) throw new IllegalArgumentException("Unsupported archive version: " + version);
        List<Object> list = saved(a);
        if (a.get("current") == null && list.isEmpty()) throw new IllegalArgumentException("Empty archive");
        if (a.get("current") != null) validateSnapshot(Json.object(a.get("current")));
        Set<String> ids = new HashSet<>(), names = new HashSet<>();
        for (Object v : list) {
            Map<String,Object> c = Json.object(v); String id = Json.string(c,"id",""); UUID.fromString(id);
            String name = Platform.line(Json.string(c,"name",""),"Connection name");
            if (!ids.add(id) || !names.add(normalized(name))) throw new IllegalArgumentException("Duplicate connection ID or name");
            validateSnapshot(Json.object(c.get("snapshot")));
        }
        Object selected = a.get("selectedSavedConnectionID");
        if (selected != null && !ids.contains(selected)) throw new IllegalArgumentException("Selected connection is absent from archive");
    }
    public static void validateSnapshot(Map<String,Object> m) {
        Map<String,Object> d = defaults();
        for (String key : d.keySet()) {
            Object v = m.get(key); if (v == null) continue;
            if (d.get(key) instanceof String) Json.string(m,key,"");
            else if (d.get(key) instanceof Integer) Json.integer(m,key,0);
            else for (Object entry : Json.array(v)) {
                Map<String,Object> row = Json.object(entry);
                for (String field : Arrays.asList("id","name","host","user","privateKeyPath","remoteHost")) Json.string(row,field,"");
                for (String field : Arrays.asList("port","sshPort","localPort","remotePort")) Json.integer(row,field,0);
                Json.bool(row,"allowLegacyRsaHostKey",false);
                Json.bool(row,"allowLegacyRsaUserSignature",false);
            }
        }
        String mode = Json.string(m,"proxyMode","");
        if (!mode.isEmpty() && !Arrays.asList("automatic","manual","direct").contains(mode)) throw new IllegalArgumentException("Unknown proxy mode");
    }
    public static List<String> conflicts(Map<String,Object> settings, Map<String,Object> archive) {
        List<String> result = new ArrayList<>();
        for (Object incoming : saved(archive)) for (Object existing : saved(settings)) {
            String n = Json.string(Json.object(incoming),"name","");
            if (normalized(n).equals(normalized(Json.string(Json.object(existing),"name","")))) { result.add(n); break; }
        }
        return result;
    }
    public static Map<String,Object> merge(Map<String,Object> settings, Map<String,Object> archive) {
        validateArchive(archive); Map<String,Object> result = Json.copy(settings);
        List<Object> records = saved(result); Map<Object,Object> ids = new HashMap<>();
        for (Object value : saved(archive)) {
            Map<String,Object> entry = Json.copy(Json.object(value)), match = null;
            for (Object old : records) if (normalized(Json.string(Json.object(old),"name","")).equals(normalized(Json.string(entry,"name","")))) { match = Json.object(old); break; }
            Object originalID = entry.get("id");
            if (match != null) { entry.put("id",match.get("id")); records.set(records.indexOf(match),entry); }
            else {
                for (Object old : records) if (Objects.equals(Json.object(old).get("id"),entry.get("id"))) { entry.put("id",UUID.randomUUID().toString()); break; }
                records.add(entry);
            }
            ids.put(originalID,entry.get("id"));
        }
        if (archive.get("current") != null) {
            apply(result,Json.object(archive.get("current")));
            result.put("selectedSavedConnectionID",ids.get(archive.get("selectedSavedConnectionID")));
        }
        return result;
    }
}
