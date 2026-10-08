package cn.local.manga;

import android.content.Context;
import java.io.*;
import java.nio.file.*;
import java.util.*;
import java.util.concurrent.CancellationException;
import org.json.*;

/** Filesystem regression/benchmark only. Android bitmap rendering is not exercised. */
public final class ImportCopyChecks {
    static int checks;
    static void check(boolean value,String label){if(!value)throw new AssertionError(label);checks++;}
    static void oldCopy(File from,File to)throws Exception{
        if(from.isDirectory()){to.mkdirs();for(File f:Objects.requireNonNull(from.listFiles()))oldCopy(f,new File(to,f.getName()));}
        else Files.copy(from.toPath(),to.toPath(),StandardCopyOption.REPLACE_EXISTING);
    }
    static String sha(File file)throws Exception{return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(Files.readAllBytes(file.toPath())));}
    public static void main(String[] args)throws Exception{
        File root=new File(args[0],"import-fixture-"+UUID.randomUUID());root.mkdirs();Context context=new Context(root);
        ComicProject session=new ComicProject(new File(root,"session"));session.dir.mkdirs();session.id=UUID.randomUUID().toString();session.title="导入测试";session.sourceKind="web";session.sourceKey="fixture";
        byte[] image=new byte[512*1024];new Random(1).nextBytes(image);
        try{
            for(int i=0;i<30;i++){
                ComicProject.Page p=new ComicProject.Page();p.id="p"+i;p.label=String.valueOf(i);p.kind=ComicProject.KIND_EDITABLE;p.imageName="rendered.png";p.originalName="original.png";session.pages.add(p);
                File draft=session.draftDir(p);draft.mkdirs();
                for(File f:List.of(new File(draft,"source.png"),new File(draft,"clean.png"),new File(draft,"rendered.png"),new File(session.pageDir(p),"original.png"),session.imageFile(p)))Files.write(f.toPath(),image);
                Files.writeString(new File(draft,"draft.json").toPath(),new JSONObject().put("schema",1).put("width",100).put("height",100).put("regions",new JSONArray()).toString());
            }
            session.save();long sourceBytes=PageDraftStore.size(session.dir);
            List<Double> oldMs=new ArrayList<>(),newMs=new ArrayList<>();long oldBytes=0,newBytes=0;
            for(int round=0;round<3;round++){
                // Alternate order to reduce a fixed warm-cache ordering advantage.
                for(boolean old:round%2==0?new boolean[]{true,false}:new boolean[]{false,true}){
                    File scratch=new File(root,"scratch"),destination=new File(root,"old-project");long start=System.nanoTime();
                    if(old){oldCopy(session.dir,scratch);long bytes=PageDraftStore.size(scratch);for(ComicProject.Page p:session.pages)oldCopy(new File(new File(new File(scratch,"pages"),p.id),"draft"),new File(destination,p.id));oldMs.add((System.nanoTime()-start)/1e6);oldBytes=bytes+PageDraftStore.size(destination);PageDraftStore.deleteTree(scratch);PageDraftStore.deleteTree(destination);}
                    else{
                        ProjectLauncher.Gathered g=SessionRepository.snapshotFiles(session,scratch,(d,t)->{},()->false);newBytes=PageDraftStore.size(scratch);
                        ComicProject p=ProjectStore.create(context,"bench","web","fixture",g.pages,(d,t)->{},()->false);newMs.add((System.nanoTime()-start)/1e6);g.cleanup.run();ProjectStore.delete(p);
                    }
                }
            }
            check(newBytes<oldBytes*.5,"snapshot copies each required layer once");
            check(PageDraftStore.size(session.dir)==sourceBytes,"benchmark did not consume live session");
            // Older drafts lack layers; importing them must not invoke Android cleanup/rendering.
            ComicProject.Page first=session.pages.get(0);new File(session.draftDir(first),"clean.png").delete();new File(session.draftDir(first),"rendered.png").delete();
            ComicProject.Page last=session.pages.get(29);last.kind=ComicProject.KIND_ORIGINAL;last.imageName="original.png";PageDraftStore.deleteTree(session.draftDir(last));
            String expected=sha(session.imageFile(first));File scratch=new File(root,"mixed");List<Integer> progress=new ArrayList<>();
            ProjectLauncher.Gathered g=SessionRepository.snapshotFiles(session,scratch,(d,t)->progress.add(d),()->false);
            check(progress.size()==31&&progress.get(30)==30,"snapshot reports page progress");
            check(!new File(g.pages.get(0).ownedDraft,"clean.png").exists(),"legacy layers not eagerly generated");
            check(sha(new File(g.pages.get(0).ownedDraft,"rendered.png")).equals(expected),"legacy finished image reused exactly");
            File witness=new File(root,"source-identity");Files.createLink(witness.toPath(),new File(g.pages.get(0).ownedDraft,"source.png").toPath());
            ComicProject imported=ProjectStore.create(context,"mixed","web","fixture",g.pages,(d,t)->{},()->false);g.cleanup.run();
            File source=new File(imported.draftDir(imported.pages.get(0)),"source.png");
            check(Files.isSameFile(witness.toPath(),source.toPath()),"snapshot is moved, not copied a second time");witness.delete();
            check(!scratch.exists()&&source.isFile(),"scratch cleanup keeps final files");
            check(!new File(source.getParentFile(),"clean.png").exists(),"ProjectStore import never rasterizes legacy pages");
            check(imported.pages.get(29).kind.equals(ComicProject.KIND_ORIGINAL)&&imported.imageFile(imported.pages.get(29)).length()==image.length,"original-only page preserved");
            Files.writeString(new File(session.draftDir(first),"source.png").toPath(),"changed browser cache");PageDraftStore.deleteTree(session.dir);
            check(sha(source).equals(expected),"project survives source overwrite and deletion");ProjectStore.delete(imported);
            // Cancellation during a file copy checks between 64 KiB chunks and removes snapshot.
            ComicProject cancel=new ComicProject(new File(root,"cancel-source"));cancel.dir.mkdirs();ComicProject.Page cp=new ComicProject.Page();cp.id="p";cp.label="cancel";cp.kind=ComicProject.KIND_ORIGINAL;cp.imageName="image.png";cancel.pages.add(cp);cancel.pageDir(cp).mkdirs();Files.write(cancel.imageFile(cp).toPath(),image);
            int[] polls={0};File cancelled=new File(root,"cancelled");boolean stopped=false;
            try{SessionRepository.snapshotFiles(cancel,cancelled,(d,t)->{},()->++polls[0]>4);}catch(CancellationException e){stopped=true;}
            check(stopped&&!cancelled.exists()&&cancel.imageFile(cp).length()==image.length,"cancel removes partial snapshot and preserves source");
            ProjectLauncher.Gathered pending=SessionRepository.snapshotFiles(cancel,new File(root,"pending"),(d,t)->{},()->false);stopped=false;
            try{ProjectStore.create(context,"cancel","web","fixture",pending.pages,(d,t)->{},()->true);}catch(CancellationException e){stopped=true;}finally{pending.cleanup.run();}
            check(stopped&&ProjectStore.list(context).isEmpty(),"cancel removes partial project");
            JSONObject result=new JSONObject().put("checks",checks).put("pages",30).put("oldDiskPhaseMs",oldMs).put("newDiskPhaseMs",newMs).put("oldCopiedBytes",oldBytes).put("newCopiedBytes",newBytes).put("scope","Windows host synthetic files, warm OS cache; excludes Android rendering, not phone end-to-end latency");
            Files.writeString(new File(args[0],"导入复制测试.json").toPath(),result.toString(2));System.out.println(result.toString(2));
        }finally{PageDraftStore.deleteTree(root);}
    }
}
