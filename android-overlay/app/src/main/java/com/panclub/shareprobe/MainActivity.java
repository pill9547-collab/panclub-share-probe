package com.panclub.shareprobe;

import android.app.Activity;
import android.content.ClipData;
import android.content.Intent;
import android.graphics.Color;
import android.os.Bundle;
import android.text.InputType;
import android.view.View;
import android.widget.Button;
import android.widget.EditText;
import android.widget.LinearLayout;
import android.widget.ScrollView;
import android.widget.TextView;

import org.json.JSONArray;
import org.json.JSONObject;

import java.net.CookieHandler;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

/** Korean-first pre-bake UI. Raw engine JSON is available only behind diagnostics. */
public final class MainActivity extends Activity {
    private final ExecutorService worker = Executors.newSingleThreadExecutor();
    private LinearLayout content;
    private Future<?> task;
    private long generation;
    private PreBakeSession session;
    private boolean diagnosticsVisible;
    private String targetDiameter = "", targetCount = "", targetHeight = "";
    private String sourceDiameter = "", sourceCount = "", sourceHeight = "";

    @Override public void onCreate(Bundle state) {
        super.onCreate(state);
        CookieHandler.setDefault(null);
        content = new LinearLayout(this);
        content.setOrientation(LinearLayout.VERTICAL);
        content.setPadding(dp(24), dp(28), dp(24), dp(40));
        ScrollView scroll = new ScrollView(this);
        scroll.addView(content);
        setContentView(scroll);
        receive(getIntent());
    }

    @Override protected void onNewIntent(Intent intent) {
        super.onNewIntent(intent);
        setIntent(intent);
        receive(intent);
    }

    @Override protected void onDestroy() {
        generation++;
        if (task != null) task.cancel(true);
        worker.shutdownNow();
        super.onDestroy();
    }

    private void receive(Intent intent) {
        final long run = ++generation;
        if (task != null) task.cancel(true);
        session = null;
        diagnosticsVisible = false;
        targetDiameter = targetCount = targetHeight = "";
        showLoading();
        try {
            JSONObject payload = capture(intent);
            task = worker.submit(() -> {
                JSONObject read;
                try { read = new SharePipeline(new SharePipeline.PublicHttp()).run(payload); }
                catch (Exception e) {
                    try {
                        read = new JSONObject().put("status", "PAYLOAD_PROCESSING_FAILURE")
                                .put("failureStage", e.getClass().getSimpleName())
                                .put("workingRecipe", JSONObject.NULL);
                    } catch (Exception ignored) { read = new JSONObject(); }
                }
                final PreBakeSession completed = new PreBakeSession(read);
                runOnUiThread(() -> {
                    if (run == generation && !isFinishing()) {
                        session = completed;
                        render(session.view());
                    }
                });
            });
        } catch (Exception e) {
            showMessage("공유 내용을 읽지 못했어요.", "Pinterest에서 텍스트 링크를 다시 공유해 주세요.");
        }
    }

    private JSONObject capture(Intent intent) throws Exception {
        JSONObject payload = new JSONObject()
                .put("action", intent.getAction() == null ? JSONObject.NULL : intent.getAction())
                .put("mimeType", intent.getType() == null ? JSONObject.NULL : intent.getType())
                .put("text", raw(intent.getCharSequenceExtra(Intent.EXTRA_TEXT)))
                .put("subject", raw(intent.getCharSequenceExtra(Intent.EXTRA_SUBJECT)))
                .put("dataUrl", intent.getDataString() == null ? JSONObject.NULL : intent.getDataString())
                .put("hasExtraStream", intent.hasExtra(Intent.EXTRA_STREAM))
                .put("receivedAtEpochMs", System.currentTimeMillis());
        JSONArray clips = new JSONArray(), uris = new JSONArray();
        ClipData clip = intent.getClipData();
        if (clip != null) for (int i = 0; i < clip.getItemCount(); i++) {
            clips.put(raw(clip.getItemAt(i).getText()));
            uris.put(clip.getItemAt(i).getUri() == null
                    ? JSONObject.NULL : clip.getItemAt(i).getUri().toString());
        }
        return payload.put("clipText", clips).put("clipUris", uris);
    }

    private void render(JSONObject view) {
        content.removeAllViews();
        title("PANCLUB");
        label(view.optString("recipeTitle", "레시피"), 22, Color.rgb(35, 35, 35));

        String sourcePan = view.optString("sourcePan");
        if (!sourcePan.isEmpty()) {
            section(view.optString("sourcePanLabel", "원본 팬"));
            label(sourcePan, 18, Color.rgb(30, 30, 30));
        }

        String targetPan = view.optString("targetPan");
        if (!targetPan.isEmpty()) {
            section("목표 팬");
            label(targetPan, 18, Color.rgb(30, 30, 30));
        }

        section("현재 결과");
        String state = view.optString("state", "BLOCKED");
        label(stateLabel(state), 16, stateColor(state));
        label(view.optString("headline"), 18, Color.rgb(35, 35, 35));
        JSONArray reasons = view.optJSONArray("reasons");
        if (reasons != null && reasons.length() > 0) {
            label("확인이 필요한 항목:", 15, Color.DKGRAY);
            for (int i = 0; i < reasons.length(); i++) label("• " + reasons.optString(i), 15, Color.DKGRAY);
        }

        JSONObject evaluated = session.evaluationResult();
        JSONObject working = evaluated.optJSONObject("workingRecipe");
        JSONObject source = working == null ? null : working.optJSONObject("sourcePan");
        if (working != null && source == null) sourceRecoveryForm();
        else if (source != null) targetForm();

        Button diagnostics = button(diagnosticsVisible ? "진단 정보 닫기" : "진단 정보 보기");
        diagnostics.setOnClickListener(v -> {
            diagnosticsVisible = !diagnosticsVisible;
            render(session.view());
        });
        if (diagnosticsVisible) {
            TextView raw = label("", 12, Color.DKGRAY);
            raw.setTextIsSelectable(true);
            raw.setText(session.diagnostics().toString());
        }
    }

    private void targetForm() {
        section("어떤 팬으로 만들까요?");
        label("Rev1은 원형 팬만 지원해요.", 14, Color.DKGRAY);
        label("원형", 16, Color.BLACK);
        EditText diameter = numberInput("지름 (cm)", targetDiameter, true);
        EditText count = numberInput("팬 개수", targetCount, false);
        EditText height = numberInput("높이 (cm, 선택)", targetHeight, true);
        Button evaluate = button("이 팬으로 평가하기");
        evaluate.setOnClickListener(v -> {
            targetDiameter = diameter.getText().toString();
            targetCount = count.getText().toString();
            targetHeight = height.getText().toString();
            render(session.evaluateTarget(targetDiameter, targetCount, targetHeight));
        });
    }

    private void sourceRecoveryForm() {
        section("원본 팬 확인");
        label("원본 팬 정보를 찾지 못했어요.\n이 레시피에 사용한 팬 크기를 알고 있나요?", 17, Color.BLACK);
        EditText diameter = numberInput("원본 팬 지름 (cm)", sourceDiameter, true);
        EditText count = numberInput("원본 팬 개수", sourceCount, false);
        EditText height = numberInput("원본 팬 높이 (cm, 선택)", sourceHeight, true);
        Button provide = button("직접 입력");
        provide.setOnClickListener(v -> {
            sourceDiameter = diameter.getText().toString();
            sourceCount = count.getText().toString();
            sourceHeight = height.getText().toString();
            render(session.provideSourcePan(sourceDiameter, sourceCount, sourceHeight));
        });
        Button unknown = button("모르겠어요");
        unknown.setOnClickListener(v -> render(session.sourcePanUnknown()));
    }

    private EditText numberInput(String hint, String value, boolean decimal) {
        EditText input = new EditText(this);
        input.setHint(hint);
        input.setText(value);
        input.setSingleLine(true);
        input.setInputType(InputType.TYPE_CLASS_NUMBER |
                (decimal ? InputType.TYPE_NUMBER_FLAG_DECIMAL : 0));
        input.setLayoutParams(spacedParams());
        content.addView(input);
        return input;
    }

    private Button button(String text) {
        Button button = new Button(this);
        button.setText(text);
        button.setAllCaps(false);
        button.setLayoutParams(spacedParams());
        content.addView(button);
        return button;
    }

    private TextView label(String text, int sp, int color) {
        TextView view = new TextView(this);
        view.setText(text);
        view.setTextSize(sp);
        view.setTextColor(color);
        view.setLineSpacing(0, 1.15f);
        view.setLayoutParams(spacedParams());
        content.addView(view);
        return view;
    }

    private void title(String text) { label(text, 28, Color.rgb(108, 55, 25)); }
    private void section(String text) { label(text, 14, Color.rgb(115, 80, 55)); }

    private void showLoading() {
        content.removeAllViews();
        title("PANCLUB");
        label("레시피와 출처를 확인하고 있어요…", 18, Color.DKGRAY);
    }

    private void showMessage(String heading, String detail) {
        content.removeAllViews();
        title("PANCLUB");
        label(heading, 20, Color.rgb(150, 30, 30));
        label(detail, 16, Color.DKGRAY);
    }

    private LinearLayout.LayoutParams spacedParams() {
        LinearLayout.LayoutParams params = new LinearLayout.LayoutParams(
                LinearLayout.LayoutParams.MATCH_PARENT, LinearLayout.LayoutParams.WRAP_CONTENT);
        params.setMargins(0, dp(6), 0, dp(6));
        return params;
    }

    private String stateLabel(String state) {
        if ("READY".equals(state)) return "평가 가능";
        if ("NEED_INFO".equals(state)) return "정보 필요";
        return "변환 불가";
    }

    private int stateColor(String state) {
        if ("READY".equals(state)) return Color.rgb(20, 115, 60);
        if ("NEED_INFO".equals(state)) return Color.rgb(180, 105, 0);
        return Color.rgb(160, 35, 35);
    }

    private int dp(int value) { return Math.round(value * getResources().getDisplayMetrics().density); }
    private static Object raw(CharSequence value) { return value == null ? JSONObject.NULL : value.toString(); }
}
