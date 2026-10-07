package cn.local.manga;

/** Pinned bundled detection models; API translation models are configured separately. */
public final class DetectorModels {
    private DetectorModels() {}
    public static final String DEFAULT_ID = "ppocr_tiled_tight";
    public static final String PP_ID = "ppocr_tiled_tight";
    public static final String[] IDS = {PP_ID};
    public static final class Spec {
        public final String id, label, asset, sha256;
        public final long bytes;
        private Spec(String id,String label,String filename,String sha256,long bytes) {
            this.id=id;this.label=label;this.asset=PP_ID.equals(id)?filename:"models/rtdetr/"+filename;this.sha256=sha256;this.bytes=bytes;
        }
    }
    private static final Spec[] MODELS = {
        new Spec(PP_ID,"PP-OCR · 分块补检＋紧贴裁切（9.9 MB）","detector.onnx","d73e0058b7a8086bbd57f3d10b8bcd4ff95363f67e06e2762b5e814fe9c9410e",9880512L)
    };
    public static boolean isValid(String id) { for(Spec s:MODELS)if(s.id.equals(id))return true;return false; }
    public static Spec get(String id) { for(Spec s:MODELS)if(s.id.equals(id))return s;throw new IllegalArgumentException("未知的本地检测模型，请在设置中重新选择"); }
    public static String label(String id) { return get(id).label; }
}
