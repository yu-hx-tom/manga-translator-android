package cn.local.manga;

import org.json.JSONArray;
import org.json.JSONObject;
import java.io.*;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.StandardCopyOption;
import java.util.*;

/** Local bookmark/history data. Accessed on BrowserLibrary's single I/O worker. */
final class LibraryStore {
    static final long MAX_BYTES=8L*1024*1024;
    static final class Entry {
        final String url; String title,bookmarkTitle; long visitedAt,bookmarkedAt,visits;
        Entry(String url,String title,String bookmarkTitle,long visitedAt,long bookmarkedAt){this.url=url;this.title=title;this.bookmarkTitle=bookmarkTitle;this.visitedAt=visitedAt;this.bookmarkedAt=bookmarkedAt;}
        String displayTitle(boolean bookmarks){return bookmarks&&!bookmarkTitle.isEmpty()?bookmarkTitle:title;}
        Entry copy(){Entry copy=new Entry(url,title,bookmarkTitle,visitedAt,bookmarkedAt);copy.visits=visits;return copy;}
    }
    interface Persistence { JSONObject load()throws Exception;void save(JSONObject data)throws Exception; }
    private final File file;
    private final Persistence persistence;
    private JSONObject original=new JSONObject();
    private final Map<String,JSONObject> rawEntries=new HashMap<>();
    private final LinkedHashMap<String,Entry> entries=new LinkedHashMap<>();
    private final LinkedHashMap<String,JSONObject> shortcuts=new LinkedHashMap<>();
    LibraryStore(File file)throws Exception{
        this.file=file;this.persistence=null;
        if(!file.exists())return;
        if(file.length()>MAX_BYTES)throw new IOException("浏览记录文件过大，请备份后处理");
        JSONObject data=new JSONObject(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));
        load(data);
    }
    LibraryStore(Persistence persistence)throws Exception{this.file=null;this.persistence=persistence;load(persistence.load());}
    private void load(JSONObject data)throws Exception{
        original=data;
        if(data.getInt("version")!=1)throw new IOException("不支持的浏览记录格式，原文件已保留");
        JSONArray rows=data.getJSONArray("entries");
        for(int i=0;i<rows.length();i++){
            JSONObject row=rows.getJSONObject(i);String url=row.getString("url");
            if(!isWebUrl(url)||entries.containsKey(url))throw new IOException("浏览记录格式无效，原文件已保留");
            long visited=row.getLong("visitedAt"),saved=row.getLong("bookmarkedAt");
            if(visited<0||saved<0)throw new IOException("浏览记录时间无效，原文件已保留");
            Entry entry=new Entry(url,row.getString("title"),row.optString("bookmarkTitle"),visited,saved);entry.visits=row.optLong("visits",visited>0?1:0);entries.put(url,entry);rawEntries.put(url,row);
        }
        JSONArray tiles=data.optJSONArray("shortcuts");if(tiles!=null)for(int i=0;i<tiles.length();i++){JSONObject tile=tiles.getJSONObject(i);shortcuts.put(tile.getString("key"),tile);}
    }
    static boolean isWebUrl(String value){
        if(value==null||value.isEmpty()||value.length()>8192||!value.equals(value.trim()))return false;
        try{URI uri=new URI(value);return ("http".equalsIgnoreCase(uri.getScheme())||"https".equalsIgnoreCase(uri.getScheme()))
            &&uri.getHost()!=null&&uri.getUserInfo()==null&&uri.getPort()!=0&&uri.getPort()<=65535;}
        catch(Exception invalid){return false;}
    }
    private static String title(String value,String url){String text=value==null?"":value.trim();return text.isEmpty()?url:text.substring(0,Math.min(512,text.length()));}
    private Entry entry(String url,String name){
        if(!isWebUrl(url))throw new IllegalArgumentException("只支持收藏或记录正常的 http/https 网页");
        return entries.computeIfAbsent(url,key->new Entry(url,title(name,url),"",0,0));
    }
    synchronized void visit(String name,String url,long now)throws Exception{
        Entry e=entry(url,name);e.title=title(name,url);e.visitedAt=Math.max(1,now);e.visits++;save();
    }
    synchronized void bookmark(String name,String url,long now)throws Exception{
        Entry e=entry(url,name);
        if(e.bookmarkedAt==0){e.bookmarkedAt=Math.max(1,now);e.bookmarkTitle=title(name,url);}save();
    }
    synchronized void rename(String url,String name)throws Exception{
        Entry e=entries.get(url);if(e==null||e.bookmarkedAt==0)throw new IOException("收藏已经不存在");
        e.bookmarkTitle=title(name,url);save();
    }
    synchronized void remove(String url,boolean bookmark)throws Exception{
        Entry e=entries.get(url);if(e==null)return;
        if(bookmark){e.bookmarkedAt=0;e.bookmarkTitle="";}else{e.visitedAt=0;e.visits=0;}
        if(e.visitedAt==0&&e.bookmarkedAt==0)entries.remove(url);save();
    }
    synchronized void clearHistory()throws Exception{
        entries.values().removeIf(e->e.bookmarkedAt==0);for(Entry e:entries.values()){e.visitedAt=0;e.visits=0;}save();
    }
    synchronized List<Entry> list(boolean bookmarks,String query,int offset,int limit){
        if(offset<0||limit<1||limit>101)throw new IllegalArgumentException("Invalid paging bounds");
        String term=query==null?"":query.trim().toLowerCase(Locale.ROOT);List<Entry> found=new ArrayList<>();
        for(Entry e:entries.values())if((bookmarks?e.bookmarkedAt:e.visitedAt)>0
                &&(term.isEmpty()||e.displayTitle(bookmarks).toLowerCase(Locale.ROOT).contains(term)||e.url.toLowerCase(Locale.ROOT).contains(term)))found.add(e.copy());
        found.sort(Comparator.<Entry>comparingLong(e->bookmarks?e.bookmarkedAt:e.visitedAt).reversed().thenComparing(e->e.url));
        if(offset>=found.size())return new ArrayList<>();
        return new ArrayList<>(found.subList(offset,Math.min(found.size(),offset+limit)));
    }
    static String siteKey(String url){
        if(!isWebUrl(url))throw new IllegalArgumentException("请输入 http 或 https 网站地址");
        URI uri=URI.create(url);String host=uri.getHost().toLowerCase(Locale.ROOT).replaceFirst("^(www\\.|m\\.)","");
        return host+((uri.getPort()==-1||uri.getPort()==80||uri.getPort()==443)?"":":"+uri.getPort());
    }
    synchronized JSONArray frequentSites()throws Exception{
        Map<String,JSONObject> sites=new LinkedHashMap<>();
        for(Entry e:entries.values())if(e.visitedAt>0&&!e.url.startsWith(BrowserAddress.HOME)){
            String key=siteKey(e.url);URI uri=URI.create(e.url);JSONObject tile=sites.get(key);
            if(tile==null){tile=new JSONObject().put("key",key).put("title",key).put("count",0).put("last",0);sites.put(key,tile);}
            tile.put("count",tile.optLong("count")+Math.max(1,e.visits));
            if((uri.getPath()==null||uri.getPath().isEmpty()||uri.getPath().equals("/"))&&!e.title.equals(e.url)&&e.visitedAt>=tile.optLong("titleAt"))tile.put("title",e.title).put("titleAt",e.visitedAt);
            if(e.visitedAt>=tile.optLong("last"))tile.put("last",e.visitedAt).put("url",uri.getScheme()+"://"+uri.getRawAuthority()+"/");
        }
        if(sites.isEmpty())for(String url:new String[]{"https://www.google.com/","https://zh.wikipedia.org/","https://www.youtube.com/"}){String key=siteKey(url);sites.put(key,new JSONObject().put("key",key).put("url",url).put("title",key).put("count",0).put("last",0));}
        for(Map.Entry<String,JSONObject> item:shortcuts.entrySet()){
            JSONObject config=item.getValue();if(config.optBoolean("hidden")){sites.remove(item.getKey());continue;}
            JSONObject tile=sites.get(item.getKey());if(tile==null){tile=new JSONObject().put("key",item.getKey());sites.put(item.getKey(),tile);}
            tile.put("title",config.getString("title")).put("url",config.getString("url")).put("pinned",config.optBoolean("pinned"));
        }
        List<JSONObject> sorted=new ArrayList<>(sites.values());sorted.sort(Comparator.<JSONObject>comparingInt(t->t.optBoolean("pinned")?0:1).thenComparing(Comparator.comparingLong((JSONObject t)->t.optLong("count")).reversed()).thenComparing(Comparator.comparingLong((JSONObject t)->t.optLong("last")).reversed()).thenComparing(t->t.optString("key")));
        List<JSONObject> visible=new ArrayList<>();int automatic=0;for(JSONObject tile:sorted)if(tile.optBoolean("pinned")||automatic++<12)visible.add(tile);return new JSONArray(visible);
    }
    synchronized void editShortcut(String previous,String name,String url,boolean pinned,boolean hidden)throws Exception{
        String key=siteKey(url);if(name.trim().isEmpty())name=key;
        if(previous!=null&&!previous.isEmpty()&&!previous.equals(key))shortcuts.put(previous,(shortcuts.containsKey(previous)?new JSONObject(shortcuts.get(previous).toString()):new JSONObject()).put("key",previous).put("hidden",true));
        JSONObject config=shortcuts.containsKey(key)?new JSONObject(shortcuts.get(key).toString()):new JSONObject();
        shortcuts.put(key,config.put("key",key).put("title",name.substring(0,Math.min(80,name.length()))).put("url",url).put("pinned",pinned).put("hidden",hidden));save();
    }
    private void save()throws Exception{
        JSONArray rows=new JSONArray();for(Entry e:entries.values())rows.put((rawEntries.containsKey(e.url)?new JSONObject(rawEntries.get(e.url).toString()):new JSONObject()).put("url",e.url).put("title",e.title)
            .put("bookmarkTitle",e.bookmarkTitle).put("visitedAt",e.visitedAt).put("bookmarkedAt",e.bookmarkedAt).put("visits",e.visits));
        JSONObject data=new JSONObject(original.toString()).put("version",1).put("entries",rows).put("shortcuts",new JSONArray(shortcuts.values()));
        byte[] bytes=data.toString().getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_BYTES)throw new IOException("浏览记录存储已满，请先清理历史记录");
        if(persistence!=null){persistence.save(data);original=data;return;}
        File parent=file.getParentFile();if(!parent.isDirectory()&&!parent.mkdirs())throw new IOException("无法保存浏览记录");
        File temporary=new File(parent,file.getName()+".tmp");
        try{
            try(FileOutputStream stream=new FileOutputStream(temporary)){stream.write(bytes);stream.getFD().sync();}
            Files.move(temporary.toPath(),file.toPath(),StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);
        }finally{Files.deleteIfExists(temporary.toPath());}
    }
}
