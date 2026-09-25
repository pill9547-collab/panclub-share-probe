package com.panclub.shareprobe;

import org.json.JSONObject;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

public class PinIdentityResolverTest {
    static int passed;
    static final String REQUESTED="211174976645508";
    static final String CANONICAL="567523990553474836";
    static final String REQUEST_URL="https://kr.pinterest.com/pin/211174976645508/";
    static final String DESTINATION="https://www.tasteofhome.com/collection/vintage-christmas-cakes/?trkid=soc-toh-pinterest-lp";

    static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);System.out.println("PASS "+name);passed++;}
    static String page(String pageId,String scripts){
        String url="https://www.pinterest.com/pin/fixture--"+pageId+"/";
        return "<meta property='og:url' content='"+url+"'><link rel='canonical' href='"+url+"'>"+scripts;
    }
    static String record(String keyPinId,String entityId,String canonicalId,String seoCanonicalId,String sourceSeoId,String link)throws Exception{
        JSONObject key=new JSONObject().put("variables",new JSONObject().put("pinId",keyPinId));
        JSONObject pinJoin=new JSONObject();
        if(canonicalId!=null)pinJoin.put("canonicalPin",new JSONObject().put("entityId",canonicalId));
        if(seoCanonicalId!=null)pinJoin.put("seoCanonicalUrl","/pin/fixture--"+seoCanonicalId+"/");
        JSONObject data=new JSONObject().put("__typename","Pin").put("entityId",entityId).put("pinJoin",pinJoin);
        if(sourceSeoId!=null)data.put("seoUrl","/pin/source--"+sourceSeoId+"/");
        if(link!=null)data.put("link",link);
        JSONObject value=new JSONObject().put("data",new JSONObject().put("v3GetPinQueryv2",new JSONObject().put("data",data)));
        String encoded=URLEncoder.encode(key.toString(),StandardCharsets.UTF_8.name());
        return "<script data-relay-completed-request='true'>window.__PWS_RELAY_REGISTER_COMPLETED_REQUEST__(\""+encoded+"\", "+value+");</script>";
    }
    static String provenFixture()throws Exception{return page(CANONICAL,
            record(REQUESTED,REQUESTED,null,CANONICAL,REQUESTED,DESTINATION)+
            record(REQUESTED,REQUESTED,CANONICAL,CANONICAL,REQUESTED,DESTINATION));}

    public static void main(String[] args)throws Exception{
        PinIdentityResolver.Result r=PinIdentityResolver.resolve(provenFixture(),REQUEST_URL,REQUESTED);
        check("PROVEN_ALIAS".equals(r.status)&&CANONICAL.equals(r.normalizedPinId),"real-phone Pin 211174976645508 explicit canonical alias to 567523990553474836");
        check("REQUESTED_PIN_EXPLICIT_CANONICAL_PIN_BINDING".equals(r.evidence.getString("evidenceClass")),"alias uses requested-ID-bound first-party fields");
        check(r.evidence.getInt("requestedRecordCount")==2&&r.evidence.getInt("explicitCanonicalBindingCount")==1,"missing duplicate field is tolerated only beside one explicit consistent binding");
        PublicPinData.Result publicData=PublicPinData.inspect(provenFixture(),REQUEST_URL,REQUESTED);
        check("DESTINATION_OBSERVED_TECHNICAL_ONLY".equals(publicData.status)&&DESTINATION.equals(publicData.destination),"proven alias continues destination resolution");
        check("EVIDENCE_WAIT_NOT_PRODUCTION_ENABLED".equals(publicData.evidence.getString("policy")),"Pinterest policy remains evidence-wait");
        String shortUrl="https://pin.it/1YrYox7oT", api="https://api.pinterest.com/url_shortener/1YrYox7oT/redirect/";
        String sent="https://kr.pinterest.com/pin/211174976645508/sent/?invite_code=fixture&sender=823033038060606833&sfo=1";
        Map<String,SharePipeline.Response> chain=new HashMap<>();
        chain.put(shortUrl,new SharePipeline.Response(308,api,"text/html",""));
        chain.put(api,new SharePipeline.Response(302,sent,"text/html",""));
        chain.put(sent,new SharePipeline.Response(200,null,"text/html",provenFixture()));
        chain.put(DESTINATION,new SharePipeline.Response(200,null,"text/html","<html></html>"));
        JSONObject pipeline=new SharePipeline(url->chain.get(url)).run(new JSONObject()
                .put("action","android.intent.action.SEND").put("mimeType","text/plain").put("text",shortUrl));
        check(DESTINATION.equals(pipeline.getString("destinationUrl"))&&"COLLECTION_NOT_SINGLE_RECIPE".equals(pipeline.getString("status")),"exact pin.it fixture passes identity and reaches recipe READ without adopting another Pin");

        String unrelatedOnly=page(CANONICAL,record(CANONICAL,CANONICAL,CANONICAL,CANONICAL,CANONICAL,DESTINATION));
        check("UNPROVEN".equals(PinIdentityResolver.resolve(unrelatedOnly,REQUEST_URL,REQUESTED).status),"unrelated candidate record cannot prove alias");
        String adjacent=record(REQUESTED,REQUESTED,null,CANONICAL,REQUESTED,null)+record(CANONICAL,CANONICAL,CANONICAL,CANONICAL,CANONICAL,DESTINATION);
        check("UNPROVEN".equals(PinIdentityResolver.resolve(page(CANONICAL,adjacent),REQUEST_URL,REQUESTED).status),"adjacent canonical Pin cannot supply missing requested-record binding");
        check("UNPROVEN".equals(PinIdentityResolver.resolve(page(CANONICAL,record(REQUESTED,REQUESTED,CANONICAL,"999",REQUESTED,DESTINATION)),REQUEST_URL,REQUESTED).status),"conflicting explicit canonical evidence blocks");
        String similarity="<title>same title</title><img src='same.jpg'><a href='"+DESTINATION+"'>same domain</a>";
        check("UNPROVEN".equals(PinIdentityResolver.resolve(page(CANONICAL,similarity),REQUEST_URL,REQUESTED).status),"title image domain and visual similarity are never identity proof");

        String pin559="559290847491964725";
        String direct=page(pin559,record(pin559,pin559,"422281209284761","422281209284761",pin559,"https://thrivingnest.com/recipe"));
        PinIdentityResolver.Result unchanged=PinIdentityResolver.resolve(direct,"https://kr.pinterest.com/pin/"+pin559+"/",pin559);
        check("SAME_ID".equals(unchanged.status)&&pin559.equals(unchanged.normalizedPinId),"existing Pin 559 direct identity remains unchanged");
        System.out.println("TOTAL "+passed+" Pin identity checks");
    }
}
