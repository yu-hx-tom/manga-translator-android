package cn.local.manga;

import java.io.File;
import java.io.FileOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.ArrayList;
import java.util.Iterator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.json.JSONObject;

/**
 * One 汉化工程: an ordered list of pages with the user's edits. Each page owns a durable copy of its draft
 * (editable), or a finished/original image (read-only). Stored as filesDir/projects/&lt;id&gt;/project.json.
 */
final class ComicProject {
    static final int SCHEMA = 1;
    static final String KIND_EDITABLE = "editable", KIND_RENDERED = "rendered", KIND_ORIGINAL = "original";

    static final class Page {
        String id, label, kind, imageName, originalName, status="untranslated";
        boolean reviewed;
        final Map<String, PageComposer.Edit> edits = new LinkedHashMap<>();
        boolean editable() { return KIND_EDITABLE.equals(kind); }
        int editedCount() { int n = 0; for (PageComposer.Edit e : edits.values()) if (!e.isDefault()) n++; return n; }
    }

    final File dir;
    // Read-only session views resolve immutable files without changing the project file format.
    boolean databaseSession,legacySession;
    String storageIssue="";
    final Map<String,File> sessionImages=new LinkedHashMap<>(),sessionDrafts=new LinkedHashMap<>();
    String id, title, sourceKind, sourceKey, coverPageId=""; PageComposer.Edit defaultStyle=new PageComposer.Edit();
    long created, updated;
    final List<Page> pages = new ArrayList<>();
    /** Last export choices, remembered for the next export. */
    JSONObject exportOptions = new JSONObject();

    ComicProject(File dir) { this.dir = dir; }

    File pageDir(Page page) { return new File(new File(dir, "pages"), page.id); }
    File draftDir(Page page) { return databaseSession?sessionDrafts.getOrDefault(page.id,new File(pageDir(page),"draft")):new File(pageDir(page), "draft"); }
    File imageFile(Page page) { return databaseSession?sessionImages.get(page.id):page.imageName == null ? null : new File(pageDir(page), page.imageName); }
    int reviewedCount() { int n = 0; for (Page p : pages) if (p.reviewed) n++; return n; }
    int editableCount() { int n = 0; for (Page p : pages) if (p.editable()) n++; return n; }
    PageComposer.Edit effective(Page page,String id){
        PageComposer.Edit override=page.edits.get(id),base=defaultStyle.copy();
        if(override==null)return base;
        if(defaultStyle.isDefault())return override.copy();
        base.text=override.text;base.hidden=override.hidden;base.inPlace=override.inPlace;
        if(override.scale!=1)base.scale=override.scale;if(override.vertical!=null)base.vertical=override.vertical;if(override.color!=0)base.color=override.color;
        if(!override.format.isDefault())base.format=override.format.copy();return base;
    }
    Map<String,PageComposer.Edit> effectiveEdits(Page page,PageDraft draft){Map<String,PageComposer.Edit> out=new LinkedHashMap<>();for(PageDraft.Item item:draft.items)out.put(item.region.id,effective(page,item.region.id));return out;}

    static ComicProject load(File dir) throws Exception {
        return load(dir,"project.json");
    }
    static ComicProject load(File dir,String name) throws Exception {
        File file = new File(dir, name);
        if (!file.isFile() || file.length() > 16L * 1024 * 1024) throw new java.io.IOException("工程文件缺失");
        JSONObject json = new JSONObject(new String(Files.readAllBytes(file.toPath()), StandardCharsets.UTF_8));
        if (json.optInt("schema") != SCHEMA) throw new java.io.IOException("工程版本不受支持");
        ComicProject project = new ComicProject(dir);
        project.id = json.getString("id"); project.title = json.optString("title", "未命名工程");
        project.sourceKind = json.optString("sourceKind"); project.sourceKey = json.optString("sourceKey");
        project.coverPageId=json.optString("coverPageId");if(json.optJSONObject("defaultStyle")!=null)project.defaultStyle=edit(json.getJSONObject("defaultStyle"));
        project.created = json.optLong("created"); project.updated = json.optLong("updated");
        JSONObject export = json.optJSONObject("export");
        if (export != null) project.exportOptions = export;
        JSONArray pages = json.getJSONArray("pages");
        for (int i = 0; i < pages.length(); i++) {
            JSONObject row = pages.getJSONObject(i);
            Page page = new Page();
            page.id = row.getString("id"); page.label = row.optString("label"); page.kind = row.optString("kind", KIND_RENDERED);
            page.imageName = row.has("image") ? row.getString("image") : null; page.reviewed = row.optBoolean("reviewed");
            page.originalName=row.has("original")?row.getString("original"):KIND_ORIGINAL.equals(page.kind)?page.imageName:null;
            page.status=row.optString("status",page.editable()?"translated":"untranslated"); if("translating".equals(page.status))page.status="failed";
            JSONObject edits = row.optJSONObject("edits");
            if (edits != null) for (Iterator<String> keys = edits.keys(); keys.hasNext(); ) {
                String key = keys.next(); page.edits.put(key, edit(edits.getJSONObject(key)));
            }
            project.pages.add(page);
        }
        return project;
    }

    /** Atomic write (temp + rename); safe to call from a background thread. */
    synchronized void save() throws Exception {
        write("project.json",snapshot());
    }
    synchronized String snapshot() throws Exception {
        updated = System.currentTimeMillis();
        JSONArray rows = new JSONArray();
        for (Page page : pages) {
            JSONObject edits = new JSONObject();
            for (Map.Entry<String, PageComposer.Edit> entry : page.edits.entrySet())
                if (!entry.getValue().isDefault()) edits.put(entry.getKey(), json(entry.getValue()));
            JSONObject row = new JSONObject().put("id", page.id).put("label", page.label).put("kind", page.kind).put("status",page.status).put("reviewed", page.reviewed).put("edits", edits);
            if (page.imageName != null) row.put("image", page.imageName);
            if(page.originalName!=null)row.put("original",page.originalName);
            rows.put(row);
        }
        JSONObject json = new JSONObject().put("schema", SCHEMA).put("id", id).put("title", title).put("sourceKind", sourceKind)
                .put("sourceKey", sourceKey).put("schemaVersion",2).put("coverPageId",coverPageId).put("defaultStyle",json(defaultStyle)).put("created", created).put("updated", updated).put("export", exportOptions).put("pages", rows);
        return json.toString();
    }
    synchronized void write(String name,String json) throws Exception {
        if(databaseSession||legacySession)throw new java.io.IOException("会话视图只读，请先导入工程");
        File target = new File(dir, name), temporary = new File(dir, name+".tmp");
        try (FileOutputStream out = new FileOutputStream(temporary)) { out.write(json.getBytes(StandardCharsets.UTF_8)); out.getFD().sync(); }
        RenderedPageCache.replace(temporary, target);
        if(PerformanceDiagnostics.enabled())PerformanceDiagnostics.written("project_metadata",target.length());
    }

    static JSONObject json(PageComposer.Edit edit) throws Exception {
        JSONObject out = new JSONObject();
        if (edit.text != null) out.put("text", edit.text);
        if (edit.scale != 1f) out.put("scale", (double) edit.scale);
        if (edit.vertical != null) out.put("vertical", edit.vertical.booleanValue());
        if (edit.color != 0) out.put("color", edit.color);
        if (edit.hidden) out.put("hidden", true);
        if (edit.inPlace) out.put("inPlace", true);
        if(!edit.format.isDefault())out.put("style",edit.format.json());
        return out;
    }
    static PageComposer.Edit edit(JSONObject json) {
        PageComposer.Edit edit = new PageComposer.Edit();
        if (json.has("text")) edit.text = json.optString("text");
        edit.scale = (float) Math.max(.5, Math.min(2.0, json.optDouble("scale", 1.0)));
        if (json.has("vertical")) edit.vertical = json.optBoolean("vertical");
        edit.color = json.optInt("color", 0);
        edit.hidden = json.optBoolean("hidden");
        edit.inPlace = json.optBoolean("inPlace");
        edit.format=TextStyle.from(json.optJSONObject("style"));
        return edit;
    }
}
