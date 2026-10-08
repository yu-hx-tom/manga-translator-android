package cn.local.manga;

import android.content.Context;

import java.io.File;
import java.nio.file.Files;
import java.util.Arrays;

/**
 * 1.1.3 复现：手机上 /data/user/0 是 /data/data 的符号链接，规范路径与 getCacheDir() 不同。 主机上用 Windows 8.3
 * 短路径（ADMINI~1）制造同样的「规范路径 ≠ 原路径」，复现网页译图保存失败。 用法：java ... cn.local.manga.Repro113AliasPath <短路径数据根>
 * <任意 PNG 文件>
 */
public class Repro113AliasPath {
    public static void main(String[] args) throws Exception {
        File root = new File(args[0]);
        root.mkdirs();
        System.out.println("数据根（原路径）：" + root.getPath());
        System.out.println("数据根（规范路径）：" + root.getCanonicalPath());
        if (root.getPath().equals(root.getCanonicalPath())) {
            System.out.println("无效夹具：原路径与规范路径相同，无法复现");
            System.exit(2);
        }
        byte[] png = Files.readAllBytes(new File(args[1]).toPath());
        Context c = new Context(root);
        PageCacheStore web = new PageCacheStore(c);
        File alias = new File(c.getCacheDir(), "browser-pages-v2/document/page.png");
        alias.getParentFile().mkdirs();
        try {
            web.save(alias, png, new PageOutcome(2, 2, 0, 0, ""));
        } catch (Exception e) {
            System.out.println("保存失败：" + e.getMessage());
            System.out.println("真实原因（StorageDatabase.problem）：" + StorageDatabase.problem);
            System.exit(1);
        }
        byte[] back = web.read(alias, true);
        boolean same = Arrays.equals(png, back);
        boolean aliasOk = alias.isFile() && Arrays.equals(png, Files.readAllBytes(alias.toPath()));
        File committed = web.committedFile(alias);
        boolean committedOk =
                committed != null && Arrays.equals(png, Files.readAllBytes(committed.toPath()));
        System.out.println("保存成功；读回一致=" + same + "；视图文件一致=" + aliasOk + "；会话来源一致=" + committedOk);
        System.exit(same && aliasOk && committedOk ? 0 : 3);
    }
}
