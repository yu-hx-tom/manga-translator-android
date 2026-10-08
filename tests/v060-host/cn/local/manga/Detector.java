package cn.local.manga;

/** Shares the controllable fake session counters with the three RT models. */
public class Detector {
    private final RtDetrDetector delegate;

    public Detector(android.content.Context context) throws Exception {
        delegate = new RtDetrDetector(context, DetectorModels.PP_ID);
    }

    public java.util.List<Region> detect(android.graphics.Bitmap bitmap) throws Exception {
        return delegate.detect(bitmap);
    }

    public void close() {
        delegate.close();
    }
}
