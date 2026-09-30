import org.jetbrains.kotlin.gradle.dsl.JvmTarget

plugins {
    kotlin("jvm") version "2.2.20"
    kotlin("plugin.spring") version "2.2.20"
    id("org.springframework.boot") version "3.5.6"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.homephoto"
version = "0.1.6-rc2"

springBoot {
    buildInfo()
}

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
    implementation("org.springframework.boot:spring-boot-starter-oauth2-authorization-server")
    implementation("org.springframework.boot:spring-boot-starter-oauth2-resource-server")
    runtimeOnly("com.h2database:h2")
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
    // JPEG 픽셀을 재인코딩하지 않고 별도 게시 파일에 EXIF를 기록한다.
    implementation("org.apache.commons:commons-imaging:1.0.0-alpha6")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("org.springframework.security:spring-security-test")
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

// 운영 PC에서 ZIP 없이 받을 수 있는 고정 이름의 실행 JAR.
val serverBootJar = tasks.named<org.springframework.boot.gradle.tasks.bundling.BootJar>("bootJar")
tasks.register<Copy>("exportServerJar") {
    group = "distribution"
    description = "Test and export the server JAR to deploy/homephoto-server.jar"
    dependsOn(tasks.test, serverBootJar)
    from(serverBootJar.flatMap { it.archiveFile })
    into(rootProject.layout.projectDirectory.dir("../deploy"))
    rename { "homephoto-server.jar" }
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

tasks.register<JavaExec>("mcpOAuthDemo") {
    group = "verification"
    description = "Run generated photos with OAuth settings imported from a separate private config"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.homephoto.server.mcp.PhotoMcpDemo")
    args("--oauth")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}

// 운영 DB/설정을 로드하지 않는 5장짜리 Google Photos 메타데이터 검증 도구.
tasks.register<JavaExec>("googlePhotosMetadataPoc") {
    group = "verification"
    description = "Prepare five JPEG metadata fixtures, or explicitly upload a prepared set with desktop OAuth"
    dependsOn(tasks.testClasses)
    classpath = sourceSets.test.get().runtimeClasspath
    mainClass.set("com.homephoto.server.publication.GooglePhotosMetadataPoc")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
    args(providers.gradleProperty("pocMode").getOrElse("prepare"))
    providers.gradleProperty("pocDir").orNull?.let { args(it) }
}

tasks.register<JavaExec>("googlePhotosAuthorize") {
    group = "verification"
    description = "Authorize a Google Photos Desktop OAuth client and write a separate private token file without uploading"
    dependsOn(tasks.classes)
    classpath = sourceSets.main.get().runtimeClasspath
    mainClass.set("com.homephoto.server.publication.GooglePhotosDesktopOAuth")
    javaLauncher.set(javaToolchains.launcherFor { languageVersion.set(JavaLanguageVersion.of(21)) })
}
