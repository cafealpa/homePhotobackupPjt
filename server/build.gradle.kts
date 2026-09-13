import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.spring") version "2.2.20"
    id("org.springframework.boot") version "3.5.6"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.homephoto"
version = "0.1.5"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val exposedVersion = "0.61.0"

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
    implementation("org.jetbrains.kotlin:kotlin-reflect")
    // Spring Framework 6 / Boot 3 호환 MCP 전송 계층. 별도 서버 프로세스 없이 실행.
    implementation("io.modelcontextprotocol.sdk:mcp-spring-webmvc:0.18.4")
    implementation("io.modelcontextprotocol.sdk:mcp:0.18.4")

    // DB: Exposed + SQLite
    implementation("org.jetbrains.exposed:exposed-spring-boot-starter:$exposedVersion")
    implementation("org.xerial:sqlite-jdbc:3.47.1.0")

    // EXIF 추출
    implementation("com.drewnoakes:metadata-extractor:2.19.0")

    // 썸네일 (JPEG/PNG 등 ImageIO 지원 포맷. HEIC/동영상은 ffmpeg 필요)
    implementation("net.coobird:thumbnailator:0.4.20")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
    testImplementation(kotlin("test"))
}

kotlin {
    compilerOptions {
        jvmTarget = JvmTarget.JVM_21
        freeCompilerArgs.add("-Xjsr305=strict")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// 실제 라이브러리/워커를 띄우지 않고 임시 사진으로 MCP 갤러리를 확인한다.
tasks.register<JavaExec>("mcpDemo") {
    group = "verification"
    description = "Run the local MCP gallery with generated demo photos on port 18081"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.homephoto.server.mcp.PhotoMcpDemo")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}
