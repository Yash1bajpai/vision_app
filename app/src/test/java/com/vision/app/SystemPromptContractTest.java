package com.vision.app;

import org.junit.Test;
import static org.junit.Assert.*;

/**
 * Contract for the production system prompt: grounded in the registry with
 * every type's name, meaning and ALL THREE field rules (including the empty
 * ones), the exact four-key shape, the separator legend, canonical JSON
 * examples for a real action and for UNKNOWN, and the
 * unsupported/ambiguous/multi-action refusal rule. Content presence only -
 * no model behavior is claimed from this test.
 */
public class SystemPromptContractTest {

    private static String rule(String rule) {
        return "empty".equals(rule) ? "-" : rule;
    }

    @Test
    public void everyRegistryLineIsPresentCompleteAndUnconditional() {
        String prompt = LocalModelProvider.buildSystemPrompt();
        for (VisionToolRegistry.Tool tool : VisionToolRegistry.all()) {
            String line = tool.type.name() + " ~ " + tool.description
                    + " ~ " + rule(tool.targetRule)
                    + " ~ " + rule(tool.textRule)
                    + " ~ " + rule(tool.channelRule);
            assertTrue("missing or incomplete registry line for " + tool.type.name()
                    + " (name, meaning and all three field rules are asserted, "
                    + "empty ones included)", prompt.contains(line));
        }
    }

    @Test
    public void promptStatesExactKeysSeparatorLegendAndEmptyMeaning() {
        String prompt = LocalModelProvider.buildSystemPrompt();
        assertTrue(prompt.contains("\"type\",\"target\",\"text\",\"channel\""));
        assertTrue("separator legend must be stated",
                prompt.contains("NAME ~ meaning ~ target rule ~ text rule ~ channel rule"));
        assertTrue("the empty-field marker must be defined",
                prompt.contains("A rule of - means the field must be \"\""));
    }

    @Test
    public void promptCarriesCanonicalJsonExamplesAndRefusalRule() {
        String prompt = LocalModelProvider.buildSystemPrompt();
        assertTrue("canonical torch JSON example required",
                prompt.contains("\"turn torch on\" -> {\"type\":\"TOGGLE_TORCH\",\"target\":\"on\",\"text\":\"\",\"channel\":\"\"}"));
        assertTrue("canonical UNKNOWN JSON example required",
                prompt.contains("{\"type\":\"UNKNOWN\",\"target\":\"\",\"text\":\"\",\"channel\":\"\"}"));
        assertTrue("refusal rule must cover unsupported, ambiguous and multi-action",
                prompt.contains("Unsupported, ambiguous, or more than one action"));
        assertFalse("UNSUPPORTED_MULTI_ACTION is parser-only; the model must never emit it",
                prompt.contains("UNSUPPORTED_MULTI_ACTION"));
    }
}
