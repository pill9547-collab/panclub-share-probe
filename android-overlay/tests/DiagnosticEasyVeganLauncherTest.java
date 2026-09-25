package com.panclub.shareprobe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Physical-phone debug entry proof without creating a second preview implementation. */
public final class DiagnosticEasyVeganLauncherTest {
    private static void check(boolean yes, String label) { if (!yes) throw new AssertionError(label); }

    public static void main(String[] args) throws Exception {
        check("Easy Vegan 검증 예시 열기".equals(DiagnosticEasyVeganLauncher.BUTTON_LABEL),
                "Korean diagnostic button label");
        check(!DiagnosticEasyVeganLauncher.visible("CONSUMER", true) &&
                        !DiagnosticEasyVeganLauncher.visible("INTERNAL_DIAGNOSTIC", false) &&
                        DiagnosticEasyVeganLauncher.visible("INTERNAL_DIAGNOSTIC", true),
                "launcher is visible only inside expanded INTERNAL_DIAGNOSTIC UI");

        JSONObject authority = new JSONObject(new String(Files.readAllBytes(Path.of(
                "app/src/main/assets/easy_vegan_bf_f02_test_wait.json")), StandardCharsets.UTF_8));
        PreBakeSession session = DiagnosticEasyVeganLauncher.launch(authority);
        JSONObject view = session.view();
        check("READY_PREVIEW".equals(view.getString("state")) &&
                        "PASS".equals(session.previewEligibilityResult().getString("decision")) &&
                        "ALLOW".equals(session.previewAuthorityResult().getString("decision")),
                "launcher reaches normal generalized eligibility and authority pipeline");
        String[][] expected = {
                {"사과식초", "6.2 g"}, {"식물성 우유", "167.4 g"},
                {"밀가루", "130.2 g"}, {"설탕", "105.4 g"},
                {"베이킹파우더", "3.72 g"}, {"베이킹소다", "1.24 g"},
                {"소금", "3.1 g"}, {"오일", "50.84 g"}, {"바닐라", "9.92 g"}
        };
        JSONArray rows = view.getJSONArray("ingredients");
        check(rows.length() == expected.length, "exact nine displayed values");
        for (int i = 0; i < expected.length; i++) {
            JSONObject row = rows.getJSONObject(i);
            check(expected[i][0].equals(row.getString("name")) &&
                    expected[i][1].equals(row.getString("adjusted")), "display row " + i);
        }
        JSONObject diagnostics = session.diagnostics();
        check("TEST_WAIT".equals(diagnostics.getJSONObject("bfF02").getString("ruleStatus")) &&
                        !diagnostics.getBoolean("fitApplied") && !diagnostics.getBoolean("productionReady") &&
                        !session.previewAuthorityResult().getBoolean("executionAuthorized") &&
                        session.previewDiagnostic().isNull("targetBakeTime"),
                "TEST_WAIT remains non-production with no scaled bake time");

        PreBakeSession thriving = new PreBakeSession(TargetPanContractTest.replaySourceFixture(), authority);
        JSONObject thrivingView = thriving.evaluateTarget("12", "2", "");
        check("BLOCKED".equals(thrivingView.getString("state")) &&
                        thrivingView.getJSONArray("ingredients").length() == 0,
                "ThrivingNest remains blocked despite diagnostic launcher availability");
        System.out.println("PASS diagnostic launcher: hidden from consumer, generalized gates used, exact 9-row preview");
    }
}
