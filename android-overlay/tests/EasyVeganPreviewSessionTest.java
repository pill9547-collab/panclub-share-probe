package com.panclub.shareprobe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.nio.file.Files;
import java.nio.file.Path;

/** Product-owner-approved fixture-only consumer preview; production state remains closed. */
public final class EasyVeganPreviewSessionTest {
    private static void check(boolean yes, String label) { if (!yes) throw new AssertionError(label); }

    public static void main(String[] args) throws Exception {
        byte[] asset = Files.readAllBytes(Path.of("app/src/main/assets/easy_vegan_bf_f02_test_wait.json"));
        EasyVeganPreviewSession session = EasyVeganPreviewSession.fromAsset(asset);
        String sourceSnapshot = session.sourceFixture().toString();
        JSONObject view = session.evaluateTarget("12", "2", "");
        JSONObject diagnostic = session.diagnostics();

        check("PREVIEW".equals(view.getString("state")) &&
                        "실제 베이크 검증 전".equals(view.getString("validationStatus")) &&
                        "내 팬에 맞춘 레시피".equals(view.getString("screenTitle")) &&
                        "원형 6 inch × 2개".equals(view.getString("sourcePan")) &&
                        "원형 12 cm × 2개".equals(view.getString("targetPan")),
                "Korean fixture-only preview identity: " + view + " diagnostics=" + diagnostic);
        String[][] expected = {
                {"사과식초", "10 g", "6.2 g"}, {"식물성 우유", "270 g", "167.4 g"},
                {"밀가루", "210 g", "130.2 g"}, {"설탕", "170 g", "105.4 g"},
                {"베이킹파우더", "6 g", "3.72 g"}, {"베이킹소다", "2 g", "1.24 g"},
                {"소금", "5 g", "3.1 g"}, {"오일", "82 g", "50.84 g"},
                {"바닐라", "16 g", "9.92 g"}
        };
        JSONArray rows = view.getJSONArray("ingredients");
        check(rows.length() == expected.length, "nine selected batter rows only");
        for (int i = 0; i < expected.length; i++) {
            JSONObject row = rows.getJSONObject(i);
            check(expected[i][0].equals(row.getString("name")) && expected[i][1].equals(row.getString("source")) &&
                    expected[i][2].equals(row.getString("preview")), "presentation row " + i);
        }

        JSONObject frozen = new JSONObject(new String(asset, java.nio.charset.StandardCharsets.UTF_8));
        JSONArray exact = diagnostic.getJSONArray("ingredientResults");
        for (int i = 0; i < exact.length(); i++) {
            JSONObject source = frozen.getJSONArray("ingredientResults").getJSONObject(i);
            JSONObject actual = exact.getJSONObject(i);
            check(source.getString("sourceGrams").equals(actual.getString("sourceMass_g")) &&
                            new BigDecimal(source.getString("decimalPreviewNonAuthoritative_g")).compareTo(
                                    new BigDecimal(actual.getString("underlyingCalculatedDecimal_g"))) == 0 &&
                            source.getJSONObject("exactExperimentalTarget_g").similar(
                                    actual.getJSONObject("exactCalculatedRational_g")),
                    "exact BF-F02 value preserved " + i);
        }
        check("TEST_WAIT".equals(diagnostic.getString("ruleStatus")) &&
                        !diagnostic.getBoolean("fitApplied") && !diagnostic.getBoolean("productionReady") &&
                        !diagnostic.getBoolean("executionAuthorized") && diagnostic.isNull("targetBakeTime") &&
                        diagnostic.getBoolean("sourceFixtureUnchanged") && sourceSnapshot.equals(session.sourceFixture().toString()),
                "TEST_WAIT state and immutable fixture");
        String consumer = view.toString();
        check(!consumer.contains("BF-F") && !consumer.contains("geometryFactor") &&
                        !consumer.contains("contract") && !consumer.contains("provenance"),
                "consumer copy excludes internals");
        check(view.getJSONArray("bakeGuidance").getString(2).contains("새 굽기 시간은 계산하지 않았어요"),
                "no scaled bake time invented");

        JSONObject changed = session.evaluateTarget("15", "2", "");
        check("BLOCKED".equals(changed.getString("state")) && "원형 15 cm × 2개".equals(changed.getString("targetPan")) &&
                        changed.getJSONArray("ingredients").length() == 0 && sourceSnapshot.equals(session.sourceFixture().toString()),
                "target diameter is input state but preview scope stays 12 cm");
        JSONObject countChanged = session.evaluateTarget("12", "1", "");
        check("BLOCKED".equals(countChanged.getString("state")) &&
                        countChanged.getJSONArray("ingredients").length() == 0,
                "pan count change cannot enter BF-F02 preview");

        JSONObject tampered = new JSONObject(new String(asset, java.nio.charset.StandardCharsets.UTF_8));
        tampered.put("sourceBodySha256Expected", "arbitrary-recipe");
        try {
            EasyVeganPreviewSession.fromAsset(tampered.toString().getBytes(java.nio.charset.StandardCharsets.UTF_8));
            throw new AssertionError("arbitrary recipe entered preview");
        } catch (IllegalArgumentException expectedBlock) { }

        JSONObject thrivingNest = new PreBakeSession(TargetPanContractTest.replaySourceFixture())
                .evaluateTarget("12", "2", "");
        check("BLOCKED".equals(thrivingNest.getString("state")) &&
                        thrivingNest.getJSONArray("reasons").length() > 0,
                "ThrivingNest Pin 559 remains blocked outside fixture preview");
        System.out.println("PASS core-value TEST_WAIT preview: 9 exact-derived rows, fixture-only, no bake time, Pin559 blocked");
    }
}
