plugins {
    id("rikkahub.android.library")
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "me.rerere.workspace"

    // 原生库改为使用预编译产物，原因同 :terminal（见 FORK.md「构建环境」）。
    // libworkspace.so 取自官方 3.1.1 arm64-v8a release APK；源码仍在 src/main/cpp/。
}

dependencies {
    implementation(libs.androidx.core.ktx)
    implementation(libs.kotlinx.serialization.json)
    implementation(libs.xz)
    implementation(libs.jsch)
    // Android 不认 multi-release jar, JSch 的 Ed25519 / Curve25519 等算法只能走 BouncyCastle 实现
    implementation(libs.bouncycastle.bcprov)
    testImplementation(libs.junit)
    // 单测里起一个嵌入式 SSH/SFTP 服务器验证 SshWorkspaceBackend
    testImplementation(libs.apache.sshd.core)
    testImplementation(libs.apache.sshd.sftp)
    androidTestImplementation(libs.androidx.espresso.core)
    androidTestImplementation(libs.androidx.junit)
}
