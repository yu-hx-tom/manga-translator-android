package cn.local.manga;

/** SQL uses only features available in Android 10's SQLite 3.22. */
final class StorageSchema {
    static final int VERSION = 1;
    static final String[] CREATE = {
        "CREATE TABLE meta(key TEXT PRIMARY KEY NOT NULL,value TEXT NOT NULL)",
        "CREATE TABLE migration(id TEXT PRIMARY KEY NOT NULL,step INTEGER NOT NULL,state TEXT NOT"
                + " NULL,started_at INTEGER NOT NULL,finished_at INTEGER NOT NULL DEFAULT"
                + " 0,source_count INTEGER NOT NULL DEFAULT 0,target_count INTEGER NOT NULL DEFAULT"
                + " 0,checksum TEXT NOT NULL DEFAULT '',error TEXT NOT NULL DEFAULT '')",
        "CREATE TABLE history_entry(url TEXT PRIMARY KEY NOT NULL,title TEXT NOT"
            + " NULL,bookmark_title TEXT NOT NULL,visited_at INTEGER NOT NULL,bookmarked_at INTEGER"
            + " NOT NULL,visits INTEGER NOT NULL,position INTEGER NOT NULL,extra_json TEXT NOT"
            + " NULL)",
        "CREATE INDEX history_visited ON history_entry(visited_at DESC)",
        "CREATE INDEX history_bookmarked ON history_entry(bookmarked_at DESC) WHERE"
                + " bookmarked_at>0",
        "CREATE INDEX history_title ON history_entry(title)",
        "CREATE TABLE shortcut(key TEXT PRIMARY KEY NOT NULL,json TEXT NOT NULL,position INTEGER"
                + " NOT NULL)",
        "CREATE TABLE blob(hash TEXT PRIMARY KEY NOT NULL,size INTEGER NOT NULL CHECK(size>=0),mime"
            + " TEXT NOT NULL,created_at INTEGER NOT NULL,last_access_at INTEGER NOT NULL,missing"
            + " INTEGER NOT NULL DEFAULT 0)",
        "CREATE TABLE blob_ref(owner_type TEXT NOT NULL,owner_id TEXT NOT NULL,role TEXT NOT"
                + " NULL,hash TEXT NOT NULL REFERENCES blob(hash) ON DELETE RESTRICT,kind TEXT NOT"
                + " NULL,PRIMARY KEY(owner_type,owner_id,role))",
        "CREATE INDEX blob_ref_hash ON blob_ref(hash)",
        "CREATE TABLE blob_pending(token TEXT PRIMARY KEY NOT NULL,hash TEXT NOT NULL,created_at"
                + " INTEGER NOT NULL,purpose TEXT NOT NULL)",
        "CREATE TABLE blob_gc(hash TEXT PRIMARY KEY NOT NULL,queued_at INTEGER NOT NULL)",
        "CREATE TABLE backup_ref(backup TEXT NOT NULL,hash TEXT NOT NULL,PRIMARY KEY(backup,hash))",
        "CREATE TABLE session(id TEXT PRIMARY KEY NOT NULL,source_key TEXT NOT NULL,title TEXT NOT"
            + " NULL,created_at INTEGER NOT NULL,updated_at INTEGER NOT NULL,last_access_at INTEGER"
            + " NOT NULL,extra_json TEXT NOT NULL)",
        "CREATE INDEX session_source ON session(source_key)",
        "CREATE TABLE session_page(session_id TEXT NOT NULL REFERENCES session(id) ON DELETE"
            + " CASCADE,page_id TEXT NOT NULL,label TEXT NOT NULL,page_order INTEGER NOT NULL,kind"
            + " TEXT NOT NULL,status TEXT NOT NULL,json TEXT NOT NULL,updated_at INTEGER NOT"
            + " NULL,PRIMARY KEY(session_id,page_id))",
        "CREATE TABLE draft(id TEXT PRIMARY KEY NOT NULL,json TEXT NOT NULL,updated_at INTEGER NOT"
                + " NULL)",
        "CREATE TABLE cache_entry(key TEXT PRIMARY KEY NOT NULL,kind TEXT NOT NULL,metadata TEXT"
                + " NOT NULL,updated_at INTEGER NOT NULL,last_access_at INTEGER NOT NULL)",
        "CREATE TABLE file_usage(path TEXT PRIMARY KEY NOT NULL,category TEXT NOT NULL,bytes"
                + " INTEGER NOT NULL,last_access_at INTEGER NOT NULL)",
        "CREATE TABLE legacy_verified(path TEXT PRIMARY KEY NOT NULL,migration_id TEXT NOT"
                + " NULL,checksum TEXT NOT NULL,bytes INTEGER NOT NULL)"
    };

    private StorageSchema() {}
}
