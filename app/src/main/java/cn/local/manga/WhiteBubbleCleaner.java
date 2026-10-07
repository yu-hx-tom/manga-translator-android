package cn.local.manga;

import java.util.Arrays;

/** Closed neutral light-background segmentation. Boundary-touching areas are never treated as balloons. */
final class WhiteBubbleCleaner {
    enum BackgroundKind { PLAIN_PAPER, TEXTURED_ART, UNCERTAIN }
    static boolean canClean(Mask mask){return mask.whiteBackground&&(mask.backgroundKind==BackgroundKind.PLAIN_PAPER||(mask.texturedBackground&&mask.fillColors!=null&&mask.evidence.equals("verified_texture_glyphs_only")));}
    /** Rectangle plus complete proven glyphs, clipped to paper and away from other paragraphs. */
    static boolean[] rectangleMask(Mask mask,int width,int height,int[] box,int[][] foreign){
        boolean[] rectangle=new boolean[Math.multiplyExact(width,height)];
        if(!canClean(mask))return rectangle;
        if(mask.texturedBackground||mask.evidence.equals("local_contrast_glyphs_only")){
            for(int p=0;p<rectangle.length;p++)rectangle[p]=mask.erase[p]&&mask.interior[p];
            return excludeForeign(rectangle,width,height,foreign);
        }
        for(int y=Math.max(0,box[1]-2);y<Math.min(height,box[3]+2);y++)
            for(int x=Math.max(0,box[0]-2);x<Math.min(width,box[2]+2);x++)
                rectangle[y*width+x]=mask.interior[y*width+x];
        // Paragraph boxes can clip a glyph, ruby, or punctuation near an irregular balloon edge.
        // Segmentation already proved these complete ink islands; do not clip them a second time.
        for(int p=0;p<rectangle.length;p++)if(mask.erase[p]&&mask.interior[p])rectangle[p]=true;
        return excludeForeign(rectangle,width,height,foreign);
    }
    /** Resolve only complete accepted glyph islands with unambiguous local ownership. */
    static boolean[] rectangleMask(Mask mask,int[] pixels,int w,int h,int[] box,int[][] own,int[][] foreign){
        return resolveForeignExclusions(pixels,w,h,rectangleMask(mask,w,h,box,new int[0][]),own,foreign);
    }
    private static boolean[] resolveForeignExclusions(int[] pixels,int w,int h,boolean[] candidate,int[][] own,int[][] foreign){
        boolean[] safe=excludeForeign(candidate,w,h,foreign);
        if(foreign.length==0||own.length==0)return safe;
        int n=pixels.length;boolean[] anchor=new boolean[n],foreignCore=new boolean[n],seen=new boolean[n];
        for(int[] r:own)for(int y=Math.max(0,r[1]);y<Math.min(h,r[3]);y++)for(int x=Math.max(0,r[0]);x<Math.min(w,r[2]);x++)anchor[y*w+x]=true;
        for(int[] r:foreign)for(int y=Math.max(0,r[1]);y<Math.min(h,r[3]);y++)for(int x=Math.max(0,r[0]);x<Math.min(w,r[2]);x++)foreignCore[y*w+x]=true;
        int[] queue=new int[n];
        for(int seed=0;seed<n;seed++)if(candidate[seed]&&!safe[seed]&&!seen[seed]&&brightness(pixels[seed])<245){
            int head=0,end=1,foreignHits=0;boolean complete=true,local=true,neutralInk=true,edge=false;
            queue[0]=seed;seen[seed]=true;
            while(head<end){int p=queue[head++],x=p%w,y=p/w;
                complete&=candidate[p];local&=anchor[p];neutralInk&=neutral(pixels[p]);if(foreignCore[p])foreignHits++;
                edge|=x==0||y==0||x==w-1||y==h-1;
                for(int yy=Math.max(0,y-1);yy<=Math.min(h-1,y+1);yy++)for(int xx=Math.max(0,x-1);xx<=Math.min(w-1,x+1);xx++){
                    int q=yy*w+xx;if(!seen[q]&&brightness(pixels[q])<245){seen[q]=true;queue[end++]=q;}
                }
            }
            // No clipping or frame detachment: the entire original component
            // must already be an accepted glyph, wholly inside actual anchors.
            // A strict majority must lie outside foreign cores; ties stay protected.
            if(complete&&local&&neutralInk&&!edge&&end>=4&&foreignHits*2<end)
                for(int i=0;i<end;i++)safe[queue[i]]=true;
        }
        return safe;
    }
    static boolean touchesForeignInk(int[] pixels,int w,int h,Mask mask,int[][] foreign,int[][] own){
        boolean[] safe=resolveForeignExclusions(pixels,w,h,mask.erase,own,foreign);
        for(int p=0;p<pixels.length;p++)if(mask.erase[p]&&!safe[p]&&brightness(pixels[p])<225)return true;
        return false;
    }
    static final class Mask {
        final boolean[] erase, interior;
        final boolean[] glyphCandidate;
        final boolean whiteBackground, texturedBackground;
        final int pixels;
        final int[] fillColors;
        final BackgroundKind backgroundKind;
        final String evidence;
        boolean closedColoredInk; // Diagnostic proof of an accepted ink island; never a mask by itself.
        Mask(boolean[] erase, boolean[] interior, boolean valid, int pixels, int[] fillColors,boolean texturedBackground) {
            this(erase,interior,valid,pixels,fillColors,texturedBackground,null);
        }
        Mask(boolean[] erase,boolean[] interior,boolean valid,int pixels,int[] fillColors,boolean texturedBackground,boolean[] glyphCandidate){
            this(erase,interior,valid,pixels,fillColors,texturedBackground,glyphCandidate,
                texturedBackground?BackgroundKind.TEXTURED_ART:valid?BackgroundKind.PLAIN_PAPER:BackgroundKind.UNCERTAIN,
                texturedBackground?"legacy_texture":valid?"verified_paper":"unclassified");
        }
        Mask(boolean[] erase,boolean[] interior,boolean valid,int pixels,int[] fillColors,boolean texturedBackground,boolean[] glyphCandidate,BackgroundKind backgroundKind,String evidence){
            this.erase=erase;this.interior=interior;this.whiteBackground=valid;this.pixels=pixels;this.fillColors=fillColors;this.texturedBackground=texturedBackground;this.glyphCandidate=glyphCandidate;
            this.backgroundKind=backgroundKind==null?BackgroundKind.UNCERTAIN:backgroundKind;this.evidence=evidence==null?"unclassified":evidence;
        }
    }
    static Mask find(int[] pixels,int width,int height,int[][] lines,int characterSize) {
        if(pixels.length!=Math.multiplyExact(width,height)||pixels.length>4_000_000)throw new IllegalArgumentException("Invalid cleanup crop");
        int lightGray=0,total=0;
        for(int[] r:lines)for(int y=Math.max(0,r[1]);y<Math.min(height,r[3]);y++)for(int x=Math.max(0,r[0]);x<Math.min(width,r[2]);x++){
            int color=pixels[y*width+x],v=brightness(color);total++;if(v>=160&&v<225&&neutral(color))lightGray++;
        }
        // Gray-paper captions need their midtone paper connected. Closure is still mandatory; no border is invented.
        return findAtThreshold(pixels,width,height,lines,characterSize,lightGray>total*.12?160:225);
    }
    /** RT ink anchors include antialias gray: first prove closed white paper with unchanged strict guards. */
    static Mask findPreferWhitePaper(int[] pixels,int width,int height,int[][] lines,int characterSize){
        Mask white=findAtThreshold(pixels,width,height,lines,characterSize,225);
        if(white.whiteBackground)return white;
        Mask paper=find(pixels,width,height,lines,characterSize);
        return paper.whiteBackground||paper.texturedBackground?paper:findLocalWhiteText(pixels,width,height,lines,characterSize,paper);
    }
    static Mask forText(int[] pixels,int width,int height,int[][] lines,int characterSize){
        return recoverFrameConnectedInk(pixels,width,height,lines,characterSize,null,forTextBase(pixels,width,height,lines,characterSize));
    }
    private static Mask forTextBase(int[] pixels,int width,int height,int[][] lines,int characterSize){
        Mask mask=findPreferWhitePaper(pixels,width,height,lines,characterSize);
        if(!mask.whiteBackground){Mask repaired=GrayGlyphRepair.repair(pixels,width,height,mask);if(repaired!=null)mask=repaired;}
        Mask classified=classify(pixels,width,height,lines,characterSize,mask);
        if(classified.backgroundKind==BackgroundKind.PLAIN_PAPER||classified.texturedBackground)return classified;
        // Keep successful legacy cleanup (including gray paper) before trying
        // the concave-core fallback, which can recover only part of that paper.
        Mask irregular=findAtThreshold(pixels,width,height,lines,characterSize,225,null,false,true);
        if(irregular.whiteBackground){Mask recovered=classify(pixels,width,height,lines,characterSize,irregular);if(recovered.backgroundKind==BackgroundKind.PLAIN_PAPER)return recovered;}
        return classified;
    }
    /** Paragraph bounds recover missed columns only inside pixel-proven closed paper; they are never a fill rectangle. */
    static Mask forText(int[] pixels,int width,int height,int[][] lines,int characterSize,int[] paragraphBoundsInRoi){
        Mask original=recoverFrameConnectedInk(pixels,width,height,lines,characterSize,paragraphBoundsInRoi,forTextParagraph(pixels,width,height,lines,characterSize,paragraphBoundsInRoi));
        if(original.texturedBackground&&original.whiteBackground&&paragraphBoundsInRoi!=null)
            original=recoverTextureParagraph(pixels,width,height,paragraphBoundsInRoi,characterSize,original);
        if(!original.whiteBackground&&!original.texturedBackground)
            original=findLocalWhiteText(pixels,width,height,lines,characterSize,original,2,225);
        if(!original.whiteBackground&&!original.texturedBackground&&lines.length>1)
            original=recoverSeparateLines(pixels,width,height,lines,characterSize,original);
        if(original.evidence.equals("local_contrast_glyphs_only")&&paragraphBoundsInRoi!=null){
            int[] b=paragraphBoundsInRoi;boolean vertical=b[3]-b[1]>=b[2]-b[0];
            int start=vertical?b[0]:b[1],limit=vertical?b[2]:b[3],span=Math.max(12,characterSize),step=Math.max(6,span/2);
            java.util.ArrayList<int[]> strips=new java.util.ArrayList<>();
            for(int v=start;v<limit;v+=step)strips.add(vertical?new int[]{v,b[1],Math.min(limit,v+span+4),b[3]}:new int[]{b[0],v,b[2],Math.min(limit,v+span+4)});
            original=recoverSeparateLines(pixels,width,height,strips.toArray(new int[0][]),characterSize,original);
        }
        return completeLocalParagraphInk(pixels,width,height,lines,characterSize,paragraphBoundsInRoi,original);
    }
    /** Only dark glyph cores on already proven closed paper enter texture repair. */
    private static Mask recoverTextureParagraph(int[] pixels,int w,int h,int[] box,int size,Mask original){
        int n=pixels.length;boolean[] seen=new boolean[n],core=new boolean[n];int[] queue=new int[n];int count=0;
        for(int seed=0;seed<n;seed++)if(!seen[seed]&&seed%w>box[0]&&seed%w<box[2]-1&&seed/w>box[1]&&seed/w<box[3]-1&&brightness(pixels[seed])<96){
            int head=0,end=1,left=w,top=h,right=0,bottom=0;boolean safe=true,proven=true;queue[0]=seed;seen[seed]=true;
            while(head<end){int p=queue[head++],x=p%w,y=p/w;
                proven&=original.interior[p];safe&=neutral(pixels[p])&&x>box[0]&&x<box[2]-1&&y>box[1]&&y<box[3]-1;
                left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x+1);bottom=Math.max(bottom,y+1);
                for(int yy=Math.max(0,y-1);yy<=Math.min(h-1,y+1);yy++)for(int xx=Math.max(0,x-1);xx<=Math.min(w-1,x+1);xx++){
                    int q=yy*w+xx;if(!seen[q]&&brightness(pixels[q])<96){seen[q]=true;queue[end++]=q;}
                }
            }
            int ring=0,paper=0;
            for(int y=Math.max(0,top-3);y<Math.min(h,bottom+3);y++)for(int x=Math.max(0,left-3);x<Math.min(w,right+3);x++)if(x<left||x>=right||y<top||y>=bottom){ring++;if(original.interior[y*w+x])paper++;}
            if(safe&&(proven||(ring>0&&paper>=ring*.85))&&end>=Math.max(12,size/3)&&end<=size*size*2&&right-left<=size*2&&bottom-top<=size*2)
                for(int i=0;i<end;i++){core[queue[i]]=true;count++;}
        }
        if(count<8)return new Mask(new boolean[n],original.interior,false,0,null,true);
        boolean[] glyph=core.clone(),inside=original.interior.clone();for(int p=0;p<n;p++)if(core[p])inside[p]=true;
        for(int p=0;p<n;p++)if(core[p]){int x=p%w,y=p/w;
            for(int yy=Math.max(0,y-2);yy<=Math.min(h-1,y+2);yy++)for(int xx=Math.max(0,x-2);xx<=Math.min(w-1,x+2);xx++){
                int q=yy*w+xx;if(xx>box[0]&&xx<box[2]-1&&yy>box[1]&&yy<box[3]-1&&neutral(pixels[q])){glyph[q]=true;inside[q]=true;}
            }
        }
        Mask proposed=new Mask(new boolean[n],inside,false,0,null,true,glyph);
        Mask repaired=GrayGlyphRepair.repair(pixels,w,h,proposed);
        return repaired==null?proposed:evidence(repaired,BackgroundKind.TEXTURED_ART,"verified_texture_glyphs_only");
    }
    /** Keep each line's proven glyphs even when a neighboring callout is unsafe. */
    private static Mask recoverSeparateLines(int[] pixels,int w,int h,int[][] lines,int size,Mask refused){
        boolean[] erase=new boolean[pixels.length],inside=new boolean[pixels.length];int[] colors=pixels.clone();int count=0;
        if(refused.whiteBackground)for(int p=0;p<pixels.length;p++){inside[p]=refused.interior[p];if(refused.erase[p]){erase[p]=true;count++;colors[p]=refused.fillColors==null?0xffffffff:refused.fillColors[p];}}
        Mask empty=new Mask(new boolean[pixels.length],new boolean[pixels.length],false,0,null,false);
        for(int[] line:lines){
            int localSize=Math.max(1,Math.min(line[2]-line[0],line[3]-line[1]));
            Mask part=findLocalWhiteText(pixels,w,h,new int[][]{line},localSize,empty,2,225);
            if(!part.whiteBackground)continue;
            for(int p=0;p<pixels.length;p++){inside[p]|=part.interior[p];if(part.erase[p]){if(!erase[p])count++;erase[p]=true;colors[p]=part.fillColors==null?0xffffffff:part.fillColors[p];}}
        }
        return count==0?refused:new Mask(erase,inside,true,count,colors,false,null,BackgroundKind.PLAIN_PAPER,"local_contrast_glyphs_only");
    }
    /** Expand ink evidence only; never expand the accepted paper fill rectangle. */
    private static Mask completeLocalParagraphInk(int[] pixels,int w,int h,int[][] lines,int size,int[] paragraph,Mask original){
        if(paragraph==null||lines.length==0||size<=0||!original.whiteBackground||original.backgroundKind!=BackgroundKind.PLAIN_PAPER)return original;
        Mask refused=new Mask(new boolean[pixels.length],new boolean[pixels.length],false,0,null,false);
        // A paragraph can span several balloons. An open paper patch supplies
        // no evidence for distant columns: search at most one glyph from anchors.
        int pad=Math.max(2,Math.min(40,size));int[][] near=new int[lines.length][4];
        for(int i=0;i<lines.length;i++){int[] b=lines[i];near[i]=new int[]{Math.max(paragraph[0],b[0]-pad),Math.max(paragraph[1],b[1]-pad),Math.min(paragraph[2],b[2]+pad),Math.min(paragraph[3],b[3]+pad)};}
        Mask extra=findLocalWhiteText(pixels,w,h,near,size,refused,2);
        if(!extra.whiteBackground)return original;
        boolean[] erase=original.erase.clone(),inside=original.interior.clone();
        int[] colors=original.fillColors==null?null:original.fillColors.clone();
        if(colors==null&&extra.fillColors!=null){colors=new int[pixels.length];Arrays.fill(colors,0xffffffff);}
        int added=0,count=0;
        for(int p=0;p<pixels.length;p++){
            if(extra.erase[p]&&!erase[p]&&!original.interior[p]){
                erase[p]=true;inside[p]=true;added++;
                if(colors!=null)colors[p]=extra.fillColors==null?0xffffffff:extra.fillColors[p];
            }
            if(erase[p])count++;
        }
        return added==0?original:new Mask(erase,inside,true,count,colors,false,null,BackgroundKind.PLAIN_PAPER,original.evidence.equals("local_contrast_glyphs_only")?original.evidence:"original_paper_plus_complete_local_ink");
    }
    private static Mask forTextParagraph(int[] pixels,int width,int height,int[][] lines,int characterSize,int[] paragraphBoundsInRoi){
        if(paragraphBoundsInRoi==null)return forTextBase(pixels,width,height,lines,characterSize);
        if(paragraphBoundsInRoi.length!=4||paragraphBoundsInRoi[2]<=paragraphBoundsInRoi[0]||paragraphBoundsInRoi[3]<=paragraphBoundsInRoi[1])throw new IllegalArgumentException("Invalid paragraph bounds");
        // A fully anchored single glyph/sound effect needs no paragraph recovery. Keep its original
        // art/background verdict instead of letting another closed white patch change that verdict.
        if(!extendsTextScope(width,height,lines,characterSize,paragraphBoundsInRoi))return recoverColoredInk(pixels,width,height,lines,characterSize,paragraphBoundsInRoi,forTextBase(pixels,width,height,lines,characterSize));
        Mask closed=findAtThreshold(pixels,width,height,lines,characterSize,225,paragraphBoundsInRoi);
        if(closed.whiteBackground){closed=evidence(closed,BackgroundKind.PLAIN_PAPER,"verified_closed_paper_paragraph_recovery");Mask classified=classify(pixels,width,height,lines,characterSize,closed);if(classified.backgroundKind==BackgroundKind.PLAIN_PAPER)return classified;}
        return recoverColoredInk(pixels,width,height,lines,characterSize,paragraphBoundsInRoi,forTextBase(pixels,width,height,lines,characterSize));
    }
    /** Recover only the inner portion of anchored ink; its frame/exterior connection stays protected. */
    private static Mask recoverFrameConnectedInk(int[] pixels,int w,int h,int[][] lines,int size,int[] paragraph,Mask original){
        Mask single=recoverPaperInk(pixels,w,h,lines,size,paragraph,original,false,false);
        // Never disturb a successful existing cleanup. Only a refused region may
        // try several genuinely closed paper pockets with the same strict guard.
        if(single.whiteBackground)return single;
        Mask pockets=recoverPaperInk(pixels,w,h,lines,size,paragraph,single,true,false);
        if(pockets.whiteBackground)return pockets;
        return recoverPaperInk(pixels,w,h,lines,size,paragraph,pockets,true,true);
    }
    private static Mask recoverPaperInk(int[] pixels,int w,int h,int[][] lines,int size,int[] paragraph,Mask original,boolean multi,boolean enhanced){
        if((lines.length==0&&paragraph==null)||size<=0||original.texturedBackground)return original;
        int n=pixels.length,margin=Math.max(2,Math.min(12,size/3));
        boolean[] anchors=new boolean[n],scope=new boolean[n];
        for(int[] r:lines)for(int y=Math.max(0,r[1]-margin);y<Math.min(h,r[3]+margin);y++)for(int x=Math.max(0,r[0]-margin);x<Math.min(w,r[2]+margin);x++){
            int p=y*w+x;scope[p]=true;if(x>=r[0]&&x<r[2]&&y>=r[1]&&y<r[3])anchors[p]=true;
        }
        // Line splitting deliberately discards ink connected to the frame. Its
        // detector paragraph can supply search scope, never a rectangular fill.
        if(paragraph!=null)for(int y=Math.max(0,paragraph[1]);y<Math.min(h,paragraph[3]);y++)for(int x=Math.max(0,paragraph[0]);x<Math.min(w,paragraph[2]);x++){
            anchors[y*w+x]=true;scope[y*w+x]=true;
        }
        if(enhanced){int anchored=0,white=0;
            for(int p=0;p<n;p++)if(anchors[p]){anchored++;if(neutral(pixels[p])&&brightness(pixels[p])>=225)white++;}
            // White lettering on dark artwork is not white paper. Enhanced
            // recovery requires a majority of actual white background samples.
            if(anchored==0||white<=anchored*.50)return original;
        }
        int[] labels=new int[n],queue=new int[n];boolean[] exterior=new boolean[n+1],selected=new boolean[n+1];
        int label=0,best=0,bestHits=0,bestArea=0,totalHits=0;byte[] light=new byte[n];
        for(int p=0;p<n;p++)light[p]=(byte)(neutral(pixels[p])?brightness(pixels[p]):0);
        for(int seed=0;seed<n;seed++)if(labels[seed]==0&&(light[seed]&255)>=225){
            int head=0,end=1,hits=0;boolean edge=false;queue[0]=seed;labels[seed]=++label;
            while(head<end){int p=queue[head++],x=p%w,y=p/w;if(anchors[p])hits++;if(x==0||y==0||x==w-1||y==h-1)edge=true;
                if(x>0)end=push(p-1,label,end,labels,light,queue,225);if(x+1<w)end=push(p+1,label,end,labels,light,queue,225);
                if(y>0)end=push(p-w,label,end,labels,light,queue,225);if(y+1<h)end=push(p+w,label,end,labels,light,queue,225);
            }
            exterior[label]=edge;
            if(multi){
                if(!edge&&end>=Math.max(16,size*size/64)&&hits>=4){selected[label]=true;totalHits+=hits;bestArea+=end;if(hits>bestHits){best=label;bestHits=hits;}}
            }else if(!edge&&end>=Math.max(64,size*size/5)){totalHits+=hits;if(hits>bestHits){best=label;bestHits=hits;bestArea=end;}}
        }
        // Need one dominant genuinely closed white region, not a box-shaped invented boundary.
        if(best==0||(multi?totalHits<Math.max(12,size):bestHits<Math.max(12,size)||bestHits<totalHits*.8))return original;
        if(!multi)selected[best]=true;
        // Light gray background is exterior paper too. Flood it from the crop
        // edge without crossing dark outlines; a leak into selected white paper
        // disproves closure and must not be repaired by a detector rectangle.
        boolean[] outerTone=new boolean[n];int outerHead=0,outerEnd=0;
        for(int p=0;p<n;p++)if((p%w==0||p%w==w-1||p<w||p>=n-w)&&(light[p]&255)>=96){outerTone[p]=true;queue[outerEnd++]=p;}
        while(outerHead<outerEnd){int p=queue[outerHead++],x=p%w,y=p/w;
            for(int d=0;d<4;d++){int q=d==0?(x>0?p-1:-1):d==1?(x+1<w?p+1:-1):d==2?(y>0?p-w:-1):(y+1<h?p+w:-1);
                if(q>=0&&!outerTone[q]&&(light[q]&255)>=96){outerTone[q]=true;queue[outerEnd++]=q;}}
        }
        if(enhanced)for(int p=0;p<n;p++)if(selected[labels[p]]&&outerTone[p])return original;
        // JPEG fringes and slightly off-white paper are common. Confirm a narrow
        // neutral paper distribution and restore its median shade, not pure white.
        int[] shades=new int[256],coreShades=new int[256];int coreArea=0;
        for(int p=0;p<n;p++)if(selected[labels[p]]){
            shades[light[p]&255]++;int x=p%w,y=p/w;boolean core=x>=2&&y>=2&&x<w-2&&y<h-2;
            if(core)for(int yy=y-2;yy<=y+2&&core;yy++)for(int xx=x-2;xx<=x+2;xx++)if(!selected[labels[yy*w+xx]]){core=false;break;}
            if(core){coreShades[light[p]&255]++;coreArea++;}
        }
        // JPEG/antialias fringes describe the stroke edge, not the paper shade.
        // Use an eroded paper sample only when it retains substantial area.
        int paperArea=bestArea;if(enhanced&&coreArea>=Math.max(64,bestArea*.35)){shades=coreShades;paperArea=coreArea;}
        int paperMedian=225,shadeCount=0;for(int v=225;v<256;v++){shadeCount+=shades[v];if(shadeCount*2>=paperArea){paperMedian=v;break;}}
        int flatPaper=0;for(int v=Math.max(225,paperMedian-8);v<=Math.min(255,paperMedian+8);v++)flatPaper+=shades[v];
        if(paperMedian<235||flatPaper<paperArea*.90)return original;
        int paperColor=0xff000000|(paperMedian<<16)|(paperMedian<<8)|paperMedian;
        // Distance from exterior paper through all pixels; connected outer handwriting
        // cannot become an interior seed just because it shares ink with the frame.
        int[] distance=new int[n];Arrays.fill(distance,-1);int head=0,end=0;
        for(int p=0;p<n;p++)if(p%w==0||p%w==w-1||p<w||p>=n-w||(labels[p]>0&&exterior[labels[p]])||(enhanced&&outerTone[p])||!neutral(pixels[p])){
            distance[p]=0;queue[end++]=p;
        }
        while(head<end){int p=queue[head++],x=p%w,y=p/w;
            for(int d=0;d<4;d++){int q=d==0?(x>0?p-1:-1):d==1?(x+1<w?p+1:-1):d==2?(y>0?p-w:-1):(y+1<h?p+w:-1);
                if(q>=0&&distance[q]<0){distance[q]=distance[p]+1;queue[end++]=q;}}
        }
        // Estimate the inner frame thickness from paper/ink interface samples near
        // exterior paper. Keep a high-quantile guard plus three pixels for antialiasing.
        int[] histogram=new int[33];int samples=0;
        for(int p=0;p<n;p++)if(labels[p]==0&&distance[p]>0&&distance[p]<=32){int x=p%w,y=p/w;
            if((x>0&&selected[labels[p-1]])||(x+1<w&&selected[labels[p+1]])||(y>0&&selected[labels[p-w]])||(y+1<h&&selected[labels[p+w]])){histogram[distance[p]]++;samples++;}}
        if(samples<16)return original;
        int guard=0,cumulative=0;for(int i=1;i<histogram.length;i++){cumulative+=histogram[i];if(cumulative>=samples*(enhanced?.25:.90)){guard=i+3;break;}}
        guard=Math.max(3,guard);
        boolean keep=original.whiteBackground&&original.backgroundKind==BackgroundKind.PLAIN_PAPER;
        boolean[] erase=keep?original.erase.clone():new boolean[n],interior=keep?original.interior.clone():new boolean[n];
        int[] colors=keep&&original.fillColors!=null?original.fillColors.clone():null;
        if(colors==null&&paperMedian<255){colors=new int[n];Arrays.fill(colors,keep?0xffffffff:paperColor);}
        int added=0,anchoredAdded=0,radius=Math.max(12,Math.min(120,size*2));
        int[] dx={1,-1,0,0,1,-1,1,-1},dy={0,0,1,-1,1,-1,-1,1};
        for(int p=0;p<n;p++)if(scope[p]&&!erase[p]&&neutral(pixels[p])&&(light[p]&255)<245&&distance[p]>guard){
            int x=p%w,y=p/w,support=0,pairs=0,rays=0;
            // rectangleMask already commits this original paper pixel; do not
            // replace a successful earlier fill with a different recovered shade.
            if(keep&&paragraph!=null&&original.interior[p]&&x>=paragraph[0]-2&&x<paragraph[2]+2&&y>=paragraph[1]-2&&y<paragraph[3]+2)continue;
            for(int d=0;d<8;d++)for(int step=1;step<=radius;step++){
                int xx=x+dx[d]*step,yy=y+dy[d]*step;if(xx<0||yy<0||xx>=w||yy>=h)break;
                int q=yy*w+xx;if(selected[labels[q]]){rays|=1<<d;support++;break;}
                if((labels[q]>0&&exterior[labels[q]])||!neutral(pixels[q]))break;
            }
            for(int d=0;d<8;d+=2)if((rays&(3<<d))==(3<<d))pairs++;
            if(support>=7&&pairs>=3){erase[p]=true;interior[p]=true;if(colors!=null)colors[p]=paperColor;added++;if(anchors[p])anchoredAdded++;}
        }
        // Do not leave half of a detached stroke after a conservative ray test.
        // Complete neutral islands entirely within the text scope and touching proven paper,
        // with no exterior-paper contact. Frame-connected components fail this test.
        boolean[] seen=new boolean[n];int inkThreshold=Math.max(160,paperMedian-12);
        for(int seed=0;seed<n;seed++)if(scope[seed]&&!seen[seed]&&(light[seed]&255)<inkThreshold){
            head=0;end=1;queue[0]=seed;seen[seed]=true;boolean contained=true,seeded=false,outer=false,neutralInk=true;int paperContact=0,hits=0,left=w,top=h,right=0,bottom=0;
            while(head<end){int p=queue[head++],x=p%w,y=p/w;contained&=scope[p];seeded|=erase[p];neutralInk&=neutral(pixels[p]);if(anchors[p])hits++;
                left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x+1);bottom=Math.max(bottom,y+1);outer|=x==0||y==0||x==w-1||y==h-1;
                for(int yy=Math.max(0,y-1);yy<=Math.min(h-1,y+1);yy++)for(int xx=Math.max(0,x-1);xx<=Math.min(w-1,x+1);xx++){
                    int q=yy*w+xx;if(labels[q]>0&&exterior[labels[q]])outer=true;if(selected[labels[q]])paperContact++;
                    if(!seen[q]&&(light[q]&255)<inkThreshold){seen[q]=true;queue[end++]=q;}
                }
            }
            if(contained&&(seeded||paperContact>0)&&!outer&&neutralInk&&hits>=end*.98&&end<=Math.max(64,size*size*3)&&Math.max(right-left,bottom-top)<=size*4){
                for(int i=0;i<end;i++){int p=queue[i];if(!erase[p]){erase[p]=true;interior[p]=true;if(colors!=null)colors[p]=paperColor;added++;if(anchors[p])anchoredAdded++;}}
            }
        }
        if(added<4||anchoredAdded<Math.max(4,added*.5))return original;
        int count=0;for(int p=0;p<n;p++){if(selected[labels[p]]){if(colors!=null&&!interior[p])colors[p]=pixels[p];interior[p]=true;}if(erase[p])count++;}
        return new Mask(erase,interior,true,count,colors,false,null,BackgroundKind.PLAIN_PAPER,"closed_paper_inner_ink_with_frame_guard");
    }
    /** Preserve every accepted original mask; only failed closed-paper color ink may use paragraph evidence. */
    private static Mask recoverColoredInk(int[] pixels,int w,int h,int[][] lines,int size,int[] paragraph,Mask original){
        if(original.whiteBackground)return original;
        Mask anchored=findAtThreshold(pixels,w,h,lines,size,225,null,true);
        if(!anchored.closedColoredInk)return original;
        Mask recovered=findAtThreshold(pixels,w,h,lines,size,225,paragraph,true);
        if(recovered.whiteBackground){Mask classified=classify(pixels,w,h,lines,size,recovered);if(classified.backgroundKind==BackgroundKind.PLAIN_PAPER)return classified;}
        return original;
    }
    private static boolean extendsTextScope(int width,int height,int[][] lines,int size,int[] paragraph){
        int margin=Math.max(2,(int)(size*.65)),extra=0,minimum=Math.max(4,size);
        for(int y=Math.max(0,paragraph[1]);y<Math.min(height,paragraph[3]);y++)for(int x=Math.max(0,paragraph[0]);x<Math.min(width,paragraph[2]);x++){
            boolean anchored=false;for(int[] line:lines)if(x>=line[0]-margin&&x<line[2]+margin&&y>=line[1]-margin&&y<line[3]+margin){anchored=true;break;}
            if(!anchored&&++extra>=minimum)return true;
        }
        return false;
    }
    /** Detector paragraph bounds may provide background evidence, but never become an erase/layout mask. */
    static Mask classifyOnly(int[] pixels,int width,int height,int[][] textBounds){
        if(width<=0||height<=0||pixels.length!=Math.multiplyExact(width,height)||pixels.length>4_000_000)throw new IllegalArgumentException("Invalid classification crop");
        int size=16;if(textBounds.length>0){int[] sizes=new int[textBounds.length];for(int i=0;i<sizes.length;i++)sizes[i]=Math.max(1,Math.min(textBounds[i][2]-textBounds[i][0],textBounds[i][3]-textBounds[i][1]));Arrays.sort(sizes);size=sizes[sizes.length/2];}
        return classify(pixels,width,height,textBounds,size,new Mask(new boolean[pixels.length],new boolean[pixels.length],false,0,null,false));
    }
    private static Mask evidence(Mask mask,BackgroundKind kind,String reason){
        return new Mask(mask.erase,mask.interior,mask.whiteBackground,mask.pixels,mask.fillColors,mask.texturedBackground,mask.glyphCandidate,kind,reason);
    }
    /** Classification describes the paper behind the detected text, never the surrounding render ROI. */
    private static Mask classify(int[] pixels,int w,int h,int[][] lines,int size,Mask mask){
        if(mask.texturedBackground)return evidence(mask,BackgroundKind.TEXTURED_ART,"verified_patterned_paper");
        if(mask.whiteBackground){
            if(paperCompartments(pixels,w,h,mask,size)>1)return evidence(mask,BackgroundKind.UNCERTAIN,"multiple_separate_paper_areas");
            return evidence(mask,BackgroundKind.PLAIN_PAPER,mask.evidence.equals("local_glyphs_with_protected_outline")||mask.evidence.equals("verified_closed_paper_paragraph_recovery")?mask.evidence:"verified_local_paper_and_glyphs");
        }
        boolean[] scope=new boolean[pixels.length],band=new boolean[pixels.length];int margin=Math.max(2,Math.min(8,size/4));
        for(int[] r:lines)for(int y=Math.max(0,r[1]-margin);y<Math.min(h,r[3]+margin);y++)for(int x=Math.max(0,r[0]-margin);x<Math.min(w,r[2]+margin);x++){
            int p=y*w+x;boolean inside=x>=r[0]&&x<r[2]&&y>=r[1]&&y<r[3];if(inside)scope[p]=true;
            if(x<r[0]+margin||x>=r[2]-margin||y<r[1]+margin||y>=r[3]-margin)band[p]=true;
        }
        int total=0,nonwhite=0,midtone=0,colored=0,edgeTotal=0,edgeNonwhite=0,edgeColored=0,pairs=0,rough=0;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){int p=y*w+x,v=brightness(pixels[p]);boolean color=!neutral(pixels[p]);
            if(band[p]){edgeTotal++;if(v<235)edgeNonwhite++;if(color)edgeColored++;}
            if(!scope[p])continue;total++;if(v<235)nonwhite++;if(v>=160&&v<235)midtone++;if(color)colored++;
            if(v>=160)for(int d=0;d<2;d++){int q=d==0?(x+1<w?p+1:-1):(y+1<h?p+w:-1);
                if(q>=0&&scope[q]&&brightness(pixels[q])>=160){pairs++;if(Math.abs(v-brightness(pixels[q]))>24)rough++;}}
        }
        if(total<24||edgeTotal<12)return evidence(mask,BackgroundKind.UNCERTAIN,"insufficient_text_background_samples");
        String background=null;
        if(colored>total*.20&&edgeColored>edgeTotal*.25)background="color_under_text_and_edges";
        else if(nonwhite>total*.60&&edgeNonwhite>edgeTotal*.70)background="dark_or_gray_under_text_and_edges";
        else if(midtone>total*.20&&rough>=12&&rough>pairs*.10)background="midtone_texture_between_strokes";
        if(background!=null){
            if(hasPaperPocket(pixels,w,h,scope,total))return evidence(mask,BackgroundKind.UNCERTAIN,"mixed_paper_and_background");
            return evidence(mask,BackgroundKind.TEXTURED_ART,background);
        }
        return evidence(mask,BackgroundKind.UNCERTAIN,"paper_or_mixed_content_not_confirmed");
    }
    /** Large contiguous white spaces inside a dark/colored paragraph prevent a confident whole-area background verdict. */
    private static boolean hasPaperPocket(int[] pixels,int w,int h,boolean[] scope,int count){
        boolean[] seen=new boolean[pixels.length];int[] queue=new int[pixels.length];
        for(int seed=0;seed<pixels.length;seed++)if(scope[seed]&&!seen[seed]&&neutral(pixels[seed])&&brightness(pixels[seed])>=245){
            int head=0,end=1,left=w,top=h,right=0,bottom=0;queue[0]=seed;seen[seed]=true;
            while(head<end){int p=queue[head++],x=p%w,y=p/w;left=Math.min(left,x);right=Math.max(right,x+1);top=Math.min(top,y);bottom=Math.max(bottom,y+1);
                for(int d=0;d<4;d++){int q=d==0?(x>0?p-1:-1):d==1?(x+1<w?p+1:-1):d==2?(y>0?p-w:-1):(y+1<h?p+w:-1);
                    if(q>=0&&scope[q]&&!seen[q]&&neutral(pixels[q])&&brightness(pixels[q])>=245){seen[q]=true;queue[end++]=q;}}
            }
            if(end>=Math.max(64,count*.05)&&Math.min(right-left,bottom-top)>=8)return true;
        }
        return false;
    }
    private static int paperCompartments(int[] pixels,int w,int h,Mask mask,int size){
        boolean[] seen=new boolean[pixels.length];int[] queue=new int[pixels.length];int compartments=0,minInk=Math.max(4,size),paperThreshold=mask.fillColors==null||mask.closedColoredInk?225:160;
        for(int seed=0;seed<pixels.length;seed++)if(mask.erase[seed]&&!seen[seed]){
            int head=0,end=1,ink=0;queue[0]=seed;seen[seed]=true;
            while(head<end){int p=queue[head++],x=p%w,y=p/w;if(mask.erase[p]&&brightness(pixels[p])<225)ink++;
                for(int d=0;d<4;d++){int q=d==0?(x>0?p-1:-1):d==1?(x+1<w?p+1:-1):d==2?(y>0?p-w:-1):(y+1<h?p+w:-1);
                    if(q>=0&&!seen[q]&&(mask.erase[q]||(neutral(pixels[q])&&brightness(pixels[q])>=paperThreshold))){seen[q]=true;queue[end++]=q;}}
            }
            if(ink>=minInk&&++compartments>1)return compartments;
        }
        return compartments;
    }
    /** A protected frame may dominate a box, but the middle of every actual text line must still be recovered. */
    private static boolean recoversLineInk(int[] pixels,int w,int h,int[][] lines,boolean[] erase){
        int total=0,recovered=0;
        for(int[] r:lines){int rw=r[2]-r[0],rh=r[3]-r[1],ix=Math.max(1,rw/(rw>rh?10:5)),iy=Math.max(1,rh/(rw>rh?5:10));
            int ink=0,cleaned=0;
            for(int y=Math.max(0,r[1]+iy);y<Math.min(h,r[3]-iy);y++)for(int x=Math.max(0,r[0]+ix);x<Math.min(w,r[2]-ix);x++){
                int p=y*w+x;if(brightness(pixels[p])<160){ink++;if(erase[p])cleaned++;}}
            if(ink>=4&&cleaned<ink*.80)return false;total+=ink;recovered+=cleaned;
        }
        return total>=4&&recovered>=total*.90;
    }
    /** A caption on plain white paper needs glyph-local cleanup, even without an enclosing balloon outline. */
    private static Mask findLocalWhiteText(int[] pixels,int width,int height,int[][] lines,int characterSize,Mask refused){
        Mask tight=findLocalWhiteText(pixels,width,height,lines,characterSize,refused,2);
        if(tight.whiteBackground)return tight;
        // An anchor can clip a stroke or nearby ruby. Retry complete components in a
        // bounded halo, keeping the original anchor as the required ink seed.
        int margin=Math.max(2,Math.min(12,characterSize/3));
        return margin==2?tight:findLocalWhiteText(pixels,width,height,lines,characterSize,refused,margin);
    }
    private static Mask findLocalWhiteText(int[] pixels,int width,int height,int[][] lines,int characterSize,Mask refused,int margin){
        return findLocalWhiteText(pixels,width,height,lines,characterSize,refused,margin,245);
    }
    private static Mask findLocalWhiteText(int[] pixels,int width,int height,int[][] lines,int characterSize,Mask refused,int margin,int inkThreshold){
        int n=pixels.length;boolean[] scope=new boolean[n];int count=0,white=0,dark=0,min=255,max=0;long shade=0;
        boolean[] anchor=new boolean[n];
        for(int[] r:lines)for(int y=Math.max(1,r[1]-2);y<Math.min(height-1,r[3]+2);y++)for(int x=Math.max(1,r[0]-2);x<Math.min(width-1,r[2]+2);x++)anchor[y*width+x]=true;
        for(int[] r:lines)for(int y=Math.max(1,r[1]-margin);y<Math.min(height-1,r[3]+margin);y++)for(int x=Math.max(1,r[0]-margin);x<Math.min(width-1,r[2]+margin);x++){
            int p=y*width+x;if(scope[p])continue;scope[p]=true;count++;int value=brightness(pixels[p]);
            if(!neutral(pixels[p]))return refused;
            if(value>=245){white++;shade+=value;min=Math.min(min,value);max=Math.max(max,value);}else if(value<inkThreshold)dark++;
        }
        if(count<16||dark<4||white<count*.55||max-min>10)return refused;
        boolean[] originalScope=scope.clone();
        byte[] seen=new byte[n];int[] queue=new int[n];boolean[] erase=new boolean[n];int removed=0,protectedDark=0,acceptedComponents=0;
        for(int seed=0;seed<n;seed++)if(originalScope[seed]&&seen[seed]==0&&brightness(pixels[seed])<inkThreshold){
            int head=0,end=1,left=width,top=height,right=0,bottom=0;boolean contained=true,anchored=false;queue[0]=seed;seen[seed]=1;
            while(head<end){int p=queue[head++],x=p%width,y=p/width;contained&=originalScope[p]&&neutral(pixels[p]);anchored|=anchor[p];left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x+1);bottom=Math.max(bottom,y+1);
                for(int yy=Math.max(0,y-1);yy<=Math.min(height-1,y+1);yy++)for(int xx=Math.max(0,x-1);xx<=Math.min(width-1,x+1);xx++){
                    int q=yy*width+xx;if(seen[q]==0&&brightness(pixels[q])<inkThreshold){seen[q]=1;queue[end++]=q;}
                }
            }
            // A connected frame/illustration extends outside the localized text scope and is never cleared.
            boolean elongated=false;
            // A narrow continuous stroke (long vowel/wave) may exceed three
            // character heights. Require its entire bounds inside one actual
            // line anchor, with an inset, rather than a larger paragraph box.
            for(int[] r:lines)if(left>=r[0]+2&&top>=r[1]+2&&right<=r[2]-2&&bottom<=r[3]-2
                &&Math.min(right-left,bottom-top)<=Math.max(4,characterSize*.60))elongated=true;
            boolean strictlyAnchored=inkThreshold==245;
            if(!strictlyAnchored)for(int[] r:lines)if(left>=r[0]+1&&top>=r[1]+1&&right<=r[2]-1&&bottom<=r[3]-1){strictlyAnchored=true;break;}
            boolean solidBlock=inkThreshold<245&&end>(right-left)*(bottom-top)*.85&&Math.min(right-left,bottom-top)>Math.max(6,characterSize/4);
            if(contained&&anchored&&strictlyAnchored&&!solidBlock&&end<=Math.max(16,characterSize*characterSize*2)
                &&(elongated||(right-left<=Math.max(5,characterSize*3)&&bottom-top<=Math.max(5,characterSize*3)))){
                for(int i=0;i<end;i++)erase[queue[i]]=true;removed+=end;acceptedComponents++;
                if(inkThreshold<245)for(int yy=Math.max(1,top-1);yy<Math.min(height-1,bottom+1);yy++)for(int xx=Math.max(1,left-1);xx<Math.min(width-1,right+1);xx++){
                    int q=yy*width+xx,v=brightness(pixels[q]);if(originalScope[q]&&v>=inkThreshold&&v<255)erase[q]=true;
                }
            }else{
                // The same ROI may include hair or an outline connected to outside artwork.
                // Keep that entire component and reserve a paper guard around it for layout.
                for(int i=0;i<end;i++){int p=queue[i],x=p%width,y=p/width;if(originalScope[p])protectedDark++;
                    for(int yy=Math.max(0,y-2);yy<=Math.min(height-1,y+2);yy++)for(int xx=Math.max(0,x-2);xx<=Math.min(width-1,x+2);xx++)scope[yy*width+xx]=false;
                }
            }
        }
        // The discarded connected component can be the balloon frame entering the detector box.
        // Its size says nothing about whether the separately contained glyphs were fully recovered.
        boolean ordinaryColumn=false;
        if(inkThreshold<245&&acceptedComponents>=3&&removed>=Math.max(20,characterSize*characterSize/4))
            for(int[] r:lines)if(Math.max(r[2]-r[0],r[3]-r[1])>=4*Math.min(r[2]-r[0],r[3]-r[1]))ordinaryColumn=true;
        if(removed<4||removed<(dark-protectedDark)*.98||(!ordinaryColumn&&removed<dark*.50&&!recoversLineInk(pixels,width,height,lines,erase)))return refused;
        if(inkThreshold<245){
            boolean[] expanded=erase.clone();
            for(int p=0;p<n;p++)if(erase[p]){int x=p%width,y=p/width;
                for(int yy=Math.max(0,y-1);yy<=Math.min(height-1,y+1);yy++)for(int xx=Math.max(0,x-1);xx<=Math.min(width-1,x+1);xx++){
                    int q=yy*width+xx,v=brightness(pixels[q]);if(scope[q]&&v>=inkThreshold&&v<245)expanded[q]=true;
                }
            }
            erase=expanded;removed=0;for(boolean v:erase)if(v)removed++;
        }
        int fill=(int)Math.round((double)shade/white);int[] colors=null;
        if(fill<255){colors=new int[n];java.util.Arrays.fill(colors,0xff000000|(fill<<16)|(fill<<8)|fill);}
        return new Mask(erase,scope,true,removed,colors,false,null,BackgroundKind.PLAIN_PAPER,inkThreshold<245?"local_contrast_glyphs_only":"local_glyphs_with_protected_outline");
    }
    static boolean[] excludeForeign(boolean[] interior,int width,int height,int[][] foreignLines){
        boolean[] safe=interior.clone();
        for(int[] r:foreignLines)for(int y=Math.max(0,r[1]-2);y<Math.min(height,r[3]+2);y++){
            int left=Math.max(0,Math.min(width,r[0]-2)),right=Math.max(0,Math.min(width,r[2]+2));
            if(right>left)Arrays.fill(safe,y*width+left,y*width+right,false);
        }
        return safe;
    }
    private static Mask findAtThreshold(int[] pixels,int width,int height,int[][] lines,int characterSize,int threshold) {
        return findAtThreshold(pixels,width,height,lines,characterSize,threshold,null);
    }
    private static Mask findAtThreshold(int[] pixels,int width,int height,int[][] lines,int characterSize,int threshold,int[] paragraphBounds) {
        return findAtThreshold(pixels,width,height,lines,characterSize,threshold,paragraphBounds,false);
    }
    private static Mask findAtThreshold(int[] pixels,int width,int height,int[][] lines,int characterSize,int threshold,int[] paragraphBounds,boolean coloredInkRecovery) {
        return findAtThreshold(pixels,width,height,lines,characterSize,threshold,paragraphBounds,coloredInkRecovery,false);
    }
    private static Mask findAtThreshold(int[] pixels,int width,int height,int[][] lines,int characterSize,int threshold,int[] paragraphBounds,boolean coloredInkRecovery,boolean irregularCoreRecovery) {
        int n=Math.multiplyExact(width,height);
        if(pixels.length!=n||n>4_000_000)throw new IllegalArgumentException("Invalid cleanup crop");
        byte[] gray=new byte[n];boolean[] core=new boolean[n],inside=new boolean[n],erase=new boolean[n];
        for(int i=0;i<n;i++)gray[i]=(byte)(neutral(pixels[i])?brightness(pixels[i]):0);
        int coreCount=0;boolean[] textScope=new boolean[n];
        for(int[] r:lines){int margin=Math.max(2,(int)(characterSize*.65));
            for(int y=Math.max(0,r[1]-margin);y<Math.min(height,r[3]+margin);y++)
                {int left=Math.max(0,Math.min(width,r[0]-margin)),right=Math.max(0,Math.min(width,r[2]+margin));if(right>left)Arrays.fill(textScope,y*width+left,y*width+right,true);}
        }
        // Missed columns may extend the anchored scope only within the detector paragraph.
        // Keep the original line margins above, without sweeping up unrelated outside marks.
        if(paragraphBounds!=null){for(int y=Math.max(0,paragraphBounds[1]);y<Math.min(height,paragraphBounds[3]);y++){
            int left=Math.max(0,Math.min(width,paragraphBounds[0])),right=Math.max(0,Math.min(width,paragraphBounds[2]));if(left<right)Arrays.fill(textScope,y*width+left,y*width+right,true);
        }}
        // Detector boxes are unclipped and padded, not exact ink bounds. Use an inset seed for confidence only.
        // Segmentation, erase and layout retain their actual closed-pixel boundary.
        for(int[] r:lines){int inset=Math.max(1,(int)(Math.min(r[2]-r[0],r[3]-r[1])*.15));
            for(int y=Math.max(0,r[1]+inset);y<Math.min(height,r[3]-inset);y++)
                for(int x=Math.max(0,r[0]+inset);x<Math.min(width,r[2]-inset);x++){int p=y*width+x;if(!core[p]){core[p]=true;coreCount++;}}
        }
        int[] labels=new int[n],queue=new int[n];int label=0,selectedWhite=0,selectedHits=0,dominantLabel=0,dominantHits=0,dominantWhite=0;
        boolean[] chosen=new boolean[n+1],exterior=new boolean[n+1];
        // A single linear flood pass also rejects exterior whitespace; no repeated RGB conversions.
        for(int seed=0;seed<n;seed++)if(labels[seed]==0&&(gray[seed]&255)>=threshold){
            int head=0,end=1,hits=0;boolean edge=false;queue[0]=seed;labels[seed]=++label;
            while(head<end){int p=queue[head++],x=p%width,y=p/width;if(core[p])hits++;
                if(x==0||y==0||x==width-1||y==height-1)edge=true;
                if(x>0)end=push(p-1,label,end,labels,gray,queue,threshold);
                if(x+1<width)end=push(p+1,label,end,labels,gray,queue,threshold);
                if(y>0)end=push(p-width,label,end,labels,gray,queue,threshold);
                if(y+1<height)end=push(p+width,label,end,labels,gray,queue,threshold);
            }
            exterior[label]=edge;
            if(!edge&&hits>=Math.max(4,characterSize)&&end>=Math.max(30,characterSize*characterSize)){
                chosen[label]=true;selectedWhite+=end;for(int i=0;i<end;i++)inside[queue[i]]=true;
                selectedHits+=hits;if(hits>dominantHits){dominantLabel=label;dominantHits=hits;dominantWhite=end;}
            }
        }
        // A coarse anchor can cross the balloon outline into a separate patch of white artwork.
        // Recover only a single overwhelmingly supported closed component, never that unrelated patch.
        if(paragraphBounds!=null&&dominantLabel>0&&dominantHits>=selectedHits*.80){Arrays.fill(chosen,false);chosen[dominantLabel]=true;selectedWhite=dominantWhite;for(int p=0;p<n;p++)inside[p]=labels[p]==dominantLabel;}
        // Only ink may be colored. Paper flood/closure stays neutral; gray and textured paper are not broadened.
        int brightPaper=0;if(coloredInkRecovery)for(int p=0;p<n;p++)if(inside[p]&&brightness(pixels[p])>=245)brightPaper++;
        boolean allowColored=coloredInkRecovery&&threshold==225&&selectedWhite>0&&brightPaper>=selectedWhite*.90,foundColoredInk=false;
        // A closed paper region may also contain undetected neighboring lettering or isolated art.
        // Clear only islands near the actual text anchors, including their nearby kana/ruby.
        for(int seed=0;seed<n;seed++)if(labels[seed]==0){
            int head=0,end=1,contact=0,outside=0,nearText=0,anchorInk=0,minX=width,maxX=0,minY=height,maxY=0;boolean edge=false;queue[0]=seed;labels[seed]=-1;
            while(head<end){int p=queue[head++],x=p%width,y=p/width;
                if(textScope[p])nearText++;minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
                for(int[] r:lines)if(x>=r[0]&&x<r[2]&&y>=r[1]&&y<r[3]){anchorInk++;break;}
                if(x==0||y==0||x==width-1||y==height-1)edge=true;
                for(int yy=Math.max(0,y-1);yy<=Math.min(height-1,y+1);yy++)for(int xx=Math.max(0,x-1);xx<=Math.min(width-1,x+1);xx++){
                    int q=yy*width+xx;if(labels[q]>0){if(chosen[labels[q]])contact++;else if(exterior[labels[q]])outside++;}
                    else if(labels[q]==0){labels[q]=-1;queue[end++]=q;}
                }
            }
            boolean monochrome=true;for(int i=0;i<end;i++)if(!neutral(pixels[queue[i]])){monochrome=false;break;}
            boolean localized=nearText>=end*.98&&(threshold>=225||(Math.max(maxX-minX+1,maxY-minY+1)<=characterSize*5&&Math.min(maxX-minX+1,maxY-minY+1)<=characterSize*2));
            boolean coloredGlyph=allowColored&&Math.max(maxX-minX+1,maxY-minY+1)<=characterSize*5&&Math.min(maxX-minX+1,maxY-minY+1)<=characterSize*2;
            // A large hand-drawn glyph may occupy more than 29% of its balloon.
            // Relax only the ink-area cap: it must still be a detached, neutral
            // island, almost entirely inside actual text anchors, on closed white paper.
            boolean largeAnchoredGlyph=threshold==225&&monochrome&&anchorInk>=end*.95
                &&Math.max(maxX-minX+1,maxY-minY+1)<=characterSize*3
                &&Math.min(maxX-minX+1,maxY-minY+1)<=characterSize*2;
            if(!edge&&localized&&(monochrome||coloredGlyph)&&contact>0&&outside==0&&end<selectedWhite*(largeAnchoredGlyph?.9:.4)){
                foundColoredInk|=!monochrome;
                for(int i=0;i<end;i++){int p=queue[i];erase[p]=true;inside[p]=true;}
            }
        }
        // Fill the counters inside erased glyphs as well; they are bounded white islands, not exterior.
        int head=0,end=0;for(int p=0;p<n;p++)if(inside[p])queue[end++]=p;
        while(head<end){int p=queue[head++],x=p%width,y=p/width;
            for(int d=0;d<4;d++){int q=d==0?(x>0?p-1:-1):d==1?(x+1<width?p+1:-1):d==2?(y>0?p-width:-1):(y+1<height?p+width:-1);
                if(q>=0&&!inside[q]&&labels[q]>0&&!exterior[labels[q]]){inside[q]=true;queue[end++]=q;}}
        }
        boolean[] expanded=erase.clone();
        int fringe=threshold<225?Math.max(2,Math.min(4,characterSize/8)):2;
        for(int p=0;p<n;p++)if(erase[p]){int x=p%width,y=p/width;
            for(int yy=Math.max(0,y-fringe);yy<=Math.min(height-1,y+fringe);yy++)for(int xx=Math.max(0,x-fringe);xx<=Math.min(width-1,x+fringe);xx++){
                int q=yy*width+xx;if(inside[q]&&(threshold<225||(gray[q]&255)<250))expanded[q]=true;
            }
        }
        erase=expanded;
        int covered=0,changed=0;
        for(int p=0;p<n;p++){if(core[p]&&inside[p])covered++;if(erase[p])changed++;}
        boolean valid=coreCount>0&&covered>=coreCount*.96&&changed>0;
        // An irregular outline can cut into a rectangular detector core. Accept
        // partial paper coverage only when complete isolated ink still recovers
        // the middle of every detected line; never add outline pixels to erase.
        if(!valid&&irregularCoreRecovery&&threshold==225&&coreCount>0&&covered>=coreCount*.85&&changed>0
            &&recoversLineInk(pixels,width,height,lines,erase))valid=true;
        // A coarse anchor may include the curved outline. The paragraph can independently confirm
        // dominant closed paper and recovered ink without making its outside rectangle editable.
        if(paragraphBounds!=null&&threshold==225){int area=0,paper=0,selected=0,ink=0,recovered=0;
            for(int y=Math.max(0,paragraphBounds[1]);y<Math.min(height,paragraphBounds[3]);y++)for(int x=Math.max(0,paragraphBounds[0]);x<Math.min(width,paragraphBounds[2]);x++){
                int p=y*width+x;area++;if((gray[p]&255)>=225){paper++;if(inside[p])selected++;}else{ink++;if(erase[p])recovered++;}}
            // Outline ink can dominate the paragraph, but strict core evidence never bypasses paper coverage.
            valid=changed>0&&area>0&&selected>=Math.max(30,area*.45)&&selected>=paper*.90&&(valid||recovered>=Math.max(4,ink*.60));
        }
        if(!valid){Arrays.fill(erase,false);Arrays.fill(inside,false);changed=0;}
        if(valid&&threshold<225){
            int pairs=0,edges=0;
            // Abrupt paper changes imply halftone/artwork under a transparent caption. Nearest-paper filling would make blotches.
            for(int y=0;y<height;y++)for(int x=0;x<width;x++){int p=y*width+x;if(!inside[p]||erase[p])continue;
                for(int d=0;d<2;d++){int q=d==0?(x+1<width?p+1:-1):(y+1<height?p+width:-1);
                    if(q>=0&&inside[q]&&!erase[q]){pairs++;if(Math.abs(brightness(pixels[p])-brightness(pixels[q]))>18)edges++;}}
            }
            if(edges>=10&&edges>pairs*.01){boolean[] candidate=erase;erase=new boolean[n];return new Mask(erase,inside,false,0,null,true,candidate);}
        }
        int[] fillColors=null;
        if(valid&&(threshold<225||foundColoredInk)){
            // Carry the nearest original paper shade across removed strokes, preserving gray/gradient caption backgrounds.
            fillColors=new int[n];head=0;end=0;
            for(int p=0;p<n;p++)if(inside[p]&&!erase[p]){fillColors[p]=pixels[p];queue[end++]=p;}
            while(head<end){int p=queue[head++],x=p%width,y=p/width;
                for(int d=0;d<4;d++){int q=d==0?(x>0?p-1:-1):d==1?(x+1<width?p+1:-1):d==2?(y>0?p-width:-1):(y+1<height?p+width:-1);
                    if(q>=0&&inside[q]&&fillColors[q]==0){fillColors[q]=fillColors[p];queue[end++]=q;}}
            }
        }
        Mask result=new Mask(erase,inside,valid,changed,fillColors,false);result.closedColoredInk=foundColoredInk;return result;
    }
    private static int push(int p,int label,int end,int[] labels,byte[] gray,int[] queue,int threshold){
        if(labels[p]==0&&(gray[p]&255)>=threshold){labels[p]=label;queue[end++]=p;}return end;
    }
    /** Refuse a cleanup that would remove another detected region's original ink, even if it was already rendered. */
    static boolean touchesForeignInk(int[] original,int width,int height,Mask mask,int[][] foreignLines){
        for(int[] line:foreignLines)for(int y=Math.max(0,line[1]);y<Math.min(height,line[3]);y++)
            for(int x=Math.max(0,line[0]);x<Math.min(width,line[2]);x++){
                int p=y*width+x;if(mask.erase[p]&&brightness(original[p])<225)return true;
            }
        return false;
    }
    /** Complex-background overlay is restricted to detected ink boxes, never inferred from exterior white. */
    static boolean[] complexTextArea(int[] pixels,int w,int h,int[][] lines){
        boolean[] area=new boolean[pixels.length];int count=0,white=0,colored=0;
        for(int[] r:lines)for(int y=Math.max(0,r[1]);y<Math.min(h,r[3]);y++)for(int x=Math.max(0,r[0]);x<Math.min(w,r[2]);x++){
            int p=y*w+x;if(area[p])continue;area[p]=true;count++;int c=pixels[p],red=(c>>>16)&255,green=(c>>>8)&255,blue=c&255;
            if(brightness(c)>225)white++;if(Math.max(red,Math.max(green,blue))-Math.min(red,Math.min(green,blue))>25)colored++;
        }
        return count>0&&(white<count*.35||colored>count*.20)?area:null;
    }
    static void apply(int[] pixels,Mask mask){for(int i=0;i<pixels.length;i++)if(mask.erase[i])pixels[i]=mask.fillColors==null?0xffffffff:mask.fillColors[i];}
    private static boolean neutral(int c){int r=(c>>>16)&255,g=(c>>>8)&255,b=c&255;return Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b))<=20;}
    private static int brightness(int c){return (((c>>>16)&255)*299+((c>>>8)&255)*587+(c&255)*114)/1000;}
}
