import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import java.util.regex.*;
import javax.tools.*;
import com.sun.source.tree.*;
import com.sun.source.util.*;

/** Static accessibility/resource gate. It does not certify measured device hit targets or layout. */
public final class Ui110SourceChecks {
    static int assertions,buttons,resourceUses;
    static void require(boolean ok,String label){if(!ok)throw new AssertionError(label);assertions++;}
    public static void main(String[] args)throws Exception{
        Path root=Path.of(args[0]),source=root.resolve("app/src/main/java"),resources=root.resolve("app/src/main/res");
        ArrayList<Path> files=new ArrayList<>();try(var walk=Files.walk(source)){walk.filter(p->p.toString().endsWith(".java")).forEach(files::add);}
        Pattern icons=Pattern.compile("R\\.drawable\\.(ic_[a-z_]+)");
        for(Path file:files){String text=Files.readString(file);Matcher uses=icons.matcher(text);while(uses.find()){require(Files.isRegularFile(resources.resolve("drawable/"+uses.group(1)+".xml")),"missing resource "+uses.group(1)+" in "+file.getFileName());resourceUses++;}}
        JavaCompiler compiler=ToolProvider.getSystemJavaCompiler();try(StandardJavaFileManager manager=compiler.getStandardFileManager(null,null,StandardCharsets.UTF_8)){
            JavacTask task=(JavacTask)compiler.getTask(null,manager,null,List.of("-proc:none"),null,manager.getJavaFileObjectsFromPaths(files));
            for(CompilationUnitTree unit:task.parse())new TreeScanner<Void,Void>(){
                @Override public Void visitMethodInvocation(MethodInvocationTree call,Void unused){if(call.getMethodSelect().toString().equals("Icons.iconButton")){
                    buttons++;require(call.getArguments().size()==4,"iconButton argument count");ExpressionTree desc=call.getArguments().get(2);
                    if(desc instanceof LiteralTree){Object value=((LiteralTree)desc).getValue();require(value instanceof String&&!((String)value).isBlank(),"empty icon description in "+unit.getSourceFile().getName());}
                }return super.visitMethodInvocation(call,unused);}
            }.scan(unit,null);
        }
        String factory=Files.readString(source.resolve("cn/local/manga/Icons.java"));require(factory.contains("description.trim().isEmpty()")&&factory.contains("throw new IllegalArgumentException"),"dynamic icon descriptions are guarded");
        require(factory.contains("setMinimumWidth(Ui.dp(c,48))")&&factory.contains("setMinimumHeight(Ui.dp(c,48))"),"factory requests 48dp targets");
        require(buttons>0,"icon scan reached the migrated UI");
        String manifest=Files.readString(root.resolve("app/src/main/AndroidManifest.xml"));require(manifest.contains("@mipmap/ic_launcher")&&manifest.contains("@mipmap/ic_launcher_round"),"adaptive launcher references");require(!manifest.contains("@drawable/ic_browser"),"legacy launcher unused");
        String service=Files.readString(source.resolve("cn/local/manga/TranslationService.java"));require(service.contains("setSmallIcon(R.drawable.ic_stat_translate)"),"silhouette notification resource");
        if(args.length>1){Path supplied=Path.of(args[1]);int count=0;try(var walk=Files.walk(supplied)){for(Path file:(Iterable<Path>)walk.filter(Files::isRegularFile)::iterator){Path copy=resources.resolve(supplied.relativize(file));require(Files.exists(copy)&&Arrays.equals(Files.readAllBytes(file),Files.readAllBytes(copy)),"supplied vector changed: "+file.getFileName());count++;}}require(count==81,"all 81 supplied resources checked");}
        System.out.println("Ui110SourceChecks: "+assertions+" checks passed; "+buttons+" iconButton calls and "+resourceUses+" resource references. Device hit-target measurement remains unverified.");
    }
}
