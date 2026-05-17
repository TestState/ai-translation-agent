package me.hsgamer.teststate.agent.translation;

import com.fasterxml.jackson.annotation.JsonProperty;
import me.hsgamer.teststate.agent.translation.tool.BrowserInteractionLog;
import me.hsgamer.teststate.agent.translation.tool.PuppeteerBrowserTools;
import me.hsgamer.teststate.uap.v1.*;

import java.util.*;

public class AiPuppeteerTranslationProcessor extends AbstractAiTranslationProcessor<AiPuppeteerTranslationProcessor.TranslatorService, AiPuppeteerTranslationProcessor.RecorderUserFlow> {
    public AiPuppeteerTranslationProcessor(String apiKey, String baseUrl, String modelName) {
        super(apiKey, baseUrl, modelName);
    }

    @Override
    public TranslationCapability getTranslationCapability() {
        return TranslationCapability.newBuilder()
            .setType("manual-to-chrome-devtools-recorder")
            .addSourcePayloads(PayloadRequirement.newBuilder()
                .setType("manual-script")
                .addAcceptedMimeTypes("text/plain")
                .setIsRequired(true)
                .build())
            .addTargetPayloads(PayloadRequirement.newBuilder()
                .setType("chrome-devtools-recorder")
                .addAcceptedMimeTypes("application/json")
                .setIsRequired(true)
                .build())
            .build();
    }

    @Override
    protected Class<TranslatorService> getServiceClass() {
        return TranslatorService.class;
    }

    @Override
    protected List<Object> getInteractionTools(com.microsoft.playwright.Page page, BrowserInteractionLog log) {
        return Collections.singletonList(new PuppeteerBrowserTools(page, log));
    }

    @Override
    protected RecorderUserFlow translate(TranslatorService service, String script) {
        String rawJson = service.translate(script);
        String cleanedJson = cleanJsonString(rawJson);
        logger.info("Cleaned JSON from AI: {}", cleanedJson);
        return GSON.fromJson(cleanedJson, RecorderUserFlow.class);
    }

    @Override
    protected TranslationResult createTranslationResult(RecorderUserFlow userFlow) {
        List<Map<String, Object>> finalSteps = new ArrayList<>();
        for (Map<String, Object> step : userFlow.steps()) {
            Map<String, Object> finalStep = new LinkedHashMap<>(step);
            String type = (String) finalStep.get("type");

            // Add back target: "main" to all steps that don't have a target
            if (!finalStep.containsKey("target")) {
                finalStep.put("target", "main");
            }

            // Special handling for click
            if ("click".equals(type)) {
                finalStep.putIfAbsent("offsetX", 1);
                finalStep.putIfAbsent("offsetY", 1);
            }

            // Special handling for setViewport
            if ("setViewport".equals(type)) {
                finalStep.putIfAbsent("deviceScaleFactor", 1);
                finalStep.putIfAbsent("isMobile", false);
                finalStep.putIfAbsent("hasTouch", false);
                finalStep.putIfAbsent("isLandscape", false);
            }

            finalSteps.add(finalStep);
        }

        Map<String, Object> finalResult = new LinkedHashMap<>();
        finalResult.put("title", userFlow.title());
        finalResult.put("steps", finalSteps);

        String jsonResult = GSON.toJson(finalResult);

        return TranslationResult.newBuilder()
            .addPayloads(Payload.newBuilder()
                .setType("chrome-devtools-recorder")
                .setAttachment(Attachment.newBuilder()
                    .setName("recording.json")
                    .setMimeType("application/json")
                    .setData(com.google.protobuf.ByteString.copyFromUtf8(jsonResult))
                    .build())
                .build())
            .setStatus(TranslationStatus.newBuilder()
                .setState(TranslationState.TRANSLATION_STATE_COMPLETED)
                .setMessage("Translation finished successfully.")
                .build())
            .build();
    }

    public interface TranslatorService {
        @dev.langchain4j.service.SystemMessage("""
            # MISSION
            You are a Senior Automation Architect. Your task is to translate a `MANUAL SCRIPT` into a precise, deterministic Chrome DevTools Recorder JSON user flow by interacting with a live browser.
            
            # CORE COMMANDMENTS
            1. **PLAN FIRST**: No tool calls (except `updatePlan`) are permitted until a roadmap is established.
            2. **LOG IS THE ONLY TRUTH**: Your final JSON output MUST be a direct reflection of the `getInteractionLog` results. You are FORBIDDEN from adding, modifying, or hallucinating any command that was not explicitly recorded in the log via a tool call. **NOTE**: The JSON objects returned by each tool are the EXACT objects that must appear in your final `steps` array. Do NOT change them.
            3. **BATCH FOR SPEED**: You are ENCOURAGED to call multiple tools in a single turn.
            4. **SYNC > PAUSE**: `waitForElementVisible` is the primary synchronization tool. It AUTOMATICALLY handles page transition delays by recording a short pause before the wait. Fixed manual `pause` is a LAST-RESORT.
            5. **SELECTOR HIERARCHY**: ARIA (Role/Name) -> ID -> Name -> CSS -> XPath. Recorder format uses a nested array for selectors. **TIP**: You are ENCOURAGED to provide multiple selector variants (e.g. [ARIA, ID, CSS]) in tool calls. Use prefixes like `id=`, `name=`, `xpath=`, or `css=` which will be automatically converted to Recorder syntax (e.g. `#id`, `[name="name"]`, `xpath/path`). For ARIA, use the `aria/Name` format.
            6. **VIEWPORT SAFETY**: Always `setWindowSize` (1280x1024) at start and `scrollToElement` before interaction.
            
            # EXECUTION PROTOCOL (Plan-and-Execute)
            1. **Initialize**: 
               - Analyze script and `updatePlan`.
            2. **Navigate & Setup (REQUIRED BATCH)**: 
               - Call `open(url)` and `setWindowSize(1280, 1024)` in the SAME TURN.
            3. **Cycle (Interactions)**:
               - Inspect, Interact, and Batch.
               - **PLAN MAINTENANCE**: You MUST call `updatePlan` at least once every 3 interaction turns to document progress and adjust for any dynamic changes in the application.
            4. **MANDATORY VERIFICATION (Extract)**: 
               - **STRICT RULE**: You MUST call `getInteractionLog` at the very end.
               - **FINAL ASSEMBLY**: Map the JSON objects from the log into the final response format. If a step is not in the log, it does not exist. **NOTE**: You ONLY need to provide the essential fields (e.g. `type`, `selectors`, `url`, `value`). Technical details like `target: "main"`, `offsetX`, `offsetY`, and viewport flags are handled automatically by the system.
            
            # MANUAL SCRIPT
            {{script}}
            
            # RESPONSE SPECIFICATION
            Return ONLY the raw JSON object. Do not wrap the JSON in markdown code blocks or quotes.
            {
              "title": "string",
              "steps": [
                { "type": "navigate", "url": "string" },
                { "type": "click", "selectors": [["string"]] },
                { "type": "change", "selectors": [["string"]], "value": "string" }
              ]
            }
            """)
        @dev.langchain4j.service.UserMessage("Start translation")
        String translate(@dev.langchain4j.service.V("script") String script);
    }

    public record RecorderUserFlow(
        @JsonProperty(required = true) String title,
        @JsonProperty(required = true) List<Map<String, Object>> steps
    ) {
    }
}
