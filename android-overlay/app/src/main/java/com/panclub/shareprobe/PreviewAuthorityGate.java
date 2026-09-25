package com.panclub.shareprobe;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;

/** Consumer-visibility authority, deliberately separate from technical eligibility. */
public final class PreviewAuthorityGate {
    private static final String EXCEPTION = "BF-F02_TEST_WAIT_CONSUMER_PREVIEW_EXCEPTION_v0.1";
    private static final Map<String, String> KOREAN_NAMES = names();

    private PreviewAuthorityGate() {}

    public static JSONObject evaluate(JSONObject result, JSONObject technical, JSONObject authorityFixture) {
        try { return evaluateChecked(result, technical, authorityFixture); }
        catch (Exception e) { return denied("PREVIEW_AUTHORITY_EVIDENCE_INVALID"); }
    }

    private static JSONObject evaluateChecked(JSONObject result, JSONObject technical,
                                               JSONObject authorityFixture) throws JSONException {
        if (technical == null || !"PASS".equals(technical.optString("decision")))
            return denied("TECHNICAL_ELIGIBILITY_REQUIRED");
        if (authorityFixture == null) return denied("PREVIEW_AUTHORITY_REQUIRED");
        JSONObject approved = ApprovedPreviewFixture.copyVerified(authorityFixture);
        JSONObject working = result.getJSONObject("workingRecipe");
        JSONObject target = working.getJSONObject("targetPan");
        if (!ApprovedPreviewFixture.SOURCE_SHA.equals(working.optString("sourceBodySha256")))
            return denied("PREVIEW_AUTHORITY_REQUIRED");
        if (!"round".equals(target.optString("shape")) || target.optInt("count", -1) != 2 ||
                new BigDecimal(target.optString("diameterValue", "0")).compareTo(new BigDecimal("12")) != 0)
            return denied("PREVIEW_TARGET_AUTHORITY_REQUIRED");

        JSONArray candidates = technical.getJSONArray("ingredientCandidates");
        JSONArray approvedRows = approved.getJSONArray("ingredientResults");
        if (candidates.length() != approvedRows.length()) return denied("PREVIEW_AUTHORITY_REQUIRED");
        JSONArray authorized = new JSONArray();
        for (int i = 0; i < approvedRows.length(); i++) {
            JSONObject candidate = candidates.getJSONObject(i);
            JSONObject expected = approvedRows.getJSONObject(i);
            String identity = expected.getString("ingredientIdentity");
            if (!identity.equals(candidate.getString("name")) ||
                    new BigDecimal(expected.getString("sourceGrams")).compareTo(
                            new BigDecimal(candidate.getString("sourceGrams"))) != 0)
                return denied("PREVIEW_AUTHORITY_REQUIRED");
            JSONObject evidence = candidate.optJSONObject("sourceEvidence");
            if (evidence == null || !"VERIFIED_IMMUTABLE_SOURCE".equals(evidence.optString("origin")) ||
                    !ApprovedPreviewFixture.SOURCE_SHA.equals(evidence.optString("expectedBodySha256")))
                return denied("PREVIEW_AUTHORITY_REQUIRED");
            authorized.put(new JSONObject().put("identity", identity)
                    .put("consumerNameKo", KOREAN_NAMES.get(identity))
                    .put("sourceGrams", expected.getString("sourceGrams"))
                    .put("approvedUnderlyingDecimal12cm",
                            expected.getString("decimalPreviewNonAuthoritative_g"))
                    .put("approvedExactRational12cm", new JSONObject(
                            expected.getJSONObject("exactExperimentalTarget_g").toString())));
        }
        return new JSONObject().put("decision", "ALLOW")
                .put("authority", EXCEPTION).put("scope", "FIXTURE_AND_TARGET_ONLY")
                .put("authorizedIngredients", authorized)
                .put("ruleStatus", "TEST_WAIT").put("fitApplied", false)
                .put("productionReady", false).put("executionAuthorized", false);
    }

    private static JSONObject denied(String reason) {
        JSONObject out = new JSONObject();
        try { return out.put("decision", "AUTHORITY_REQUIRED").put("reason", reason)
                .put("authority", JSONObject.NULL).put("authorizedIngredients", new JSONArray())
                .put("ruleStatus", "TEST_WAIT").put("fitApplied", false)
                .put("productionReady", false).put("executionAuthorized", false); }
        catch (JSONException ignored) { return out; }
    }

    private static Map<String, String> names() {
        Map<String, String> names = new LinkedHashMap<>();
        names.put("apple cider vinegar", "사과식초");
        names.put("plant milk", "식물성 우유");
        names.put("all purpose flour", "밀가루");
        names.put("granulated sugar", "설탕");
        names.put("baking powder", "베이킹파우더");
        names.put("baking soda", "베이킹소다");
        names.put("salt", "소금");
        names.put("oil", "오일");
        names.put("vanilla extract", "바닐라");
        return names;
    }
}
