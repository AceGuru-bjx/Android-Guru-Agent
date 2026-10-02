package com.apex.agent.mcp.proot

import com.apex.agent.platform.terminal.proot.PRootArgvCapabilities
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * T92（D5 完成度）：ProotMcpProcessLauncher 的 argv 能力门测试。
 *
 * 本类是全仓库唯一**不走** PRootCommandBuilder 的 proot argv 构造点
 * （argv 手工内联拼装）—— T91 贯通了 builder 路径却留下这里硬编码
 * `--kill-on-exit` 与 `--`（-E 事故同构风险的最后一个残留点）。本测试
 * 锁定手工能力门与 builder 行为逐项一致：
 *
 * | 能力集 | `--kill-on-exit` | `--` |
 * |---|---|---|
 * | TERMUX_BUNDLED（默认/设备生产） | 发 | 发 |
 * | UPSTREAM_SAFE（upstream 5.1.0） | 省 | 省 |
 * | Debian 5.4 混合形态 | 发 | 省 |
 */
class ProotMcpProcessLauncherCapabilityTest {

    @get:Rule
    val tmp = TemporaryFolder()

    private fun launcher(capabilities: () -> PRootArgvCapabilities): ProotMcpProcessLauncher =
        ProotMcpProcessLauncher(
            hostEnv = mapOf("PATH" to "/system/bin"),
            libprootPath = "/fake/nativeDir/libproot.so",
            rootfsDir = tmp.newFolder("rootfs"),
            isRootfsReady = { true },
            capabilities = capabilities
        )

    private fun argvOf(
        launcher: ProotMcpProcessLauncher,
        command: List<String> = listOf("node", "mcp-server.js")
    ): List<String> = launcher.buildArgv(
        rootfs = tmp.newFolder("r"),
        command = command,
        requestEnv = emptyMap()
    )

    private val envIdx: (List<String>) -> Int = { argv -> argv.indexOf("/usr/bin/env") }

    @Test
    fun `TERMUX_BUNDLED baseline argv is byte-identical to pre-T92 shape`() {
        val argv = argvOf(launcher { PRootArgvCapabilities.TERMUX_BUNDLED })
        assertTrue("kill-on-exit kept", argv.contains("--kill-on-exit"))
        assertEquals("separator right before env trampoline", "--", argv[envIdx(argv) - 1])
        assertEquals("/fake/nativeDir/libproot.so", argv[0])
        assertEquals("-r", argv[1])
        assertEquals("-0", argv[3])
        assertEquals("/usr/bin/env", argv[envIdx(argv)])
        assertEquals("-i", argv[envIdx(argv) + 1])
        assertEquals("guest 命令尾随 argv 之后", listOf("node", "mcp-server.js"), argv.takeLast(2))
    }

    @Test
    fun `UPSTREAM_SAFE omits both hardcoded flags but keeps trampoline boundary`() {
        val argv = argvOf(launcher { PRootArgvCapabilities.UPSTREAM_SAFE })
        assertFalse("upstream 5.1.0 不认 --kill-on-exit（拒启）", argv.contains("--kill-on-exit"))
        assertFalse("upstream 不认 --（拒启）", argv.contains("--"))
        // trampoline 首 token /usr/bin/env 是非选项 token —— 天然分界
        assertTrue("env trampoline present", envIdx(argv) > 0)
        assertEquals("-i", argv[envIdx(argv) + 1])
        assertEquals("guest 命令尾随 argv 之后", listOf("node", "mcp-server.js"), argv.takeLast(2))
    }

    @Test
    fun `Debian 5_4 hybrid keeps kill-on-exit and omits separator`() {
        val argv = argvOf(launcher { PRootArgvCapabilities(supportsKillOnExit = true, supportsOptionSeparator = false) })
        assertTrue("Debian 5.4 支持 --kill-on-exit", argv.contains("--kill-on-exit"))
        assertFalse("Debian 5.4 不支持 --", argv.contains("--"))
        assertTrue("env trampoline present", envIdx(argv) > 0)
    }

    @Test
    fun `default constructor baseline stays TERMUX_BUNDLED (existing fixtures unchanged)`() {
        // 不传 capabilities 的既有构造形态 → 默认 Termux 基线（既有测试夹具
        // 与 DI 未接线场景的兼容语义 —— 生产 DI 由 TerminalModule 注入真实源）
        val argv = argvOf(
            ProotMcpProcessLauncher(
                hostEnv = mapOf("PATH" to "/system/bin"),
                libprootPath = "/fake/libproot.so",
                rootfsDir = tmp.newFolder("rootfs2"),
                isRootfsReady = { true }
            )
        )
        assertTrue(argv.contains("--kill-on-exit"))
        assertEquals("--", argv[envIdx(argv) - 1])
    }
}
