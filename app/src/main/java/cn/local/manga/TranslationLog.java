package cn.local.manga;

import org.json.JSONObject;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.text.SimpleDateFormat;
import java.util.*;

/** Local bounded diagnostics. No screenshots, prompt, API body, credential or full page URL. */
final class TranslationLog {
    private static final Object LOCK=new Object();
    private static final long LIMIT=512*1024;
    private final File directory;
    TranslationLog(File directory){this.directory=directory;}
    static String clean(String text,String key){
        String s=text==null?"":text;if(key!=null&&!key.isEmpty())s=s.replace(key,"[隐藏]");
        s=s.replaceAll("(?i)(?:https?://|data:image/)[^\\s]+","[地址/图像已省略]")
            .replaceAll("(?i)(?:Bearer\\s+|(?:sk-|agt_)[A-Za-z0-9_-]*)[A-Za-z0-9._-]*","[凭据已隐藏]")
            .replaceAll("[\\r\\n\\t]+"," ");
        return s.substring(0,Math.min(1600,s.length()));
    }
    void record(String trace,String region,String stage,String reason,AppSettings settings){
        try{
            String key=settings==null?"":settings.apiKey;
            JSONObject event=new JSONObject().put("time",new SimpleDateFormat("yyyy-MM-dd'T'HH:mm:ss.SSSZ",Locale.ROOT).format(new Date()))
                .put("version","1.0.0").put("task",clean(trace,key)).put("region",clean(region,key)).put("stage",clean(stage,key))
                .put("reason",clean(reason,key));
            if(settings!=null)event.put("detector",clean(settings.detectorModel,key)).put("mode",clean(settings.mode,key))
                .put("model",clean("image".equals(settings.mode)?settings.imageModel:settings.textModel,key));
            if(settings!=null&&"text".equals(settings.mode))event.put("protocol",ApiClient.textEndpoint(settings))
                .put("reasoning",settings.reasoningEffort).put("serviceTier",settings.requestedServiceTier())
                .put("maxRetries",settings.maxRetries).put("retryIntervalSeconds",settings.retryIntervalSeconds);
            byte[] bytes=(event.toString()+"\n").getBytes(StandardCharsets.UTF_8);
            synchronized(LOCK){
                if(!directory.isDirectory()&&!directory.mkdirs())return;
                File current=new File(directory,"current.jsonl"),previous=new File(directory,"previous.jsonl");
                if(current.length()+bytes.length>LIMIT){if(previous.exists()&&!previous.delete())return;if(!current.renameTo(previous))return;}
                try(FileOutputStream out=new FileOutputStream(current,true)){out.write(bytes);}
            }
        }catch(Exception ignored){/* A diagnostics write must never discard a translated page. */}
    }
    String read()throws IOException{
        synchronized(LOCK){StringBuilder text=new StringBuilder();for(String name:new String[]{"previous.jsonl","current.jsonl"}){File file=new File(directory,name);if(file.isFile())text.append(new String(Files.readAllBytes(file.toPath()),StandardCharsets.UTF_8));}return text.toString();}
    }
    void export(OutputStream out)throws IOException{out.write(read().getBytes(StandardCharsets.UTF_8));PerformanceDiagnostics.export(out);StorageDiagnostics.export(directory,out);}
}
