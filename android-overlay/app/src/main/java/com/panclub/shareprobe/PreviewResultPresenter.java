package com.panclub.shareprobe;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.LinkedHashSet;
import java.util.Set;

/** Korean consumer projection plus separate exact diagnostics for generalized preview evaluation. */
public final class PreviewResultPresenter {
    private PreviewResultPresenter() {}

    public static JSONObject project(JSONObject result, JSONObject geometry,
                                     JSONObject technical, JSONObject authority) {
        try {
            JSONObject working = result.getJSONObject("workingRecipe");
            JSONObject base = baseView(working);
            JSONObject diagnostic = new JSONObject()
                    .put("previewTechnicalEligibility", copy(technical))
                    .put("previewAuthority", copy(authority))
                    .put("ruleId", "BF-F02").put("ruleStatus", "TEST_WAIT")
                    .put("fitApplied", false).put("productionReady", false)
                    .put("executionAuthorized", false).put("targetBakeTime", JSONObject.NULL)
                    .put("ingredientResults", new JSONArray());
            if (technical == null || !"PASS".equals(technical.optString("decision"))) {
                String decision = technical == null ? "BLOCK" : technical.optString("decision", "BLOCK");
                base.put("state", "NEED_INFO".equals(decision) ? "NEED_INFO" : "BLOCKED")
                        .put("headline", "NEED_INFO".equals(decision)
                                ? "팬 정보를 더 확인해 주세요."
                                : "이 레시피는 아직 안전하게 미리보기할 수 없어요.")
                        .put("reasons", technicalReasons(technical));
                return new JSONObject().put("view", base).put("diagnostic", diagnostic);
            }
            if (authority == null || !"ALLOW".equals(authority.optString("decision"))) {
                base.put("state", "AUTHORITY_REQUIRED")
                        .put("headline", "이 레시피는 계산 조건은 맞지만 아직 검증 전 미리보기가 승인되지 않았어요.")
                        .put("reasons", new JSONArray().put("승인된 레시피와 목표 팬 범위를 확인해 주세요."));
                return new JSONObject().put("view", base).put("diagnostic", diagnostic);
            }
            if (geometry == null || !"GEOMETRY_PASS".equals(geometry.optString("decision")))
                throw new IllegalArgumentException("GEOMETRY_PASS_REQUIRED");

            BigDecimal factor = new BigDecimal(geometry.getString("geometryFactor"));
            JSONArray approved = authority.getJSONArray("authorizedIngredients");
            JSONArray consumerRows = new JSONArray();
            JSONArray exactRows = new JSONArray();
            for (int i = 0; i < approved.length(); i++) {
                JSONObject row = approved.getJSONObject(i);
                BigDecimal source = new BigDecimal(row.getString("sourceGrams"));
                BigDecimal recomputed = BatterMassSimulator.experimentalTargetMass(source, factor);
                BigDecimal approvedUnderlying = new BigDecimal(row.getString("approvedUnderlyingDecimal12cm"));
                if (!display(recomputed).equals(display(approvedUnderlying)))
                    throw new IllegalArgumentException("APPROVED_UNDERLYING_VALUE_MISMATCH");
                consumerRows.put(new JSONObject()
                        .put("name", row.getString("consumerNameKo"))
                        .put("adjusted", display(approvedUnderlying) + " g")
                        .put("original", "원본 " + source.toPlainString() + " g"));
                exactRows.put(new JSONObject()
                        .put("ingredientIdentity", row.getString("identity"))
                        .put("sourceMass_g", source.toPlainString())
                        .put("underlyingCalculatedDecimal_g", approvedUnderlying.toPlainString())
                        .put("exactCalculatedRational_g", new JSONObject(
                                row.getJSONObject("approvedExactRational12cm").toString()))
                        .put("presentation_g", display(approvedUnderlying)));
            }
            JSONObject policy = working.getJSONObject("previewPolicy");
            base.put("state", "READY_PREVIEW").put("screenTitle", "내 팬에 맞춘 레시피")
                    .put("headline", "실제 베이크 검증 전")
                    .put("validationStatus", "실제 베이크 검증 전")
                    .put("ingredients", consumerRows)
                    .put("bakeHeading", "굽기")
                    .put("bakeGuidance", new JSONArray(policy.getJSONArray("sourceBakeGuidance").toString()));
            diagnostic.put("geometryFactor", factor.toPlainString())
                    .put("ingredientResults", exactRows).put("sourceRecipeUnchanged", true);
            return new JSONObject().put("view", base).put("diagnostic", diagnostic);
        } catch (Exception e) {
            try {
                JSONObject view = new JSONObject().put("state", "BLOCKED")
                        .put("headline", "검증 전 미리보기를 안전하게 표시할 수 없어요.")
                        .put("reasons", new JSONArray().put("원본 값과 계산 근거를 다시 확인해 주세요."))
                        .put("ingredients", new JSONArray()).put("fitApplied", false)
                        .put("productionReady", false);
                JSONObject diagnostic = new JSONObject().put("decision", "BLOCK")
                        .put("blocker", e.getMessage()).put("ruleStatus", "TEST_WAIT")
                        .put("fitApplied", false).put("productionReady", false);
                return new JSONObject().put("view", view).put("diagnostic", diagnostic);
            } catch (JSONException ignored) { return new JSONObject(); }
        }
    }

    private static JSONObject baseView(JSONObject working) throws JSONException {
        return new JSONObject()
                .put("recipeTitle", safe(working.optString("recipeTitle"), "레시피"))
                .put("publisher", safe(working.optString("publisher"), ""))
                .put("sourcePanLabel", "원본 팬")
                .put("sourcePan", pan(working.optJSONObject("sourcePan")))
                .put("targetPan", pan(working.optJSONObject("targetPan")))
                .put("ingredients", new JSONArray()).put("reasons", new JSONArray())
                .put("fitApplied", false).put("productionReady", false);
    }

    private static JSONArray technicalReasons(JSONObject technical) throws JSONException {
        Set<String> reasons = new LinkedHashSet<>();
        JSONArray blockers = technical == null ? null : technical.optJSONArray("blockers");
        if (blockers != null) for (int i = 0; i < blockers.length(); i++) {
            String code = blockers.getJSONObject(i).optString("code");
            if (code.contains("SOURCE_PAN")) reasons.add("원본 팬 정보");
            else if (code.contains("TARGET_PAN")) reasons.add("내 팬 정보");
            else if (code.contains("COUNT") || code.contains("PROCESS")) reasons.add("팬 개수 변경에 필요한 공정 보존");
            else if (code.contains("MEASUREMENT") || code.contains("DIAMETER") || code.contains("GEOMETRY"))
                reasons.add("원본 팬과 내 팬의 지름 기준 일치 여부");
            else if (code.contains("EGG")) reasons.add("달걀 중량");
            else if (code.contains("COMPOUND")) reasons.add("복합 재료 수량");
            else if (code.contains("CUP") || code.contains("GRAM") || code.contains("INGREDIENT"))
                reasons.add("컵 등 재료 단위와 수량 근거");
            else if (code.contains("FROSTING")) reasons.add("프로스팅 양");
            else if (code.contains("BAKE_TIME")) reasons.add("굽기 시간 변경 없음");
            else if (code.contains("ORPHAN") || code.contains("PROVENANCE") || code.contains("CONVERSION"))
                reasons.add("원본 숫자와 출처 근거");
            else if (code.contains("SHAPE")) reasons.add("지원되는 원형 팬");
            else reasons.add("검증 전 미리보기에 필요한 안전 조건");
        }
        if (reasons.isEmpty()) reasons.add("검증 전 미리보기에 필요한 안전 조건");
        JSONArray out = new JSONArray();
        for (String reason : reasons) out.put(reason);
        return out;
    }

    private static String pan(JSONObject pan) {
        if (pan == null) return "아직 선택되지 않았어요";
        String diameter = pan.optString("diameter");
        int count = pan.optInt("count", 0);
        return diameter.isEmpty() || count < 1 ? "확인이 필요해요" : "원형 " + diameter + " × " + count + "개";
    }

    private static String safe(String value, String fallback) {
        if (value == null || value.trim().isEmpty() || "null".equalsIgnoreCase(value.trim())) return fallback;
        return value.trim();
    }

    private static String display(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static JSONObject copy(JSONObject value) throws JSONException {
        return value == null ? new JSONObject() : new JSONObject(value.toString());
    }
}
