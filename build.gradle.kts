plugins {
    java
}

group = "dev.lapius7"
version = "1.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

// 最小対応版の API (Paper 1.16.5) に対し、Java 8 のバイトコードでコンパイルする。
// それより新しい API は実行時に有無を調べて使う。
val paperApi = "com.destroystokyo.paper:paper-api:1.16.5-R0.1-SNAPSHOT"

dependencies {
    compileOnly(paperApi)
    // Java 8 のバイトコードを出すため、新しい構文 (var / switch 式 / instanceof パターン) を Jabel で受ける
    annotationProcessor("com.github.bsideup.jabel:jabel-javac-plugin:1.0.0")
    compileOnly("com.github.bsideup.jabel:jabel-javac-plugin:1.0.0")
    testImplementation(paperApi)
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_1_8
}

tasks.compileJava {
    options.encoding = "UTF-8"
    options.release = 8
    // Jabel が javac 内部を触るため、JDK 16 以降では公開が要る
    options.isFork = true
    options.forkOptions.jvmArgs = listOf(
        "--add-exports=jdk.compiler/com.sun.tools.javac.api=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.file=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.main=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.model=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.processing=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.tree=ALL-UNNAMED",
        "--add-exports=jdk.compiler/com.sun.tools.javac.util=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.code=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.comp=ALL-UNNAMED",
        "--add-opens=jdk.compiler/com.sun.tools.javac.parser=ALL-UNNAMED",
    )
}

tasks.compileTestJava {
    options.encoding = "UTF-8"
    options.release = 17
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.jar {
    archiveFileName = "ImagePaste-${project.version}.jar"
}

tasks.test {
    useJUnitPlatform()
}
