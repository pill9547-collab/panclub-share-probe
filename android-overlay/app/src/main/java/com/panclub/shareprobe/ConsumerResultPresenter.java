package com.panclub.shareprobe;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.util.LinkedHashSet;
import java.util.Set;

/** Korean consumer projection. Contract IDs remain only in the separate diagnostic object. */
public final class ConsumerResultPresenter {
    private ConsumerResultPresenter() {}

    public static JSONObject present(JSONObject result, JSONObject geometry,
                                     JSONObject eligibility, String inputProblem) {
        try {
            JSONObject working = result == null ? null : result.optJSONObject("workingRecipe");
            JSONObject normalized = result == null ? null : result.optJSONObject("normalizedInput");
            JSONObject source = working == null ? null : working.optJSONObject("sourcePan");
            JSONObject target = working == null ? null : working.optJSONObject("targetPan");
            JSONObject recovery = result == null ? null : result.optJSONObject("sourcePanRecovery");
            JSONObject recoveredCandidate = recovery == null ? null : recovery.optJSONObject("candidate");
            String title = working != null ? working.optString("recipeTitle") :
                    normalized == null ? "레시피" : normalized.optString("recipeTitle", "레시피");
            JSONObject view = new JSONObject()
                    .put("recipeTitle", consumerText(title, "레시피"))
                    .put("sourcePanLabel", recoveredCandidate == null ? "원본 팬" : "입력한 원본 팬")
                    .put("sourcePan", source != null ? displayPan(source) : recoveredCandidate != null
                            ? "직접 입력: " + displayPan(recoveredCandidate) + " (레시피 원문에서 확인되지 않음)"
                            : "아직 확인되지 않았어요")
                    .put("targetPan", target == null ? "아직 선택되지 않았어요" : displayPan(target))
                    .put("actions", new JSONArray())
                    .put("fitApplied", false).put("productionReady", false);
            JSONArray reasons = new JSONArray();
            String state;
            String headline;

            if (working == null || !"WORKING_RECIPE_SOURCE_PRESERVED".equals(result.optString("status"))) {
                state = "BLOCKED";
                headline = "레시피 정보를 안전하게 읽지 못했어요.";
                reasons.put("Pinterest 원본과 레시피 출처를 다시 확인해 주세요.");
            } else if (source == null) {
                if (recovery == null || "RECOVER".equals(recovery.optString("decision"))) {
                    state = "NEED_INFO";
                    boolean ambiguous = "AMBIGUOUS".equals(result.optString("sourcePanResolution"));
                    headline = inputProblem == null
                            ? ambiguous ? "원본 팬 정보가 여러 가지라 확인이 필요해요."
                                        : "원본 팬 정보를 찾지 못했어요."
                            : "원본 팬 정보를 확인해 주세요.";
                    reasons.put(inputProblem == null
                            ? "이 레시피에 사용한 팬 크기를 알고 있나요?"
                            : inputProblemKorean(inputProblem));
                    view.put("actions", new JSONArray().put("직접 입력").put("모르겠어요"));
                } else {
                    state = "BLOCKED";
                    headline = "원본 팬 근거가 없어 안전하게 계산할 수 없어요.";
                    reasons.put(recovery.isNull("candidate")
                            ? "원본 팬 크기를 확인하지 못했어요."
                            : "입력한 원본 팬 정보는 보존했지만 레시피 원문 근거로 사용할 수 없어요.");
                }
            } else if (inputProblem != null) {
                state = "NEED_INFO";
                headline = "목표 팬 정보를 확인해 주세요.";
                reasons.put(inputProblemKorean(inputProblem));
            } else if (target == null) {
                state = "NEED_INFO";
                headline = "어떤 팬으로 만들까요?";
                reasons.put("원형 팬의 지름(cm)과 팬 개수를 입력해 주세요.");
            } else {
                Set<String> mapped = blockerReasons(eligibility);
                String geometryDecision = geometry == null ? "BLOCK" : geometry.optString("decision", "BLOCK");
                String eligibilityDecision = eligibility == null ? "BLOCK" : eligibility.optString("decision", "BLOCK");
                if ("GEOMETRY_PASS".equals(geometryDecision) && "ALLOW".equals(eligibilityDecision)) {
                    state = "READY";
                    headline = "안전한 평가 경로를 확인했어요.";
                } else if ("RECOVER".equals(eligibilityDecision) && mapped.isEmpty()) {
                    state = "NEED_INFO";
                    headline = "추가 정보가 필요해요.";
                    reasons.put("확인되지 않은 팬 또는 재료 정보를 입력해 주세요.");
                } else {
                    state = "BLOCKED";
                    headline = "이 레시피는 아직 안전하게 변환할 수 없어요.";
                    String geometryBlocker = geometry == null ? "" : geometry.optString("blocker");
                    if ("PAN_COUNT_MERGE_SPLIT_REQUIRED".equals(geometryBlocker))
                        mapped.add("팬 개수 변경에 필요한 공정 보존");
                    else if ("PAN_DIAMETER_SEMANTIC_CLASS_UNVERIFIED".equals(geometryBlocker))
                        mapped.add("원본 팬과 목표 팬의 지름 기준(명목·내경 등) 일치 여부");
                    if (mapped.isEmpty()) mapped.add("안전한 변환에 필요한 근거");
                    for (String item : mapped) reasons.put(item);
                }
            }
            return view.put("state", state).put("headline", headline).put("reasons", reasons);
        } catch (JSONException e) {
            JSONObject fail = new JSONObject();
            try {
                fail.put("state", "BLOCKED").put("headline", "결과를 안전하게 표시할 수 없어요.")
                        .put("reasons", new JSONArray().put("진단 정보 구조를 확인해 주세요."))
                        .put("fitApplied", false).put("productionReady", false);
            } catch (JSONException ignored) { }
            return fail;
        }
    }

    public static String inputProblemKorean(String code) {
        if (code == null) return "입력값을 확인해 주세요.";
        if (code.contains("DIAMETER_CM_REQUIRED")) return "호수 대신 실제 지름을 cm로 입력해 주세요.";
        if (code.contains("DIAMETER_MISSING")) return "지름을 cm로 입력해 주세요.";
        if (code.contains("DIAMETER_INVALID")) return "지름은 0보다 큰 cm 숫자여야 해요.";
        if (code.contains("COUNT_MISSING")) return "팬 개수를 입력해 주세요.";
        if (code.contains("COUNT")) return "팬 개수는 1 이상의 정수여야 해요.";
        if (code.contains("HEIGHT")) return "높이는 비워 두거나 0보다 큰 cm 숫자로 입력해 주세요.";
        if (code.contains("UNSUPPORTED")) return "Rev1에서는 원형 팬과 명시적인 cm 지름만 지원해요.";
        return "입력값을 확인해 주세요.";
    }

    private static String displayPan(JSONObject pan) {
        String diameter = consumerText(pan.optString("diameter", ""), "");
        int count = pan.optInt("count", 0);
        if (diameter.isEmpty() || count < 1) return "확인이 필요해요";
        return "원형 " + diameter + " × " + count + "개";
    }

    private static String consumerText(String value, String fallback) {
        if (value == null) return fallback;
        String trimmed = value.trim();
        return trimmed.isEmpty() || "null".equalsIgnoreCase(trimmed) ? fallback : trimmed;
    }

    private static Set<String> blockerReasons(JSONObject eligibility) throws JSONException {
        Set<String> reasons = new LinkedHashSet<>();
        JSONArray blockers = eligibility == null ? null : eligibility.optJSONArray("blockers");
        if (blockers == null) return reasons;
        for (int i = 0; i < blockers.length(); i++) {
            String code = blockers.getJSONObject(i).optString("code");
            if (code.contains("EGG")) reasons.add("달걀 중량");
            else if (code.contains("COMPOUND")) reasons.add("복합 재료 수량");
            else if (code.contains("SEPARATE_COMPONENT")) reasons.add("프로스팅·필링 양");
            else if (code.contains("INGREDIENT") || code.contains("UNIT_CONVERSION")) reasons.add("컵 등 재료 단위와 수량 근거");
            else if (code.contains("PAN_COUNT") || code.contains("PROCESS")) reasons.add("팬 개수와 공정 보존");
            else if (code.contains("SOURCE_PAN")) reasons.add("원본 팬 크기 근거");
            else if (code.contains("TARGET_PAN")) reasons.add("목표 팬 입력 근거");
            else if (code.contains("ORPHAN") || code.contains("CONFLICT") || code.contains("REVISION"))
                reasons.add("원본 레시피의 숫자·출처 일치 여부");
        }
        return reasons;
    }
}
