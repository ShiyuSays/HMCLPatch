HMCLNoIPCheck 1.1.0
===================

移除 HMCL 3.17（本补丁针对 3.17.0.362 验证）的 IP 地区校验，解除
"根据相关法律法规，HMCL 在中国大陆地区暂不支持下载游戏相关内容" 的下载封锁。


一、原理
--------
HMCL 里唯一的 IP 校验在：

    org.jackhuang.hmcl.util.RegionUtils#isIsChinaMainlandNetwork()

它请求 https://ipwho.is 判断当前网络是否在中国大陆；返回 true 时

    org.jackhuang.hmcl.ui.download.VersionsPage        （安装新游戏页面）
    org.jackhuang.hmcl.ui.instances.DownloadListPage   （资源/整合包下载页面）

会插入 download.china 警告并把整个页面 setDisable(true)，于是无法下载。

本补丁是一个 Java Agent（Premain-Class / Agent-Class），在类加载阶段把
RegionUtils 的字节码替换成打补丁的版本，使 isIsChinaMainlandNetwork()
直接返回 false：

    * 下载页面不再被禁用，也不再显示 download.china 提示；
    * 不再请求 https://ipwho.is，本机不会因为"IP 在中国大陆"被拦截；
    * isIsChinaMainlandMachine() 保持原样，因此 BMCLAPI 镜像源选择和
      Terracotta 联机不受影响；
    * 不修改 HMCL 的任何文件，不破坏 HMCL 的签名与自动更新校验。


二、文件
--------
    HMCLNoIPCheck.jar              补丁本体（Java Agent + 图形注入窗口）
    start-window.bat               打开图形注入窗口（自动挑 Java 17+）
    start-hmcl-patched.bat         带补丁启动 HMCL 的批处理（可选）
    README.txt                     本文件

运行后会在 HMCLNoIPCheck.jar 所在目录生成日志：

    HMCLNoIPCheck.log


三、用法
--------
方式 0（图形窗口，最直观）
    双击 start-window.bat，会打开「HMCLNoIPCheck 注入器」：

        · 列表里选中正在运行的 HMCL，点「注入选中进程」——立即生效，不用重启
          （双击列表项也可以）；如果下载页已经开着，后退再进来一次即可；
        · 点「启动 HMCL（带补丁）」——用 HMCL_JAVA_OPTS 方式启动，长期生效；
        · 「复制启动命令」把命令行写进剪贴板，「打开日志」直接看补丁日志。

    窗口本身需要 Java 17+，start-window.bat 会自动在 JAVA_HOME、
    C:\Program Files\Java 等常见位置里找。直接双击 jar 只有在系统默认
    Java 是 17+ 时才会开窗口；默认是 Java 8 时请用这个 bat。

    「注入选中进程」是热注入：只对当前这次运行有效，HMCL 重启后失效。
    想每次都生效，请用下面方式 1 或窗口里的「启动 HMCL（带补丁）」。

方式 1（启动期注入，不改动 HMCL）
    把 HMCLNoIPCheck.jar 放到任意固定目录，然后新建一个批处理放在
    HMCL-3.17.0.362.exe 旁边：

        @echo off
        set HMCL_JAVA_OPTS=-javaagent:"C:\path\to\HMCLNoIPCheck.jar"
        start "" HMCL-3.17.0.362.exe

    双击这个批处理即可。HMCL 官方的 exe 启动器会读取 HMCL_JAVA_OPTS，
    并把它作为 JVM 参数传给 java。

    也可以只在本机环境变量里永久设置 HMCL_JAVA_OPTS
    （系统属性 -> 环境变量 -> 新建用户变量），之后直接双击 exe 就行。

方式 2（一条命令启动）
        java -jar HMCLNoIPCheck.jar --run "C:\path\to\HMCL-3.17.0.362.exe"
    会自动带上 -javaagent 启动 HMCL；不带参数时在当前目录里找 HMCL*.exe。

方式 3（命令行注入已经运行的 HMCL）
        java -jar HMCLNoIPCheck.jar --attach
    自动寻找正在运行的 HMCL 进程；也可以用 --list 先看 PID，再
        java -jar HMCLNoIPCheck.jar --attach 12345
    要求用 JDK（含 jdk.attach 模块）运行，且与 HMCL 同一用户。


四、如何确认生效
----------------
1. HMCLNoIPCheck.log 里出现：

       [INFO] 已替换 org.jackhuang.hmcl.util.RegionUtils（类加载期），IP 下载限制已关闭

2. HMCL 里进入"安装新游戏"，不再出现红色警告，版本列表可以直接下载。

3. 日志里不会再出现 HMCL 的 "Fetching network information"（即不再请求 ipwho.is）。


五、恢复原状
------------
删除 HMCL_JAVA_OPTS 里的 -javaagent 参数（或删掉那个环境变量 / 批处理）即可，
HMCL 本身没有被修改过，不需要还原任何文件。热注入的进程重启后自动失效。


六、兼容性
----------
* 需要 HMCL 3.17（类名/方法名与 3.17.0.362 一致）；
* 需要 Java 17+（HMCL 3.17 本身的要求），补丁字节码为 Java 17（class 61）；
* 补丁只替换 RegionUtils 一个类，字节码成员与原版完全一致，
  所以 -javaagent 启动期注入和运行期 retransform 都可以用；
* 「注入选中进程」需要完整 JDK（jdk.attach 模块）；JRE 下用不了 attach，
  但用 HMCL_JAVA_OPTS 启动期注入照样有效。


七、免责声明
------------
本补丁仅移除启动器自身的地区下载限制，不提供、不修改任何游戏内容，
请自行遵守当地法律法规与相关服务条款。使用风险自负。
