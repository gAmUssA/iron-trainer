package io.gamov.irontrainer.nutrition;

import io.quarkus.test.InjectMock;
import io.quarkus.test.junit.QuarkusTest;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.equalTo;
import static org.hamcrest.Matchers.hasItem;

/** The key-wiring risk gate: the app MUST boot with NO ANTHROPIC_API_KEY (the
 * test profile forces it empty), and POST /race-day/regenerate must fall back to
 * the deterministic plan with the "LLM unavailable" note — never 500. Also
 * guards the GET /race-day refactor (now sharing assemble()) and the LLM merge
 * wiring via a mocked NutritionLlm. */
@QuarkusTest
class NutritionRegenerateTest {

    @InjectMock
    NutritionLlm llm;

    @Test
    void regenerateFallsBackWhenLlmUnavailable() {
        Mockito.when(llm.generate(Mockito.anyString(), Mockito.anyString(),
                        Mockito.anyString(), Mockito.anyString()))
                .thenThrow(new NutritionLlm.Unavailable("ANTHROPIC_API_KEY not set."));
        given().when().post("/api/v2/athletes").then().statusCode(200);
        given().when().post("/api/nutrition/race-day/regenerate").then()
                .statusCode(200)
                .body("llm_used", equalTo(false))
                .body("adjustments", hasItem("LLM unavailable — showing the deterministic plan."));
    }

    @Test
    void regenerateMergesLlmTimeline() {
        Map<String, Object> item = new LinkedHashMap<>();
        item.put("phase", "bike");
        item.put("offset_min", 40);
        item.put("label", "Custom gel plan");
        item.put("carbs_g", 90);
        item.put("fluid_ml", 700);
        item.put("sodium_mg", 400);
        item.put("notes", "eat every 20 min");
        Mockito.when(llm.generate(Mockito.anyString(), Mockito.anyString(),
                        Mockito.anyString(), Mockito.anyString()))
                .thenReturn(new NutritionLlm.Result("Custom race-day fueling.", List.of(item)));

        given().when().post("/api/v2/athletes").then().statusCode(200);
        given().when().post("/api/nutrition/race-day/regenerate").then()
                .statusCode(200)
                .body("llm_used", equalTo(true))
                .body("summary", equalTo("Custom race-day fueling."))
                .body("items[0].phase", equalTo("bike"))
                .body("items[0].label", equalTo("Custom gel plan"));
    }

    @Test
    void raceDayStillServesDeterministicPlan() {
        given().when().post("/api/v2/athletes").then().statusCode(200);
        given().when().get("/api/nutrition/race-day").then()
                .statusCode(200)
                .body("llm_used", equalTo(false));
    }
}
