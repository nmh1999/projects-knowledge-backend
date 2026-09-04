package com.projectsknowledge.general.desktop;

import java.awt.Desktop;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.channels.FileChannel;
import java.nio.channels.FileLock;
import java.nio.channels.OverlappingFileLockException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;

/** Keeps the packaged desktop application single-instance and reopens the running UI. */
public final class DesktopInstanceCoordinator {

    private static final String DESKTOP_OPTION = "--projects-knowledge.desktop.enabled";
    private static final String PORT_OPTION = "--server.port";
    private static final int DEFAULT_PORT = 8090;
    private static final int READY_ATTEMPTS = 30;
    private static final long READY_DELAY_MILLIS = 250;
    private static FileChannel lockChannel;
    private static FileLock instanceLock;

    private DesktopInstanceCoordinator() {}

    public static boolean startOrActivateRunningInstance(String[] args) {
        if (!booleanOption(args, DESKTOP_OPTION, false)) return true;

        URI applicationUri =
            URI.create("http://127.0.0.1:" + integerOption(args, PORT_OPTION, DEFAULT_PORT) + "/");
        try {
            Path lockFile = Path.of(System.getProperty("user.home"), ".projects-knowledge", "desktop.lock");
            Files.createDirectories(lockFile.getParent());
            lockChannel = FileChannel.open(lockFile, StandardOpenOption.CREATE, StandardOpenOption.WRITE);
            try {
                instanceLock = lockChannel.tryLock();
            } catch (OverlappingFileLockException ignored) {
                instanceLock = null;
            }
            if (instanceLock != null) return true;
        } catch (IOException exception) {
            if (!isReady(applicationUri)) return true;
        }

        waitUntilReadyAndOpen(applicationUri);
        closeLockChannel();
        return false;
    }

    private static void waitUntilReadyAndOpen(URI applicationUri) {
        for (int attempt = 0; attempt < READY_ATTEMPTS; attempt++) {
            if (isReady(applicationUri)) {
                openBrowser(applicationUri);
                return;
            }
            try {
                Thread.sleep(READY_DELAY_MILLIS);
            } catch (InterruptedException exception) {
                Thread.currentThread().interrupt();
                return;
            }
        }
    }

    private static boolean isReady(URI applicationUri) {
        try {
            HttpURLConnection connection = (HttpURLConnection) applicationUri.toURL().openConnection();
            connection.setConnectTimeout(300);
            connection.setReadTimeout(300);
            connection.setRequestMethod("HEAD");
            int status = connection.getResponseCode();
            connection.disconnect();
            return status >= 200 && status < 400;
        } catch (IOException ignored) {
            return false;
        }
    }

    private static void openBrowser(URI applicationUri) {
        if (!Desktop.isDesktopSupported() || !Desktop.getDesktop().isSupported(Desktop.Action.BROWSE)) return;
        try {
            Desktop.getDesktop().browse(applicationUri);
        } catch (IOException ignored) {
            // The running application's tray icon remains available when browser integration fails.
        }
    }

    private static boolean booleanOption(String[] args, String option, boolean defaultValue) {
        String value = optionValue(args, option);
        return value == null ? defaultValue : Boolean.parseBoolean(value);
    }

    private static int integerOption(String[] args, String option, int defaultValue) {
        String value = optionValue(args, option);
        if (value == null) return defaultValue;
        try {
            return Integer.parseInt(value);
        } catch (NumberFormatException ignored) {
            return defaultValue;
        }
    }

    private static String optionValue(String[] args, String option) {
        String value = null;
        for (int index = 0; index < args.length; index++) {
            String argument = args[index];
            if (argument.startsWith(option + "=")) {
                value = argument.substring(option.length() + 1);
            } else if (argument.equals(option) && index + 1 < args.length) {
                value = args[++index];
            }
        }
        return value;
    }

    private static void closeLockChannel() {
        if (lockChannel == null) return;
        try {
            lockChannel.close();
        } catch (IOException ignored) {
            // Nothing else can be recovered while the secondary process exits.
        } finally {
            lockChannel = null;
        }
    }
}
