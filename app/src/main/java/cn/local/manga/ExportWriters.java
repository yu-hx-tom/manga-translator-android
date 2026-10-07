package cn.local.manga;

import android.content.ContentResolver;
import android.content.ContentValues;
import android.content.Context;
import android.net.Uri;
import android.os.Environment;
import android.provider.DocumentsContract;
import android.provider.MediaStore;
import java.io.IOException;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.zip.CRC32;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

/** Destinations for exported pages. Each writer receives files in order and cleans up on abort(). */
final class ExportWriters {
    private ExportWriters() {}

    interface Writer extends AutoCloseable {
        void write(String name, String mime, byte[] data) throws IOException;
        /** Called on failure/cancel: remove what an incomplete export would leave behind where sensible. */
        void abort();
        /** Human readable "where it went", shown when the export finishes. */
        String describe();
        @Override void close() throws IOException;
    }

    /** A folder chosen through the system picker, optionally a new sub-folder created inside it. */
    static final class FolderWriter implements Writer {
        private final ContentResolver resolver;
        private final Uri tree;
        private final String folderId, folderName;
        private final boolean overwrite;
        private final Set<String> existing = new HashSet<>();

        FolderWriter(Context context, Uri tree, String newFolderName, boolean overwrite) throws IOException {
            resolver = context.getContentResolver(); this.tree = tree; this.overwrite = overwrite;
            String rootId = DocumentsContract.getTreeDocumentId(tree);
            try {
                if (newFolderName != null) {
                    String name = unique(rootId, safe(newFolderName));
                    Uri created = DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, rootId), DocumentsContract.Document.MIME_TYPE_DIR, name);
                    if (created == null) throw new IOException();
                    folderId = DocumentsContract.getDocumentId(created); folderName = name;
                } else {
                    folderId = rootId; folderName = rootId.contains(":") ? rootId.substring(rootId.lastIndexOf(':') + 1) : rootId;
                    for (String[] child : children(folderId)) existing.add(child[1]);
                }
            } catch (SecurityException | IllegalArgumentException | UnsupportedOperationException | IOException denied) {
                throw new IOException("无法在所选位置写入，请换一个文件夹（例如“下载”或“文档”里的子文件夹），或改为导出压缩包");
            }
        }
        private String unique(String parentId, String name) {
            Set<String> names = new HashSet<>();
            for (String[] child : children(parentId)) names.add(child[1]);
            if (!names.contains(name)) return name;
            for (int i = 2; ; i++) if (!names.contains(name + " (" + i + ")")) return name + " (" + i + ")";
        }
        private List<String[]> children(String id) {
            List<String[]> out = new ArrayList<>();
            Uri uri = DocumentsContract.buildChildDocumentsUriUsingTree(tree, id);
            try (android.database.Cursor c = resolver.query(uri, new String[]{DocumentsContract.Document.COLUMN_DOCUMENT_ID, DocumentsContract.Document.COLUMN_DISPLAY_NAME}, null, null, null)) {
                if (c != null) while (c.moveToNext()) out.add(new String[]{c.getString(0), c.getString(1)});
            } catch (Exception ignored) {}
            return out;
        }
        @Override public void write(String name, String mime, byte[] data) throws IOException {
            try {
                Uri target = null;
                if (existing.contains(name)) {
                    for (String[] child : children(folderId)) if (name.equals(child[1])) {
                        if (overwrite) target = DocumentsContract.buildDocumentUriUsingTree(tree, child[0]);
                        else { name = unique(folderId, name); }
                        break;
                    }
                }
                if (target == null) target = DocumentsContract.createDocument(resolver, DocumentsContract.buildDocumentUriUsingTree(tree, folderId), mime, name);
                if (target == null) throw new IOException();
                try (OutputStream out = resolver.openOutputStream(target, "wt")) { if (out == null) throw new IOException(); out.write(data); }
            } catch (SecurityException | IllegalArgumentException | UnsupportedOperationException denied) {
                throw new IOException("写入失败：" + name + "（文件夹可能不可写或空间不足）");
            }
        }
        @Override public void abort() { /* Keep pages already written: the result message says how many. */ }
        @Override public String describe() { return "文件夹「" + folderName + "」"; }
        @Override public void close() {}
    }

    /** ZIP or CBZ written as one document; image entries are STORED (already compressed), text is deflated. */
    static final class ZipWriter implements Writer {
        private final Context context; private final Uri document; private final ZipOutputStream zip; private final String label;
        /** CBZ: also gets ComicInfo.xml. */
        final boolean comicBook;
        ZipWriter(Context context, Uri document, String label, boolean comicBook) throws IOException {
            this.context = context; this.document = document; this.label = label; this.comicBook = comicBook;
            OutputStream out;
            try { out = context.getContentResolver().openOutputStream(document, "wt"); }
            catch (SecurityException | IllegalArgumentException denied) { out = null; }
            if (out == null) throw new IOException("无法写入所选压缩包位置");
            zip = new ZipOutputStream(new java.io.BufferedOutputStream(out, 1 << 16));
        }
        @Override public void write(String name, String mime, byte[] data) throws IOException {
            ZipEntry entry = new ZipEntry(name);
            if (mime.startsWith("image/")) {
                CRC32 crc = new CRC32(); crc.update(data);
                entry.setMethod(ZipEntry.STORED); entry.setSize(data.length); entry.setCompressedSize(data.length); entry.setCrc(crc.getValue());
            } else entry.setMethod(ZipEntry.DEFLATED);
            zip.putNextEntry(entry); zip.write(data); zip.closeEntry();
        }
        @Override public void abort() {
            try { zip.close(); } catch (Exception ignored) {}
            try { DocumentsContract.deleteDocument(context.getContentResolver(), document); } catch (Exception ignored) {}
        }
        @Override public String describe() { return label; }
        @Override public void close() throws IOException { zip.finish(); zip.close(); }
    }

    /** Pictures/漫画翻译助手/&lt;title&gt;/ via MediaStore (no permission needed). */
    static final class GalleryWriter implements Writer {
        private final Context context; private final String folder; private final List<Uri> written = new ArrayList<>();
        GalleryWriter(Context context, String title) { this.context = context; this.folder = Environment.DIRECTORY_PICTURES + "/漫画翻译助手/" + safe(title); }
        @Override public void write(String name, String mime, byte[] data) throws IOException {
            if (!mime.startsWith("image/")) return; // the gallery only takes pictures; text scripts are skipped
            ContentValues values = new ContentValues();
            values.put(MediaStore.Images.Media.DISPLAY_NAME, name);
            values.put(MediaStore.Images.Media.MIME_TYPE, mime);
            values.put(MediaStore.Images.Media.RELATIVE_PATH, folder);
            values.put(MediaStore.Images.Media.IS_PENDING, 1);
            Uri uri = context.getContentResolver().insert(MediaStore.Images.Media.EXTERNAL_CONTENT_URI, values);
            if (uri == null) throw new IOException("无法在相册创建图片");
            try {
                try (OutputStream out = context.getContentResolver().openOutputStream(uri)) { if (out == null) throw new IOException(); out.write(data); }
                values.clear(); values.put(MediaStore.Images.Media.IS_PENDING, 0);
                context.getContentResolver().update(uri, values, null, null);
                written.add(uri);
            } catch (IOException | RuntimeException failed) {
                context.getContentResolver().delete(uri, null, null);
                throw new IOException("相册写入失败，请检查剩余空间");
            }
        }
        @Override public void abort() { /* Pages already in the gallery stay; the result message says how many. */ }
        @Override public String describe() { return "相册 " + folder.substring(Environment.DIRECTORY_PICTURES.length() + 1); }
        @Override public void close() {}
    }

    /** File and folder names without characters that common file systems reject. */
    static String safe(String name) {
        String cleaned = name == null ? "" : name.replaceAll("[\\\\/:*?\"<>|\\p{Cntrl}]", "_").trim();
        if (cleaned.isEmpty()) cleaned = "漫画汉化";
        return cleaned.length() > 80 ? cleaned.substring(0, 80) : cleaned;
    }
}
