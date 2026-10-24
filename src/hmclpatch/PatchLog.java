/*
 * HMCLNoIPCheck - a hot-injection patch for HMCL 3.17.
 *
 * This file is released into the public domain.
 */
package hmclpatch;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.LocalDateTime;
import java.time.format.DateTimeFormatter;

/// Minimal logger used by the patch.
///
/// Every message is written to the standard output (visible when HMCL is started from a console) and
/// appended to `HMCLNoIPCheck.log` next to the patch jar. Both sinks are best-effort: a read-only
/// directory must never break the launcher, therefore all I/O failures are swallowed.
final class PatchLog {

    private static final DateTimeFormatter TIME_FORMAT = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss.SSS");
    private static final Object LOCK = new Object();

    private static boolean resolved;
    private static Path logFile;

    private PatchLog() {
    }

    /// Returns the log file used by this patch, or `null` when it cannot be resolved.
    static Path logFile() {
        synchronized (LOCK) {
            resolve();
            return logFile;
        }
    }

    static void info(String message) {
        write("INFO", message);
    }

    static void warn(String message) {
        write("WARN", message);
    }

    static void error(String message, Throwable throwable) {
        write("ERROR", throwable == null ? message : message + ": " + throwable);
    }

    private static void resolve() {
        if (resolved) {
            return;
        }
        resolved = true;

        try {
            Path jar = NoIPCheckAgent.ownJar();
            if (jar != null && jar.getParent() != null) {
                logFile = jar.getParent().resolve("HMCLNoIPCheck.log");
            }
        } catch (Throwable ignored) {
            // The patch must keep working even if the jar location cannot be determined.
        }
    }

    private static void write(String level, String message) {
        String line = LocalDateTime.now().format(TIME_FORMAT) + " [" + level + "] " + message;

        System.out.println("[HMCLNoIPCheck] " + message);

        synchronized (LOCK) {
            resolve();
            if (logFile == null) {
                return;
            }

            try {
                Files.write(
                        logFile,
                        (line + System.lineSeparator()).getBytes(StandardCharsets.UTF_8),
                        StandardOpenOption.CREATE,
                        StandardOpenOption.APPEND
                );
            } catch (IOException ignored) {
                // Logging is best-effort.
            }
        }
    }
}
