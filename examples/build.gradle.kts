plugins {
    id("java")
}

group = "com.m00nreport"
version = "1.0.0"

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

repositories {
    mavenCentral()
}

dependencies {
    // Use the reporter from sibling module (during development)
    // In production, users would use: implementation("io.m00nreport:m00n-junit-reporter:1.0.0")
    implementation(project(":m00n-junit-reporter"))
    
    // Playwright
    implementation("com.microsoft.playwright:playwright:1.57.0")
    
    // JUnit 5
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    
    // JUnit Pioneer for @RetryingTest
    testImplementation("org.junit-pioneer:junit-pioneer:2.3.0")
    
    // Logging implementation
    implementation("ch.qos.logback:logback-classic:1.5.12")
    
    // AssertJ for fluent assertions
    testImplementation("org.assertj:assertj-core:3.26.3")
}

tasks.test {
    useJUnitPlatform()
    
    // Exclude nested classes from test discovery
    exclude("**/*\$*.class")
    
    // Pass system properties to tests
    systemProperty("m00n.serverUrl", System.getProperty("m00n.serverUrl") ?: "")
    systemProperty("m00n.apiKey", System.getProperty("m00n.apiKey") ?: "")
    systemProperty("m00n.launch", System.getProperty("m00n.launch") ?: "Playwright Java Tests")
    systemProperty("m00n.tags", System.getProperty("m00n.tags") ?: "")
    systemProperty("m00n.debug", System.getProperty("m00n.debug") ?: "false")
    systemProperty("m00n.enabled", System.getProperty("m00n.enabled") ?: "true")
    
    // Test output
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    
    maxParallelForks = 1
}

// Task to install Playwright browsers.
//
// Through the injected ExecOperations rather than Project.exec, which Gradle 9
// removed from the script scope: a task that reaches back into the project at
// execution time is what stops the configuration cache from working.
abstract class InstallPlaywrightBrowsers : DefaultTask() {
    @get:javax.inject.Inject
    abstract val execOperations: ExecOperations

    @TaskAction
    fun install() {
        execOperations.exec {
            commandLine("npx", "playwright", "install", "--with-deps")
        }
    }
}

tasks.register<InstallPlaywrightBrowsers>("installPlaywrightBrowsers")
