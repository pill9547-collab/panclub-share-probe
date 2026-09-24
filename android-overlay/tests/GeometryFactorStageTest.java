package com.panclub.shareprobe;

import java.math.BigDecimal;
import java.math.MathContext;
import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;

/** Real Pin replay; BF-U01 + BF-P04 + BF-F01 stop at geometry-only output. */
public final class GeometryFactorStageTest {
    private static void check(boolean yes, String message) { if (!yes) throw new AssertionError(message); }

    private static JSONObject prepared(String rawInput) throws Exception {
        JSONObject out = TargetPanContract.attach(TargetPanContractTest.replaySourceFixture(), rawInput);
        return out.put("inputRevision", 1).put("workingRecipeRevision", 1);
    }

    public static void main(String[] args) throws Exception {
        JSONObject fixture = new JSONObject(Files.readString(
                Path.of("probe/target_pan_ko_round_15cm_2_geometry_fixture.json")));
        JSONObject intent = fixture.getJSONObject("geometryIntent");
        JSONObject input = prepared(fixture.getString("rawInput"));
        String unchangedInput = input.toString();
        JSONObject workingBefore = new JSONObject(input.getJSONObject("workingRecipe").toString());
        JSONObject geometry = GeometryFactorStage.evaluate(input, intent);
        check("GEOMETRY_PASS".equals(geometry.getString("decision")), "narrow geometry eligible");
        check("15.24 cm".equals(geometry.getString("sourceDiameterNormalized")) &&
                "15 cm".equals(geometry.getString("targetDiameterNormalized")), "exact BF-U01 length normalization");
        check(geometry.getInt("sourceCount") == 2 && geometry.getInt("targetCount") == 2 &&
                "PASS".equals(geometry.getString("measurementCompatibility")), "BF-P04 and same count");
        JSONArray chain = geometry.getJSONArray("ruleChain");
        check(chain.toString().equals(new JSONArray().put("BF-P04").put("BF-U01").put("BF-F01").toString()),
                "only canonical rule chain");
        JSONObject derived = geometry.getJSONObject("derivation");
        JSONObject source = derived.getJSONObject("sourceDiameter");
        JSONObject target = derived.getJSONObject("targetDiameter");
        check("6".equals(source.getString("sourceRawValue")) && "inch".equals(source.getString("sourceRawUnit")) &&
                "15.24".equals(source.getString("normalizedValue")) && "cm".equals(source.getString("normalizedUnit")) &&
                "BF-U01".equals(source.getString("ruleId")) &&
                "6 inch".equals(source.getJSONObject("sourceProvenance").getString("sourceSpan")),
                "unit derivation retains exact source and rule");
        check("NOMINAL_DIAMETER".equals(derived.getJSONObject("sourceMeasurementClass").getString("measurementClass")) &&
                "NOMINAL_DIAMETER".equals(derived.getJSONObject("targetMeasurementClass").getString("measurementClass")) &&
                "15 cm".equals(target.getJSONObject("provenance").getString("sourceSpan")) &&
                TargetPanContract.ORIGIN.equals(target.getJSONObject("provenance").getString("origin")),
                "both nominal classes and distinct provenances retained");
        check(derived.getJSONObject("sourceCount").getJSONObject("provenance").getString("sourceSpan").equals("two") &&
                derived.getJSONObject("targetCount").getJSONObject("provenance").getString("sourceSpan").equals("2"),
                "count facts source anchored");
        BigDecimal expected = new BigDecimal("15").divide(
                new BigDecimal("6").multiply(new BigDecimal("2.54")), MathContext.DECIMAL128);
        expected = expected.multiply(expected, MathContext.DECIMAL128);
        check(expected.compareTo(new BigDecimal(geometry.getString("geometryFactor"))) == 0,
                "factor obtained from inputs and exact normalization");
        check(geometry.getJSONArray("numericConversionsApplied").length() == 1 &&
                "BF-U01".equals(geometry.getJSONArray("numericConversionsApplied")
                        .getJSONObject(0).getString("ruleId")), "only length conversion recorded");
        check(!geometry.getBoolean("fitApplied") && !geometry.getBoolean("ingredientScalingApplied") &&
                geometry.getInt("ingredientScalingCount") == 0 &&
                geometry.getJSONArray("orphanNumbers").length() == 0 &&
                !geometry.has("workingRecipe") && !geometry.has("kitchenSheet"), "geometry only, no orphan or user surface");
        check(unchangedInput.equals(input.toString()) &&
                workingBefore.similar(input.getJSONObject("workingRecipe")) &&
                input.getJSONObject("workingRecipe").getJSONArray("numericConversionsApplied").length() == 0,
                "recipe, ingredients, bake time, yield and reader provenance not mutated");

        JSONObject original = prepared("원형 15 cm 팬 1개");
        JSONObject blocked = GeometryFactorStage.evaluate(original, intent);
        check("BLOCK".equals(FitEligibilityGate.evaluate(original).getString("decision")) &&
                "BLOCK".equals(blocked.getString("decision")) &&
                "PAN_COUNT_MERGE_SPLIT_REQUIRED".equals(blocked.getString("blocker")) &&
                !blocked.has("geometryFactor") && blocked.getJSONArray("numericConversionsApplied").length() == 0,
                "2-to-1 blocks before any unit normalization or geometry");

        JSONObject changedInput = prepared("원형 14 cm 팬 2개");
        JSONObject changed = GeometryFactorStage.evaluate(changedInput, intent);
        check("GEOMETRY_PASS".equals(changed.getString("decision")) &&
                new BigDecimal(changed.getString("geometryFactor"))
                        .compareTo(new BigDecimal(geometry.getString("geometryFactor"))) != 0,
                "target changes factor: no fixture constant");

        JSONObject orphan = new JSONObject(input.toString());
        orphan.getJSONObject("workingRecipe").getJSONArray("orphanNumbers").put(42);
        check(!GeometryFactorStage.evaluate(orphan, intent).has("geometryFactor"), "orphan blocks");
        JSONObject stale = new JSONObject(input.toString()).put("workingRecipeRevision", 0);
        check(!GeometryFactorStage.evaluate(stale, intent).has("geometryFactor"), "stale revision blocks");
        JSONObject unprovenClass = new JSONObject(input.toString());
        unprovenClass.getJSONObject("workingRecipe").getJSONObject("sourcePan")
                .getJSONObject("diameterEvidence").put("rawInstruction", "6 inch outer cake pans");
        unprovenClass.getJSONObject("normalizedInput").getJSONObject("sourcePan")
                .getJSONObject("diameterEvidence").put("rawInstruction", "6 inch outer cake pans");
        check(!GeometryFactorStage.evaluate(unprovenClass, intent).has("geometryFactor"),
                "nominal-to-outer comparison blocks without class promotion");
        JSONObject missingIntent = new JSONObject(intent.toString()).put("processPreserved", false);
        check(!GeometryFactorStage.evaluate(input, missingIntent).has("geometryFactor"),
                "unproven process preservation blocks");
        System.out.println("PASS canonical geometry only, BF-P04/BF-U01/BF-F01, 2-to-1 BLOCK and Pin 559 E2E replay; factor="
                + geometry.getString("geometryFactor"));
    }
}
