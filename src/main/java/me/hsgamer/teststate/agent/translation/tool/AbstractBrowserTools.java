package me.hsgamer.teststate.agent.translation.tool;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.microsoft.playwright.Page;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public abstract class AbstractBrowserTools {
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    protected static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();
    protected final Page page;
    protected final BrowserInteractionLog interactionLog;

    protected AbstractBrowserTools(Page page, BrowserInteractionLog interactionLog) {
        this.page = page;
        this.interactionLog = interactionLog;
    }

    protected <T> T logAndReturn(T formatted) {
        interactionLog.add(formatted);
        return formatted;
    }
}
