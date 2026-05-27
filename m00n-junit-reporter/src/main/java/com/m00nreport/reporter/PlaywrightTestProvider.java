package com.m00nreport.reporter;

/**
 * Interface for test classes to provide Playwright objects for automatic artifact capture.
 * 
 * <p>Implement this interface on your base test class to enable automatic
 * screenshot/trace/video capture on test failure - no manual registration needed!</p>
 * 
 * <h2>Usage:</h2>
 * <pre>{@code
 * public abstract class BaseTest implements PlaywrightTestProvider {
 *     protected BrowserContext context;
 *     protected Page page;
 *     
 *     @Override
 *     public Object getPage() {
 *         return page;
 *     }
 *     
 *     @Override
 *     public Object getContext() {
 *         return context;  // Return null if you don't need trace capture
 *     }
 *     
 *     @BeforeEach
 *     void setUp() {
 *         context = browser.newContext();
 *         page = context.newPage();
 *         // No M00NPlaywright.setPage() needed!
 *     }
 * }
 * }</pre>
 * 
 * <h2>What Gets Captured on Failure:</h2>
 * <ul>
 *   <li><b>Screenshot</b> - if {@link #getPage()} returns non-null</li>
 *   <li><b>Trace</b> - if {@link #getContext()} returns non-null and tracing was started</li>
 *   <li><b>Video</b> - if using {@code @ExtendWith(M00NPlaywrightExtension.class)} and video recording enabled</li>
 * </ul>
 * 
 * <h2>Alternative: Manual Registration</h2>
 * <p>If you prefer not to implement this interface, you can still use manual registration:</p>
 * <pre>{@code
 * @BeforeEach
 * void setUp() {
 *     page = context.newPage();
 *     M00NPlaywright.setPage(page);  // Manual registration
 * }
 * }</pre>
 * 
 * @see M00NPlaywright
 * @see M00NExtension
 */
public interface PlaywrightTestProvider {
    
    /**
     * Returns the Playwright Page object for screenshot capture.
     * 
     * @return The Page object (com.microsoft.playwright.Page), or null if not available
     */
    Object getPage();
    
    /**
     * Returns the Playwright BrowserContext for trace capture.
     * 
     * <p>Override this method if you want trace capture on failure.
     * Make sure to start tracing in your setup:</p>
     * <pre>{@code
     * context.tracing().start(new Tracing.StartOptions()
     *     .setScreenshots(true)
     *     .setSnapshots(true));
     * }</pre>
     * 
     * @return The BrowserContext object (com.microsoft.playwright.BrowserContext), or null
     */
    default Object getContext() {
        return null;
    }
}
