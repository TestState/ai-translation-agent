package me.hsgamer.teststate.agent.translation;

import com.fasterxml.jackson.annotation.JsonProperty;
import me.hsgamer.teststate.agent.translation.tool.BrowserInteractionLog;
import me.hsgamer.teststate.agent.translation.tool.PuppeteerBrowserTools;
import me.hsgamer.teststate.agent.translation.tool.TranslationOutputSubmitter;
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
    protected Class<RecorderUserFlow> getResultClass() {
        return RecorderUserFlow.class;
    }

    @Override
    protected RecorderUserFlow translate(TranslatorService service, String script, TranslationOutputSubmitter<RecorderUserFlow> submitter) {
        String rawJson = service.translate(script);
        
        // 1. Primary path: Use the validated JSON submitted via the tool
        if (submitter.getSubmittedOutput() != null) {
            logger.info("Using validated translation output submitted via the submitTranslation tool.");
            return submitter.getSubmittedOutput();
        }
        
        // 2. Fallback path: Clean and parse the text returned by the chat completion
        logger.warn("AI did not call the submitTranslation tool. Falling back to parsing chat response text.");
        String cleanedJson = cleanJsonString(rawJson);
        logger.info("Cleaned fallback JSON from AI: {}", cleanedJson);
        RecorderUserFlow userFlow = GSON.fromJson(cleanedJson, RecorderUserFlow.class);
        if (userFlow != null) {
            userFlow.validate();
        }
        return userFlow;
    }

    @Override
    protected TranslationResult createTranslationResult(RecorderUserFlow userFlow) {
        List<Map<String, Object>> finalSteps = new ArrayList<>();
        for (RecorderStep step : userFlow.steps()) {
            Map<String, Object> finalStep = new LinkedHashMap<>();
            finalStep.put("type", step.type());
            if (step.url() != null) {
                finalStep.put("url", step.url());
            }
            if (step.selectors() != null) {
                finalStep.put("selectors", step.selectors());
            }
            if (step.value() != null) {
                finalStep.put("value", step.value());
            }
            if (step.width() != null) {
                finalStep.put("width", step.width());
            }
            if (step.height() != null) {
                finalStep.put("height", step.height());
            }

            String type = step.type();

            // Add back target: "main" to all steps
            finalStep.put("target", "main");

            // Special handling for click
            if ("click".equals(type)) {
                finalStep.put("offsetX", 1);
                finalStep.put("offsetY", 1);
            }

            // Special handling for setViewport
            if ("setViewport".equals(type)) {
                finalStep.put("deviceScaleFactor", 1);
                finalStep.put("isMobile", false);
                finalStep.put("hasTouch", false);
                finalStep.put("isLandscape", false);
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
            5. **NO SELENIUM SELECTORS**: You MUST NOT use Selenium-style selector strategy prefixes (like `id=`, `name=`, `css=`, `xpath=`, or `linkText=`) at all under any circumstances. Pure Chrome DevTools Recorder selector syntax is required. Standard CSS selectors must have NO strategy prefix (e.g. `#username`, `.btn`). Attribute selectors must use `[name="value"]`. XPath selectors must start with `xpath/`. ARIA selectors must use `aria/Name`. This rule applies to both tool calls and the final submitted JSON payload.
            6. **VIEWPORT SAFETY**: Always `setWindowSize` (1280x1024) at start and `scrollToElement` before interaction.
            7. **MANDATORY SUBMISSION TOOL**: You MUST call the `submitTranslation` tool as the very final action of your run to submit the assembled JSON object. It is STRICTLY FORBIDDEN to simply return the JSON text response without calling the `submitTranslation` tool.
            
            # EXECUTION PROTOCOL (Plan-and-Execute)
            1. **Initialize**: 
               - Analyze script and `updatePlan`.
            2. **Navigate & Setup (REQUIRED BATCH)**: 
               - Call `open(url)` and `setWindowSize(1280, 1024)` in the SAME TURN.
            3. **Cycle (Interactions)**:
               - Inspect, Interact, and Batch.
               - **PLAN MAINTENANCE**: You MUST call `updatePlan` at least once every 3 interaction turns to document progress and adjust for any dynamic changes in the application.
            4. **MANDATORY VERIFICATION & SUBMISSION**: 
               - Call `getInteractionLog` to fetch all actual steps.
               - Assemble the steps into the target JSON structure:
                 {
                   "title": "string",
                   "steps": [
                     { "type": "setViewport", "width": 1280, "height": 1024 },
                     { "type": "navigate", "url": "string" },
                     { "type": "click", "selectors": [["string"]] },
                     { "type": "change", "selectors": [["string"]], "value": "string" }
                   ]
                 }
                - **MANDATORY FINAL STEP & SELF-CORRECTION LOOP**: Call the `submitTranslation` tool with this JSON string. The tool will parse, strictly type check, and semantically validate the JSON structure (e.g. validating that navigate steps have a URL, and click/change steps have selectors).
                - **IF SUBMISSION FAILS**: If the tool returns a validation error or parsing exception, you MUST analyze the exact error message, correct your JSON payload (fixing types, selectors, or missing fields), and call `submitTranslation` again. You MUST loop and resubmit until you receive a success message from the tool.
             
            # REFERENCE EXAMPLE
            **User Script**: "1. Go to site.com, 2. Login as 'admin', 3. Click 'Dashboard'."
            **Thinking Process**:
            1. Turn 1 [Action]: `updatePlan("...")` + `open("https://site.com")` + `setWindowSize(1280, 1024)`
            2. Turn 2 [Action]: `getAriaSnapshot()` + `getAriaLocatorVariants("textbox", "Username")`
            3. Turn 3 [Action]: `type(["#u"], "admin")` + `type(["#p"], "secret")` + `click(["#login"])`
            4. Turn 4 [Action]: `scrollToElement(["#dash"])` + `click(["#dash"])` + `getInteractionLog()`
            5. Turn 5 [Action]: `submitTranslation("...")` (Mandatory final submission tool call)
            6. Turn 6: Receive success message from tool. Return the final raw JSON as response.
            
            # MANUAL SCRIPT
            {{script}}
            
            # RESPONSE SPECIFICATION
            Return ONLY the raw JSON object. Do not wrap the JSON in markdown code blocks or quotes.
            {
              "title": "string",
              "steps": [
                { "type": "setViewport", "width": 1280, "height": 1024 },
                { "type": "navigate", "url": "string" },
                { "type": "click", "selectors": [["string"]] },
                { "type": "change", "selectors": [["string"]], "value": "string" }
              ]
            }
            """)
        @dev.langchain4j.service.UserMessage("Start translation")
        String translate(@dev.langchain4j.service.V("script") String script);
    }

    public record RecorderStep(
        @JsonProperty(required = true) String type,
        String url,
        List<List<String>> selectors,
        String value,
        Integer width,
        Integer height
    ) {
    }

    public record RecorderUserFlow(
        @JsonProperty(required = true) String title,
        @JsonProperty(required = true) List<RecorderStep> steps
    ) {
        public void validate() {
            if (title == null || title.trim().isEmpty()) {
                throw new IllegalArgumentException("title cannot be null or empty");
            }
            if (steps == null || steps.isEmpty()) {
                throw new IllegalArgumentException("steps list cannot be null or empty");
            }
            for (int i = 0; i < steps.size(); i++) {
                RecorderStep step = steps.get(i);
                if (step == null) {
                    throw new IllegalArgumentException("Step " + (i + 1) + " cannot be null");
                }
                if (step.type() == null || step.type().trim().isEmpty()) {
                    throw new IllegalArgumentException("Step " + (i + 1) + ": type is required");
                }
                String type = step.type().trim();
                if ("navigate".equalsIgnoreCase(type)) {
                    if (step.url() == null || step.url().trim().isEmpty()) {
                        throw new IllegalArgumentException("Step " + (i + 1) + ": url is required for navigate step");
                    }
                    String url = step.url().trim();
                    if (!url.startsWith("http://") && !url.startsWith("https://")) {
                        throw new IllegalArgumentException("Step " + (i + 1) + ": url must start with http:// or https:// (got: " + url + ")");
                    }
                } else if ("click".equalsIgnoreCase(type) || "change".equalsIgnoreCase(type)) {
                    if (step.selectors() == null || step.selectors().isEmpty()) {
                        throw new IllegalArgumentException("Step " + (i + 1) + ": selectors are required for " + type + " step");
                    }
                    for (int j = 0; j < step.selectors().size(); j++) {
                        List<String> selectorGroup = step.selectors().get(j);
                        if (selectorGroup == null || selectorGroup.isEmpty()) {
                            throw new IllegalArgumentException("Step " + (i + 1) + ": selector group at index " + j + " cannot be null or empty");
                        }
                        for (int k = 0; k < selectorGroup.size(); k++) {
                            String sel = selectorGroup.get(k);
                            if (sel == null || sel.trim().isEmpty()) {
                                throw new IllegalArgumentException("Step " + (i + 1) + ": selector string at group " + j + " index " + k + " cannot be null or empty");
                            }
                            String trimmed = sel.trim();
                            if (trimmed.startsWith("id=") || trimmed.startsWith("name=") || trimmed.startsWith("css=") || trimmed.startsWith("xpath=") || trimmed.startsWith("linkText=")) {
                                throw new IllegalArgumentException("Step " + (i + 1) + ": selector \"" + sel + "\" in group " + j + " uses a Selenium-style prefix. " +
                                    "Chrome DevTools Recorder selectors must NOT use 'id=', 'name=', 'css=', 'xpath=', or 'linkText='. " +
                                    "Please convert it to standard Recorder format: e.g. '#value' instead of 'id=value', '[name=\"value\"]' instead of 'name=value', " +
                                    "or 'xpath/...' instead of 'xpath=...'.");
                            }
                        }
                    }
                }
            }
        }
    }
}
