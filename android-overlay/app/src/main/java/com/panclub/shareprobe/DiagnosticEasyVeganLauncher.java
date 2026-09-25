package com.panclub.shareprobe;

import org.json.JSONObject;

/** Debug-only entry point; delegates to the normal generalized PreBakeSession pipeline. */
public final class DiagnosticEasyVeganLauncher {
    public static final String BUTTON_LABEL = "Easy Vegan 검증 예시 열기";
    private static final String DIAGNOSTIC_MODE = "INTERNAL_DIAGNOSTIC";

    private DiagnosticEasyVeganLauncher() {}

    public static boolean visible(String mode, boolean diagnosticsExpanded) {
        return diagnosticsExpanded && DIAGNOSTIC_MODE.equals(mode);
    }

    public static PreBakeSession launch(JSONObject authorityFixture) {
        if (authorityFixture == null) throw new IllegalArgumentException("PREVIEW_AUTHORITY_ASSET_MISSING");
        PreBakeSession session = new PreBakeSession(
                ApprovedPreviewFixture.readResult(authorityFixture), authorityFixture);
        session.evaluateTarget("12", "2", "");
        return session;
    }
}
