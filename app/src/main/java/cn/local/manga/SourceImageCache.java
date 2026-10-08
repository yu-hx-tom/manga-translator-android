package cn.local.manga;

import java.io.BufferedInputStream;
import java.io.DataInputStream;
import java.io.DataOutputStream;
import java.io.File;
import java.io.FileInputStream;
import java.io.IOException;
import java.net.HttpURLConnection;
import java.security.MessageDigest;
import java.util.Locale;
import java.util.function.BooleanSupplier;

/** Encoded originals are usable only after the server validates their ETag/Last-Modified. */
final class SourceImageCache {
    static final long MAX_BYTES = 192L * 1024 * 1024;
    static final int MAX_ENTRIES = 256, MAX_BODY = 24 * 1024 * 1024;
    private static final int MAGIC = 0x4d534331;
    private final File directory;
    private android.content.Context context;
    SourceImageCache(File cacheDirectory) { directory = new File(cacheDirectory, "browser-originals-v1"); }
    SourceImageCache(android.content.Context context){this(context.getCacheDir());this.context=context.getApplicationContext();}
    static final class Entry {
        final String key, etag, modified;
        final byte[] digest;
        final int bytes;
        Entry(String key, String etag, String modified, byte[] digest, int bytes) {
            this.key = key; this.etag = etag; this.modified = modified; this.digest = digest; this.bytes = bytes;
        }
        void conditionalHeaders(HttpURLConnection connection) {
            if (!etag.isEmpty()) connection.setRequestProperty("If-None-Match", etag);
            if (!modified.isEmpty()) connection.setRequestProperty("If-Modified-Since", modified);
        }
    }
    static String key(String url, String page, String referer, String agent, String cookie) {
        return CacheFiles.key("source-image-v1", url, page, referer, agent, cookie);
    }
    File file(String key) { if (!CacheFiles.validKey(key)) throw new IllegalArgumentException("缓存标识无效"); return new File(directory, key + ".cache"); }
    Entry find(String key, BooleanSupplier cancelled) {
        CacheFiles.check(cancelled);
        if(context!=null)try{org.json.JSONObject meta=StoredCache.metadata(context,"source",key);if(meta==null)return null;String hash=meta.getString("digest");byte[] digest=new byte[32];for(int i=0;i<32;i++)digest[i]=(byte)Integer.parseInt(hash.substring(i*2,i*2+2),16);return new Entry(key,meta.getString("etag"),meta.getString("modified"),digest,meta.getInt("bytes"));}catch(Exception unavailable){return null;}
        File file = file(key);
        try (DataInputStream input = open(file)) { return header(input, key, file.length()); }
        catch (IOException invalid) { file.delete(); return null; }
    }
    byte[] read(Entry entry, BooleanSupplier cancelled) {
        CacheFiles.check(cancelled);
        if(context!=null)try{StoredCache.Entry value=StoredCache.read(context,"source",entry.key,MAX_BODY);if(value==null||!entry.etag.equals(value.metadata.optString("etag"))||!entry.modified.equals(value.metadata.optString("modified"))||entry.bytes!=value.bytes.length||!MessageDigest.isEqual(entry.digest,CacheFiles.digest().digest(value.bytes)))return null;CacheFiles.check(cancelled);return value.bytes;}catch(Exception unavailable){return null;}
        File file = file(entry.key);
        try (DataInputStream input = open(file)) {
            Entry current = header(input, entry.key, file.length());
            if (!current.etag.equals(entry.etag) || !current.modified.equals(entry.modified) || current.bytes != entry.bytes || !MessageDigest.isEqual(current.digest, entry.digest)) return null;
            byte[] body = new byte[current.bytes]; MessageDigest digest = CacheFiles.digest();
            for (int offset = 0; offset < body.length;) {
                CacheFiles.check(cancelled); int count = input.read(body, offset, Math.min(65536, body.length - offset));
                if (count < 0) throw new IOException("原图缓存不完整"); digest.update(body, offset, count); offset += count;
            }
            if (input.read() != -1 || !MessageDigest.isEqual(current.digest, digest.digest())) throw new IOException("原图缓存校验失败");
            CacheFiles.check(cancelled); file.setLastModified(System.currentTimeMillis()); return body;
        } catch (IOException invalid) { file.delete(); return null; }
    }
    void store(String key, byte[] body, String etag, String modified, String cacheControl, String vary, BooleanSupplier cancelled) {
        CacheFiles.check(cancelled);
        if (!mayStore(cacheControl, vary) || !validValidator(etag) || !validValidator(modified)
                || (blank(etag) && blank(modified)) || body == null || body.length == 0 || body.length > MAX_BODY) { remove(key); return; }
        try {
            MessageDigest digest = CacheFiles.digest();
            for (int offset = 0; offset < body.length; offset += 65536) { CacheFiles.check(cancelled); digest.update(body, offset, Math.min(65536, body.length - offset)); }
            byte[] checksum = digest.digest();
            if(context!=null){try{StoredCache.write(context,"source",key,body,new org.json.JSONObject().put("etag",etag==null?"":etag).put("modified",modified==null?"":modified).put("digest",CacheFiles.hex(checksum)).put("bytes",body.length),null);}catch(Exception unavailable){}return;}
            CacheFiles.write(file(key), body.length + 16384L, MAX_BYTES, MAX_ENTRIES, cancelled, stream -> {
                DataOutputStream output = new DataOutputStream(stream);
                output.writeInt(MAGIC); output.writeUTF(etag == null ? "" : etag); output.writeUTF(modified == null ? "" : modified);
                output.writeInt(body.length); output.write(checksum); CacheFiles.writeBytes(output, body, cancelled);
            });
        } catch (IOException unavailable) { /* Cache writes never turn a valid download into a failed page. */ }
    }
    void remove(String key) { if(context!=null){try{StoredCache.remove(context,"source",key);}catch(Exception ignored){}return;}file(key).delete(); }
    static String combinedHeader(HttpURLConnection connection, String name) {
        StringBuilder values = new StringBuilder();
        for (java.util.Map.Entry<String, java.util.List<String>> entry : connection.getHeaderFields().entrySet()) {
            if (entry.getKey() != null && entry.getKey().equalsIgnoreCase(name) && entry.getValue() != null)
                for (String value : entry.getValue()) { if (values.length() > 0) values.append(','); values.append(value); }
        }
        return values.toString();
    }
    static boolean mayStore(String control, String vary) {
        if (control != null) for (String directive : control.split(",")) if (directive.trim().split("=", 2)[0].trim().equalsIgnoreCase("no-store")) return false;
        if (vary != null) for (String field : vary.split(",")) {
            String name = field.trim().toLowerCase(Locale.ROOT);
            if (!name.isEmpty() && !name.equals("accept") && !name.equals("accept-encoding") && !name.equals("user-agent") && !name.equals("referer") && !name.equals("cookie")) return false;
        }
        return true;
    }
    private static boolean blank(String value) { return value == null || value.isEmpty(); }
    private static boolean validValidator(String value) { return value == null || value.length() <= 2048 && value.indexOf('\r') < 0 && value.indexOf('\n') < 0; }
    private static DataInputStream open(File file) throws IOException { return new DataInputStream(new BufferedInputStream(new FileInputStream(file))); }
    private static Entry header(DataInputStream input, String key, long length) throws IOException {
        if (length < 44 || length > MAX_BODY + 16384L || input.readInt() != MAGIC) throw new IOException("原图缓存格式无效");
        String etag = input.readUTF(), modified = input.readUTF(); int bytes = input.readInt(); byte[] digest = new byte[32]; input.readFully(digest);
        if (!validValidator(etag) || !validValidator(modified) || (etag.isEmpty() && modified.isEmpty()) || bytes <= 0 || bytes > MAX_BODY || length < bytes + 44L) throw new IOException("原图缓存元数据无效");
        return new Entry(key, etag, modified, digest, bytes);
    }
}
