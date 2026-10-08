package cn.local.manga;

import java.io.*;
import java.lang.reflect.*;
import java.net.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;

/** Production retry loop with controlled HttpURLConnection objects: no external network or model calls. */
public final class ApiRetryChecks {
    private static int checks;
    private static final String TOKEN="host-only-test-key";
    private static final Class<?> BODY;
    private static final Method REQUEST,WRITE;
    private static final List<FakeConnection> connections=new CopyOnWriteArrayList<>();
    private static final Queue<Reply> replies=new ConcurrentLinkedQueue<>();
    static {
        try {
            BODY=Class.forName("cn.local.manga.ApiClient$BodyWriter");
            REQUEST=ApiClient.class.getDeclaredMethod("requestStream",String.class,String.class,String.class,long.class,BODY,String.class,BooleanSupplier.class,int.class,AppSettings.class);
            WRITE=ApiClient.class.getDeclaredMethod("writePrepared",OutputStream.class,File.class,long.class,byte[].class,byte[].class,BooleanSupplier.class);
            REQUEST.setAccessible(true);WRITE.setAccessible(true);
        } catch(Exception e){throw new ExceptionInInitializerError(e);}
    }
    private static final class Reply {
        int code=200;String after;IOException error;boolean block;Runnable onCode;
        byte[] bytes="ok".getBytes(StandardCharsets.UTF_8);
        Reply(int code){this.code=code;}
    }
    private static final class FakeConnection extends HttpURLConnection {
        final Reply reply;final ByteArrayOutputStream body=new ByteArrayOutputStream();volatile boolean disconnected;
        FakeConnection(URL url,Reply reply){super(url);this.reply=reply;}
        public void connect(){}
        public boolean usingProxy(){return false;}
        public void disconnect(){disconnected=true;}
        public OutputStream getOutputStream(){return body;}
        public int getResponseCode()throws IOException{
            if(reply.onCode!=null)reply.onCode.run();
            if(reply.error!=null)throw reply.error;
            if(reply.block){while(!disconnected){try{Thread.sleep(5);}catch(InterruptedException e){Thread.currentThread().interrupt();throw new IOException();}}throw new IOException("disconnected");}
            return reply.code;
        }
        public String getHeaderField(String name){return "Retry-After".equalsIgnoreCase(name)?reply.after:null;}
        public int getContentLength(){return reply.bytes.length;}
        public InputStream getInputStream(){return new ByteArrayInputStream(reply.bytes);}
    }
    private static void ok(boolean condition,String label){if(!condition)throw new AssertionError(label);checks++;}
    private static void plan(Reply... values){connections.clear();replies.clear();Collections.addAll(replies,values);}
    private static AppSettings settings(int retries){AppSettings s=new AppSettings();s.maxRetries=retries;s.requestTimeoutSeconds=10;s.retryIntervalSeconds=1;s.rateLimitWaitSeconds=1;return s;}
    private static byte[] call(AppSettings s,BooleanSupplier cancel,Object writer,long length,String key)throws Throwable{
        try{return(byte[])REQUEST.invoke(null,"retrytest://model/request",writer==null?"GET":"POST","application/json",length,writer,key,cancel,2048,s);}
        catch(InvocationTargetException e){throw e.getCause();}
    }
    private static Throwable failure(AppSettings s,BooleanSupplier cancel,Object writer,long length)throws Throwable{
        try{call(s,cancel,writer,length,TOKEN);throw new AssertionError("failure expected");}
        catch(Exception e){return e;}
    }
    public static void main(String[] args)throws Throwable{
        URL.setURLStreamHandlerFactory(protocol->!"retrytest".equals(protocol)?null:new URLStreamHandler(){protected URLConnection openConnection(URL url){Reply reply=replies.poll();if(reply==null)throw new AssertionError("unexpected extra request");FakeConnection c=new FakeConnection(url,reply);connections.add(c);return c;}});
        AppSettings defaults=new AppSettings();
        ok(defaults.requestTimeoutSeconds==180&&defaults.maxRetries==1&&defaults.retryIntervalSeconds==5&&defaults.rateLimitWaitSeconds==30,"documented defaults");
        for(int timeout:new int[]{10,600}){AppSettings s=settings(0);s.requestTimeoutSeconds=timeout;s.validateRequestOptions();ok(true,"timeout boundary "+timeout);}
        for(int value:new int[]{9,601,Integer.MAX_VALUE}){AppSettings s=settings(0);s.requestTimeoutSeconds=value;try{s.validateRequestOptions();throw new AssertionError();}catch(Exception expected){ok(expected.getMessage().contains("10–600"),"invalid timeout");}}
        for(int value:new int[]{-1,6}){AppSettings s=settings(0);s.maxRetries=value;try{s.validateRequestOptions();throw new AssertionError();}catch(Exception expected){checks++;}}
        for(int value:new int[]{0,121}){AppSettings s=settings(0);s.retryIntervalSeconds=value;try{s.validateRequestOptions();throw new AssertionError();}catch(Exception expected){checks++;}}
        for(int value:new int[]{0,301}){AppSettings s=settings(0);s.rateLimitWaitSeconds=value;try{s.validateRequestOptions();throw new AssertionError();}catch(Exception expected){checks++;}}
        ok(AppSettings.parseInteger(" 600 ","等待",10,600)==600,"trim numeric input");
        for(String value:new String[]{"","-1","+1","1.5","1e2","999999999999999999","１２","key-secret"}){
            try{AppSettings.parseInteger(value,"等待",1,600);throw new AssertionError();}
            catch(Exception expected){ok(!expected.getMessage().contains(value)||value.isEmpty(),"safe numeric rejection");}}
        for(int code:new int[]{408,429,500,502,503,504})ok(ApiClient.retryableStatus(code),"retry classification "+code);
        for(int code:new int[]{200,302,400,401,403,404,422})ok(!ApiClient.retryableStatus(code),"nonretry classification "+code);
        long now=java.time.Instant.parse("2026-09-29T00:00:00Z").toEpochMilli();
        ok(ApiClient.retryAfterMillis("7",now)==7000,"Retry-After seconds");
        ok(ApiClient.retryAfterMillis("000000000000007",now)==7000,"leading-zero Retry-After remains valid");
        ok(ApiClient.retryAfterMillis("Tue, 29 Sep 2026 00:00:08 GMT",now)==8000,"Retry-After HTTP date");
        ok(ApiClient.retryAfterMillis("Tue, 29 Sep 2026 00:00:00 GMT",now+1000)==0,"past date");
        for(String invalid:new String[]{"-5","garbage","1.5"})ok(ApiClient.retryAfterMillis(invalid,now)==0,"invalid header ignored");
        ok(ApiClient.retryAfterMillis("99999999999999999999",now)>300000,"huge header cannot cause early resend");
        ok(ApiClient.retryWaitMillis(7,30,429,15000)==30000,"configured throttle wins");
        ok(ApiClient.retryWaitMillis(7,1,429,15000)==15000,"server delay wins");
        ok(ApiClient.retryWaitMillis(7,30,503,0)==7000,"normal interval controls 503");
        File prepared=File.createTempFile("retry-body-",".json");
        byte[] content="测试同一个内容文件".repeat(1000).getBytes(StandardCharsets.UTF_8),prefix="[".getBytes(),suffix="]".getBytes();Files.write(prepared.toPath(),content);
        Object writer=java.lang.reflect.Proxy.newProxyInstance(BODY.getClassLoader(),new Class[]{BODY},(proxy,method,arguments)->{try{WRITE.invoke(null,arguments[0],prepared,(long)content.length,prefix,suffix,(BooleanSupplier)()->false);return null;}catch(InvocationTargetException e){throw e.getCause();}});
        try{
            for(int retries=0;retries<=2;retries++){
                Reply[] values=new Reply[retries+1];Arrays.setAll(values,i->new Reply(503));plan(values);
                Throwable e=failure(settings(retries),()->false,writer,content.length+2);
                ok(connections.size()==retries+1,"exact attempt count "+retries);
                ok(!e.getMessage().contains(TOKEN),"safe exhausted error");
                byte[] sent=connections.get(0).body.toByteArray();
                ok(sent.length==content.length+2&&connections.stream().allMatch(c->Arrays.equals(sent,c.body.toByteArray())),"every retry reopens full prepared file");
                ok(connections.stream().allMatch(c->c.disconnected),"all failed connections closed");
                ok(connections.stream().allMatch(c->!c.getInstanceFollowRedirects()),"credentialed redirects disabled");
            }
            for(int code:new int[]{400,401,403,404,422,302}){
                plan(new Reply(code));Throwable e=failure(settings(5),()->false,writer,content.length+2);
                ok(connections.size()==1&&!e.getMessage().contains(TOKEN),"no retry configuration/redirect "+code);
            }
            Reply oversized=new Reply(200);oversized.bytes=new byte[2049];plan(oversized);failure(settings(5),()->false,null,-1);ok(connections.size()==1,"oversized success body not retried");
            Reply tooLate=new Reply(429);tooLate.after="301";plan(tooLate);Throwable late=failure(settings(5),()->false,null,-1);
            ok(connections.size()==1&&late.getMessage().contains("超过300秒"),"server longer than cap stops instead of early resend");
            plan(tooLate);ok(failure(settings(0),()->false,null,-1).getMessage().contains("超过300秒"),"long server wait explained even with retries disabled");
            Reply throttle=new Reply(429);plan(throttle,new Reply(200));long started=System.nanoTime();call(settings(1),()->false,null,-1,null);
            ok(connections.size()==2&&(System.nanoTime()-started)/1_000_000>=900,"429 uses configured one second, not hidden thirty seconds");
            ok(connections.stream().allMatch(c->c.getRequestProperty("Authorization")==null),"returned-image requests do not gain credentials on retry");
            Reply later=new Reply(503);later.after="2";plan(later,new Reply(200));started=System.nanoTime();call(settings(1),()->false,null,-1,TOKEN);
            ok((System.nanoTime()-started)/1_000_000>=1900,"Retry-After applied in real retry loop");
            AppSettings snapshot=settings(1);Reply changed=new Reply(503);changed.onCode=()->{snapshot.maxRetries=0;snapshot.requestTimeoutSeconds=600;snapshot.retryIntervalSeconds=120;};plan(changed,new Reply(200));call(snapshot,()->false,null,-1,TOKEN);
            ok(connections.size()==2&&connections.stream().allMatch(c->c.getReadTimeout()==10000&&c.getConnectTimeout()==10000),"in-flight snapshot survives setting changes");
            AppSettings longer=settings(0);longer.requestTimeoutSeconds=600;plan(new Reply(200));call(longer,()->false,null,-1,TOKEN);
            ok(connections.get(0).getReadTimeout()==600000&&connections.get(0).getConnectTimeout()==15000,"configured read/deadline with bounded connection timeout");
            Reply timeout=new Reply(200);timeout.error=new SocketTimeoutException();plan(timeout,new Reply(200));call(settings(1),()->false,null,-1,TOKEN);ok(connections.size()==2,"timeout retries");
            Reply network=new Reply(200);network.error=new IOException("unsafe host detail");plan(network,new Reply(200));call(settings(1),()->false,null,-1,TOKEN);ok(connections.size()==2,"network error retries");
            Reply certificate=new Reply(200);certificate.error=new javax.net.ssl.SSLHandshakeException("unsafe certificate detail");plan(certificate);Throwable tls=failure(settings(5),()->false,null,-1);ok(connections.size()==1&&!tls.getMessage().contains("unsafe"),"certificate error not retried or leaked");
            plan();ok(failure(settings(5),()->true,null,-1) instanceof CancellationException&&connections.isEmpty(),"cancel before opening a connection");
            AtomicBoolean cancel=new AtomicBoolean();Reply failed=new Reply(503);failed.onCode=()->new Thread(()->{try{Thread.sleep(100);}catch(InterruptedException ignored){}cancel.set(true);}).start();plan(failed);
            started=System.nanoTime();ok(failure(settings(5),cancel::get,null,-1) instanceof CancellationException&&connections.size()==1,"cancel during backoff prevents next request");
            ok((System.nanoTime()-started)/1_000_000<1000,"backoff cancellation responsive");
            cancel.set(false);Reply blocked=new Reply(200);blocked.block=true;blocked.onCode=()->new Thread(()->{try{Thread.sleep(100);}catch(InterruptedException ignored){}cancel.set(true);}).start();plan(blocked);
            ok(failure(settings(5),cancel::get,null,-1) instanceof CancellationException&&connections.size()==1&&connections.get(0).disconnected,"cancel in transport never retries");
            Reply deadline=new Reply(200);deadline.block=true;plan(deadline);started=System.nanoTime();
            Throwable expired=failure(settings(0),()->false,null,-1);
            ok(expired instanceof ApiClient.RequestFailure&&expired.getMessage().contains("超时"),"production request watchdog uses configured timeout");
            long elapsed=(System.nanoTime()-started)/1_000_000;
            ok(elapsed>=9500&&elapsed<13000&&connections.size()==1&&connections.get(0).disconnected,"configured ten-second watchdog disconnects blocking operation without retry");
            checkQueueBudget();
            System.out.println("ApiRetryChecks: "+checks+" checks passed (controlled HTTP connections; no paid API; Android UI/Keystore not executed)");
        }finally{Files.deleteIfExists(prepared.toPath());}
    }
    private static void checkQueueBudget(){
        AutoTranslationQueue queue=new AutoTranslationQueue();queue.resume(0);AutoTranslationQueue.Image image=new AutoTranslationQueue.Image("one","https://fixture/one",true,false,0);queue.scan(Collections.singletonList(image));
        queue.take(0,1L<<30);queue.finish(image,queue.epoch(),false,false,0,false);
        ok(queue.take(999999,1L<<30).isEmpty()&&queue.exhausted()==1&&queue.waiting()==0,"request exhaustion cannot multiply through page queue");
        queue.stop();queue.resume(1000000);queue.scan(Collections.singletonList(image));
        ok(queue.take(1000000,1L<<30).isEmpty(),"settings return does not silently repay exhausted page");
        queue.clear();queue.resume(0);queue.scan(Collections.singletonList(image));queue.take(0,1L<<30);int previous=queue.epoch();queue.stop();queue.resume(1);queue.scan(Collections.singletonList(image));queue.take(1,1L<<30);queue.finish(image,previous,false,false,2,false);
        ok(queue.running()==1,"stale generation cannot terminate resumed work");
        queue.finish(image,queue.epoch(),false,false,2,true);
        ok(queue.take(5002,1L<<30).size()==1,"safe cached-pixel restoration remains retryable");
        AutoTranslationQueue cooling=new AutoTranslationQueue();cooling.resume(0);cooling.scan(Collections.singletonList(image));cooling.backoff(0,true,2000);
        ok(cooling.take(1999,1L<<30).isEmpty()&&cooling.take(2000,1L<<30).size()==1,"queue cooldown accepts user wait instead of hidden thirty seconds");
    }
}
