package me.hsgamer.teststate.agent.translation;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import com.microsoft.playwright.BrowserType;
import com.microsoft.playwright.Playwright;
import dev.langchain4j.memory.chat.MessageWindowChatMemory;
import dev.langchain4j.model.chat.listener.ChatModelErrorContext;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.listener.ChatModelResponseContext;
import dev.langchain4j.model.openai.OpenAiChatModel;
import dev.langchain4j.service.AiServices;
import me.hsgamer.teststate.agent.translation.tool.BrowserExecutionPlan;
import me.hsgamer.teststate.agent.translation.tool.BrowserInteractionLog;
import me.hsgamer.teststate.agent.translation.tool.CommonBrowserTools;
import me.hsgamer.teststate.client.context.TranslationSessionContext;
import me.hsgamer.teststate.client.processor.TranslationSessionProcessor;
import me.hsgamer.teststate.uap.v1.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.*;
import java.util.stream.Stream;

public abstract class AbstractAiTranslationProcessor<T, R> implements TranslationSessionProcessor {
    protected final Logger logger = LoggerFactory.getLogger(getClass());
    protected static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    public static String cleanJsonString(String input) {
        if (input == null) {
            return null;
        }
        String trimmed = input.trim();

        // If it's wrapped in a markdown code block, extract the JSON block
        if (trimmed.startsWith("```")) {
            int start = trimmed.indexOf('{');
            if (start == -1) start = trimmed.indexOf('[');
            int end = trimmed.lastIndexOf('}');
            if (end == -1) end = trimmed.lastIndexOf(']');
            if (start != -1 && end != -1 && end > start) {
                trimmed = trimmed.substring(start, end + 1).trim();
            }
        }

        // If it's wrapped in quotes, it might be a JSON string literal
        if (trimmed.startsWith("\"") && trimmed.endsWith("\"")) {
            try {
                // Parse it as a JSON string primitive to automatically handle all escaping rules
                trimmed = GSON.fromJson(trimmed, String.class).trim();
            } catch (Exception e) {
                // Fallback to manual stripping if parsing fails
                trimmed = trimmed.substring(1, trimmed.length() - 1).trim();
            }
        }

        // In case there is still any leading/trailing garbage, find the first brace
        int start = trimmed.indexOf('{');
        if (start == -1) start = trimmed.indexOf('[');
        int end = trimmed.lastIndexOf('}');
        if (end == -1) end = trimmed.lastIndexOf(']');
        if (start != -1 && end != -1 && end > start) {
            trimmed = trimmed.substring(start, end + 1).trim();
        }

        return trimmed;
    }

    private final String apiKey;
    private final String baseUrl;
    private final String modelName;

    protected AbstractAiTranslationProcessor(String apiKey, String baseUrl, String modelName) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.modelName = modelName;
    }

    @Override
    public void process(String sessionId, TranslationSessionContext context) {
        String script = getManualScript(context.getInit()).orElseThrow(() -> new RuntimeException("No manual script found"));

        context.sendStatus(TranslationStatus.newBuilder()
            .setState(TranslationState.TRANSLATION_STATE_ACKNOWLEDGED)
            .setMessage("Initializing AI Translator...")
            .build());

        Path userDataDir = null;
        try {
            userDataDir = Files.createTempDirectory("playwright-profile-");
            Path defaultDir = userDataDir.resolve("Default");
            Files.createDirectories(defaultDir);

            // Configure preferences to disable password manager and autofill
            Map<String, Object> prefs = new HashMap<>();
            Map<String, Object> profile = new HashMap<>();
            profile.put("password_manager_leak_detection", false);
            profile.put("password_manager_enabled", false);
            profile.put("password_manager_leak_detection_enabled", false);
            profile.put("autofill.profile_enabled", false);
            profile.put("autofill.address_enabled", false);
            profile.put("autofill.credit_card_enabled", false);
            prefs.put("profile", profile);
            prefs.put("credentials_enable_service", false);

            Files.writeString(defaultDir.resolve("Preferences"), GSON.toJson(prefs));

            try (Playwright playwright = Playwright.create()) {
                com.microsoft.playwright.BrowserContext browserContext = playwright.chromium().launchPersistentContext(userDataDir, new BrowserType.LaunchPersistentContextOptions()
                    .setHeadless(false)
                    .setArgs(Arrays.asList(
                        "--disable-save-password-bubble",
                        "--disable-notifications",
                        "--disable-infobars",
                        "--no-sandbox",
                        "--disable-dev-shm-usage",
                        "--disable-features=PasswordGeneration,PasswordManager",
                        "--password-store=basic"
                    ))
                );

                com.microsoft.playwright.Page page = browserContext.pages().get(0);

                logger.info("Building OpenAI-compatible model...");
                OpenAiChatModel.OpenAiChatModelBuilder modelBuilder = OpenAiChatModel.builder()
                    .apiKey(apiKey)
                    .modelName(modelName)
                    .strictJsonSchema(true)
                    .returnThinking(true)
                    .sendThinking(true)
                    .logRequests(true)
                    .logResponses(true)
                    .listeners(Collections.singletonList(new ChatModelListener() {
                        @Override
                        public void onRequest(ChatModelRequestContext requestContext) {
                            logger.info("AI Request sent");
                        }

                        @Override
                        public void onResponse(ChatModelResponseContext responseContext) {
                            String text = responseContext.chatResponse().aiMessage().text();
                            if (text != null && !text.isEmpty()) {
                                context.sendTelemetry("[AI] " + text, Severity.SEVERITY_INFO);
                            }

                            String thinking = responseContext.chatResponse().aiMessage().thinking();
                            if (thinking != null && !thinking.isEmpty()) {
                                context.sendTelemetry("[Reasoning] " + thinking, Severity.SEVERITY_INFO);
                            }

                            responseContext.chatResponse().aiMessage().toolExecutionRequests().forEach(tool -> {
                                context.sendTelemetry("[Tool] Calling " + tool.name() + " with " + tool.arguments(), Severity.SEVERITY_INFO);
                            });
                        }

                        @Override
                        public void onError(ChatModelErrorContext errorContext) {
                            context.sendTelemetry("[Error] " + errorContext.error().getMessage(), Severity.SEVERITY_ERROR);
                        }
                    }));

                if (baseUrl != null && !baseUrl.isEmpty()) {
                    modelBuilder.baseUrl(baseUrl);
                }

                OpenAiChatModel model = modelBuilder.build();

                logger.info("Setting up AiServices and BrowserTools...");
                BrowserInteractionLog log = new BrowserInteractionLog();
                BrowserExecutionPlan plan = new BrowserExecutionPlan();
                CommonBrowserTools commonTools = new CommonBrowserTools(page, log);
                
                List<Object> tools = new ArrayList<>();
                tools.add(log);
                tools.add(plan);
                tools.add(commonTools);
                tools.addAll(getInteractionTools(page, log));

                AiServices<T> aiServicesBuilder = AiServices.builder(getServiceClass())
                    .chatModel(model)
                    .tools(tools)
                    .chatMemory(MessageWindowChatMemory.withMaxMessages(100));

                T translator = aiServicesBuilder.build();

                context.sendStatus(TranslationStatus.newBuilder()
                    .setState(TranslationState.TRANSLATION_STATE_PROCESSING)
                    .setMessage("AI is analyzing the script and application...")
                    .build());

                logger.info("Starting AI translation...");
                R result = translate(translator, script);
                logger.info("AI translation finished successfully.");

                context.sendResult(createTranslationResult(result));
            }
        } catch (Exception e) {
            logger.error("Error during AI translation", e);
            context.sendStatus(TranslationStatus.newBuilder()
                .setState(TranslationState.TRANSLATION_STATE_FAILED)
                .setMessage("Translation failed: " + e.getMessage())
                .build());
        } finally {
            if (userDataDir != null) {
                try (Stream<Path> walk = Files.walk(userDataDir)) {
                    walk.sorted(Comparator.reverseOrder())
                        .forEach(path -> {
                            try {
                                Files.delete(path);
                            } catch (IOException e) {
                                logger.error("Failed to delete temp file: " + path, e);
                            }
                        });
                } catch (IOException e) {
                    logger.error("Failed to cleanup userDataDir", e);
                }
            }
        }
    }

    protected Optional<String> getManualScript(TranslationInit init) {
        return init.getPayloadsList().stream()
            .filter(p -> p.getType().equals("manual-script"))
            .map(p -> p.getAttachment().getData().toStringUtf8())
            .findFirst();
    }

    protected abstract Class<T> getServiceClass();

    protected abstract List<Object> getInteractionTools(com.microsoft.playwright.Page page, BrowserInteractionLog log);

    protected abstract R translate(T service, String script);

    protected abstract TranslationResult createTranslationResult(R result);
}
