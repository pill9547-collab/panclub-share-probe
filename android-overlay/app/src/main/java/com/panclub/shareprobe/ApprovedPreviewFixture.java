package com.panclub.shareprobe;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Adapts the approved immutable BF-F02 evidence into the same READ shape used at runtime. */
public final class ApprovedPreviewFixture {
    public static final String SOURCE_SHA =
            "b6c50e9f5c3b82cbde644049b2967b4b06471d59090d1fb80b8bdaed861e39d7";
    private static final String TITLE = "Easy Vegan Vanilla Cake";
    private static final String PUBLISHER = "The Curious Chickpea";

    private ApprovedPreviewFixture() {}

    public static JSONObject readResult(JSONObject fixture) {
        try {
            verify(fixture);
            JSONArray sourceRows = fixture.getJSONArray("ingredientResults");
            JSONArray ingredients = new JSONArray();
            String url = sourceRows.getJSONObject(0).getJSONObject("sourceEvidence").getString("sourceUrl");
            String sha = fixture.getString("sourceBodySha256Expected");
            for (int i = 0; i < sourceRows.length(); i++) {
                JSONObject source = sourceRows.getJSONObject(i);
                ingredients.put(new JSONObject()
                        .put("rawValue", source.getJSONObject("sourceEvidence").getString("rawSourceRow"))
                        .put("amountRaw", source.getString("sourceGrams"))
                        .put("unitRaw", "g")
                        .put("nameRaw", source.getString("ingredientIdentity"))
                        .put("component", "BATTER")
                        .put("optional", false)
                        .put("quantitySemanticState", "STRUCTURED_SINGLE_CARD_AMOUNT")
                        .put("numericConversion", "NONE_SOURCE_PRESERVED")
                        .put("sourceUrl", url)
                        .put("sourceBodySha256", sha)
                        .put("sourceEvidence", new JSONObject(source.getJSONObject("sourceEvidence").toString())));
            }
            JSONArray groups = new JSONArray().put(new JSONObject()
                    .put("sourceHeading", "Cake Layers")
                    .put("component", "BATTER")
                    .put("optional", false)
                    .put("ingredients", ingredients)
                    .put("sourceUrl", url)
                    .put("sourceBodySha256", sha));

            String instruction = "Divide the batter between two 6 inch cake pans.";
            JSONObject sourcePan = sourcePan(instruction, url, sha);
            JSONArray instructions = new JSONArray().put(new JSONObject()
                    .put("sourceHeading", "Cake Layers")
                    .put("steps", new JSONArray()
                            .put(step(instruction, url, sha, "fixture.instructions[0]"))
                            .put(step("Assemble the two cooled cake layers.", url, sha,
                                    "fixture.instructions[1]"))));
            JSONObject policy = new JSONObject()
                    .put("scope", "INGREDIENT_MASS_PREVIEW_ONLY")
                    .put("processPreserved", true)
                    .put("frostingExcludedFromPreview", true)
                    .put("frostingScalingRequired", false)
                    .put("bakeTimeScalingRequired", false)
                    .put("targetBakeTime", JSONObject.NULL)
                    .put("unsupportedIngredientTransformations", new JSONArray())
                    .put("sourceBakeGuidance", new JSONArray()
                            .put("원본 레시피 온도 안내: 350°F")
                            .put("원본 6 inch 팬 안내: 31–33분")
                            .put("팬 크기가 달라졌으므로 새 굽기 시간은 계산하지 않았어요. 원본의 온도와 익음 상태 기준을 참고해 실제 상태를 확인해 주세요."));
            JSONObject normalized = new JSONObject()
                    .put("sourceUrl", url).put("sourceBodySha256", sha)
                    .put("recipeTitle", TITLE).put("publisher", PUBLISHER)
                    .put("sourcePan", sourcePan).put("ingredientGroups", groups)
                    .put("instructionGroups", instructions).put("previewPolicy", policy)
                    .put("cardAndJsonLdMatched", true)
                    .put("numericConversionsApplied", new JSONArray()).put("fitApplied", false);
            JSONObject working = new JSONObject(normalized.toString())
                    .put("contract", "BakeFit_Rev1_Functional_Prototype_Handoff_v0.1")
                    .put("readiness", "SOURCE_FAITHFUL_UNFITTED")
                    .put("orphanNumbers", new JSONArray());
            return new JSONObject()
                    .put("status", "WORKING_RECIPE_SOURCE_PRESERVED")
                    .put("normalizedInput", normalized).put("workingRecipe", working)
                    .put("workingRecipeGuard", new JSONObject()
                            .put("decision", "SOURCE_PRESERVE_PASS")
                            .put("fit", "NOT_RUN")
                            .put("orphanNumbers", new JSONArray())
                            .put("sourceBodySha256", sha));
        } catch (JSONException e) {
            throw new IllegalArgumentException("APPROVED_PREVIEW_FIXTURE_INVALID", e);
        }
    }

    public static JSONObject copyVerified(JSONObject fixture) {
        verify(fixture);
        try { return new JSONObject(fixture.toString()); }
        catch (JSONException e) { throw new IllegalArgumentException("APPROVED_PREVIEW_FIXTURE_INVALID", e); }
    }

    private static JSONObject sourcePan(String instruction, String url, String sha) throws JSONException {
        int countStart = instruction.indexOf("two");
        int diameterStart = instruction.indexOf("6 inch");
        int shapeStart = instruction.indexOf("cake pans");
        return new JSONObject().put("shape", "round").put("diameter", "6 inch")
                .put("diameterUnit", "inch").put("count", 2)
                .put("height", JSONObject.NULL).put("capacity", JSONObject.NULL)
                .put("shapeEvidence", panEvidence("sourcePan.shape", "cake pans", shapeStart,
                        instruction, url, sha))
                .put("diameterEvidence", panEvidence("sourcePan.diameter", "6 inch", diameterStart,
                        instruction, url, sha))
                .put("countEvidence", panEvidence("sourcePan.count", "two", countStart,
                        instruction, url, sha)).put("fitApplied", false);
    }

    private static JSONObject panEvidence(String field, String span, int start, String instruction,
                                          String url, String sha) throws JSONException {
        return new JSONObject().put("semanticField", field).put("sourceSpan", span)
                .put("spanStart", start).put("spanEnd", start + span.length())
                .put("rawInstruction", instruction).put("sourceInstructionLocator", "fixture.instructions[0]")
                .put("jsonLdInstructionLocator", "fixture.instructions[0]")
                .put("sourceUrl", url).put("sourceBodySha256", sha);
    }

    private static JSONObject step(String text, String url, String sha, String locator) throws JSONException {
        return new JSONObject().put("rawText", text).put("sourceUrl", url)
                .put("sourceBodySha256", sha).put("cardLocator", locator)
                .put("jsonLdLocator", locator).put("jsonLdExactMatch", true)
                .put("numericConversion", "NONE_SOURCE_PRESERVED");
    }

    private static void verify(JSONObject fixture) {
        try {
            require(fixture != null && "TEST_WAIT_SOURCE_VERIFIED".equals(fixture.getString("status")) &&
                            "TEST_WAIT".equals(fixture.getString("ruleStatus")) &&
                            "SOURCE_FIXTURE_INTEGRATION_PASS".equals(fixture.getString("sourceFixtureStatus")) &&
                            !fixture.getBoolean("fitApplied") && !fixture.getBoolean("productionReady") &&
                            !fixture.getBoolean("userVisible") && !fixture.getBoolean("frostingScalingApplied") &&
                            fixture.getInt("volumeToMassConversions") == 0 &&
                            SOURCE_SHA.equals(fixture.getString("sourceBodySha256Expected")) &&
                            fixture.getJSONArray("orphanNumbers").length() == 0 &&
                            fixture.getJSONArray("ingredientResults").length() == 9,
                    "APPROVED_PREVIEW_FIXTURE_STATE_MISMATCH");
            JSONObject geometry = fixture.getJSONObject("geometry");
            require("6 inch".equals(geometry.getJSONObject("sourceDiameter").getString("raw")) &&
                            "12 cm".equals(geometry.getJSONObject("targetDiameter").getString("raw")) &&
                            geometry.getInt("sourcePanCount") == 2 && geometry.getInt("targetPanCount") == 2 &&
                            geometry.getBoolean("processPreserved") && !geometry.getBoolean("referenceHeightUsed"),
                    "APPROVED_PREVIEW_FIXTURE_GEOMETRY_MISMATCH");
        } catch (JSONException e) {
            throw new IllegalArgumentException("APPROVED_PREVIEW_FIXTURE_INVALID", e);
        }
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new IllegalArgumentException(code);
    }
}
