package cn.local.manga;

import java.awt.image.BufferedImage;
import java.nio.file.*;
import javax.imageio.ImageIO;
import org.json.*;

/** Input-adapter checks only; no real network request or visual acceptance. */
public final class WebsiteReviewInputChecks {
    static int checks;
    static void require(boolean okay,String reason){checks++;if(!okay)throw new AssertionError(reason);}
    public static void main(String[] args)throws Exception{
        Path project=Paths.get(args[0]),root=Paths.get(args[1]),input=root.resolve("input"),out=root.resolve("output");
        System.setProperty("manga.review.sourceDir",project.resolve("app/src/main/java/cn/local/manga").toString());
        System.setProperty("manga.review.paragraphBounds","true");System.setProperty("manga.review.precomputedRegions","true");
        System.setProperty("manga.review.pageCount","36");System.setProperty("manga.review.freshTranslations","true");
        for(int page=1;page<=36;page++){
            Path dir=input.resolve("逐页").resolve(String.format("第%02d页",page));Files.createDirectories(dir);
            BufferedImage image=new BufferedImage(64,80,BufferedImage.TYPE_INT_RGB);ImageIO.write(image,"png",dir.resolve("原图.png").toFile());
            JSONObject region=new JSONObject().put("id","rt_1").put("box",new JSONArray(new int[]{5,6,35,60})).put("lines",new JSONArray().put(new JSONArray(new int[]{10,12,20,50}))).put("vertical",true).put("contextBox",JSONObject.NULL);
            JSONObject regions=new JSONObject().put("schemaVersion",1).put("width",64).put("height",80).put("sourceSha256",BatchMangaTranslationReview.hash(dir.resolve("原图.png"))).put("regions",new JSONArray().put(region));
            BatchMangaTranslationReview.save(dir.resolve("段落检测.json"),regions);
            BatchMangaTranslationReview.save(dir.resolve("检测原始结果.json"),new JSONObject().put("boxes",new JSONArray()).put("box_types",new JSONArray()).put("scores",new JSONArray()));
            BatchMangaTranslationReview.save(dir.resolve("真实译文.json"),new JSONObject().put("translations",new JSONArray()));
            require(BatchMangaTranslationReview.readRegions(image,dir).get(0).lines.get(0).top==12,"exact stored region preserved, raw predictions not regrouped");
        }
        Files.createDirectories(out);BatchMangaTranslationReview.prepare(input,out,project);
        JSONObject plan=BatchMangaTranslationReview.json(out.resolve("translation_plan.json"));
        require(plan.getJSONArray("pages").length()==36,"pages 31-36 not dropped");
        require(plan.getJSONObject("counts").getInt("missingTranslation")==36,"missing text cannot be passed as translated");
        require(!plan.getBoolean("cachedRealTranslations"),"new text provenance flag");
        require(plan.getJSONArray("pages").getJSONObject(35).has("precomputedRegionsSha256"),"stored regions bound to SHA");
        Path first=input.resolve("逐页/第01页"),regionFile=first.resolve("段落检测.json");
        JSONObject bad=BatchMangaTranslationReview.json(regionFile).put("sourceSha256","changed");BatchMangaTranslationReview.save(regionFile,bad);
        try{BatchMangaTranslationReview.readRegions(ImageIO.read(first.resolve("原图.png").toFile()),first);throw new AssertionError("accepted changed source identity");}catch(IllegalStateException expected){checks++;}
        try{BatchMangaTranslationReview.render(input,out,project);throw new AssertionError("accepted changed detector record");}catch(Exception expected){require(expected.getMessage().contains("Detection evidence changed"),"changed detector evidence rejected before rendering");}
        System.out.println("WebsiteReviewInputChecks: "+checks+" checks passed (36 synthetic inputs, no network)");
    }
}
