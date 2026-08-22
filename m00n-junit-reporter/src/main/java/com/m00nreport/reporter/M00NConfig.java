package com.m00nreport.reporter;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Configuration for M00N Reporter.
 * 
 * <p>Configuration is loaded from multiple sources in priority order:</p>
 * <ol>
 *   <li>System properties (e.g., -Dm00n.serverUrl=...)</li>
 *   <li>Environment variables (e.g., M00N_SERVER_URL=...)</li>
 *   <li>m00n.properties file in classpath or project root</li>
 * </ol>
 * 
 * <p>Higher priority sources override lower priority ones.</p>
 */
public class M00NConfig {
    
    private static final Logger log = LoggerFactory.getLogger(M00NConfig.class);
    private static final String PROPERTIES_FILE = "m00n.properties";
    
    private boolean enabled = true;
    private String serverUrl;
    private String apiKey;
    private String launch;
    private List<String> tags = new ArrayList<>();
    private Map<String, String> attributes = new HashMap<>();
    private boolean debug = false;
    private int timeout = 30000;
    private int maxRetries = 3;
    
    private M00NConfig() {}
    
    /**
     * Creates configuration from all available sources.
     * Priority: System Properties > Environment Variables > Properties File
     */
    public static M00NConfig load() {
        var config = new M00NConfig();
        var props = loadPropertiesFile();
        
        // Enabled flag (can disable reporter entirely)
        var enabledStr = getValue("m00n.enabled", "M00N_ENABLED", props, "true");
        config.enabled = !"false".equalsIgnoreCase(enabledStr);
        
        // Server URL
        config.serverUrl = getValue("m00n.serverUrl", "M00N_SERVER_URL", props, null);
        
        // API Key
        config.apiKey = getValue("m00n.apiKey", "M00N_API_KEY", props, null);
        
        // Launch name
        config.launch = getValue("m00n.launch", "M00N_LAUNCH", props, "Playwright Java Tests");
        
        // Tags
        var tagsStr = getValue("m00n.tags", "M00N_TAGS", props, "");
        if (!tagsStr.isEmpty()) {
            Arrays.stream(tagsStr.split(","))
                .map(String::trim)
                .filter(s -> !s.isEmpty())
                .forEach(config.tags::add);
        }
        
        // Debug mode
        var debugStr = getValue("m00n.debug", "M00N_DEBUG", props, "false");
        config.debug = "true".equalsIgnoreCase(debugStr);
        
        // Timeout
        var timeoutStr = getValue("m00n.timeout", "M00N_TIMEOUT", props, "30000");
        try {
            config.timeout = Integer.parseInt(timeoutStr);
        } catch (NumberFormatException e) {
            config.timeout = 30000;
        }
        
        // Max retries
        var retriesStr = getValue("m00n.maxRetries", "M00N_MAX_RETRIES", props, "3");
        try {
            config.maxRetries = Integer.parseInt(retriesStr);
        } catch (NumberFormatException e) {
            config.maxRetries = 3;
        }
        
        config.attributes.putAll(resolveAttributes(props, System.getenv()));
        
        if (config.isEnabled()) {
            log.info("[M00NReporter] Loaded config: serverUrl={}, launch={}", 
                config.serverUrl, config.launch);
        }
        
        return config;
    }
    
    private static final String ATTRIBUTE_PREFIX = "m00n.attribute.";
    private static final String ATTRIBUTE_ENV_PREFIX = "M00N_ATTRIBUTE_";

    /**
     * Matches the one placeholder form the documentation uses. Anything that does
     * not match is left exactly as written rather than half-parsed.
     */
    private static final Pattern ENV_PLACEHOLDER =
        Pattern.compile("\\$\\{env\\.([A-Za-z_][A-Za-z0-9_]*)\\}");

    /**
     * Resolve custom attributes from every source, in the same priority order as
     * every other setting: system property, then environment variable, then the
     * properties file.
     *
     * <p>They used to come from the properties file alone, which made them the one
     * setting a CI job could not supply - and they are precisely the setting a CI
     * job needs, since branch and committer are what make a launch card
     * identifiable. The documented way round it was to write
     * {@code m00n.attribute.branch=${env.GITHUB_REF_NAME}} in the file, and nothing
     * interpolated that: Gradle and Maven do not touch a properties file this
     * library loads itself at runtime, so the literal placeholder reached the
     * server and appeared on every launch card.</p>
     *
     * <p>Two channels now exist. {@code M00N_ATTRIBUTE_TRIGGERED_BY=oleg} sets the
     * attribute {@code triggered_by} directly, and the {@code ${env.VAR}} form in
     * the file resolves, so the documented example works as written.</p>
     *
     * <p>An unresolved placeholder DROPS the attribute rather than sending the
     * literal text. An unset CI variable means there is no branch information,
     * which is a truthful absence; the placeholder on a launch card is a visible
     * defect that reads as ours.</p>
     *
     * <p>The environment is a parameter rather than a direct
     * {@link System#getenv()} call because Java cannot set an environment variable
     * for a running JVM, and a seam is cheaper than leaving the main path
     * untested.</p>
     */
    static Map<String, String> resolveAttributes(Properties props, Map<String, String> env) {
        Map<String, String> resolved = new LinkedHashMap<>();

        // Lowest priority first; each later source overwrites what it names.
        for (String key : props.stringPropertyNames()) {
            if (key.startsWith(ATTRIBUTE_PREFIX)) {
                store(resolved, key.substring(ATTRIBUTE_PREFIX.length()), props.getProperty(key), env);
            }
        }

        for (Map.Entry<String, String> entry : env.entrySet()) {
            if (entry.getKey().startsWith(ATTRIBUTE_ENV_PREFIX)) {
                var name = entry.getKey().substring(ATTRIBUTE_ENV_PREFIX.length())
                    .toLowerCase(Locale.ROOT);
                store(resolved, name, entry.getValue(), env);
            }
        }

        for (String key : System.getProperties().stringPropertyNames()) {
            if (key.startsWith(ATTRIBUTE_PREFIX)) {
                store(resolved, key.substring(ATTRIBUTE_PREFIX.length()), System.getProperty(key), env);
            }
        }

        return resolved;
    }

    /** Adds one attribute, or nothing at all. Never adds an empty or unresolved value. */
    private static void store(Map<String, String> into, String name, String rawValue,
                              Map<String, String> env) {
        if (name == null || name.isEmpty() || rawValue == null) {
            return;
        }
        var value = interpolate(rawValue, env);
        if (value == null || value.isEmpty()) {
            // Deliberately does not remove an existing lower-priority value: an
            // override that resolves to nothing is not an instruction to forget
            // what the file said.
            return;
        }
        into.put(name, value);
    }

    /** Substitutes ${env.VAR}. Returns null if any placeholder cannot be resolved. */
    private static String interpolate(String value, Map<String, String> env) {
        if (value == null || !value.contains("${env.")) {
            return value;
        }
        var matcher = ENV_PLACEHOLDER.matcher(value);
        var out = new StringBuilder();
        while (matcher.find()) {
            var replacement = env.get(matcher.group(1));
            if (replacement == null || replacement.isEmpty()) {
                return null;
            }
            matcher.appendReplacement(out, Matcher.quoteReplacement(replacement));
        }
        matcher.appendTail(out);
        return out.toString();
    }

    /**
     * Gets value from sources in priority order.
     */
    private static String getValue(String sysProp, String envVar, Properties props, String defaultValue) {
        // 1. System property (highest priority)
        var value = System.getProperty(sysProp);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        
        // 2. Environment variable
        value = System.getenv(envVar);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        
        // 3. Properties file
        value = props.getProperty(sysProp);
        if (value != null && !value.isEmpty()) {
            return value;
        }
        
        return defaultValue;
    }
    
    /**
     * Loads properties from m00n.properties file.
     * Checks: classpath, then project root.
     */
    private static Properties loadPropertiesFile() {
        var props = new Properties();
        
        // Try classpath first
        try (InputStream is = M00NConfig.class.getClassLoader().getResourceAsStream(PROPERTIES_FILE)) {
            if (is != null) {
                props.load(is);
                log.debug("[M00NReporter] Loaded {} from classpath", PROPERTIES_FILE);
                return props;
            }
        } catch (IOException e) {
            log.debug("[M00NReporter] Could not load {} from classpath: {}", PROPERTIES_FILE, e.getMessage());
        }
        
        // Try project root
        var projectRoot = Path.of(PROPERTIES_FILE);
        if (Files.exists(projectRoot)) {
            try (var reader = Files.newBufferedReader(projectRoot)) {
                props.load(reader);
                log.debug("[M00NReporter] Loaded {} from project root", PROPERTIES_FILE);
                return props;
            } catch (IOException e) {
                log.debug("[M00NReporter] Could not load {} from project root: {}", PROPERTIES_FILE, e.getMessage());
            }
        }
        
        return props;
    }
    
    /**
     * Builder for programmatic configuration.
     */
    public static Builder builder() {
        return new Builder();
    }
    
    public boolean isEnabled() {
        return enabled 
            && serverUrl != null && !serverUrl.isEmpty() 
            && apiKey != null && !apiKey.isEmpty();
    }
    
    // Getters
    public boolean getEnabled() { return enabled; }
    public String getServerUrl() { return serverUrl; }
    public String getApiKey() { return apiKey; }
    public String getLaunch() { return launch; }
    public List<String> getTags() { return tags; }
    public Map<String, String> getAttributes() { return attributes; }
    public boolean isDebug() { return debug; }
    public int getTimeout() { return timeout; }
    public int getMaxRetries() { return maxRetries; }
    
    public static class Builder {
        private final M00NConfig config = new M00NConfig();
        
        public Builder enabled(boolean enabled) {
            config.enabled = enabled;
            return this;
        }
        
        public Builder serverUrl(String serverUrl) {
            config.serverUrl = serverUrl;
            return this;
        }
        
        public Builder apiKey(String apiKey) {
            config.apiKey = apiKey;
            return this;
        }
        
        public Builder launch(String launch) {
            config.launch = launch;
            return this;
        }
        
        public Builder tags(List<String> tags) {
            config.tags = new ArrayList<>(tags);
            return this;
        }
        
        public Builder addTag(String tag) {
            config.tags.add(tag);
            return this;
        }
        
        public Builder attributes(Map<String, String> attributes) {
            config.attributes = new HashMap<>(attributes);
            return this;
        }
        
        public Builder addAttribute(String key, String value) {
            config.attributes.put(key, value);
            return this;
        }
        
        public Builder debug(boolean debug) {
            config.debug = debug;
            return this;
        }
        
        public Builder timeout(int timeout) {
            config.timeout = timeout;
            return this;
        }
        
        public Builder maxRetries(int maxRetries) {
            config.maxRetries = maxRetries;
            return this;
        }
        
        public M00NConfig build() {
            return config;
        }
    }
}
