# M00N Playwright JUnit Reporter

[![CI](https://github.com/m00nreport/junit5-reporter/actions/workflows/ci.yml/badge.svg)](https://github.com/m00nreport/junit5-reporter/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.m00nreport/m00n-junit-reporter.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/com.m00nreport/m00n-junit-reporter)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A **Playwright JUnit** reporter for [M00N Report](https://m00nreport.com) that streams test results in real-time. Designed for **Playwright Java** browser automation with the JUnit Jupiter test platform.

## ✨ Features

- 🎭 **Built for Playwright** - First-class Playwright Java integration
- 🚀 **Real-time streaming** - Watch tests as they run via WebSocket
- 📝 **Automatic step tracking** - `@Step` annotation with AspectJ (no boilerplate!)
- 🔗 **Test case linking** - `@TestCaseId` annotation links autotests to manual cases (single, multi, parametrized)
- 🔄 **Retry tracking** - Full support for `@RetryingTest`
- 📸 **Rich attachments** - Screenshots, videos, Playwright traces
- ⚡ **Thread-safe** - Parallel test execution support

## 🚀 Quick Start

### 1. Add Dependencies

**Gradle (Kotlin DSL):**

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    // M00N Playwright JUnit Reporter (includes AspectJ runtime)
    implementation("com.m00nreport:m00n-junit-reporter:1.5.1")

    // JUnit Jupiter
    testImplementation("org.junit.jupiter:junit-jupiter:5.11.3")
    
    // Playwright
    implementation("com.microsoft.playwright:playwright:1.48.0")
}

// AspectJ Weaver for automatic @Step tracking
val aspectjWeaverConfig by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    aspectjWeaverConfig("org.aspectj:aspectjweaver:1.9.22")
}

tasks.test {
    useJUnitPlatform()
    
    // Enable AspectJ agent
    doFirst {
        val aspectjWeaver = aspectjWeaverConfig.singleFile
        jvmArgs("-javaagent:${aspectjWeaver.absolutePath}")
    }
}
```

### 2. Configure

Create `src/test/resources/m00n.properties`:

```properties
m00n.serverUrl=https://m00nreport.com
m00n.apiKey=m00n_your_api_key_here
m00n.launch=Playwright Tests

# Optional: show branch + user on the launch card in the M00N dashboard.
# Wire these to your CI env vars (Gradle systemProperty + System.getenv) or set them directly.
m00n.attribute.branch=${env.GITHUB_REF_NAME}
m00n.attribute.triggered_by=${env.GITHUB_ACTOR}
```

Create `src/test/resources/junit-platform.properties`:

```properties
junit.jupiter.extensions.autodetection.enabled=true
```

**That's it!** No `aop.xml` needed - it's bundled in the reporter.

### 3. Use @Step in Your Code

```java
import com.m00nreport.reporter.annotations.Step;

public class LoginPage {
    private final Page page;
    
    public LoginPage(Page page) {
        this.page = page;
    }
    
    @Step("Open login page")
    public void open() {
        page.navigate("https://example.com/login");
    }
    
    @Step("Login with username")
    public void login(String username, String password) {
        page.locator("#username").fill(username);
        page.locator("#password").fill(password);
        page.locator("button[type='submit']").click();
    }
}
```

### 4. Run Tests

```bash
./gradlew test
```

Results stream to M00N Report in real-time! 🎉

## 📖 Full Documentation

See the [full documentation](m00n-junit-reporter/README.md) for:

- **Page Object pattern** with `@Step` annotation
- **BasePlaywrightTest class** for automatic screenshot/video/trace capture
- **`@TestCaseId` linking** - single, multi-case, and parametrized mappings
- **Retry support** with JUnit Pioneer's `@RetryingTest`
- **Rich attachments** - screenshots, videos, Playwright traces
- **Configuration reference** for customization

## 📁 Project Structure

```
m00n-junit-reporter/
├── m00n-junit-reporter/     # The Playwright JUnit reporter library
│   ├── src/main/java/        # Source code
│   └── README.md             # Full documentation
├── examples/                 # Example Playwright tests
│   └── src/test/java/        # Playwright test examples with Page Objects
└── README.md                 # This file
```

## 🔧 Building from Source

```bash
# Clone the repository
git clone https://github.com/m00nreport/junit5-reporter.git
cd m00n-junit-reporter

# Build
./gradlew build

# Publish to local Maven
./gradlew publishToMavenLocal

# Run examples
./gradlew :examples:test
```

## 📝 License

MIT License - See [LICENSE](LICENSE) for details.

## 🔗 Links

- [M00N Report Dashboard](https://m00nreport.com)
- [Documentation](https://m00nreport.com/documentation/reporters/junit)
- [Issue Tracker](https://github.com/m00nreport/junit5-reporter/issues)
