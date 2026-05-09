package me.hsgamer.teststate.agent.translation;

import com.fasterxml.jackson.annotation.JsonProperty;
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

public class AiTranslationProcessor implements TranslationSessionProcessor {
    private static final Logger logger = LoggerFactory.getLogger(AiTranslationProcessor.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final String apiKey;
    private final String baseUrl;
    private final String modelName;

    public AiTranslationProcessor(String apiKey, String baseUrl, String modelName) {
        this.apiKey = apiKey;
        this.baseUrl = baseUrl;
        this.modelName = modelName;
    }

    @Override
    public TranslationCapability getTranslationCapability() {
        return TranslationCapability.newBuilder()
            .setType("manual-to-selenium-side")
            .addSourcePayloads(PayloadRequirement.newBuilder()
                .setType("manual-script")
                .addAcceptedMimeTypes("text/plain")
                .setIsRequired(true)
                .build())
            .addTargetPayloads(PayloadRequirement.newBuilder()
                .setType("selenium-side")
                .addAcceptedMimeTypes("application/json")
                .setIsRequired(true)
                .build())
            .build();
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
                TranslatorService translator = AiServices.builder(TranslatorService.class)
                    .chatModel(model)
                    .chatMemory(MessageWindowChatMemory.withMaxMessages(100))
                    .tools(new BrowserTools(page))
                    .build();

                context.sendStatus(TranslationStatus.newBuilder()
                    .setState(TranslationState.TRANSLATION_STATE_PROCESSING)
                    .setMessage("AI is analyzing the script and application...")
                    .build());

                logger.info("Starting AI translation...");
                SideTest resultTest = translator.translate(script);
                logger.info("AI translation finished successfully.");

                List<Map<String, Object>> finalCommands = new ArrayList<>();
                for (SideCommand cmd : resultTest.commands()) {
                    Map<String, Object> finalCmd = new LinkedHashMap<>();
                    finalCmd.put("id", UUID.randomUUID().toString());
                    finalCmd.put("comment", cmd.comment());
                    finalCmd.put("command", cmd.command());
                    finalCmd.put("target", cmd.target());
                    finalCmd.put("value", cmd.value());
                    finalCommands.add(finalCmd);
                }

                Map<String, Object> finalResult = new LinkedHashMap<>();
                finalResult.put("id", UUID.randomUUID().toString());
                finalResult.put("name", resultTest.testName());
                finalResult.put("description", resultTest.description());
                finalResult.put("commands", finalCommands);

                String jsonResult = GSON.toJson(finalResult);

                context.sendResult(TranslationResult.newBuilder()
                    .addPayloads(Payload.newBuilder()
                        .setType("selenium-side")
                        .setAttachment(Attachment.newBuilder()
                            .setName("test.json")
                            .setMimeType("application/json")
                            .setData(com.google.protobuf.ByteString.copyFromUtf8(jsonResult))
                            .build())
                        .build())
                    .setStatus(TranslationStatus.newBuilder()
                        .setState(TranslationState.TRANSLATION_STATE_COMPLETED)
                        .setMessage("Translation finished successfully.")
                        .build())
                    .build());
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

    private Optional<String> getManualScript(TranslationInit init) {
        return init.getPayloadsList().stream()
            .filter(p -> p.getType().equals("manual-script"))
            .map(p -> p.getAttachment().getData().toStringUtf8())
            .findFirst();
    }

    public interface TranslatorService {
        @dev.langchain4j.service.SystemMessage("""
            # MISSION
            You are a Senior Automation Architect. Your task is to translate a `MANUAL SCRIPT` into a precise, deterministic Selenium IDE (.side) JSON script by interacting with a live browser.
            
            # CORE COMMANDMENTS
            1. **PLAN FIRST**: No tool calls (except `updatePlan`) are permitted until a roadmap is established.
            2. **BATCH FOR SPEED**: You are ENCOURAGED to call multiple tools in a single turn. Combine setup (open+setWindowSize) or interaction sequences (type+type+click) to reduce latency.
            3. **LOG IS TRUTH**: Always call `getInteractionLog` before finishing. Use its output for your final JSON.
            4. **SYNC > PAUSE**: `waitForElementVisible` is the primary synchronization tool. `pause` is a last-resort.
            5. **VIEWPORT SAFETY**: Always `setWindowSize` (1280x1024) at start and `scrollToElement` before interaction.
            6. **SELECTOR HIERARCHY**: ARIA (Role/Name) -> ID -> Name -> CSS -> XPath.
            
            # EXECUTION PROTOCOL (Plan-and-Execute)
            1. **Initialize**: 
               - [Reasoning]: Analyze script for entry URL and milestones.
               - [Action]: `updatePlan` (Step-by-step breakdown).
            2. **Navigate & Setup (REQUIRED BATCH)**: 
               - **EFFICIENCY**: You should call `open(url)` and `setWindowSize(1280, 1024)` in the SAME TURN.
            3. **Cycle (Interactions)**:
               - [Reasoning]: Inspect state. **TIP**: You may batch `getAriaSnapshot` with `getAriaLocatorVariants` if you already suspect an element's role/name.
               - **VISIBILITY CHECK**: Call `scrollToElement` before interactions if needed.
               - [Action]: Interact. **BATCH CONFIDENTLY**: Fill forms with multiple `type` calls in one turn.
               - [Maintenance]: Update plan every 3 turns. 
            4. **MANDATORY VERIFICATION (Extract)**: 
               - **STRICT RULE**: You are FORBIDDEN from returning final JSON until you have called `getInteractionLog`.
            
            # REFERENCE EXAMPLE
            **User Script**: "1. Go to site.com, 2. Login as 'admin', 3. Click 'Dashboard'."
            **Thinking Process**:
            1. Turn 1 [Action]: `updatePlan("...")` + `open("https://site.com")` + `setWindowSize(1280, 1024)`
            2. Turn 2 [Action]: `getAriaSnapshot()` + `getAriaLocatorVariants("textbox", "Username")`
            3. Turn 3 [Action]: `type("id=u", "admin")` + `type("id=p", "secret")` + `click("id=login")`
            4. Turn 4 [Action]: `scrollToElement("id=dash")` + `click("id=dash")` + `getInteractionLog()`
            5. Turn 5: Return Final JSON based on Log.
            
            # MANUAL SCRIPT
            {{script}}
            
            # RESPONSE SPECIFICATION
            Return ONLY the JSON object. No markdown blocks.
            {
              "testName": "string",
              "description": "string",
              "commands": [
                { "command": "string", "target": "string", "value": "string", "comment": "string" }
              ]
            }
            """)
        @dev.langchain4j.service.UserMessage("Start translation")
        SideTest translate(@dev.langchain4j.service.V("script") String script);
    }

    public record SideTest(
        @JsonProperty(required = true) String testName,
        @JsonProperty(required = true) String description,
        @JsonProperty(required = true) List<SideCommand> commands
    ) {
    }

    public record SideCommand(
        @JsonProperty(required = true) String comment,
        @JsonProperty(required = true) String command,
        @JsonProperty(required = true) String target,
        @JsonProperty(required = true) String value
    ) {
    }

}
