package com.panclub.shareprobe;

import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Structured user target-pan input. No source extraction, geometry, or FIT occurs here. */
public final class TargetPanContract {
    public static final String ORIGIN = "USER_PROVIDED_TARGET_PAN";
    private static final Pattern KOREAN_ROUND_PAN = Pattern.compile(
            "^(원형) ([^ ]+) (cm) 팬 ([^개]+)개$");
    private static final Pattern DECIMAL = Pattern.compile("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?");
    private static final Pattern POSITIVE_INTEGER = Pattern.compile("[1-9][0-9]*");
    private static final Pattern KOREAN_SIZE_LABEL = Pattern.compile("^(?:미니|[1-9][0-9]*호)$");

    private TargetPanContract() {}

    /** Backward-compatible text entry point used by fixtures and diagnostics. */
    public static JSONObject parse(String rawInput) {
        if (rawInput == null) throw new IllegalArgumentException("TARGET_PAN_INPUT_MISSING");
        Matcher match = KOREAN_ROUND_PAN.matcher(rawInput.trim());
        if (!match.matches()) throw new IllegalArgumentException("TARGET_PAN_INPUT_UNSUPPORTED");
        return parseFields(match.group(2), match.group(4), null, "userInput.text");
    }

    /** Production form entry point. Height is retained as metadata and never enters BF-F01. */
    public static JSONObject parseFields(String diameterCm, String countText, String heightCm) {
        return parseFields(diameterCm, countText, heightCm, "targetPanForm");
    }

    private static JSONObject parseFields(String diameterCm, String countText, String heightCm, String locatorRoot) {
        try {
            String diameter = required(diameterCm, "TARGET_PAN_DIAMETER_MISSING");
            String countRaw = required(countText, "TARGET_PAN_COUNT_MISSING");
            String height = heightCm == null ? "" : heightCm.trim();
            if (KOREAN_SIZE_LABEL.matcher(diameter).matches() || diameter.contains("호"))
                throw new IllegalArgumentException("TARGET_PAN_DIAMETER_CM_REQUIRED");
            positiveDecimal(diameter, "TARGET_PAN_DIAMETER_INVALID");
            if (!POSITIVE_INTEGER.matcher(countRaw).matches())
                throw new IllegalArgumentException("TARGET_PAN_COUNT_INVALID");
            final int count;
            try { count = Integer.parseInt(countRaw); }
            catch (NumberFormatException e) {
                throw new IllegalArgumentException("TARGET_PAN_COUNT_UNREPRESENTABLE", e);
            }
            if (!height.isEmpty()) {
                if (KOREAN_SIZE_LABEL.matcher(height).matches() || height.contains("호"))
                    throw new IllegalArgumentException("TARGET_PAN_HEIGHT_CM_REQUIRED");
                positiveDecimal(height, "TARGET_PAN_HEIGHT_INVALID");
            }

            String raw = "원형 " + diameter + " cm 팬 " + countRaw + "개";
            int diameterStart = 3;
            int diameterEnd = diameterStart + diameter.length() + 3;
            int countStart = raw.lastIndexOf(countRaw + "개");
            JSONObject out = new JSONObject()
                    .put("origin", ORIGIN)
                    .put("inputMode", "STRUCTURED_FORM")
                    .put("userInputRaw", raw)
                    .put("shape", "round")
                    .put("diameter", diameter + " cm")
                    .put("diameterValue", diameter)
                    .put("diameterUnit", "cm")
                    .put("measurementClass", "NOMINAL_DIAMETER")
                    .put("count", count)
                    .put("height", height.isEmpty() ? JSONObject.NULL : height + " cm")
                    .put("heightValue", height.isEmpty() ? JSONObject.NULL : height)
                    .put("heightUnit", height.isEmpty() ? JSONObject.NULL : "cm")
                    .put("heightRole", height.isEmpty() ? JSONObject.NULL : "OPTIONAL_METADATA_ONLY")
                    .put("capacity", JSONObject.NULL)
                    .put("shapeEvidence", evidence("targetPan.shape", ORIGIN, raw, "원형", 0, 2,
                            locator(locatorRoot, "shape")))
                    .put("diameterEvidence", evidence("targetPan.diameter", ORIGIN, raw,
                            diameter + " cm", diameterStart, diameterEnd, locator(locatorRoot, "diameterCm")))
                    .put("countEvidence", evidence("targetPan.count", ORIGIN, raw,
                            countRaw, countStart, countStart + countRaw.length(), locator(locatorRoot, "count")))
                    .put("fitApplied", false);
            if (!height.isEmpty())
                out.put("heightEvidence", new JSONObject()
                        .put("semanticField", "targetPan.height")
                        .put("origin", ORIGIN)
                        .put("inputLocator", locator(locatorRoot, "heightCm"))
                        .put("rawInput", height)
                        .put("sourceSpan", height + " cm")
                        .put("role", "OPTIONAL_METADATA_ONLY")
                        .put("usedByGeometry", false));
            return out;
        } catch (JSONException e) {
            throw new IllegalStateException("TARGET_PAN_JSON_BUILD_FAILED", e);
        }
    }

    public static JSONObject attach(JSONObject existingResult, String rawInput) {
        return attachTarget(existingResult, parse(rawInput));
    }

    public static JSONObject attachFields(JSONObject existingResult, String diameterCm,
                                          String countText, String heightCm) {
        return attachTarget(existingResult, parseFields(diameterCm, countText, heightCm));
    }

    private static JSONObject attachTarget(JSONObject existingResult, JSONObject target) {
        try {
            JSONObject workingInput = existingResult == null ? null : existingResult.optJSONObject("workingRecipe");
            if (existingResult == null ||
                    !"WORKING_RECIPE_SOURCE_PRESERVED".equals(existingResult.optString("status")) ||
                    workingInput == null || existingResult.optJSONObject("normalizedInput") == null ||
                    workingInput.optBoolean("fitApplied", true))
                throw new IllegalArgumentException("SOURCE_PRESERVED_WORKING_RECIPE_REQUIRED");

            JSONObject result = new JSONObject(existingResult.toString());
            JSONObject working = result.getJSONObject("workingRecipe");
            JSONObject normalized = result.getJSONObject("normalizedInput");
            if (working.has("targetPan") || normalized.has("targetPan"))
                throw new IllegalArgumentException("TARGET_PAN_ALREADY_PRESENT");
            for (JSONObject recipe : new JSONObject[]{normalized, working}) {
                recipe.put("targetPan", new JSONObject(target.toString()));
                recipe.put("panOrigins", new JSONObject()
                        .put("sourcePan", recipe.isNull("sourcePan") ? JSONObject.NULL : "SOURCE_RECIPE")
                        .put("targetPan", ORIGIN));
            }
            return result;
        } catch (JSONException e) {
            throw new IllegalStateException("TARGET_PAN_JSON_BUILD_FAILED", e);
        }
    }

    private static JSONObject evidence(String semantic, String origin, String rawInput,
                                       String span, int start, int end, String locator)
            throws JSONException {
        return new JSONObject().put("semanticField", semantic).put("origin", origin)
                .put("inputLocator", locator).put("rawInput", rawInput).put("sourceSpan", span)
                .put("spanStart", start).put("spanEnd", end);
    }

    private static String locator(String root, String field) {
        return "userInput.text".equals(root) ? root : root + "." + field;
    }

    private static String required(String value, String code) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(code);
        return value.trim();
    }

    private static void positiveDecimal(String value, String code) {
        if (!DECIMAL.matcher(value).matches()) throw new IllegalArgumentException(code);
        if (new BigDecimal(value).signum() <= 0) throw new IllegalArgumentException(code);
    }
}
