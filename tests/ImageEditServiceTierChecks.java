package cn.local.manga;

import android.graphics.Bitmap;
import com.sun.net.httpserver.HttpServer;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;

/** Both production image entry points submit the same selected tier and never downgrade after refusal. */
public final class ImageEditServiceTierChecks {
    static int checks;
    static void ok(boolean value,String why){checks++;if(!value)throw new AssertionError(why);}
    public static void main(String[] args)throws Exception{
        Path root=Paths.get(args[0]);Files.createDirectories(root);Path png=root.resolve("fixture.png");Files.write(png,ImageCleanupApiChecks.png(32,48));
        List<String> bodies=Collections.synchronizedList(new ArrayList<>());
        HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);
        server.createContext("/v1/images/edits",exchange->{try{
            bodies.add(new String(exchange.getRequestBody().readAllBytes(),StandardCharsets.UTF_8));byte[] reply="{\"error\":{\"param\":\"service_tier\",\"message\":\"selected tier unsupported by fixture\"}}".getBytes(StandardCharsets.UTF_8);
            exchange.sendResponseHeaders(400,reply.length);exchange.getResponseBody().write(reply);
        }finally{exchange.close();}});server.start();
        AppSettings settings=new AppSettings();settings.baseUrl="http://127.0.0.1:"+server.getAddress().getPort()+"/v1";settings.apiKey="localhost-tier-fixture";settings.mode="image";settings.maxRetries=5;
        Bitmap bitmap=new Bitmap(32,48,new int[32*48]);
        try{
            for(String tier:List.of("auto","default","priority"))for(boolean cleanup:new boolean[]{false,true}){
                settings.serviceTier=tier;int before=bodies.size();boolean rejected=false;
                try{
                    if(cleanup)ApiClient.cleanImagePrepared(settings,png.toFile(),32,48,ImageCleanupApiChecks.TARGETS,ImageCleanupApiChecks.PROTECTED,()->false);
                    else ApiClient.edit(settings,bitmap,()->false);
                }catch(ApiClient.RequestFailure expected){rejected=expected.status==400;}
                ok(rejected&&bodies.size()==before+1,"unsupported tier surfaces once without retry or tier downgrade: "+tier+" cleanup="+cleanup);
                String sent=bodies.get(before);
                ok(sent.contains("name=\"model\"\r\n\r\n"+settings.imageModel+"\r\n"),"configured image model is used by both paths");
                ok(tier.equals("auto")?!sent.contains("name=\"service_tier\""):sent.contains("name=\"service_tier\"\r\n\r\n"+tier+"\r\n"),"selected tier has identical wire behavior in image translation and cleanup");
                ok(sent.contains("name=\"image\"; filename=")&&sent.contains("Content-Type: image/png"),"real bitmap/prepared file reaches multipart image field");
            }
        }finally{bitmap.recycle();server.stop(0);}
        System.out.println("ImageEditServiceTierChecks: "+checks+" checks passed (real production multipart and loopback refusal; desktop PNG adapter only)");
    }
}
