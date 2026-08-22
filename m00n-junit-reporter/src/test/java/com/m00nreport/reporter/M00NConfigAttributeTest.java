package com.m00nreport.reporter;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import java.util.HashSet;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.LinkedHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Custom attributes put a branch name and a committer on the launch card, so they are what
 * makes a CI run identifiable at all. They resolve from a system property, then an environment
 * variable, then the properties file - the same order as every other setting.
 *
 * ${env.VAR} inside the properties file is interpolated by this library: nothing in Gradle or
 * Maven touches a .properties file we load ourselves at runtime.
 *
 * The env lookup is injected rather than read from the process, because Java has no portable way
 * to set an environment variable for a running JVM.
 */
class M00NConfigAttributeTest {

    private final Set<String> propertiesToClear = new HashSet<>();

    @AfterEach
    void clearSystemProperties() {
        propertiesToClear.forEach(System::clearProperty);
        propertiesToClear.clear();
    }

    private void setProperty(String key, String value) {
        propertiesToClear.add(key);
        System.setProperty(key, value);
    }

    /** A stand-in environment. Enumerable, because M00N_ATTRIBUTE_* has to be discovered. */
    private static Map<String, String> env(String... pairs) {
        Map<String, String> e = new LinkedHashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            e.put(pairs[i], pairs[i + 1]);
        }
        return e;
    }

    private static Properties file(String... pairs) {
        Properties p = new Properties();
        for (int i = 0; i < pairs.length; i += 2) {
            p.setProperty(pairs[i], pairs[i + 1]);
        }
        return p;
    }

    // -------------------------------------------------------- the properties file

    @Test
    @DisplayName("a literal value in the properties file still works")
    void literalFromFile() {
        Map<String, String> attrs = M00NConfig.resolveAttributes(
                file("m00n.attribute.branch", "main"), env());

        assertEquals("main", attrs.get("branch"));
    }

    // --------------------------------------------- system property and environment

    @Test
    @DisplayName("a system property sets an attribute, like every other setting")
    void fromSystemProperty() {
        setProperty("m00n.attribute.branch", "release/4.2");

        Map<String, String> attrs = M00NConfig.resolveAttributes(file(), env());

        assertEquals("release/4.2", attrs.get("branch"));
    }

    @Test
    @DisplayName("an environment variable sets an attribute")
    void fromEnvironmentVariable() {
        // The only channel a CI job reliably has. M00N_ATTRIBUTE_TRIGGERED_BY maps
        // to triggered_by: the env half is upper snake by convention, the attribute
        // half is what appears on the launch card.
        Map<String, String> attrs = M00NConfig.resolveAttributes(
                file(), env("M00N_ATTRIBUTE_TRIGGERED_BY", "oleg"));

        assertEquals("oleg", attrs.get("triggered_by"));
    }

    @Test
    @DisplayName("precedence matches every other setting: system property, then env, then file")
    void precedence() {
        setProperty("m00n.attribute.branch", "from-sysprop");

        Map<String, String> attrs = M00NConfig.resolveAttributes(
                file("m00n.attribute.branch", "from-file"),
                env("M00N_ATTRIBUTE_BRANCH", "from-env"));
        assertEquals("from-sysprop", attrs.get("branch"));

        System.clearProperty("m00n.attribute.branch");
        attrs = M00NConfig.resolveAttributes(
                file("m00n.attribute.branch", "from-file"),
                env("M00N_ATTRIBUTE_BRANCH", "from-env"));
        assertEquals("from-env", attrs.get("branch"));

        attrs = M00NConfig.resolveAttributes(file("m00n.attribute.branch", "from-file"), env());
        assertEquals("from-file", attrs.get("branch"));
    }

    // ---------------------------------------------------------- interpolation

    @Test
    @DisplayName("${env.VAR} in the properties file resolves")
    void interpolatesEnvPlaceholder() {
        // The exact form the README documents.
        Map<String, String> attrs = M00NConfig.resolveAttributes(
                file("m00n.attribute.branch", "${env.GITHUB_REF_NAME}"),
                env("GITHUB_REF_NAME", "feat/parity"));

        assertEquals("feat/parity", attrs.get("branch"));
    }

    @Test
    @DisplayName("an unresolvable ${env.VAR} drops the attribute instead of sending it literally")
    void unresolvedPlaceholderIsDropped() {
        // Sending the placeholder is worse than sending nothing: it puts a broken string on
        // every launch card and reads as our fault rather than a missing CI variable.
        Map<String, String> attrs = M00NConfig.resolveAttributes(
                file("m00n.attribute.branch", "${env.GITHUB_REF_NAME}"), env());

        assertFalse(attrs.containsKey("branch"),
                "an unset CI variable means no branch information, not a literal placeholder");
        assertNull(attrs.get("branch"));
    }

    @Test
    @DisplayName("a placeholder inside a longer value resolves in place")
    void interpolatesWithinAValue() {
        Map<String, String> attrs = M00NConfig.resolveAttributes(
                file("m00n.attribute.build", "gh-${env.GITHUB_RUN_NUMBER}"),
                env("GITHUB_RUN_NUMBER", "417"));

        assertEquals("gh-417", attrs.get("build"));
    }

    @Test
    @DisplayName("two placeholders in one value both resolve")
    void interpolatesTwice() {
        Map<String, String> attrs = M00NConfig.resolveAttributes(
                file("m00n.attribute.origin", "${env.A}/${env.B}"),
                env("A", "gitlab", "B", "runner-7"));

        assertEquals("gitlab/runner-7", attrs.get("origin"));
    }

    // ------------------------------------------------------------- hostile input

    @Test
    @DisplayName("an empty value is dropped rather than reported as an empty attribute")
    void emptyValueIsDropped() {
        Map<String, String> attrs = M00NConfig.resolveAttributes(
                file("m00n.attribute.branch", ""), env());

        assertFalse(attrs.containsKey("branch"));
    }

    @Test
    @DisplayName("an attribute with no name is ignored")
    void bareNamespaceKeyIsIgnored() {
        Map<String, String> attrs = M00NConfig.resolveAttributes(
                file("m00n.attribute.", "orphan"), env());

        assertTrue(attrs.isEmpty(), "m00n.attribute. with nothing after it names nothing");
    }

    @Test
    @DisplayName("unrelated env vars and properties are not swept in")
    void onlyTheAttributeNamespace() {
        Map<String, String> attrs = M00NConfig.resolveAttributes(
                file("m00n.launch", "Nightly", "unrelated", "x"),
                env("M00N_API_KEY", "m00n_secret", "PATH", "/usr/bin"));

        assertTrue(attrs.isEmpty(),
                "resolved " + attrs + "; the API key in particular must never become an attribute");
    }

    @Test
    @DisplayName("a malformed placeholder is left alone rather than half-parsed")
    void malformedPlaceholder() {
        Map<String, String> attrs = M00NConfig.resolveAttributes(
                file("m00n.attribute.weird", "${env.UNCLOSED"), env("UNCLOSED", "x"));

        assertEquals("${env.UNCLOSED", attrs.get("weird"));
    }
}
