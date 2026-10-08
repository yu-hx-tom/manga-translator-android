package android.content;
/** Host-only private storage and preference interface; never packaged into the application. */
public class Context {
    private final java.io.File root;
    public Context(java.io.File root){this.root=root;}
    public Context getApplicationContext(){return this;}
    public java.io.File getCacheDir(){java.io.File value=new java.io.File(root,"cache");value.mkdirs();return value;}
    public java.io.File getFilesDir(){java.io.File value=new java.io.File(root,"files");value.mkdirs();return value;}
    public SharedPreferences getSharedPreferences(String name,int mode){throw new UnsupportedOperationException("Fixture must supply preferences");}
}
