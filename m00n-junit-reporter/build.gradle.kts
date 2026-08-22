import com.vanniktech.maven.publish.SonatypeHost
import com.vanniktech.maven.publish.JavaLibrary
import com.vanniktech.maven.publish.JavadocJar

plugins {
    id("java-library")
    id("maven-publish")
    id("signing")
    id("com.vanniktech.maven.publish") version "0.30.0"
}

group = "com.m00nreport"
version = "1.6.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17

    // javadoc and sources jars are created by the maven-publish plugin
}

repositories {
    mavenCentral()
}

dependencies {
    // JUnit 5 Platform (provided - users bring their own)
    compileOnly(platform("org.junit:junit-bom:5.11.3"))
    compileOnly("org.junit.jupiter:junit-jupiter-api")
    compileOnly("org.junit.platform:junit-platform-launcher")

    // AspectJ Runtime - included transitively for @Step support
    api("org.aspectj:aspectjrt:1.9.22")

    // AspectJ Weaver - users must add this + javaagent for automatic step tracking
    compileOnly("org.aspectj:aspectjweaver:1.9.22")

    // HTTP Client
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // JSON processing
    implementation("com.google.code.gson:gson:2.14.0")

    // Logging (SLF4J API - users bring their own impl)
    implementation("org.slf4j:slf4j-api:2.0.16")

    // Test dependencies
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    // On the compile classpath, not just runtime: the extension's own behaviour is only
    // observable by running a real suite through the launcher inside a test.
    testImplementation("org.junit.platform:junit-platform-launcher")
    testImplementation("org.junit.platform:junit-platform-testkit")
    testImplementation("ch.qos.logback:logback-classic:1.5.12")
}

tasks.test {
    useJUnitPlatform()
    // Suites that exist to be run BY a test, not as one. One of them fails on purpose - a fixture
    // that fails the build it is meant to be studied by is useless.
    exclude("**/fixtures/**")
}

// Javadoc configuration
tasks.javadoc {
    options {
        (this as StandardJavadocDocletOptions).apply {
            encoding = "UTF-8"
            addStringOption("Xdoclint:none", "-quiet")
        }
    }
}

// ============================================================================
// Publishing Configuration
// ============================================================================

mavenPublishing {
    configure(JavaLibrary(JavadocJar.Javadoc(), true))

    publishToMavenCentral(SonatypeHost.CENTRAL_PORTAL, automaticRelease = false)
    signAllPublications()

    pom {
        name.set("M00N JUnit Reporter")
        description.set("Official JUnit 5 reporter for M00N Report, an AI-native test management platform. Streams live results, retries, @Step timings and attachments into your test cases and releases. Playwright Java screenshots and traces are captured automatically when a test drives a browser, but nothing here requires Playwright. Setup, configuration and troubleshooting: https://github.com/m00nreport/junit5-reporter")
        url.set("https://github.com/m00nreport/junit5-reporter")

        licenses {
            license {
                name.set("MIT License")
                url.set("https://opensource.org/licenses/MIT")
            }
        }

        developers {
            developer {
                id.set("m00nreport")
                name.set("M00N Team")
                email.set("lazariev.oleg@gmail.com")
            }
        }

        scm {
            connection.set("scm:git:git://github.com/m00nreport/junit5-reporter.git")
            developerConnection.set("scm:git:ssh://github.com:m00nreport/junit5-reporter.git")
            url.set("https://github.com/m00nreport/junit5-reporter")
        }
    }
}
