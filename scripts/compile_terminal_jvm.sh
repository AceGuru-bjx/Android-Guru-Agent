#!/usr/bin/env bash
# ═══════════════════════════════════════════════════════════════════════════
# compile_terminal_jvm.sh — local kotlinc type-check for the terminal stack
#
# The sandbox has no Android SDK/Gradle; kotlinc (JVM target 17) gives full
# type-level verification for the pure-JVM modules this task touches:
#   :terminal-emulator   (pure Kotlin, zero Android imports)
#   :terminal-native     (one android.util.Log import — stubbed locally)
#   :platform:terminal   (screen/ pulls vtnative → android.util.Log, stubbed)
#
# android.util.Log is stubbed from a temp dir (never committed); everything
# else compiles against real sources. Main + test in ONE invocation gives
# tests `internal` visibility exactly like Gradle's test source set.
#
# T92（审计修复）：T91 新增的 service/ 两个 Android 壳（TerminalService/
# TerminalServiceClient —— Service/Notification/IBinder 等框架 API）超出
# Log stub 覆盖范围，计入编译后脚本在 T91 后必红（本地验证链断裂）。
# 壳文件零逻辑（薄壳纪律），排除后类型检查面仍是全部纯 JVM 语义；
# AIDL 壳由 CI 的 Gradle aidl 管线覆盖。新增壳文件需同步登记排除清单。
# ═══════════════════════════════════════════════════════════════════════════
set -euo pipefail
cd "$(dirname "$0")/.."

KOTLINC="${KOTLINC:-$HOME/.local/kotlinc/bin/kotlinc}"
LIBS="${LIBS:-$HOME/kotlin-libs}"
PLUGIN="$HOME/.local/kotlinc/lib/kotlinx-serialization-compiler-plugin.jar"
CP="$LIBS/kotlinx-coroutines-core-jvm-1.9.0.jar:$LIBS/kotlinx-serialization-json-jvm-1.7.3.jar:$LIBS/kotlinx-serialization-core-jvm-1.7.3.jar:$LIBS/annotations-24.1.0.jar:$LIBS/junit-4.13.2.jar:$LIBS/hamcrest-core-1.3.jar:$LIBS/kotlinx-coroutines-test-jvm-1.9.0.jar"
OUT=$(mktemp -d)
STUBS=$(mktemp -d)
# T92：Android 壳排除（见头部注释）—— service/ 的 TerminalService.kt 与
# TerminalServiceClient.kt 依赖 Service/Notification/IBinder 等框架 API，
# Log stub 覆盖不了；纯 JVM 的 TerminalIpcController/TerminalRuntimeRegistry 照编。
mapfile -t PLATFORM_SOURCES < <(
  find platform/terminal/src/main/kotlin platform/terminal/src/test/kotlin \
    -name '*.kt' ! -name 'TerminalService.kt' ! -name 'TerminalServiceClient.kt' | sort
)
mkdir -p "$STUBS/android/util"
cat > "$STUBS/android/util/Log.kt" <<'STUB'
// Local JVM-compile stub (never committed) — CI uses the real android.util.Log.
package android.util
object Log {
    @JvmStatic fun v(tag: String, msg: String): Int = 0
    @JvmStatic fun d(tag: String, msg: String): Int = 0
    @JvmStatic fun i(tag: String, msg: String): Int = 0
    @JvmStatic fun w(tag: String, msg: String): Int = 0
    @JvmStatic fun w(tag: String, msg: String, tr: Throwable?): Int = 0
    @JvmStatic fun e(tag: String, msg: String): Int = 0
    @JvmStatic fun e(tag: String, msg: String, tr: Throwable): Int = 0
    @JvmStatic fun isLoggable(tag: String, level: Int): Boolean = false
}
STUB

echo "── compiling terminal-emulator + terminal-native + platform/terminal ──"
# 全栈一次编译内存压力大（UnicodeWidthTables 大区间表 + 800+ 源文件）：-J-Xmx4g
# T92：platform/terminal 以文件清单参编（排除两个 Android 壳，见 mapfile）
JAVA_OPTS="-Xmx4g" "$KOTLINC" -J-Xmx4g -jvm-target 17 -nowarn -Xplugin="$PLUGIN" \
  "$STUBS" \
  terminal-emulator/src/main/kotlin \
  terminal-emulator/src/test/kotlin \
  terminal-native/src/main/kotlin \
  terminal-native/src/test/kotlin \
  "${PLATFORM_SOURCES[@]}" \
  -cp "$CP" -d "$OUT/classes" 2>&1 | { grep -E "error:" || true; } | head -60

echo "── OK: $(find $OUT/classes -name '*.class' | wc -l) classes ──"
rm -rf "$OUT" "$STUBS"
