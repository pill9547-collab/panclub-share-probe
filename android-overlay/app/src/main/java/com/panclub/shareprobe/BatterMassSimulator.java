package com.panclub.shareprobe;

import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.math.BigDecimal;
import java.math.BigInteger;
import java.math.MathContext;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.zip.GZIPInputStream;

/** TEST_WAIT evidence only; never writes to workingRecipe or emits kitchen quantities. */
public final class BatterMassSimulator {
    private static final String FROZEN_SHA = "1f807042892e1e3ed5ec7e10bf9afb48e169f67ffae2b5bd0388ecccc2f07943";
    private static final Pattern POSITIVE_DECIMAL = Pattern.compile("(?:0|[1-9][0-9]*)(?:\\.[0-9]+)?");
    private static final Pattern CARD_ROW = Pattern.compile(
            "<li class=\"wprm-recipe-ingredient\"[^>]*data-uid=\"([0-9]+)\"[^>]*>.*?" +
            "<input[^>]*class=\"wprm-checkbox\"[^>]*aria-label=\"([^\"]*)\"", Pattern.DOTALL);

    private BatterMassSimulator() {}

    public static JSONObject evaluate(JSONObject fixture, byte[] archivedHtmlGzip, JSONObject target) {
        JSONObject out = initialOutput();
        if (out.length() == 0) return out;
        try {
            require(fixture != null && target != null && archivedHtmlGzip != null, "MISSING_INPUT");
            byte[] body = gunzip(archivedHtmlGzip);
            String sha = sha256(body);
            String sourceUrl = fixture.getString("sourceUrl");
            require(FROZEN_SHA.equals(sha) && sha.equals(fixture.getString("responseBodySha256")) &&
                    sha.equals(fixture.getJSONObject("sourceRevisionIdentity").getString("responseBodySha256")) &&
                    sourceUrl.equals(fixture.getJSONObject("sourceRevisionIdentity").getString("sourceUrl")) &&
                    "FIXTURE_READY".equals(fixture.getString("status")), "STALE_SOURCE_REVISION_OR_HASH");
            require(!fixture.getBoolean("fitApplied") && fixture.getInt("ingredientScalingCount") == 0 &&
                    fixture.getJSONArray("orphanNumbers").length() == 0 &&
                    "NO_OBSERVED_INTERNAL_CONFLICT".equals(fixture.getJSONObject("withinSnapshotAudit").getString("decision")),
                    "SOURCE_CONFLICT_OR_ORPHAN");
            require("SOURCE_REVISION_OBSERVED".equals(fixture.getJSONObject("history").getString("status")) &&
                    "OBSERVATION_ONLY_NOT_MERGED".equals(fixture.getJSONObject("history").getString("historicalAuthority")),
                    "HISTORICAL_SOURCE_MERGED");

            JSONObject sourcePan = fixture.getJSONObject("sourcePan");
            JSONObject diameter = sourcePan.getJSONObject("diameter");
            JSONObject panEvidence = sourcePan.getJSONObject("diameterAndCountEvidence");
            JSONObject shapeEvidence = sourcePan.getJSONObject("shapeEvidence");
            String html = new String(body, StandardCharsets.UTF_8);
            String rawInstruction = panEvidence.getString("rawInstruction");
            require("round".equals(sourcePan.getString("shape")) &&
                    "round".equals(target.getString("shape")) &&
                    "NOMINAL_DIAMETER".equals(diameter.getString("measurementClass")) &&
                    "NOMINAL_DIAMETER".equals(target.getString("measurementClass")) &&
                    "6-inch".equals(diameter.getString("rawValue")) &&
                    "6".equals(diameter.getString("numericRaw")) && "inch".equals(diameter.getString("unit")) &&
                    "6-inch round cake pans".equals(shapeEvidence.getString("rawValue")) &&
                    rawInstruction.contains("three 6-inch cake pans") &&
                    html.contains("three 6-inch cake pans") && html.contains("6-inch round cake pans") &&
                    html.contains("Divide the batter equally between the prepared pans") &&
                    "three".equals(sourcePan.getString("countRaw")) &&
                    sourceUrl.equals(panEvidence.getString("sourceUrl")) &&
                    sha.equals(panEvidence.getString("responseBodySha256")) &&
                    sha.equals(shapeEvidence.getString("responseBodySha256")), "SOURCE_PAN_UNPROVEN");
            // BF-F01 fails before BF-U01 when count/process preservation is unavailable.
            require(sourcePan.getInt("count") == target.getInt("count"), "PAN_COUNT_MERGE_SPLIT_REQUIRED");
            require("USER_SPECIFIED_SIMULATOR_TARGET".equals(target.getString("origin")) &&
                    "15 cm".equals(target.getString("diameterRaw")) &&
                    "cm".equals(target.getString("unit")) &&
                    !target.getBoolean("physicalOwnershipConfirmed") &&
                    target.getString("instructionLocator").length() > 0 &&
                    target.getBoolean("layerStructurePreserved") &&
                    target.getBoolean("processPreserved") &&
                    target.getBoolean("sameBatterDepthIntent"), "TARGET_OR_PROCESS_UNPROVEN");

            // BF-P04 retains nominal semantics. BF-U01 and BF-F01 reuse the same arithmetic
            // methods as the existing Pin559 geometry execution, with the frozen hyphenated span.
            BigDecimal sourceInches = positive(diameter.getString("numericRaw"));
            BigDecimal targetCm = positive(target.getString("diameterRaw").replace(" cm", ""));
            BigDecimal sourceCm = GeometryFactorStage.normalizeInches(sourceInches);
            BigDecimal factor = GeometryFactorStage.factorFromCentimeters(sourceCm, targetCm);
            BigInteger[] rational = rationalFactor(targetCm, sourceCm);
            JSONObject geometry = new JSONObject()
                    .put("decision", "GEOMETRY_PASS").put("sourceDiameterNormalized", sourceCm.toPlainString()+" cm")
                    .put("targetDiameterNormalized", targetCm.toPlainString()+" cm")
                    .put("geometryFactor", factor.toPlainString())
                    .put("exactFactorRational", new JSONObject().put("numerator", rational[0].toString())
                            .put("denominator", rational[1].toString()))
                    .put("ruleChain", new JSONArray().put("BF-P04").put("BF-U01").put("BF-F01"))
                    .put("sourcePanEvidence", new JSONObject(panEvidence.toString()))
                    .put("sourceShapeEvidence", new JSONObject(shapeEvidence.toString()))
                    .put("targetEvidence", new JSONObject(target.toString()))
                    .put("lengthNormalization", new JSONObject().put("ruleId", "BF-U01")
                            .put("sourceRawValue", diameter.getString("rawValue"))
                            .put("sourceRawUnit", "inch").put("normalizedValue", sourceCm.toPlainString())
                            .put("normalizedUnit", "cm"));
            out.put("geometry", geometry).put("sourceFixtureRevision", fixture.getJSONObject("sourceRevisionIdentity"))
                    .put("targetPan", new JSONObject(target.toString()))
                    .put("numericConversionsApplied", new JSONArray().put(geometry.getJSONObject("lengthNormalization")));

            JSONObject group = fixture.getJSONObject("batterIngredientGroup");
            JSONArray rows = group.getJSONArray("ingredients");
            require(group.getInt("count") == rows.length() && rows.length() == 12, "SOURCE_BATTER_GROUP_UNVERIFIED");
            int transformed = 0;
            for (int i=0; i<rows.length(); i++) {
                JSONObject row = rows.getJSONObject(i);
                JSONObject rowResult = new JSONObject().put("index", i)
                        .put("rawSourceRow", row.optString("rawValue"))
                        .put("ingredientIdentity", row.optString("ingredientIdentity"))
                        .put("sourceProvenance", new JSONObject(row.toString()))
                        .put("status", "REJECTED");
                out.getJSONArray("ingredientResults").put(rowResult);
                String reject = rowBlocker(row, i, sha, sourceUrl, html, fixture);
                if (reject != null) { rowResult.put("reason",reject); continue; }
                BigDecimal grams = positive(row.getJSONObject("sourceGrams").getString("rawAmount"));
                BigDecimal computed = experimentalTargetMass(grams, factor);
                BigInteger[] exact = exactMass(grams, rational);
                rowResult.put("status", "EXPERIMENTAL_TRANSFORMED")
                        .put("sourceMass_g", grams.toPlainString())
                        .put("targetMass_g", computed.toPlainString())
                        .put("targetMassExactRational_g", new JSONObject()
                                .put("numerator",exact[0].toString()).put("denominator",exact[1].toString()))
                        .put("precision", "DECIMAL128_INTERNAL_NO_PRESENTATION_ROUNDING_EXACT_RATIONAL_ATTACHED")
                        .put("derivation", new JSONObject()
                                .put("geometry",new JSONObject(geometry.toString()))
                                .put("ruleId","BF-F02").put("ruleStatus","TEST_WAIT")
                                .put("formula","sourceMass_g * geometryFactor"));
                transformed++;
            }
            out.put("status", "TEST_WAIT").put("ruleChain", new JSONArray()
                    .put("BF-P04").put("BF-U01").put("BF-F01").put("BF-F02"))
                    .put("sourceBatterRowCount",rows.length()).put("experimentalTransformCount",transformed)
                    .put("densityConversionsApplied",0);
            return out;
        } catch (Exception e) {
            return blockedOutput(e instanceof IllegalArgumentException
                    ? e.getMessage() : "MALFORMED_OR_UNVERIFIED_FIXTURE");
        }
    }

    private static JSONObject initialOutput() {
        JSONObject out = new JSONObject();
        try {
            out.put("status", "BLOCK")
                    .put("ruleId", "BF-F02").put("ruleStatus", "TEST_WAIT")
                    .put("fitApplied", false).put("productionReady", false).put("userVisible", false)
                    .put("bakeValidationRequired", true).put("ingredientResults", new JSONArray())
                    .put("numericConversionsApplied", new JSONArray()).put("orphanNumbers", new JSONArray());
        } catch (JSONException ignored) {
            // Android uses checked JSONException. The empty object remains fail-closed.
        }
        return out;
    }

    private static JSONObject blockedOutput(String blocker) {
        JSONObject out = new JSONObject();
        try {
            out.put("status", "BLOCK").put("blocker", blocker)
                    .put("ruleId", "BF-F02").put("ruleStatus", "TEST_WAIT")
                    .put("fitApplied", false).put("productionReady", false).put("userVisible", false)
                    .put("ingredientResults", new JSONArray()).put("numericConversionsApplied", new JSONArray())
                    .put("orphanNumbers", new JSONArray());
        } catch (JSONException ignored) {
            // Android uses checked JSONException. The empty object remains fail-closed.
        }
        return out;
    }

    private static String rowBlocker(JSONObject row,int index,String sha,String sourceUrl,String html,JSONObject fixture) {
        try {
            if (!("batter.ingredients["+index+"]").equals(row.getString("semanticField")) ||
                    !sourceUrl.equals(row.getString("sourceUrl")) || !sha.equals(row.getString("responseBodySha256")))
                return "NOT_SAME_SOURCE_BATTER_ROW";
            if (!"SOURCE_ROW_DUAL_UNIT_MATCHED_JSONLD_PRIMARY_UNSCALED".equals(row.getString("semanticState")) ||
                    !row.getString("jsonLdPrimaryRow").contains(row.getString("ingredientIdentity")))
                return "SOURCE_CONFLICT_OR_COMPOUND_QUANTITY";
            JSONObject mass = row.optJSONObject("sourceGrams");
            if (mass == null || !"g".equals(mass.optString("unit")) ||
                    !"EXPLICIT_IN_VISIBLE_CARD_SECONDARY_UNIT".equals(mass.optString("status")) ||
                    !POSITIVE_DECIMAL.matcher(mass.optString("rawAmount")).matches())
                return "EXPLICIT_SOURCE_GRAMS_MISSING";
            String raw = row.getString("rawValue");
            if (!raw.contains(mass.getString("rawAmount")+" g") ||
                    !raw.contains(row.getString("ingredientIdentity"))) return "SOURCE_MASS_NOT_IN_ROW";
            String locator = row.getString("sourceLocator");
            Matcher uid = Pattern.compile("^#wprm-recipe-container-81014 li\\.wprm-recipe-ingredient\\[data-uid=\"([0-9]+)\"\\]$").matcher(locator);
            if (!uid.matches() || !mass.getString("locator").equals(locator+" .wprm-recipe-ingredient-unit-system-2"))
                return "ROW_LOCATOR_UNVERIFIED";
            Matcher card = CARD_ROW.matcher(html); boolean found=false;
            while(card.find()) if (uid.group(1).equals(card.group(1))) {
                if (found || !raw.equals(decodeAttribute(card.group(2)))) return "SOURCE_ROW_HTML_CONFLICT";
                found=true;
            }
            if (!found) return "SOURCE_ROW_HTML_MISSING";
            if (index==7 && !("3".equals(row.getString("sourceAmount")) &&
                    "count".equals(row.getString("sourceUnit")) &&
                    "132".equals(mass.getString("rawAmount")) &&
                    "132 g".equals(fixture.getJSONObject("eggSourceRepresentation").getString("sourceMassRaw"))))
                return "EGG_SOURCE_MASS_UNPROVEN";
            return null;
        } catch (Exception e) { return "ROW_MALFORMED"; }
    }

    private static String decodeAttribute(String raw) {
        return raw.replace("&nbsp;","\u00a0").replace("&#032;"," ").replace("&#39;","'")
                .replace("&quot;","\"").replace("&amp;","&");
    }
    private static BigDecimal positive(String text) {
        if (!POSITIVE_DECIMAL.matcher(text).matches()) throw new IllegalArgumentException("UNSUPPORTED_NUMERIC_SOURCE");
        BigDecimal value=new BigDecimal(text);
        if (value.signum()<=0) throw new IllegalArgumentException("NONPOSITIVE_NUMERIC_SOURCE");
        return value;
    }
    /** Shared TEST_WAIT arithmetic. Presentation and production authority are deliberately absent. */
    static BigDecimal experimentalTargetMass(BigDecimal sourceMass, BigDecimal geometryFactor) {
        if (sourceMass == null || geometryFactor == null ||
                sourceMass.signum() <= 0 || geometryFactor.signum() <= 0)
            throw new IllegalArgumentException("NONPOSITIVE_EXPERIMENTAL_INPUT");
        return sourceMass.multiply(geometryFactor, MathContext.DECIMAL128);
    }
    private static BigInteger[] rationalFactor(BigDecimal target,BigDecimal source) {
        BigInteger n=target.unscaledValue().multiply(BigInteger.TEN.pow(source.scale()));
        BigInteger d=source.unscaledValue().multiply(BigInteger.TEN.pow(target.scale()));
        BigInteger gcd=n.gcd(d);n=n.divide(gcd);d=d.divide(gcd);
        return new BigInteger[]{n.multiply(n),d.multiply(d)};
    }
    private static BigInteger[] exactMass(BigDecimal mass,BigInteger[] factor) {
        BigInteger n=mass.unscaledValue().multiply(factor[0]);
        BigInteger d=BigInteger.TEN.pow(mass.scale()).multiply(factor[1]);
        BigInteger gcd=n.gcd(d);return new BigInteger[]{n.divide(gcd),d.divide(gcd)};
    }
    private static byte[] gunzip(byte[] bytes) throws Exception {
        ByteArrayOutputStream buffer=new ByteArrayOutputStream();
        try (GZIPInputStream in=new GZIPInputStream(new ByteArrayInputStream(bytes))) {
            byte[] chunk=new byte[8192];int n;
            while ((n=in.read(chunk))!=-1) {
                buffer.write(chunk,0,n);
                if(buffer.size()>2_000_000)throw new IllegalArgumentException("SOURCE_ARCHIVE_TOO_LARGE");
            }
        }
        return buffer.toByteArray();
    }
    private static String sha256(byte[] bytes) throws Exception {
        byte[] digest=MessageDigest.getInstance("SHA-256").digest(bytes);
        StringBuilder sb=new StringBuilder();for(byte b:digest)sb.append(String.format("%02x",b&0xff));return sb.toString();
    }
    private static void require(boolean passes,String blocker) {
        if(!passes)throw new IllegalArgumentException(blocker);
    }
}
