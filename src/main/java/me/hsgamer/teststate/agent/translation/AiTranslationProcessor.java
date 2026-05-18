package me.hsgamer.teststate.agent.translation;

import com.fasterxml.jackson.annotation.JsonProperty;
import me.hsgamer.teststate.agent.translation.tool.BrowserInteractionLog;
import me.hsgamer.teststate.agent.translation.tool.SeleniumBrowserTools;
import me.hsgamer.teststate.agent.translation.tool.TranslationOutputSubmitter;
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
    protected Class<SideTest> getResultClass() {
        return SideTest.class;
    }

    @Override
    protected SideTest translate(TranslatorService service, String script, TranslationOutputSubmitter<SideTest> submitter) {
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
        SideTest sideTest = GSON.fromJson(cleanedJson, SideTest.class);
        if (sideTest != null) {
            sideTest.validate();
        }
        return sideTest;
    }

    @Override
    protected TranslationResult createTranslationResult(SideTest resultTest) {
        List<Map<String, Object>> finalCommands = new ArrayList<>();
        for (SideCommand cmd : resultTest.commands()) {
            Map<String, Object> finalCmd = new LinkedHashMap<>();
            finalCmd.put("id", UUID.randomUUID().toString());
            finalCmd.put("comment", cmd.comment() != null ? cmd.comment() : "");
            finalCmd.put("command", cmd.command());
            finalCmd.put("target", cmd.target());
            finalCmd.put("targets", cmd.targets());
            finalCmd.put("value", cmd.value() != null ? cmd.value() : "");
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
                   "testName": "string",
                   "description": "string",
                   "commands": [
                     { "command": "string", "target": "string", "targets": [["string", "string"]], "value": "string", "comment": "string" }
                   ]
                 }
                - **MANDATORY FINAL STEP & SELF-CORRECTION LOOP**: Call the `submitTranslation` tool with this JSON string. The tool will parse, strictly type check, and semantically validate the JSON structure (e.g. validating that commands has elements, and each command has a target and a command type).
                - **IF SUBMISSION FAILS**: If the tool returns a validation error or parsing exception, you MUST analyze the exact error message, correct your JSON payload (fixing types, selectors, or missing fields), and call `submitTranslation` again. You MUST loop and resubmit until you receive a success message from the tool.
             
            # REFERENCE EXAMPLE
            **User Script**: "1. Go to site.com, 2. Login as 'admin', 3. Click 'Dashboard'."
            **Thinking Process**:
            1. Turn 1 [Action]: `updatePlan("...")` + `open("https://site.com")` + `setWindowSize(1280, 1024)`
            2. Turn 2 [Action]: `getAriaSnapshot()` + `getAriaLocatorVariants("textbox", "Username")`
            3. Turn 3 [Action]: `type("id=u", "admin")` + `type("id=p", "secret")` + `click("id=login")`
            4. Turn 4 [Action]: `scrollToElement("id=dash")` + `click("id=dash")` + `getInteractionLog()`
            5. Turn 5 [Action]: `submitTranslation("...")` (Mandatory final submission tool call)
            6. Turn 6: Receive success message from tool. Return the final raw JSON as response.
            
            # MANUAL SCRIPT
            {{script}}
            
            # RESPONSE SPECIFICATION
            Return ONLY the raw JSON object. Do not wrap the JSON in markdown code blocks or quotes.
            {
              "testName": "string",
              "description": "string",
              "commands": [
                { "command": "string", "target": "string", "targets": [["string", "string"]], "value": "string", "comment": "string" }
              ]
            }
            """)
        @dev.langchain4j.service.UserMessage("Start translation")
        String translate(@dev.langchain4j.service.V("script") String script);
    }

    public record SideTest(
        @JsonProperty(required = true) String testName,
        @JsonProperty(required = true) String description,
        @JsonProperty(required = true) List<SideCommand> commands
    ) {
        public void validate() {
            if (testName == null || testName.trim().isEmpty()) {
                throw new IllegalArgumentException("testName cannot be null or empty");
            }
            if (commands == null || commands.isEmpty()) {
                throw new IllegalArgumentException("commands list cannot be null or empty");
            }
            for (int i = 0; i < commands.size(); i++) {
                SideCommand cmd = commands.get(i);
                if (cmd == null) {
                    throw new IllegalArgumentException("Command " + (i + 1) + " cannot be null");
                }
                if (cmd.command() == null || cmd.command().trim().isEmpty()) {
                    throw new IllegalArgumentException("Command " + (i + 1) + ": command is required");
                }
                if (cmd.target() == null) {
                    throw new IllegalArgumentException("Command " + (i + 1) + ": target is required (can be empty string)");
                }
                
                // Verify the targets list format if it is provided
                if (cmd.targets() != null) {
                    for (int j = 0; j < cmd.targets().size(); j++) {
                        List<String> targetPair = cmd.targets().get(j);
                        if (targetPair == null || targetPair.isEmpty()) {
                            throw new IllegalArgumentException("Command " + (i + 1) + ": target pair at index " + j + " cannot be null or empty");
                        }
                        String selector = targetPair.get(0);
                        if (selector == null || selector.trim().isEmpty()) {
                            throw new IllegalArgumentException("Command " + (i + 1) + ": selector in target pair index " + j + " cannot be null or empty");
                        }
                        if (targetPair.size() >= 2) {
                            String strategy = targetPair.get(1);
                            if (strategy == null || strategy.trim().isEmpty()) {
                                throw new IllegalArgumentException("Command " + (i + 1) + ": locator strategy in target pair index " + j + " cannot be null or empty");
                            }
                        }
                    }
                }
            }
        }
    }

    public record SideCommand(
        @JsonProperty(required = true) String comment,
        @JsonProperty(required = true) String command,
        @JsonProperty(required = true) String target,
        @JsonProperty(required = true) List<List<String>> targets,
        @JsonProperty(required = true) String value
    ) {
    }
}
