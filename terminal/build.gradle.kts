plugins {
    id("rikkahub.android.library")
}

android {
    namespace = "me.rerere.terminal"

    // 原生库改为使用预编译产物（见 FORK.md「构建环境」）：
    // 容器是 aarch64，而 NDK/CMake 的宿主工具只有 x86-64，无法在本机交叉编译。
    // libtermux.so 取自官方 3.1.1 arm64-v8a release APK（同一份源码构建）。
    // 源码仍在 src/main/cpp/termux_pty.cpp，需要时可在 x86-64 主机上重建。

    testOptions {
        // 上游单测会碰到 android.util.Base64 等 Android 类, 沿用上游的设置让它们返回默认值
        unitTests.isReturnDefaultValues = true
    }
}

dependencies {
    testImplementation(libs.junit)
}
