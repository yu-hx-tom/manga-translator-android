package cn.local.manga;
import java.nio.file.*;
import java.util.*;
public final class PageOutcomeChecks {
    private static int checks;
    private static void check(boolean ok,String reason){if(!ok)throw new AssertionError(reason);checks++;}
    public static void main(String[] args)throws Exception{
        PageOutcome complete=new PageOutcome(3,3,0,0,"已回填");
        check(!complete.incomplete(),"complete must not become partial");
        PageOutcome partial=new PageOutcome(5,3,1,1,"r4：未能确认封闭白气泡，已保留原文\nr5：接口跳过");
        check(partial.incomplete(),"partial must remain visible");
        PageOutcome disk=PageOutcome.decode(partial.encode());
        check(disk.known&&disk.detected==5&&disk.succeeded==3&&disk.failed==1&&disk.skipped==1,"cache counts round trip");
        check(disk.detail.equals(partial.detail),"cache must keep actual rejection reason");
        check(disk.incomplete(),"cache hit must not become complete");
        check(PageOutcome.decode(new PageOutcome(2,0,0,2,"接口跳过").encode()).incomplete(),"all skipped is not complete");
        check(!new PageOutcome(0,0,0,0,"未检测到").incomplete(),"no detection is a separate observation");
        check(!PageOutcome.decode(null).known&&PageOutcome.decode(null).incomplete(),"missing sidecar unknown, never silently complete");
        check(!PageOutcome.decode("version=2\n").known,"unknown format fails closed");
        check(!PageOutcome.decode("version=1\ndetected=1\nsucceeded=3\nfailed=0\nskipped=0").known,"impossible counts rejected");
        check(!PageOutcome.decode("version=1\ndetected=1\nsucceeded=0\nfailed=-1\nskipped=0").known,"negative counts rejected");
        check(!PageOutcome.decode("x".repeat(PageOutcome.MAX_ENCODED_SIZE+1)).known,"bounded cache parser");
        check(new PageOutcome(1,0,1,0,"x".repeat(2000)).detail.length()==1600,"bounded detail");
        check(disk.describe().contains("未能确认封闭白气泡")&&disk.describe().contains("跳过 1"),"details show render cause and skipped count");
        PageOutcome overlay=PageOutcome.decode(new PageOutcome(3,3,0,0,1,"灰底保留原笔画并叠字").encode());
        check(!overlay.incomplete()&&overlay.preservedOriginal==1,"nearby fallback is complete across cache");
        check(overlay.describe().contains("原笔画仍保留"),"overlay count does not claim Japanese removed");
        check(!PageOutcome.decode("version=1\ndetected=1\nsucceeded=1\nfailed=0\nskipped=0\npreservedOriginal=2").known,"preserved subset cannot exceed success count");
        Path out=Paths.get(args[0]);Files.createDirectories(out.getParent());
        String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(Paths.get(args[1]))));
        Files.writeString(out,"{\"checksPassed\":"+checks+",\"sourceSha256\":\""+hash+"\",\"partialCacheRoundTrip\":true,\"skippedIsIncomplete\":true,\"overlayOnlyIsIncomplete\":false,\"missingMetadataIsUnknown\":true,\"rejectionReasonPreserved\":true,\"paidApiUsed\":false,\"androidRuntimeVerified\":false,\"scope\":\"Production PageOutcome serialization and validation on Windows JDK; Browser wiring separately source-reviewed\"}\n");
        System.out.println(checks+" page outcome checks passed");
    }
}
