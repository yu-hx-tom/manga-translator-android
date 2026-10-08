package cn.local.manga;
import java.util.*;
public final class SearchTermsChecks {
    static int checks;
    static void check(boolean ok,String label){if(!ok)throw new AssertionError(label);checks++;}
    public static void main(String[] args)throws Exception{
        String json="[]";for(int i=0;i<12;i++)json=SearchTerms.add(json,"关键词 "+i,8);
        List<String> terms=SearchTerms.read(json,8);
        check(terms.size()==8,"eight terms maximum");check(terms.get(0).equals("关键词 11")&&terms.get(7).equals("关键词 4"),"newest first, oldest evicted");
        json=SearchTerms.add(json,"  关键词 6  ",8);terms=SearchTerms.read(json,8);
        check(terms.size()==8&&terms.get(0).equals("关键词 6")&&new HashSet<>(terms).size()==8,"duplicate promoted, trimmed");
        check(SearchTerms.add(json," ",8).equals(json),"empty ignored");check(SearchTerms.add(json,"x".repeat(257),8).equals(json),"overlong ignored");
        String deleted=SearchTerms.remove(json,"关键词 6",8);check(!SearchTerms.read(deleted,8).contains("关键词 6")&&SearchTerms.read(deleted,8).size()==7,"delete persists exactly one term");
        check(SearchTerms.remove(deleted,"不存在",8).equals(deleted),"repeat or absent deletion harmless");
        check(SearchTerms.remove("[\"仅一条\"]","仅一条",8).equals("[]"),"last deletion empties strip");
        check(SearchTerms.read(SearchTerms.add(deleted,"关键词 6",8),8).get(0).equals("关键词 6"),"explicit new search can re-add deleted term");
        check(SearchTerms.read("[null,7,{},\"你好\",\"你好\"]",8).equals(List.of("你好")),"untrusted JSON values rejected");
        check(SearchTerms.read("broken",8).isEmpty(),"damaged input tolerated");
        check(SearchTerms.read(SearchTerms.add("[]","<b>\"词条\"</b>",8),8).get(0).equals("<b>\"词条\"</b>"),"literal punctuation round trip");
        json="[]";for(int i=0;i<130;i++)json=SearchTerms.add(json,"query"+i,100);check(SearchTerms.read(json,100).size()==100,"omnibox separately bounded");
        check(SearchTerms.matchesNavigation("https://site.test/search?q=3D","https://site.test","q","3D"),"navigation confirms alphanumeric term");
        check(SearchTerms.matchesNavigation("https://site.test/search?keyword=%E6%A1%82%E4%BA%95","https://site.test","keyword","桂井"),"navigation confirms Chinese term");
        check(SearchTerms.matchesNavigation("https://site.test/find?mySearch=two+words","https://site.test","mySearch","two words"),"custom field and spaces");
        check(!SearchTerms.matchesNavigation("https://site.test/search?q=old","https://site.test","q","new"),"unsubmitted edit is not recorded");
        check(!SearchTerms.matchesNavigation("https://other.test/search?q=3D","https://site.test","q","3D"),"different origin not recorded");
        check(!SearchTerms.matchesNavigation("https://site.test/read?title=3D","https://site.test","q","3D"),"unrelated navigation not recorded");
        check(!SearchTerms.matchesNavigation("https://site.test/?q=%GG","https://site.test","q","3D"),"malformed query rejected");
        if(args.length>0){
            org.json.JSONObject fixture=new org.json.JSONObject(java.nio.file.Files.readString(java.nio.file.Path.of(args[0])));
            org.json.JSONArray events=fixture.getJSONArray("events");String saved="[]",origin="",field="",candidate="";boolean firstPair=false;
            for(int i=0;i<events.length();i++){
                org.json.JSONObject event=events.getJSONObject(i);String op=event.getString("op");
                if(op.equals("candidate")){origin=event.getString("origin");field=event.optString("field");candidate=event.getString("term");}
                if(op.equals("save"))saved=SearchTerms.add(saved,event.getString("term"),8);
                if(op.equals("navigation")&&SearchTerms.matchesNavigation(event.getString("url"),origin,field,candidate)){saved=SearchTerms.add(saved,candidate,8);candidate="";}
                if(op.equals("save")&&event.optString("term").equals("桂井")){check(SearchTerms.read(saved,8).containsAll(List.of("3D","桂井")),"3D and 桂井 both retained after distinct submit paths");firstPair=true;}
            }
            check(firstPair,"both reported user examples exercised");
            List<String> actual=SearchTerms.read(saved,8);check(actual.equals(List.of("城市","森林","猫咪","星空","旅行","冒险","连载","漫画")),"actual browser event sequence accumulates eight in exact MRU order");
            check(!actual.contains("只输入未搜索"),"transient candidate is not persisted without search");
        }
        System.out.println("PASS "+checks+" search history checks");
    }
}
