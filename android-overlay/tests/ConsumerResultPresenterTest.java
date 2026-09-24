package com.panclub.shareprobe;

import java.util.Locale;
import org.json.JSONArray;
import org.json.JSONObject;

/** Consumer-only UI states: Korean copy, no JSON null text, and no contract identifiers. */
public final class ConsumerResultPresenterTest {
    private static void check(boolean yes, String label) { if (!yes) throw new AssertionError(label); }

    public static void main(String[] args) throws Exception {
        JSONObject read = TargetPanContractTest.replaySourceFixture();

        JSONObject initial = new PreBakeSession(read).view();
        check("아직 선택되지 않았어요".equals(initial.getString("targetPan")),
                "initial target has natural Korean copy");
        assertConsumerOnly(initial, "initial target");

        JSONObject missing = withoutSourcePan(read);
        PreBakeSession missingSession = new PreBakeSession(missing);
        JSONObject missingView = missingSession.view();
        check("NEED_INFO".equals(missingView.getString("state")) &&
                        "RECOVER".equals(missingSession.evaluationResult().getJSONObject("sourcePanRecovery")
                                .getString("decision")) &&
                        "원본 팬 정보를 찾지 못했어요.".equals(missingView.getString("headline")) &&
                        contains(missingView.getJSONArray("reasons"), "이 레시피에 사용한 팬 크기를 알고 있나요?") &&
                        contains(missingView.getJSONArray("actions"), "직접 입력") &&
                        contains(missingView.getJSONArray("actions"), "모르겠어요"),
                "missing source enters RECOVER and asks the user");
        assertConsumerOnly(missingView, "missing source");

        JSONObject ambiguous = withoutSourcePan(read).put("sourcePanResolution", "AMBIGUOUS");
        JSONObject ambiguousView = new PreBakeSession(ambiguous).view();
        check("NEED_INFO".equals(ambiguousView.getString("state")) &&
                        ambiguousView.getString("headline").contains("여러 가지") &&
                        contains(ambiguousView.getJSONArray("actions"), "직접 입력") &&
                        contains(ambiguousView.getJSONArray("actions"), "모르겠어요"),
                "ambiguous source enters the same recovery choice");
        assertConsumerOnly(ambiguousView, "ambiguous source");

        PreBakeSession recovered = new PreBakeSession(missing);
        JSONObject recoveredView = recovered.provideSourcePan("15", "2", "5");
        JSONObject recoveredResult = recovered.evaluationResult();
        check("BLOCKED".equals(recoveredView.getString("state")) &&
                        "입력한 원본 팬".equals(recoveredView.getString("sourcePanLabel")) &&
                        recoveredView.getString("sourcePan").contains("레시피 원문에서 확인되지 않음") &&
                        recoveredResult.getJSONObject("workingRecipe").isNull("sourcePan") &&
                        SourcePanRecovery.ORIGIN.equals(recoveredResult.getJSONObject("sourcePanRecovery")
                                .getJSONObject("candidate").getString("origin")),
                "user source remains visibly separate and never becomes recipe evidence");
        check("BLOCKED".equals(new PreBakeSession(missing).sourcePanUnknown().getString("state")),
                "unresolved source blocks");
        assertConsumerOnly(recoveredView, "recovered source quarantine");

        checkReason(new PreBakeSession(read).evaluateTarget("", "2", ""), "지름을 cm로 입력해 주세요.",
                "missing target diameter");
        checkReason(new PreBakeSession(read).evaluateTarget("12", "", ""), "팬 개수를 입력해 주세요.",
                "missing target count");
        checkReason(ConsumerResultPresenter.present(read, null, null, "TARGET_PAN_INPUT_UNSUPPORTED"),
                "Rev1에서는 원형 팬과 명시적인 cm 지름만 지원해요.", "unsupported non-round target");
        checkReason(new PreBakeSession(read).evaluateTarget("2호", "2", ""),
                "호수 대신 실제 지름을 cm로 입력해 주세요.", "Korean size label");

        JSONObject countView = new PreBakeSession(read).evaluateTarget("12", "1", "");
        check("BLOCKED".equals(countView.getString("state")) &&
                        contains(countView.getJSONArray("reasons"), "팬 개수 변경에 필요한 공정 보존"),
                "pan count change has plain Korean block reason");
        assertConsumerOnly(countView, "pan count change");

        JSONObject targetResult = TargetPanContract.attachFields(read, "15", "2", "")
                .put("inputRevision", read.getJSONObject("workingRecipe").getString("sourceBodySha256"))
                .put("workingRecipeRevision", read.getJSONObject("workingRecipe").getString("sourceBodySha256"));
        JSONObject mismatchGeometry = new JSONObject().put("decision", "BLOCK")
                .put("blocker", "PAN_DIAMETER_SEMANTIC_CLASS_UNVERIFIED");
        JSONObject mismatchView = ConsumerResultPresenter.present(targetResult, mismatchGeometry,
                FitEligibilityGate.evaluate(targetResult), null);
        check("BLOCKED".equals(mismatchView.getString("state")) &&
                        contains(mismatchView.getJSONArray("reasons"),
                                "원본 팬과 목표 팬의 지름 기준(명목·내경 등) 일치 여부"),
                "measurement-class mismatch has plain Korean block reason");
        assertConsumerOnly(mismatchView, "measurement-class mismatch");

        System.out.println("PASS 9 consumer UI states: null-safe target, source RECOVER/BLOCK, Korean input and geometry reasons");
    }

    private static JSONObject withoutSourcePan(JSONObject read) throws Exception {
        JSONObject copy = new JSONObject(read.toString());
        copy.getJSONObject("workingRecipe").put("sourcePan", JSONObject.NULL);
        copy.getJSONObject("normalizedInput").put("sourcePan", JSONObject.NULL);
        return copy;
    }

    private static void checkReason(JSONObject view, String reason, String label) throws Exception {
        check("NEED_INFO".equals(view.getString("state")) && contains(view.getJSONArray("reasons"), reason), label);
        assertConsumerOnly(view, label);
    }

    private static boolean contains(JSONArray array, String value) {
        for (int i = 0; i < array.length(); i++) if (value.equals(array.optString(i))) return true;
        return false;
    }

    private static void assertConsumerOnly(JSONObject view, String label) {
        String text = view.toString();
        check(!text.toLowerCase(Locale.ROOT).contains("null"), label + " never renders literal null");
        check(!text.contains("TARGET_PAN_") && !text.contains("SOURCE_PAN_") &&
                        !text.contains("PAN_DIAMETER_") && !text.contains("BF-F"),
                label + " does not expose internal contract identifiers");
        check(!view.getBoolean("fitApplied") && !view.getBoolean("productionReady"),
                label + " preserves safety flags");
    }
}
