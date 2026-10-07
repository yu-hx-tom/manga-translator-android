package cn.local.manga;

import java.net.URI;
import java.net.IDN;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;

/** Omnibox: explicit web URLs or bare host names navigate; all other text searches Google. */
final class BrowserAddress {
    static final String HOME="https://manga-home.invalid/";
    static String resolve(String input){
        String value=input==null?"":input.trim();
        if(value.isEmpty())return HOME;
        if(value.length()>8192)throw new IllegalArgumentException("地址或搜索内容过长");
        boolean explicit=value.matches("(?i)^https?://.*");
        if(!explicit&&value.matches("(?i)^(javascript|data|file|content|intent|about):.*"))throw new IllegalArgumentException("只支持网页地址或搜索内容");
        if(!explicit&&value.contains("://"))throw new IllegalArgumentException("只支持 http 或 https 网页地址");
        if(explicit||(!value.matches("(?s).*\\s.*")&&(value.contains(".")||value.matches("(?i)^localhost(:[0-9]+)?(/.*)?$")))){
            try{
                URI uri=new URI(explicit?value:"https://"+value);
                String authority=uri.getRawAuthority();
                if(authority==null||authority.contains("@"))throw new IllegalArgumentException();
                if(uri.getHost()==null){
                    String[] parts=authority.split(":",2);String ascii=IDN.toASCII(parts[0]);
                    uri=new URI((explicit?value:"https://"+value).replaceFirst(java.util.regex.Pattern.quote(authority),java.util.regex.Matcher.quoteReplacement(ascii+(parts.length>1?":"+parts[1]:""))));
                }
                if(uri.getHost()==null||uri.getPort()==0||uri.getPort()>65535)throw new IllegalArgumentException();
                return uri.toASCIIString();
            }catch(Exception invalid){if(explicit)throw new IllegalArgumentException("网址格式无效");}
        }
        try{return "https://www.google.com/search?q="+URLEncoder.encode(value,StandardCharsets.UTF_8.name());}
        catch(java.io.UnsupportedEncodingException impossible){throw new AssertionError(impossible);}
    }
}
