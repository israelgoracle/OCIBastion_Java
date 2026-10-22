package ocitools;

import javax.swing.*;
import javax.swing.table.DefaultTableModel;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.*;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.function.Consumer;

/** Swing stays on the EDT; OCI, proxy detection and process waits run on workers. */
final class MainWindow extends JFrame {
    private Map<String,Object> settings;
    private final Map<String,JTextField> fields=new LinkedHashMap<>();
    private final JComboBox<String> profiles=new JComboBox<>(), mode=new JComboBox<>(new String[]{"automatic","manual","direct"});
    private final JComboBox<String> terminal=new JComboBox<>(new String[]{"terminal","iterm"});
    private final JComboBox<Choice> connections=new JComboBox<>(), compartments=new JComboBox<>(), bastions=new JComboBox<>();
    private final JPanel connectionPanel=new JPanel();
    private final JTextArea log=new JTextArea(6,80);
    private final JLabel status=new JLabel("Disconnected");
    private final JButton connect=new JButton("Connect Bastion"), disconnect=new JButton("Disconnect");
    private final List<Object> compartmentRecords=new ArrayList<>();
    private String nextPage,tenancy; private boolean syncing,busy,closing;
    private SwingWorker<?,?> dataWorker;
    private final DefaultTableModel destinationModel=tableModel(),tunnelModel=tableModel();
    private final JTable destinationTable=new JTable(destinationModel),tunnelTable=new JTable(tunnelModel);
    private final TunnelManager manager; private TrayIcon tray;
    private final Runnable releaseLock;
    MainWindow(Map<String,Object> settings,Runnable releaseLock) {
        super("OCIBastion_Java — OCI Bastion"); this.settings=settings; this.releaseLock=releaseLock;
        manager=new TunnelManager(new TunnelManager.Listener() {
            public void status(String text,boolean connected,boolean running) { SwingUtilities.invokeLater(() -> {
                status.setText(text); log(text); refreshEnabled(); updateTray(connected);
            }); }
            public void session(String id,String fingerprint) { SwingUtilities.invokeLater(() -> {
                MainWindow.this.settings.put("ownedSessionID",id); MainWindow.this.settings.put("ownedSessionKeyFingerprint",fingerprint); persist();
            }); }
        });
        setDefaultCloseOperation(DO_NOTHING_ON_CLOSE); addWindowListener(new WindowAdapter() { public void windowClosing(WindowEvent e) { shutdown(); } });
        JPanel root=new JPanel(new BorderLayout(8,8)); root.setBorder(BorderFactory.createEmptyBorder(10,10,10,10)); setContentPane(root);
        JTabbedPane tabs=new JTabbedPane(); tabs.addTab("Bastion & connections",buildConnection());
        tabs.addTab("SSH destinations",buildTargets(false)); tabs.addTab("Local SSH tunnels",buildTargets(true)); root.add(tabs,BorderLayout.CENTER);
        JPanel bottom=new JPanel(new BorderLayout(6,6)); JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT));
        connect.addActionListener(e -> guarded(() -> { collect(); Configuration.save(this.settings); manager.connect(this.settings); refreshEnabled(); }));
        connect.setToolTipText("Create or reuse an OCI Bastion session and start the local SOCKS5 proxy used by SSH destinations and tunnels.");
        disconnect.setToolTipText("Stop the local Bastion proxy. The OCI session remains until its TTL expires.");
        disconnect.addActionListener(e -> { manager.disconnect(); refreshEnabled(); }); actions.add(connect); actions.add(disconnect);
        actions.add(button("Copy Bastion SSH",() -> copy(Platform.displayCommand(manager.command()))));
        bottom.add(actions,BorderLayout.NORTH); bottom.add(status,BorderLayout.CENTER); log.setEditable(false); log.setLineWrap(true); log.setWrapStyleWord(true);
        bottom.add(new JScrollPane(log),BorderLayout.SOUTH); root.add(bottom,BorderLayout.SOUTH);
        showSettings(); refreshEnabled(); installTray();
        setSize(1050,850); setMinimumSize(new Dimension(840,650)); setLocationRelativeTo(null);
    }
    private JPanel buildConnection() {
        connectionPanel.setLayout(new BoxLayout(connectionPanel,BoxLayout.Y_AXIS));
        JPanel saved=new JPanel(new FlowLayout(FlowLayout.LEFT)); connections.setPreferredSize(new Dimension(230,27)); saved.add(new JLabel("Saved connection")); saved.add(connections);
        saved.add(button("Save as…",this::saveAs)); saved.add(button("Update",() -> { collect(); requireSelected().put("snapshot",Configuration.snapshot(settings)); persist(); log("Saved connection updated"); }));
        saved.add(button("Rename…",this::rename)); saved.add(button("Delete",() -> {
            Map<String,Object> entry=requireSelected(); if(confirm("Delete saved connection “"+entry.get("name")+"”?")) { Configuration.saved(settings).remove(entry); settings.remove("selectedSavedConnectionID"); persist(); refreshConnections(); }
        })); connectionPanel.add(saved);
        JPanel storage=new JPanel(new FlowLayout(FlowLayout.LEFT,6,0));
        JLabel storageHint=new JLabel("Connections saved in: "+Configuration.dataDirectory().resolve("settings.json").toAbsolutePath());
        storageHint.setToolTipText("Current settings, saved connections, SSH destinations and tunnels are stored here. Only key paths are saved, not key contents or passwords.");
        storage.add(storageHint); connectionPanel.add(storage);
        connections.setToolTipText("Load a named connection with its Bastion settings, SSH destinations and tunnels. Use Save as to create one and Update to save changes to it.");
        connections.addActionListener(e -> { if(syncing) return; guarded(() -> {
            if(manager.running() || busy) return;
            Choice choice=(Choice)connections.getSelectedItem(); if(choice==null) return;
            if(choice.id.isEmpty()) settings.remove("selectedSavedConnectionID");
            else for(Object v:Configuration.saved(settings)) if(choice.id.equals(Json.object(v).get("id"))) {
                Configuration.apply(settings,Json.object(Json.object(v).get("snapshot"))); settings.put("selectedSavedConnectionID",choice.id); break;
            }
            resetDiscovery(); showSettings(); persist();
        }); });
        JPanel form=new JPanel(new GridBagLayout());
        addField(form,"OCI config","configPath",true); profiles.setEditable(true);
        profiles.setToolTipText("Select or enter a profile section from your OCI config file, such as DEFAULT. Load profiles refreshes this list.");
        ((JComponent)profiles.getEditor().getEditorComponent()).setToolTipText(profiles.getToolTipText());
        addRow(form,"OCI profile",profiles,button("Load profiles",this::loadProfiles));
        profiles.addActionListener(e -> { if(!syncing) { fields.get("bastionID").setText(""); fields.get("compartmentID").setText(""); resetDiscovery(); } });
        addField(form,"Compartment OCID","compartmentID",false);
        JPanel discover=new JPanel(new FlowLayout(FlowLayout.LEFT,4,0)); discover.add(button("Load 100",() -> loadCompartments(true))); discover.add(button("Load 100 more",() -> loadCompartments(false)));
        compartments.setPreferredSize(new Dimension(380,26)); discover.add(compartments); addRow(form,"Compartments",discover,null);
        compartments.setToolTipText("Choose the compartment containing your Bastion. Load 100 fetches the first page; Load 100 more fetches the next page.");
        discover.setToolTipText(compartments.getToolTipText());
        compartments.addActionListener(e -> { if(!syncing) { Choice c=(Choice)compartments.getSelectedItem(); if(c!=null) { fields.get("compartmentID").setText(c.id); fields.get("bastionID").setText(""); } } });
        addField(form,"Bastion OCID","bastionID",false); bastions.setPreferredSize(new Dimension(380,26)); addRow(form,"Bastions",bastions,button("Find bastions",this::loadBastions));
        bastions.setToolTipText("Select a Bastion in the chosen compartment. Find bastions refreshes the list; the selection fills Bastion OCID.");
        bastions.addActionListener(e -> { if(!syncing) { Choice c=(Choice)bastions.getSelectedItem(); if(c!=null) fields.get("bastionID").setText(c.id); } });
        addField(form,"Bastion public key","publicKeyPath",true); addField(form,"Bastion private key","privateKeyPath",true);
        addField(form,"Local SOCKS5 port","socksPort",false); addField(form,"Session TTL (seconds)","ttlSeconds",false);
        mode.setToolTipText("automatic detects a corporate HTTP proxy; manual uses the host and port below; direct connects without an HTTP proxy.");
        addRow(form,"HTTP proxy mode",mode,null); addField(form,"HTTP proxy host","proxyHost",false); addField(form,"HTTP proxy port","proxyPort",false);
        terminal.setToolTipText("On macOS, choose Terminal or iTerm2 for SSH windows. Windows uses PowerShell; Linux uses an installed terminal automatically.");
        addRow(form,"macOS terminal",terminal,new JLabel("Windows: PowerShell · Linux: detected terminal"));
        connectionPanel.add(form);
        JPanel archive=new JPanel(new FlowLayout(FlowLayout.LEFT));
        archive.add(button("Save settings",() -> { collect(); Configuration.save(settings); log("Settings saved to "+Configuration.dataDirectory()); }));
        archive.add(button("Export all…",() -> export(false))); archive.add(button("Export active…",() -> export(true))); archive.add(button("Import…",this::importArchive));
        archive.add(button("Clear fields",() -> {
            Object savedConnections=settings.get("savedConnections"); settings=Configuration.defaults(); settings.put("configPath",""); settings.put("savedConnections",savedConnections);
            resetDiscovery(); showSettings(); persist();
        })); connectionPanel.add(archive);
        JLabel hint=new JLabel("  Encrypted Bastion key: load it into ssh-agent first. Key contents and passwords are never saved.");
        hint.setBorder(BorderFactory.createEmptyBorder(10,0,10,0)); connectionPanel.add(hint);
        JPanel holder=new JPanel(new BorderLayout()); holder.add(new JScrollPane(connectionPanel)); return holder;
    }
    private static DefaultTableModel tableModel() { return new DefaultTableModel(new Object[]{"Name","SSH host","User","SSH port","Forward"},0) { public boolean isCellEditable(int row,int col) { return false; } }; }
    private JPanel buildTargets(boolean tunnel) {
        JPanel panel=new JPanel(new BorderLayout(6,6)); JTable table=tunnel?tunnelTable:destinationTable; table.setSelectionMode(ListSelectionModel.SINGLE_SELECTION); table.setAutoCreateRowSorter(true);
        table.setToolTipText(tunnel?"Double-click a row to open its local tunnel through the active Bastion connection. Select Edit to change its SSH server, instance key and port forwarding settings.":"Double-click a row to open an interactive SSH shell through the active Bastion connection. Select Edit to change its username, port or instance key.");
        table.getTableHeader().setToolTipText(tunnel?"SSH host, user and SSH port identify the server you log into. Forward shows this computer's listening port and the service reached from that server.":"Name is your label. SSH host is the private server address; User is its login account; SSH port normally is 22.");
        panel.add(new JScrollPane(table)); JPanel actions=new JPanel(new FlowLayout(FlowLayout.LEFT));
        actions.add(button("Add",() -> editTarget(tunnel,false))); actions.add(button("Edit",() -> editTarget(tunnel,true)));
        actions.add(button("Remove",() -> { List<Object> list=targets(tunnel); list.remove(selectedIndex(table)); refreshTargets(); persist(); }));
        JButton open=button(tunnel?"Open tunnel":"Open SSH",() -> {
            Map<String,Object> d=Json.object(targets(tunnel).get(selectedIndex(table))); int socks=manager.port();
            Platform.terminal(Ssh.destination(d,socks,tunnel),Json.string(settings,"terminalApp","terminal"));
        });
        actions.add(open);
        table.addMouseListener(new MouseAdapter() {
            @Override public void mouseClicked(MouseEvent event) {
                if(!SwingUtilities.isLeftMouseButton(event) || event.getClickCount()!=2) return;
                int row=table.rowAtPoint(event.getPoint());
                if(row<0) return;
                table.setRowSelectionInterval(row,row);
                open.doClick();
            }
        });
        actions.add(button("Copy SSH",() -> {
            Map<String,Object> d=Json.object(targets(tunnel).get(selectedIndex(table)));
            int socks=manager.connected()?manager.port():Integer.parseInt(fields.get("socksPort").getText().trim());
            copy(Platform.displayCommand(Ssh.destination(d,socks,tunnel)));
        }));
        panel.add(actions,BorderLayout.SOUTH); return panel;
    }
    private int selectedIndex(JTable table) { int row=table.getSelectedRow(); if(row<0) throw new IllegalStateException("Select a row first"); return table.convertRowIndexToModel(row); }
    private List<Object> targets(boolean tunnel) {
        String key=tunnel?"tunnels":"destinations"; if(settings.get(key)==null) settings.put(key,new ArrayList<>()); return Json.array(settings.get(key));
    }
    private void editTarget(boolean tunnel,boolean edit) throws Exception {
        List<Object> list=targets(tunnel); int index=edit?selectedIndex(tunnel?tunnelTable:destinationTable):-1;
        Map<String,Object> entry=edit?Json.copy(Json.object(list.get(index))):new LinkedHashMap<>();
        if(!edit) { entry.put("id",UUID.randomUUID().toString()); entry.put("user","opc"); entry.put(tunnel?"sshPort":"port",22); if(tunnel) entry.put("remoteHost","127.0.0.1"); }
        JPanel form=new JPanel(new GridBagLayout()); Map<String,JTextField> inputs=new LinkedHashMap<>();
        List<String> names=new ArrayList<>(Arrays.asList("name","host","user",tunnel?"sshPort":"port","privateKeyPath"));
        if(tunnel) names.addAll(Arrays.asList("localPort","remoteHost","remotePort"));
        for(String key:names) {
            JTextField input=new JTextField(Objects.toString(entry.get(key),""),30); inputs.put(key,input);
            input.setToolTipText(targetFieldHelp(key));
            addRow(form,key,input,key.equals("privateKeyPath")?button("Browse…",() -> choose(input)):null);
        }
        JCheckBox legacyRsa=new JCheckBox("Allow legacy RSA host key (ssh-rsa / SHA-1)",Json.bool(entry,"allowLegacyRsaHostKey",false));
        legacyRsa.setToolTipText("For older SSH servers that offer only ssh-rsa host signatures. Applies only to this destination; verify its host fingerprint.");
        addRow(form,"SSH compatibility",legacyRsa,null);
        JCheckBox legacyRsaUser=new JCheckBox("Allow legacy RSA user signatures (ssh-rsa / SHA-1)",Json.bool(entry,"allowLegacyRsaUserSignature",false));
        legacyRsaUser.setToolTipText("For RSA instance keys on older SSH servers that require SHA-1 user signatures. Independent of the server host-key option.");
        addRow(form,"User authentication",legacyRsaUser,null);
        while(JOptionPane.showConfirmDialog(this,form,tunnel?"Local SSH tunnel":"SSH destination",JOptionPane.OK_CANCEL_OPTION,JOptionPane.PLAIN_MESSAGE)==JOptionPane.OK_OPTION) {
            try {
                for(String key:names) {
                    String value=inputs.get(key).getText().trim();
                    entry.put(key,key.toLowerCase(Locale.ROOT).endsWith("port")?Integer.parseInt(value):value);
                }
                if(legacyRsa.isSelected()) entry.put("allowLegacyRsaHostKey",true); else entry.remove("allowLegacyRsaHostKey");
                if(legacyRsaUser.isSelected()) entry.put("allowLegacyRsaUserSignature",true); else entry.remove("allowLegacyRsaUserSignature");
                Platform.line(Json.string(entry,"name",""),"Name");
                Ssh.destination(entry,2222,tunnel);
                if(!Files.isRegularFile(Platform.path(Json.string(entry,"privateKeyPath","")))) throw new IllegalArgumentException("Private key file does not exist");
                if(edit) list.set(index,entry); else list.add(entry); refreshTargets(); persist(); return;
            } catch(Exception e) { error(e); }
        }
    }
    private void refreshTargets() {
        for(boolean tunnel:new boolean[]{false,true}) {
            DefaultTableModel model=tunnel?tunnelModel:destinationModel; model.setRowCount(0);
            for(Object v:targets(tunnel)) {
                Map<String,Object> d=Json.object(v);
                model.addRow(new Object[]{d.get("name"),d.get("host"),d.get("user"),d.get(tunnel?"sshPort":"port"),tunnel?"127.0.0.1:"+d.get("localPort")+" → "+d.get("remoteHost")+":"+d.get("remotePort"):""});
            }
        }
    }
    private void addField(JPanel form,String label,String key,boolean browse) {
        JTextField input=new JTextField(34); input.setToolTipText(connectionFieldHelp(key)); fields.put(key,input); addRow(form,label,input,browse?button("Browse…",() -> choose(input)):null);
    }
    private static String connectionFieldHelp(String key) {
        switch(key) {
            case "configPath": return "Path to your OCI CLI configuration file, usually .oci/config in your home folder. Click Browse to select it, then Load profiles.";
            case "compartmentID": return "OCID of the compartment containing the Bastion (ocid1.compartment...). Select it from Compartments, or paste its OCID if you cannot list compartments.";
            case "bastionID": return "OCID of the Bastion to connect to (ocid1.bastion...). Select it from Bastions, or paste its OCID directly.";
            case "publicKeyPath": return "Bastion SSH public key file, normally ending in .pub. OCI registers this key for the session; it must match the Bastion private key below.";
            case "privateKeyPath": return "Bastion SSH private key matching the public key above. If encrypted, load it into ssh-agent first. Set each instance's login key separately in its SSH destination or local tunnel.";
            case "socksPort": return "Unused port on this computer for the Bastion SOCKS5 proxy: 1 to 65535, default 2222. SSH destinations and tunnels connect through this proxy.";
            case "ttlSeconds": return "OCI Bastion session lifetime in seconds: 1800 to 10800 (30 minutes to 3 hours). The session expires after this duration.";
            case "proxyHost": return "Corporate HTTP CONNECT proxy hostname or IP, for example proxy.company.com. Used only in manual mode; enter no URL scheme, username or password.";
            case "proxyPort": return "Corporate HTTP CONNECT proxy port, such as 80, 8080 or 3128 (1 to 65535). Required in manual mode; may remain 0 in automatic or direct mode.";
            default: throw new IllegalArgumentException("Unknown connection field: "+key);
        }
    }
    private static String targetFieldHelp(String key) {
        switch(key) {
            case "name": return "A name of your choice to identify this SSH destination or tunnel, such as App server or Application port 9090.";
            case "host": return "Private IP address or DNS name of the SSH server reached through the Bastion, for example 10.0.5.62. Enter no username, URL scheme or port here.";
            case "user": return "Login account on the target SSH server, such as opc or ubuntu. Its authorized_keys must contain the public key matching the private key selected below.";
            case "port": case "sshPort": return "Port where SSH listens on the target server: normally 22 (1 to 65535). For a tunnel, this is separate from the local and remote service ports.";
            case "privateKeyPath": return "Private key authorized for this user on the target instance. It may differ from the Bastion key. Select the private key file, not its .pub public key.";
            case "localPort": return "Unused listening port on this computer, for example 9090 (1 to 65535). Your local browser or application connects to 127.0.0.1 on this port.";
            case "remoteHost": return "Service hostname or IP as seen from the SSH server. Use 127.0.0.1 if the service runs on that server, or an address reachable from it. Enter no URL scheme or port.";
            case "remotePort": return "Port of the destination service, for example 8080 (1 to 65535). With localPort 9090, remoteHost 127.0.0.1 and remotePort 8080, localhost:9090 forwards to port 8080 on the SSH server.";
            default: throw new IllegalArgumentException("Unknown SSH field: "+key);
        }
    }
    private static void addRow(JPanel form,String label,JComponent value,JComponent extra) {
        int row=form.getComponentCount(); GridBagConstraints c=new GridBagConstraints(); c.gridy=row; c.insets=new Insets(4,6,4,6); c.anchor=GridBagConstraints.WEST;
        JLabel caption=new JLabel(label); caption.setLabelFor(value); caption.setToolTipText(value.getToolTipText());
        c.gridx=0; form.add(caption,c); c.gridx=1; c.weightx=1; c.fill=GridBagConstraints.HORIZONTAL; form.add(value,c);
        if(extra!=null) { c.gridx=2; c.weightx=0; c.fill=GridBagConstraints.NONE; form.add(extra,c); }
    }
    private void choose(JTextField input) {
        JFileChooser chooser=new JFileChooser();
        if(!input.getText().trim().isEmpty()) chooser.setSelectedFile(Platform.path(input.getText().trim()).toFile());
        if(chooser.showOpenDialog(this)==JFileChooser.APPROVE_OPTION) input.setText(chooser.getSelectedFile().getAbsolutePath());
    }
    private void collect() {
        Map<String,Object> snapshot=Configuration.snapshot(settings);
        for(Map.Entry<String,JTextField> e:fields.entrySet()) {
            String key=e.getKey(), value=e.getValue().getText().trim();
            snapshot.put(key,Arrays.asList("socksPort","ttlSeconds","proxyPort").contains(key)?Integer.parseInt(value.isEmpty()?"0":value):value);
        }
        snapshot.put("profile",Objects.toString(profiles.getEditor().getItem(),"")); snapshot.put("proxyMode",mode.getSelectedItem()); snapshot.put("terminalApp",terminal.getSelectedItem());
        Configuration.apply(settings,snapshot);
    }
    private void showSettings() {
        syncing=true;
        try {
            for(Map.Entry<String,JTextField> e:fields.entrySet()) e.getValue().setText(Objects.toString(settings.get(e.getKey()),""));
            profiles.setSelectedItem(Json.string(settings,"profile","DEFAULT"));
            mode.setSelectedItem(Json.string(settings,"proxyMode",Json.string(settings,"proxyHost","").isEmpty()?"automatic":"manual"));
            terminal.setSelectedItem(Json.string(settings,"terminalApp","terminal")); refreshConnections(); refreshTargets();
        } finally { syncing=false; }
    }
    private void refreshConnections() {
        boolean was=syncing; syncing=true;
        try {
            connections.removeAllItems(); connections.addItem(new Choice("","(unsaved)"));
            for(Object v:Configuration.saved(settings)) {
                Map<String,Object> s=Json.object(v); Choice c=new Choice(Json.string(s,"id",""),Json.string(s,"name","")); connections.addItem(c);
                if(c.id.equals(settings.get("selectedSavedConnectionID"))) connections.setSelectedItem(c);
            }
        } finally { syncing=was; }
    }
    private Map<String,Object> requireSelected() { Map<String,Object> e=Configuration.selected(settings); if(e==null) throw new IllegalStateException("Select a saved connection"); return e; }
    private String newName(String previous) {
        String raw=JOptionPane.showInputDialog(this,"Connection name",previous); if(raw==null) return null;
        String name=Platform.line(raw,"Name");
        for(Object v:Configuration.saved(settings)) {
            Map<String,Object> e=Json.object(v);
            if(Configuration.normalized(name).equals(Configuration.normalized(Json.string(e,"name",""))) && !Objects.equals(e.get("id"),settings.get("selectedSavedConnectionID"))) throw new IllegalArgumentException("A connection with that name already exists");
        }
        return name;
    }
    private void saveAs() throws Exception {
        collect(); String name=newName(""); if(name==null) return;
        for(Object v:Configuration.saved(settings)) if(Configuration.normalized(name).equals(Configuration.normalized(Json.string(Json.object(v),"name","")))) throw new IllegalArgumentException("A connection with that name already exists");
        Map<String,Object> entry=new LinkedHashMap<>(); entry.put("id",UUID.randomUUID().toString()); entry.put("name",name); entry.put("snapshot",Configuration.snapshot(settings));
        Configuration.saved(settings).add(entry); settings.put("selectedSavedConnectionID",entry.get("id")); persist(); refreshConnections();
    }
    private void rename() throws Exception {
        Map<String,Object> entry=requireSelected(); String name=newName(Json.string(entry,"name","")); if(name==null) return;
        entry.put("name",name); persist(); refreshConnections();
    }
    private void export(boolean active) throws Exception {
        collect(); JFileChooser chooser=new JFileChooser(); chooser.setSelectedFile(new java.io.File("OCIBastion_Java-config.json"));
        if(chooser.showSaveDialog(this)!=JFileChooser.APPROVE_OPTION) return;
        Path file=chooser.getSelectedFile().toPath(); if(Files.exists(file) && !confirm("Replace "+file.getFileName()+"?")) return;
        Configuration.write(file,Configuration.archive(settings,active)); log("Exported "+file);
    }
    private void importArchive() throws Exception {
        if(manager.running()) throw new IllegalStateException("Disconnect before importing");
        collect();
        JFileChooser chooser=new JFileChooser(); if(chooser.showOpenDialog(this)!=JFileChooser.APPROVE_OPTION) return;
        Map<String,Object> archive=Configuration.read(chooser.getSelectedFile().toPath()); Configuration.validateArchive(archive);
        List<String> conflicts=Configuration.conflicts(settings,archive);
        if((archive.get("current")!=null || !conflicts.isEmpty()) && !confirm("Import will replace "+(archive.get("current")!=null?"the current form":"")+
            (!conflicts.isEmpty()?"\nSaved connections: "+String.join(", ",conflicts):"")+". Continue?")) return;
        Map<String,Object> merged=Configuration.merge(settings,archive); Configuration.save(merged); settings=merged;
        resetDiscovery(); showSettings(); log("Imported "+Configuration.saved(archive).size()+" saved connections. Check local key and OCI config paths.");
    }
    private void resetDiscovery() {
        boolean was=syncing; syncing=true; try { compartmentRecords.clear(); compartments.removeAllItems(); bastions.removeAllItems(); nextPage=null; tenancy=null; } finally { syncing=was; }
    }
    private void loadProfiles() throws Exception {
        collect(); List<String> names=new ArrayList<>(OciClient.profiles(Json.string(settings,"configPath","")).keySet()); Collections.sort(names);
        syncing=true; try { Object selected=profiles.getEditor().getItem(); profiles.removeAllItems(); for(String n:names) profiles.addItem(n); profiles.setSelectedItem(names.contains(selected)?selected:names.isEmpty()?"DEFAULT":names.get(0)); } finally { syncing=false; }
        log("Loaded "+names.size()+" OCI profiles");
    }
    private void loadCompartments(boolean reset) throws Exception {
        collect(); if(!reset && nextPage==null) { log("No further page available; use Load 100 first"); return; }
        Map<String,Object> snapshot=Json.copy(settings); String page=reset?null:nextPage;
        background(() -> new OciClient(snapshot,ProxySupport.resolve(snapshot)).compartments(page),result -> {
            if(reset) compartmentRecords.clear(); compartmentRecords.addAll(result.records); tenancy=result.tenancy; nextPage=result.next;
            Map<String,Map<String,Object>> byId=new HashMap<>(); for(Object v:compartmentRecords) byId.put(Json.string(Json.object(v),"id",""),Json.object(v));
            List<Choice> choices=new ArrayList<>();
            for(Map<String,Object> record:byId.values()) {
                LinkedList<String> parts=new LinkedList<>(); Set<Object> visited=new HashSet<>(); Map<String,Object> current=record;
                while(current!=null && visited.add(current.get("id"))) { parts.addFirst(Json.string(current,"name",Json.string(current,"id",""))); current=byId.get(current.get("compartment-id")); }
                choices.add(new Choice(Json.string(record,"id",""),String.join(" / ",parts)));
            }
            choices.sort(Comparator.comparing(c -> c.label,String.CASE_INSENSITIVE_ORDER)); syncing=true;
            try {
                compartments.removeAllItems(); compartments.addItem(new Choice(tenancy,"Tenancy (root)")); for(Choice c:choices) compartments.addItem(c);
                compartments.setSelectedIndex(-1);
            } finally { syncing=false; }
            log("Loaded "+byId.size()+" compartments"+(nextPage==null?"; no further pages":"; more pages available"));
        });
    }
    private void loadBastions() throws Exception {
        collect(); Map<String,Object> snapshot=Json.copy(settings);
        background(() -> new OciClient(snapshot,ProxySupport.resolve(snapshot)).bastions(),list -> {
            syncing=true; try {
                bastions.removeAllItems();
                list.sort(Comparator.comparing(v -> Json.string(Json.object(v),"name",Json.string(Json.object(v),"id","")),String.CASE_INSENSITIVE_ORDER));
                for(Object v:list) { Map<String,Object> m=Json.object(v); bastions.addItem(new Choice(Json.string(m,"id",""),Json.string(m,"name",Json.string(m,"display-name",Json.string(m,"id",""))))); }
                bastions.setSelectedIndex(-1);
            } finally { syncing=false; }
            log("Found "+list.size()+" bastions");
        });
    }
    private <T> void background(Callable<T> task,Consumer<T> complete) {
        if(busy) throw new IllegalStateException("An operation is in progress"); busy=true; refreshEnabled(); status.setText("Loading OCI data…");
        SwingWorker<T,Void> worker=new SwingWorker<T,Void>() {
            protected T doInBackground() throws Exception { return task.call(); }
            protected void done() { busy=false; dataWorker=null; if(closing) return; try { complete.accept(get()); status.setText("Ready"); } catch(Exception e) { error(e.getCause()==null?e:e.getCause()); } finally { refreshEnabled(); } }
        };
        dataWorker=worker; worker.execute();
    }
    private void refreshEnabled() {
        enableTree(connectionPanel,!busy && !manager.running() && !closing);
        connect.setEnabled(!busy && !manager.running() && !closing); disconnect.setEnabled(manager.running() && !closing);
    }
    private static void enableTree(Container c,boolean enabled) { for(Component child:c.getComponents()) { child.setEnabled(enabled); if(child instanceof Container) enableTree((Container)child,enabled); } }
    private void persist() { try { Configuration.save(settings); } catch(Exception e) { error(e); } }
    private void copy(String text) { Toolkit.getDefaultToolkit().getSystemClipboard().setContents(new StringSelection(text),null); log("SSH command copied"); }
    private void log(String text) { log.append(java.time.LocalTime.now().withNano(0)+"  "+text+"\n"); if(log.getDocument().getLength()>100000) log.setText(log.getText().substring(50000)); log.setCaretPosition(log.getDocument().getLength()); }
    private void error(Throwable e) { log("Error: "+e.getMessage()); JOptionPane.showMessageDialog(this,e.getMessage(),"OCIBastion_Java",JOptionPane.ERROR_MESSAGE); }
    private boolean confirm(String message) { return JOptionPane.showConfirmDialog(this,message,"OCIBastion_Java",JOptionPane.OK_CANCEL_OPTION)==JOptionPane.OK_OPTION; }
    interface Action { void run() throws Exception; }
    private JButton button(String label,Action action) {
        JButton button=new JButton(label); button.addActionListener(e -> guarded(action));
        switch(label) {
            case "Save as…": button.setToolTipText("Save the current Bastion form, SSH destinations and local tunnels as a new named connection."); break;
            case "Update": button.setToolTipText("Replace the selected named connection's saved settings with the current form, SSH destinations and tunnels."); break;
            case "Rename…": button.setToolTipText("Change the selected saved connection's name."); break;
            case "Delete": button.setToolTipText("Delete the selected saved connection; the currently displayed settings remain in the form."); break;
            case "Browse…": button.setToolTipText("Choose the local file required by the field beside this button. Hover over the field for details."); break;
            case "Load profiles": button.setToolTipText("Read profile names from the OCI config file selected above."); break;
            case "Load 100": button.setToolTipText("Fetch the first page of accessible compartments from the selected OCI profile's tenancy."); break;
            case "Load 100 more": button.setToolTipText("Fetch the next page of compartments after the first page has loaded."); break;
            case "Find bastions": button.setToolTipText("List Bastions in the compartment selected or entered above."); break;
            case "Save settings": button.setToolTipText("Persist the current form. Use Update separately to replace the selected named connection's saved snapshot."); break;
            case "Export all…": button.setToolTipText("Export the current form and all saved connections, including their SSH destinations and tunnels, to a JSON backup."); break;
            case "Export active…": button.setToolTipText("Export only the current connection, including visible changes, SSH destinations and tunnels."); break;
            case "Import…": button.setToolTipText("Import connections from a JSON export. Existing names or the current form may require overwrite confirmation. Disconnect Bastion first."); break;
            case "Clear fields": button.setToolTipText("Reset the current Bastion form, destinations and tunnels while retaining your named saved connections."); break;
            case "Add": button.setToolTipText("Create a new entry in this tab; hover over its editor fields for examples and requirements."); break;
            case "Edit": button.setToolTipText("Edit the selected entry's SSH login details, key and compatibility settings, plus forwarding ports for a local tunnel."); break;
            case "Remove": button.setToolTipText("Remove the selected entry from the current configuration. Use Update to retain the change in a named connection."); break;
            case "Open SSH": button.setToolTipText("Open an interactive shell on the selected private SSH server through the active Bastion proxy."); break;
            case "Open tunnel": button.setToolTipText("Open the selected port forward in a terminal. Keep that terminal and the Bastion connection running while using the service."); break;
            case "Copy SSH": button.setToolTipText("Copy the selected entry's SSH command. The Bastion proxy must be active when you run it."); break;
            case "Copy Bastion SSH": button.setToolTipText("Copy the SSH command for the active OCI Bastion session."); break;
        }
        return button;
    }
    private void guarded(Action action) { try { action.run(); } catch(Exception e) { error(e); } }
    private void installTray() {
        if(!SystemTray.isSupported()) return;
        try {
            PopupMenu menu=new PopupMenu(); MenuItem show=new MenuItem("Show OCIBastion_Java"),quit=new MenuItem("Quit");
            show.addActionListener(e -> SwingUtilities.invokeLater(() -> { setVisible(true); setExtendedState(JFrame.NORMAL); toFront(); }));
            quit.addActionListener(e -> SwingUtilities.invokeLater(this::shutdown)); menu.add(show); menu.add(quit);
            tray=new TrayIcon(icon(false),"OCIBastion_Java: disconnected",menu); tray.setImageAutoSize(true); SystemTray.getSystemTray().add(tray);
            tray.addActionListener(e -> SwingUtilities.invokeLater(() -> { setVisible(true); toFront(); }));
        } catch(Exception e) { log("System tray unavailable: "+e.getMessage()); }
    }
    private Image icon(boolean connected) { BufferedImage image=new BufferedImage(32,32,BufferedImage.TYPE_INT_ARGB); Graphics2D g=image.createGraphics(); g.setColor(connected?new Color(24,140,80):new Color(80,95,120)); g.fillRoundRect(0,0,32,32,8,8); g.setColor(Color.WHITE); g.setFont(new Font(Font.SANS_SERIF,Font.BOLD,18)); g.drawString("O",8,23); g.dispose(); return image; }
    private void updateTray(boolean connected) { if(tray!=null) { tray.setImage(icon(connected)); tray.setToolTip("OCIBastion_Java: "+(connected?"connected":"disconnected")); } }
    private void shutdown() {
        if(closing) return;
        try { if(!manager.running()) collect(); Configuration.save(settings); } catch(Exception e) { error(e); }
        closing=true; refreshEnabled(); status.setText("Stopping SSH…");
        if(dataWorker!=null) dataWorker.cancel(true);
        new Thread(() -> { manager.close(); SwingUtilities.invokeLater(() -> { if(tray!=null) SystemTray.getSystemTray().remove(tray); dispose(); releaseLock.run(); }); },"shutdown").start();
    }
    private static final class Choice {
        final String id,label; Choice(String id,String label) { this.id=id; this.label=label; }
        public String toString() { return label; }
    }
    TunnelManager tunnelManager() { return manager; }
}
