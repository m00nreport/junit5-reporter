# m00n-junit-reporter

[![Maven Central](https://img.shields.io/maven-central/v/com.m00nreport/m00n-junit-reporter.svg?label=Maven%20Central)](https://central.sonatype.com/artifact/com.m00nreport/m00n-junit-reporter)
[![License: MIT](https://img.shields.io/badge/License-MIT-yellow.svg)](https://opensource.org/licenses/MIT)

The library subproject. Everything published to Maven Central as
`com.m00nreport:m00n-junit-reporter` is built from here.

**Documentation lives in the [repository README](../README.md).** Installation, configuration,
`@Step`, `@TestCaseId`, attachments, Playwright, retries and troubleshooting are all there, in one
place, so there is no second copy to fall out of date.

## Layout

```
src/main/java/com/m00nreport/reporter/
  M00NTestExecutionListener   Run-level lifecycle. Registered via ServiceLoader, always loads.
  M00NExtension               Per-test lifecycle, retry detection, Playwright capture on failure.
                              Registered as a JUnit Extension service, so it needs
                              junit.jupiter.extensions.autodetection.enabled=true.
  M00NStepAspect              The @Step aspect. Applied by the AspectJ agent at class load.
  M00NConfig                  Settings resolution: -D property, then environment, then
                              m00n.properties.
  M00NHttpClient              Ingest transport, retries and error classification.
  M00NReporter                Payload assembly and the attachment upload queue.
  M00NPlaywright              Reflective Playwright access. No compile-time dependency on it.

src/main/resources/META-INF/
  aop.xml                     Bundled weaver config. Users do not write their own.
  services/                   ServiceLoader registration for the listener and the extension.
```

## Building

Run Gradle from the repository root, not from this directory; the wrapper lives there.

```bash
./gradlew :m00n-junit-reporter:test
./gradlew :m00n-junit-reporter:build
./gradlew :m00n-junit-reporter:publishToMavenLocal
```
