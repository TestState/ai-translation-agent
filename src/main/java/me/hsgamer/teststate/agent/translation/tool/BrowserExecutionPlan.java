package me.hsgamer.teststate.agent.translation.tool;

import dev.langchain4j.agent.tool.P;
import dev.langchain4j.agent.tool.Tool;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

public class BrowserExecutionPlan {
    private static final Logger logger = LoggerFactory.getLogger(BrowserExecutionPlan.class);
    private String currentPlan = "No plan created yet.";

    @Tool("Update your high-level execution plan (list of steps done and remaining). Use this at the start of the session and every few steps to maintain state.")
    public String updatePlan(@P("The updated plan/task list") String plan) {
        logger.info("Tool [UPDATEPLAN]: Plan updated");
        this.currentPlan = plan;
        return "Plan updated successfully.";
    }

    @Tool("Get your current execution plan and status. Call this frequently to ensure you are on track.")
    public String getPlan() {
        logger.info("Tool [GETPLAN]");
        return this.currentPlan;
    }
}
