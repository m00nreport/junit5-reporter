# M00N Playwright JUnit Reporter

[![Maven Central](https://img.shields.io/maven-central/v/com.m00nreport/m00n-junit-reporter.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/com.m00nreport/m00n-junit-reporter)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A **Playwright JUnit** reporter for [M00N Report](https://m00nreport.com) that sends test results in real-time to your dashboard. Designed for **Playwright Java** browser automation with the JUnit Jupiter test platform.

## ✨ Features

- 🎭 **Built for Playwright** - First-class Playwright Java integration with automatic artifact capture
- 🚀 **Real-time streaming** - See tests as they run via WebSocket
- 📝 **Automatic step tracking** - Use `@Step` annotation with AspectJ - no boilerplate code!
- 🔗 **Declarative case linking** - Use `@TestCaseId` to link autotests to manual test cases (single, multi, parametrized)
- 🔄 **Retry tracking** - Full support for `@RetryingTest` with attempt history
- 📸 **Rich attachments** - Screenshots, videos, Playwright traces
- 🏷️ **Display names** - Use `@DisplayName` for readable test titles
- ⚡ **Parallel execution** - Thread-safe for concurrent tests

---

## 📦 Installation

### Gradle (Kotlin DSL)

```kotlin
plugins {
    java
}

java {
    toolchain {
        languageVersion.set(JavaLanguageVersion.of(21))
    }
}

repositories {
    mavenCentral()
}

dependencies {
    // M00N Playwright JUnit Reporter
    implementation("com.m00nreport:m00n-junit-reporter:1.5.1")
    
    // AspectJ for automatic step interception (no boilerplate!)
    implementation("org.aspectj:aspectjrt:1.9.22")
    
    // JUnit 5
    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    
    // Playwright (required)
    implementation("com.microsoft.playwright:playwright:1.48.0")
    
    // JUnit Pioneer for @RetryingTest (recommended for flaky tests)
    testImplementation("org.junit-pioneer:junit-pioneer:2.3.0")
    
    // Logging
    implementation("ch.qos.logback:logback-classic:1.5.12")
    
    // Assertions (optional)
    testImplementation("org.assertj:assertj-core:3.26.3")
}

// AspectJ configuration for automatic @Step tracking
val aspectjWeaverConfig by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    aspectjWeaverConfig("org.aspectj:aspectjweaver:1.9.22")
}

tasks.test {
    useJUnitPlatform()
    
    // Enable AspectJ agent for automatic step tracking
    doFirst {
        val aspectjWeaver = aspectjWeaverConfig.singleFile
        jvmArgs("-javaagent:${aspectjWeaver.absolutePath}")
    }
    
    // Run tests sequentially for consistent M00N reporting
    maxParallelForks = 1
    
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}
```

### Maven

```xml
<dependencies>
    <!-- M00N Playwright JUnit Reporter -->
    <dependency>
        <groupId>com.m00nreport</groupId>
        <artifactId>m00n-junit-reporter</artifactId>
        <version>1.5.1</version>
    </dependency>
    
    <!-- AspectJ Runtime -->
    <dependency>
        <groupId>org.aspectj</groupId>
        <artifactId>aspectjrt</artifactId>
        <version>1.9.22</version>
    </dependency>
    
    <!-- JUnit 5 -->
    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>5.11.3</version>
        <scope>test</scope>
    </dependency>
    
    <!-- Playwright (required) -->
    <dependency>
        <groupId>com.microsoft.playwright</groupId>
        <artifactId>playwright</artifactId>
        <version>1.48.0</version>
    </dependency>
</dependencies>

<build>
    <plugins>
        <plugin>
            <groupId>org.apache.maven.plugins</groupId>
            <artifactId>maven-surefire-plugin</artifactId>
            <version>3.2.5</version>
            <configuration>
                <argLine>-javaagent:${settings.localRepository}/org/aspectj/aspectjweaver/1.9.22/aspectjweaver-1.9.22.jar</argLine>
            </configuration>
        </plugin>
    </plugins>
</build>
```

---

## ⚙️ Configuration

### 1. Create `src/test/resources/m00n.properties`

```properties
# Required: M00N Report server URL
m00n.serverUrl=https://m00nreport.com

# Required: Project API key (get from M00N Report dashboard)
m00n.apiKey=m00n_your_project_api_key

# Optional: Run/launch name
m00n.launch=My Playwright Tests

# Optional: Tags (comma-separated)
m00n.tags=smoke,regression

# Optional: Debug mode
m00n.debug=false

# Optional: Custom attributes
m00n.attribute.environment=staging
m00n.attribute.browser=chromium

# Recognized by the dashboard - shown on the launch card.
# Wire to your CI env vars or set directly.
m00n.attribute.branch=${env.GITHUB_REF_NAME}
m00n.attribute.triggered_by=${env.GITHUB_ACTOR}
```

#### Recognized attribute keys

The dashboard renders these keys specially on each launch card:

| Key | Type | Shown as | Example value |
|-----|------|----------|---------------|
| `branch` | `string` | Branch icon + value | `main`, `feature/checkout-v2` |
| `triggered_by` | `string` | User icon + value | `oleg-dev`, `oleg@m00nreport.com` |

Any other `m00n.attribute.*` key is stored on the run and available via the API, but is not surfaced on the launch card.

### 2. Enable Extension Auto-Detection

Create `src/test/resources/junit-platform.properties`:

```properties
junit.jupiter.extensions.autodetection.enabled=true
```

### 3. Configure AspectJ (for automatic step tracking)

Create `src/test/resources/META-INF/aop.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<aspectj>
    <aspects>
        <aspect name="com.m00nreport.reporter.M00NStepAspect"/>
    </aspects>
    <weaver options="-verbose">
        <!-- Include your test packages -->
        <include within="com.example..*"/>
        <!-- Include M00N reporter -->
        <include within="com.m00nreport.reporter..*"/>
    </weaver>
</aspectj>
```

### 4. Environment Variables (Alternative)

```bash
export M00N_SERVER_URL=https://m00nreport.com
export M00N_API_KEY=m00n_your_project_api_key
export M00N_LAUNCH="CI Build #${BUILD_NUMBER}"
export M00N_TAGS="ci,${BRANCH_NAME}"
```

---

## 🏗️ Playwright Project Structure (Recommended)

```
src/test/
├── java/com/example/
│   ├── tests/                    # Playwright test classes
│   │   ├── BasePlaywrightTest.java  # Base class with browser lifecycle
│   │   ├── LoginTests.java          # Login page tests
│   │   └── CheckoutTests.java       # Checkout flow tests
│   └── pages/                    # Playwright Page Objects with @Step
│       ├── LoginPage.java           # Login page locators & actions
│       └── CheckoutPage.java        # Checkout page locators & actions
└── resources/
    ├── m00n.properties           # M00N configuration
    ├── junit-platform.properties # JUnit configuration
    ├── META-INF/aop.xml          # AspectJ configuration
    └── logback-test.xml          # Logging configuration
```

---

## 🎭 Automatic Step Tracking with AspectJ

With AspectJ, simply annotate methods with `@Step` and they're automatically tracked - no factory classes needed!

### Step 1: Create Page Objects with `@Step`

```java
package com.example.pages;

import com.m00nreport.reporter.annotations.Step;
import com.microsoft.playwright.Page;
import com.microsoft.playwright.Locator;

/**
 * Page Object for the Login page.
 * Methods annotated with @Step are automatically tracked as test steps.
 */
public class LoginPage {
    
    private final Page page;
    private final Locator usernameInput;
    private final Locator passwordInput;
    private final Locator loginButton;
    private final Locator errorMessage;
    private final Locator welcomeMessage;

    public LoginPage(Page page) {
        this.page = page;
        this.usernameInput = page.locator("#username");
        this.passwordInput = page.locator("#password");
        this.loginButton = page.locator("button[type='submit']");
        this.errorMessage = page.locator(".error-message");
        this.welcomeMessage = page.locator(".welcome");
    }

    @Step("Open login page")
    public void open() {
        page.navigate("https://example.com/login");
        page.waitForLoadState();
    }

    @Step("Enter username")
    public void enterUsername(String username) {
        usernameInput.fill(username);
    }

    @Step("Enter password")
    public void enterPassword(String password) {
        passwordInput.fill(password);
    }

    @Step("Click login button")
    public void clickLogin() {
        loginButton.click();
        page.waitForLoadState();
    }

    @Step("Verify login successful")
    public boolean isLoggedIn() {
        return welcomeMessage.isVisible();
    }

    @Step("Get error message")
    public String getErrorMessage() {
        return errorMessage.textContent();
    }
}
```

### Step 2: Create Base Test Class

```java
package com.example.tests;

import com.m00nreport.reporter.M00NReporter;
import com.m00nreport.reporter.M00NStep;
import com.m00nreport.reporter.model.AttachmentData;
import com.m00nreport.reporter.model.TestResult;
import com.microsoft.playwright.*;
import org.junit.jupiter.api.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.Paths;

/**
 * Base class for all Playwright tests.
 */
public abstract class BasePlaywrightTest {

    private static Playwright playwright;
    private static Browser browser;
    
    protected BrowserContext context;
    protected Page page;
    
    protected static final Path ARTIFACTS_DIR = Paths.get("test-results");
    
    private String currentTestId;
    private String currentTestName;

    @BeforeAll
    static void launchBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch(new BrowserType.LaunchOptions()
            .setHeadless(true));
        
        try {
            Files.createDirectories(ARTIFACTS_DIR.resolve("traces"));
            Files.createDirectories(ARTIFACTS_DIR.resolve("videos"));
        } catch (Exception e) {
            System.err.println("Failed to create artifacts dirs: " + e.getMessage());
        }
    }

    @AfterAll
    static void closeBrowser() {
        if (browser != null) browser.close();
        if (playwright != null) playwright.close();
    }

    @BeforeEach
    void setUp(TestInfo testInfo) {
        context = browser.newContext(new Browser.NewContextOptions()
            .setViewportSize(1280, 720)
            .setRecordVideoDir(ARTIFACTS_DIR.resolve("videos")));
        
        page = context.newPage();
        
        context.tracing().start(new Tracing.StartOptions()
            .setScreenshots(true)
            .setSnapshots(true));
        
        this.currentTestName = testInfo.getDisplayName();
        this.currentTestId = M00NStep.current()
            .map(TestResult::getTestId)
            .orElse(null);
    }

    @AfterEach
    void tearDown() {
        if (M00NStep.isCurrentTestFailed() && currentTestId != null) {
            captureFailureArtifacts(currentTestId);
        }
        
        if (context != null) {
            try { context.close(); } catch (Exception ignored) {}
        }
    }

    private void captureFailureArtifacts(String testId) {
        // Screenshot
        try {
            byte[] screenshot = page.screenshot();
            M00NReporter.getInstance().attachToTest(testId,
                AttachmentData.screenshot("failure.png", screenshot));
        } catch (Exception e) {}
        
        // Trace
        try {
            Path tracePath = ARTIFACTS_DIR.resolve("traces/trace.zip");
            context.tracing().stop(new Tracing.StopOptions().setPath(tracePath));
            byte[] traceBytes = Files.readAllBytes(tracePath);
            M00NReporter.getInstance().attachToTest(testId,
                AttachmentData.trace("trace.zip", traceBytes));
        } catch (Exception e) {}
    }
}
```

### Step 3: Write Your Tests

```java
package com.example.tests;

import com.example.pages.LoginPage;
import org.junit.jupiter.api.*;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Login tests using AspectJ-based step tracking.
 * No PageFactory needed - just create page objects directly!
 */
@DisplayName("Login Tests")
class LoginTests extends BasePlaywrightTest {

    private LoginPage loginPage;

    @BeforeEach
    void setUpPage() {
        // Just create the page object directly - AspectJ handles the rest!
        loginPage = new LoginPage(page);
    }

    @Test
    @DisplayName("✅ Should login with valid credentials")
    void testValidLogin() {
        // Each method call is automatically tracked as a step!
        loginPage.open();
        loginPage.enterUsername("testuser");
        loginPage.enterPassword("password123");
        loginPage.clickLogin();
        
        assertTrue(loginPage.isLoggedIn(), "User should be logged in");
    }

    @Test
    @DisplayName("❌ Should reject invalid password")
    void testInvalidPassword() {
        loginPage.open();
        loginPage.enterUsername("testuser");
        loginPage.enterPassword("wrongpassword");
        loginPage.clickLogin();
        
        assertFalse(loginPage.isLoggedIn());
        assertEquals("Invalid credentials", loginPage.getErrorMessage());
    }
}
```

---

## 📝 Step Tracking Options

### Option 1: AspectJ (Recommended - Zero Boilerplate!)

With AspectJ enabled, just annotate methods with `@Step`:

```java
public class MyPage {
    @Step("Navigate to page")
    public void open() { /* ... */ }
    
    @Step  // Auto-generates title from method name
    public void clickSubmitButton() { /* ... */ }
}

// Usage - steps are automatically tracked!
MyPage page = new MyPage(playwrightPage);
page.open();           // Tracked: "Navigate to page"
page.clickSubmitButton();  // Tracked: "Click submit button"
```

### Option 2: Manual Steps

For tests without page objects:

```java
@Test
void testManualSteps() {
    M00NStep.current().ifPresent(test -> {
        var step1 = test.addStep("Step 1: Setup", "setup");
        // ... do setup ...
        test.completeStep(step1, true, null);
        
        var step2 = test.addStep("Step 2: Action", "action");
        // ... do action ...
        test.completeStep(step2, true, null);
    });
}
```

---

## 🔄 Retry Support

Use JUnit Pioneer's `@RetryingTest` for flaky tests:

```java
import org.junitpioneer.jupiter.RetryingTest;

@RetryingTest(maxAttempts = 3, name = "🔄 Flaky API call - Attempt {index}")
void flakyApiTest() {
    // M00N Report tracks each attempt separately
    // and marks the test as "flaky" if it passes on retry
    var response = callUnstableApi();
    assertEquals(200, response.status());
}
```

---

## 🔗 Linking Autotests to Test Cases - `@TestCaseId`

Use `@TestCaseId` to declaratively link a JUnit test to one or more
manual test cases in M00N Report. The reporter injects `[TC-N]` markers
into the test name and the ingest service resolves them to case IDs -
no backend setup required.

> Available since `1.4.0`.

### Single case

```java
import com.m00nreport.reporter.annotations.TestCaseId;

@Test
@TestCaseId("634207")
void homepageLoads() { ... }
```

### One test covers multiple cases (non-parametrized)

```java
@Test
@TestCaseId("634199, 634203, 634205")
void smokeCoversThreeCases() {
    // All three cases (TC-634199, TC-634203, TC-634205) link to this run.
    // Useful when a single autotest validates several acceptance criteria.
}
```

### Parametrized - invocation N links to value[N-1]

```java
@ParameterizedTest
@ValueSource(strings = {"a", "b", "c", "d", "e", "f"})
@TestCaseId(parametrized = true,
            value = "634199, 634203, 634205, 634206, 377482, 2878215")
void perInvocationLinks(String input) {
    // invocation 1 → TC-634199, invocation 2 → TC-634203, ...
}
```

### Single case across every parametrized invocation

```java
@ParameterizedTest
@ValueSource(strings = {"/docs/page", "/docs/locator"})
@TestCaseId("634300")
void sharedCase(String path) {
    // TC-634300 links to both invocations.
}
```

### Class-level fallback (method-level fully overrides)

```java
@TestCaseId("100000")        // applies to every method without its own
class CheckoutSuite {

    @Test
    void usesClassLevel() { ... }    // → TC-100000

    @Test
    @TestCaseId("100099")
    void overridesClassLevel() { ... } // → TC-100099 only (100000 suppressed)
}
```

### Multiple autotests on the same case

Different `@Test` methods can carry the same `@TestCaseId` - the case
will show all of them as linked autotests:

```java
@Test
@TestCaseId("5")
void uiCoversCase5()  { ... }

@Test
@TestCaseId("5")     // same case, second autotest
void apiCoversCase5() { ... }
```

### Behavior summary

| Annotation | Test type | Result |
|---|---|---|
| `@TestCaseId("N")` | `@Test` | 1 case linked |
| `@TestCaseId("A, B, C")` | `@Test` | all 3 cases linked to that test |
| `@TestCaseId(parametrized=true, value="A,B,C,D")` | `@ParameterizedTest` | invocation K → value[K-1] |
| `@TestCaseId("N")` | `@ParameterizedTest` | same case on every invocation |
| Mismatched usage (e.g. parametrized=true on `@Test`) | - | reporter logs warning, uses first value |

---

## 📎 Attachment Types

```java
import com.m00nreport.reporter.model.AttachmentData;

// Screenshot
AttachmentData.screenshot("failure.png", screenshotBytes);

// Video
AttachmentData.video("recording.webm", videoBytes);

// Trace (Playwright)
AttachmentData.trace("trace.zip", traceBytes);

// Log file
AttachmentData.log("console.log", logBytes);

// Generic file
AttachmentData.file("data.json", jsonBytes, "application/json");
```

---

## 📊 Configuration Reference

| Property | Env Variable | Default | Description |
|----------|-------------|---------|-------------|
| `m00n.serverUrl` | `M00N_SERVER_URL` | - | Server URL (required) |
| `m00n.apiKey` | `M00N_API_KEY` | - | Project API key (required) |
| `m00n.enabled` | `M00N_ENABLED` | `true` | Enable/disable reporter |
| `m00n.launch` | `M00N_LAUNCH` | `Playwright Java Tests` | Run/launch name |
| `m00n.tags` | `M00N_TAGS` | - | Comma-separated tags |
| `m00n.debug` | `M00N_DEBUG` | `false` | Enable debug logging |
| `m00n.timeout` | `M00N_TIMEOUT` | `30000` | HTTP timeout (ms) |
| `m00n.maxRetries` | `M00N_MAX_RETRIES` | `3` | Max retry attempts |
| `m00n.attribute.*` | - | - | Custom attributes |

---

## 🔧 Logback Configuration

Create `src/test/resources/logback-test.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder>
            <pattern>%d{HH:mm:ss.SSS} %highlight(%-5level) %cyan([%logger{20}]) - %msg%n</pattern>
        </encoder>
    </appender>
    
    <!-- M00N Reporter - show INFO and above -->
    <logger name="com.m00nreport.reporter" level="INFO" />
    
    <!-- Playwright - reduce noise -->
    <logger name="com.microsoft.playwright" level="WARN" />
    
    <root level="INFO">
        <appender-ref ref="CONSOLE" />
    </root>
</configuration>
```

---

## 🚀 Running Tests

### From Command Line

```bash
# Run all tests
./gradlew test

# Run specific test class
./gradlew test --tests "com.example.tests.LoginTests"

# Run with custom configuration
./gradlew test -Dm00n.launch="CI Build #123" -Dm00n.tags="smoke"

# Run in headed mode (for debugging)
./gradlew test -Dplaywright.headless=false
```

### From IntelliJ IDEA

1. Open the project in IntelliJ IDEA
2. Right-click on a test class or method
3. Select "Run 'TestName'"
4. Results stream to M00N Report in real-time!

---

## 📝 License

MIT License - See [LICENSE](LICENSE) for details.

## 🔗 Links

- [M00N Report Dashboard](https://m00nreport.com)
- [Documentation](https://m00nreport.com/documentation)
- [Issue Tracker](https://github.com/m00nreport/junit5-reporter/issues)
