package ocitools;

import javax.swing.*;
import java.awt.*;
import java.awt.event.WindowEvent;
import java.nio.file.*;

/** Opens the actual Swing window with isolated settings, then exercises orderly close. */
public final class GuiSmoke {
    public static void main(String[] args) throws Exception {
        System.setProperty("ocitools.home",Files.createTempDirectory(Paths.get(args[0]),"gui-smoke-").toString());
        SwingUtilities.invokeAndWait(() -> {
            try {
                UIManager.setLookAndFeel(UIManager.getSystemLookAndFeelClassName());
                MainWindow window=new MainWindow(Configuration.defaults(),() -> { }); window.setVisible(true);
                if(args.length>1) {
                    Timer capture=new Timer(900,e -> {
                        try {
                            java.awt.image.BufferedImage image=new java.awt.image.BufferedImage(window.getWidth(),window.getHeight(),java.awt.image.BufferedImage.TYPE_INT_RGB);
                            Graphics2D graphics=image.createGraphics(); window.paint(graphics); graphics.dispose();
                            javax.imageio.ImageIO.write(image,"png",Paths.get(args[1]).toFile());
                            window.dispatchEvent(new WindowEvent(window,WindowEvent.WINDOW_CLOSING));
                        } catch(Exception ex) { throw new RuntimeException(ex); }
                    }); capture.setRepeats(false); capture.start();
                } else window.dispatchEvent(new WindowEvent(window,WindowEvent.WINDOW_CLOSING));
            } catch(Exception e) { throw new RuntimeException(e); }
        });
    }
}
