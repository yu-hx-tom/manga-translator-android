package cn.local.manga;

import android.content.ContentValues;
import android.content.Context;
import android.graphics.Bitmap;
import android.graphics.ImageDecoder;
import android.net.Uri;
import android.os.Environment;
import android.provider.MediaStore;
import java.io.File;
import java.io.IOException;
import java.io.OutputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;

/** Bounded image loading and user-visible PNG export, without storage permission. */
public final class Storage {
    private Storage() {}

    public static Bitmap load(Context context, Uri uri) throws IOException {
        return decode(ImageDecoder.createSource(context.getContentResolver(), uri));
    }

    public static Bitmap loadCached(Context context, String path) throws IOException {
        File file = new File(path).getCanonicalFile();
        String parent = context.getCacheDir().getCanonicalPath() + File.separator;
        if (!file.getPath().startsWith(parent) || !file.isFile()) {
            throw new IOException("截图文件无效，请重新截取");
        }
        return decode(ImageDecoder.createSource(file));
    }

    private static Bitmap decode(ImageDecoder.Source source) throws IOException {
        return ImageDecoder.decodeBitmap(source, (decoder, info, ignored) -> {
            int width = info.getSize().getWidth(), height = info.getSize().getHeight();
            float factor = Math.min(1f, 2400f / Math.max(width, height));
            decoder.setTargetSize(Math.max(1, Math.round(width * factor)),
                    Math.max(1, Math.round(height * factor)));
            decoder.setAllocator(ImageDecoder.ALLOCATOR_SOFTWARE);
        });
    }

    public static Uri save(Context context, Bitmap bitmap, String prefix) throws IOException {
        ContentValues values = new ContentValues();
        String name = prefix.replaceAll("[^\\p{L}\\p{N}_-]", "_") + "_"
                + new SimpleDateFormat("yyyyMMdd_HHmmss_SSS", Locale.ROOT).format(new Date()) + ".png";
        values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
        values.put(MediaStore.Images.Media.MIME_TYPE, "image/png");
        values.put(MediaStore.Images.Media.RELATIVE_PATH,
                Environment.DIRECTORY_PICTURES + "/漫画翻译助手");
        values.put(MediaStore.Images.Media.IS_PENDING, 1);
        Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
        if (uri == null) throw new IOException("无法在相册创建图片");
        try {
            try (OutputStream stream = context.getContentResolver().openOutputStream(uri)) {
                if (stream == null || !PerformanceDiagnostics.compress("single_output",bitmap,Bitmap.CompressFormat.PNG, 100, stream)) {
                    throw new IOException("图片写入失败，请检查剩余空间");
                }
            }
            PerformanceDiagnostics.pixels("single_master",bitmap);PerformanceDiagnostics.uri(context,"single_png_output",uri);
            values.clear();
            values.put(MediaStore.Images.Media.IS_PENDING, 0);
            context.getContentResolver().update(uri, values, null, null);
            return uri;
        } catch (Exception error) {
            context.getContentResolver().delete(uri, null, null);
            if (error instanceof IOException) throw (IOException) error;
            throw new IOException("保存失败，请检查相册或剩余空间", error);
        }
    }
}
