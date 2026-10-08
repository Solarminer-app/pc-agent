import org.springframework.boot.gradle.tasks.bundling.BootJar
import org.springframework.boot.gradle.tasks.run.BootRun

plugins {
    java
    id("org.springframework.boot") version "3.4.3"
    id("io.spring.dependency-management") version "1.1.7"
    id("org.graalvm.buildtools.native") version "0.10.6" apply false
}

group = "de.verdox.solarminer"
version = providers.gradleProperty("pcAgentVersion").orElse("0.0.1-SNAPSHOT").get()
description = "pc-agent"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springdoc:springdoc-openapi-starter-webmvc-ui:2.8.5")
    implementation("com.github.oshi:oshi-core:7.3.1")
    implementation("org.apache.commons:commons-compress:1.28.0")
    compileOnly("org.projectlombok:lombok")
    annotationProcessor("org.projectlombok:lombok")
    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testCompileOnly("org.projectlombok:lombok")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testAnnotationProcessor("org.projectlombok:lombok")
}

tasks.withType<Test> {
    useJUnitPlatform()
}

val mainSourceSet = sourceSets.getByName("main")

tasks.register<BootJar>("standaloneJar") {
    group = "distribution"
    description = "Builds one executable PC-Agent JAR; the Stratum proxy is downloaded at runtime"
    dependsOn(tasks.named("classes"))
    archiveFileName.set("solarminer-pc-agent-standalone.jar")
    destinationDirectory.set(layout.buildDirectory.dir("distributions"))
    mainClass.set("de.verdox.solarminer.pcagent.PcAgentApplication")
    targetJavaVersion.set(JavaVersion.VERSION_21)
    classpath = mainSourceSet.runtimeClasspath
}

tasks.named<BootRun>("bootRun") {
    group = "application"
    description = "Runs the PC-Agent in local proxy mode, downloading the Stratum proxy release JAR"
    dependsOn(tasks.named("classes"))
    mainClass.set("de.verdox.solarminer.pcagent.PcAgentApplication")
    classpath = mainSourceSet.runtimeClasspath
    javaLauncher.set(javaToolchains.launcherFor {
        languageVersion.set(JavaLanguageVersion.of(21))
    })
    workingDir = rootProject.projectDir
    args("--solarminer.agent.standalone=true")
}

tasks.register("printPcAgentVersion") {
    group = "versioning"
    description = "Prints the PC-Agent version."

    doLast {
        println(version)
    }
}

tasks.register("printPcAgentImage") {
    group = "versioning"
    description = "Prints the PC-Agent Docker image repository."

    doLast {
        println(providers.gradleProperty("pcAgentImage").get())
    }
}
