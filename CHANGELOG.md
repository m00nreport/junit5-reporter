# Changelog

All notable changes to this project will be documented in this file.

The format is based on [Keep a Changelog](https://keepachangelog.com/en/1.1.0/),
and this project adheres to [Semantic Versioning](https://semver.org/spec/v2.0.0.html).

## [Unreleased]

## [1.5.0] - 2026-05-26

### Changed
- **Step streaming is now batched.** Per-step events used to fire one HTTP POST each; they now coalesce in a 200 ms / 100-step window, mirroring the JS Playwright reporter. Under parallel test load (many `@Step` calls), this cuts step-stream HTTP traffic by 10–100× without changing the dashboard's real-time experience.
- Same endpoint (`/api/ingest/v2/steps/stream`) and payload shape (`{ items: [...] }`) — backend unchanged.
- Failure mode is still fire-and-forget; if a batch POST drops, the test's final `/test/complete` payload still carries the full step list, so the dashboard stays consistent.

### Added
- `StepBatcher` — thread-safe, size-or-time bounded buffer with a single-threaded daemon scheduler. Producers never block on network I/O.

## [1.4.1] - 2026-05-26

### Security
- Bump `com.google.code.gson:gson` 2.11.0 → 2.14.0 to address CVSS 6.9 (Medium) vulnerability flagged on the older version. Gson is used internally for serializing reporter payloads — no API change.

## [1.4.0] - 2026-05-26

### Added
- **`@TestCaseId` annotation** — declarative linking of JUnit 5 tests to M00N test cases, ReportPortal-compatible syntax:
  - `@TestCaseId("634207")` — link a single test to one case.
  - `@TestCaseId("634199, 634203, 634205")` — link a single non-parametrized test to multiple cases (one autotest covering several acceptance criteria).
  - `@TestCaseId(parametrized = true, value = "634199, 634203, ...")` on `@ParameterizedTest` — invocation N links to `value[N-1]`.
  - Class-level annotation acts as a fallback; method-level fully overrides class-level.
  - Multiple `@Test` methods may share the same case number, aggregating coverage from multiple autotests on one case.
- Reporter injects `[TC-N]` markers into the test display name; the existing M00N ingest extractor resolves them to case IDs — **no backend changes required**.

## [1.2.4] - 2026-01-14

### Fixed
- **Critical: @ParameterizedTest name mismatch** - Fixed bug where parameterized tests would show "No active test found" and stay in "RUNNING" state forever
  - Root cause: `getTestDisplayName()` was falling back to method name instead of using `context.getDisplayName()` which includes parameter values
  - Now correctly uses context display name for @ParameterizedTest, @RepeatedTest, and all test templates
  - Example: `"1: Test with params [value1] [value2]"` now matches correctly between start and complete
- **Critical: TestWatcher lifecycle order** - Fixed "No active test found" warnings for ALL tests
  - Root cause: `afterEach` was clearing `reportedInCurrentTest` flag BEFORE TestWatcher callbacks ran
  - TestWatcher callbacks (`testSuccessful`, `testFailed`) run AFTER `afterEach` in JUnit 5
  - Fix: Don't clear the flag in `afterEach`; it's reset in `beforeEach` for the next test

## [1.2.3] - 2026-01-14

### Fixed (Code Review Feedback)
- **DRY: Primitive array formatters** - Extracted common logic into `formatPrimitiveArrayGeneric()` using `IntFunction<String>`, eliminating duplicate code across 8 overloads
- **Security: setAccessible() handling** - Added graceful handling for Java 17+ module restrictions (`InaccessibleObjectException`), with debug logging instead of silent failures
- **Robustness: Video filename extraction** - Video attachments now use actual filename from path (e.g., `test-video.webm`) instead of hardcoded `video.webm`
- **Robustness: Null checks** - Added null check for video path supplier return value to prevent NPE
- **Robustness: parseRetryInfo null safety** - Returns empty string instead of null displayName to prevent downstream NPEs
- **Constants: Configurable limits** - Extracted magic numbers to named constants:
  - `MAX_ARG_LENGTH = 200` - Step argument truncation limit
  - `MAX_ARRAY_ITEMS = 5` - Array items before truncation
- **Duplicate artifact prevention** - Added `SCREENSHOT_CAPTURED` and `TRACE_CAPTURED` thread-local flags to prevent duplicate attachments when both M00NExtension and base test classes attempt to capture artifacts

### Added
- `M00NPlaywright.isScreenshotCaptured()` - Check if screenshot already captured for current test
- `M00NPlaywright.isTraceCaptured()` - Check if trace already captured for current test
- `M00NPlaywright.markScreenshotCaptured()` - Mark screenshot as captured (for manual capture)
- `M00NPlaywright.markTraceCaptured()` - Mark trace as captured (for manual capture)

## [1.2.2] - 2026-01-14

### Added
- **Automatic Playwright artifact capture** - Screenshots and traces captured on test failure:
  - Works with ANY custom base test class or extension (no inheritance required!)
  - Artifacts captured IMMEDIATELY on failure, BEFORE any `@AfterEach` runs
  - Compatible with custom extensions that close browser in teardown
- **Zero-config Playwright detection** - No code changes needed!
  - Auto-detects `Page` and `BrowserContext` fields via reflection
  - Just add dependency + config file, run tests
  - Scans class hierarchy (parent classes too)
- **`PlaywrightTestProvider` interface** - Optional explicit contract:
  - Implement `getPage()` and `getContext()` if you prefer explicit registration
  - Useful for wrapper classes like `MainPage`
- **`M00NPlaywright` utility class** - Alternative manual registration API:
  - `M00NPlaywright.setPage(page)` - enables screenshot capture on failure
  - `M00NPlaywright.setContext(context)` - enables trace capture on failure
  - Uses reflection to avoid compile-time Playwright dependency
- **`M00NPlaywrightExtension`** - Optional extension for video capture:
  - Only needed if you want video recording (requires context close first)
- **`@Step` placeholder support** - Step titles now support parameter placeholders:
  - `{paramName}` - replaced with parameter value by name (e.g., `@Step("Opening {url}")`)
  - `{0}`, `{1}`, `{2}` - replaced with parameter value by index
  - Long strings (>200 chars) are automatically truncated
  - Arrays are formatted nicely with item count
- **`@ParameterizedTest` compatibility** - Parameterized tests now work correctly with unique test names

### Fixed
- **Critical: Incorrect retry count detection** - Fixed bug where parameterized test invocation numbers (e.g., `#599`, `#1599`) were incorrectly interpreted as retry attempts
- Retry detection now ONLY matches explicit "Attempt N" patterns from JUnit Pioneer's `@RetryingTest`
- `[test-template-invocation:#N]` patterns in unique IDs are no longer treated as retry indicators
- Tests without `@RetryingTest` now correctly show retry=0 instead of high invocation numbers

## [1.2.1] - 2026-01-14

### Fixed
- **@RetryingTest compatibility** - Fixed "NamespacedHierarchicalStore cannot be modified after it has been closed" error
- Attachments now display correctly when using `@RetryingTest` with retries
- Used thread-local tracking instead of extension context store for reported status
- Added safe fallback when accessing test context after store closure

## [1.2.0] - 2026-01-14

### Added
- **Bundled `aop.xml`** - Users no longer need to create their own AspectJ configuration file
- **Transitive AspectJ runtime** - `aspectjrt` is now included automatically

### Changed
- Simplified setup - only need to add `aspectjweaver` dependency and `-javaagent`
- Updated documentation to reflect zero-config AspectJ approach

### Removed
- Manual `aop.xml` creation requirement (it's bundled in the JAR)
- Manual `aspectjrt` dependency requirement (it's transitive now)

## [1.1.0] - 2026-01-14

### Added
- **AspectJ-based step tracking** - Automatic `@Step` interception without StepProxy or PageFactory
- `M00NStepAspect` for load-time weaving of `@Step` annotated methods
- Zero-boilerplate step tracking - just annotate methods and they're tracked!

### Changed
- Recommended approach is now AspectJ (simpler, no factory classes needed)
- Updated documentation to reflect AspectJ-first approach
- Updated all examples to use concrete classes instead of interfaces

### Deprecated
- `StepProxy.create()` - still works but AspectJ is recommended
- Interface-based Page Objects - concrete classes are simpler with AspectJ

## [1.0.0] - 2026-01-14

### Added
- Initial release of M00N JUnit 5 Reporter
- Real-time test result streaming via WebSocket
- JUnit 5 extension with automatic registration via ServiceLoader

### Step Tracking
- `@Step` annotation for Page Object methods
- `StepProxy.create()` for manual step tracking (legacy approach)
- Steps appear in M00N Report with timing and status
- Manual step API via `TestResult.addStep()`

### Retry Support
- Full support for JUnit Pioneer's `@RetryingTest`
- Each retry attempt tracked separately
- Flaky test detection and marking

### Attachments
- Screenshot attachment via `AttachmentData.screenshot()`
- Video attachment via `AttachmentData.video()`
- Trace attachment via `AttachmentData.trace()`
- Log attachment via `AttachmentData.log()`
- Generic file attachment via `AttachmentData.file()`
- Async upload with retry logic

### Playwright Integration
- `M00NStep.current()` for current test access
- `M00NStep.isCurrentTestFailed()` for conditional artifact capture
- Base test class pattern for artifact capture

### Configuration
- `m00n.properties` file configuration
- Environment variable support
- System property overrides
- Multiple configuration sources with precedence

### Properties
| Property | Description |
|----------|-------------|
| `m00n.serverUrl` | Ingest server URL (required) |
| `m00n.apiKey` | Project API key (required) |
| `m00n.enabled` | Enable/disable reporter |
| `m00n.launch` | Run name |
| `m00n.tags` | Comma-separated tags |
| `m00n.debug` | Enable debug logging |
| `m00n.timeout` | HTTP timeout (ms) |
| `m00n.maxRetries` | Max retry attempts |
| `m00n.attribute.*` | Custom attributes |

### Other
- Thread-safe parallel test execution
- `@DisplayName` support for readable test titles
- Graceful error handling
- Connection pooling for HTTP client
- AtomicInteger counters for thread safety

[Unreleased]: https://github.com/m00nsolutions/m00n-junit-reporter/compare/v1.0.0...HEAD
[1.0.0]: https://github.com/m00nsolutions/m00n-junit-reporter/releases/tag/v1.0.0
