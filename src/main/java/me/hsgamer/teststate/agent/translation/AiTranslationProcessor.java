package me.hsgamer.teststate.agent.translation;

import com.fasterxml.jackson.annotation.JsonProperty;
import me.hsgamer.teststate.agent.translation.tool.BrowserInteractionLog;
import me.hsgamer.teststate.agent.translation.tool.SeleniumBrowserTools;
import me.hsgamer.teststate.uap.v1.*;

import java.util.*;

public class AiTranslationProcessor extends AbstractAiTranslationProcessor<AiTranslationProcessor.TranslatorService, AiTranslationProcessor.SideTest> {
    public AiTranslationProcessor(String apiKey, String baseUrl, String modelName) {
        super(apiKey, baseUrl, modelName);
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
    protected Class<TranslatorService> getServiceClass() {
        return TranslatorService.class;
    }

    @Override
    protected List<Object> getInteractionTools(com.microsoft.playwright.Page page, BrowserInteractionLog log) {
        return Collections.singletonList(new SeleniumBrowserTools(page, log));
    }

    @Override
    protected SideTest translate(TranslatorService service, String script) {
        return service.translate(script);
    }

    @Override
    protected TranslationResult createTranslationResult(SideTest resultTest) {
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

        return TranslationResult.newBuilder()
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
            .build();
    }

    public interface TranslatorService {
        @dev.langchain4j.service.SystemMessage("""
            # MISSION
            You are a Senior Automation Architect. Your task is to translate a `MANUAL SCRIPT` into a precise, deterministic Selenium IDE (.side) JSON script by interacting with a live browser.
            
            # CORE COMMANDMENTS
            1. **PLAN FIRST**: No tool calls (except `updatePlan`) are permitted until a roadmap is established.
            2. **LOG IS THE ONLY TRUTH**: Your final JSON output MUST be a direct reflection of the `getInteractionLog` results. You are FORBIDDEN from adding, modifying, or hallucinating any command that was not explicitly recorded in the log via a tool call. **NOTE**: The JSON objects returned by each tool are the EXACT objects that must appear in your final `commands` array. Do NOT change them.
            3. **BATCH FOR SPEED**: You are ENCOURAGED to call multiple tools in a single turn.
            4. **SYNC > PAUSE**: `waitForElementVisible` is the primary synchronization tool. `pause` is a last-resort.
            5. **VIEWPORT SAFETY**: Always `setWindowSize` (1280x1024) at start and `scrollToElement` before interaction.
            6. **SELECTOR HIERARCHY**: ARIA (Role/Name) -> ID -> Name -> CSS -> XPath. **TIP**: You are ENCOURAGED to provide multiple selector variants (e.g. [ARIA, ID, CSS]) in tool calls to improve script reliability.
            
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
