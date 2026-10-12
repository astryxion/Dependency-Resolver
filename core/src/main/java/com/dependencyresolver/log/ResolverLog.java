package com.dependencyresolver.log;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.logging.FileHandler;
import java.util.logging.Formatter;
import java.util.logging.Level;
import java.util.logging.LogRecord;
import java.util.logging.Logger;
import java.util.logging.StreamHandler;

public final class ResolverLog {
    private static final Logger LOGGER = Logger.getLogger("dependencyresolver");
    private static String secret = "";
    private static boolean ready;

    private ResolverLog() {
    }

    public static synchronized void init(Path gameDir, boolean debug) {
        reset();
        LOGGER.setUseParentHandlers(false);
        LOGGER.setLevel(debug ? Level.FINE : Level.INFO);
        LOGGER.addHandler(new StreamHandler(System.out, new LineFormatter()) {
            @Override
            public synchronized void publish(LogRecord record) {
                super.publish(record);
                flush();
            }
        });
        if (gameDir != null) {
            try {
                Path logs = gameDir.resolve("logs");
                Files.createDirectories(logs);
                FileHandler files = new FileHandler(logs.resolve("dependencyresolver.log").toString(), true);
                files.setFormatter(new LineFormatter());
                files.setLevel(Level.FINE);
                LOGGER.addHandler(files);
            } catch (IOException exception) {
                LOGGER.log(Level.WARNING, "Could not open logs/dependencyresolver.log: " + exception.getMessage());
            }
        }
        ready = true;
    }

    public static synchronized void reset() {
        for (var handler : LOGGER.getHandlers()) {
            handler.close();
            LOGGER.removeHandler(handler);
        }
        ready = false;
    }

    public static void setSecret(String apiKey) {
        secret = apiKey == null ? "" : apiKey;
    }

    public static void info(String message) {
        log(Level.INFO, message, null);
    }

    public static void debug(String message) {
        log(Level.FINE, message, null);
    }

    public static void error(String message, Throwable error) {
        log(Level.SEVERE, message, error);
    }

    public static String redact(String value) {
        if (value == null) {
            return "";
        }
        String cleaned = value;
        if (!secret.isBlank()) {
            cleaned = cleaned.replace(secret, "***");
        }
        int query = cleaned.indexOf('?');
        if (query >= 0 && (cleaned.startsWith("http://") || cleaned.startsWith("https://"))) {
            cleaned = cleaned.substring(0, query);
        }
        return cleaned;
    }

    private static void log(Level level, String message, Throwable error) {
        if (!ready) {
            System.out.println("[dependencyresolver] " + redact(message));
            if (error != null) {
                error.printStackTrace(System.out);
            }
            return;
        }
        if (error == null) {
            LOGGER.log(level, redact(message));
        } else {
            LOGGER.log(level, redact(message), error);
        }
    }

    private static final class LineFormatter extends Formatter {
        @Override
        public String format(LogRecord record) {
            String thrown = "";
            if (record.getThrown() != null) {
                thrown = " (" + record.getThrown().getClass().getSimpleName() + ": " + record.getThrown().getMessage() + ")";
            }
            return Instant.ofEpochMilli(record.getMillis()) + " " + record.getLevel().getName()
                    + " [dependencyresolver] " + redact(record.getMessage()) + thrown + System.lineSeparator();
        }
    }
}
