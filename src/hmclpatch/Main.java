/*
 * HMCLNoIPCheck - a hot-injection patch for HMCL 3.17.
 *
 * This file is released into the public domain.
 */
package hmclpatch;

import java.awt.GraphicsEnvironment;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.util.List;
import java.util.Locale;

/// Command line front end of the patch.
///
/// Running the jar without arguments opens the injection window ([InjectionWindow]); the command line
/// modes are kept for scripting:
///
/// - `java -jar HMCLNoIPCheck.jar` -- open the injection window;
/// - `java -jar HMCLNoIPCheck.jar --attach [pid]` -- inject into a running HMCL process;
/// - `java -jar HMCLNoIPCheck.jar --list` -- list the detected HMCL processes;
/// - `java -jar HMCLNoIPCheck.jar --run [HMCL.exe|HMCL.jar]` -- start HMCL with `-javaagent`;
/// - `java -jar HMCLNoIPCheck.jar --help` -- print this help.
///
/// The patch is usually used the other way round though: put
/// `-javaagent:C:\path\to\HMCLNoIPCheck.jar` into `HMCL_JAVA_OPTS` and start HMCL normally.
public final class Main {

    private static final String INDENT = "    ";

    private Main() {
    }

    public static void main(String[] args) {
        try {
            dispatch(args);
        } catch (Throwable e) {
            System.out.println("执行失败: " + e);
            e.printStackTrace(System.out);
        }
    }

    private static void dispatch(String[] args) throws Exception {
        if (args.length == 0) {
            openWindow();
            return;
        }

        String command = normalize(args[0]);
        String argument = args.length > 1 ? args[1] : null;

        switch (command) {
            case "gui" -> openWindow();
            case "help" -> printHelp();
            case "version" -> printVersion();
            case "list" -> list();
            case "attach" -> attach(argument);
            case "run" -> run(argument);
            default -> {
                System.out.println("未知参数: " + args[0]);
                System.out.println();
                printHelp();
            }
        }
    }

    private static String normalize(String raw) {
        String value = raw.trim().toLowerCase(Locale.ROOT);
        while (value.startsWith("-") || value.startsWith("/")) {
            value = value.substring(1);
        }
        return switch (value) {
            case "help", "h", "?" -> "help";
            case "version", "v" -> "version";
            case "list", "ls", "ps" -> "list";
            case "attach", "inject", "hot" -> "attach";
            case "run", "launch", "start" -> "run";
            case "gui", "window", "ui" -> "gui";
            default -> value;
        };
    }

    // ---------------------------------------------------------------------------------------
    // window
    // ---------------------------------------------------------------------------------------

    private static void openWindow() {
        if (GraphicsEnvironment.isHeadless()) {
            System.out.println("当前环境没有图形界面，改用命令行模式。");
            System.out.println();
            printHelp();
            return;
        }

        try {
            InjectionWindow.open();
        } catch (Throwable e) {
            System.out.println("无法打开注入窗口: " + e);
            e.printStackTrace(System.out);
            System.out.println();
            printHelp();
        }
    }

    // ---------------------------------------------------------------------------------------
    // console
    // ---------------------------------------------------------------------------------------

    private static void printVersion() {
        System.out.println("HMCLNoIPCheck " + NoIPCheckAgent.VERSION);
        System.out.println("补丁目标: " + NoIPCheckAgent.TARGET_CLASS + "#isIsChinaMainlandNetwork()");
    }

    private static void printHelp() {
        System.out.println("HMCLNoIPCheck " + NoIPCheckAgent.VERSION);
        System.out.println("移除 HMCL 3.17 的 IP 地区校验（中国大陆 IP 会被禁止下载游戏/模组）。");
        System.out.println();
        System.out.println("图形注入窗口（不带参数运行，或 --gui）：");
        System.out.println(INDENT + "java -jar HMCLNoIPCheck.jar");
        System.out.println(INDENT + "列表里选一个 HMCL 进程点「注入选中进程」，或点「启动 HMCL（带补丁）」。");
        System.out.println();
        System.out.println("启动期热注入（推荐长期使用，不用改动 HMCL 本体）：");
        System.out.println(INDENT + "set HMCL_JAVA_OPTS=-javaagent:\"" + agentJarForDisplay() + "\"");
        System.out.println(INDENT + "HMCL-3.17.0.362.exe");
        System.out.println(INDENT + "HMCL 官方 exe 启动器会读取 HMCL_JAVA_OPTS，并把它传给 java。");
        System.out.println();
        System.out.println("其他命令行用法：");
        System.out.println(INDENT + "java -jar HMCLNoIPCheck.jar --run [HMCL.exe|HMCL.jar]");
        System.out.println(INDENT + "    自动带上 -javaagent 启动 HMCL（不带路径时在当前目录里找）。");
        System.out.println(INDENT + "java -jar HMCLNoIPCheck.jar --attach [pid]");
        System.out.println(INDENT + "    注入已经运行的 HMCL 进程（需要 JDK 且同为当前用户）。");
        System.out.println(INDENT + "java -jar HMCLNoIPCheck.jar --list");
        System.out.println(INDENT + "    列出当前可注入的 HMCL 进程。");
        System.out.println();
        System.out.println("注入成功后会关闭“根据相关法律法规……暂不支持下载”的提示，并让下载页面恢复可用。");
        System.out.println("运行日志写在补丁 jar 同目录的 HMCLNoIPCheck.log。");
    }

    private static String agentJarForDisplay() {
        Path self = NoIPCheckAgent.ownJar();
        return self == null ? "C:\\path\\to\\HMCLNoIPCheck.jar" : self.toString();
    }

    private static void list() {
        List<String[]> all;
        try {
            all = AttachSupport.list();
        } catch (Throwable e) {
            printAttachUnavailable(e);
            return;
        }

        List<String[]> hmcl = Injector.filterHmcl(all);
        if (hmcl.isEmpty()) {
            System.out.println("没有找到正在运行的 HMCL 进程。");
            if (!all.isEmpty()) {
                System.out.println("当前可注入的 Java 进程：");
                printTargets(all);
            }
            return;
        }

        System.out.println("检测到 " + hmcl.size() + " 个 HMCL 进程：");
        printTargets(hmcl);
    }

    private static void attach(String pidArgument) {
        long pid;

        if (pidArgument != null && !pidArgument.isBlank()) {
            try {
                pid = Long.parseLong(pidArgument.trim());
            } catch (NumberFormatException e) {
                System.out.println("无效的进程号: " + pidArgument);
                return;
            }
        } else {
            List<String[]> candidates;
            try {
                candidates = Injector.hmclTargets();
            } catch (Throwable e) {
                printAttachUnavailable(e);
                return;
            }

            if (candidates.isEmpty()) {
                System.out.println("没有找到正在运行的 HMCL 进程。");
                System.out.println("请先启动 HMCL，或者用 --attach <pid> 指定进程号。");
                return;
            }
            if (candidates.size() > 1) {
                System.out.println("检测到多个候选进程，请用 --attach <pid> 指定：");
                printTargets(candidates);
                return;
            }

            pid = Long.parseLong(candidates.get(0)[0]);
            System.out.println("选中进程 " + pid + "（" + candidates.get(0)[1] + "）");
        }

        System.out.println("正在注入 PID " + pid + " ...");
        Injector.Result result = Injector.inject(pid);

        if (!result.ok()) {
            System.out.println("注入未生效");
            if (result.error() != null) {
                System.out.println(INDENT + "原因: " + result.error());
            }
            for (String line : result.lines()) {
                System.out.println(INDENT + line);
            }
            System.out.println("可能的原因：");
            System.out.println(INDENT + "1. 当前 java 不是完整 JDK，缺少 jdk.attach 模块；");
            System.out.println(INDENT + "2. HMCL 以管理员身份运行，而当前程序没有；");
            System.out.println(INDENT + "3. 进程号不存在、已退出，或不属于当前用户。");
            System.out.println("启动期注入永远可用：set HMCL_JAVA_OPTS=-javaagent:\"" + agentJarForDisplay() + "\"");
            return;
        }

        System.out.println("[OK] 注入成功");
        System.out.println(INDENT + "RegionUtils 是否已加载: " + orDefault(result.value("loaded"), "?"));
        System.out.println(INDENT + "retransform: " + orDefault(result.value("retransform"), "不需要（类尚未加载）"));
        System.out.println(INDENT + "缓存字段兜底: " + orDefault(result.value("field_patched"), "不需要"));
        System.out.println(INDENT + "字节码是否已被替换: " + orDefault(result.value("transformed"), "false"));

        if ("true".equals(result.value("loaded"))) {
            System.out.println("提示：如果注入前已经打开过下载页面，请后退再进入一次即可恢复。");
        }
        System.out.println("下次重启 HMCL 后补丁会失效，需要每次生效请用 HMCL_JAVA_OPTS 启动期注入。");
    }

    private static void printAttachUnavailable(Throwable e) {
        System.out.println("无法读取可注入的 Java 进程列表: " + e);
        System.out.println(INDENT + "当前 java 可能不是完整 JDK（缺少 jdk.attach 模块）。");
        System.out.println(INDENT + "可以用 --attach <pid> 直接指定进程号，或者改用 HMCL_JAVA_OPTS 启动期注入。");
    }

    private static void printTargets(List<String[]> targets) {
        for (String[] target : targets) {
            System.out.println(INDENT + target[0] + "  " + (target[1].isEmpty() ? "<unknown>" : target[1]));
        }
    }

    private static void run(String targetArgument) {
        Path target = targetArgument != null && !targetArgument.isBlank()
                ? Paths.get(targetArgument.trim())
                : LaunchSupport.detectLauncher();

        if (target == null) {
            System.out.println("当前目录下没有找到 HMCL 启动器。");
            System.out.println("用法: java -jar HMCLNoIPCheck.jar --run \"C:\\path\\to\\HMCL-3.17.0.362.exe\"");
            return;
        }

        try {
            LaunchSupport.Launch launch = LaunchSupport.launch(target, true);
            System.out.println("已启动 " + target.getFileName() + "（PID " + launch.process().pid() + "）");
            System.out.println(INDENT + launch.description());
        } catch (Throwable e) {
            System.out.println("启动失败: " + e);
        }
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }
}
