package com.panclub.shareprobe;

import java.nio.file.Files;
import java.nio.file.Path;
import org.json.JSONArray;
import org.json.JSONObject;

/** Replays Pin 559, then attaches structured Korean-first user target state. */
public final class TargetPanContractTest {
    private static void check(boolean ok, String label) { if (!ok) throw new AssertionError(label); }

    static JSONObject replaySourceFixture() throws Exception {
        String pinHtml = Files.readString(Path.of("probe/pin559_excerpt.html"));
        String recipeHtml = Files.readString(Path.of("probe/source559_card_jsonld_same_response.html"));
        String id = "559290847491964725";
        String shortUrl = "https://pin.it/4FEBtOliP";
        String shortRedirect = "https://api.pinterest.com/url_shortener/4FEBtOliP/redirect/";
        String sent = "https://kr.pinterest.com/pin/" + id + "/sent/";
        String pinUrl = "https://kr.pinterest.com/pin/" + id + "/";
        String destination = PublicPinData.inspect(pinHtml, pinUrl, id).destination;
        check(destination != null, "existing same-Pin destination fixture");
        JSONObject payload = new JSONObject()
                .put("action", "android.intent.action.SEND").put("mimeType", "text/plain")
                .put("text", "Take a look! 📌 " + shortUrl).put("subject", "Take a look! 📌")
                .put("clipText", new JSONArray().put("Take a look! 📌 " + shortUrl));
        return new SharePipeline(url -> {
            if (url.equals(shortUrl)) return new SharePipeline.Response(308, shortRedirect, "text/html", "");
            if (url.equals(shortRedirect)) return new SharePipeline.Response(302, sent, "text/html", "");
            if (url.equals(sent)) return new SharePipeline.Response(200, null, "text/html",
                    "<meta property='og:url' content='https://www.pinterest.com/pin/other--422281209284761/'>");
            if (url.equals(pinUrl)) return new SharePipeline.Response(200, null, "text/html", pinHtml);
            if (url.equals(destination)) return new SharePipeline.Response(200, null, "text/html", recipeHtml);
            throw new AssertionError("unobserved request " + url);
        }).run(payload);
    }

    public static void main(String[] args) throws Exception {
        JSONObject before = replaySourceFixture();
        String snapshot = before.toString();
        JSONObject after = TargetPanContract.attachFields(before, "15", "1", "4.5");
        check(snapshot.equals(before.toString()), "READ snapshot not mutated");
        JSONObject working = after.getJSONObject("workingRecipe");
        JSONObject normalized = after.getJSONObject("normalizedInput");
        check(working.getJSONObject("sourcePan").similar(
                before.getJSONObject("workingRecipe").getJSONObject("sourcePan")), "sourcePan untouched");
        JSONObject target = working.getJSONObject("targetPan");
        check("round".equals(target.getString("shape")) && "15 cm".equals(target.getString("diameter"))
                && target.getInt("count") == 1, "structured target values");
        check(TargetPanContract.ORIGIN.equals(target.getString("origin")) &&
                TargetPanContract.ORIGIN.equals(working.getJSONObject("panOrigins").getString("targetPan")),
                "explicit user target origin");
        check("targetPanForm.diameterCm".equals(target.getJSONObject("diameterEvidence").getString("inputLocator"))
                && "targetPanForm.count".equals(target.getJSONObject("countEvidence").getString("inputLocator")),
                "field-level form provenance");
        check("4.5 cm".equals(target.getString("height")) &&
                "OPTIONAL_METADATA_ONLY".equals(target.getString("heightRole")) &&
                !target.getJSONObject("heightEvidence").getBoolean("usedByGeometry"), "height metadata only");
        check(target.isNull("capacity") && !target.getBoolean("fitApplied") &&
                normalized.getJSONObject("targetPan").similar(target), "no capacity or FIT inference");
        check(!working.has("scalingFactor") && !working.has("panArea") &&
                working.getJSONArray("numericConversionsApplied").length() == 0 &&
                working.getJSONArray("orphanNumbers").length() == 0, "no conversions or orphan promotion");
        check(TargetPanContract.ORIGIN.equals(
                TargetPanContract.parse("원형 18 cm 팬 2개").getString("origin")), "legacy explicit-cm input retained");

        String[][] invalid = {
                {null, "2", null}, {"", "2", null}, {"0", "2", null}, {"-1", "2", null},
                {"twelve", "2", null}, {"12cm", "2", null}, {"12", null, null},
                {"12", "", null}, {"12", "0", null}, {"12", "1.5", null},
                {"1호", "2", null}, {"미니", "2", null}
        };
        for (String[] row : invalid) {
            try {
                TargetPanContract.attachFields(before, row[0], row[1], row[2]);
                throw new AssertionError("accepted invalid target: " + java.util.Arrays.toString(row));
            } catch (IllegalArgumentException expected) {
                check(!before.getJSONObject("workingRecipe").has("targetPan"), "invalid target leaves READ unchanged");
            }
        }
        for (String unsupported : new String[]{"사각 15 cm 팬 2개", "1호 팬 2개", "미니 팬 2개"}) {
            try { TargetPanContract.attach(before, unsupported); throw new AssertionError("accepted " + unsupported); }
            catch (IllegalArgumentException expected) { }
        }
        System.out.println("PASS dynamic structured targetPan, explicit provenance, height metadata only, 호 blocked, no FIT");
    }
}
