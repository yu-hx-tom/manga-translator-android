package cn.local.manga;

import java.io.StringReader;
import java.io.StringWriter;
import java.util.Properties;
import java.util.LinkedHashMap;

/** Small local companion to a cached PNG; no image, URL or credential is stored here. */
final class PageOutcome {
    static final int MAX_ENCODED_SIZE=2*1024*1024;
    static void rememberRecent(LinkedHashMap<String,String> records,String key,String detail){
        records.remove(key);records.put(key,detail);
        while(records.size()>20)records.remove(records.keySet().iterator().next());
    }
    /** Current-attempt wall-clock observations only. Deliberately excluded from cached outcome serialization. */
    static final class Timings {
        final boolean cacheRestore,applied;
        final long queueMs,renderMs,pngMs,saveMs,replaceMs,totalMs;
        private Timings(boolean cacheRestore,boolean applied,long queueMs,long renderMs,long pngMs,long saveMs,long replaceMs,long totalMs){
            if(totalMs<0)throw new IllegalArgumentException("Invalid elapsed duration");
            long sum=0;for(long value:new long[]{queueMs,renderMs,pngMs,saveMs,replaceMs}){if(value< -1||value>totalMs)throw new IllegalArgumentException("Invalid stage duration");if(value>=0)sum=Math.addExact(sum,value);}
            if(sum>totalMs||(!cacheRestore&&applied&&(queueMs<0||renderMs<0||pngMs<0||saveMs<0||replaceMs<0)))throw new IllegalArgumentException("Incomplete successful timing");
            this.cacheRestore=cacheRestore;this.applied=applied;this.queueMs=queueMs;this.renderMs=renderMs;this.pngMs=pngMs;this.saveMs=saveMs;this.replaceMs=replaceMs;this.totalMs=totalMs;
        }
        static Timings text(long queueMs,long renderMs,long pngMs,long saveMs,long replaceMs,long totalMs,boolean applied){return new Timings(false,applied,queueMs,renderMs,pngMs,saveMs,replaceMs,totalMs);}
        static Timings cache(long totalMs,boolean applied){return new Timings(true,applied,-1,-1,-1,-1,-1,totalMs);}
        private static String elapsed(long value){return value<0?"未执行":value==0?"<1 毫秒":value<1000?value+" 毫秒":String.format(java.util.Locale.ROOT,"%.3f 秒",value/1000d);}
        String describe(){
            if(cacheRestore)return(applied?"缓存译图恢复耗时：":"缓存译图恢复未完成，已用：")+elapsed(totalMs)+"\n仅本次排队、读取缓存和网页回填；未重新请求翻译。";
            String title=applied?"模型返回至网页回填确认：":"模型返回后已用（网页回填未完成）：";
            return title+elapsed(totalMs)+"\n回填排队："+elapsed(queueMs)+(queueMs>=0?"；处理与回填："+elapsed(totalMs-queueMs):"")
                    +"\n去字与排版："+elapsed(renderMs)+"；PNG 编码："+elapsed(pngMs)
                    +"\n缓存保存："+elapsed(saveMs)+"；网页回填："+elapsed(replaceMs)
                    +"\n网页回填含 Base64、JS 往返及 applied 确认轮询。"+(applied?"":"未完成阶段仅记录已用时间，不代表成功。")
                    +"\n不含模型请求等待；若命中文字缓存，则从本地译文就绪开始。";
        }
    }
    final int detected,succeeded,failed,skipped,preservedOriginal;
    final String detail;
    final boolean known;
    final boolean needsCleanupRetry;
    final TranslationTranscript transcript;
    PageOutcome(int detected,int succeeded,int failed,int skipped,String detail){
        this(detected,succeeded,failed,skipped,0,detail);
    }
    PageOutcome(int detected,int succeeded,int failed,int skipped,int preservedOriginal,String detail){
        this(detected,succeeded,failed,skipped,preservedOriginal,detail,TranslationTranscript.oldCache());
    }
    PageOutcome(int detected,int succeeded,int failed,int skipped,int preservedOriginal,String detail,TranslationTranscript transcript){
        this(detected,succeeded,failed,skipped,preservedOriginal,detail,transcript,false);
    }
    PageOutcome(int detected,int succeeded,int failed,int skipped,int preservedOriginal,String detail,TranslationTranscript transcript,boolean needsCleanupRetry){
        if(detected<0||succeeded<0||failed<0||skipped<0||detected>100000
                ||preservedOriginal<0||preservedOriginal>succeeded
                ||(long)succeeded+failed+skipped>detected)throw new IllegalArgumentException("Invalid page outcome");
        this.detected=detected;this.succeeded=succeeded;this.failed=failed;this.skipped=skipped;
        this.preservedOriginal=preservedOriginal;
        this.transcript=transcript==null?TranslationTranscript.unavailable():transcript;
        this.needsCleanupRetry=needsCleanupRetry;
        String text=detail==null?"":detail.trim();this.detail=text.substring(0,Math.min(1600,text.length()));known=true;
    }
    private PageOutcome(){detected=succeeded=failed=skipped=preservedOriginal=0;detail="该译图没有有效的段落统计，可停止后手选网页图片检查。";known=false;needsCleanupRetry=false;transcript=TranslationTranscript.oldCache();}
    static PageOutcome unknown(){return new PageOutcome();}
    boolean incomplete(){return !known||failed>0||skipped>0||needsCleanupRetry;}
    String describe(){
        if(!known)return detail;
        String counts="检测 "+detected+" 段，回填 "+succeeded+" 段，失败 "+failed+" 段，跳过 "+skipped+" 段";
        if(preservedOriginal>0)counts+="；其中 "+preservedOriginal+" 段兜底嵌字，原笔画仍保留";
        return detail.startsWith("检测 ")?detail:counts+(detail.isEmpty()?"":"\n"+detail);
    }
    String encode()throws Exception{
        Properties values=new Properties();values.setProperty("version","1");
        values.setProperty("detected",String.valueOf(detected));values.setProperty("succeeded",String.valueOf(succeeded));
        values.setProperty("failed",String.valueOf(failed));values.setProperty("skipped",String.valueOf(skipped));
        values.setProperty("preservedOriginal",String.valueOf(preservedOriginal));
        values.setProperty("needsCleanupRetry",String.valueOf(needsCleanupRetry));
        values.setProperty("detail",detail);transcript.writeTo(values);
        StringWriter writer=new StringWriter();values.store(writer,"Local page result");return writer.toString();
    }
    static PageOutcome decode(String text){
        if(text==null||text.length()>MAX_ENCODED_SIZE)return unknown();
        try{
            Properties values=new Properties();values.load(new StringReader(text));
            if(!"1".equals(values.getProperty("version")))return unknown();
            return new PageOutcome(Integer.parseInt(values.getProperty("detected")),Integer.parseInt(values.getProperty("succeeded")),
                    Integer.parseInt(values.getProperty("failed")),Integer.parseInt(values.getProperty("skipped")),
                    Integer.parseInt(values.getProperty("preservedOriginal","0")),values.getProperty("detail",""),TranslationTranscript.readFrom(values),Boolean.parseBoolean(values.getProperty("needsCleanupRetry","false")));
        }catch(Exception invalid){return unknown();}
    }
}
