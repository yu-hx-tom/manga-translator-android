package android.content;
public class Context {
    private final java.io.File root;
    public Context(java.io.File root){this.root=root;}
    public Context getApplicationContext(){return this;}
    public android.content.SharedPreferences getSharedPreferences(String name,int mode){
        return (android.content.SharedPreferences)java.lang.reflect.Proxy.newProxyInstance(getClass().getClassLoader(),new Class<?>[]{android.content.SharedPreferences.class},(proxy,method,args)->{
            if(method.getName().startsWith("get")&&args!=null&&args.length==2)return args[1];
            if(method.getName().equals("contains"))return false;
            if(method.getName().equals("getAll"))return java.util.Collections.emptyMap();
            throw new UnsupportedOperationException("Host preferences are read-only defaults: "+method.getName());
        });
    }
    public java.io.File getCacheDir(){return directory("cache");}
    public java.io.File getFilesDir(){return directory("files");}
    public java.io.File getDatabasePath(String name){return new java.io.File(directory("databases"),name);}
    private java.io.File directory(String path){java.io.File file=new java.io.File(root,path);file.mkdirs();return file;}
}
