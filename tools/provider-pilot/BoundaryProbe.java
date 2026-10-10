package com.vision.app;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Paths;
/** Uses the production validator. This program proposes, never executes actions. */
public final class BoundaryProbe {
    public static void main(String[] args) throws Exception {
        String raw = new String(Files.readAllBytes(Paths.get(args[0])), StandardCharsets.UTF_8);
        ReasoningProposalValidator.ValidationResult result =
                ReasoningProposalValidator.validate(StrictJson.parseObject(raw));
        System.out.println(result.action == null ? "REJECTED:" + result.reason :
                result.action.type.name() + ":" + result.action.target);
    }
}
