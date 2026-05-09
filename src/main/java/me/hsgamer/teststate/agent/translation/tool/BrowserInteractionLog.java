package me.hsgamer.teststate.agent.translation.tool;

import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.ArrayList;
import java.util.List;

public class BrowserInteractionLog {
    private static final Logger logger = LoggerFactory.getLogger(BrowserInteractionLog.class);
    private final List<Object> interactionLog = new ArrayList<>();

    public void add(Object step) {
        interactionLog.add(step);
    }

    public boolean isEmpty() {
        return interactionLog.isEmpty();
    }

    @Tool("Get a clean summary of all browser interactions and notes performed so far. MUST be called before finishing the translation.")
    public Object getInteractionLog() {
        logger.info("Tool [GETINTERACTIONLOG]");
        if (interactionLog.isEmpty()) return "No interactions recorded yet.";
        return interactionLog;
    }

    public List<Object> getRawLog() {
        return interactionLog;
    }
}
