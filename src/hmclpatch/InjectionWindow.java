/*
 * HMCLNoIPCheck - a hot-injection patch for HMCL 3.17.
 *
 * This file is released into the public domain.
 */
package hmclpatch;

import javax.swing.*;
import javax.swing.border.EmptyBorder;
import javax.swing.filechooser.FileNameExtensionFilter;
import java.awt.*;
import java.awt.datatransfer.StringSelection;
import java.awt.event.MouseAdapter;
import java.awt.event.MouseEvent;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;
import java.time.LocalTime;
import java.time.format.DateTimeFormatter;
import java.util.List;

/// The injection window.
///
/// Shows the running HMCL processes, injects the patch into the selected one, starts HMCL with the
/// agent already attached, and logs everything it does.
public final class InjectionWindow {

    private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("HH:mm:ss");

    private final JFrame frame = new JFrame("HMCLNoIPCheck 注入器 " + NoIPCheckAgent.VERSION);
    private final DefaultListModel<String[]> targets = new DefaultListModel<>();
    private final JList<String[]> targetList = new JList<>(targets);

    private final JTextArea status = new JTextArea();
    private final JLabel hint = new JLabel(" ");
    private final JLabel footer = new JLabel(" ");

    private final JButton refreshButton = new JButton("刷新列表");
    private final JButton injectButton = new JButton("注入选中进程");
    private final JButton launchButton = new JButton("启动 HMCL（带补丁）");
    private final JButton copyButton = new JButton("复制启动命令");
    private final JButton logButton = new JButton("打开日志");
    private final JButton closeButton = new JButton("关闭");

    /// Creates the window without showing it.
    public InjectionWindow() {
        buildUi();
    }

    /// Opens the window and keeps the JVM alive until it is closed.
    public static void open() {
        new InjectionWindow().display();
    }

    /// Makes the window visible and schedules the first process scan.
    public void display() {
        frame.setVisible(true);
        append("窗口已就绪，正在扫描可注入的 HMCL 进程 ...");
        SwingUtilities.invokeLater(this::refreshTargets);
    }

    // ---------------------------------------------------------------------------------------
    // ui construction
    // ---------------------------------------------------------------------------------------

    private void buildUi() {
        frame.setDefaultCloseOperation(WindowConstants.EXIT_ON_CLOSE);
        frame.setMinimumSize(new Dimension(760, 540));
        frame.setSize(800, 580);
        frame.setLocationRelativeTo(null);

        frame.add(createHeader(), BorderLayout.NORTH);
        frame.add(createBody(), BorderLayout.CENTER);
        frame.add(createFooter(), BorderLayout.SOUTH);

        injectButton.setFont(injectButton.getFont().deriveFont(Font.BOLD));
        injectButton.setEnabled(false);

        refreshButton.addActionListener(e -> refreshTargets());
        injectButton.addActionListener(e -> onInject());
        launchButton.addActionListener(e -> onLaunch());
        copyButton.addActionListener(e -> onCopyCommand());
        logButton.addActionListener(e -> onOpenLog());
        closeButton.addActionListener(e -> {
            frame.dispose();
            System.exit(0);
        });

        targetList.setSelectionMode(ListSelectionModel.SINGLE_SELECTION);
        targetList.setCellRenderer(new TargetRenderer());
        targetList.addMouseListener(new MouseAdapter() {
            @Override
            public void mouseClicked(MouseEvent e) {
                if (e.getClickCount() == 2 && targetList.getSelectedIndex() >= 0) {
                    onInject();
                }
            }
        });
    }

    private JPanel createHeader() {
        JPanel header = new JPanel(new BorderLayout(0, 3));
        header.setBorder(new EmptyBorder(14, 16, 10, 16));

        JLabel title = new JLabel("HMCLNoIPCheck 注入器");
        title.setFont(title.getFont().deriveFont(Font.BOLD, 18f));

        JLabel subtitle = new JLabel(
                "为运行中的 HMCL 热注入补丁，解除中国大陆 IP 的下载限制（目标："
                        + NoIPCheckAgent.TARGET_CLASS + "）");
        subtitle.setForeground(new Color(0x66, 0x66, 0x66));

        header.add(title, BorderLayout.NORTH);
        header.add(subtitle, BorderLayout.CENTER);
        return header;
    }

    private JComponent createBody() {
        JPanel body = new JPanel(new BorderLayout(0, 10));
        body.setBorder(new EmptyBorder(0, 14, 0, 14));
        body.add(createTargetPanel(), BorderLayout.NORTH);
        body.add(createStatusPanel(), BorderLayout.CENTER);
        return body;
    }

    private JPanel createTargetPanel() {
        JPanel panel = new JPanel(new BorderLayout(6, 6));
        panel.setBorder(BorderFactory.createTitledBorder("可注入的 HMCL 进程"));

        JPanel tools = new JPanel(new FlowLayout(FlowLayout.RIGHT, 6, 0));
        tools.setOpaque(false);
        tools.add(refreshButton);
        panel.add(tools, BorderLayout.NORTH);

        JScrollPane scroll = new JScrollPane(targetList);
        scroll.setPreferredSize(new Dimension(100, 160));
        panel.add(scroll, BorderLayout.CENTER);

        hint.setForeground(new Color(0x66, 0x66, 0x66));
        hint.setBorder(new EmptyBorder(2, 2, 0, 2));
        panel.add(hint, BorderLayout.SOUTH);

        return panel;
    }

    private JPanel createStatusPanel() {
        JPanel panel = new JPanel(new BorderLayout());
        panel.setBorder(BorderFactory.createTitledBorder("运行状态"));

        status.setEditable(false);
        status.setLineWrap(true);
        status.setWrapStyleWord(false);
        status.setBorder(new EmptyBorder(6, 8, 6, 8));
        status.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));

        panel.add(new JScrollPane(status), BorderLayout.CENTER);
        return panel;
    }

    private JPanel createFooter() {
        JPanel footerPanel = new JPanel(new BorderLayout());

        JPanel left = new JPanel(new FlowLayout(FlowLayout.LEFT, 8, 10));
        left.add(launchButton);
        left.add(injectButton);

        JPanel right = new JPanel(new FlowLayout(FlowLayout.RIGHT, 8, 10));
        right.add(copyButton);
        right.add(logButton);
        right.add(closeButton);

        footer.setForeground(new Color(0x88, 0x88, 0x88));
        footer.setText("补丁 " + NoIPCheckAgent.VERSION + " · Java " + System.getProperty("java.version"));

        footerPanel.add(left, BorderLayout.WEST);
        footerPanel.add(right, BorderLayout.EAST);
        footerPanel.add(footer, BorderLayout.SOUTH);
        footer.setBorder(new EmptyBorder(0, 16, 8, 16));

        return footerPanel;
    }

    // ---------------------------------------------------------------------------------------
    // actions
    // ---------------------------------------------------------------------------------------

    /// Rescans the attach registry for HMCL processes. Runs on the EDT, the attach registry is fast.
    void refreshTargets() {
        targets.clear();

        try {
            List<String[]> found = Injector.hmclTargets();
            for (String[] target : found) {
                targets.addElement(target);
            }

            injectButton.setEnabled(!found.isEmpty());
            if (found.isEmpty()) {
                hint.setText("没有检测到正在运行的 HMCL —— 可以先用下方「启动 HMCL（带补丁）」，或直接双击 HMCL 启动器");
                append("扫描完成：没有找到正在运行的 HMCL");
            } else {
                hint.setText("共 " + found.size() + " 个可注入进程，选中后点击「注入选中进程」（也可以双击列表项）");
                append("扫描完成：发现 " + found.size() + " 个 HMCL 进程");
            }
        } catch (Throwable e) {
            injectButton.setEnabled(false);
            hint.setText("无法读取进程列表：" + e);
            append("无法读取进程列表：" + e);
            append("      请使用完整 JDK 运行本窗口（需要 jdk.attach 模块），或改用 HMCL_JAVA_OPTS 启动期注入。");
        }
    }

    /// Injects the patch into `pid` and writes the outcome into the status box.
    ///
    /// This blocks until the attach finished; callers on the EDT should use [#onInject] instead.
    void injectNow(long pid) {
        append("正在向 PID " + pid + " 注入补丁 ...");

        Injector.Result result = Injector.inject(pid);
        if (result.ok()) {
            append("[成功] PID " + pid + " 已注入");
            append("        RegionUtils 已加载: " + orDefault(result.value("loaded"), "?"));
            append("        retransform: " + orDefault(result.value("retransform"), "不需要（类尚未加载）"));
            append("        字节码已替换: " + orDefault(result.value("transformed"), "false"));
            append("        若注入前已经打开过下载页面，后退再进入一次即可恢复。");
            append("        重启 HMCL 后补丁失效；想每次生效请用「启动 HMCL（带补丁）」。");
        } else {
            append("[失败] PID " + pid + " 注入未成功");
            if (result.error() != null) {
                append("        原因: " + result.error());
            }
            for (String line : result.lines()) {
                append("        " + line);
            }
            append("        常见原因：HMCL 以管理员权限运行、当前不是完整 JDK、进程已退出。");
        }
        append("");
    }

    private void onInject() {
        String[] selected = targetList.getSelectedValue();
        if (selected == null) {
            append("请先在列表中选择一个 HMCL 进程");
            return;
        }

        long pid;
        try {
            pid = Long.parseLong(selected[0]);
        } catch (NumberFormatException e) {
            append("进程号无效: " + selected[0]);
            return;
        }

        setBusy(true);
        Thread worker = new Thread(() -> {
            try {
                injectNow(pid);
            } finally {
                SwingUtilities.invokeLater(() -> {
                    setBusy(false);
                    refreshTargets();
                });
            }
        }, "hmcl-noipcheck-inject");
        worker.setDaemon(true);
        worker.start();
    }

    private void onLaunch() {
        Path target = LaunchSupport.detectLauncher();

        if (target == null) {
            JFileChooser chooser = new JFileChooser(Paths.get("").toAbsolutePath().toFile());
            chooser.setDialogTitle("选择 HMCL 启动器（HMCL*.exe 或 HMCL*.jar）");
            chooser.setFileFilter(new FileNameExtensionFilter("HMCL 启动器 (*.exe, *.jar)", "exe", "jar"));
            if (chooser.showOpenDialog(frame) != JFileChooser.APPROVE_OPTION) {
                return;
            }
            target = chooser.getSelectedFile().toPath();
        }

        try {
            LaunchSupport.Launch launch = LaunchSupport.launch(target, false);
            append("已启动 " + target.getFileName() + "（PID " + launch.process().pid() + "）");
            append("        " + launch.description());
            append("        补丁会在 JVM 启动时自动加载，不需要再手动注入。");
        } catch (Throwable e) {
            append("[失败] 启动 HMCL 失败: " + e);
            append("        可以手动启动 HMCL，然后点「刷新列表」再注入。");
        }
    }

    private void onCopyCommand() {
        Path target = LaunchSupport.detectLauncher();
        String command = LaunchSupport.startupCommand(target);
        if (command.isEmpty()) {
            append("无法生成启动命令（定位不到补丁 jar）");
            return;
        }

        try {
            Toolkit.getDefaultToolkit().getSystemClipboard()
                    .setContents(new StringSelection(command), null);
            append("启动命令已复制到剪贴板：");
            for (String line : command.split("\\R")) {
                append("        " + line);
            }
            if (target == null) {
                append("        把上面两行里的 exe 路径换成你自己的 HMCL 启动器路径即可。");
            }
        } catch (Throwable e) {
            append("复制到剪贴板失败: " + e);
            append("        命令：" + command.replace("\n", " && "));
        }
    }

    private void onOpenLog() {
        Path log = PatchLog.logFile();
        if (log == null || !Files.exists(log)) {
            append("补丁日志还没有生成（会在 HMCL 加载补丁后写入）");
            return;
        }

        try {
            if (Desktop.isDesktopSupported()
                    && Desktop.getDesktop().isSupported(Desktop.Action.OPEN)) {
                Desktop.getDesktop().open(log.toFile());
                return;
            }
        } catch (Throwable ignored) {
            // Fall back to an internal viewer.
        }

        showLogDialog(log);
    }

    private void showLogDialog(Path log) {
        String content;
        try {
            content = Files.readString(log, StandardCharsets.UTF_8);
        } catch (IOException e) {
            content = "读取失败: " + e;
        }

        JTextArea area = new JTextArea(content);
        area.setEditable(false);
        area.setFont(new Font(Font.DIALOG, Font.PLAIN, 12));

        JScrollPane scroll = new JScrollPane(area);
        scroll.setPreferredSize(new Dimension(720, 420));

        JOptionPane.showMessageDialog(frame, scroll, "补丁日志 - " + log, JOptionPane.PLAIN_MESSAGE);
    }

    private void setBusy(boolean busy) {
        injectButton.setEnabled(!busy);
        refreshButton.setEnabled(!busy);
        launchButton.setEnabled(!busy);
        targetList.setEnabled(!busy);
    }

    // ---------------------------------------------------------------------------------------
    // helpers
    // ---------------------------------------------------------------------------------------

    private void append(String message) {
        String line = "[" + LocalTime.now().format(TIME) + "] " + message + System.lineSeparator();

        Runnable task = () -> {
            status.append(line);
            status.setCaretPosition(status.getDocument().getLength());
        };

        if (SwingUtilities.isEventDispatchThread()) {
            task.run();
        } else {
            try {
                SwingUtilities.invokeAndWait(task);
            } catch (Throwable ignored) {
                SwingUtilities.invokeLater(task);
            }
        }
    }

    private static String orDefault(String value, String fallback) {
        return value == null ? fallback : value;
    }

    // ---------------------------------------------------------------------------------------
    // package-private test hooks
    // ---------------------------------------------------------------------------------------

    JFrame frame() {
        return frame;
    }

    int targetCount() {
        return targets.size();
    }

    void selectFirstTarget() {
        if (!targets.isEmpty()) {
            targetList.setSelectedIndex(0);
        }
    }

    String statusText() {
        return status.getText();
    }

    boolean injectEnabled() {
        return injectButton.isEnabled();
    }

    /// Renders a process entry as `PID  displayName`.
    private static final class TargetRenderer extends DefaultListCellRenderer {
        @Override
        public Component getListCellRendererComponent(JList<?> list, Object value, int index,
                                                      boolean selected, boolean focus) {
            super.getListCellRendererComponent(list, value, index, selected, focus);

            if (value instanceof String[] target) {
                String color = selected ? "#ffffff" : "#666666";
                setText("<html><b>" + escape(target[0]) + "</b>&nbsp;&nbsp;<font color='" + color + "'>"
                        + escape(target[1]) + "</font></html>");
                setBorder(new EmptyBorder(6, 8, 6, 8));
            }

            return this;
        }

        private static String escape(String value) {
            return value.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;");
        }
    }
}
