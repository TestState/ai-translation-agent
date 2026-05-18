package me.hsgamer.teststate.agent.translation.tool;

import com.microsoft.playwright.Page;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.*;
import java.util.stream.Collectors;

public class PuppeteerBrowserTools extends AbstractBrowserTools {
    public PuppeteerBrowserTools(Page page, BrowserInteractionLog interactionLog) {
        super(page, interactionLog);
    }

    private Map<String, Object> createStep(String type, Map<String, Object> params) {
        Map<String, Object> step = new LinkedHashMap<>();
        step.put("type", type);
        step.putAll(params);
        return logAndReturn(step);
    }

    private List<List<String>> formatSelectors(List<String> selectors) {
        return selectors.stream()
                .map(s -> {
                    String formatted = s;
                    if (s.startsWith("id=")) {
                        formatted = "#" + s.substring(3);
                    } else if (s.startsWith("name=")) {
                        formatted = "[name=\"" + s.substring(5) + "\"]";
                    } else if (s.startsWith("xpath=")) {
                        formatted = "xpath/" + s.substring(6);
                    } else if (s.startsWith("css=")) {
                        formatted = s.substring(4);
                    } else if (s.startsWith("aria=")) {
                        formatted = "aria/" + s.substring(5);
                    }
                    return Collections.singletonList(formatted);
                })
                .collect(Collectors.toList());
    }

    private String toPlaywrightSelector(String s) {
        if (s == null) return "";
        String trimmed = s.trim();
        if (trimmed.startsWith("xpath/")) {
            return "xpath=" + trimmed.substring(6);
        }
        if (trimmed.startsWith("aria/")) {
            return "text=" + trimmed.substring(5);
        }
        if (trimmed.startsWith("id=")) {
            return "#" + trimmed.substring(3);
        }
        if (trimmed.startsWith("name=")) {
            return "[name=\"" + trimmed.substring(5) + "\"]";
        }
        if (trimmed.startsWith("css=")) {
            return trimmed.substring(4);
        }
        if (trimmed.startsWith("xpath=")) {
            return trimmed.substring(6);
        }
        return trimmed;
    }

    @Tool("Navigate the browser to a specific URL (Chrome DevTools Recorder: navigate). Returns the exact JSON step recorded.")
    public List<Object> open(@P("The full destination URL") String url) {
        logger.info("Tool [OPEN]: {}", url);
        page.navigate(url);
        Map<String, Object> params = new HashMap<>();
        params.put("url", url);
        return Collections.singletonList(createStep("navigate", params));
    }

    @Tool("Perform a mouse click on a specific element (Chrome DevTools Recorder: click). Returns the exact JSON step recorded.")
    public List<Object> click(@P("The selectors for the element (provide multiple variants for reliability)") List<String> selectors) {
        logger.info("Tool [CLICK]: {}", selectors);
        String primary = selectors.get(0);
        page.click(toPlaywrightSelector(primary));
        Map<String, Object> params = new HashMap<>();
        params.put("selectors", formatSelectors(selectors));
        return Collections.singletonList(createStep("click", params));
    }

    @Tool("Enter text into an input field (Chrome DevTools Recorder: change). Returns the exact JSON step recorded.")
    public List<Object> type(
            @P("The selectors for the input field (provide multiple variants for reliability)") List<String> selectors,
            @P("The string of text to enter") String text
    ) {
        logger.info("Tool [TYPE]: {} with text {}", selectors, text);
        String primary = selectors.get(0);
        page.fill(toPlaywrightSelector(primary), text);
        Map<String, Object> params = new HashMap<>();
        params.put("selectors", formatSelectors(selectors));
        params.put("value", text);
        return Collections.singletonList(createStep("change", params));
    }

    @Tool("Press the Enter key on a focused element (Chrome DevTools Recorder: keyDown/keyUp). Returns the list of exact JSON steps recorded.")
    public List<Object> sendKeys(@P("The selectors for the element to focus (provide multiple variants for reliability)") List<String> selectors) {
        logger.info("Tool [SENDKEYS]: {}", selectors);
        String primary = selectors.get(0);
        page.focus(toPlaywrightSelector(primary));
        page.keyboard().press("Enter");
        
        Map<String, Object> params = new HashMap<>();
        params.put("key", "Enter");
        
        List<Object> steps = new ArrayList<>();
        steps.add(createStep("keyDown", params));
        steps.add(createStep("keyUp", params));
        return steps;
    }

    @Tool("Wait for a fixed duration (Chrome DevTools Recorder: waitForExpression with setTimeout). Returns the exact JSON step recorded. LAST RESORT ONLY: Use waitForElementVisible instead if possible.")
    public List<Object> pause(@P("The duration to wait in milliseconds") int ms) {
        logger.info("Tool [PAUSE]: {}ms", ms);
        page.waitForTimeout(ms);
        Map<String, Object> params = new HashMap<>();
        params.put("expression", String.format("new Promise(resolve => setTimeout(() => resolve(true), %d))", ms));
        return Collections.singletonList(createStep("waitForExpression", params));
    }

    @Tool("Wait for an element to be visible (Chrome DevTools Recorder: waitForElement). Returns the exact JSON steps recorded. Includes a short grace period for transitions.")
    public Object waitForElementVisible(
            @P("The selectors for the element (provide multiple variants for reliability)") List<String> selectors,
            @P("The timeout in milliseconds") int timeout
    ) {
        logger.info("Tool [WAITFORELEMENTVISIBLE]: {} with timeout {}", selectors, timeout);
        String primary = selectors.get(0);
        try {
            // Grace period for page transitions
            page.waitForTimeout(300);
            Map<String, Object> pauseParams = new HashMap<>();
            pauseParams.put("expression", "new Promise(resolve => setTimeout(() => resolve(true), 300))");
            Object pauseStep = createStep("waitForExpression", pauseParams);

            page.locator(toPlaywrightSelector(primary)).waitFor(new com.microsoft.playwright.Locator.WaitForOptions()
                .setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE)
                .setTimeout(timeout));
            Map<String, Object> params = new HashMap<>();
            params.put("selectors", formatSelectors(selectors));
            Object waitStep = createStep("waitForElement", params);
            
            return Arrays.asList(pauseStep, waitStep);
        } catch (com.microsoft.playwright.PlaywrightException e) {
            return "Error: Element not visible after " + timeout + "ms: " + selectors;
        }
    }

    @Tool("Set the browser window size (Chrome DevTools Recorder: setViewport). Returns the exact JSON step recorded.")
    public List<Object> setWindowSize(
            @P("The width in pixels") int width,
            @P("The height in pixels") int height
    ) {
        logger.info("Tool [SETWINDOWSIZE]: {}x{}", width, height);
        page.setViewportSize(width, height);
        Map<String, Object> params = new LinkedHashMap<>();
        params.put("width", width);
        params.put("height", height);
        return Collections.singletonList(createStep("setViewport", params));
    }

    @Tool("Scroll an element into view (Chrome DevTools Recorder: scroll). Returns the exact JSON step recorded.")
    public Object scrollToElement(@P("The selectors for the element (provide multiple variants for reliability)") List<String> selectors) {
        logger.info("Tool [SCROLLTOELEMENT]: {}", selectors);
        String primary = selectors.get(0);
        try {
            page.locator(toPlaywrightSelector(primary)).scrollIntoViewIfNeeded();
            Map<String, Object> params = new HashMap<>();
            params.put("selectors", formatSelectors(selectors));
            return Collections.singletonList(createStep("scroll", params));
        } catch (com.microsoft.playwright.PlaywrightException e) {
            return "Error: Could not scroll to element: " + selectors;
        }
    }
}
