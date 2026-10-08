package cn.local.manga;

import java.io.File;
import java.nio.file.*;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicInteger;

/** Real mask algorithms + disk handoff, without Android runtime or a paid translation endpoint. */
public final class CleanupPlanChecks {
    private static int checks;
    private static void ok(boolean value,String message){if(!value)throw new AssertionError(message);checks++;}
    private static void same(WhiteBubbleCleaner.Mask a,WhiteBubbleCleaner.Mask b){
        ok(b!=null,"ready mask available");
        ok(a.whiteBackground==b.whiteBackground&&a.texturedBackground==b.texturedBackground&&a.pixels==b.pixels,"mask decisions unchanged");
        ok(a.backgroundKind==b.backgroundKind&&a.evidence.equals(b.evidence),"background classification and evidence survive exact roundtrip");
        ok(Arrays.equals(a.erase,b.erase)&&Arrays.equals(a.interior,b.interior),"erase and layout area are exact");
        ok(Arrays.equals(a.fillColors,b.fillColors)&&Arrays.equals(a.glyphCandidate,b.glyphCandidate),"paper colors and optional candidates are exact");
    }
    private static int[] fixture(int w,int h,int paper){
        int[] pixels=new int[w*h];Arrays.fill(pixels,0xff555555);
        for(int y=8;y<h-8;y++)for(int x=8;x<w-8;x++)pixels[y*w+x]=0xff000000;
        for(int y=10;y<h-10;y++)for(int x=10;x<w-10;x++)pixels[y*w+x]=paper;
        for(int y=35;y<145;y+=22)for(int yy=y;yy<y+11;yy++)for(int x=64;x<76;x++)pixels[yy*w+x]=0xff000000;
        return pixels;
    }
    public static void main(String[] args)throws Exception{
        Path root=Files.createTempDirectory(Paths.get(args[0]),"cleanup-check-");
        int w=140,h=180;int[][] lines={{58,29,82,153}};
        File directory=Files.createDirectory(root.resolve("job")).toFile();CleanupPlan cache=new CleanupPlan(directory);
        WhiteBubbleCleaner.Mask accepted=null;
        for(int i=0;i<3;i++){
            int[] original=fixture(w,h,i==0?0xffffffff:i==1?0xffc4c4c4:0xffd4a18c);
            WhiteBubbleCleaner.Mask mask=WhiteBubbleCleaner.forText(original,w,h,lines,24);
            ok(mask.whiteBackground==(i<2),"fixture accepted/refused as expected");
            ok(cache.save(i,w,h,mask,()->false),"per-region atomic save");
            WhiteBubbleCleaner.Mask loaded=cache.load(i,w,h,()->false);same(mask,loaded);
            int[] before=original.clone(),after=original.clone();WhiteBubbleCleaner.apply(before,mask);WhiteBubbleCleaner.apply(after,loaded);
            ok(Arrays.equals(before,after),"synchronous and precomputed cleanup pixels match");
            int[][] foreign={{62,33,78,47}};
            ok(WhiteBubbleCleaner.touchesForeignInk(original,w,h,mask,foreign)==WhiteBubbleCleaner.touchesForeignInk(original,w,h,loaded,foreign),"foreign ink protection survives handoff");
            ok(Arrays.equals(WhiteBubbleCleaner.excludeForeign(mask.interior,w,h,foreign),WhiteBubbleCleaner.excludeForeign(loaded.interior,w,h,foreign)),"foreign layout protection survives handoff");
            if(mask.whiteBackground){
                BubbleLayout.Plan expected=BubbleLayout.plan("测试译文",mask.interior,w,h,true,24,lines),actual=BubbleLayout.plan("测试译文",loaded.interior,w,h,true,24,lines);
                ok(expected.font==actual.font&&Arrays.deepEquals(expected.cells,actual.cells)&&Arrays.equals(expected.codepoints,actual.codepoints),"normal layout unchanged");
            }
            if(i==0)accepted=mask;
        }
        ok(cache.prepared()==3&&cache.refused()==1&&cache.hits()==3,"accepted/refused/used counts");
        WhiteBubbleCleaner.Mask candidate=new WhiteBubbleCleaner.Mask(accepted.erase,accepted.interior,false,accepted.pixels,null,true,accepted.erase.clone());
        ok(cache.save(3,w,h,candidate,()->false),"textured refusal save");same(candidate,cache.load(3,w,h,()->false));
        for(WhiteBubbleCleaner.BackgroundKind kind:WhiteBubbleCleaner.BackgroundKind.values()){
            WhiteBubbleCleaner.Mask classified=new WhiteBubbleCleaner.Mask(accepted.erase,accepted.interior,kind==WhiteBubbleCleaner.BackgroundKind.PLAIN_PAPER,accepted.pixels,
                accepted.fillColors,kind==WhiteBubbleCleaner.BackgroundKind.TEXTURED_ART,accepted.erase.clone(),kind,"分类证据："+kind.name()+" / original-local-text");
            int index=10+kind.ordinal();ok(cache.save(index,w,h,classified,()->false),"every background class can be serialized: "+kind);same(classified,cache.load(index,w,h,()->false));
        }
        Path damaged=directory.toPath().resolve("cleanup-0.bin");byte[] good=Files.readAllBytes(damaged),changed=good.clone();changed[23]^=0x40;Files.write(damaged,changed);
        ok(cache.load(0,w,h,()->false)==null,"checksum corruption falls back");
        Files.write(damaged,Arrays.copyOf(good,good.length-1));ok(cache.load(0,w,h,()->false)==null,"truncated file falls back");
        Files.write(damaged,good);ok(cache.load(0,w+1,h,()->false)==null,"dimension mismatch falls back");
        ok(cache.load(90,w,h,()->false)==null,"missing region does not block rendering");
        ok(cache.load(0,0,h,()->false)==null,"bad dimensions do not allocate");
        ok(!cache.save(4,w,h,accepted,()->true),"already cancelled save does nothing");
        AtomicInteger cancellationChecks=new AtomicInteger();boolean cancelled=false;
        try{cache.save(5,w,h,accepted,()->cancellationChecks.incrementAndGet()>3);}catch(CancellationException expected){cancelled=true;}
        ok(cancelled,"cancel during serialization honored");
        ok(!Files.exists(directory.toPath().resolve("cleanup-5.bin"))&&!Files.exists(directory.toPath().resolve("cleanup-5.bin.tmp")),"cancel leaves no partial mask");
        cancelled=false;try{cache.load(1,w,h,()->true);}catch(CancellationException expected){cancelled=true;}
        ok(cancelled,"cancel during reload honored");
        Thread.currentThread().interrupt();ok(cache.stopped(),"worker interrupt stops more regions");Thread.interrupted();
        cache.startRendering();ok(!cache.save(6,w,h,accepted,()->false),"render starts without waiting for future masks");
        same(accepted,cache.load(0,w,h,()->false));
        cache.close();ok(!directory.exists(),"close removes source job directory");
        ok(!cache.save(7,w,h,accepted,()->false)&&!directory.exists(),"late commit never recreates a closed directory");
        cache.close();ok(cache.load(0,w,h,()->false)==null,"close is idempotent and late reads miss");

        // Simulate close while an expensive mask is being computed outside the file-commit lock.
        File lateDir=Files.createDirectory(root.resolve("late-job")).toFile();CleanupPlan late=new CleanupPlan(lateDir);
        CountDownLatch computing=new CountDownLatch(1),finish=new CountDownLatch(1);WhiteBubbleCleaner.Mask finalMask=accepted;
        ExecutorService worker=Executors.newSingleThreadExecutor();
        try{
            Future<Boolean> commit=worker.submit(()->{computing.countDown();finish.await();return late.save(0,w,h,finalMask,()->false);});
            ok(computing.await(2,TimeUnit.SECONDS),"background work started");
            late.close();ok(!lateDir.exists(),"close does not wait for background pixel work");
            finish.countDown();ok(!commit.get(2,TimeUnit.SECONDS)&&!lateDir.exists(),"racing computation cannot resurrect files");
        }finally{finish.countDown();worker.shutdownNow();late.close();}
        Files.delete(root);
        System.out.println("CleanupPlanChecks: "+checks+" checks passed");
    }
}
