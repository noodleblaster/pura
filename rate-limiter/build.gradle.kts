plugins {
  java
  alias(libs.plugins.kotlin.jvm)
  alias(libs.plugins.spring.boot)
  alias(libs.plugins.spotless)
  alias(libs.plugins.spotbugs)
}

group = "com.pura"
version = "0.0.1-SNAPSHOT"

repositories {
  mavenCentral()
}

kotlin {
  jvmToolchain(25)
}

dependencies {
  implementation(platform("org.springframework.boot:spring-boot-dependencies:${libs.versions.springBoot.get()}"))
  implementation("org.springframework.boot:spring-boot-starter-web")
  implementation("org.springframework.boot:spring-boot-starter-validation")
  compileOnly(libs.lombok)
  annotationProcessor(libs.lombok)

  testImplementation("org.springframework.boot:spring-boot-starter-test")
  testImplementation("org.springframework.boot:spring-boot-webmvc-test")
  testImplementation("org.springframework.boot:spring-boot-resttestclient")
  testImplementation("org.springframework.boot:spring-boot-restclient")
  testImplementation(libs.kotest.runner.junit5)
  testImplementation(libs.kotest.assertions.core)
  testImplementation(libs.kotest.extensions.spring)
}

tasks.withType<Test> {
  useJUnitPlatform()
}

// SpotBugs is used specifically for its concurrency detectors on production code (see
// docs/design.md); it has no value running over Kotlin test bytecode, and doing so is what
// previously forced test specs into a `private var x: T? = null` + accessor pattern instead of
// plain `lateinit var`. Skip the auto-generated test task instead.
tasks.named("spotbugsTest") { enabled = false }

spotbugs {
  effort.set(com.github.spotbugs.snom.Effort.MAX)
  reportLevel.set(com.github.spotbugs.snom.Confidence.DEFAULT)
}

spotless {
  java {
    target("src/*/java/**/*.java")
    googleJavaFormat()
  }
  kotlin {
    target("src/*/kotlin/**/*.kt")
    ktlint()
  }
  kotlinGradle {
    target("*.gradle.kts")
    ktlint()
  }
}
