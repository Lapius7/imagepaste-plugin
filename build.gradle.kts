plugins {
    java
}

group = "dev.lapius7"
version = "0.1.0"

repositories {
    mavenCentral()
    maven("https://repo.papermc.io/repository/maven-public/")
}

dependencies {
    // サーバーの paper.jar (26.3-258) の API に合わせる
    compileOnly("io.papermc.paper:paper-api:26.3.build.258-stable")
    testImplementation("io.papermc.paper:paper-api:26.3.build.258-stable")
    testImplementation(platform("org.junit:junit-bom:5.11.4"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

java {
    sourceCompatibility = JavaVersion.VERSION_25
    targetCompatibility = JavaVersion.VERSION_25
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.release = 25
}

tasks.processResources {
    inputs.property("version", project.version)
    filesMatching("plugin.yml") { expand("version" to project.version) }
}

tasks.jar {
    archiveFileName = "mc-image-paste-${project.version}.jar"
}

tasks.test {
    useJUnitPlatform()
}
