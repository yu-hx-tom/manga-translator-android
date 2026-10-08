package cn.local.manga;
import android.content.Context;
import android.graphics.Bitmap;
import java.util.*;
/** Host-only controllable session; never included in Android sources or APK. */
public final class RtDetrDetector {
    static int live,peak,created,closed,calls,activeCalls,peakCalls;static String failId="";static final List<String> events=new ArrayList<>();
    final String id;boolean disposed;
    public RtDetrDetector(Context context,String id)throws Exception{if(id.equals(failId))throw new Exception("controlled constructor failure");this.id=id;live++;peak=Math.max(peak,live);created++;events.add("new:"+id);}
    public List<Region> detect(Bitmap bitmap)throws Exception{if(disposed)throw new AssertionError("detect on closed model");calls++;activeCalls++;peakCalls=Math.max(peakCalls,activeCalls);try{Thread.sleep(3);return Collections.emptyList();}finally{activeCalls--;}}
    public void close(){if(disposed)throw new AssertionError("double model close");disposed=true;live--;closed++;events.add("close:"+id);}
}
