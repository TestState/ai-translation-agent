package me.hsgamer.teststate.agent.translation.tool;

import com.microsoft.playwright.Page;
import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;

public class CommonBrowserTools extends AbstractBrowserTools {
    protected static final String ELEMENT_INSPECTION_JS = """
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
            locators.ariaSelector = "aria/" + aria.name;

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

    public CommonBrowserTools(Page page, BrowserInteractionLog interactionLog) {
        super(page, interactionLog);
    }

    @Tool("Get the title of the current page")
    public String getTitle() {
        logger.info("Tool [GETTITLE]");
        return page.title();
    }

    @Tool("Get the current URL of the page")
    public String getUrl() {
        logger.info("Tool [GETURL]");
        return page.url();
    }

    @Tool("Get all possible locator variants for an element (ID, Name, XPath, CSS, ARIA)")
    public String getLocatorVariants(@P("The XPath or CSS selector of the element") String selector) {
        logger.info("Tool [GETLOCATORVARIANTS]: {}", selector);
        if (page.locator(selector).count() == 0) {
            return "Error: Element not found for selector '" + selector + "'";
        }
        return (String) page.locator(selector).evaluate(ELEMENT_INSPECTION_JS);
    }

    @Tool("Get possible locators for an element identified by its ARIA role and name")
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
        return "Note recorded.";
    }
}
