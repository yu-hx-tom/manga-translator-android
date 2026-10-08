package cn.local.manga;

import org.json.*;
import java.awt.*;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.security.MessageDigest;
import java.util.*;
import javax.imageio.ImageIO;

/** Explicitly authorized, bounded live probe. API credential is read into memory and never persisted or printed. */
public final class LiveImageCleanupProbe {
    static String hash(byte[] bytes)throws Exception{return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));}
    static int[][] boxes(JSONArray values){int[][] result=new int[values.length()][4];for(int i=0;i<result.length;i++)for(int j=0;j<4;j++)result[i][j]=values.getJSONArray(i).getInt(j);return result;}
    static void save(Path path,JSONObject value)throws Exception{Files.writeString(path,value.toString(2)+"\n",StandardCharsets.UTF_8);}
    public static void main(String[] args)throws Exception{
        Path configPath=Paths.get(args[0]),project=Paths.get(args[1]),out=Paths.get(args[2]);Files.createDirectories(out);
        String configText=Files.readString(configPath,StandardCharsets.UTF_8);if(configText.startsWith("\ufeff"))configText=configText.substring(1);
        JSONObject config=new JSONObject(configText);configText=null;
        if(!config.optBoolean("enabled")||config.optInt("port")!=50254)throw new Exception("Local API is disabled or its port differs from the authorized endpoint");
        String selected=config.optString("imageGenerationModel","gpt-image-2.5");if(!selected.equals("gpt-image-2.5"))throw new Exception("Configured image model differs: "+selected);
        AppSettings settings=new AppSettings();settings.baseUrl="http://127.0.0.1:50254/v1";settings.apiKey=config.optString("apiKey","");settings.imageModel=selected;
        settings.maxRetries=0;settings.requestTimeoutSeconds=args[3].equals("probe")?15:600;settings.retryIntervalSeconds=1;settings.rateLimitWaitSeconds=1;
        config=null;if(settings.apiKey.isBlank())throw new Exception("No primary API credential is configured");
        if(args[3].equals("probe")){
            long started=System.currentTimeMillis();JSONObject report=new JSONObject().put("action","GET /models").put("endpoint",settings.baseUrl).put("configuredImageModel",selected).put("maxRetries",0);
            try{java.util.List<String> models=ApiClient.listModels(settings,()->false);report.put("reachable",true).put("modelCount",models.size()).put("selectedModelListed",models.contains(selected));}
            catch(Exception failure){report.put("reachable",false).put("failure",failure.toString().replace(settings.apiKey,"[redacted]"));}
            report.put("elapsedMs",System.currentTimeMillis()-started);save(out.resolve("models_probe.json"),report);System.out.println(report.toString());return;
        }
        if(!args[3].equals("run"))throw new Exception("Use probe or run");
        JSONObject manifest=new JSONObject(Files.readString(out.resolve("samples.json"),StandardCharsets.UTF_8));String id=args[4];JSONObject sample=null;
        for(Object item:manifest.getJSONArray("samples"))if(item instanceof JSONObject&&((JSONObject)item).getString("id").equals(id))sample=(JSONObject)item;
        if(sample==null)throw new Exception("Sample is not explicitly listed");
        Path sampleDir=out.resolve(id);Files.createDirectories(sampleDir);Path resultReport=sampleDir.resolve("request_result.json");
        if(Files.exists(resultReport)||Files.exists(sampleDir.resolve("request_started.json")))throw new Exception("This sample already has an attempt record; automatic repeats are disabled");
        Path source=project.resolve("tests/0.9.0验证/分类基准").resolve(sample.getString("roiFile"));byte[] sourceBytes=Files.readAllBytes(source);BufferedImage original=ImageIO.read(new ByteArrayInputStream(sourceBytes));
        if(original==null)throw new Exception("ROI is not decodable");int width=original.getWidth(),height=original.getHeight();int[][] targets=boxes(sample.getJSONArray("targetBoxes")),protectedBoxes=boxes(sample.getJSONArray("protectedBoxes"));
        Path crop=sampleDir.resolve("input_roi.png");Files.write(crop,sourceBytes);Files.writeString(sampleDir.resolve("cleanup_prompt.txt"),ImageCleanup.prompt(width,height,targets,protectedBoxes),StandardCharsets.UTF_8);
        JSONObject report=new JSONObject().put("sample",sample).put("inputWidth",width).put("inputHeight",height).put("inputSha256",hash(sourceBytes)).put("endpoint",settings.baseUrl+"/images/edits")
            .put("model",selected).put("maxRetries",0).put("requestTimeoutSeconds",settings.requestTimeoutSeconds).put("productionMethod","ApiClient.cleanImagePrepared")
            .put("nativeAndroidRuntime",false).put("boundsAdapter","ImageIO metadata adapter; production HTTP/multipart/response parsing")
            .put("inputScope","ROI only; no full page uploaded").put("promptVersion",ImageCleanup.PROMPT_VERSION).put("startedAt",java.time.Instant.now().toString());
        save(sampleDir.resolve("request_started.json"),report);long started=System.currentTimeMillis();
        System.out.println("START "+id+" "+width+"x"+height+" model="+selected+" maxRetries=0");System.out.flush();
        try{
            byte[] response=ApiClient.cleanImagePrepared(settings,crop.toFile(),width,height,targets,protectedBoxes,()->Files.exists(out.resolve("CANCEL")));
            Files.write(sampleDir.resolve("returned_original.bin"),response);BufferedImage returned=ImageIO.read(new ByteArrayInputStream(response));
            if(returned==null)throw new Exception("Returned bytes passed header bounds but full ImageIO pixel decode failed");
            String extension=ImageCleanup.pngSignature(response)?"png":(response.length>2&&(response[0]&255)==255&&(response[1]&255)==216)?"jpg":"webp";
            Files.move(sampleDir.resolve("returned_original.bin"),sampleDir.resolve("returned_original."+extension));
            BufferedImage scaled=new BufferedImage(width,height,BufferedImage.TYPE_INT_ARGB);Graphics2D resize=scaled.createGraphics();resize.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_BICUBIC);resize.drawImage(returned,0,0,width,height,null);resize.dispose();ImageIO.write(scaled,"png",sampleDir.resolve("returned_resized.png").toFile());
            int scale=Math.max(1,Math.min(4,800/Math.max(width,height)));BufferedImage comparison=new BufferedImage(width*scale*2+18,height*scale+40,BufferedImage.TYPE_INT_RGB);Graphics2D g=comparison.createGraphics();g.setColor(Color.WHITE);g.fillRect(0,0,comparison.getWidth(),comparison.getHeight());g.setColor(Color.BLACK);g.setFont(new Font("SansSerif",Font.PLAIN,14));g.drawString("Original ROI",4,22);g.drawString("AI output resized",width*scale+20,22);g.setRenderingHint(RenderingHints.KEY_INTERPOLATION,RenderingHints.VALUE_INTERPOLATION_NEAREST_NEIGHBOR);g.drawImage(original,0,36,width*scale,height*scale,null);g.drawImage(scaled,width*scale+18,36,width*scale,height*scale,null);g.dispose();ImageIO.write(comparison,"png",sampleDir.resolve("comparison.png").toFile());
            report.put("success",true).put("returnedWidth",returned.getWidth()).put("returnedHeight",returned.getHeight()).put("returnedBytes",response.length).put("returnedSha256",hash(response)).put("resizedWidth",width).put("resizedHeight",height)
                .put("nativeProtectedCompositeApplied",false).put("visualReview","pending");
        }catch(Exception failure){report.put("success",false).put("failure",failure.toString().replace(settings.apiKey,"[redacted]")).put("retriesAttempted",0);}
        report.put("elapsedMs",System.currentTimeMillis()-started).put("completedAt",java.time.Instant.now().toString());save(resultReport,report);
        System.out.println("RESULT "+id+" success="+report.getBoolean("success")+" elapsedMs="+report.getLong("elapsedMs")+(report.has("failure")?" failure="+report.getString("failure"):" returned="+report.getInt("returnedWidth")+"x"+report.getInt("returnedHeight")));
    }
}
