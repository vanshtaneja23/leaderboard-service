import com.google.protobuf.gradle.id

plugins {
    java
    id("org.springframework.boot") version "4.1.1"
    id("io.spring.dependency-management") version "1.1.7"
    id("com.google.protobuf") version "0.10.0"
}

group = "dev.vansh"
version = "0.1.0"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-grpc-server")
    implementation("io.grpc:grpc-protobuf")
    implementation("io.grpc:grpc-stub")
    implementation("com.google.protobuf:protobuf-java")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation("io.grpc:grpc-inprocess")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

// Declaring the "grpc" protoc plugin is all that's needed: Spring Boot's Gradle plugin reacts to
// the protobuf plugin, pins protoc + protoc-gen-grpc-java to the versions in its BOM, and attaches
// the grpc generator to every generateProto task.
protobuf {
    plugins {
        id("grpc")
    }
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
