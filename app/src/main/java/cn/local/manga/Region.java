package cn.local.manga;

import android.graphics.Rect;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/** Coordinates refer to the original bitmap; right/bottom are exclusive. */
public final class Region {
    public final String id;
    public final Rect box;
    public final List<Rect> lines;
    public final boolean vertical;
    /** Optional background read hint only; never an ink/font/erase rectangle. */
    public final Rect contextBox;
    public Region(String id, Rect box, List<Rect> lines, boolean vertical) {
        this(id,box,lines,vertical,null);
    }
    public Region(String id, Rect box, List<Rect> lines, boolean vertical,Rect contextBox) {
        this.id=id; this.box=new Rect(box); this.vertical=vertical;
        this.contextBox=contextBox==null?null:new Rect(contextBox);
        ArrayList<Rect> copy=new ArrayList<>();
        for(Rect line:lines) copy.add(new Rect(line));
        this.lines=Collections.unmodifiableList(copy);
    }
}
