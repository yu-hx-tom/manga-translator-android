package cn.local.manga;

import java.util.*;

/** UI-thread queue: all loaded images are retained as metadata, only resource-admitted work is submitted. */
final class AutoTranslationQueue {
    static final long MIB=1024L*1024;
    static final class Image {
        final String id,url,key; final boolean visible,ahead,nearViewport,translated,pending; final int order; final long memoryBytes,restorationBytes;
        long reservedBytes;
        boolean cacheOnly;
        int retryMode; // 0 automatic, 1 retry missing text, 2 explicitly request fresh translation.
        String viewKey;
        Image(String id,String url,boolean visible,boolean translated,int order){this(id,url,visible,!visible,translated,false,order,800,1200);}
        Image(String id,String url,boolean visible,boolean translated,boolean pending,int order){this(id,url,visible,!visible,translated,pending,order,800,1200);}
        Image(String id,String url,boolean visible,boolean ahead,boolean translated,boolean pending,int order,int width,int height){
            this(id,url,visible,ahead,translated,pending,order,width,height,false);
        }
        Image(String id,String url,boolean visible,boolean ahead,boolean translated,boolean pending,int order,int width,int height,boolean imageMode){
            this(id,url,visible,ahead,translated,pending,order,width,height,imageMode,false);
        }
        Image(String id,String url,boolean visible,boolean ahead,boolean translated,boolean pending,int order,int width,int height,boolean imageMode,boolean nearViewport){
            // Image responses permit 16MP (64MB decoded), plus response/Base64/JSON copies; this is a byte reservation, not a task cap.
            this.id=id;this.url=url;this.key=id+"\n"+url;this.visible=visible;this.ahead=ahead;this.nearViewport=nearViewport;this.translated=translated;this.pending=pending;this.order=order;restorationBytes=estimateMemory(width,height);memoryBytes=restorationBytes+(imageMode?128*MIB:0)+(url.startsWith("manga-canvas:")?64*MIB:0);reservedBytes=memoryBytes;
        }
    }
    static long estimateMemory(int width,int height){
        if(width<=0||height<=0)return 128*MIB;
        double scale=Math.min(1d,6000d/Math.max(width,height));scale=Math.min(scale,Math.sqrt(8_000_000d/((long)width*height)));
        long pixels=Math.max(1,(long)(width*scale))*Math.max(1,(long)(height*scale));
        // Source, working image, encoding/bridge copies and detector/region scratch. Budget, not an allocation guarantee.
        return 16*MIB+24*pixels;
    }
    static long availableMemory(long maximum,long used){return Math.max(0,maximum-used-Math.max(24*MIB,maximum/6));}
    static long storageBudget(long cached,long usable){return Math.max(0,(cached+usable-256*MIB)/2);}
    static final class Record { Image image; int attempts,retryMode; long retryAt; boolean running,noText,completed,tooLarge,cacheMissing,terminalFailure; String attemptContext="",restoredView=""; }
    private final Map<String,Record> records=new HashMap<>();
    private List<Image> candidates=new ArrayList<>();
    private volatile Map<String,Integer> priorities=Collections.emptyMap();
    private int epoch,completed,concurrency=1;
    private int textTarget;
    private long growAt=1000,cooldownUntil;
    private boolean memoryBlocked;
    private boolean storageBlocked;
    private String viewKey="";
    private boolean requestedOnly;
    int epoch(){return epoch;}
    void requestedOnly(boolean value){requestedOnly=value;}
    void restartUnfinished(){
        for(Record r:records.values())if(!r.running&&!r.completed&&!r.noText){
            r.attempts=0;r.retryAt=0;r.terminalFailure=r.tooLarge=false;
            if(r.image!=null)r.retryMode=Math.max(1,Math.max(r.retryMode,r.image.retryMode));
        }
    }
    /** Re-admit only this page, without resetting the epoch or disturbing other active work. */
    boolean retry(Image image,boolean fresh){
        Record r=records.computeIfAbsent(image.key,k->new Record());
        if(r.running)return false; // Already requesting: do not issue a duplicate paid request.
        if(r.retryMode!=0){r.retryMode=Math.max(r.retryMode,fresh?2:1);return false;}
        if(r.completed){r.completed=false;completed--;}
        r.image=image;r.retryMode=fresh?2:1;r.attempts=0;r.retryAt=0;
        r.noText=r.tooLarge=r.cacheMissing=r.terminalFailure=false;r.restoredView="";
        if(candidates.stream().noneMatch(x->x.key.equals(image.key)))candidates.add(image);
        return true;
    }
    void scan(List<Image> images){
        candidates=new ArrayList<>(images);
        Set<String> found=new HashSet<>();for(Image image:images)found.add(image.key);
        for(Record r:records.values())if(r.retryMode!=0&&r.image!=null&&found.add(r.image.key))candidates.add(r.image);
        candidates.sort(Comparator.comparing((Image x)->!x.visible).thenComparingInt(x->x.order));
        Map<String,Integer> next=new HashMap<>();for(Image image:images)next.put(image.key,image.visible?0:image.nearViewport?1:image.ahead?2:3);priorities=Collections.unmodifiableMap(next);
        StringBuilder view=new StringBuilder();for(Image image:candidates)if(image.visible||image.nearViewport)view.append(image.visible?'V':'N').append(image.key).append('\0');viewKey=view.toString();
    }
    List<Image> take(long now,long availableBytes){
        return take(now,availableBytes,Long.MAX_VALUE);
    }
    List<Image> take(long now,long availableBytes,long maximumHeap){
        List<Image> result=new ArrayList<>();memoryBlocked=false;if(now<cooldownUntil)return result;
        if(now>=growAt){concurrency=Math.min(textTarget>0?textTarget:Math.max(1,candidates.size()),concurrency+1);growAt=now+1000;}
        long remaining=availableBytes;int active=0;Set<String> seen=new HashSet<>(),urls=new HashSet<>();
        candidates.sort(Comparator.comparing((Image x)->{Record r=records.get(x.key);return r==null||r.retryMode==0;}).thenComparing(x->!x.visible).thenComparingInt(x->x.order));
        for(Image image:runningImages()){active++;urls.add(image.url);remaining=Math.max(0,remaining-image.reservedBytes);}
        for(Image image:candidates){
            // A newly visible text page can enter while background jobs wait on HTTP. Actual requests stay at the configured target.
            Record r=records.computeIfAbsent(image.key,k->new Record());
            if(active>=concurrency&&(textTarget<=0||!image.visible||active>=concurrency+2))continue;
            if(!seen.add(image.key)||urls.contains(image.url)||(requestedOnly&&r.retryMode==0)||((image.translated||image.pending)&&r.retryMode==0))continue;
            if(r.completed&&!r.cacheMissing&&!r.running){String context=viewKey+(image.visible?"visible":"near");if(!context.equals(r.attemptContext)){r.attemptContext=context;r.attempts=0;r.retryAt=0;}}
            if(r.running||r.noText||r.terminalFailure||r.attempts>=2||now<r.retryAt||(r.completed&&!image.visible&&(!image.nearViewport||r.cacheMissing)))continue;
            if(r.completed&&!image.visible&&viewKey.equals(r.restoredView))continue;
            image.cacheOnly=r.completed&&!r.cacheMissing;long pixelPeak=image.cacheOnly?image.restorationBytes:image.memoryBytes;
            // Text jobs hold only disk paths + bounded response metadata while HTTP waits. Pixel phases have their own gate.
            image.reservedBytes=textTarget>0?4*MIB:pixelPeak;
            if(storageBlocked&&!image.cacheOnly)continue;
            r.tooLarge=pixelPeak>availableMemory(maximumHeap,0);if(r.tooLarge)continue;
            if(image.reservedBytes>remaining){memoryBlocked=true;continue;}
            image.retryMode=r.retryMode;image.viewKey=viewKey;r.image=image;r.running=true;r.attempts++;active++;remaining-=image.reservedBytes;result.add(image);urls.add(image.url);
        }return result;
    }
    void finish(Image image,int token,boolean success,boolean noText,long now){
        finish(image,token,success,noText,now,true);
    }
    void finish(Image image,int token,boolean success,boolean noText,long now,boolean allowPageRetry){
        if(token!=epoch)return;Record r=records.get(image.key);if(r==null||!r.running)return;
        r.running=false;r.noText=noText;r.terminalFailure=!success&&!allowPageRetry;
        if(success){if(!r.completed){completed++;r.completed=true;}if(image.cacheOnly)r.restoredView=image.viewKey;r.attemptContext=image.viewKey+(image.visible?"visible":"near");r.cacheMissing=false;r.attempts=0;r.retryAt=now+1500L;}
        else r.retryAt=now+5000L;
        if(success||r.terminalFailure||r.attempts>=2)r.retryMode=0;
    }
    void backoff(long now,boolean throttled){backoff(now,throttled,throttled?30000:5000);}
    void backoff(long now,boolean throttled,long delayMillis){concurrency=Math.max(1,concurrency/2);cooldownUntil=Math.max(cooldownUntil,now+Math.max(0,delayMillis));growAt=cooldownUntil+1000;}
    void defer(Image image,int token){if(token!=epoch)return;Record r=records.get(image.key);if(r!=null&&r.running){r.running=false;r.attempts=Math.max(0,r.attempts-1);}}
    void cacheMiss(Image image,int token){if(token!=epoch)return;defer(image,token);Record r=records.get(image.key);if(r!=null)r.cacheMissing=true;}
    boolean waitingForMemory(){return memoryBlocked&&running()==0;}
    void blockNewTranslations(){storageBlocked=true;}
    boolean waitingForStorage(){return storageBlocked;}
    void stop(){epoch++;for(Record r:records.values()){r.retryMode=0;if(r.running){r.running=false;r.attempts=Math.max(0,r.attempts-1);}}candidates.clear();priorities=Collections.emptyMap();concurrency=1;growAt=Long.MAX_VALUE;}
    int priority(Image image){return image.retryMode!=0?-1:priorities.getOrDefault(image.key,3);}
    void configureTextTarget(int target){textTarget=Math.max(0,target);}
    void resume(long now){concurrency=textTarget>0?textTarget:1;growAt=Math.max(now,cooldownUntil)+1000;storageBlocked=false;}
    void clear(){stop();records.clear();completed=0;cooldownUntil=0;}
    int running(){int n=0;for(Record r:records.values())if(r.running)n++;return n;}
    List<Image> runningImages(){List<Image> result=new ArrayList<>();for(Record r:records.values())if(r.running)result.add(r.image);return result;}
    int completed(){return completed;}
    int readyAhead(){int n=0;for(Image x:candidates)if(x.ahead&&x.translated)n++;return n;}
    int waiting(){int n=0;for(Image x:candidates){Record r=records.get(x.key);boolean retry=r!=null&&r.retryMode!=0;if((!requestedOnly||retry)&&(retry||(!x.translated&&!x.pending))&&(r==null||(!r.running&&!r.noText&&!r.tooLarge&&!r.terminalFailure&&r.attempts<2&&(!r.completed||x.visible||(x.nearViewport&&!r.cacheMissing&&!viewKey.equals(r.restoredView))))))n++;}return n;}
    int oversized(){int n=0;for(Image x:candidates){Record r=records.get(x.key);if(r!=null&&r.tooLarge&&!x.translated)n++;}return n;}
    int exhausted(){int n=0;for(Image x:candidates){Record r=records.get(x.key);if(r!=null&&(r.attempts>=2||r.terminalFailure)&&!r.running)n++;}return n;}
}
