package me.hsgamer.teststate.agent.translation.tool;

import com.microsoft.playwright.Page;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

import java.util.*;

public class SeleniumBrowserTools extends AbstractBrowserTools {
    public SeleniumBrowserTools(Page page, BrowserInteractionLog interactionLog) {
        super(page, interactionLog);
    }

    private Map<String, Object> createSeleniumStep(String command, List<String> targets, String value) {
        Map<String, Object> cmd = new LinkedHashMap<>();
        cmd.put("command", command);
        cmd.put("target", targets.get(0));
        
        List<List<String>> targetVariants = new ArrayList<>();
        for (String t : targets) {
            String strategy = "css";
            if (t.startsWith("id=")) strategy = "id";
            else if (t.startsWith("name=")) strategy = "name";
            else if (t.startsWith("xpath=")) strategy = "xpath";
            else if (t.startsWith("linkText=")) strategy = "linkText";
            
            targetVariants.add(Arrays.asList(t, strategy));
        }
        cmd.put("targets", targetVariants);
        cmd.put("value", value);
        return logAndReturn(cmd);
    }

    @Tool("Navigate the browser to a specific URL (Selenium IDE: open). Target MUST be a full, absolute URL starting with http:// or https://. Returns the exact JSON step recorded.")
    public List<Object> open(@P("The full destination URL") String url) {
        logger.info("Tool [OPEN]: {}", url);
        page.navigate(url);
        return Collections.singletonList(createSeleniumStep("open", Collections.singletonList(url), ""));
    }

    @Tool("Navigate back in browser history (Selenium IDE: runScript with history.back). Returns the exact JSON step recorded.")
    public List<Object> goBack() {
        logger.info("Tool [GOBACK]");
        page.goBack();
        return Collections.singletonList(createSeleniumStep("runScript", Collections.singletonList("window.history.back()"), ""));
    }

    @Tool("Perform a mouse click on a specific element (Selenium IDE: click). Returns the exact JSON step recorded.")
    public List<Object> click(@P("The selectors for the element (provide multiple variants for reliability)") List<String> selectors) {
        logger.info("Tool [CLICK]: {}", selectors);
        String primary = selectors.get(0);
        page.click(primary);
        return Collections.singletonList(createSeleniumStep("click", selectors, ""));
    }

    @Tool("Enter text into an input field (Selenium IDE: type). Returns the exact JSON step recorded.")
    public List<Object> type(
            @P("The selectors for the input field (provide multiple variants for reliability)") List<String> selectors,
            @P("The string of text to enter") String text
    ) {
        logger.info("Tool [TYPE]: {} with text {}", selectors, text);
        String primary = selectors.get(0);
        page.fill(primary, text);
        return Collections.singletonList(createSeleniumStep("type", selectors, text));
    }

    @Tool("Press the Enter key on a focused element (Selenium IDE: sendKeys with ${KEY_ENTER}). Returns the exact JSON step recorded.")
    public List<Object> sendKeys(@P("The selectors for the element to focus (provide multiple variants for reliability)") List<String> selectors) {
        logger.info("Tool [SENDKEYS]: {}", selectors);
        String primary = selectors.get(0);
        page.focus(primary);
        page.keyboard().press("Enter");
        return Collections.singletonList(createSeleniumStep("sendKeys", selectors, "${KEY_ENTER}"));
    }

    @Tool("Pause execution for a fixed duration (Selenium IDE: pause). Returns the exact JSON step recorded. LAST RESORT ONLY: Use waitForElementVisible instead if possible.")
    public List<Object> pause(@P("The duration to wait in milliseconds") int ms) {
        logger.info("Tool [PAUSE]: {}ms", ms);
        page.waitForTimeout(ms);
        return Collections.singletonList(createSeleniumStep("pause", Collections.singletonList(String.valueOf(ms)), ""));
    }

    @Tool("Get the inner text of an element (Selenium IDE: verifyText). Returns the exact JSON step recorded.")
    public Object verifyText(@P("The selectors for the element (provide multiple variants for reliability)") List<String> selectors) {
        logger.info("Tool [VERIFYTEXT]: {}", selectors);
        String primary = selectors.get(0);
        if (page.locator(primary).count() == 0) {
            return "Error: Element not found: " + selectors;
        }
        String text = page.locator(primary).innerText();
        return Collections.singletonList(createSeleniumStep("verifyText", selectors, text));
    }

    @Tool("Verify the title of the current page (Selenium IDE: verifyTitle). Returns the exact JSON step recorded.")
    public Object verifyTitle(@P("The expected title text") String expectedTitle) {
        logger.info("Tool [VERIFYTITLE]: {}", expectedTitle);
        String actualTitle = page.title();
        if (!actualTitle.equals(expectedTitle)) {
            return "Error: Title mismatch. Expected: '" + expectedTitle + "', but found: '" + actualTitle + "'";
        }
        return Collections.singletonList(createSeleniumStep("verifyTitle", Collections.singletonList(expectedTitle), ""));
    }

    @Tool("Verify the current URL of the page (Selenium IDE: executeScript check). Returns the exact JSON step recorded.")
    public Object verifyLocation(@P("The expected URL") String expectedUrl) {
        logger.info("Tool [VERIFYLOCATION]: {}", expectedUrl);
        String actualUrl = page.url();
        if (!actualUrl.equals(expectedUrl)) {
            return "Error: URL mismatch. Expected: '" + expectedUrl + "', but found: '" + actualUrl + "'";
        }
        
        String jsCheck = String.format(
            "if (window.location.href !== %s) { throw new Error('URL Mismatch: expected ' + %s + ' but found ' + window.location.href); }",
            GSON.toJson(expectedUrl), GSON.toJson(expectedUrl));

        return Collections.singletonList(createSeleniumStep("executeScript", Collections.singletonList(jsCheck), ""));
    }

    @Tool("Wait for an element to be visible (Selenium IDE: waitForElementVisible). Returns the exact JSON step recorded.")
    public Object waitForElementVisible(
            @P("The selectors for the element (provide multiple variants for reliability)") List<String> selectors,
            @P("The timeout in milliseconds") int timeout
    ) {
        logger.info("Tool [WAITFORELEMENTVISIBLE]: {} with timeout {}", selectors, timeout);
        String primary = selectors.get(0);
        try {
            page.locator(primary).waitFor(new com.microsoft.playwright.Locator.WaitForOptions()
                .setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE)
                .setTimeout(timeout));
            return Collections.singletonList(createSeleniumStep("waitForElementVisible", selectors, String.valueOf(timeout)));
        } catch (com.microsoft.playwright.PlaywrightException e) {
            return "Error: Element not visible after " + timeout + "ms: " + selectors;
        }
    }

    @Tool("Assert that an element exists in the DOM (Selenium IDE: assertElementPresent). Returns the exact JSON step recorded.")
    public Object assertElementPresent(@P("The selectors for the element (provide multiple variants for reliability)") List<String> selectors) {
        logger.info("Tool [ASSERTELEMENTPRESENT]: {}", selectors);
        String primary = selectors.get(0);
        if (page.locator(primary).count() > 0) {
            return Collections.singletonList(createSeleniumStep("assertElementPresent", selectors, ""));
        } else {
            return "Error: Element not found: " + selectors;
        }
    }

    @Tool("Scroll the window to bring an element into view (Selenium IDE: runScript with scrollIntoView). Returns the exact JSON step recorded.")
    public Object scrollToElement(@P("The selectors for the element (provide multiple variants for reliability)") List<String> selectors) {
        logger.info("Tool [SCROLLTOELEMENT]: {}", selectors);
        String primary = selectors.get(0);
        try {
            page.locator(primary).scrollIntoViewIfNeeded();
            
            String jsSnippet;
            if (primary.startsWith("id=")) {
                jsSnippet = String.format("document.getElementById('%s').scrollIntoView({behavior: 'smooth', block: 'center'})", primary.substring(3));
            } else if (primary.startsWith("name=")) {
                jsSnippet = String.format("document.getElementsByName('%s')[0].scrollIntoView({behavior: 'smooth', block: 'center'})", primary.substring(5));
            } else if (primary.startsWith("xpath=")) {
                jsSnippet = String.format("document.evaluate(\"%s\", document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue.scrollIntoView({behavior: 'smooth', block: 'center'})", primary.substring(6).replace("\"", "\\\""));
            } else {
                String css = primary.startsWith("css=") ? primary.substring(4) : primary;
                jsSnippet = String.format("document.querySelector('%s').scrollIntoView({behavior: 'smooth', block: 'center'})", css.replace("'", "\\'"));
            }

            return Collections.singletonList(createSeleniumStep("runScript", Collections.singletonList(jsSnippet), ""));
        } catch (com.microsoft.playwright.PlaywrightException e) {
            return "Error: Could not scroll to element: " + selectors;
        }
    }

    @Tool("Set the browser window size (Selenium IDE: setWindowSize). Returns the exact JSON step recorded.")
    public List<Object> setWindowSize(
            @P("The width in pixels") int width,
            @P("The height in pixels") int height
    ) {
        logger.info("Tool [SETWINDOWSIZE]: {}x{}", width, height);
        page.setViewportSize(width, height);
        return Collections.singletonList(createSeleniumStep("setWindowSize", Collections.singletonList(width + "x" + height), ""));
    }
}
