// rikkasync:纯 JVM 的云同步引擎(加密、客户端、对账算法)。不依赖 Android,也**不引用任何上游业务类型**
// (模块边界由编译器强制)。Android 侧的适配器在 app 模块的 sync/host 包里。
plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.serialization)
}

// 与其它模块一致:字节码目标 Java 17(不强求本机装有 17 的工具链)。
java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions {
        jvmTarget.set(org.jetbrains.kotlin.gradle.dsl.JvmTarget.JVM_17)
    }
}

dependencies {
    // 全部是工程里已有的依赖,不引入新的第三方库。
    api(libs.okhttp)
    api(libs.kotlinx.serialization.json)
    api(libs.kotlinx.coroutines.core)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
}

tasks.withType<Test>().configureEach {
    useJUnit()
    // 引擎测试需要一个真实的同步服务器:指向 rikkasync 仓库的检出目录(含 server/test-harness.ts),并需要 bun。
    // 未设置时这些测试会被跳过(假设不成立),密码学向量测试不受影响。
    environment("RIKKASYNC_REPO", providers.environmentVariable("RIKKASYNC_REPO").getOrElse(""))
}
