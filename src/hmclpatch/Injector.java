/*
 * HMCLNoIPCheck - a hot-injection patch for HMCL 3.17.
 *
 * This file is released into the public domain.
 */
package hmclpatch;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/// Finds running HMCL JVMs and injects the agent into them.
///
/// Shared by the command line front end ([Main]) and the injection window ([InjectionWindow]) so that
/// both do exactly the same thing.
final class Injector {

    private Injector() {
    }

    /// Outcome of one injection attempt.
    ///
    /// @param ok    whether the agent reported `status=ok`
    /// @param lines the raw `key=value` report written by [NoIPCheckAgent#agentmain]
    /// @param error the failure reason when the agent could not be loaded at all
    record Result(boolean ok, List<String> lines, String error) {

        /// Reads one key of the agent report, or `null` when it is missing.
        String value(String key) {
            for (String line : lines) {
                int index = line.indexOf('=');
                if (index > 0 && line.substring(0, index).equals(key)) {
                    return line.substring(index + 1);
                }
            }
            return null;
        }
    }

    /// Whether a JVM display name (jar path / main class reported by the attach registry) belongs to HMCL.
    static boolean looksLikeHmcl(String displayName) {
        String lower = displayName.toLowerCase(Locale.ROOT);
        if (lower.contains("hmclnoipcheck")) {
            // Never attach to the patch tool itself.
            return false;
        }
        return lower.contains("hmcl") || lower.contains("jackhuang");
    }

    /// Returns the `{pid, displayName}` pairs of the running HMCL JVMs.
    ///
    /// @throws Exception when the attach registry is unavailable (for example on a JRE without `jdk.attach`)
    static List<String[]> hmclTargets() throws Exception {
        return filterHmcl(AttachSupport.list());
    }

    /// Keeps only the entries that belong to HMCL and drops this very process.
    static List<String[]> filterHmcl(List<String[]> targets) {
        String selfPid = Long.toString(ProcessHandle.current().pid());
        List<String[]> result = new ArrayList<>();

        for (String[] target : targets) {
            if (!target[0].equals(selfPid) && looksLikeHmcl(target[1])) {
                result.add(target);
            }
        }

        return result;
    }

    /// Injects the patch into `pid`.
    ///
    /// The agent writes its report into a temporary file; this method reads it back and deletes it.
    static Result inject(long pid) {
        Path self = NoIPCheckAgent.ownJar();
        if (self == null || !Files.isRegularFile(self)) {
            return new Result(false, List.of(), "无法定位补丁 jar");
        }

        Path resultFile = null;
        try {
            resultFile = Files.createTempFile("hmcl-noipcheck-", ".txt");
            AttachSupport.attach(self, pid, resultFile);

            List<String> lines = Files.readAllLines(resultFile, StandardCharsets.UTF_8);
            return new Result("ok".equals(valueOf(lines, "status")), lines, null);
        } catch (Throwable e) {
            return new Result(false, List.of(), String.valueOf(e));
        } finally {
            if (resultFile != null) {
                try {
                    Files.deleteIfExists(resultFile);
                } catch (Throwable ignored) {
                    // A leftover temp file is harmless.
                }
            }
        }
    }

    private static String valueOf(List<String> lines, String key) {
        for (String line : lines) {
            int index = line.indexOf('=');
            if (index > 0 && line.substring(0, index).equals(key)) {
                return line.substring(index + 1);
            }
        }
        return null;
    }
}
