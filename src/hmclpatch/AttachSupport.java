/*
 * HMCLNoIPCheck - a hot-injection patch for HMCL 3.17.
 *
 * This file is released into the public domain.
 */
package hmclpatch;

import com.sun.tools.attach.VirtualMachine;
import com.sun.tools.attach.VirtualMachineDescriptor;

import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;

/// Thin wrapper around the JDK attach API.
///
/// Everything that touches `com.sun.tools.attach` lives in this file so that a JRE without the
/// `jdk.attach` module never fails to load [Main]: the references are resolved on first use and a
/// `LinkageError` surfaces as a normal exception that the caller can handle.
final class AttachSupport {

    private AttachSupport() {
    }

    /// Returns `{pid, displayName}` pairs for every JVM that accepts an attach request.
    ///
    /// On Windows `ProcessHandle#info().commandLine()` is empty for processes that are not children of
    /// the current JVM, while the attach registry still knows the main class / jar of every HotSpot VM.
    /// That makes this list the only reliable way to find a running HMCL here.
    static List<String[]> list() {
        List<String[]> result = new ArrayList<>();

        for (VirtualMachineDescriptor descriptor : VirtualMachine.list()) {
            String id = descriptor.id();
            String name = descriptor.displayName();
            if (id != null && id.matches("\\d+")) {
                result.add(new String[]{id, name == null ? "" : name});
            }
        }

        return result;
    }

    /// Loads this agent into the JVM that owns `pid`.
    ///
    /// The agent writes its report into `resultFile`, which allows the caller to show what happened
    /// inside the target VM.
    static void attach(Path agentJar, long pid, Path resultFile) throws Exception {
        VirtualMachine vm = VirtualMachine.attach(Long.toString(pid));
        try {
            vm.loadAgent(agentJar.toAbsolutePath().toString(), resultFile.toAbsolutePath().toString());
        } finally {
            vm.detach();
        }
    }
}
