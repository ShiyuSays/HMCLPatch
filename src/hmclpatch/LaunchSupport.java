/*
 * HMCLNoIPCheck - a hot-injection patch for HMCL 3.17.
 *
 * This file is released into the public domain.
 */
package hmclpatch;

import java.io.IOException;
import java.nio.file.DirectoryStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/// Starts HMCL with the agent attached.
///
/// HMCL's official `.exe` launcher reads `HMCL_JAVA_OPTS` and passes it to `java`, so starting the
/// launcher with that variable set is enough to get the patch loaded at startup. A plain `.jar` is
/// started directly with the current JDK instead.
final class LaunchSupport {

    private LaunchSupport() {
    }

    /// Result of starting HMCL.
    ///
    /// @param process     the started launcher process
    /// @param description how HMCL was started, for the user
    record Launch(Process process, String description) {
    }

    /// The `-javaagent:...` option pointing at this jar, or `null` when the location is unknown.
    static String agentOption() {
        Path self = NoIPCheckAgent.ownJar();
        if (self == null) {
            return null;
        }
        return "-javaagent:" + quoteIfNecessary(self.toAbsolutePath().toString());
    }

    /// Looks for `HMCL*.exe` (preferred) or `HMCL*.jar` next to this jar and in the current directory.
    static Path detectLauncher() {
        List<Path> directories = new ArrayList<>();

        Path self = NoIPCheckAgent.ownJar();
        if (self != null && self.getParent() != null) {
            directories.add(self.getParent());
        }
        directories.add(Paths.get("").toAbsolutePath());

        for (String pattern : List.of("HMCL*.exe", "HMCL*.jar")) {
            for (Path directory : directories) {
                Path found = firstMatch(directory, pattern);
                if (found != null) {
                    return found;
                }
            }
        }
        return null;
    }

    /// Starts HMCL with the agent attached.
    ///
    /// @param target        `HMCL*.exe` or `HMCL*.jar`
    /// @param inheritOutput whether HMCL's console output should be forwarded to the current process
    static Launch launch(Path target, boolean inheritOutput) throws IOException {
        Path file = target.toAbsolutePath().normalize();
        if (!Files.isRegularFile(file)) {
            throw new IOException("文件不存在: " + file);
        }

        String agent = agentOption();
        if (agent == null) {
            throw new IOException("无法定位补丁 jar");
        }

        String fileName = file.getFileName().toString().toLowerCase(Locale.ROOT);

        if (fileName.endsWith(".exe")) {
            String existing = System.getenv("HMCL_JAVA_OPTS");
            String options = existing == null || existing.isBlank()
                    ? agent
                    : agent + " " + existing;

            ProcessBuilder builder = new ProcessBuilder(file.toString());
            builder.environment().put("HMCL_JAVA_OPTS", options);
            builder.directory(file.getParent().toFile());
            configure(builder, inheritOutput);

            Process process = builder.start();
            return new Launch(process, "HMCL_JAVA_OPTS=" + options);
        }

        if (fileName.endsWith(".jar")) {
            Path java = findJava();
            ProcessBuilder builder = new ProcessBuilder(
                    java.toString(), agent, "-jar", file.toString());
            builder.directory(file.getParent().toFile());
            configure(builder, inheritOutput);

            Process process = builder.start();
            return new Launch(process, java + " " + agent + " -jar " + file.getFileName());
        }

        throw new IOException("不支持的文件类型: " + file.getFileName() + "（需要 .exe 或 .jar）");
    }

    /// Text the user can paste into a command prompt to start HMCL with the patch.
    static String startupCommand(Path target) {
        String agent = agentOption();
        if (agent == null) {
            return "";
        }
        if (target == null) {
            return "set HMCL_JAVA_OPTS=" + agent;
        }
        return "set HMCL_JAVA_OPTS=" + agent + System.lineSeparator()
                + "start \"\" \"" + target.toAbsolutePath() + "\"";
    }

    private static void configure(ProcessBuilder builder, boolean inheritOutput) {
        if (inheritOutput) {
            builder.inheritIO();
        } else {
            builder.redirectOutput(ProcessBuilder.Redirect.DISCARD);
            builder.redirectError(ProcessBuilder.Redirect.DISCARD);
        }
    }

    private static String quoteIfNecessary(String value) {
        return value.indexOf(' ') >= 0 ? '"' + value + '"' : value;
    }

    private static Path firstMatch(Path directory, String pattern) {
        if (!Files.isDirectory(directory)) {
            return null;
        }
        try (DirectoryStream<Path> stream = Files.newDirectoryStream(directory, pattern)) {
            for (Path candidate : stream) {
                if (Files.isRegularFile(candidate)) {
                    return candidate;
                }
            }
        } catch (IOException ignored) {
            // Ignore unreadable directories.
        }
        return null;
    }

    /// Returns `java` from the current runtime when it is 17+, otherwise `JAVA_HOME` or `PATH`.
    static Path findJava() {
        String executable = isWindows() ? "java.exe" : "java";

        if (Runtime.version().feature() >= 17) {
            Path current = Paths.get(System.getProperty("java.home"), "bin", executable);
            if (Files.isRegularFile(current)) {
                return current;
            }
        }

        String javaHome = System.getenv("JAVA_HOME");
        if (javaHome != null && !javaHome.isBlank()) {
            Path fromEnvironment = Paths.get(javaHome, "bin", executable);
            if (Files.isRegularFile(fromEnvironment)) {
                return fromEnvironment;
            }
        }

        return Paths.get(executable);
    }

    static boolean isWindows() {
        return System.getProperty("os.name", "").toLowerCase(Locale.ROOT).startsWith("windows");
    }
}
