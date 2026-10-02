plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.hilt)
    alias(libs.plugins.ksp)
}

android {
    namespace = "com.apex.agent.platform.privilege"
    compileSdk = 35

    defaultConfig {
        minSdk = 26
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
}

dependencies {
    // T92 (#255) 权限链审计收敛：随 ShellStreamSource / ShizukuStreamAdapter /
    // ProcessStreamFactory 死代码三件套移除，本模块不再引用 core:tool-registry
    //（唯一消费方 ToolStreamEvent 已删）—— privilege 收紧为叶子模块：
    // core:logging（审计遥测）+ Shizuku SDK + coroutines，无引擎/工具依赖。
    implementation(project(":core:logging"))
    implementation(libs.core.ktx)
    implementation(libs.coroutines.core)
    implementation(libs.coroutines.android)
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)

    // Unit testing (pure-JVM src/test)
    testImplementation(libs.junit)
    testImplementation(libs.coroutines.test)
}
