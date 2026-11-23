plugins {
    java
}

group = "dev.lapius7"
version = "1.0.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

// 最小対応版の API (Paper 1.18.2) に対してコンパイルする。
// それより新しい API は実行時に有無を調べて使う。
val paperApi = "io.papermc.paper:paper-api:1.18.2-R0.1-SNAPSHOT"

dependencies {
    compileOnly(paperApi)
    testImplementation(paperApi)
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

tasks.withType<JavaCompile> {
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
