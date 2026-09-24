package com.panclub.shareprobe;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Canonical v1.0 geometry-only arithmetic; never mutates or FITs a recipe. */
public final class GeometryFactorStage {
    private static final String CONTRACT = "BakeFit_Rev1_Canonical_RuleContract_v1.0.md";
    private static final BigDecimal CENTIMETERS_PER_INCH = new BigDecimal("2.54");
    private static final Pattern INCH = Pattern.compile("^([1-9][0-9]*(?:\\.[0-9]+)?) inch$");
    private static final Pattern CM = Pattern.compile("^([1-9][0-9]*(?:\\.[0-9]+)?) cm$");
    private static final Pattern RECIPE_PAN_SUFFIX = Pattern.compile("(?i)^\\s+cake pans?\\b");
    private static final Pattern MEASUREMENT_QUALIFIER = Pattern.compile("(?i)\\b(inner|outer|inside|outside|interior|exterior)\\b|내경|외경");

    private GeometryFactorStage() {}

    /** intent asserts unchanged process/structure and depth intent for this separate narrow fixture. */
    public static JSONObject evaluate(JSONObject result, JSONObject intent) {
        try {
            return evaluateChecked(result, intent);
        } catch (JSONException e) {
            JSONObject out = new JSONObject();
            try {
                out.put("decision", "BLOCK");
                out.put("fitApplied", false);
                out.put("ingredientScalingApplied", false);
                out.put("ingredientScalingCount", 0);
                out.put("numericConversionsApplied", new JSONArray());
                out.put("orphanNumbers", new JSONArray());
                out.put("blocker", "JSON_STRUCTURE_INVALID");
            } catch (JSONException ignored) {
                // Android org.json uses checked JSONException. Empty output remains fail-closed.
            }
            return out;
        }
    }

    private static JSONObject evaluateChecked(JSONObject result, JSONObject intent) throws JSONException {
        JSONObject out = new JSONObject()
                .put("decision", "BLOCK")
                .put("fitApplied", false)
                .put("ingredientScalingApplied", false)
                .put("ingredientScalingCount", 0)
                .put("numericConversionsApplied", new JSONArray())
                .put("orphanNumbers", new JSONArray());
        if (result == null || intent == null ||
                !("GEOMETRY_FIXTURE_ASSERTION".equals(intent.optString("origin")) ||
                        "USER_TARGET_EVALUATION_INTENT".equals(intent.optString("origin"))) ||
                !intent.optBoolean("layerStructurePreserved", false) ||
                !intent.optBoolean("processPreserved", false) ||
                !intent.optBoolean("sameBatterDepthIntent", false)) return block(out, "GEOMETRY_INTENT_UNPROVEN");

        // Existing eligibility verifies pan spans, distinct origins, the unchanged READ snapshot,
        // ordinary layers, pan count, process, orphan audit, and revision freshness. Its separate
        // ingredient/FIT and pre-v1 unit-path blockers never become permission for recipe FIT.
        JSONObject gate = FitEligibilityGate.evaluate(result);
        if (!pass(gate, "PAN_COUNT")) return block(out, "PAN_COUNT_MERGE_SPLIT_REQUIRED");
        for (String check : new String[]{"ORDINARY_ROUND_LAYER", "SOURCE_PAN_DIMENSIONS",
                "TARGET_PAN_DIMENSIONS", "PROCESS_PRESERVATION", "ORPHAN_CONFLICT_REVISION"})
            if (!pass(gate, check)) return block(out, check + "_UNPROVEN");

        JSONObject working = result.getJSONObject("workingRecipe");
        JSONObject source = working.getJSONObject("sourcePan");
        JSONObject target = working.getJSONObject("targetPan");
        JSONObject sourceEvidence = source.getJSONObject("diameterEvidence");
        JSONObject targetEvidence = target.getJSONObject("diameterEvidence");
        if (!"inch".equals(source.optString("diameterUnit")) ||
                !"cm".equals(target.optString("diameterUnit"))) return block(out, "LENGTH_UNITS_UNSUPPORTED");

        // BF-P04: infer *nominal* from the explicit author pan phrase and plain user target.
        // Classification lives only in this stage's output, never in the source facts.
        String instruction = sourceEvidence.optString("rawInstruction");
        String userInput = targetEvidence.optString("rawInput");
        if (MEASUREMENT_QUALIFIER.matcher(instruction).find() ||
                MEASUREMENT_QUALIFIER.matcher(userInput).find() ||
                !RECIPE_PAN_SUFFIX.matcher(instruction.substring(
                        sourceEvidence.getInt("spanEnd"))).find() ||
                !userInput.matches("원형 [1-9][0-9]*(?:\\.[0-9]+)? cm 팬 [1-9][0-9]*개"))
            return block(out, "PAN_DIAMETER_SEMANTIC_CLASS_UNVERIFIED");

        Matcher s = INCH.matcher(source.optString("diameter"));
        Matcher t = CM.matcher(target.optString("diameter"));
        if (!s.matches() || !t.matches()) return block(out, "DIAMETER_RAW_VALUE_UNVERIFIED");
        BigDecimal sourceRaw = new BigDecimal(s.group(1));
        BigDecimal targetRaw = new BigDecimal(t.group(1));
        BigDecimal sourceCm = normalizeInches(sourceRaw);
        BigDecimal factor = factorFromCentimeters(sourceCm, targetRaw);

        JSONObject sourceClass = classification("sourcePan.diameter", sourceEvidence);
        JSONObject targetClass = classification("targetPan.diameter", targetEvidence);
        JSONObject conversion = new JSONObject()
                .put("ruleId", "BF-U01")
                .put("semanticField", "sourcePan.diameter")
                .put("measurementClass", "NOMINAL_DIAMETER")
                .put("sourceRawValue", s.group(1))
                .put("sourceRawUnit", "inch")
                .put("sourceProvenance", new JSONObject(sourceEvidence.toString()))
                .put("normalizedValue", sourceCm.toPlainString())
                .put("normalizedUnit", "cm")
                .put("exactDefinition", "1 inch = 2.54 cm");
        JSONObject derivation = new JSONObject()
                .put("semanticField", "geometryFactor")
                .put("comparisonSemanticField", "pan.diameter")
                .put("sourceDiameter", conversion)
                .put("targetDiameter", new JSONObject()
                        .put("semanticField", "targetPan.diameter")
                        .put("rawValue", t.group(1))
                        .put("rawUnit", "cm")
                        .put("measurementClass", "NOMINAL_DIAMETER")
                        .put("provenance", new JSONObject(targetEvidence.toString())))
                .put("sourceMeasurementClass", sourceClass)
                .put("targetMeasurementClass", targetClass)
                .put("sourceCount", new JSONObject()
                        .put("semanticField", "sourcePan.count")
                        .put("value", source.getInt("count"))
                        .put("provenance", new JSONObject(source.getJSONObject("countEvidence").toString())))
                .put("targetCount", new JSONObject()
                        .put("semanticField", "targetPan.count")
                        .put("value", target.getInt("count"))
                        .put("provenance", new JSONObject(target.getJSONObject("countEvidence").toString())))
                .put("geometryIntent", new JSONObject(intent.toString()))
                .put("inputRevision", result.get("inputRevision"))
                .put("ruleChain", new JSONArray().put("BF-P04").put("BF-U01").put("BF-F01"))
                .put("formula", "(targetDiameterCm/sourceDiameterCm)^2")
                .put("ruleContract", CONTRACT);
        return out.put("decision", "GEOMETRY_PASS")
                .put("sourceDiameterNormalized", sourceCm.toPlainString() + " cm")
                .put("targetDiameterNormalized", targetRaw.toPlainString() + " cm")
                .put("sourceCount", source.getInt("count"))
                .put("targetCount", target.getInt("count"))
                .put("measurementCompatibility", "PASS")
                .put("geometryFactor", factor.toPlainString())
                .put("ruleChain", new JSONArray().put("BF-P04").put("BF-U01").put("BF-F01"))
                .put("numericConversionsApplied", new JSONArray().put(conversion))
                .put("derivation", derivation);
    }

    private static JSONObject classification(String field, JSONObject evidence) throws JSONException {
        return new JSONObject().put("semanticField", field)
                .put("measurementClass", "NOMINAL_DIAMETER")
                .put("ruleId", "BF-P04")
                .put("provenance", new JSONObject(evidence.toString()));
    }

    /** Same BF-U01/BF-F01 arithmetic used by the provenance-checked frozen-source Simulator. */
    static BigDecimal normalizeInches(BigDecimal sourceInches) {
        return sourceInches.multiply(CENTIMETERS_PER_INCH);
    }

    static BigDecimal factorFromCentimeters(BigDecimal sourceCm, BigDecimal targetCm) {
        BigDecimal diameterRatio = targetCm.divide(sourceCm, MathContext.DECIMAL128);
        return diameterRatio.multiply(diameterRatio, MathContext.DECIMAL128);
    }

    private static boolean pass(JSONObject gate, String check) throws JSONException {
        JSONArray checks = gate.optJSONArray("checks");
        if (checks == null) return false;
        for (int i = 0; i < checks.length(); i++) {
            JSONObject row = checks.getJSONObject(i);
            if (check.equals(row.optString("check"))) return "PASS".equals(row.optString("status"));
        }
        return false;
    }

    private static JSONObject block(JSONObject out, String code) throws JSONException { return out.put("blocker", code); }
}
