package com.m00nreport.reporter;

import com.m00nreport.reporter.model.TestResult;
import org.junit.jupiter.api.extension.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * Optional JUnit 5 Extension for video capture on test failure.
 * 
 * <p><b>Note:</b> Screenshots and traces are captured automatically by M00NExtension
 * (which is auto-registered via ServiceLoader). This extension is only needed if you
 * want video capture, which requires special handling after context close.</p>
 * 
 * <h2>For Most Users - Just Screenshots/Traces:</h2>
 * <p>No extension needed! Just register your page:</p>
 * <pre>{@code
 * class MyTests {
 *     @BeforeEach
 *     void setUp() {
 *         page = context.newPage();
 *         M00NPlaywright.setPage(page);      // Screenshots work automatically!
 *         M00NPlaywright.setContext(context); // Traces work automatically!
 *     }
 * }
 * }</pre>
 * 
 * <h2>For Video Capture - Add This Extension:</h2>
 * <pre>{@code
 * @ExtendWith(M00NPlaywrightExtension.class)  // Only for video
 * class MyTests {
 *     @BeforeEach
 *     void setUp() {
 *         context = browser.newContext(new Browser.NewContextOptions()
 *             .setRecordVideoDir(Paths.get("videos")));
 *         page = context.newPage();
 *         
 *         M00NPlaywright.setPage(page);
 *         M00NPlaywright.setContext(context);
 *     }
 *     
 *     @AfterEach
 *     void tearDown() {
 *         context.close();  // Video is captured after this
 *     }
 * }
 * }</pre>
 * 
 * @see M00NPlaywright
 * @see M00NExtension
 */
public class M00NPlaywrightExtension implements AfterEachCallback {
    
    private static final Logger log = LoggerFactory.getLogger(M00NPlaywrightExtension.class);
    
    /**
     * Capture video after context is closed (in user's @AfterEach).
     * Videos are only available after the context that recorded them is closed.
     */
    @Override
    public void afterEach(ExtensionContext context) {
        // Only capture video if test failed
        if (!M00NStep.isCurrentTestFailed()) {
            return;
        }
        
        String testId = M00NStep.current()
            .map(TestResult::getTestId)
            .orElse(null);
        
        if (testId != null) {
            // Capture video (context should be closed by user's @AfterEach at this point)
            M00NPlaywright.captureVideo(testId);
        }
    }
}
