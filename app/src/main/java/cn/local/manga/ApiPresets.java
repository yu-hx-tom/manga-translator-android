package cn.local.manga;

import android.content.Context;
import android.content.SharedPreferences;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.List;
import java.util.UUID;
import javax.crypto.Cipher;
import javax.crypto.SecretKey;
import javax.crypto.spec.GCMParameterSpec;
import org.json.JSONArray;
import org.json.JSONObject;

/** Entire preset collections are encrypted with the same installation key as active credentials. */
final class ApiPresets {
    private static final String STORAGE="encrypted_presets_v1";
    private final SharedPreferences preferences;
    private final SecretKey key;
    static final class Preset {
        final String id,name;
        Preset(String id,String name){this.id=id;this.name=name;}
    }
    ApiPresets(Context context)throws Exception{this(context.getSharedPreferences("api_presets",Context.MODE_PRIVATE),AppSettings.key());}
    ApiPresets(SharedPreferences preferences,SecretKey key){this.preferences=preferences;this.key=key;}
    List<Preset> list()throws Exception{
        JSONArray values=read();List<Preset> result=new ArrayList<>();
        for(int i=0;i<values.length();i++){JSONObject value=values.getJSONObject(i);result.add(new Preset(value.getString("id"),value.getString("name")));}
        return result;
    }
    AppSettings load(String id)throws Exception{return AppSettings.fromSnapshot(find(read(),id).getJSONObject("settings"));}
    String save(String id,String name,AppSettings settings)throws Exception{
        name=checkName(name);settings.validate();JSONArray values=read();checkDuplicate(values,id,name);
        JSONObject value=id==null?new JSONObject().put("id",UUID.randomUUID().toString()):find(values,id);
        value.put("name",name).put("settings",settings.snapshot());if(id==null)values.put(value);
        write(values);return value.getString("id");
    }
    void rename(String id,String name)throws Exception{
        name=checkName(name);JSONArray values=read();checkDuplicate(values,id,name);find(values,id).put("name",name);write(values);
    }
    void delete(String id)throws Exception{
        JSONArray values=read(),remaining=new JSONArray();find(values,id);
        for(int i=0;i<values.length();i++)if(!id.equals(values.getJSONObject(i).getString("id")))remaining.put(values.getJSONObject(i));
        write(remaining);
    }
    private static JSONObject find(JSONArray values,String id)throws Exception{
        for(int i=0;i<values.length();i++)if(values.getJSONObject(i).getString("id").equals(id))return values.getJSONObject(i);
        throw new Exception("预设不存在，请重新打开设置页");
    }
    private static String checkName(String name)throws Exception{
        name=name==null?"":name.trim();if(name.isEmpty()||name.length()>80)throw new Exception("预设名称请填写 1–80 个字符");return name;
    }
    private static void checkDuplicate(JSONArray values,String id,String name)throws Exception{
        for(int i=0;i<values.length();i++){JSONObject value=values.getJSONObject(i);if(name.equals(value.getString("name"))&&!value.getString("id").equals(id))throw new Exception("已有同名预设，请更换名称或覆盖该预设");}
    }
    private JSONArray read()throws Exception{
        String saved=preferences.getString(STORAGE,"");if(saved.isEmpty())return new JSONArray();
        try{
            JSONObject envelope=new JSONObject(saved);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
            cipher.init(Cipher.DECRYPT_MODE,key,new GCMParameterSpec(128,Base64.getDecoder().decode(envelope.getString("iv"))));
            JSONObject plain=new JSONObject(new String(cipher.doFinal(Base64.getDecoder().decode(envelope.getString("ciphertext"))),StandardCharsets.UTF_8));
            if(plain.getInt("version")!=1)throw new Exception();return plain.getJSONArray("presets");
        }catch(Exception invalid){throw new Exception("预设无法读取，原数据已保留；请确认本机密钥可用");}
    }
    private void write(JSONArray values)throws Exception{
        JSONObject plain=new JSONObject().put("version",1).put("presets",values);Cipher cipher=Cipher.getInstance("AES/GCM/NoPadding");
        cipher.init(Cipher.ENCRYPT_MODE,key);JSONObject envelope=new JSONObject().put("iv",Base64.getEncoder().encodeToString(cipher.getIV()))
            .put("ciphertext",Base64.getEncoder().encodeToString(cipher.doFinal(plain.toString().getBytes(StandardCharsets.UTF_8))));
        if(!preferences.edit().putString(STORAGE,envelope.toString()).commit())throw new Exception("预设保存失败，请检查手机存储空间");
    }
}
