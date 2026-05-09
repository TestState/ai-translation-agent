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
        return service.translate(script);
    }

    @Override
    protected TranslationResult createTranslationResult(RecorderUserFlow userFlow) {
        Map<String, Object> finalResult = new LinkedHashMap<>();
        finalResult.put("title", userFlow.title());
        finalResult.put("steps", userFlow.steps());

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
            5. **SELECTOR HIERARCHY**: ARIA (Role/Name) -> ID -> Name -> CSS -> XPath. Recorder format uses a nested array for selectors. **TIP**: You are ENCOURAGED to provide multiple selector variants (e.g. [ARIA, ID, CSS]) in tool calls to improve script reliability.
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
               - **FINAL ASSEMBLY**: Map the JSON objects from the log DIRECTLY into the final response format. If a step is not in the log, it does not exist.
            
            # MANUAL SCRIPT
            {{script}}
            
            # RESPONSE SPECIFICATION
            Return ONLY the JSON object. No markdown blocks.
            {
              "title": "string",
              "steps": [
                { "type": "navigate", "url": "string" },
                { "type": "click", "selectors": [["string"]], "target": "main", "offsetX": 1, "offsetY": 1 },
                { "type": "change", "selectors": [["string"]], "value": "string", "target": "main" }
              ]
            }
            """)
        @dev.langchain4j.service.UserMessage("Start translation")
        RecorderUserFlow translate(@dev.langchain4j.service.V("script") String script);
    }

    public record RecorderUserFlow(
        @JsonProperty(required = true) String title,
        @JsonProperty(required = true) List<Map<String, Object>> steps
    ) {
    }
}
