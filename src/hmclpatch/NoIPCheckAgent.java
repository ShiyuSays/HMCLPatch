/*
 * HMCLNoIPCheck - a hot-injection patch for HMCL 3.17.
 *
 * This file is released into the public domain.
 */
package hmclpatch;

import java.io.InputStream;
import java.lang.instrument.ClassFileTransformer;
import java.lang.instrument.Instrumentation;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.security.CodeSource;
import java.security.ProtectionDomain;
import java.util.ArrayList;
import java.util.List;

/// The java agent that removes HMCL's IP based download restriction.
///
/// HMCL 3.17 refuses to download anything when `RegionUtils.isIsChinaMainlandNetwork()` reports that
/// the current network is located in China mainland: `VersionsPage` and `DownloadListPage` are both
/// wrapped in a warning and set to `disabled`. The check itself is a plain HTTP request to
/// `https://ipwho.is`.
///
/// This agent replaces the bytecode of `org.jackhuang.hmcl.util.RegionUtils` with a patched copy whose
/// `isIsChinaMainlandNetwork()` unconditionally returns `false`, therefore:
///
/// - the `download.china` warning is never shown;
/// - the download pages stay enabled;
/// - `https://ipwho.is` is never contacted.
///
/// `isIsChinaMainlandMachine()` is left untouched on purpose: it selects the BMCLAPI mirror and gates
/// Terracotta, so changing it would make downloads slower.
///
/// The class can be injected in two ways:
///
/// 1. `-javaagent:HMCLNoIPCheck.jar` -- the transformer rewrites the class while it is loaded;
/// 2. `java -jar HMCLNoIPCheck.jar --attach` -- the class is already loaded, so it is retransformed
///    in place (or, if retransformation is unavailable, the cached result field is forced to `false`).
public final class NoIPCheckAgent {

    /// Version of the patch, also written into the jar manifest.
    public static final String VERSION = "1.1.0";

    /// Binary name of the class that is patched.
    public static final String TARGET_CLASS = "org.jackhuang.hmcl.util.RegionUtils";

    /// Internal (slash separated) name of the class that is patched.
    private static final String TARGET_INTERNAL = "org/jackhuang/hmcl/util/RegionUtils";

    /// Resource that stores the patched class file.
    ///
    /// The patched bytes are deliberately stored outside of their canonical path: `-javaagent` jars are
    /// appended to the system class path, and a canonical copy could be picked up instead of the
    /// original class, which would make the patch order dependent.
    private static final String PATCH_RESOURCE = "/patch/org/jackhuang/hmcl/util/RegionUtils.class";

    /// Name of the field that caches the result of the IP lookup.
    private static final String CACHED_RESULT_FIELD = "isChinaMainlandNetwork";

    private static volatile byte[] patchedClassBytes;

    /// Whether the transformer already rewrote the target class.
    private static volatile boolean transformed;

    private NoIPCheckAgent() {
    }

    /// Entry point used by `-javaagent:HMCLNoIPCheck.jar`.
    public static void premain(String args, Instrumentation instrumentation) {
        PatchLog.info("HMCLNoIPCheck " + VERSION + " 启动期注入（premain）");

        try {
            instrumentation.addTransformer(new RegionUtilsTransformer(), true);
            PatchLog.info("已注册 RegionUtils 转换器，等待 HMCL 加载该类");
        } catch (Throwable e) {
            PatchLog.error("注册转换器失败", e);
        }
    }

    /// Entry point used by `VirtualMachine.loadAgent`.
    ///
    /// @param args path of the file that receives the result, may be empty
    public static void agentmain(String args, Instrumentation instrumentation) {
        PatchLog.info("HMCLNoIPCheck " + VERSION + " 运行期热注入（agentmain）");

        List<String> report = new ArrayList<>();
        try {
            report.add("version=" + VERSION);
            instrumentation.addTransformer(new RegionUtilsTransformer(), true);
            report.add("transformer=on");

            Class<?> target = findLoadedClass(instrumentation);
            if (target == null) {
                // The class has not been loaded yet, the transformer will handle it later.
                report.add("loaded=false");
                report.add("mode=load-time");
                report.add("status=ok");
                PatchLog.info("RegionUtils 尚未加载，转换器将在其加载时生效");
            } else {
                report.add("loaded=true");
                boolean retransformed = retransform(instrumentation, target, report);
                if (!retransformed) {
                    report.add("field_patched=" + forceCachedResult(target, report));
                }
                report.add("transformed=" + transformed);
                report.add("status=ok");
            }
        } catch (Throwable e) {
            report.add("status=failed");
            report.add("error=" + e);
            PatchLog.error("热注入失败", e);
        }

        if (args != null && !args.isBlank()) {
            try {
                Files.write(Paths.get(args.trim()), report, StandardCharsets.UTF_8);
            } catch (Throwable e) {
                PatchLog.error("写入结果文件失败", e);
            }
        }
    }

    /// Returns the location of the jar that contains this agent, or `null` when unknown.
    public static Path ownJar() {
        try {
            ProtectionDomain domain = NoIPCheckAgent.class.getProtectionDomain();
            CodeSource source = domain == null ? null : domain.getCodeSource();
            if (source == null || source.getLocation() == null) {
                return null;
            }
            return Paths.get(source.getLocation().toURI());
        } catch (Throwable e) {
            return null;
        }
    }

    private static Class<?> findLoadedClass(Instrumentation instrumentation) {
        for (Class<?> loaded : instrumentation.getAllLoadedClasses()) {
            if (TARGET_CLASS.equals(loaded.getName())) {
                return loaded;
            }
        }
        return null;
    }

    private static boolean retransform(Instrumentation instrumentation, Class<?> target, List<String> report) {
        if (!instrumentation.isModifiableClass(target)) {
            report.add("retransform=not_modifiable");
            return false;
        }
        if (!instrumentation.isRetransformClassesSupported()) {
            report.add("retransform=unsupported");
            return false;
        }

        try {
            instrumentation.retransformClasses(target);
            report.add("retransform=ok");
            return true;
        } catch (Throwable e) {
            report.add("retransform=failed");
            report.add("retransform_error=" + e);
            PatchLog.error("retransformClasses 失败，退回缓存字段注入", e);
            return false;
        }
    }

    /// Forces the cached IP lookup result to `false`.
    ///
    /// This fallback is only used when the class cannot be retransformed. It is weaker than replacing
    /// the bytecode (it cannot remove the already rendered warning), but it makes every later call to
    /// `isIsChinaMainlandNetwork()` return `false` without touching the network.
    private static boolean forceCachedResult(Class<?> target, List<String> report) {
        try {
            Field field = target.getDeclaredField(CACHED_RESULT_FIELD);
            field.setAccessible(true);
            field.set(null, Boolean.FALSE);
            PatchLog.info("已将 RegionUtils." + CACHED_RESULT_FIELD + " 强制置为 false");
            return true;
        } catch (Throwable e) {
            report.add("field_error=" + e);
            PatchLog.error("写入缓存字段失败", e);
            return false;
        }
    }

    /// Reads the patched class file from the agent jar. The bytes are cached after the first access.
    private static byte[] patchedBytes() {
        byte[] cached = patchedClassBytes;
        if (cached != null) {
            return cached;
        }

        synchronized (NoIPCheckAgent.class) {
            if (patchedClassBytes != null) {
                return patchedClassBytes;
            }

            try (InputStream in = NoIPCheckAgent.class.getResourceAsStream(PATCH_RESOURCE)) {
                if (in == null) {
                    PatchLog.error("补丁字节码资源缺失: " + PATCH_RESOURCE, null);
                    return null;
                }
                byte[] bytes = in.readAllBytes();
                patchedClassBytes = bytes;
                return bytes;
            } catch (Throwable e) {
                PatchLog.error("读取补丁字节码失败", e);
                return null;
            }
        }
    }

    /// The transformer that swaps `RegionUtils` with the patched version.
    private static final class RegionUtilsTransformer implements ClassFileTransformer {
        @Override
        public byte[] transform(ClassLoader loader, String className, Class<?> classBeingRedefined,
                                ProtectionDomain protectionDomain, byte[] classfileBuffer) {
            if (!TARGET_INTERNAL.equals(className)) {
                return null;
            }

            byte[] patched = patchedBytes();
            if (patched == null) {
                return null;
            }

            transformed = true;
            PatchLog.info("已替换 " + TARGET_CLASS
                    + (classBeingRedefined != null ? "（运行期 retransform）" : "（类加载期）")
                    + "，IP 下载限制已关闭");
            return patched;
        }
    }
}
