package me.hsgamer.teststate.agent.translation;

import me.hsgamer.teststate.client.Agent;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Optional;

public class AiTranslationAgentApp {
    private static final Logger logger = LoggerFactory.getLogger(AiTranslationAgentApp.class);

    public static void main(String[] args) {
        String hubUrl = Optional.ofNullable(System.getenv("HUB_URL")).orElse("http://localhost:9000/");
        String displayName = Optional.ofNullable(System.getenv("DISPLAY_NAME")).orElse("ai-java-translator");
        String aiApiKey = Optional.ofNullable(System.getenv("AI_API_KEY")).orElse(System.getenv("GOOGLE_API_KEY"));
        String aiBaseUrl = System.getenv("AI_BASE_URL");
        String aiModelName = Optional.ofNullable(System.getenv("AI_MODEL_NAME")).orElse("gpt-4o-mini");

        if (aiApiKey == null || aiApiKey.isEmpty()) {
            logger.error("AI_API_KEY (or GOOGLE_API_KEY) environment variable is not set!");
            System.exit(1);
        }

        try {
            logger.info("Starting AI Translation Agent connecting to {}", hubUrl);
            Agent agent = new Agent(hubUrl, displayName);

            // Register processor
            agent.registerTranslationProcessor(new AiTranslationProcessor(aiApiKey, aiBaseUrl, aiModelName));

            agent.start();
        } catch (Exception e) {
            logger.error("Failed to start agent", e);
            System.exit(1);
        }
    }
}
