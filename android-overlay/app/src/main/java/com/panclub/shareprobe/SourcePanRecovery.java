package com.panclub.shareprobe;

import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.regex.Pattern;

/** Preserves user-reported source-pan facts separately; never promotes them to recipe evidence. */
public final class SourcePanRecovery {
    public static final String ORIGIN = "USER_PROVIDED_SOURCE_PAN";
    private static final Pattern DECIMAL = Pattern.compile("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?");
    private static final Pattern COUNT = Pattern.compile("[1-9][0-9]*");

    private SourcePanRecovery() {}

    public static JSONObject request(JSONObject readResult) {
        return withRecord(readResult, record("RECOVER", JSONObject.NULL));
    }

    public static JSONObject provide(JSONObject readResult, String diameterCm,
                                     String countText, String heightCm) {
        String diameter = required(diameterCm, "SOURCE_PAN_DIAMETER_MISSING");
        String countRaw = required(countText, "SOURCE_PAN_COUNT_MISSING");
        String height = heightCm == null ? "" : heightCm.trim();
        if (diameter.contains("호") || "미니".equals(diameter))
            throw new IllegalArgumentException("SOURCE_PAN_DIAMETER_CM_REQUIRED");
        positive(diameter, "SOURCE_PAN_DIAMETER_INVALID");
        if (!COUNT.matcher(countRaw).matches())
            throw new IllegalArgumentException("SOURCE_PAN_COUNT_INVALID");
        if (!height.isEmpty()) positive(height, "SOURCE_PAN_HEIGHT_INVALID");
        final int count;
        try { count = Integer.parseInt(countRaw); }
        catch (NumberFormatException e) {
            throw new IllegalArgumentException("SOURCE_PAN_COUNT_UNREPRESENTABLE", e);
        }
        try {
            JSONObject candidate = new JSONObject()
                    .put("origin", ORIGIN)
                    .put("shape", "round")
                    .put("diameter", diameter + " cm")
                    .put("diameterUnit", "cm")
                    .put("measurementClass", "USER_STATED_UNCLASSIFIED_DIAMETER")
                    .put("count", count)
                    .put("height", height.isEmpty() ? JSONObject.NULL : height + " cm")
                    .put("heightRole", height.isEmpty() ? JSONObject.NULL : "OPTIONAL_METADATA_ONLY")
                    .put("fitAuthority", false)
                    .put("recipeEvidenceAuthority", false)
                    .put("fitApplied", false);
            return withRecord(readResult, record("BLOCK_UNAUTHORIZED_FOR_FIT", candidate));
        } catch (JSONException e) {
            throw new IllegalStateException("SOURCE_PAN_RECOVERY_JSON_FAILED", e);
        }
    }

    public static JSONObject unknown(JSONObject readResult) {
        return withRecord(readResult, record("BLOCK_SOURCE_PAN_UNKNOWN", JSONObject.NULL));
    }

    private static JSONObject record(String decision, Object candidate) {
        try {
            return new JSONObject().put("decision", decision).put("origin", ORIGIN)
                    .put("candidate", candidate)
                    .put("promotedToSourceRecipe", false)
                    .put("fitAuthority", false)
                    .put("fitApplied", false)
                    .put("productionReady", false);
        } catch (JSONException e) {
            throw new IllegalStateException("SOURCE_PAN_RECOVERY_JSON_FAILED", e);
        }
    }

    private static JSONObject withRecord(JSONObject readResult, JSONObject recovery) {
        try {
            JSONObject out = readResult == null ? new JSONObject() : new JSONObject(readResult.toString());
            return out.put("sourcePanRecovery", recovery);
        } catch (JSONException e) {
            throw new IllegalStateException("SOURCE_PAN_RECOVERY_JSON_FAILED", e);
        }
    }

    private static String required(String value, String code) {
        if (value == null || value.trim().isEmpty()) throw new IllegalArgumentException(code);
        return value.trim();
    }

    private static void positive(String value, String code) {
        if (!DECIMAL.matcher(value).matches() || new BigDecimal(value).signum() <= 0)
            throw new IllegalArgumentException(code);
    }
}
