package cn.local.manga;

import android.app.*;
import android.content.Intent;
import android.view.*;
import android.webkit.WebView;
import android.widget.TextView;
import java.util.*;

/** Device-only smoke test. Does not invoke translation APIs or edit the user's projects. */
final class UiShellChecks {
    static String run(Instrumentation test)throws Exception{
        Activity browser=test.startActivitySync(new Intent(test.getTargetContext(),BrowserActivity.class).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK));
        test.waitForIdleSync();Thread.sleep(600);test.waitForIdleSync();
        final Throwable[] failure={null};final WebView[] retained={null};
        test.runOnMainSync(()->{try{
            List<View> views=views(browser.getWindow().getDecorView());
            for(String label:new String[]{"浏览器","本地翻译","汉化工具","开始翻译"}){
                TextView found=null;for(View v:views)if(v instanceof TextView&&label.contentEquals(((TextView)v).getText()))found=(TextView)v;
                if(found==null||found.getMaxLines()!=1||found.getWidth()<=0)throw new AssertionError("Missing/split shell label: "+label);
            }
            for(View v:views)if(v instanceof WebView)retained[0]=(WebView)v;
            if(retained[0]==null)throw new AssertionError("Browser WebView missing");
            ((ShellActivity)browser).switchTab(1);
        }catch(Throwable e){failure[0]=e;}});
        if(failure[0]!=null)throw new AssertionError(failure[0]);test.waitForIdleSync();
        test.runOnMainSync(()->browser.startActivity(new Intent(browser,BrowserActivity.class).addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT)));
        test.waitForIdleSync();test.runOnMainSync(()->{try{if(browser.isDestroyed()||!views(browser.getWindow().getDecorView()).contains(retained[0]))throw new AssertionError("Tab switch destroyed WebView");}catch(Throwable e){failure[0]=e;}});
        if(failure[0]!=null)throw new AssertionError(failure[0]);return "4 single-line navigation labels + retained WebView passed; translation/50-page performance need separate device scenarios";
    }
    private static List<View> views(View root){List<View> out=new ArrayList<>();out.add(root);if(root instanceof ViewGroup){ViewGroup group=(ViewGroup)root;for(int i=0;i<group.getChildCount();i++)out.addAll(views(group.getChildAt(i)));}return out;}
}
