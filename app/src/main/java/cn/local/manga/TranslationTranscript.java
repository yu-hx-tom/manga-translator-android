package cn.local.manga;

import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.List;
import java.util.Properties;

/** Local, bounded recognition evidence. Never inferred from a translation or sent to diagnostics. */
public final class TranslationTranscript {
    static final int MAX_ROWS=512, MAX_TEXT_CHARS=256000, MAX_ORIGINAL=2000;
    public final List<Row> rows;
    public final String mode;
    public final boolean truncated;

    public static final class Row {
        public final String id,originalText,zh,status,originalStatus,error;
        public final int left,top,right,bottom;
        public final boolean vertical;
        Row(String id,String originalText,String zh,String status,String originalStatus,String error,
            int left,int top,int right,int bottom,boolean vertical){
            if(id==null||id.isEmpty()||id.length()>160||originalText==null||originalText.length()>MAX_ORIGINAL
                    ||zh==null||zh.length()>1000||error==null||error.length()>240||left<0||top<0||right<left||bottom<top
                    ||right>100000||bottom>100000||!oneOf(status,"translated","nearby","in_place","image_cleaned","skipped","failed","image_translated")
                    ||!oneOf(originalStatus,"available","missing","invalid","old_cache","unreadable","image_mode")
                    ||("available".equals(originalStatus)!=!originalText.isEmpty()))throw new IllegalArgumentException("Invalid transcript row");
            this.id=id;this.originalText=originalText;this.zh=zh;this.status=status;this.originalStatus=originalStatus;this.error=error;
            this.left=left;this.top=top;this.right=right;this.bottom=bottom;this.vertical=vertical;
        }
        public String statusLabel(){
            switch(status){case "translated":return "本地去字，已回填";case "nearby":return "附近嵌字，保留原笔画";case "in_place":return "原位嵌字，保留原笔画";case "image_cleaned":return "修图去字，已回填";case "skipped":return "已跳过";case "image_translated":return "已回填译图";default:return "未完成，保留原图";}
        }
        public String originalLabel(){
            switch(originalStatus){case "available":return originalText;case "old_cache":return "旧缓存未记录原文";case "invalid":return "模型原文字段格式异常，未采用";case "unreadable":return "模型未能辨认原文";case "image_mode":return "图像模式不返回识读原文";default:return "模型未返回识读原文";}
        }
    }

    TranslationTranscript(String mode,List<Row> source){this(mode,source,false);}
    private TranslationTranscript(String mode,List<Row> source,boolean wasTruncated){
        if(!oneOf(mode,"text","image","old_cache","unavailable"))throw new IllegalArgumentException("Invalid transcript mode");
        ArrayList<Row> copy=new ArrayList<>();HashSet<String> ids=new HashSet<>();int chars=0;boolean limited=wasTruncated;
        for(Row row:source){
            int size=row.id.length()+row.originalText.length()+row.zh.length()+row.error.length();
            if(copy.size()>=MAX_ROWS||chars+size>MAX_TEXT_CHARS){limited=true;break;}
            if(!ids.add(row.id))throw new IllegalArgumentException("Duplicate transcript id");
            copy.add(row);chars+=size;
        }
        this.mode=mode;this.rows=Collections.unmodifiableList(copy);this.truncated=limited;
    }
    public static TranslationTranscript oldCache(){return new TranslationTranscript("old_cache",Collections.emptyList());}
    public static TranslationTranscript unavailable(){return new TranslationTranscript("unavailable",Collections.emptyList());}
    public String describe(){
        if("old_cache".equals(mode))return "此旧缓存未记录识读原文，已保留已有译图；查看不会重新请求翻译。";
        if("unavailable".equals(mode))return "本页暂无识读记录。";
        StringBuilder text=new StringBuilder("image".equals(mode)?"图像模式仅返回译图，没有识读原文和文字译文。":"原文由本次翻译模型识读，可能有误；坐标对应处理时的原图。旧缓存不会自动补发识字请求。");
        if(rows.isEmpty())text.append("\n没有可展示的文字段落。");
        for(Row row:rows){
            text.append("\n\n").append(row.id).append(" · ").append(row.statusLabel())
                .append("\n位置：(").append(row.left).append(", ").append(row.top).append(")–(").append(row.right).append(", ").append(row.bottom).append(")")
                .append(row.vertical?"，竖排":"，横排").append("\n原文：").append(row.originalLabel())
                .append("\n译文：").append(row.zh.isEmpty()?"（无文字译文）":row.zh);
            if(!row.error.isEmpty())text.append("\n说明：").append(row.error);
        }
        if(truncated)text.append("\n\n识读记录过长，仅保留前面部分；已生成的译图不受影响。");
        return text.toString();
    }
    void writeTo(Properties values){
        values.setProperty("transcript.version","1");values.setProperty("transcript.mode",mode);
        values.setProperty("transcript.count",String.valueOf(rows.size()));values.setProperty("transcript.truncated",String.valueOf(truncated));
        for(int i=0;i<rows.size();i++){
            Row row=rows.get(i);String p="transcript."+i+".";
            values.setProperty(p+"id",row.id);values.setProperty(p+"original",row.originalText);values.setProperty(p+"zh",row.zh);
            values.setProperty(p+"status",row.status);values.setProperty(p+"originalStatus",row.originalStatus);values.setProperty(p+"error",row.error);
            values.setProperty(p+"bounds",row.left+","+row.top+","+row.right+","+row.bottom);values.setProperty(p+"vertical",String.valueOf(row.vertical));
        }
    }
    static TranslationTranscript readFrom(Properties values){
        if(!values.containsKey("transcript.version"))return oldCache();
        try{
            if(!"1".equals(values.getProperty("transcript.version")))return unavailable();
            int count=Integer.parseInt(values.getProperty("transcript.count"));if(count<0||count>MAX_ROWS)return unavailable();
            List<Row> rows=new ArrayList<>();
            for(int i=0;i<count;i++){
                String p="transcript."+i+".";String[] box=values.getProperty(p+"bounds","").split(",");if(box.length!=4)return unavailable();
                String vertical=values.getProperty(p+"vertical");if(!oneOf(vertical,"true","false"))return unavailable();
                rows.add(new Row(values.getProperty(p+"id"),values.getProperty(p+"original"),values.getProperty(p+"zh"),
                    values.getProperty(p+"status"),values.getProperty(p+"originalStatus"),values.getProperty(p+"error"),
                    Integer.parseInt(box[0]),Integer.parseInt(box[1]),Integer.parseInt(box[2]),Integer.parseInt(box[3]),Boolean.parseBoolean(vertical)));
            }
            return new TranslationTranscript(values.getProperty("transcript.mode"),rows,Boolean.parseBoolean(values.getProperty("transcript.truncated")));
        }catch(Exception invalid){return unavailable();}
    }
    private static boolean oneOf(String value,String... choices){for(String choice:choices)if(choice.equals(value))return true;return false;}
}
