# M00N JUnit 5 Reporter

[![CI](https://github.com/m00nreport/junit5-reporter/actions/workflows/ci.yml/badge.svg)](https://github.com/m00nreport/junit5-reporter/actions/workflows/ci.yml)
[![Maven Central](https://img.shields.io/maven-central/v/com.m00nreport/m00n-junit-reporter.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/com.m00nreport/m00n-junit-reporter)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

A JUnit 5 reporter for [M00N Report](https://m00nreport.com). It registers itself through the JUnit
Platform ServiceLoader and streams results while the suite runs: tests, steps, retries, errors and
attachments.

Playwright Java is supported on top of it, not required by it. The reporter declares no Playwright
dependency and reaches Playwright only by reflection, so a plain JUnit suite with no browser
anywhere works unchanged.

## Features

- Results stream as the suite runs. Nothing is buffered until the end, so a suite killed halfway
  still has everything up to that point in the dashboard.
- `@Step` on any method becomes a reported step, via AspectJ load-time weaving. No factory classes,
  no proxy wiring.
- `@TestCaseId` links an autotest to manual cases: one case, several cases, or one case per
  invocation of a `@ParameterizedTest`.
- Retry tracking for JUnit Pioneer's `@RetryingTest`, each attempt reported as its own attempt.
- Playwright screenshots and traces on failure, captured before your `@AfterEach` closes the
  browser, with no code in your tests.
- A reporting failure never fails your suite. Every reporter call site catches its own errors.

## Requirements

Java 17 or newer; CI runs the suite on 17 and 21. JUnit Jupiter 5.11 or newer, with
`junit-platform-launcher` on the test runtime classpath.

An SLF4J binding on the test classpath. The reporter ships `slf4j-api` only. Without a binding every
diagnostic it emits is discarded, including the line saying it is disabled, so a misconfigured run
looks exactly like a working one.

The AspectJ weaver plus a `-javaagent` flag, only if you use `@Step`. Playwright Java, only if your
tests drive a browser.

## Installation

### Gradle (Kotlin DSL)

```kotlin
repositories {
    mavenCentral()
}

dependencies {
    testImplementation("com.m00nreport:m00n-junit-reporter:1.5.1")

    testImplementation(platform("org.junit:junit-bom:5.11.3"))
    testImplementation("org.junit.jupiter:junit-jupiter")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")

    // Any SLF4J binding. Without one the reporter logs nothing at all.
    testRuntimeOnly("ch.qos.logback:logback-classic:1.5.12")

    // Only for @RetryingTest
    testImplementation("org.junit-pioneer:junit-pioneer:2.3.0")

    // Only if your tests drive a browser
    testImplementation("com.microsoft.playwright:playwright:1.48.0")
}

// Only for @Step. The weaver goes in its own configuration so the agent can be
// pointed at a single resolved jar.
val aspectjWeaver by configurations.creating {
    isCanBeConsumed = false
    isCanBeResolved = true
}

dependencies {
    aspectjWeaver("org.aspectj:aspectjweaver:1.9.22")
}

tasks.test {
    useJUnitPlatform()

    doFirst {
        jvmArgs("-javaagent:${aspectjWeaver.singleFile.absolutePath}")
    }

    // Each forked JVM loads its own reporter and starts its own launch, so more
    // than one fork splits a single test run across several launch cards.
    maxParallelForks = 1
}
```

`aspectjrt` arrives transitively at compile scope. You do not need to declare it.

### Maven

```xml
<dependencies>
    <dependency>
        <groupId>com.m00nreport</groupId>
        <artifactId>m00n-junit-reporter</artifactId>
        <version>1.5.1</version>
        <scope>test</scope>
    </dependency>

    <dependency>
        <groupId>org.junit.jupiter</groupId>
        <artifactId>junit-jupiter</artifactId>
        <version>5.11.3</version>
        <scope>test</scope>
    </dependency>

    <!-- Any SLF4J binding. Without one the reporter logs nothing at all. -->
    <dependency>
        <groupId>ch.qos.logback</groupId>
        <artifactId>logback-classic</artifactId>
        <version>1.5.12</version>
        <scope>test</scope>
    </dependency>

    <!-- Only for @Step. Declared so Maven downloads the jar argLine points at. -->
    <dependency>
        <groupId>org.aspectj</groupId>
        <artifactId>aspectjweaver</artifactId>
        <version>1.9.22</version>
        <scope>test</scope>
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

That `aspectjweaver` dependency is what puts the jar in your local repository. Without it the path
resolves to nothing and the JVM refuses to start, before a single test runs.

## Quick Start

Create `src/test/resources/m00n.properties`:

```properties
m00n.serverUrl=https://m00nreport.com
m00n.apiKey=m00n_your_project_api_key
m00n.launch=Checkout suite
```

Create `src/test/resources/junit-platform.properties`:

```properties
junit.jupiter.extensions.autodetection.enabled=true
```

The second file is not optional. The run-level listener always loads, but the per-test extension is
published as a JUnit `Extension` service and JUnit ignores those unless auto-detection is on. Skip it
and you get a launch with zero tests in it. If you would rather not enable auto-detection globally,
annotate your test classes with `@M00NTest` instead.

Then run the suite as usual:

```bash
./gradlew test
```

No `aop.xml` of your own is needed. The reporter ships one that weaves every package except the JDK
and a list of common frameworks.

## Configuration

Every setting resolves from three sources, highest first: a `-D` system property, an environment
variable, then `m00n.properties`. That file is read from the classpath, and failing that from the
working directory.

| Property | Env variable | Default | Description |
|---|---|---|---|
| `m00n.serverUrl` | `M00N_SERVER_URL` | none | Required. Unset, the reporter disables itself and the suite runs unreported. |
| `m00n.apiKey` | `M00N_API_KEY` | none | Required. Identifies both organization and project. |
| `m00n.enabled` | `M00N_ENABLED` | `true` | Only the exact value `false`, in any case, disables it. Anything else, a typo included, leaves it on. |
| `m00n.launch` | `M00N_LAUNCH` | `Playwright Java Tests` | Title on the launch card. |
| `m00n.tags` | `M00N_TAGS` | none | Comma-separated. Blank entries are dropped. |
| `m00n.debug` | `M00N_DEBUG` | `false` | Logs request bodies and responses at DEBUG level. |
| `m00n.timeout` | `M00N_TIMEOUT` | `30000` | HTTP timeout in ms. An unparseable value falls back to the default. |
| `m00n.maxRetries` | `M00N_MAX_RETRIES` | `3` | Total attempts per request, not retries on top of a first try. `3` means three tries; `0` and `1` both mean one try and no retry. Backoff doubles from 1s, capped at 5s. |
| `m00n.attribute.<name>` | none | none | Custom metadata on the run. Read from `m00n.properties` only, see below. |

### Attributes

`m00n.attribute.<name>` attaches metadata to the run. The dashboard renders two keys specially on the
launch card, `branch` and `triggered_by`. Any other key is stored on the run and readable through the
API.

```properties
m00n.attribute.environment=staging
m00n.attribute.browser=chromium
```

Attributes are the one setting that does not follow the priority order above. In 1.5.1 they are read
from `m00n.properties` and nowhere else, and the value is used exactly as written. A `-D` property
does not set one, an environment variable does not set one, and a `${env.VAR}` placeholder is not
substituted: it reaches the server as that literal text and shows up on the launch card.

To get CI values onto a run on 1.5.1, use the two settings that do read the environment:

```bash
M00N_LAUNCH="$GITHUB_REF_NAME #$GITHUB_RUN_NUMBER" M00N_TAGS="ci,$GITHUB_REF_NAME" ./gradlew test
```

## Steps

Annotate any method. The weaver picks it up wherever it lives, so page objects, helpers and the test
class itself all work.

```java
import com.m00nreport.reporter.annotations.Step;

public class LoginPage {
    private final Page page;

    public LoginPage(Page page) {
        this.page = page;
    }

    @Step("Open the login page")
    public void open() {
        page.navigate("https://example.com/login");
    }

    @Step("Sign in as {username}")
    public void signIn(String username, String password) {
        page.locator("#username").fill(username);
        page.locator("#password").fill(password);
        page.locator("button[type='submit']").click();
    }

    @Step  // Title taken from the method name: "Click submit"
    public void clickSubmit() {
        page.locator("#submit").click();
    }
}
```

`{username}` resolves by parameter name, which needs compilation with `-parameters`. `{0}` and `{1}`
resolve by position and always work. A step that throws is reported failed with its stack trace, and
the exception propagates unchanged.

Steps need the weaver and the `-javaagent` flag from Installation. Without them the annotation is
inert: the methods run normally, no steps appear, and nothing is logged to say so.

Two alternatives if you cannot attach a JVM agent. `StepProxy.create(MyPage.class, new MyPageImpl())`
wraps an interface in a dynamic proxy that reads `@Step` off the interface methods. Or build steps by
hand, which streams them the same way:

```java
@Test
void checkoutSucceeds() {
    M00NStep.current().ifPresent(test -> {
        var step = test.addStep("Add item to cart", "action");
        // ...
        test.completeStep(step, true, null);
    });
}
```

## Attachments

```java
import com.m00nreport.reporter.M00NReporter;
import com.m00nreport.reporter.model.AttachmentData;

M00NReporter reporter = M00NReporter.getInstance();

reporter.attach(AttachmentData.screenshot("failure.png", screenshotBytes));
reporter.attach(AttachmentData.video("recording.webm", videoBytes));
reporter.attach(AttachmentData.trace("trace.zip", traceBytes));
reporter.attach(AttachmentData.fromBytes("cart.json", jsonBytes, "application/json"));
reporter.attach(AttachmentData.fromPath("report.html", Path.of("build/report.html"), "text/html"));
```

`attach` resolves the running test from a thread-local. Use `reporter.attachToTest(testId, attachment)`
only when you are attaching from another thread, or after the test has already ended.

A factory returns `null` above 200 MB and logs why, and `attach(null)` is a no-op, so an oversized
file costs you the attachment rather than the run. Uploads are queued on a background executor and
drained before the run is closed.

## Linking Tests to Cases

`@TestCaseId` carries M00N case numbers, the numeric part of the `TC-N` shown in the UI. The reporter
rewrites the test name with `[TC-N]` markers and the ingest service resolves them, so nothing has to
be set up on the M00N side.

```java
import com.m00nreport.reporter.annotations.TestCaseId;

@Test
@TestCaseId("634207")
void homepageLoads() { }

@Test
@TestCaseId("634199, 634203, 634205")
void smokeCoversThreeCases() { }

@ParameterizedTest
@ValueSource(strings = {"a", "b", "c"})
@TestCaseId(parametrized = true, value = "634199, 634203, 634205")
void oneCasePerInvocation(String input) { }
```

| Annotation | On | Result |
|---|---|---|
| `@TestCaseId("N")` | `@Test` | One case linked. |
| `@TestCaseId("A, B, C")` | `@Test` | All three cases link to that one test. |
| `@TestCaseId(parametrized = true, value = "A, B, C")` | `@ParameterizedTest` | Invocation K links to value K. |
| `@TestCaseId("N")` | `@ParameterizedTest` | The same case links to every invocation. |
| `@TestCaseId("N")` | a class | Applies to every method that has no annotation of its own. |

A method-level annotation replaces the class-level one outright; the two are never merged. Two
different test methods may carry the same case number, and the case then shows both as linked
autotests. Available since 1.4.0.

## Playwright Integration

Declare `Page` and `BrowserContext` fields on your test class or any superclass and the reporter
finds them by reflection when a test fails. Screenshots and traces need no code in your tests.

```java
public abstract class BaseTest {
    protected static Playwright playwright;
    protected static Browser browser;
    protected BrowserContext context;
    protected Page page;

    @BeforeAll
    static void launchBrowser() {
        playwright = Playwright.create();
        browser = playwright.chromium().launch();
    }

    @BeforeEach
    void openPage() {
        context = browser.newContext();
        context.tracing().start(new Tracing.StartOptions()
            .setScreenshots(true).setSnapshots(true));
        page = context.newPage();
    }

    @AfterEach
    void closeContext() {
        context.close();
    }

    @AfterAll
    static void closeBrowser() {
        browser.close();
        playwright.close();
    }
}
```

A trace only exists if you started tracing, as above. If your `Page` is not a field on the test
instance, register it explicitly with `M00NPlaywright.setPage(page)` and
`M00NPlaywright.setContext(context)`.

Capture runs in the failure path, before any `@AfterEach`, so your own teardown closing the browser
does not race it. Videos are the exception: the file only exists once the context that recorded it is
closed, so they need `@ExtendWith(M00NPlaywrightExtension.class)` on the test class, plus
`setRecordVideoDir(...)` on the context.

## Retries

Retries come from JUnit Pioneer's `@RetryingTest`. Each attempt is reported separately, and a test
that fails and then passes is marked flaky.

```java
import org.junitpioneer.jupiter.RetryingTest;

@RetryingTest(maxAttempts = 3, name = "Flaky API call - Attempt {index}")
void flakyApiTest() {
    assertEquals(200, callUnstableApi().status());
}
```

The `name` is load-bearing on 1.5.1. Attempts are recognised by matching `Attempt <number>` in the
display name, and Pioneer's default names invocations `[1]` and `[2]`, which the reporter cannot tell
apart from `@ParameterizedTest` invocations. Without `Attempt {index}` in the name the attempts are
reported as separate tests rather than as retries of one.

The reporter's own `@Retry` annotation is deprecated and has no effect. Do not use it.

## What Gets Sent

To `serverUrl`: the launch name, tags and attributes; per test the display name and title path, the
test class as a file path, status, timings and retry index; error message, exception type and stack
trace; step titles, categories, nesting, timings and status; and any attachment you or the Playwright
integration produced.

Not sent: your source code, and any environment variable you did not name in the configuration above.
The API key travels in an `X-API-Key` header and is never written to the log, including under
`m00n.debug=true`.

## Troubleshooting

Everything below goes through SLF4J under the logger `com.m00nreport.reporter`, so none of it appears
without a binding on the test classpath.

| Message | Cause |
|---|---|
| `[M00NReporter] Disabled - no serverUrl or apiKey configured` | One of the two required settings did not resolve. In CI this is most often a forked pull request, where the secret expands to an empty string. |
| `[M00NReporter] Server unavailable at {}. Reporter disabled.` | 1.5.1 probes `GET {serverUrl}/` before starting a run and gives up entirely if that is not 2xx. A self-hosted deployment that does not answer 200 on `/` loses reporting even though its ingest endpoints work. |
| `[M00NReporter] Failed to start run: Permanent error: INVALID_API_KEY` | The key was rejected. `API_KEY_REQUIRED` and `PROJECT_NOT_FOUND` arrive in the same shape, sometimes with the server's own message appended after a hyphen. None of the three is retried. |
| `[M00NReporter] Attachment upload rejected: {}. Remaining uploads for this run may be skipped.` | HTTP 413, or the run hit its attachment quota. Attachments already uploaded are kept. |
| `[M00NReporter] Attachment skipped: "..." (N MB) exceeds 200 MB limit` | Over the per-attachment ceiling. The factory returned `null` and nothing was uploaded. |
| `[M00NReporter] No active test found for: {}` | A result arrived with no matching start. Two tests sharing one `@DisplayName` in a class collide this way on 1.5.1, see Known Limitations. |
| `[M00NReporter] No active test found for attachment: {}` | `attach` was called outside a running test. Use `attachToTest(testId, ...)`. |
| Nothing at all in the console | No SLF4J binding on the test classpath. |
| A launch appears with no tests in it | `junit-platform.properties` is missing, or does not enable extension auto-detection. The run-level listener opened the launch; the per-test extension never registered. |
| `@Step` methods produce no steps | The AspectJ agent is not attached. Check that `-javaagent` reached the test JVM. |

## Known Limitations

Tests are keyed by suite plus display name on 1.5.1, not by JUnit's unique id. Two tests in one class
with the same `@DisplayName` overwrite each other, and one test's result is reported against the
other's id. Both look real in the dashboard.

The published jar carries a `logback.xml` at its root. If your project has no logging configuration
of its own, logback uses that one, which sets its own console pattern and writes
`test-results/m00n-reporter.log` under the working directory. A `src/test/resources/logback-test.xml`
takes precedence over it:

```xml
<configuration>
    <appender name="CONSOLE" class="ch.qos.logback.core.ConsoleAppender">
        <encoder><pattern>%d{HH:mm:ss.SSS} %-5level %logger{20} - %msg%n</pattern></encoder>
    </appender>
    <logger name="com.m00nreport.reporter" level="INFO"/>
    <root level="INFO"><appender-ref ref="CONSOLE"/></root>
</configuration>
```

Attributes cannot be driven from CI without writing the value into `m00n.properties`, as described
under Configuration.

## Building from Source

```bash
git clone https://github.com/m00nreport/junit5-reporter.git
cd junit5-reporter
./gradlew build
./gradlew publishToMavenLocal
```

The published library is the `m00n-junit-reporter/` subproject. `examples/` holds Playwright suites
used to exercise it by hand and is not published.

## Support

- 📦 [com.m00nreport:m00n-junit-reporter on Maven Central](https://central.sonatype.com/artifact/com.m00nreport/m00n-junit-reporter)
- 📖 [Documentation](https://m00nreport.com/documentation/reporters/junit)
- 🐛 [Report Issues](https://github.com/m00nreport/junit5-reporter/issues)
- 💬 [Community Discord](https://discord.gg/hzZvyVWS3Q)

## License

MIT License. See [LICENSE](https://github.com/m00nreport/junit5-reporter/blob/main/LICENSE).
