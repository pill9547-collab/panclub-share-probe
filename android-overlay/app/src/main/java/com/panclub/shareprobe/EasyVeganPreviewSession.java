package com.panclub.shareprobe;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;

/** Fixture-only consumer projection authorized by BF-F02 TEST_WAIT Consumer Preview Exception v0.1. */
public final class EasyVeganPreviewSession {
    private static final String SOURCE_URL =
            "https://www.thecuriouschickpea.com/easy-vegan-vanilla-cake/";
    private static final String SOURCE_SHA =
            "b6c50e9f5c3b82cbde644049b2967b4b06471d59090d1fb80b8bdaed861e39d7";
    private static final String TITLE = "Easy Vegan Vanilla Cake";
    private static final String PUBLISHER = "The Curious Chickpea";
    private static final String EXCEPTION = "BF-F02_TEST_WAIT_CONSUMER_PREVIEW_EXCEPTION_v0.1";
    private static final Map<String, String> KOREAN_NAMES = names();

    private final String fixtureSnapshot;
    private JSONObject currentDiagnostic;
    private JSONObject currentView;

    private EasyVeganPreviewSession(JSONObject fixture) {
        verifyFixture(fixture);
        fixtureSnapshot = fixture.toString();
        currentView = emptyView("NEED_INFO", "내 팬 정보를 입력해 주세요.", "아직 선택되지 않았어요");
        currentDiagnostic = baseDiagnostic();
    }

    public static EasyVeganPreviewSession fromAsset(byte[] bytes) {
        if (bytes == null) throw new IllegalArgumentException("PREVIEW_FIXTURE_REQUIRED");
        try { return new EasyVeganPreviewSession(new JSONObject(new String(bytes, StandardCharsets.UTF_8))); }
        catch (JSONException e) { throw new IllegalArgumentException("PREVIEW_FIXTURE_MALFORMED", e); }
    }

    public JSONObject evaluateTarget(String diameterCm, String countText, String heightCm) {
        final JSONObject target;
        try { target = TargetPanContract.parseFields(diameterCm, countText, heightCm); }
        catch (IllegalArgumentException e) {
            currentDiagnostic = baseDiagnostic();
            currentView = emptyView("NEED_INFO", ConsumerResultPresenter.inputProblemKorean(e.getMessage()),
                    targetText(diameterCm, countText));
            return view();
        }
        try {
            currentDiagnostic = baseDiagnostic().put("targetPan", new JSONObject(target.toString()));
            String displayTarget = "원형 " + target.getString("diameter") + " × " + target.getInt("count") + "개";
            if (target.getInt("count") != 2) {
                currentDiagnostic.put("decision", "BLOCK_PAN_COUNT_CHANGE");
                currentView = emptyView("BLOCKED", "팬 개수를 바꾸는 미리보기는 아직 지원하지 않아요.", displayTarget);
                return view();
            }
            if (!"12".equals(new BigDecimal(target.getString("diameterValue")).stripTrailingZeros().toPlainString())) {
                currentDiagnostic.put("decision", "BLOCK_OUTSIDE_APPROVED_PREVIEW_TARGET");
                currentView = emptyView("BLOCKED", "검증 전 재료 미리보기는 현재 12 cm × 2개만 제공해요.", displayTarget);
                return view();
            }

            JSONObject fixture = fixture();
            JSONObject geometry = fixture.getJSONObject("geometry");
            BigDecimal sourceCm = new BigDecimal(geometry.getJSONObject("lengthNormalization")
                    .getString("sourceNormalizedCm"));
            BigDecimal targetCm = new BigDecimal(target.getString("diameterValue"));
            BigDecimal factor = GeometryFactorStage.factorFromCentimeters(sourceCm, targetCm);
            JSONArray sourceRows = fixture.getJSONArray("ingredientResults");
            JSONArray consumerRows = new JSONArray();
            JSONArray exactRows = new JSONArray();
            for (int i = 0; i < sourceRows.length(); i++) {
                JSONObject source = sourceRows.getJSONObject(i);
                verifyRow(source, i);
                BigDecimal sourceMass = new BigDecimal(source.getString("sourceGrams"));
                BigDecimal recomputed = BatterMassSimulator.experimentalTargetMass(sourceMass, factor);
                BigDecimal underlying = new BigDecimal(source.getString("decimalPreviewNonAuthoritative_g"));
                require(presentation(recomputed).equals(presentation(underlying)),
                        "EXISTING_BF_F02_PRESENTATION_MISMATCH");
                verifyExactFraction(source, sourceMass);
                String identity = source.getString("ingredientIdentity");
                consumerRows.put(new JSONObject()
                        .put("name", KOREAN_NAMES.get(identity))
                        .put("source", sourceMass.toPlainString() + " g")
                        .put("preview", presentation(underlying) + " g"));
                exactRows.put(new JSONObject()
                        .put("ingredientIdentity", identity)
                        .put("sourceMass_g", sourceMass.toPlainString())
                        .put("underlyingCalculatedDecimal_g", underlying.toPlainString())
                        .put("exactCalculatedRational_g", new JSONObject(
                                source.getJSONObject("exactExperimentalTarget_g").toString()))
                        .put("presentation_g", presentation(underlying)));
            }
            currentDiagnostic.put("decision", "TEST_WAIT_PREVIEW")
                    .put("geometryFactor", factor.toPlainString())
                    .put("ingredientResults", exactRows)
                    .put("sourceFixtureUnchanged", fixtureSnapshot.equals(fixture.toString()))
                    .put("targetBakeTime", JSONObject.NULL);
            currentView = emptyView("PREVIEW", "실제 베이크 검증 전", displayTarget)
                    .put("ingredients", consumerRows)
                    .put("bakeHeading", "굽기")
                    .put("bakeGuidance", new JSONArray()
                            .put("원본 레시피 온도 안내: 350°F")
                            .put("원본 6 inch 팬 안내: 31–33분")
                            .put("팬 크기가 달라졌으므로 새 굽기 시간은 계산하지 않았어요. 원본의 온도와 익음 상태 기준을 참고해 실제 상태를 확인해 주세요."));
            return view();
        } catch (Exception e) {
            currentDiagnostic = baseDiagnostic();
            try { currentDiagnostic.put("decision", "BLOCK").put("blocker", e.getMessage()); }
            catch (JSONException ignored) { }
            currentView = emptyView("BLOCKED", "검증 전 미리보기를 안전하게 만들지 못했어요.", "확인이 필요해요");
            return view();
        }
    }

    public JSONObject view() { return copy(currentView); }
    public JSONObject diagnostics() { return copy(currentDiagnostic); }
    public JSONObject sourceFixture() { return fixture(); }

    private JSONObject emptyView(String state, String message, String targetPan) {
        try {
            return new JSONObject().put("screenTitle", "내 팬에 맞춘 레시피")
                    .put("recipeTitle", TITLE).put("publisher", PUBLISHER)
                    .put("sourcePan", "원형 6 inch × 2개").put("targetPan", targetPan)
                    .put("state", state).put("validationStatus", message)
                    .put("ingredients", new JSONArray()).put("fitApplied", false)
                    .put("productionReady", false).put("executionAuthorized", false);
        } catch (JSONException e) { return new JSONObject(); }
    }

    private JSONObject baseDiagnostic() {
        try {
            return new JSONObject().put("mode", "FIXTURE_ONLY_CONSUMER_PREVIEW")
                    .put("exception", EXCEPTION).put("fixtureSourceUrl", SOURCE_URL)
                    .put("fixtureSourceBodySha256", SOURCE_SHA).put("ruleId", "BF-F02")
                    .put("ruleStatus", "TEST_WAIT").put("fitApplied", false)
                    .put("productionReady", false).put("executionAuthorized", false)
                    .put("presentationRounding", "MAX_2_DECIMAL_HALF_UP_TRAILING_ZEROS_REMOVED")
                    .put("targetBakeTime", JSONObject.NULL).put("ingredientResults", new JSONArray());
        } catch (JSONException e) { return new JSONObject(); }
    }

    private static void verifyFixture(JSONObject fixture) {
        try {
            require("TEST_WAIT_SOURCE_VERIFIED".equals(fixture.getString("status")) &&
                            "TEST_WAIT".equals(fixture.getString("ruleStatus")) &&
                            "SOURCE_FIXTURE_INTEGRATION_PASS".equals(fixture.getString("sourceFixtureStatus")) &&
                            SOURCE_SHA.equals(fixture.getString("sourceBodySha256Expected")) &&
                            fixture.getInt("sourceBatterRowCount") == 9 &&
                            fixture.getInt("experimentalTransformCount") == 9 &&
                            !fixture.getBoolean("fitApplied") && !fixture.getBoolean("productionReady") &&
                            !fixture.getBoolean("userVisible") && !fixture.getBoolean("frostingScalingApplied") &&
                            fixture.getInt("volumeToMassConversions") == 0 &&
                            fixture.getJSONArray("orphanNumbers").length() == 0,
                    "EASY_VEGAN_FIXTURE_IDENTITY_MISMATCH");
            JSONObject geometry = fixture.getJSONObject("geometry");
            require("12 cm".equals(geometry.getJSONObject("targetDiameter").getString("raw")) &&
                            "6 inch".equals(geometry.getJSONObject("sourceDiameter").getString("raw")) &&
                            geometry.getInt("sourcePanCount") == 2 && geometry.getInt("targetPanCount") == 2 &&
                            geometry.getBoolean("processPreserved") && !geometry.getBoolean("referenceHeightUsed"),
                    "EASY_VEGAN_GEOMETRY_SCOPE_MISMATCH");
            JSONObject process = fixture.getJSONObject("process");
            require(process.getInt("sourcePanCount") == 2 && process.getInt("targetPanCount") == 2 &&
                            process.isNull("targetBakeTime"), "EASY_VEGAN_PROCESS_SCOPE_MISMATCH");
            require(fixture.getJSONArray("ingredientResults").length() == 9,
                    "EASY_VEGAN_BATTER_ROWS_MISMATCH");
        } catch (JSONException e) { throw new IllegalArgumentException("PREVIEW_FIXTURE_MALFORMED", e); }
    }

    private static void verifyRow(JSONObject row, int index) throws JSONException {
        String identity = row.getString("ingredientIdentity");
        JSONObject evidence = row.getJSONObject("sourceEvidence");
        require(row.getInt("index") == index && "BATTER".equals(row.getString("component")) &&
                        KOREAN_NAMES.containsKey(identity) && SOURCE_URL.equals(evidence.getString("sourceUrl")) &&
                        SOURCE_SHA.equals(evidence.getString("expectedBodySha256")) &&
                        "VERIFIED_IMMUTABLE_SOURCE".equals(evidence.getString("origin")) &&
                        row.isNull("executionTarget_g") && row.isNull("scaleResolution_g") &&
                        "TEST_WAIT".equals(row.getJSONObject("derivation").getString("ruleStatus")),
                "PREVIEW_ROW_NOT_AUTHORIZED");
    }

    private static void verifyExactFraction(JSONObject row, BigDecimal sourceMass) throws JSONException {
        JSONObject fraction = row.getJSONObject("exactExperimentalTarget_g");
        BigInteger numerator = new BigInteger(fraction.getString("numerator"));
        BigInteger denominator = new BigInteger(fraction.getString("denominator"));
        BigInteger expectedN = sourceMass.toBigIntegerExact().multiply(new BigInteger("10000"));
        BigInteger expectedD = new BigInteger("16129");
        BigInteger gcd = expectedN.gcd(expectedD);
        require(numerator.equals(expectedN.divide(gcd)) && denominator.equals(expectedD.divide(gcd)),
                "EXISTING_BF_F02_RATIONAL_MISMATCH");
    }

    private static String presentation(BigDecimal value) {
        return value.setScale(2, RoundingMode.HALF_UP).stripTrailingZeros().toPlainString();
    }

    private static String targetText(String diameter, String count) {
        String d = diameter == null || diameter.trim().isEmpty() ? "?" : diameter.trim() + " cm";
        String c = count == null || count.trim().isEmpty() ? "?" : count.trim() + "개";
        return "원형 " + d + " × " + c;
    }

    private JSONObject fixture() {
        try { return new JSONObject(fixtureSnapshot); }
        catch (JSONException e) { throw new IllegalStateException("FIXTURE_SNAPSHOT_FAILED", e); }
    }

    private static JSONObject copy(JSONObject object) {
        try { return new JSONObject(object.toString()); }
        catch (JSONException e) { return new JSONObject(); }
    }

    private static void require(boolean condition, String code) {
        if (!condition) throw new IllegalArgumentException(code);
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
