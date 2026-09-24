package com.panclub.shareprobe;

import java.math.BigDecimal;
import java.math.MathContext;
import org.json.JSONObject;

/** Full pre-bake state flow: dynamic BF-F01 targets, immutable READ, recovery and consumer copy. */
public final class PreBakeSessionTest {
    private static void check(boolean yes, String label) { if (!yes) throw new AssertionError(label); }

    public static void main(String[] args) throws Exception {
        JSONObject read = TargetPanContractTest.replaySourceFixture();
        String readJson = read.toString();
        JSONObject originalWorking = new JSONObject(read.getJSONObject("workingRecipe").toString());
        String pinId = read.getString("pinId");
        String sourceUrl = read.getJSONObject("workingRecipe").getString("sourceUrl");
        String sourceHash = read.getJSONObject("workingRecipe").getString("sourceBodySha256");

        for (String diameter : new String[]{"12", "15", "18", "21"}) {
            PreBakeSession session = new PreBakeSession(read);
            JSONObject view = session.evaluateTarget(diameter, "2", "");
            JSONObject geometry = session.geometryResult();
            check(geometry != null && "GEOMETRY_PASS".equals(geometry.getString("decision")),
                    "BF-F01 geometry passes for " + diameter + " cm x2");
            BigDecimal expected = new BigDecimal(diameter).divide(new BigDecimal("15.24"), MathContext.DECIMAL128);
            expected = expected.multiply(expected, MathContext.DECIMAL128);
            check(expected.compareTo(new BigDecimal(geometry.getString("geometryFactor"))) == 0,
                    "dynamic factor for " + diameter);
            check((diameter + " cm").equals(session.evaluationResult().getJSONObject("workingRecipe")
                    .getJSONObject("targetPan").getString("diameter")), "target is data");
            check("BLOCKED".equals(view.getString("state")), "Pin 559 remains production blocked");
            assertReadIdentity(session, pinId, sourceUrl, sourceHash, originalWorking);
        }

        PreBakeSession same = new PreBakeSession(read);
        same.evaluateTarget("15.24", "2", "9");
        check(BigDecimal.ONE.compareTo(new BigDecimal(same.geometryResult().getString("geometryFactor"))) == 0,
                "same source/target size factor is one");
        check("9 cm".equals(same.evaluationResult().getJSONObject("workingRecipe")
                .getJSONObject("targetPan").getString("height")), "height preserved");
        check(!same.geometryResult().toString().contains("9 cm"), "height excluded from BF-F01");

        PreBakeSession countChange = new PreBakeSession(read);
        countChange.evaluateTarget("12", "1", "");
        check("BLOCK".equals(countChange.geometryResult().getString("decision")) &&
                "PAN_COUNT_MERGE_SPLIT_REQUIRED".equals(countChange.geometryResult().getString("blocker")),
                "2-to-1 remains blocked before geometry");

        String[][] invalid = {{"", "2"}, {"0", "2"}, {"-3", "2"}, {"twelve", "2"},
                {"12", ""}, {"12", "0"}, {"12", "3.5"}, {"2호", "2"}, {"미니", "2"}};
        for (String[] row : invalid) {
            PreBakeSession invalidSession = new PreBakeSession(read);
            JSONObject view = invalidSession.evaluateTarget(row[0], row[1], "");
            check("NEED_INFO".equals(view.getString("state")) && invalidSession.geometryResult() == null,
                    "invalid target requests correction: " + java.util.Arrays.toString(row));
            assertReadIdentity(invalidSession, pinId, sourceUrl, sourceHash, originalWorking);
        }

        try { TargetPanContract.parse("사각 15 cm 팬 2개"); throw new AssertionError("non-round accepted"); }
        catch (IllegalArgumentException expected) { }

        PreBakeSession mismatch = new PreBakeSession(read);
        mismatch.evaluateTarget("15", "2", "");
        JSONObject mismatched = mismatch.evaluationResult();
        for (String branch : new String[]{"workingRecipe", "normalizedInput"})
            mismatched.getJSONObject(branch).getJSONObject("targetPan").getJSONObject("diameterEvidence")
                    .put("rawInput", "원형 15 cm 내경 팬 2개");
        JSONObject mismatchGeometry = GeometryFactorStage.evaluate(mismatched,
                new JSONObject().put("origin", "USER_TARGET_EVALUATION_INTENT")
                        .put("layerStructurePreserved", true).put("processPreserved", true)
                        .put("sameBatterDepthIntent", true));
        check("BLOCK".equals(mismatchGeometry.getString("decision")), "nominal vs measured-inner mismatch blocks");

        JSONObject missing = new JSONObject(read.toString());
        missing.getJSONObject("workingRecipe").put("sourcePan", JSONObject.NULL);
        missing.getJSONObject("normalizedInput").put("sourcePan", JSONObject.NULL);
        PreBakeSession recovery = new PreBakeSession(missing);
        check("NEED_INFO".equals(recovery.view().getString("state")), "missing source asks user");
        JSONObject recoveredView = recovery.provideSourcePan("15", "2", "5");
        JSONObject recoveredResult = recovery.evaluationResult();
        check("BLOCKED".equals(recoveredView.getString("state")) &&
                recoveredResult.getJSONObject("workingRecipe").isNull("sourcePan"),
                "user source is preserved but never promoted to recipe evidence");
        check(SourcePanRecovery.ORIGIN.equals(recoveredResult.getJSONObject("sourcePanRecovery")
                .getJSONObject("candidate").getString("origin")) &&
                !recoveredResult.getJSONObject("sourcePanRecovery").getBoolean("fitAuthority"),
                "source recovery provenance is separate and unauthorized");
        check("BLOCKED".equals(new PreBakeSession(missing).sourcePanUnknown().getString("state")),
                "unknown source blocks");

        JSONObject ambiguous = new JSONObject(missing.toString()).put("sourcePanResolution", "AMBIGUOUS");
        check("NEED_INFO".equals(new PreBakeSession(ambiguous).view().getString("state")),
                "ambiguous source is never guessed");
        check(readJson.equals(read.toString()), "all sessions leave original READ byte-equivalent");
        check("TEST_WAIT".equals(new PreBakeSession(read).diagnostics().getJSONObject("bfF02").getString("ruleStatus")),
                "BF-F02 remains quarantined TEST_WAIT");
        System.out.println("PASS pre-bake flow: 4-size BF-F01 matrix, same-size, invalids, count block, recovery, immutable READ, Korean consumer state");
    }

    private static void assertReadIdentity(PreBakeSession session, String pinId, String url,
                                           String hash, JSONObject originalWorking) throws Exception {
        JSONObject snapshot = session.readSnapshot();
        check(pinId.equals(snapshot.getString("pinId")), "Pin identity unchanged");
        JSONObject working = snapshot.getJSONObject("workingRecipe");
        check(url.equals(working.getString("sourceUrl")) && hash.equals(working.getString("sourceBodySha256")),
                "source revision unchanged");
        check(working.getJSONObject("sourcePan").similar(originalWorking.getJSONObject("sourcePan")),
                "source pan provenance unchanged");
    }
}
