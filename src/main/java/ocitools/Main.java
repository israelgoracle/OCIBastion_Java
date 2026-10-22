package ocitools;

import javax.swing.*;
import java.awt.*;
import java.io.*;
import java.nio.channels.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.List;

public final class Main {
    private Main() { }
    public static void main(String[] args) {
        try {
            if(args.length>0 && args[0].equals("--proxy")) { ProxySupport.relay(args); return; }
            if(args.length==2 && args[0].equals("--terminal")) {
                List<String> command=new ArrayList<>();
                for(Object v:Json.array(Json.parse(new String(Base64.getDecoder().decode(args[1]),StandardCharsets.UTF_8)))) command.add((String)v);
                Process p=Platform.builder(command).inheritIO().start(); int status=p.waitFor();
                System.out.println("\nSSH exited with status "+status+". Press Enter to close."); System.in.read(); return;
            }
            if(args.length==1 && args[0].equals("--help")) {
                System.out.println("OCIBastion_Java — cross-platform OCI Bastion desktop client\nRun: java -jar OCIBastion_Java.jar\nSettings: "+Configuration.dataDirectory()+
                    "\nOverrides: -Docitools.home=DIR -Docitools.oci=FILE -Docitools.ssh=FILE -Docitools.ssh-keygen=FILE\nCopied Windows commands use PowerShell. See README.md for setup."); return;
            }
            if(args.length>0) throw new IllegalArgumentException("Unknown arguments. Use --help");
            if(GraphicsEnvironment.isHeadless()) throw new IOException("A graphical desktop is required. Use --help for setup information.");
            System.setProperty("java.net.useSystemProxies","true");
            Files.createDirectories(Configuration.dataDirectory());
            FileChannel channel=FileChannel.open(Configuration.dataDirectory().resolve("app.lock"),StandardOpenOption.CREATE,StandardOpenOption.WRITE);
            FileLock lock=channel.tryLock(); if(lock==null) { channel.close(); throw new IOException("OCIBastion_Java is already running for this settings directory"); }
            Map<String,Object> settings;
            try { settings=Configuration.load(); } catch(Exception e) { lock.release(); channel.close(); throw e; }
            Runnable release=() -> { try { if(lock.isValid()) lock.release(); channel.close(); } catch(IOException ignored) { } };
            SwingUtilities.invokeLater(() -> {
                try {
                    UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                    MainWindow window=new MainWindow(settings,release);
                    Runtime.getRuntime().addShutdownHook(new Thread(() -> { window.tunnelManager().close(); release.run(); },"exit-cleanup"));
                    window.setVisible(true);
                } catch(Exception e) { release.run(); JOptionPane.showMessageDialog(null,e.toString(),"OCIBastion_Java startup error",JOptionPane.ERROR_MESSAGE); }
            });
        } catch(Exception e) {
            System.err.println("OCIBastion_Java: "+e.getMessage());
            if(args.length==0 && !GraphicsEnvironment.isHeadless()) JOptionPane.showMessageDialog(null,e.getMessage(),"OCIBastion_Java",JOptionPane.ERROR_MESSAGE);
            System.exit(1);
        }
    }
}
