package com.panclub.shareprobe;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Resolves only explicit Pinterest first-party Pin identity evidence.
 * Titles, images, descriptions, domains, anchors, and unrelated records are never identity evidence.
 */
public final class PinIdentityResolver {
    private static final Pattern SCRIPT = Pattern.compile("(?is)<script\\b([^>]*)>(.*?)</script\\s*>");
    private static final Pattern TAG = Pattern.compile("(?is)<(?:meta|link)\\b[^>]*>");
    private static final Pattern ATTRIBUTE = Pattern.compile("(?is)([a-zA-Z:-]+)\\s*=\\s*(['\"])(.*?)\\2");
    private static final Pattern PIN_PATH = Pattern.compile("/pin/(?:([0-9]+)|[a-z0-9-]+--([0-9]+))(?:/sent)?/?", Pattern.CASE_INSENSITIVE);
    private static final String WRAPPER = "window.__PWS_RELAY_REGISTER_COMPLETED_REQUEST__(";

    private PinIdentityResolver() {}

    public static final class Result {
        public final String status;
        public final String normalizedPinId;
        public final JSONObject evidence;

        Result(String status, String normalizedPinId, JSONObject evidence) {
            this.status = status;
            this.normalizedPinId = normalizedPinId;
            this.evidence = evidence;
        }
    }

    public static Result resolve(String html, String requestUrl, String expectedPinId) throws Exception {
        JSONObject proof = new JSONObject()
                .put("resolver", "PinIdentityResolver/1")
                .put("requestedPinId", expectedPinId)
                .put("requestUrl", requestUrl)
                .put("policy", "EVIDENCE_WAIT_NOT_PRODUCTION_ENABLED");
        if (!expectedPinId.equals(pinId(requestUrl))) return unproven(proof, "REQUEST_ID_MISMATCH");

        JSONArray pageUrls = new JSONArray();
        JSONArray pageIds = new JSONArray();
        String pageId = null;
        Matcher tags = TAG.matcher(html);
        while (tags.find()) {
            String tag = tags.group();
            String property = attr(tag, "property");
            String rel = attr(tag, "rel");
            String url = null;
            if ("og:url".equalsIgnoreCase(property)) url = attr(tag, "content");
            if ("canonical".equalsIgnoreCase(rel)) url = attr(tag, "href");
            if (url == null) continue;
            String id = pinId(url);
            pageUrls.put(url);
            pageIds.put(id == null ? JSONObject.NULL : id);
            if (id == null) return unproven(proof.put("pageIdentityUrls", pageUrls).put("pageIdentityPinIds", pageIds), "INVALID_PAGE_IDENTITY");
            if (pageId == null) pageId = id;
            else if (!pageId.equals(id)) return unproven(proof.put("pageIdentityUrls", pageUrls).put("pageIdentityPinIds", pageIds), "CONFLICTING_PAGE_IDENTITIES");
        }
        proof.put("pageIdentityUrls", pageUrls).put("pageIdentityPinIds", pageIds);
        if (pageId == null) return unproven(proof, "PAGE_IDENTITY_MISSING");
        if (expectedPinId.equals(pageId)) {
            return finish("SAME_ID", expectedPinId, proof.put("evidenceClass", "DIRECT_PAGE_IDENTITY"));
        }

        JSONArray bindings = new JSONArray();
        int requestedRecords = 0;
        int explicitCanonicalBindings = 0;
        int scriptIndex = 0;
        Matcher scripts = SCRIPT.matcher(html);
        while (scripts.find()) {
            if (!scripts.group(1).contains("data-relay-completed-request")) { scriptIndex++; continue; }
            try {
                String body = scripts.group(2).trim();
                if (!body.startsWith(WRAPPER)) throw new IllegalArgumentException("UNKNOWN_WRAPPER");
                JSONTokener tokens = new JSONTokener(body.substring(WRAPPER.length()));
                Object keyRaw = tokens.nextValue();
                if (!(keyRaw instanceof String) || tokens.nextClean() != ',') throw new IllegalArgumentException("INVALID_ARGUMENTS");
                JSONObject key = new JSONObject(URLDecoder.decode((String) keyRaw, StandardCharsets.UTF_8.name()));
                Object second = tokens.nextValue();
                if (!(second instanceof JSONObject) || tokens.nextClean() != ')' || tokens.nextClean() != ';' || tokens.nextClean() != 0)
                    throw new IllegalArgumentException("INVALID_TRAILING_CODE");
                JSONObject variables = key.optJSONObject("variables");
                if (variables == null || !expectedPinId.equals(variables.optString("pinId"))) { scriptIndex++; continue; }
                JSONObject data = ((JSONObject) second).getJSONObject("data").getJSONObject("v3GetPinQueryv2").getJSONObject("data");
                if (!expectedPinId.equals(data.optString("entityId"))) return unproven(proof, "REQUESTED_RECORD_ENTITY_CONFLICT");
                if (!"Pin".equals(data.optString("__typename"))) return unproven(proof, "REQUESTED_RECORD_TYPE_CONFLICT");
                requestedRecords++;

                JSONObject pinJoin = data.optJSONObject("pinJoin");
                JSONObject canonicalPin = pinJoin == null ? null : pinJoin.optJSONObject("canonicalPin");
                String canonicalId = canonicalPin == null ? null : canonicalPin.optString("entityId", null);
                String seoCanonicalUrl = pinJoin == null ? null : pinJoin.optString("seoCanonicalUrl", null);
                String seoCanonicalId = pinId(seoCanonicalUrl);
                String sourceSeoId = pinId(data.optString("seoUrl", null));
                JSONObject row = new JSONObject()
                        .put("scriptIndex", scriptIndex)
                        .put("requestedEntityId", data.optString("entityId"))
                        .put("canonicalPinEntityId", canonicalId == null ? JSONObject.NULL : canonicalId)
                        .put("seoCanonicalUrl", seoCanonicalUrl == null ? JSONObject.NULL : seoCanonicalUrl)
                        .put("sourceSeoUrl", data.optString("seoUrl", null) == null ? JSONObject.NULL : data.optString("seoUrl"))
                        .put("locators", new JSONArray()
                                .put("script[" + scriptIndex + "].literalArgument[0].variables.pinId")
                                .put("script[" + scriptIndex + "].literalArgument[1].data.v3GetPinQueryv2.data.entityId")
                                .put("script[" + scriptIndex + "].literalArgument[1].data.v3GetPinQueryv2.data.pinJoin.canonicalPin.entityId")
                                .put("script[" + scriptIndex + "].literalArgument[1].data.v3GetPinQueryv2.data.pinJoin.seoCanonicalUrl"));
                bindings.put(row);
                if (canonicalId != null && !pageId.equals(canonicalId))
                    return unproven(proof.put("requestedRecordBindings", bindings), "EXPLICIT_CANONICAL_PIN_CONFLICT");
                if (canonicalId != null) explicitCanonicalBindings++;
                if (!pageId.equals(seoCanonicalId) || !expectedPinId.equals(sourceSeoId))
                    return unproven(proof.put("requestedRecordBindings", bindings), "EXPLICIT_ALIAS_BINDING_INCOMPLETE_OR_CONFLICTING");
            } catch (Exception e) {
                return unproven(proof.append("parseErrors", new JSONObject()
                        .put("scriptIndex", scriptIndex).put("reason", e.getClass().getSimpleName())), "PUBLIC_DATA_AMBIGUOUS");
            }
            scriptIndex++;
        }
        proof.put("requestedRecordCount", requestedRecords)
                .put("explicitCanonicalBindingCount", explicitCanonicalBindings)
                .put("requestedRecordBindings", bindings);
        if (requestedRecords == 0) return unproven(proof, "NO_REQUESTED_ID_BOUND_RECORD");
        if (explicitCanonicalBindings == 0) return unproven(proof, "NO_EXPLICIT_CANONICAL_PIN_BINDING");
        return finish("PROVEN_ALIAS", pageId, proof
                .put("evidenceClass", "REQUESTED_PIN_EXPLICIT_CANONICAL_PIN_BINDING")
                .put("aliasFromPinId", expectedPinId)
                .put("aliasToPinId", pageId));
    }

    private static Result unproven(JSONObject proof, String reason) throws Exception {
        return finish("UNPROVEN", null, proof.put("reason", reason));
    }

    private static Result finish(String status, String normalizedPinId, JSONObject proof) throws Exception {
        proof.put("status", status).put("normalizedPinId", normalizedPinId == null ? JSONObject.NULL : normalizedPinId);
        return new Result(status, normalizedPinId, proof);
    }

    private static String attr(String tag, String name) {
        Matcher m = ATTRIBUTE.matcher(tag);
        while (m.find()) if (m.group(1).equalsIgnoreCase(name)) return m.group(3).replace("&amp;", "&");
        return null;
    }

    static String pinId(String url) {
        if (url == null) return null;
        try {
            URI u = url.startsWith("/") ? new URI("https://www.pinterest.com" + url) : new URI(url);
            String h = u.getHost();
            if (!"https".equalsIgnoreCase(u.getScheme()) || h == null) return null;
            h = h.toLowerCase(Locale.ROOT);
            if (!(h.equals("pinterest.com") || h.endsWith(".pinterest.com") || h.equals("pinterest.co.kr") || h.endsWith(".pinterest.co.kr"))) return null;
            Matcher m = PIN_PATH.matcher(u.getPath());
            return m.matches() ? (m.group(1) != null ? m.group(1) : m.group(2)) : null;
        } catch (Exception e) { return null; }
    }
}
