package com.apex.agent.platform.terminal.proot

import com.apex.agent.platform.terminal.linux.CpuArchitecture
import com.apex.agent.platform.terminal.workspace.AbsolutePath
import java.io.File
import java.io.RandomAccessFile

/**
 * P71: 生产 PRoot 二进制 provider —— 从 APK 的 nativeLibraryDir 定位 libproot.so。
 *
 * 纯 JVM（无 android.* 依赖）：ABI 列表由 app DI 注入（`Build.SUPPORTED_ABIS.toList()`），
 * 版本探测走可注入 lambda（默认真实 exec `<binary> --version`，JVM 测试注入假探针）。
 *
 * verify 内容：
 *  1. 文件存在且可执行
 *  2. ELF 机器类型（读文件头 e_machine，字节级判定，不 exec）与设备支持 ABI 匹配
 *  3. `--version` 输出解析（真实 exec；探针失败 → version 为 null 但二进制仍可用 ——
 *     版本是诊断信息而非硬门槛，ptrace 环境差异不应阻断可用二进制）
 *  4. T91（D5）：`--kill-on-exit` 能力探针 → [PRootDialect]（argv 方言）。
 *     探针 exec `<binary> --kill-on-exit --version`：exit 0 = Termux 补丁方言
 *    （选项被识别）；exit 非零（unknown option）= 上游方言；exec 异常 = 不可判定
 *    → 保守回落 TERMUX_COMPAT（捆绑二进制的设备行为不变）。结果按二进制路径
 *    记忆化（provider 为 DI 单例，prepare/availability 每会话都会 verify ——
 *    不允许每次 spawn 都多一次 exec）。
 */
class NativeLibraryPRootBinaryProvider(
    private val hostEnv: PRootHostEnvironment,
    /** 设备支持的 ABI 列表（app 注入 Build.SUPPORTED_ABIS；空 = 跳过 ABI 检查（JVM 测试））。 */
    private val supportedAbis: () -> List<String> = { emptyList() },
    /** 版本探针：给定二进制 → "--version" 输出首行（或 null）。默认真实 exec。 */
    private val versionProbe: (File) -> String? = { binary -> defaultVersionProbe(binary, hostEnv) },
    /**
     * T91（D5）：`--kill-on-exit` 能力探针：true = 支持（Termux 补丁方言），
     * false = 不支持（上游方言），null = 探针不可判定（exec 异常）。
     * 默认真实 exec `<binary> --kill-on-exit --version`（不 ptrace，环境无关）。
     */
    private val killOnExitProbe: (File) -> Boolean? = { binary ->
        defaultKillOnExitProbe(binary, hostEnv)
    }
) : PRootBinaryProvider {

    /** T91（D5）：探针结果记忆化（二进制路径 → 方言；provider 为 DI 单例）。 */
    private val dialectCache = java.util.concurrent.ConcurrentHashMap<String, PRootDialect>()

    override suspend fun locate(): Result<AbsolutePath> = runCatching {
        val f = hostEnv.prootBinary
        if (!f.exists()) {
            error("PRootError:BINARY_NOT_FOUND — ${f.absolutePath}（useLegacyPackaging 未生效或 APK 未打包 proot）")
        }
        AbsolutePath(f.absolutePath)
    }

    override suspend fun verify(binary: AbsolutePath): Result<PRootBinaryInfo> = runCatching {
        val f = File(binary.value)
        if (!f.exists()) error("PRootError:BINARY_NOT_FOUND — ${binary.value}")
        if (!f.canRead()) error("PRootError:BINARY_NOT_EXECUTABLE — 不可读: ${binary.value}")

        val elfArch = readElfMachine(f)
            ?: error("PRootError:BINARY_NOT_EXECUTABLE — 不是有效 ELF: ${binary.value}")
        val deviceAbis = supportedAbis()
        val abiCompatible = deviceAbis.isEmpty() || deviceAbis.any { abiMatches(it, elfArch) }
        if (!abiCompatible) {
            error(
                "PRootError:ARCHITECTURE_MISMATCH — proot ELF=$elfArch，设备 ABI=$deviceAbis"
            )
        }

        val versionText = try {
            versionProbe(f)
        } catch (e: Exception) {
            null // 探针失败不阻断 —— 版本是诊断信息（见类注释）
        }
        PRootBinaryInfo(
            path = binary,
            version = versionText?.let { parseVersion(it) },
            architecture = elfArch,
            executable = f.canExecute(),
            dialect = dialectFor(f)
        )
    }

    // ─── T91（D5）：方言探针（--kill-on-exit 能力实测 + 记忆化） ───

    /**
     * 实测判定二进制方言（记忆化）。探针结果三态：
     *  - true  → [PRootDialect.TERMUX_COMPAT]（选项被识别 —— 捆绑 5.1.107.92 实测行为）；
     *  - false → [PRootDialect.UPSTREAM]（unknown option —— Debian 5.4 / Ubuntu 5.1.0）；
     *  - null  → 保守回落 TERMUX_COMPAT（exec 不可判定时保持设备生产行为不变
     *            —— 捆绑二进制是主目标；上游环境探针本身能跑就会给出确定值）。
     */
    internal fun dialectFor(binary: File): PRootDialect =
        dialectCache.computeIfAbsent(binary.absolutePath) {
            val supported = try {
                killOnExitProbe(binary)
            } catch (e: Exception) {
                null
            }
            when (supported) {
                true -> PRootDialect.TERMUX_COMPAT
                false -> PRootDialect.UPSTREAM
                null -> PRootDialect.TERMUX_COMPAT
            }
        }

    // ─── ELF 解析（字节级，无 exec —— 在任何环境可跑） ───

    private fun readElfMachine(f: File): CpuArchitecture? = runCatching {
        RandomAccessFile(f, "r").use { raf ->
            val header = ByteArray(20)
            if (raf.read(header) != 20) return@runCatching null
            // ELF magic + 64/32 位 + 字节序
            if (header[0] != 0x7f.toByte() || header[1] != 'E'.code.toByte() ||
                header[2] != 'L'.code.toByte() || header[3] != 'F'.code.toByte()
            ) return@runCatching null
            val is64 = header[4] == 2.toByte()
            val isLE = header[5] == 1.toByte()
            val machineOffset = 18
            val m = if (isLE) {
                (header[machineOffset].toInt() and 0xFF) or
                    ((header[machineOffset + 1].toInt() and 0xFF) shl 8)
            } else {
                ((header[machineOffset].toInt() and 0xFF) shl 8) or
                    (header[machineOffset + 1].toInt() and 0xFF)
            }
            when (m) {
                EM_AARCH64 -> CpuArchitecture.ARM64
                EM_ARM -> CpuArchitecture.ARM32
                EM_X86_64 -> CpuArchitecture.X86_64
                EM_386 -> CpuArchitecture.X86
                else -> CpuArchitecture.UNKNOWN
            }
        }
    }.getOrNull()

    private fun abiMatches(abi: String, arch: CpuArchitecture): Boolean = when (abi) {
        "arm64-v8a" -> arch == CpuArchitecture.ARM64
        "armeabi-v7a", "armeabi" -> arch == CpuArchitecture.ARM32
        "x86_64" -> arch == CpuArchitecture.X86_64
        "x86" -> arch == CpuArchitecture.X86
        else -> false
    }

    private fun parseVersion(text: String): PRootVersion {
        // 兼容 "proot version: 5.1.107.92" / "proot-5.1.107" / "5.4.0" 等形态
        val m = Regex("(\\d+)\\.(\\d+)(?:\\.(\\d+))?").find(text) ?: return PRootVersion(null, null, null)
        val (a, b, c) = m.destructured
        return PRootVersion(a.toIntOrNull(), b.toIntOrNull(), c.toIntOrNull())
    }

    companion object {
        private const val EM_ARM = 40
        private const val EM_X86_64 = 62
        private const val EM_AARCH64 = 183
        private const val EM_386 = 3

        /** 默认探针：真实 exec `<binary> --version`（Android/JVM 通用）。 */
        private fun defaultVersionProbe(binary: File, hostEnv: PRootHostEnvironment): String? {
            return try {
                val envMap = hostEnv.hostEnv()
                val pb = ProcessBuilder(listOf(binary.absolutePath, "--version"))
                pb.environment().clear()
                pb.environment().putAll(envMap)
                val proc = pb.start()
                val out = proc.inputStream.bufferedReader().readText().trim()
                val err = proc.errorStream.bufferedReader().readText().trim()
                proc.waitFor()
                out.ifBlank { err }.ifBlank { null }
            } catch (e: Exception) {
                null
            }
        }

        /**
         * T91（D5）：`--kill-on-exit` 能力探针 —— exec `<binary> --kill-on-exit --version`：
         *  - exit 0 → 选项被识别（Termux 补丁方言）→ true；
         *  - exit 非零 → unknown option（上游方言）→ false；
         *  - exec 异常 → null（不可判定，调用方保守回落）。
         *
         * 与 --version 同构：不触发 ptrace/loader 链路，任何可 exec 环境均可用。
         */
        private fun defaultKillOnExitProbe(binary: File, hostEnv: PRootHostEnvironment): Boolean? {
            return try {
                val envMap = hostEnv.hostEnv()
                val pb = ProcessBuilder(
                    listOf(binary.absolutePath, KILL_ON_EXIT_OPTION, "--version")
                )
                pb.environment().clear()
                pb.environment().putAll(envMap)
                val proc = pb.start()
                proc.inputStream.bufferedReader().readText()
                proc.errorStream.bufferedReader().readText()
                val exited = proc.waitFor(10, java.util.concurrent.TimeUnit.SECONDS)
                if (!exited) {
                    runCatching { proc.destroyForcibly() }
                    null // 无界挂起不可判定（与 E2E 探针同款有界防御）
                } else {
                    proc.exitValue() == 0
                }
            } catch (e: Exception) {
                null
            }
        }

        /** `--kill-on-exit` 选项字面量（argv 构造/探针单一事实源）。 */
        const val KILL_ON_EXIT_OPTION: String = "--kill-on-exit"
    }
}
