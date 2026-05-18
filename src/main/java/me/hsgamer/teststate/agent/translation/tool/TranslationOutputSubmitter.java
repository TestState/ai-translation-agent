package me.hsgamer.teststate.agent.translation.tool;

import com.google.gson.Gson;
import com.google.gson.GsonBuilder;
import dev.langchain4j.agent.tool.Tool;
import me.hsgamer.teststate.agent.translation.AbstractAiTranslationProcessor;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import java.lang.reflect.Method;

public class TranslationOutputSubmitter<R> {
    private static final Logger logger = LoggerFactory.getLogger(TranslationOutputSubmitter.class);
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private final Class<R> outputClass;
    private R submittedOutput;

    public TranslationOutputSubmitter(Class<R> outputClass) {
        this.outputClass = outputClass;
    }

    @Tool("Parse, validate, and submit the final translated JSON. Calling this tool with the complete and valid JSON string is the MANDATORY final step to successfully complete the translation task. If validation fails, correct the JSON and call this tool again.")
    public String submitTranslation(String json) {
        if (json == null || json.trim().isEmpty()) {
            return "JSON Validation Error: The provided JSON string is empty.";
        }

        try {
            String cleaned = AbstractAiTranslationProcessor.cleanJsonString(json);
            if (cleaned == null || cleaned.trim().isEmpty()) {
                return "JSON Validation Error: Could not extract balanced JSON boundaries from input.";
            }

            R parsed = GSON.fromJson(cleaned, outputClass);
            if (parsed == null) {
                return "JSON Validation Error: Parsed output is null.";
            }

            // Enforce custom validate() method if present on the parsed object
            try {
                Method validateMethod = outputClass.getMethod("validate");
                validateMethod.invoke(parsed);
            } catch (NoSuchMethodException e) {
                // No validation method, skip
                logger.debug("No validate() method found on class {}", outputClass.getName());
            } catch (Exception e) {
                // Validation failed (wrapped in InvocationTargetException)
                Throwable cause = e.getCause() != null ? e.getCause() : e;
                return "JSON Validation Error: " + cause.getMessage() + ". Please correct the JSON schema/values and try again.";
            }

            this.submittedOutput = parsed;
            logger.info("Successfully validated and submitted translation output of type {}", outputClass.getSimpleName());
            return "Success: Translation submitted and validated successfully. You can now conclude your execution and finish.";
        } catch (Exception e) {
            logger.error("JSON parsing/validation error in submitTranslation tool", e);
            return "JSON Validation Error: " + e.getMessage() + ". Please fix the JSON and submit again.";
        }
    }

    public R getSubmittedOutput() {
        return submittedOutput;
    }
}
