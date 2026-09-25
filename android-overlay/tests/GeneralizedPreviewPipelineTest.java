package com.panclub.shareprobe;

import org.json.JSONArray;
import org.json.JSONObject;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/** Generalized READ -> geometry -> technical eligibility -> authority -> presentation matrix. */
public final class GeneralizedPreviewPipelineTest {
    private static void check(boolean yes, String label) { if (!yes) throw new AssertionError(label); }

    public static void main(String[] args) throws Exception {
        JSONObject authority = new JSONObject(new String(Files.readAllBytes(Path.of(
                "app/src/main/assets/easy_vegan_bf_f02_test_wait.json")), StandardCharsets.UTF_8));
        JSONObject easyRead = ApprovedPreviewFixture.readResult(authority);
        String easySnapshot = easyRead.toString();

        PreBakeSession easy = new PreBakeSession(easyRead, authority);
        check("NEED_INFO".equals(easy.view().getString("state")), "target missing needs input");
        JSONObject ready = easy.evaluateTarget("12", "2", "");
        check("PASS".equals(easy.previewEligibilityResult().getString("decision")),
                "Easy Vegan technical eligibility passes");
        check("ALLOW".equals(easy.previewAuthorityResult().getString("decision")),
                "Easy Vegan authority passes");
        check("READY_PREVIEW".equals(ready.getString("state")) &&
                        "내 팬에 맞춘 레시피".equals(ready.getString("screenTitle")) &&
                        "실제 베이크 검증 전".equals(ready.getString("headline")),
                "authorized consumer preview state: " + ready + " diagnostics=" + easy.diagnostics());
        String[][] expected = {
                {"사과식초", "6.2 g", "원본 10 g"}, {"식물성 우유", "167.4 g", "원본 270 g"},
                {"밀가루", "130.2 g", "원본 210 g"}, {"설탕", "105.4 g", "원본 170 g"},
                {"베이킹파우더", "3.72 g", "원본 6 g"}, {"베이킹소다", "1.24 g", "원본 2 g"},
                {"소금", "3.1 g", "원본 5 g"}, {"오일", "50.84 g", "원본 82 g"},
                {"바닐라", "9.92 g", "원본 16 g"}
        };
        JSONArray rows = ready.getJSONArray("ingredients");
        check(rows.length() == expected.length, "nine batter rows");
        for (int i = 0; i < expected.length; i++) {
            JSONObject row = rows.getJSONObject(i);
            check(expected[i][0].equals(row.getString("name")) &&
                            expected[i][1].equals(row.getString("adjusted")) &&
                            expected[i][2].equals(row.getString("original")), "consumer row " + i);
        }
        JSONArray exact = easy.previewDiagnostic().getJSONArray("ingredientResults");
        JSONArray approvedRows = authority.getJSONArray("ingredientResults");
        for (int i = 0; i < exact.length(); i++)
            check(new BigDecimal(approvedRows.getJSONObject(i).getString("decimalPreviewNonAuthoritative_g"))
                            .compareTo(new BigDecimal(exact.getJSONObject(i)
                                    .getString("underlyingCalculatedDecimal_g"))) == 0,
                    "underlying BF-F02 value unchanged " + i);
        check(easySnapshot.equals(easy.readSnapshot().toString()), "Easy source READ remains immutable");
        check(!ready.toString().contains("BF-F02") && !ready.toString().contains("geometryFactor") &&
                        !ready.toString().contains("provenance"), "consumer projection hides internals");

        JSONObject thrivingRead = TargetPanContractTest.replaySourceFixture();
        PreBakeSession thriving = new PreBakeSession(thrivingRead, authority);
        JSONObject thrivingView = thriving.evaluateTarget("12", "2", "");
        check("WORKING_RECIPE_SOURCE_PRESERVED".equals(thrivingRead.getString("status")), "ThrivingNest READ passes");
        check("BLOCK".equals(thriving.previewEligibilityResult().getString("decision")) &&
                        "BLOCKED".equals(thrivingView.getString("state")) &&
                        thrivingView.getJSONArray("ingredients").length() == 0,
                "ThrivingNest Pin 559 preview remains blocked");

        JSONObject syntheticRead = withSyntheticHash(easyRead, "aaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaaa");
        syntheticRead.getJSONObject("workingRecipe").remove("previewPolicy");
        syntheticRead.getJSONObject("normalizedInput").remove("previewPolicy");
        PreBakeSession synthetic = new PreBakeSession(syntheticRead, authority);
        JSONObject syntheticView = synthetic.evaluateTarget("12", "2", "");
        check("PASS".equals(synthetic.previewEligibilityResult().getString("decision")),
                "synthetic gram-only recipe is technically eligible");
        check("AUTHORITY_REQUIRED".equals(synthetic.previewAuthorityResult().getString("decision")) &&
                        "AUTHORITY_REQUIRED".equals(syntheticView.getString("state")) &&
                        syntheticView.getJSONArray("ingredients").length() == 0 &&
                        syntheticView.getString("headline").contains("아직 검증 전 미리보기가 승인되지 않았어요"),
                "technical eligibility never grants consumer authority");

        assertIngredientBlock(authority, easyRead, "cup", "flour", false,
                "cup quantity blocks");
        assertIngredientBlock(authority, easyRead, "each", "large egg", false,
                "egg mass assumption blocks");
        assertIngredientBlock(authority, easyRead, "g", "flour", true,
                "compound quantity blocks");

        JSONObject frostingRead = new JSONObject(easyRead.toString());
        for (String branch : new String[]{"workingRecipe", "normalizedInput"}) {
            JSONObject recipe = frostingRead.getJSONObject(branch);
            recipe.getJSONObject("previewPolicy").put("frostingExcludedFromPreview", false)
                    .put("frostingScalingRequired", true);
            recipe.getJSONArray("ingredientGroups").put(new JSONObject()
                    .put("sourceHeading", "Frosting").put("component", "FROSTING")
                    .put("ingredients", new JSONArray()));
        }
        PreBakeSession frosting = new PreBakeSession(frostingRead, authority);
        frosting.evaluateTarget("12", "2", "");
        check("BLOCK".equals(frosting.previewEligibilityResult().getString("decision")),
                "frosting scaling requirement blocks");

        PreBakeSession count = new PreBakeSession(easyRead, authority);
        check("BLOCKED".equals(count.evaluateTarget("12", "1", "").getString("state")),
                "changed pan count blocks");

        JSONObject nonRoundRead = new JSONObject(easyRead.toString());
        for (String branch : new String[]{"workingRecipe", "normalizedInput"})
            nonRoundRead.getJSONObject(branch).getJSONObject("sourcePan").put("shape", "square");
        PreBakeSession nonRound = new PreBakeSession(nonRoundRead, authority);
        nonRound.evaluateTarget("12", "2", "");
        check("BLOCK".equals(nonRound.previewEligibilityResult().getString("decision")),
                "unsupported source shape blocks");

        JSONObject noSource = new JSONObject(easyRead.toString());
        noSource.getJSONObject("workingRecipe").put("sourcePan", JSONObject.NULL);
        noSource.getJSONObject("normalizedInput").put("sourcePan", JSONObject.NULL);
        check("NEED_INFO".equals(new PreBakeSession(noSource, authority).view().getString("state")),
                "missing source enters recovery");

        PreBakeSession mismatchSession = new PreBakeSession(easyRead, authority);
        mismatchSession.evaluateTarget("12", "2", "");
        JSONObject mismatchResult = mismatchSession.evaluationResult();
        for (String branch : new String[]{"workingRecipe", "normalizedInput"})
            mismatchResult.getJSONObject(branch).getJSONObject("targetPan").getJSONObject("diameterEvidence")
                    .put("rawInput", "원형 12 cm 내경 팬 2개");
        JSONObject mismatchGeometry = GeometryFactorStage.evaluate(mismatchResult, intent());
        JSONObject mismatchGate = PreviewEligibilityGate.evaluate(mismatchResult, mismatchGeometry);
        check("BLOCK".equals(mismatchGate.getString("decision")), "measurement-class mismatch blocks");

        PreBakeSession changed = new PreBakeSession(easyRead, authority);
        JSONObject changedView = changed.evaluateTarget("15", "2", "");
        BigDecimal expectedFactor = new BigDecimal("15").divide(new BigDecimal("15.24"),
                java.math.MathContext.DECIMAL128);
        expectedFactor = expectedFactor.multiply(expectedFactor, java.math.MathContext.DECIMAL128);
        check(expectedFactor.compareTo(new BigDecimal(changed.geometryResult().getString("geometryFactor"))) == 0 &&
                        "PASS".equals(changed.previewEligibilityResult().getString("decision")) &&
                        "AUTHORITY_REQUIRED".equals(changedView.getString("state")) &&
                        changedView.getJSONArray("ingredients").length() == 0 &&
                        easySnapshot.equals(changed.readSnapshot().toString()),
                "diameter recomputes geometry without mutating source or expanding authority");

        JSONObject diagnostic = easy.diagnostics();
        check("TEST_WAIT".equals(diagnostic.getJSONObject("bfF02").getString("ruleStatus")) &&
                        !diagnostic.getBoolean("fitApplied") && !diagnostic.getBoolean("productionReady") &&
                        !easy.previewAuthorityResult().getBoolean("executionAuthorized"),
                "safety state remains TEST_WAIT false false");
        System.out.println("PASS generalized preview: technical gate, separate authority, Korean projection, 13-case fail-closed matrix");
    }

    private static void assertIngredientBlock(JSONObject authority, JSONObject source, String unit,
                                              String name, boolean compound, String label) throws Exception {
        JSONObject changed = new JSONObject(source.toString());
        for (String branch : new String[]{"workingRecipe", "normalizedInput"}) {
            JSONObject row = changed.getJSONObject(branch).getJSONArray("ingredientGroups")
                    .getJSONObject(0).getJSONArray("ingredients").getJSONObject(0);
            row.put("unitRaw", unit).put("nameRaw", name);
            if (compound) row.put("quantitySemanticState", "SOURCE_PRESERVED_COMPOUND_UNRESOLVED")
                    .put("unresolvedQuantityFragments", new JSONArray().put(new JSONObject().put("rawNumber", "2")));
        }
        PreBakeSession session = new PreBakeSession(changed, authority);
        JSONObject view = session.evaluateTarget("12", "2", "");
        check("BLOCK".equals(session.previewEligibilityResult().getString("decision")) &&
                "BLOCKED".equals(view.getString("state")) &&
                view.getJSONArray("ingredients").length() == 0, label);
    }

    private static JSONObject withSyntheticHash(JSONObject source, String hash) throws Exception {
        JSONObject changed = new JSONObject(source.toString());
        for (String branch : new String[]{"workingRecipe", "normalizedInput"}) {
            JSONObject recipe = changed.getJSONObject(branch).put("sourceBodySha256", hash);
            JSONObject pan = recipe.getJSONObject("sourcePan");
            for (String evidence : new String[]{"shapeEvidence", "diameterEvidence", "countEvidence"})
                pan.getJSONObject(evidence).put("sourceBodySha256", hash);
            JSONArray groups = recipe.getJSONArray("ingredientGroups");
            for (int i = 0; i < groups.length(); i++) {
                JSONObject group = groups.getJSONObject(i).put("sourceBodySha256", hash);
                JSONArray rows = group.getJSONArray("ingredients");
                for (int j = 0; j < rows.length(); j++) {
                    JSONObject row = rows.getJSONObject(j).put("sourceBodySha256", hash);
                    JSONObject evidence = row.optJSONObject("sourceEvidence");
                    if (evidence != null) evidence.put("expectedBodySha256", hash);
                }
            }
            JSONArray instructions = recipe.getJSONArray("instructionGroups");
            for (int i = 0; i < instructions.length(); i++) {
                JSONArray steps = instructions.getJSONObject(i).getJSONArray("steps");
                for (int j = 0; j < steps.length(); j++) steps.getJSONObject(j).put("sourceBodySha256", hash);
            }
        }
        changed.getJSONObject("workingRecipeGuard").put("sourceBodySha256", hash);
        return changed;
    }

    private static JSONObject intent() throws Exception {
        return new JSONObject().put("origin", "USER_TARGET_EVALUATION_INTENT")
                .put("layerStructurePreserved", true).put("processPreserved", true)
                .put("sameBatterDepthIntent", true);
    }
}
