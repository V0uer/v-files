import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins { alias(libs.plugins.kotlin.jvm) }
kotlin {
    jvmToolchain(17)
    // :core corre en Android 11+ (API 30): compilar contra la API de Java 11 hace que una
    // llamada a algo más nuevo (p. ej. Stream.toList(), de Java 16) falle al compilar y no
    // en el aparato con NoSuchMethodError.
    compilerOptions {
        jvmTarget.set(JvmTarget.JVM_11)
        freeCompilerArgs.add("-Xjdk-release=11")
    }
}
tasks.withType<JavaCompile>().configureEach { options.release.set(11) }
dependencies {
    implementation(libs.coroutines.core)
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.launcher)
    testImplementation(libs.coroutines.test)
}
tasks.test { useJUnitPlatform(); maxHeapSize = "1g" }
