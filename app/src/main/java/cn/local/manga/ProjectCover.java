package cn.local.manga;

import android.graphics.Bitmap;
import android.graphics.BitmapFactory;
import android.util.LruCache;
import android.widget.ImageView;

import java.io.File;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;

/** Small sampled thumbnails only; full page bitmaps never enter this cache. */
final class ProjectCover {
    private static final LruCache<String, Bitmap> CACHE =
            new LruCache<String, Bitmap>(4 * 1024 * 1024) {
                @Override
                protected int sizeOf(String key, Bitmap value) {
                    return value.getAllocationByteCount();
                }
            };
    private static final ExecutorService IO = Executors.newFixedThreadPool(2);

    static Bitmap load(File file) {
        if (file == null || !file.isFile()) return null;
        String key = file.getPath() + ":" + file.lastModified() + ":" + file.length();
        Bitmap hit = CACHE.get(key);
        if (hit != null) return hit;
        try {
            BitmapFactory.Options o = new BitmapFactory.Options();
            o.inJustDecodeBounds = true;
            BitmapFactory.decodeFile(file.getPath(), o);
            o.inSampleSize = 1;
            while (o.outWidth / o.inSampleSize > 256 || o.outHeight / o.inSampleSize > 352)
                o.inSampleSize *= 2;
            o.inJustDecodeBounds = false;
            Bitmap decoded = BitmapFactory.decodeFile(file.getPath(), o);
            if (decoded == null) return null;
            float factor =
                    Math.min(1f, Math.min(128f / decoded.getWidth(), 176f / decoded.getHeight()));
            Bitmap thumb =
                    Bitmap.createScaledBitmap(
                            decoded,
                            Math.max(1, Math.round(decoded.getWidth() * factor)),
                            Math.max(1, Math.round(decoded.getHeight() * factor)),
                            true);
            if (thumb != decoded) decoded.recycle();
            CACHE.put(key, thumb);
            return thumb;
        } catch (RuntimeException | OutOfMemoryError ignored) {
            return null;
        }
    }

    static void bind(ImageView view, File file) {
        view.setImageDrawable(Icons.icon(view.getContext(), R.drawable.ic_book, Ui.MUTED));
        Object request = new Object();
        view.setTag(request);
        IO.execute(
                () -> {
                    Bitmap bitmap = load(file);
                    view.post(
                            () -> {
                                if (view.getTag() == request && bitmap != null)
                                    view.setImageBitmap(bitmap);
                            });
                });
    }
}
