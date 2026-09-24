plugins {
    java
    id("io.quarkus")
}

repositories {
    mavenCentral()
    mavenLocal()
}

val quarkusPlatformGroupId: String by project
val quarkusPlatformArtifactId: String by project
val quarkusPlatformVersion: String by project

dependencies {
    implementation(enforcedPlatform("${quarkusPlatformGroupId}:${quarkusPlatformArtifactId}:${quarkusPlatformVersion}"))
    implementation("io.quarkus:quarkus-rest")
    implementation("io.quarkus:quarkus-rest-client-jackson")
    implementation("io.quarkus:quarkus-rest-jackson")
    implementation("io.quarkus:quarkus-agroal")
    implementation("io.quarkus:quarkus-jdbc-postgresql")
    implementation("io.quarkus:quarkus-arc")
    implementation("io.quarkus:quarkus-flyway")
    implementation("io.quarkus:quarkus-smallrye-health")
    implementation("io.quarkus:quarkus-redis-client")

    // PostGIS geometry type support on top of quarkus-jdbc-postgresql's driver/pool
    implementation("net.postgis:postgis-jdbc:2024.1.0")

    testImplementation("io.quarkus:quarkus-junit")
    testImplementation("io.quarkus:quarkus-junit-mockito")
    testImplementation("io.rest-assured:rest-assured")
    testImplementation("org.assertj:assertj-core:3.25.3")

    // Tests run against throwaway PostGIS + Redis containers rather than the shared
    // instances in application.properties - see PostgisRedisTestResource.
    testImplementation("org.testcontainers:postgresql:1.20.6")
}

group = "com.sarvika.mlssearch"
version = "1.0.0-SNAPSHOT"

java {
    sourceCompatibility = JavaVersion.VERSION_21
    targetCompatibility = JavaVersion.VERSION_21
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

tasks.withType<JavaCompile> {
    options.encoding = "UTF-8"
    options.compilerArgs.add("-parameters")
}

// Every test tagged "requires-docker" starts PostgisRedisTestResource (Testcontainers), which
// needs a live Docker daemon. `./gradlew test` still runs everything by default - for local
// dev, where Docker is expected to be running (see docker-compose.yml). Pass
// -PexcludeDockerTests in a CI environment whose job containers have no Docker daemon
// available (e.g. a runner without docker-in-docker support).
tasks.test {
    useJUnitPlatform {
        if (project.hasProperty("excludeDockerTests")) {
            excludeTags("requires-docker")
        }
    }
}
