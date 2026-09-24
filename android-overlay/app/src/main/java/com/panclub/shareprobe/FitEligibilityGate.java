package com.panclub.shareprobe;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.Iterator;
import java.util.Locale;

/** Checks LOCKED prototype safety requirements without calculating or applying FIT. */
public final class FitEligibilityGate {
    private static final String HANDOFF = "BakeFit_Rev1_Functional_Prototype_Handoff_v0.1";
    private static final String SAFETY = "BakeFit_Rev1_Functional_Prototype_Safety_Handoff_v1.0";
    private static final String GOLDEN = "BakeFit_Simulator_GoldenFixtures_v0.2";

    private FitEligibilityGate() {}

    public static JSONObject evaluate(JSONObject result) {
        try {
            return evaluateChecked(result);
        } catch (JSONException e) {
            JSONObject out = new JSONObject();
            try {
                out.put("decision", "BLOCK");
                out.put("blockers", new JSONArray().put(new JSONObject().put("code", "JSON_STRUCTURE_INVALID")));
                out.put("checks", new JSONArray());
                out.put("fitApplied", false);
            } catch (JSONException ignored) {
                // Android org.json uses checked JSONException. Empty output remains fail-closed.
            }
            return out;
        }
    }

    private static JSONObject evaluateChecked(JSONObject result) throws JSONException {
        JSONObject working = result == null ? null : result.optJSONObject("workingRecipe");
        JSONObject source = working == null ? null : working.optJSONObject("sourcePan");
        JSONObject target = working == null ? null : working.optJSONObject("targetPan");
        JSONObject eligibility = new JSONObject()
                .put("decision", "ALLOW")
                .put("blockers", new JSONArray())
                .put("checks", new JSONArray())
                .put("sourcePan", source == null ? JSONObject.NULL : new JSONObject(source.toString()))
                .put("targetPan", target == null ? JSONObject.NULL : new JSONObject(target.toString()))
                .put("fitApplied", false);
        if (working == null || result.optJSONObject("normalizedInput") == null ||
                !"WORKING_RECIPE_SOURCE_PRESERVED".equals(result.optString("status")) ||
                working.optBoolean("fitApplied", true)) {
            fail(eligibility, "SOURCE_PRESERVED_INPUT", "BLOCK", "SOURCE_PRESERVED_INPUT_REQUIRED", HANDOFF + " §4");
            return eligibility;
        }

        // G01/G12: only a proven round-layer recipe may enter ordinary round FIT.
        if (source == null || !"round".equals(source.optString("shape")) ||
                !"round".equals(target == null ? "" : target.optString("shape")) ||
                !roundLayerEvidence(working))
            fail(eligibility, "ORDINARY_ROUND_LAYER", "BLOCK", "ORDINARY_ROUND_LAYER_UNPROVEN", HANDOFF + " §6 G12; " + GOLDEN + " G01");
        else pass(eligibility, "ORDINARY_ROUND_LAYER", GOLDEN + " G01");

        boolean sourceProven = sourceDimensionEvidence(source, working.optString("sourceUrl"), working.optString("sourceBodySha256"));
        if (!sourceProven)
            fail(eligibility, "SOURCE_PAN_DIMENSIONS", "RECOVER", "SOURCE_PAN_DIMENSION_PROVENANCE_MISSING", GOLDEN + " G01–G02");
        else pass(eligibility, "SOURCE_PAN_DIMENSIONS", GOLDEN + " G01");

        boolean targetProven = targetDimensionEvidence(target) &&
                working.optJSONObject("panOrigins") != null &&
                "SOURCE_RECIPE".equals(working.getJSONObject("panOrigins").optString("sourcePan")) &&
                TargetPanContract.ORIGIN.equals(working.getJSONObject("panOrigins").optString("targetPan"));
        if (!targetProven)
            fail(eligibility, "TARGET_PAN_DIMENSIONS", "RECOVER", "TARGET_PAN_USER_PROVENANCE_MISSING", HANDOFF + " §2; " + GOLDEN + " G01");
        else pass(eligibility, "TARGET_PAN_DIMENSIONS", HANDOFF + " §2; " + GOLDEN + " G01");

        // A two-pan source cannot be silently folded into the one-pan target.
        boolean countKnown = sourceProven && targetProven && source.has("count") && target.has("count");
        boolean sameCount = countKnown && source.optInt("count", -1) == target.optInt("count", -2);
        if (!countKnown)
            fail(eligibility, "PAN_COUNT", "RECOVER", "PAN_COUNT_UNPROVEN", GOLDEN + " G09");
        else if (!sameCount)
            fail(eligibility, "PAN_COUNT", "BLOCK", "PAN_COUNT_MERGE_SPLIT_REQUIRED", GOLDEN + " G09");
        else pass(eligibility, "PAN_COUNT", GOLDEN + " G01/G09");

        if (!sameCount)
            fail(eligibility, "PROCESS_PRESERVATION", countKnown ? "BLOCK" : "RECOVER",
                    countKnown ? "PROCESS_REWRITE_REQUIRED_FOR_PAN_COUNT" : "PROCESS_PRESERVATION_UNPROVEN", GOLDEN + " G09");
        else if (!roundLayerEvidence(working))
            fail(eligibility, "PROCESS_PRESERVATION", "RECOVER", "PROCESS_PRESERVATION_UNPROVEN", GOLDEN + " G01");
        else pass(eligibility, "PROCESS_PRESERVATION", GOLDEN + " G01");

        JSONArray groups = working.optJSONArray("ingredientGroups");
        if (groups == null || groups.length() == 0)
            fail(eligibility, "INGREDIENT_TRANSFORM_PATH", "RECOVER", "INGREDIENT_QUANTITY_PROVENANCE_MISSING", SAFETY + " §2–3");
        else {
            boolean unresolved = false, massOnly = true, eggMassMissing = false, frosting = false;
            for (int i = 0; i < groups.length(); i++) {
                JSONObject group = groups.getJSONObject(i);
                if (group.optString("component").toLowerCase().contains("frosting") ||
                        group.optString("component").toLowerCase().contains("filling")) frosting = true;
                JSONArray rows = group.getJSONArray("ingredients");
                for (int j = 0; j < rows.length(); j++) {
                    JSONObject row = rows.getJSONObject(j);
                    if (!"STRUCTURED_SINGLE_CARD_AMOUNT".equals(row.optString("quantitySemanticState"))) unresolved = true;
                    if (!"g".equals(row.optString("unitRaw"))) massOnly = false;
                    if (row.optString("nameRaw").toLowerCase().contains("egg") &&
                            !"g".equals(row.optString("unitRaw"))) eggMassMissing = true;
                }
            }
            if (unresolved) fail(eligibility, "INGREDIENT_TRANSFORM_PATH", "BLOCK",
                    "COMPOUND_INGREDIENT_QUANTITY_UNRESOLVED", HANDOFF + " §2–4; " + SAFETY + " §3 G02");
            if (!massOnly) fail(eligibility, "INGREDIENT_TRANSFORM_PATH", "BLOCK",
                    "SOURCE_INGREDIENT_MASS_PATH_UNAVAILABLE", GOLDEN + " G01; " + SAFETY + " G04/G06");
            if (eggMassMissing) fail(eligibility, "INGREDIENT_TRANSFORM_PATH", "BLOCK",
                    "LARGE_EGG_EDIBLE_MASS_UNPROVEN", HANDOFF + " §6 G04; " + SAFETY + " G05");
            if (!unresolved && massOnly && !eggMassMissing)
                fail(eligibility, "INGREDIENT_TRANSFORM_PATH", "RECOVER",
                        "LOCKED_NUMERIC_TRANSFORM_PATH_UNPROVEN", HANDOFF + " §4");

            if (frosting) fail(eligibility, "SEPARATE_COMPONENT_SCALING", "BLOCK",
                    "FROSTING_FIT_POLICY_UNAVAILABLE", HANDOFF + " §6 G05; " + GOLDEN + " G08");
            else pass(eligibility, "SEPARATE_COMPONENT_SCALING", HANDOFF + " §6 G05");
        }

        if (source == null || target == null ||
                !source.optString("diameterUnit").equals(target.optString("diameterUnit")))
            fail(eligibility, "UNIT_CONVERSION", "BLOCK", "PAN_UNIT_CONVERSION_LOCKED_PATH_UNPROVEN", HANDOFF + " §4; " + SAFETY + " §3 G04");
        else pass(eligibility, "UNIT_CONVERSION", HANDOFF + " §4");

        JSONObject normalized = result.getJSONObject("normalizedInput");
        boolean sameSnapshot = (source == null ? normalized.isNull("sourcePan") :
                jsonEquivalent(source, normalized.optJSONObject("sourcePan"))) &&
                (target == null ? normalized.isNull("targetPan") :
                jsonEquivalent(target, normalized.optJSONObject("targetPan")));
        boolean noOrphans = working.optJSONArray("orphanNumbers") != null &&
                working.getJSONArray("orphanNumbers").length() == 0 &&
                result.optJSONObject("workingRecipeGuard") != null &&
                "SOURCE_PRESERVE_PASS".equals(result.getJSONObject("workingRecipeGuard").optString("decision")) &&
                result.getJSONObject("workingRecipeGuard").optJSONArray("orphanNumbers") != null &&
                result.getJSONObject("workingRecipeGuard").getJSONArray("orphanNumbers").length() == 0;
        if (!noOrphans || working.optJSONArray("numericConversionsApplied") == null ||
                working.getJSONArray("numericConversionsApplied").length() != 0 ||
                !"NOT_RUN".equals(result.getJSONObject("workingRecipeGuard").optString("fit")))
            fail(eligibility, "ORPHAN_CONFLICT_REVISION", "BLOCK", "ORPHAN_OR_GUARD_CONFLICT", HANDOFF + " §2–4 G01/G03");
        else if (!sameSnapshot)
            fail(eligibility, "ORPHAN_CONFLICT_REVISION", "BLOCK", "SOURCE_TARGET_SNAPSHOT_CONFLICT", HANDOFF + " §6 G03; " + SAFETY + " §2 G11");
        else if (!working.optBoolean("cardAndJsonLdMatched", false))
            fail(eligibility, "ORPHAN_CONFLICT_REVISION", "BLOCK", "SOURCE_CONFLICT_UNRESOLVED", HANDOFF + " §6 G03");
        else if (!result.has("inputRevision") || !result.has("workingRecipeRevision"))
            fail(eligibility, "ORPHAN_CONFLICT_REVISION", "RECOVER", "REVISION_FRESHNESS_UNVERIFIED", SAFETY + " §2 G11");
        else if (!result.get("inputRevision").equals(result.get("workingRecipeRevision")))
            fail(eligibility, "ORPHAN_CONFLICT_REVISION", "BLOCK", "STALE_WORKING_RECIPE_REVISION", SAFETY + " §2 G11");
        else pass(eligibility, "ORPHAN_CONFLICT_REVISION", SAFETY + " §2 G11");

        // Even the ALLOW verdict here would only be eligibility, never a calculation.
        return eligibility;
    }


    /** Android's platform JSONObject does not expose JSON-java's similar(). */
    private static boolean jsonEquivalent(JSONObject left, JSONObject right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        int leftCount = 0;
        for (Iterator<String> it = left.keys(); it.hasNext();) {
            String key = it.next();
            leftCount++;
            if (!right.has(key) || !jsonValueEquivalent(left.opt(key), right.opt(key))) return false;
        }
        int rightCount = 0;
        for (Iterator<String> it = right.keys(); it.hasNext();) {
            it.next();
            rightCount++;
        }
        return leftCount == rightCount;
    }

    private static boolean jsonArrayEquivalent(JSONArray left, JSONArray right) {
        if (left == right) return true;
        if (left == null || right == null || left.length() != right.length()) return false;
        for (int i = 0; i < left.length(); i++)
            if (!jsonValueEquivalent(left.opt(i), right.opt(i))) return false;
        return true;
    }

    private static boolean jsonValueEquivalent(Object left, Object right) {
        if (left == right) return true;
        if (left == null || right == null) return false;
        if (left == JSONObject.NULL || right == JSONObject.NULL)
            return left == JSONObject.NULL && right == JSONObject.NULL;
        if (left instanceof JSONObject && right instanceof JSONObject)
            return jsonEquivalent((JSONObject) left, (JSONObject) right);
        if (left instanceof JSONArray && right instanceof JSONArray)
            return jsonArrayEquivalent((JSONArray) left, (JSONArray) right);
        if (left instanceof Number && right instanceof Number) {
            try {
                return new BigDecimal(left.toString()).compareTo(new BigDecimal(right.toString())) == 0;
            } catch (NumberFormatException ignored) {
                return left.toString().equals(right.toString());
            }
        }
        return left.equals(right);
    }

    private static boolean roundLayerEvidence(JSONObject working) throws JSONException {
        JSONArray sections = working.optJSONArray("instructionGroups");
        if (sections == null) return false;
        String title = working.optString("recipeTitle").toLowerCase(Locale.ROOT);
        for (String unsupported : new String[]{"chiffon", "angel food", "bundt", "roll cake", "loaf", "tube cake"})
            if (title.contains(unsupported)) return false;
        boolean bakeLayers = false, assembleLayers = false;
        for (int i = 0; i < sections.length(); i++) {
            JSONObject section = sections.getJSONObject(i);
            if (section.optString("sourceHeading").toLowerCase().contains("cake layers")) bakeLayers = true;
            JSONArray steps = section.getJSONArray("steps");
            for (int j = 0; j < steps.length(); j++)
                if (steps.getJSONObject(j).optString("rawText").toLowerCase().contains("two cooled cake layers")) assembleLayers = true;
        }
        return bakeLayers && assembleLayers && working.optBoolean("cardAndJsonLdMatched", false);
    }

    private static boolean sourceDimensionEvidence(JSONObject pan, String url, String sha) throws JSONException {
        if (pan == null || !"round".equals(pan.optString("shape")) ||
                !pan.optString("diameter").matches("[1-9][0-9]*(?:\\.[0-9]+)? inch(?:es)?") ||
                !"inch".equals(pan.optString("diameterUnit"))) return false;
        for (String field : new String[]{"shape", "diameter", "count"}) {
            JSONObject evidence = pan.optJSONObject(field + "Evidence");
            if (evidence == null || !evidence.optString("semanticField").equals("sourcePan." + field) ||
                    !url.equals(evidence.optString("sourceUrl")) || !sha.equals(evidence.optString("sourceBodySha256")) ||
                    evidence.optString("sourceInstructionLocator").isEmpty()) return false;
            String raw = evidence.optString("rawInstruction"), span = evidence.optString("sourceSpan");
            int start = evidence.optInt("spanStart", -1), end = evidence.optInt("spanEnd", -1);
            if (start < 0 || end > raw.length() || end <= start || !raw.substring(start, end).equals(span)) return false;
        }
        return pan.optInt("count", 0) > 0 &&
                pan.getJSONObject("diameterEvidence").optString("sourceSpan").equals(pan.optString("diameter"));
    }

    private static boolean targetDimensionEvidence(JSONObject pan) throws JSONException {
        if (pan == null || !"round".equals(pan.optString("shape")) ||
                !TargetPanContract.ORIGIN.equals(pan.optString("origin")) ||
                !pan.optString("diameter").matches("[1-9][0-9]*(?:\\.[0-9]+)? cm") ||
                !"cm".equals(pan.optString("diameterUnit")) || pan.optInt("count", 0) <= 0) return false;
        String raw = pan.optString("userInputRaw");
        for (String field : new String[]{"shape", "diameter", "count"}) {
            JSONObject evidence = pan.optJSONObject(field + "Evidence");
            if (evidence == null || !TargetPanContract.ORIGIN.equals(evidence.optString("origin")) ||
                    !("targetPan." + field).equals(evidence.optString("semanticField")) ||
                    !raw.equals(evidence.optString("rawInput")) ||
                    !targetInputLocator(field, evidence.optString("inputLocator"))) return false;
            int start = evidence.optInt("spanStart", -1), end = evidence.optInt("spanEnd", -1);
            if (start < 0 || end > raw.length() || end <= start ||
                    !raw.substring(start, end).equals(evidence.optString("sourceSpan"))) return false;
        }
        return pan.getJSONObject("diameterEvidence").optString("sourceSpan").equals(pan.optString("diameter")) &&
                pan.getJSONObject("countEvidence").optString("sourceSpan").equals(Integer.toString(pan.optInt("count")));
    }

    private static boolean targetInputLocator(String field, String locator) {
        if ("userInput.text".equals(locator)) return true;
        if ("shape".equals(field)) return "targetPanForm.shape".equals(locator);
        if ("diameter".equals(field)) return "targetPanForm.diameterCm".equals(locator);
        return "count".equals(field) && "targetPanForm.count".equals(locator);
    }

    private static void pass(JSONObject output, String check, String contract) throws JSONException {
        output.getJSONArray("checks").put(new JSONObject().put("check", check).put("status", "PASS").put("contractRef", contract));
    }
    private static void fail(JSONObject output, String check, String severity, String code, String contract) throws JSONException {
        output.getJSONArray("checks").put(new JSONObject().put("check", check).put("status", severity).put("contractRef", contract));
        output.getJSONArray("blockers").put(new JSONObject().put("code", code).put("contractRef", contract));
        if ("BLOCK".equals(severity) || !"BLOCK".equals(output.getString("decision"))) output.put("decision", severity);
    }
}
