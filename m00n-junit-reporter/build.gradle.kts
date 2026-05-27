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
version = "1.5.1"

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
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("ch.qos.logback:logback-classic:1.5.12")
}

tasks.test {
    useJUnitPlatform()
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
        description.set("JUnit 5 reporter for M00N Report - real-time test result streaming with retry tracking, attachments, and step reporting")
        url.set("https://github.com/m00nsolutions/m00n-junit-reporter")

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
            connection.set("scm:git:git://github.com/m00nsolutions/m00n-junit-reporter.git")
            developerConnection.set("scm:git:ssh://github.com:m00nsolutions/m00n-junit-reporter.git")
            url.set("https://github.com/m00nsolutions/m00n-junit-reporter")
        }
    }
}
