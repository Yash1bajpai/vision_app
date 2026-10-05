package com.vision.app;

import java.util.HashSet;
import java.util.Set;
import org.junit.Test;
import static org.junit.Assert.*;

public class VisionHelpRenderTest {

    @Test public void helpTextHasHeaderAndOneEntryPerToolInRegistryOrder() {
        String help = VisionToolRegistry.helpText();
        assertTrue(help.startsWith("SUPPORTED COMMANDS\n"));
        assertTrue(help.contains("cancel plan"));
        assertTrue(help.contains("forget context"));
        int position = 0;
        for (VisionToolRegistry.Tool tool : VisionToolRegistry.all()) {
            int found = help.indexOf(tool.description + "\nTry: " + tool.example, position);
            assertTrue("Missing or out-of-order help entry for " + tool.type, found >= position);
            position = found;
        }
    }

    @Test public void confirmMarkerMatchesRiskPolicyExactly() {
        String help = VisionToolRegistry.helpText();
        Set<String> markedLines = new HashSet<>();
        for (String line : help.split("\n")) {
            if (line.startsWith("[Confirm] ") && !line.startsWith("[Confirm] means")) {
                markedLines.add(line);
            }
        }
        int expected = 0;
        for (VisionToolRegistry.Tool tool : VisionToolRegistry.all()) {
            if (tool.requiresConfirmation()) {
                expected++;
                assertTrue("Confirm marker missing for " + tool.type,
                        markedLines.contains("[Confirm] " + tool.description));
            } else {
                assertFalse("Unexpected confirm marker for " + tool.type,
                        markedLines.contains("[Confirm] " + tool.description));
            }
        }
        assertEquals(expected, markedLines.size());
    }

    @Test public void helpTextIsDeterministicAndCarriesRequirementLines() {
        assertEquals(VisionToolRegistry.helpText(), VisionToolRegistry.helpText());
        String help = VisionToolRegistry.helpText();
        for (VisionToolRegistry.Tool tool : VisionToolRegistry.all()) {
            assertTrue("Requirement line missing for " + tool.type, help.contains(tool.requirement));
        }
    }

    @Test public void jsonExportListsEveryToolWithStableContract() {
        String json = VisionToolRegistry.toJson();
        assertTrue(json.startsWith("{\"schemaVersion\":1,"));
        assertTrue(json.contains("\"proposalKeys\":[\"type\",\"target\",\"text\",\"channel\"]"));
        for (VisionToolRegistry.Tool tool : VisionToolRegistry.all()) {
            assertTrue("Tool missing from export: " + tool.type,
                    json.contains("\"type\":\"" + tool.type.name() + "\""));
        }
        assertFalse("UNKNOWN must never be advertised", json.contains("UNKNOWN"));
    }
}
