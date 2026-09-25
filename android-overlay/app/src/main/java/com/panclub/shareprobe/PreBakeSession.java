package com.panclub.shareprobe;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Immutable READ snapshot plus replaceable user-input evaluation state. */
public final class PreBakeSession {
    private final String readSnapshotJson;
    private final String previewAuthorityJson;
    private JSONObject currentResult;
    private JSONObject geometry;
    private JSONObject eligibility;
    private JSONObject previewEligibility;
    private JSONObject previewAuthority;
    private JSONObject previewProjection;
    private String inputProblem;

    public PreBakeSession(JSONObject readResult) {
        this(readResult, null);
    }

    public PreBakeSession(JSONObject readResult, JSONObject previewAuthorityFixture) {
        if (readResult == null) throw new IllegalArgumentException("READ_RESULT_REQUIRED");
        this.readSnapshotJson = readResult.toString();
        this.previewAuthorityJson = previewAuthorityFixture == null ? null : previewAuthorityFixture.toString();
        JSONObject initial = copy(readResult);
        JSONObject working = initial.optJSONObject("workingRecipe");
        this.currentResult = working != null && working.optJSONObject("sourcePan") == null
                ? SourcePanRecovery.request(initial) : initial;
    }

    public JSONObject evaluateTarget(String diameterCm, String count, String heightCm) {
        JSONObject base = revisionedReadSnapshot();
        inputProblem = null;
        try {
            currentResult = TargetPanContract.attachFields(base, diameterCm, count, heightCm);
            eligibility = FitEligibilityGate.evaluate(currentResult);
            geometry = GeometryFactorStage.evaluate(currentResult, userGeometryIntent());
            previewEligibility = PreviewEligibilityGate.evaluate(currentResult, geometry);
            previewAuthority = PreviewAuthorityGate.evaluate(currentResult, previewEligibility, previewAuthorityFixture());
            previewProjection = PreviewResultPresenter.project(
                    currentResult, geometry, previewEligibility, previewAuthority);
        } catch (IllegalArgumentException e) {
            currentResult = base;
            geometry = null;
            eligibility = null;
            previewEligibility = null;
            previewAuthority = null;
            previewProjection = null;
            inputProblem = e.getMessage();
        }
        return view();
    }

    public JSONObject requestSourcePanRecovery() {
        currentResult = SourcePanRecovery.request(readSnapshot());
        geometry = null;
        eligibility = null;
        previewEligibility = null;
        previewAuthority = null;
        previewProjection = null;
        inputProblem = null;
        return view();
    }

    public JSONObject provideSourcePan(String diameterCm, String count, String heightCm) {
        try {
            currentResult = SourcePanRecovery.provide(readSnapshot(), diameterCm, count, heightCm);
            inputProblem = null;
        } catch (IllegalArgumentException e) {
            currentResult = SourcePanRecovery.request(readSnapshot());
            inputProblem = e.getMessage();
        }
        geometry = null;
        eligibility = null;
        previewEligibility = null;
        previewAuthority = null;
        previewProjection = null;
        return view();
    }

    public JSONObject sourcePanUnknown() {
        currentResult = SourcePanRecovery.unknown(readSnapshot());
        geometry = null;
        eligibility = null;
        previewEligibility = null;
        previewAuthority = null;
        previewProjection = null;
        inputProblem = null;
        return view();
    }

    public JSONObject view() {
        if (previewProjection != null && previewProjection.optJSONObject("view") != null)
            return copy(previewProjection.optJSONObject("view"));
        return ConsumerResultPresenter.present(currentResult, geometry, eligibility, inputProblem);
    }

    public JSONObject diagnostics() {
        try {
            return new JSONObject()
                    .put("mode", "INTERNAL_DIAGNOSTIC")
                    .put("readSnapshot", readSnapshot())
                    .put("evaluationResult", copy(currentResult))
                    .put("geometry", geometry == null ? JSONObject.NULL : copy(geometry))
                    .put("fitEligibility", eligibility == null ? JSONObject.NULL : copy(eligibility))
                    .put("previewTechnicalEligibility", previewEligibility == null
                            ? JSONObject.NULL : copy(previewEligibility))
                    .put("previewAuthority", previewAuthority == null
                            ? JSONObject.NULL : copy(previewAuthority))
                    .put("previewResult", previewProjection == null
                            ? JSONObject.NULL : copy(previewProjection.optJSONObject("diagnostic")))
                    .put("inputProblem", inputProblem == null ? JSONObject.NULL : inputProblem)
                    .put("bfF02", new JSONObject().put("ruleId", "BF-F02")
                            .put("ruleStatus", "TEST_WAIT")
                            .put("consumerPreviewCalculated", previewAuthority != null &&
                                    "ALLOW".equals(previewAuthority.optString("decision")))
                            .put("productionExecutionAuthorized", false)
                            .put("productionTransformedQuantities", new JSONArray()))
                    .put("fitApplied", false).put("productionReady", false);
        } catch (JSONException e) {
            return new JSONObject();
        }
    }

    public JSONObject readSnapshot() {
        try { return new JSONObject(readSnapshotJson); }
        catch (JSONException e) { throw new IllegalStateException("READ_SNAPSHOT_JSON_FAILED", e); }
    }
    public JSONObject evaluationResult() { return copy(currentResult); }
    public JSONObject geometryResult() { return geometry == null ? null : copy(geometry); }
    public JSONObject eligibilityResult() { return eligibility == null ? null : copy(eligibility); }
    public JSONObject previewEligibilityResult() {
        return previewEligibility == null ? null : copy(previewEligibility);
    }
    public JSONObject previewAuthorityResult() {
        return previewAuthority == null ? null : copy(previewAuthority);
    }
    public JSONObject previewDiagnostic() {
        return previewProjection == null ? null : copy(previewProjection.optJSONObject("diagnostic"));
    }

    private JSONObject previewAuthorityFixture() {
        if (previewAuthorityJson == null) return null;
        try { return new JSONObject(previewAuthorityJson); }
        catch (JSONException e) { throw new IllegalStateException("PREVIEW_AUTHORITY_JSON_FAILED", e); }
    }

    private JSONObject revisionedReadSnapshot() {
        try {
            JSONObject base = readSnapshot();
            JSONObject working = base.optJSONObject("workingRecipe");
            String revision = working == null ? "READ_NO_WORKING_RECIPE" :
                    working.optString("sourceBodySha256", "READ_SOURCE_REVISION_MISSING");
            return base.put("inputRevision", revision).put("workingRecipeRevision", revision);
        } catch (JSONException e) {
            throw new IllegalStateException("SESSION_REVISION_JSON_FAILED", e);
        }
    }

    private static JSONObject userGeometryIntent() {
        try {
            return new JSONObject().put("origin", "USER_TARGET_EVALUATION_INTENT")
                    .put("layerStructurePreserved", true)
                    .put("processPreserved", true)
                    .put("sameBatterDepthIntent", true)
                    .put("scope", "TARGET_PAN_FORM_REEVALUATION_ONLY");
        } catch (JSONException e) {
            throw new IllegalStateException("GEOMETRY_INTENT_JSON_FAILED", e);
        }
    }

    private static JSONObject copy(JSONObject object) {
        if (object == null) return new JSONObject();
        try { return new JSONObject(object.toString()); }
        catch (JSONException e) { throw new IllegalStateException("SESSION_COPY_JSON_FAILED", e); }
    }
}
