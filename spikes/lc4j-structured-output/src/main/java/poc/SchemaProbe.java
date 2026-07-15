package poc;

import dev.langchain4j.model.anthropic.AnthropicChatModel;
import dev.langchain4j.model.chat.Capability;
import dev.langchain4j.model.chat.listener.ChatModelListener;
import dev.langchain4j.model.chat.listener.ChatModelRequestContext;
import dev.langchain4j.model.chat.request.ResponseFormat;
import dev.langchain4j.service.AiServices;

import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Probe: does LangChain4j's Anthropic module implement AI-Service structured
 * output via Anthropic's NATIVE schema enforcement (output_format / forced
 * tool with input_schema), or by pasting the schema into the prompt?
 *
 * Evidence channels:
 *  1. ChatModelListener — the LC4J-level ChatRequest.responseFormat.
 *  2. logRequests(true) — the RAW HTTP body sent to api.anthropic.com
 *     (slf4j-simple prints it; the runner greps for output_format / tools).
 */
public class SchemaProbe {

    // Mirrors a slice of the Iron Trainer season-plan schema.
    public record PlannedWeek(String weekStart, double targetHours, String phase,
                              int quality_sessions) {}

    interface Planner {
        PlannedWeek plan(String instruction);
    }

    public static void main(String[] args) throws Exception {
        String key = apiKeyFromEnvFile();

        ChatModelListener listener = new ChatModelListener() {
            @Override
            public void onRequest(ChatModelRequestContext ctx) {
                ResponseFormat rf = ctx.chatRequest().responseFormat();
                System.out.println("PROBE responseFormat.type = "
                        + (rf == null ? "null" : rf.type()));
                System.out.println("PROBE responseFormat.jsonSchema = "
                        + (rf == null ? "null" : String.valueOf(rf.jsonSchema())));
            }
        };

        AnthropicChatModel model = AnthropicChatModel.builder()
                .apiKey(key)
                .modelName("claude-haiku-4-5-20251001")
                .maxTokens(500)
                .logRequests(true)
                .logResponses(true)
                .supportedCapabilities(Capability.RESPONSE_FORMAT_JSON_SCHEMA)
                .listeners(java.util.List.of(listener))
                .build();

        Planner planner = AiServices.create(Planner.class, model);
        PlannedWeek week = planner.plan(
                "Plan one build-phase triathlon week starting 2026-07-20, about 8 hours, 2 quality sessions.");
        System.out.println("PROBE parsed POJO = " + week);
    }

    /** Read ANTHROPIC_API_KEY from a nearby .env (never exported to the shell). */
    private static String apiKeyFromEnvFile() throws Exception {
        String fromEnv = System.getenv("ANTHROPIC_API_KEY");
        if (fromEnv != null && !fromEnv.isBlank()) return fromEnv;
        Path[] candidates = {
            Path.of("../../backend/.env"), Path.of("../../.env"),
            Path.of(".env"), Path.of("../.env"),
        };
        for (Path p : candidates) {
            Path env = p.toAbsolutePath().normalize();
            if (!Files.exists(env)) continue;
            for (String line : Files.readAllLines(env)) {
                String t = line.strip();
                if (t.startsWith("ANTHROPIC_API_KEY=")) {
                    return t.substring("ANTHROPIC_API_KEY=".length()).strip()
                            .replaceAll("^\"|\"$", "");
                }
            }
        }
        throw new IllegalStateException("ANTHROPIC_API_KEY not found in .env candidates");
    }
}
