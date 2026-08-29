package com.projectsknowledge.general.desktop;

import com.projectsknowledge.general.config.ProjectsKnowledgeProperties;
import jakarta.annotation.PreDestroy;
import java.awt.AWTException;
import java.awt.Color;
import java.awt.Desktop;
import java.awt.Font;
import java.awt.Graphics2D;
import java.awt.Image;
import java.awt.MenuItem;
import java.awt.PopupMenu;
import java.awt.SystemTray;
import java.awt.TrayIcon;
import java.awt.image.BufferedImage;
import java.io.IOException;
import java.net.URI;
import java.net.URISyntaxException;
import java.util.concurrent.atomic.AtomicBoolean;
import lombok.RequiredArgsConstructor;
import lombok.extern.slf4j.Slf4j;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.boot.web.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.event.EventListener;
import org.springframework.stereotype.Component;

/** Adds browser and system-tray controls only for the packaged Windows desktop distribution. */
@Component
@RequiredArgsConstructor
@Slf4j
public class DesktopApplicationService {

    private final ProjectsKnowledgeProperties properties;
    private final ConfigurableApplicationContext context;
    private final AtomicBoolean closing = new AtomicBoolean();
    private TrayIcon trayIcon;
    private URI applicationUri;

    @EventListener(ApplicationReadyEvent.class)
    public void start() {
        if (!properties.getDesktop().isEnabled()) return;
        applicationUri = applicationUri();
        installTrayIcon();
        openBrowser();
    }

    private URI applicationUri() {
        int port = ((WebServerApplicationContext) context).getWebServer().getPort();
        try {
            return new URI("http", null, "127.0.0.1", port, "/", "desktop=true", null);
        } catch (URISyntaxException impossible) {
            throw new IllegalStateException("Could not create the local application URL.", impossible);
        }
    }

    private void installTrayIcon() {
        if (!SystemTray.isSupported()) {
            log.warn("System tray is unavailable. Open {} manually and stop the app from Task Manager.", applicationUri);
            return;
        }
        PopupMenu menu = new PopupMenu();
        MenuItem open = new MenuItem("Open Projects Knowledge");
        open.addActionListener(ignored -> openBrowser());
        menu.add(open);
        menu.addSeparator();
        MenuItem exit = new MenuItem("Exit");
        exit.addActionListener(ignored -> requestShutdown());
        menu.add(exit);
        trayIcon = new TrayIcon(iconImage(), "Projects Knowledge", menu);
        trayIcon.setImageAutoSize(true);
        trayIcon.addActionListener(ignored -> openBrowser());
        try {
            SystemTray.getSystemTray().add(trayIcon);
        } catch (AWTException exception) {
            trayIcon = null;
            log.warn("Could not add the Projects Knowledge tray icon: {}", exception.getMessage());
        }
    }

    private Image iconImage() {
        BufferedImage image = new BufferedImage(64, 64, BufferedImage.TYPE_INT_ARGB);
        Graphics2D graphics = image.createGraphics();
        try {
            graphics.setColor(new Color(11, 42, 66));
            graphics.fillRoundRect(0, 0, 64, 64, 16, 16);
            graphics.setColor(new Color(111, 194, 235));
            graphics.setFont(new Font(Font.SANS_SERIF, Font.BOLD, 25));
            graphics.drawString("PK", 12, 41);
            return image;
        } finally {
            graphics.dispose();
        }
    }

    private void openBrowser() {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) {
            log.warn("Default browser integration is unavailable. Open {} manually.", applicationUri);
            return;
        }
        try {
            Desktop.getDesktop().browse(applicationUri);
        } catch (IOException exception) {
            log.warn("Could not open the default browser. Open {} manually.", applicationUri);
        }
    }

    /** Lets the HTTP response finish before stopping the embedded server and JVM. */
    public void requestShutdown() {
        if (!closing.compareAndSet(false, true)) return;
        Thread.ofVirtual().name("projects-knowledge-shutdown").start(() -> {
            try {
                Thread.sleep(500);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
            }
            closeApplication();
        });
    }

    private void closeApplication() {
        removeTrayIcon();
        int exitCode = SpringApplication.exit(context);
        System.exit(exitCode);
    }

    @PreDestroy
    public void removeTrayIcon() {
        if (trayIcon == null || !SystemTray.isSupported()) return;
        SystemTray.getSystemTray().remove(trayIcon);
        trayIcon = null;
    }
}
