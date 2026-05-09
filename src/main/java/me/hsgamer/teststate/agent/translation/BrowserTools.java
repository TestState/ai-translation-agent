package me.hsgamer.teststate.agent.translation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.microsoft.playwright.Page;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BrowserTools {
    private static final Logger logger = LoggerFactory.getLogger(BrowserTools.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    private final Page page;
    private final java.util.List<String> interactionLog = new java.util.ArrayList<>();
    private String currentPlan = "No plan created yet.";

    private static final String ELEMENT_INSPECTION_JS = """
        el => {
            const getAriaSelectors = (element) => {
                const role = element.getAttribute('role') || element.tagName.toLowerCase();
                const name = element.getAttribute('aria-label') || element.innerText.trim();
                return { role, name };
            };

            const locators = {};
            if (el.id) locators.id = "id=" + el.id;
            if (el.name) locators.name = "name=" + el.name;
            
            // Get ARIA data
            const aria = getAriaSelectors(el);
            locators.aria = aria;

            // Simple XPath
            const getXPath = (element) => {
                if (element.id) return `//*[@id="${element.id}"]`;
                const parts = [];
                while (element && element.nodeType === 1) {
                    let index = 0;
                    for (let sibling = element.previousSibling; sibling; sibling = sibling.previousSibling) {
                        if (sibling.nodeType === Node.DOCUMENT_TYPE_NODE) continue;
                        if (sibling.nodeName === element.nodeName) ++index;
                    }
                    const tagName = element.nodeName.toLowerCase();
                    const pathIndex = (index ? `[${index + 1}]` : '');
                    parts.unshift(`${tagName}${pathIndex}`);
                    element = element.parentNode;
                }
                return parts.length ? `/${parts.join('/')}` : null;
            };
            
            locators.xpath = "xpath=" + getXPath(el);
            locators.css = "css=" + el.tagName.toLowerCase() + (el.className ? "." + Array.from(el.classList).join(".") : "");
            
            // Add attributes for extra context
            const attrs = {};
            for (const attr of el.attributes) {
                attrs[attr.name] = attr.value;
            }
            locators.attributes = attrs;

            return JSON.stringify(locators);
        }
        """;

    public BrowserTools(Page page) {
        this.page = page;
    }

    @Tool("Navigate the browser to a specific URL (Selenium IDE: open). Use this only at the start of a test or when a hard redirect is required. Target MUST be a full, absolute URL starting with http:// or https://.")
    public String open(@P("The full destination URL") String url) {
        logger.info("Tool [OPEN]: {}", url);
        page.navigate(url);
        java.util.Map<String, String> cmd = new java.util.LinkedHashMap<>();
        cmd.put("command", "open");
        cmd.put("target", url);
        cmd.put("value", "");
        String json = GSON.toJson(cmd);
        interactionLog.add(json);
        return json;
    }

    @Tool("Navigate back to the previous page in the browser history (Selenium IDE: runScript with history.back). Use this when the manual script specifies 'go back' or 'return to previous page'.")
    public String goBack() {
        logger.info("Tool [GOBACK]");
        page.goBack();
        java.util.Map<String, String> cmd = new java.util.LinkedHashMap<>();
        cmd.put("command", "runScript");
        cmd.put("target", "window.history.back()");
        cmd.put("value", "");
        String json = GSON.toJson(cmd);
        interactionLog.add(json);
        return json;
    }

    @Tool("Perform a mouse click on a specific element (Selenium IDE: click). Use this for buttons, links, checkboxes, and radio buttons. Ensure the element is visible before clicking.")
    public String click(@P("The XPath or CSS selector identifying the element") String selector) {
        logger.info("Tool [CLICK]: {}", selector);
        page.click(selector);
        java.util.Map<String, String> cmd = new java.util.LinkedHashMap<>();
        cmd.put("command", "click");
        cmd.put("target", selector);
        cmd.put("value", "");
        String json = GSON.toJson(cmd);
        interactionLog.add(json);
        return json;
    }

    @Tool("Enter text into an input field, replacing any existing content (Selenium IDE: type). Use this for textboxes, textareas, and login fields. DO NOT use this for sensitive PII unless explicitly required by the test script.")
    public String type(
            @P("The XPath or CSS selector identifying the input field") String selector,
            @P("The string of text to enter") String text
    ) {
        logger.info("Tool [TYPE]: {} with text {}", selector, text);
        page.fill(selector, text);
        java.util.Map<String, String> cmd = new java.util.LinkedHashMap<>();
        cmd.put("command", "type");
        cmd.put("target", selector);
        cmd.put("value", text);
        String json = GSON.toJson(cmd);
        interactionLog.add(json);
        return json;
    }

    @Tool("Press the Enter key on a focused element (Selenium IDE: sendKeys with ${KEY_ENTER}). Use this to submit forms or trigger search fields after typing.")
    public String sendKeys(@P("The XPath or CSS selector identifying the element to focus") String selector) {
        logger.info("Tool [SENDKEYS]: {}", selector);
        page.focus(selector);
        page.keyboard().press("Enter");
        java.util.Map<String, String> cmd = new java.util.LinkedHashMap<>();
        cmd.put("command", "sendKeys");
        cmd.put("target", selector);
        cmd.put("value", "${KEY_ENTER}");
        String json = GSON.toJson(cmd);
        interactionLog.add(json);
        return json;
    }

    @Tool("Pause execution for a fixed duration (Selenium IDE: pause). Use this only as a last resort when dynamic wait tools fail. Prefer 'waitForElementVisible' for better test stability.")
    public String pause(@P("The duration to wait in milliseconds") int ms) {
        logger.info("Tool [PAUSE]: {}ms", ms);
        page.waitForTimeout(ms);
        java.util.Map<String, String> cmd = new java.util.LinkedHashMap<>();
        cmd.put("command", "pause");
        cmd.put("target", String.valueOf(ms));
        cmd.put("value", "");
        String json = GSON.toJson(cmd);
        interactionLog.add(json);
        return json;
    }

    @Tool("Get the inner text of an element (Selenium IDE: verifyText / assertText)")
    public String verifyText(@P("The XPath or CSS selector of the element") String selector) {
        logger.info("Tool [VERIFYTEXT]: {}", selector);
        if (page.locator(selector).count() == 0) {
            return "Error: Element not found: " + selector;
        }
        String text = page.locator(selector).innerText();
        java.util.Map<String, String> cmd = new java.util.LinkedHashMap<>();
        cmd.put("command", "verifyText");
        cmd.put("target", selector);
        cmd.put("value", text);
        String json = GSON.toJson(cmd);
        interactionLog.add(json);
        return json;
    }

    @Tool("Get the title of the current page (Selenium IDE: storeTitle)")
    public String getTitle() {
        logger.info("Tool [GETTITLE]");
        return page.title();
    }

    @Tool("Get the current URL of the page (Selenium IDE: executeScript return window.location.href)")
    public String getUrl() {
        logger.info("Tool [GETURL]");
        return page.url();
    }

    @Tool("Verify the title of the current page (Selenium IDE: verifyTitle)")
    public String verifyTitle(@P("The expected title text") String expectedTitle) {
        logger.info("Tool [VERIFYTITLE]: {}", expectedTitle);
        String actualTitle = page.title();
        if (!actualTitle.equals(expectedTitle)) {
            return "Error: Title mismatch. Expected: '" + expectedTitle + "', but found: '" + actualTitle + "'";
        }
        java.util.Map<String, String> cmd = new java.util.LinkedHashMap<>();
        cmd.put("command", "verifyTitle");
        cmd.put("target", expectedTitle);
        cmd.put("value", "");
        String json = GSON.toJson(cmd);
        interactionLog.add(json);
        return json;
    }

    @Tool("Verify the current URL of the page (Selenium IDE: executeScript check)")
    public String verifyLocation(@P("The expected URL") String expectedUrl) {
        logger.info("Tool [VERIFYLOCATION]: {}", expectedUrl);
        String actualUrl = page.url();
        if (!actualUrl.equals(expectedUrl)) {
            return "Error: URL mismatch. Expected: '" + expectedUrl + "', but found: '" + actualUrl + "'";
        }
        
        // Modern Selenium IDE uses executeScript to assert custom conditions
        String jsCheck = String.format(
            "if (window.location.href !== %s) { throw new Error('URL Mismatch: expected ' + %s + ' but found ' + window.location.href); }",
            GSON.toJson(expectedUrl), GSON.toJson(expectedUrl));

        java.util.Map<String, String> cmd = new java.util.LinkedHashMap<>();
        cmd.put("command", "executeScript");
        cmd.put("target", jsCheck);
        cmd.put("value", "");
        String json = GSON.toJson(cmd);
        interactionLog.add(json);
        return json;
    }

    @Tool("Get possible Selenium IDE locators for an element identified by its ARIA role and name")
    public String getAriaLocatorVariants(
            @P("The ARIA role of the element (e.g. 'button', 'link', 'textbox')") String role,
            @P("The ARIA name of the element (e.g. the button text or aria-label)") String name
    ) {
        logger.info("Tool [GETARIALOCATORVARIANTS]: role='{}', name='{}'", role, name);
        com.microsoft.playwright.options.AriaRole ariaRole;
        try {
            ariaRole = com.microsoft.playwright.options.AriaRole.valueOf(role.toUpperCase());
        } catch (IllegalArgumentException e) {
            return "Error: Invalid ARIA role '" + role + "'";
        }

        if (page.getByRole(ariaRole, new Page.GetByRoleOptions().setName(name)).count() == 0) {
            return "Error: Element not found for role '" + role + "' and name '" + name + "'";
        }
        return (String) page.getByRole(ariaRole, new Page.GetByRoleOptions().setName(name)).first().evaluate(ELEMENT_INSPECTION_JS);
    }

    @Tool("Wait for an element to be visible (Selenium IDE: waitForElementVisible)")
    public String waitForElementVisible(
            @P("The XPath or CSS selector of the element") String selector,
            @P("The timeout in milliseconds") int timeout
    ) {
        logger.info("Tool [WAITFORELEMENTVISIBLE]: {} with timeout {}", selector, timeout);
        try {
            page.locator(selector).waitFor(new com.microsoft.playwright.Locator.WaitForOptions()
                .setState(com.microsoft.playwright.options.WaitForSelectorState.VISIBLE)
                .setTimeout(timeout));
            java.util.Map<String, String> cmd = new java.util.LinkedHashMap<>();
            cmd.put("command", "waitForElementVisible");
            cmd.put("target", selector);
            cmd.put("value", String.valueOf(timeout));
            String json = GSON.toJson(cmd);
            interactionLog.add(json);
            return json;
        } catch (com.microsoft.playwright.PlaywrightException e) {
            return "Error: Element not visible after " + timeout + "ms: " + selector;
        }
    }

    @Tool("Assert that an element exists in the DOM (Selenium IDE: assertElementPresent)")
    public String assertElementPresent(@P("The XPath or CSS selector of the element") String selector) {
        logger.info("Tool [ASSERTELEMENTPRESENT]: {}", selector);
        if (page.locator(selector).count() > 0) {
            java.util.Map<String, String> cmd = new java.util.LinkedHashMap<>();
            cmd.put("command", "assertElementPresent");
            cmd.put("target", selector);
            cmd.put("value", "");
            String json = GSON.toJson(cmd);
            interactionLog.add(json);
            return json;
        } else {
            return "Error: Element not found: " + selector;
        }
    }

    @Tool("Scroll the window to bring an element into view (Selenium IDE: runScript with scrollIntoView)")
    public String scrollToElement(@P("The XPath or CSS selector of the element") String selector) {
        logger.info("Tool [SCROLLTOELEMENT]: {}", selector);
        try {
            page.locator(selector).scrollIntoViewIfNeeded();
            
            // This script handles common Selenium IDE selector prefixes safely in the browser
            String jsSnippet = String.format(
                "(function(s) { " +
                "  let el; " +
                "  if (s.startsWith('xpath=')) el = document.evaluate(s.substring(6), document, null, XPathResult.FIRST_ORDERED_NODE_TYPE, null).singleNodeValue; " +
                "  else if (s.startsWith('id=')) el = document.getElementById(s.substring(3)); " +
                "  else if (s.startsWith('name=')) el = document.getElementsByName(s.substring(5))[0]; " +
                "  else el = document.querySelector(s.startsWith('css=') ? s.substring(4) : s); " +
                "  if (el) el.scrollIntoView({behavior: 'smooth', block: 'center'}); " +
                "})(%s)", GSON.toJson(selector));

            java.util.Map<String, String> cmd = new java.util.LinkedHashMap<>();
            cmd.put("command", "runScript");
            cmd.put("target", jsSnippet);
            cmd.put("value", "");
            
            String json = GSON.toJson(cmd);
            interactionLog.add(json);
            return json;
        } catch (com.microsoft.playwright.PlaywrightException e) {
            return "Error: Could not scroll to element: " + selector;
        }
    }

    @Tool("Set the browser window size (Selenium IDE: setWindowSize)")
    public String setWindowSize(
            @P("The width in pixels") int width,
            @P("The height in pixels") int height
    ) {
        logger.info("Tool [SETWINDOWSIZE]: {}x{}", width, height);
        page.setViewportSize(width, height);
        java.util.Map<String, String> cmd = new java.util.LinkedHashMap<>();
        cmd.put("command", "setWindowSize");
        cmd.put("target", width + "x" + height);
        cmd.put("value", "");
        String json = GSON.toJson(cmd);
        interactionLog.add(json);
        return json;
    }

    @Tool("Get all possible locator variants for an element (ID, Name, XPath, CSS, ARIA)")
    public String getLocatorVariants(@P("The XPath or CSS selector of the element") String selector) {
        logger.info("Tool [GETLOCATORVARIANTS]: {}", selector);
        if (page.locator(selector).count() == 0) {
            return "Error: Element not found for selector '" + selector + "'";
        }
        return (String) page.locator(selector).evaluate(ELEMENT_INSPECTION_JS);
    }

    @Tool("Get the HTML content of a specific element")
    public String getElementHtml(@P("The XPath or CSS selector of the element") String selector) {
        logger.info("Tool [GETELEMENTHTML]: {}", selector);
        if (page.locator(selector).count() == 0) {
            return "Error: Element not found: " + selector;
        }
        return page.locator(selector).first().innerHTML();
    }

    @Tool("Get the HTML content of the current page")
    public String getHtml() {
        logger.info("Tool [GETHTML]");
        return page.content();
    }

    @Tool("Get the ARIA snapshot of the current page (useful for identifying interactive elements with low token usage)")
    public String getAriaSnapshot() {
        logger.info("Tool [GETARIASNAPSHOT]");
        return page.locator(":root").ariaSnapshot();
    }

    @Tool("Save a manual note about the current session state or a discovery")
    public String addNote(@P("The note to record") String text) {
        logger.info("Tool [ADDNOTE]: {}", text);
        java.util.Map<String, String> cmd = new java.util.LinkedHashMap<>();
        cmd.put("command", "echo");
        cmd.put("target", text);
        cmd.put("value", "");
        cmd.put("comment", "Note");
        String json = GSON.toJson(cmd);
        interactionLog.add(json);
        return "Note recorded.";
    }

    @Tool("Update your high-level execution plan (list of steps done and remaining)")
    public String updatePlan(@P("The updated plan/task list") String plan) {
        logger.info("Tool [UPDATEPLAN]: Plan updated");
        this.currentPlan = plan;
        return "Plan updated successfully.";
    }

    @Tool("Get your current execution plan and status")
    public String getPlan() {
        logger.info("Tool [GETPLAN]");
        return this.currentPlan;
    }

    @Tool("Get a clean summary of all browser interactions and notes performed so far")
    public String getInteractionLog() {
        logger.info("Tool [GETINTERACTIONLOG]");
        if (interactionLog.isEmpty()) return "No interactions recorded yet.";
        return String.join("\n", interactionLog);
    }
}
