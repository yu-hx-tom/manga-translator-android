package android.util;

/**
 * Allows LocalComics' unused thumbnail cache to initialize; thumbnail operations are not simulated.
 */
public class LruCache<K, V> {
    public LruCache(int maximum) {
        if (maximum < 1) throw new IllegalArgumentException();
    }

    protected int sizeOf(K key, V value) {
        return 1;
    }

    public V get(K key) {
        throw new UnsupportedOperationException("Thumbnail cache is outside host storage checks");
    }

    public V put(K key, V value) {
        throw new UnsupportedOperationException("Thumbnail cache is outside host storage checks");
    }

    public void evictAll() {
        throw new UnsupportedOperationException("Thumbnail cache is outside host storage checks");
    }
}
