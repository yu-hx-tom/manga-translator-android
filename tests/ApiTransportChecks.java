package cn.local.manga;
import com.sun.net.httpserver.HttpServer;
import java.io.*;
import java.lang.reflect.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.Arrays;
import java.util.concurrent.*;
import java.util.concurrent.atomic.*;
import java.util.function.BooleanSupplier;
public final class ApiTransportChecks {
 private static int checks;
 private static void check(boolean ok,String name){if(!ok)throw new AssertionError(name);checks++;}
 private static final Class<?> BODY;
 private static final Method REQUEST,WRITE;
 static{try{BODY=Class.forName("cn.local.manga.ApiClient$BodyWriter");REQUEST=ApiClient.class.getDeclaredMethod("requestStream",String.class,String.class,String.class,long.class,BODY,String.class,BooleanSupplier.class,int.class,AppSettings.class);REQUEST.setAccessible(true);WRITE=ApiClient.class.getDeclaredMethod("writePrepared",OutputStream.class,File.class,long.class,byte[].class,byte[].class,BooleanSupplier.class);WRITE.setAccessible(true);}catch(Exception e){throw new ExceptionInInitializerError(e);}}
 private static byte[] request(String url,int limit,BooleanSupplier cancel,Object writer,long length)throws Throwable{AppSettings settings=new AppSettings();settings.maxRetries=0;try{return(byte[])REQUEST.invoke(null,url,writer==null?"GET":"POST","application/json",length,writer,"host-test-token",cancel,limit,settings);}catch(InvocationTargetException e){throw e.getCause();}}
 public static void main(String[] args)throws Throwable{
  AppSettings s=new AppSettings();s.baseUrl="http://127.0.0.1:1/v1";s.apiKey="host-test-token";s.validate();check(s.textConcurrency==10&&s.reasoningEffort.equals("low"),"defaults");check(s.requestedServiceTier().equals("auto"),"Fast off by default");s.serviceTier="fast";s.validate();check(s.requestedServiceTier().equals("priority"),"Fast alias normalizes to priority");s.serviceTier="default";check(s.requestedServiceTier().equals("default"),"standard tier");s.textConcurrency=33;try{s.validate();throw new AssertionError("bad concurrency");}catch(Exception expected){checks++;}s.textConcurrency=10;
  AtomicInteger activeRequests=new AtomicInteger(),peakRequests=new AtomicInteger();AtomicInteger received=new AtomicInteger();AtomicReference<byte[]> body=new AtomicReference<>();AtomicReference<String> auth=new AtomicReference<>();AtomicInteger status=new AtomicInteger(200);AtomicBoolean large=new AtomicBoolean(),delay=new AtomicBoolean();
  HttpServer server=HttpServer.create(new InetSocketAddress("127.0.0.1",0),0);ExecutorService handlers=Executors.newCachedThreadPool();server.setExecutor(handlers);server.createContext("/",exchange->{try{int active=activeRequests.incrementAndGet();peakRequests.accumulateAndGet(active,Math::max);received.incrementAndGet();body.set(exchange.getRequestBody().readAllBytes());auth.set(exchange.getRequestHeaders().getFirst("Authorization"));if(delay.get())try{Thread.sleep(1000);}catch(InterruptedException ignored){}byte[] reply=(large.get()?"x".repeat(3000):"{\"ok\":true}").getBytes(StandardCharsets.UTF_8);if(status.get()==302)exchange.getResponseHeaders().add("Location","http://127.0.0.1:1/must-not-follow");exchange.sendResponseHeaders(status.get(),reply.length);exchange.getResponseBody().write(reply);}catch(IOException ignored){}finally{activeRequests.decrementAndGet();exchange.close();}});server.start();
  File payload=File.createTempFile("manga-content-",".json");String address="http://127.0.0.1:"+server.getAddress().getPort()+"/chat/completions";
  try{
   byte[] content=("[{\"type\":\"text\",\"text\":\""+"测试".repeat(30000)+"\"}]").getBytes(StandardCharsets.UTF_8);Files.write(payload.toPath(),content);byte[] prefix="{\"messages\":[{\"role\":\"user\",\"content\":".getBytes(StandardCharsets.UTF_8),suffix="}]}".getBytes(StandardCharsets.UTF_8);
   Object writer=Proxy.newProxyInstance(BODY.getClassLoader(),new Class[]{BODY},(proxy,method,arguments)->{try{WRITE.invoke(null,arguments[0],payload,(long)content.length,prefix,suffix,(BooleanSupplier)()->false);return null;}catch(InvocationTargetException e){throw e.getCause();}});
   byte[] reply=request(address,2048,()->false,writer,prefix.length+content.length+suffix.length);check(new String(reply,StandardCharsets.UTF_8).contains("ok"),"successful transport");byte[] expected=new byte[prefix.length+content.length+suffix.length];System.arraycopy(prefix,0,expected,0,prefix.length);System.arraycopy(content,0,expected,prefix.length,content.length);System.arraycopy(suffix,0,expected,prefix.length+content.length,suffix.length);check(Arrays.equals(expected,body.get()),"streamed file body exact UTF8");check("Bearer host-test-token".equals(auth.get()),"authorization only to selected endpoint");check(received.get()==1,"no duplicate request");
   large.set(true);try{request(address,2048,()->false,null,-1);throw new AssertionError("large response");}catch(Exception expectedFailure){check(expectedFailure.getMessage().contains("安全大小"),"text response size bound");}large.set(false);
   for(int code:new int[]{400,422,429,302}){status.set(code);int before=received.get();try{request(address,2048,()->false,null,-1);throw new AssertionError("status "+code);}catch(Exception expectedFailure){check(!expectedFailure.getMessage().contains("host-test-token")&&!expectedFailure.getMessage().contains("{\"ok"),"safe error "+code);}check(received.get()==before+1,"no fallback or retry "+code);}
   status.set(200);int before=received.get();try{request(address,2048,()->true,null,-1);throw new AssertionError("cancel");}catch(CancellationException expectedFailure){check(received.get()==before,"cancel before network");}
   delay.set(true);peakRequests.set(0);ExecutorService waiters=Executors.newFixedThreadPool(10);java.util.List<Future<Boolean>> concurrent=new java.util.ArrayList<>();
   try{for(int i=0;i<10;i++)concurrent.add(waiters.submit(()->{try{return request(address,2048,()->false,writer,prefix.length+content.length+suffix.length).length>0;}catch(Throwable e){throw new Exception(e);}}));for(Future<Boolean> future:concurrent)if(!future.get(10,TimeUnit.SECONDS))throw new AssertionError("concurrent response");check(peakRequests.get()>=10,"ten real overlapping network waits");check(concurrent.size()==10,"all ten streamed requests complete");}finally{waiters.shutdownNow();}
   delay.set(true);AtomicBoolean cancel=new AtomicBoolean();new Thread(()->{try{Thread.sleep(100);}catch(InterruptedException ignored){}cancel.set(true);}).start();try{request(address,2048,cancel::get,null,-1);throw new AssertionError("live cancel");}catch(CancellationException expectedFailure){checks++;}
   System.out.println("ApiTransportChecks: "+checks+" checks passed (local mock HTTP, no paid API; UI/Android runtime not exercised)");
  }finally{server.stop(0);handlers.shutdownNow();Files.deleteIfExists(payload.toPath());}
 }
}
