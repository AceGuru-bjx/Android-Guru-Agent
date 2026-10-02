package com.apex.agent.platform.terminal.proot

import com.apex.agent.platform.terminal.linux.CpuArchitecture
import com.apex.agent.platform.terminal.linux.LinuxDistribution
import com.apex.agent.platform.terminal.linux.RootfsDescriptor
import com.apex.agent.platform.terminal.linux.RootfsProvider
import com.apex.agent.platform.terminal.linux.RootfsVerification
import com.apex.agent.platform.terminal.runtime.SessionSpawnRequest
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import com.apex.agent.platform.terminal.workspace.GuestUserHome
import com.apex.agent.platform.terminal.workspace.LinuxWorkspaceManager
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** 共享夹具：静态 rootfs 描述（外层类与嵌套 provider 均可用）。 */
private fun dialectRootfs() = RootfsDescriptor(
    id = "ubuntu-24.04-arm64", distribution = LinuxDistribution.UBUNTU,
    version = "24.04", architecture = CpuArchitecture.ARM64,
    location = AbsolutePath("/fake/rootfs"), sizeBytes = null, checksum = null, readOnly = false
)

/**
 * T91（D5）：PRoot 方言自适应契约测试 ——「版本探针参与 argv 决策，根除静默差异」。
 *
 * 覆盖四层：
 *  1. **探针 → 方言映射**（[NativeLibraryPRootBinaryProvider.dialectFor]）：
 *     探针 true/false/null 三态 + 记忆化（探针只 exec 一次）；
 *  2. **方言 → argv 形状**（[PRootCommandBuilderImpl]）：TERMUX_COMPAT 发
 *     `--kill-on-exit` + `--`；UPSTREAM 两者皆省（env trampoline 原样保留）；
 *  3. **契约双方言形状**（[PRootArgvContract]）：黑名单扫描边界与 trampoline
 *     判定在两种形状下都正确；
 *  4. **端到端**（[LinuxPRootBackend.prepare]）：provider verify 结果的方言
 *     真实参与 argv（此前只作门禁被丢弃 —— 静默差异的直接根源）。
 *
 * 真实 proot 执行（上游方言 argv 可跑）见 ProotExecutorProotSmokeTest /
 * UbuntuRootfsEndToEndIntegrationTest / UbuntuTerminalRuntimeWiringTest
 * （T91 起三者的测试内适配层已删除，生产 argv 原样执行）。
 */
class PRootDialectAdaptationTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun launchRequest(killOnExit: Boolean = true) = PRootLaunchRequest(
        rootfs = dialectRootfs(), executable = "/bin/bash", arguments = listOf("-i"),
        environment = linkedMapOf("TERM" to "xterm-256color", "PATH" to "/usr/bin:/bin"),
        fakeRoot = true, killOnExit = killOnExit
    )

    // ─── 1. 探针 → 方言映射（三态 + 记忆化） ───

    private fun elfDir(): File = tmp.newFolder().apply {
        // 真实 Termux arm64 libproot.so 前 20 字节（ELF header，e_machine=183）
        File(this, "libproot.so").writeBytes(
            byteArrayOf(0x7f, 0x45, 0x4c, 0x46, 0x02, 0x01, 0x01, 0x00, 0x00, 0x00,
                0x00, 0x00, 0x00, 0x00, 0x00, 0x00, 0x02, 0x00, 0xB7.toByte(), 0x00)
        )
    }

    private fun hostEnvAt(dir: File): PRootHostEnvironment =
        PRootHostEnvironment(dir.absolutePath, tmp.newFolder(), tmp.newFolder())

    @Test
    fun `probe true maps to TERMUX_COMPAT and lands in verify result`() = runBlocking {
        val dir = elfDir()
        val provider = NativeLibraryPRootBinaryProvider(
            hostEnv = hostEnvAt(dir),
            supportedAbis = { listOf("arm64-v8a") },
            versionProbe = { "proot version: 5.1.107.92" },
            killOnExitProbe = { true } // Termux 补丁版：选项被识别
        )
        val info = provider.verify(provider.locate().getOrThrow()).getOrThrow()
        assertEquals(PRootDialect.TERMUX_COMPAT, info.dialect)
        assertEquals(PRootVersion(5, 1, 107), info.version)
    }

    @Test
    fun `probe false maps to UPSTREAM (upstream 5_1_0 and Debian 5_4)`() = runBlocking {
        val dir = elfDir()
        val provider = NativeLibraryPRootBinaryProvider(
            hostEnv = hostEnvAt(dir),
            versionProbe = { "proot version: 5.4.0" },
            killOnExitProbe = { false } // 上游：unknown option '--kill-on-exit'
        )
        val info = provider.verify(provider.locate().getOrThrow()).getOrThrow()
        assertEquals(PRootDialect.UPSTREAM, info.dialect)
        assertEquals(PRootDialect.TERMUX_COMPAT, PRootDialect.valueOf("TERMUX_COMPAT"))
    }

    @Test
    fun `probe indeterminate falls back to TERMUX_COMPAT conservatively`() = runBlocking {
        // 探针 exec 异常（如 fork 失败环境）→ null → 保守回落设备生产行为
        //（捆绑 Termux 5.1.107 是主目标，回落不会引入新故障面）
        val dir = elfDir()
        val provider = NativeLibraryPRootBinaryProvider(
            hostEnv = hostEnvAt(dir),
            versionProbe = { null },
            killOnExitProbe = { null }
        )
        val info = provider.verify(provider.locate().getOrThrow()).getOrThrow()
        assertEquals(PRootDialect.TERMUX_COMPAT, info.dialect)
        assertNull(info.version)
    }

    @Test
    fun `dialect probe is memoized per binary (no exec storm across verify calls)`() = runBlocking {
        val dir = elfDir()
        val probeCalls = AtomicInteger(0)
        val provider = NativeLibraryPRootBinaryProvider(
            hostEnv = hostEnvAt(dir),
            versionProbe = { "5.1.107" },
            killOnExitProbe = { probeCalls.incrementAndGet(); false }
        )
        val binary = provider.locate().getOrThrow()
        repeat(5) { provider.verify(binary).getOrThrow() }
        // provider 是 DI 单例；availability + prepare 每会话都 verify ——
        // 探针必须只 exec 一次（记忆化），否则每次 spawn 多一次子进程。
        assertEquals("probe must be memoized", 1, probeCalls.get())
        assertEquals(PRootDialect.UPSTREAM, provider.verify(binary).getOrThrow().dialect)
    }

    // ─── 2. 方言 → argv 形状（builder golden） ───

    @Test
    fun `TERMUX_COMPAT argv keeps kill-on-exit and option separator`() {
        val cmd = PRootCommandBuilderImpl().build(
            launchRequest(), AbsolutePath("/p"), AbsolutePath("/r"), AbsolutePath("/w"),
            dialect = PRootDialect.TERMUX_COMPAT
        )
        val argv = listOf(cmd.executable.value) + cmd.arguments
        assertTrue("kill-on-exit present on Termux dialect", argv.contains("--kill-on-exit"))
        assertEquals("option separator present on Termux dialect", "--", argv[argv.indexOf("/usr/bin/env") - 1])
        assertTrue(PRootArgvContract.hasEnvTrampoline(argv))
        assertTrue(PRootArgvContract.legacyIncompatibleFlags(argv).isEmpty())
    }

    @Test
    fun `UPSTREAM argv omits kill-on-exit and separator but keeps env trampoline`() {
        val cmd = PRootCommandBuilderImpl().build(
            launchRequest(), AbsolutePath("/p"), AbsolutePath("/r"), AbsolutePath("/w"),
            dialect = PRootDialect.UPSTREAM
        )
        val argv = listOf(cmd.executable.value) + cmd.arguments
        assertFalse("no kill-on-exit on upstream dialect (unknown option → 拒启)", argv.contains("--kill-on-exit"))
        assertFalse("no -- separator on upstream dialect", argv.contains("--"))
        // env trampoline 原样保留：guest env 注入语义与方言无关（T88 trampoline
        // 在上游 proot 上原样合法 —— 这正是 E2E 无需适配 env 的原因）
        val envIdx = argv.indexOf("/usr/bin/env")
        assertTrue("env trampoline present", envIdx > 0)
        assertEquals("-i", argv[envIdx + 1])
        assertEquals("TERM=xterm-256color", argv[envIdx + 2])
        assertEquals("PATH=/usr/bin:/bin", argv[envIdx + 3])
        assertEquals(listOf("/bin/bash", "-i"), argv.takeLast(2))
        assertTrue(PRootArgvContract.hasEnvTrampoline(argv))
    }

    @Test
    fun `killOnExit=false never emits the flag on any dialect`() {
        for (dialect in PRootDialect.values()) {
            val cmd = PRootCommandBuilderImpl().build(
                launchRequest(killOnExit = false), AbsolutePath("/p"), AbsolutePath("/r"), AbsolutePath("/w"),
                dialect = dialect
            )
            assertFalse(
                "dialect=$dialect: request.killOnExit=false must win",
                cmd.arguments.contains("--kill-on-exit")
            )
        }
    }

    // ─── 3. 契约双方言形状（PRootArgvContract） ───

    @Test
    fun `contract accepts both dialect shapes and rejects malformed argv`() {
        val termuxArgv = listOf(
            "/proot", "-r", "/rootfs", "-0", "--kill-on-exit", "-b", "/ws:/workspace",
            "--", "/usr/bin/env", "-i", "TERM=xterm", "/bin/bash", "-i"
        )
        val upstreamArgv = listOf(
            "/proot", "-r", "/rootfs", "-0", "-b", "/ws:/workspace",
            "/usr/bin/env", "-i", "TERM=xterm", "/bin/bash", "-i"
        )
        for (argv in listOf(termuxArgv, upstreamArgv)) {
            assertTrue("trampoline recognized: $argv", PRootArgvContract.hasEnvTrampoline(argv))
            assertTrue("no legacy flags: $argv", PRootArgvContract.legacyIncompatibleFlags(argv).isEmpty())
        }
        // 违规形状：无 trampoline / 黑名单 flag 命中
        assertFalse(PRootArgvContract.hasEnvTrampoline(listOf("/proot", "-r", "/rootfs", "/bin/bash")))
        assertEquals(
            listOf("-E"),
            PRootArgvContract.legacyIncompatibleFlags(
                listOf("/proot", "-r", "/rootfs", "-E", "KEY=V", "/usr/bin/env", "-i", "/bin/bash")
            )
        )
        // 上游形状的黑名单扫描边界：guest 命令段不被扫描（env trampoline 后止）
        assertEquals(
            "blacklist scan stops at env trampoline on upstream shape",
            emptyList<String>(),
            PRootArgvContract.legacyIncompatibleFlags(upstreamArgv)
        )
    }

    // ─── 4. 端到端：backend prepare 消费 verify 方言 ───

    private class DialectProvider(private val dialect: PRootDialect) : PRootBinaryProvider {
        override suspend fun locate(): Result<AbsolutePath> = Result.success(AbsolutePath("/fake/libproot.so"))
        override suspend fun verify(binary: AbsolutePath): Result<PRootBinaryInfo> = Result.success(
            PRootBinaryInfo(binary, PRootVersion(5, 1, 107), CpuArchitecture.ARM64, true, dialect)
        )
    }

    private class StaticRootfsProvider : RootfsProvider {
        override suspend fun current(): RootfsDescriptor = dialectRootfs()
        override suspend fun verify(rootfs: RootfsDescriptor): Result<RootfsVerification> =
            Result.failure(RuntimeException("unused"))
    }

    @Test
    fun `backend prepare routes verified dialect into spawn argv`() = runBlocking {
        for (dialect in PRootDialect.values()) {
            val backend = LinuxPRootBackend(
                binaryProvider = DialectProvider(dialect),
                rootfsProvider = StaticRootfsProvider(),
                workspaces = LinuxWorkspaceManager(File(tmp.root, "ws-$dialect")),
                userHome = GuestUserHome(File(tmp.root, "home-$dialect")),
                systemBinds = SystemBindProfile.NONE,
                hostEnv = null
            )
            val spec = backend.prepare(SessionSpawnRequest(cwd = "", rows = 24, cols = 80)).getOrThrow()
            val argv = spec.argv
            assertEquals(
                "dialect=$dialect: --kill-on-exit 由探针方言决定（不再盲发）",
                dialect.supportsKillOnExit, argv.contains("--kill-on-exit")
            )
            assertEquals(
                "dialect=$dialect: -- 终结符由探针方言决定",
                dialect.supportsOptionSeparator, argv.contains("--")
            )
            assertTrue("env trampoline intact", PRootArgvContract.hasEnvTrampoline(argv))
        }
    }
}
