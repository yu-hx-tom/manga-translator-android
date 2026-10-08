package cn.local.manga;

import org.json.*;
import java.util.*;

/** Canonical equality is independent of object property order; array order remains significant. */
final class StorageJson {
    static String canonical(Object value)throws JSONException {
        if(value instanceof JSONObject){JSONObject row=(JSONObject)value;TreeSet<String> keys=new TreeSet<>();row.keys().forEachRemaining(keys::add);StringBuilder out=new StringBuilder("{");for(String key:keys){if(out.length()>1)out.append(',');out.append(JSONObject.quote(key)).append(':').append(canonical(row.get(key)));}return out.append('}').toString();}
        if(value instanceof JSONArray){JSONArray rows=(JSONArray)value;StringBuilder out=new StringBuilder("[");for(int i=0;i<rows.length();i++){if(i>0)out.append(',');out.append(canonical(rows.get(i)));}return out.append(']').toString();}
        if(value==null||value==JSONObject.NULL)return "null";
        if(value instanceof String)return JSONObject.quote((String)value);
        if(value instanceof Number)return JSONObject.numberToString((Number)value);
        if(value instanceof Boolean)return value.toString();
        throw new JSONException("未知的 JSON 值类型");
    }
    static void same(Object expected,Object actual,String label)throws Exception {if(!canonical(expected).equals(canonical(actual)))throw new java.io.IOException("迁移核对不一致："+label);}
    static JSONObject copy(JSONObject source)throws JSONException{return new JSONObject(source.toString());}
    static JSONObject emptyLibrary()throws JSONException{return new JSONObject().put("version",1).put("entries",new JSONArray()).put("shortcuts",new JSONArray());}
    static void validateLibrary(JSONObject source)throws Exception {
        if(source.getInt("version")!=1)throw new java.io.IOException("浏览记录版本不受支持，旧文件保持原样");
        Set<String> urls=new HashSet<>();JSONArray rows=source.getJSONArray("entries");
        for(int i=0;i<rows.length();i++){JSONObject row=rows.getJSONObject(i);String url=row.getString("url");if(!LibraryStore.isWebUrl(url)||!urls.add(url))throw new java.io.IOException("浏览记录第 "+(i+1)+" 条无效或重复，未迁移");
            if(row.getLong("visitedAt")<0||row.getLong("bookmarkedAt")<0||row.optLong("visits",row.getLong("visitedAt")>0?1:0)<0)throw new java.io.IOException("浏览记录计数或时间无效");
            if(row.getString("title").length()>512||row.optString("bookmarkTitle").length()>512)throw new java.io.IOException("浏览记录标题超出旧格式限制，未迁移");}
        Set<String> keys=new HashSet<>();JSONArray tiles=source.optJSONArray("shortcuts");if(tiles!=null)for(int i=0;i<tiles.length();i++){JSONObject tile=tiles.getJSONObject(i);String key=tile.getString("key");if(key.isEmpty()||!keys.add(key))throw new java.io.IOException("常用网站重复或无标识，未迁移");if(!tile.optBoolean("hidden")&&(!LibraryStore.isWebUrl(tile.getString("url"))||tile.getString("title").length()>80))throw new java.io.IOException("常用网站字段无效，未迁移");}
    }
    private StorageJson(){}
}
