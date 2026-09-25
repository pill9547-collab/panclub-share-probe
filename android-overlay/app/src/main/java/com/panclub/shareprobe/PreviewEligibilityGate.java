package com.panclub.shareprobe;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.Locale;

/** Property-based, fail-closed eligibility for a non-production BF-F02 preview. */
public final class PreviewEligibilityGate {
    private PreviewEligibilityGate() {}

    public static JSONObject evaluate(JSONObject result, JSONObject geometry) {
        try { return evaluateChecked(result, geometry); }
        catch (Exception e) {
            return failure("BLOCK", "PREVIEW_STRUCTURE_INVALID");
        }
    }

    private static JSONObject evaluateChecked(JSONObject result, JSONObject geometry) throws JSONException {
        JSONObject out = new JSONObject().put("decision", "PASS")
                .put("checks", new JSONArray()).put("blockers", new JSONArray())
                .put("ingredientCandidates", new JSONArray())
                .put("ruleStatus", "TEST_WAIT").put("fitApplied", false)
                .put("productionReady", false).put("authorityGranted", false);
        JSONObject working = result == null ? null : result.optJSONObject("workingRecipe");
        if (working == null || !"WORKING_RECIPE_SOURCE_PRESERVED".equals(result.optString("status"))) {
            fail(out, "READ", "BLOCK", "SOURCE_PRESERVED_READ_REQUIRED");
            return out;
        }
        JSONObject source = working.optJSONObject("sourcePan");
        JSONObject target = working.optJSONObject("targetPan");
        if (source == null) fail(out, "SOURCE_PAN", "NEED_INFO", "SOURCE_PAN_REQUIRED");
        else pass(out, "SOURCE_PAN");
        if (target == null) fail(out, "TARGET_PAN", "NEED_INFO", "TARGET_PAN_REQUIRED");
        else pass(out, "TARGET_PAN");
        if (source == null || target == null) return out;

        if (!"round".equals(source.optString("shape")) || !"round".equals(target.optString("shape")))
            fail(out, "PAN_SHAPE", "BLOCK", "UNSUPPORTED_NON_ROUND_PAN");
        else pass(out, "PAN_SHAPE");
        if (source.optInt("count", -1) <= 0 || target.optInt("count", -2) <= 0)
            fail(out, "PAN_COUNT", "NEED_INFO", "PAN_COUNT_REQUIRED");
        else if (source.optInt("count") != target.optInt("count"))
            fail(out, "PAN_COUNT", "BLOCK", "PAN_COUNT_MERGE_SPLIT_REQUIRED");
        else pass(out, "PAN_COUNT");

        if (geometry == null || !"GEOMETRY_PASS".equals(geometry.optString("decision")) ||
                !"PASS".equals(geometry.optString("measurementCompatibility")))
            fail(out, "GEOMETRY", "BLOCK", geometry == null
                    ? "GEOMETRY_REQUIRED" : geometry.optString("blocker", "MEASUREMENT_CLASS_MISMATCH"));
        else pass(out, "GEOMETRY");

        JSONObject policy = working.optJSONObject("previewPolicy");
        if (geometry == null || !"GEOMETRY_PASS".equals(geometry.optString("decision")) ||
                (policy != null && policy.has("processPreserved") &&
                        !policy.optBoolean("processPreserved", false)))
            fail(out, "PROCESS", "BLOCK", "PROCESS_PRESERVATION_UNPROVEN");
        else pass(out, "PROCESS");
        if (policy != null && (policy.optBoolean("bakeTimeScalingRequired", false) ||
                (policy.has("targetBakeTime") && !policy.isNull("targetBakeTime"))))
            fail(out, "BAKE_TIME", "BLOCK", "BAKE_TIME_SCALING_NOT_AUTHORIZED");
        else pass(out, "BAKE_TIME");

        JSONArray unsupported = policy == null ? null : policy.optJSONArray("unsupportedIngredientTransformations");
        if (policy != null && (unsupported == null || unsupported.length() != 0))
            fail(out, "TRANSFORMS", "BLOCK", "UNSUPPORTED_INGREDIENT_TRANSFORMATION");
        else pass(out, "TRANSFORMS");

        JSONArray groups = working.optJSONArray("ingredientGroups");
        boolean batterFound = false, frostingFound = false;
        if (groups == null || groups.length() == 0) {
            fail(out, "INGREDIENTS", "BLOCK", "BATTER_INGREDIENT_PATH_REQUIRED");
        } else for (int i = 0; i < groups.length(); i++) {
            JSONObject group = groups.optJSONObject(i);
            if (group == null) { fail(out, "INGREDIENTS", "BLOCK", "INGREDIENT_GROUP_INVALID"); continue; }
            String component = (group.optString("component") + " " + group.optString("sourceHeading"))
                    .toLowerCase(Locale.ROOT);
            boolean frosting = containsAny(component, "frosting", "filling", "buttercream", "icing");
            boolean batter = component.contains("batter") || component.contains("cake layer");
            if (frosting) {
                frostingFound = true;
                if (policy == null || !policy.optBoolean("frostingExcludedFromPreview", false))
                    fail(out, "FROSTING", "BLOCK", "FROSTING_SCALING_REQUIRED");
                continue;
            }
            if (!batter) {
                fail(out, "INGREDIENTS", "BLOCK", "UNSUPPORTED_RECIPE_COMPONENT");
                continue;
            }
            batterFound = true;
            JSONArray rows = group.optJSONArray("ingredients");
            if (rows == null || rows.length() == 0) {
                fail(out, "INGREDIENTS", "BLOCK", "BATTER_INGREDIENT_PATH_REQUIRED");
                continue;
            }
            for (int j = 0; j < rows.length(); j++) evaluateRow(out, rows.optJSONObject(j));
        }
        if (!batterFound) fail(out, "INGREDIENTS", "BLOCK", "BATTER_INGREDIENT_PATH_REQUIRED");
        if (!frostingFound) pass(out, "FROSTING");
        else if (frostingFound && policy != null && policy.optBoolean("frostingExcludedFromPreview", false) &&
                !policy.optBoolean("frostingScalingRequired", true)) pass(out, "FROSTING");

        JSONArray orphans = working.optJSONArray("orphanNumbers");
        JSONObject guard = result.optJSONObject("workingRecipeGuard");
        JSONArray guardOrphans = guard == null ? null : guard.optJSONArray("orphanNumbers");
        JSONArray conversions = working.optJSONArray("numericConversionsApplied");
        if (orphans == null || orphans.length() != 0 || guard == null ||
                !"SOURCE_PRESERVE_PASS".equals(guard.optString("decision")) ||
                guardOrphans == null || guardOrphans.length() != 0 ||
                conversions == null || conversions.length() != 0)
            fail(out, "SOURCE_GUARDS", "BLOCK", "ORPHAN_CONVERSION_OR_PROVENANCE_CONFLICT");
        else pass(out, "SOURCE_GUARDS");
        return out;
    }

    private static void evaluateRow(JSONObject out, JSONObject row) throws JSONException {
        if (row == null) { fail(out, "INGREDIENTS", "BLOCK", "INGREDIENT_ROW_INVALID"); return; }
        String name = row.optString("nameRaw").trim();
        String unit = row.optString("unitRaw").trim();
        String amount = row.optString("amountRaw").trim();
        String semantic = row.optString("quantitySemanticState");
        String lowerName = name.toLowerCase(Locale.ROOT);
        if (!"STRUCTURED_SINGLE_CARD_AMOUNT".equals(semantic) || row.optJSONArray("unresolvedQuantityFragments") != null) {
            fail(out, "INGREDIENTS", "BLOCK", "COMPOUND_INGREDIENT_QUANTITY_UNRESOLVED");
            return;
        }
        if (!"g".equals(unit)) {
            fail(out, "INGREDIENTS", "BLOCK", lowerName.contains("egg")
                    ? "EGG_EDIBLE_MASS_UNPROVEN" : "CUP_OR_NON_GRAM_QUANTITY_REQUIRES_CONVERSION");
            return;
        }
        if (name.isEmpty() || !amount.matches("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?") ||
                new BigDecimal(amount).signum() <= 0) {
            fail(out, "INGREDIENTS", "BLOCK", "EXPLICIT_POSITIVE_GRAM_QUANTITY_REQUIRED");
            return;
        }
        if (!"NONE_SOURCE_PRESERVED".equals(row.optString("numericConversion"))) {
            fail(out, "INGREDIENTS", "BLOCK", "UNSUPPORTED_INGREDIENT_TRANSFORMATION");
            return;
        }
        out.getJSONArray("ingredientCandidates").put(new JSONObject()
                .put("name", name).put("sourceGrams", amount)
                .put("sourceEvidence", row.optJSONObject("sourceEvidence") == null
                        ? JSONObject.NULL : new JSONObject(row.getJSONObject("sourceEvidence").toString())));
    }

    private static boolean containsAny(String value, String... needles) {
        for (String needle : needles) if (value.contains(needle)) return true;
        return false;
    }

    private static void pass(JSONObject out, String check) throws JSONException {
        out.getJSONArray("checks").put(new JSONObject().put("check", check).put("status", "PASS"));
    }

    private static void fail(JSONObject out, String check, String severity, String code) throws JSONException {
        out.getJSONArray("checks").put(new JSONObject().put("check", check).put("status", severity));
        out.getJSONArray("blockers").put(new JSONObject().put("code", code));
        if ("BLOCK".equals(severity) || "PASS".equals(out.optString("decision"))) out.put("decision", severity);
    }

    private static JSONObject failure(String decision, String code) {
        JSONObject out = new JSONObject();
        try { return out.put("decision", decision).put("blockers", new JSONArray()
                .put(new JSONObject().put("code", code))).put("checks", new JSONArray())
                .put("ingredientCandidates", new JSONArray()).put("ruleStatus", "TEST_WAIT")
                .put("fitApplied", false).put("productionReady", false).put("authorityGranted", false); }
        catch (JSONException ignored) { return out; }
    }
}
