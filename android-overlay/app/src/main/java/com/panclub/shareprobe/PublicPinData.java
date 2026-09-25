package com.panclub.shareprobe;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;

import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Experimental public-response evidence reader. No Pinterest API requests or JS execution. */
public final class PublicPinData {
    private static final Pattern SCRIPT = Pattern.compile("(?is)<script\\b([^>]*)>(.*?)</script\\s*>");
    private static final String WRAPPER = "window.__PWS_RELAY_REGISTER_COMPLETED_REQUEST__(";
    private PublicPinData() {}

    public static final class Result {
        public final String status, destination;
        public final JSONObject evidence;
        Result(String status, String destination, JSONObject evidence) {
            this.status = status; this.destination = destination; this.evidence = evidence;
        }
    }

    public static Result inspect(String html, String requestUrl, String expectedPinId) throws Exception {
        JSONObject proof = new JSONObject().put("requestUrl",requestUrl).put("expectedPinId",expectedPinId)
                .put("responseBodySha256",sha(html)).put("policy","EVIDENCE_WAIT_NOT_PRODUCTION_ENABLED");
        PinIdentityResolver.Result identity=PinIdentityResolver.resolve(html,requestUrl,expectedPinId);
        JSONArray pageIdentities=identity.evidence.optJSONArray("pageIdentityUrls");
        proof.put("identityResolution",identity.evidence)
                .put("pageIdentities",pageIdentities==null?new JSONArray():pageIdentities);
        if ("UNPROVEN".equals(identity.status)) return result("PAGE_IDENTITY_UNVERIFIED",null,proof);
        JSONArray records=new JSONArray(); proof.put("records",records);
        List<String> links=new ArrayList<>(); boolean parseError=false; int scriptNumber=0;
        Matcher scripts=SCRIPT.matcher(html);
        while (scripts.find()) {
            if (scripts.group(1).contains("data-relay-completed-request")) {
                try {
                    String body=scripts.group(2).trim();
                    if (!body.startsWith(WRAPPER)) throw new IllegalArgumentException("UNKNOWN_WRAPPER");
                    JSONTokener tokens=new JSONTokener(body.substring(WRAPPER.length()));
                    Object keyRaw=tokens.nextValue();
                    if (!(keyRaw instanceof String) || tokens.nextClean()!=',') throw new IllegalArgumentException("INVALID_ARGUMENTS");
                    JSONObject key=new JSONObject(URLDecoder.decode((String)keyRaw,StandardCharsets.UTF_8.name()));
                    Object second=tokens.nextValue();
                    if (!(second instanceof JSONObject) || tokens.nextClean()!=')' || tokens.nextClean()!=';' || tokens.nextClean()!=0) throw new IllegalArgumentException("INVALID_TRAILING_CODE");
                    JSONObject data=((JSONObject)second).getJSONObject("data").getJSONObject("v3GetPinQueryv2").getJSONObject("data");
                    if (!expectedPinId.equals(data.optString("entityId"))) {scriptNumber++;continue;}
                    if (!"Pin".equals(data.optString("__typename")) || !expectedPinId.equals(key.getJSONObject("variables").optString("pinId"))) throw new IllegalArgumentException("PIN_IDENTITY_CONFLICT");
                    String locator="script["+scriptNumber+"].literalArgument[1].data.v3GetPinQueryv2.data.link";
                    JSONObject row=new JSONObject().put("entityId",data.getString("entityId")).put("locator",locator);
                    if (!data.has("link")) row.put("linkState","MISSING");
                    else if (data.isNull("link")) row.put("linkState","EXPLICIT_NULL");
                    else if (!(data.opt("link") instanceof String)) row.put("linkState","INVALID_TYPE");
                    else { row.put("linkState","FOUND").put("link",data.getString("link")); links.add(data.getString("link")); }
                    records.put(row);
                } catch (Exception e) {
                    parseError=true;
                    proof.append("parseErrors",new JSONObject().put("scriptIndex",scriptNumber).put("reason",e.getClass().getSimpleName()));
                }
            }
            scriptNumber++;
        }
        if (parseError) return result("PUBLIC_DATA_AMBIGUOUS",null,proof);
        if (records.length()==0) return result("NO_ID_BOUND_PIN_RECORD",null,proof);
        if (links.isEmpty()) {
            for (int i=0;i<records.length();i++) if (!"EXPLICIT_NULL".equals(records.getJSONObject(i).getString("linkState"))) return result("LINK_MISSING_OR_AMBIGUOUS",null,proof);
            return result("PIN_LINK_EXPLICIT_NULL",null,proof);
        }
        if (links.size()!=records.length()) return result("LINK_MISSING_OR_AMBIGUOUS",null,proof);
        String link=links.get(0);
        if (!external(link)) return result("LINK_INVALID",null,proof);
        for(String value:links) if(!link.equals(value)) return result("CONFLICTING_PIN_LINKS",null,proof);
        return result("DESTINATION_OBSERVED_TECHNICAL_ONLY",link,proof);
    }

    private static Result result(String status,String link,JSONObject p) throws Exception {
        p.put("status",status).put("destinationUrl",link==null?JSONObject.NULL:link);
        return new Result(status,link,p);
    }
    private static boolean external(String url) {
        try {
            URI u=new URI(url);String h=u.getHost();
            if (!"https".equalsIgnoreCase(u.getScheme()) || h==null || u.getUserInfo()!=null || (u.getPort()!=-1 && u.getPort()!=443)) return false;
            h=h.toLowerCase(Locale.ROOT);
            if (!h.contains(".") || h.matches("[0-9.]+") || h.contains(":")) return false;
            return !h.equals("pin.it") && !h.equals("pinterest.com") && !h.endsWith(".pinterest.com") && !h.equals("pinterest.co.kr") && !h.endsWith(".pinterest.co.kr") && !h.equals("pinimg.com") && !h.endsWith(".pinimg.com");
        } catch(Exception e) {return false;}
    }
    private static String sha(String value) throws Exception {
        byte[] bytes=MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
        StringBuilder s=new StringBuilder();for(byte b:bytes)s.append(String.format(Locale.ROOT,"%02x",b&255));return s.toString();
    }
}
