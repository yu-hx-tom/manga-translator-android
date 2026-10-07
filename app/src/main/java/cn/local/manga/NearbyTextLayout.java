package cn.local.manga;

import java.util.List;
import java.util.ArrayList;
import java.util.Collections;
import java.util.HashSet;
import java.util.concurrent.CancellationException;
import java.util.function.IntBinaryOperator;

/** Fallback geometry stays inside the detected original text box. Never truncates text. */
final class NearbyTextLayout {
    static final class Plan {
        int[] box, points;
        int columns;
        boolean vertical=true;
        float step, font, padding, offsetX, offsetY;
        float[][] placedCells;
        float[] placedSteps,placedFonts;
        boolean sourceGrouped;
        int rows(){return (points.length+columns-1)/columns;}
        float cellStep(int index){return placedSteps==null?step:placedSteps[index];}
        float cellFont(int index){return placedFonts==null?font:placedFonts[index];}
        // Top to bottom in each column, then move one column left.
        float cellLeft(int index){return placedCells!=null?placedCells[index][0]:box[0]+padding+offsetX+(vertical?columns-1-index/rows():index%columns)*step;}
        float cellTop(int index){return placedCells!=null?placedCells[index][1]:box[1]+padding+offsetY+(vertical?index%rows():index/columns)*step;}
    }
    static int overlap(int[] a,int[] b){return Math.max(0,Math.min(a[2],b[2])-Math.max(a[0],b[0]))*Math.max(0,Math.min(a[3],b[3])-Math.max(a[1],b[1]));}
    /** Keep every glyph size and relative position; move only inside the original target. */
    static boolean fitProtected(Plan plan,int[][] protectedBoxes){
        if(protectedBoxes==null||protectedBoxes.length==0)return true;
        int count=plan.points.length;long pairs=(long)count*protectedBoxes.length;if(pairs>4096)return false;
        float[][] cells=new float[count][3];
        float left=Float.MAX_VALUE,top=Float.MAX_VALUE,right=-Float.MAX_VALUE,bottom=-Float.MAX_VALUE;
        boolean collision=false;
        for(int i=0;i<count;i++){float x=plan.cellLeft(i),y=plan.cellTop(i),step=plan.cellStep(i);cells[i]=new float[]{x,y,step};
            left=Math.min(left,x);top=Math.min(top,y);right=Math.max(right,x+step);bottom=Math.max(bottom,y+step);
            for(int[] r:protectedBoxes)if(x<r[2]+2&&x+step>r[0]-2&&y<r[3]+2&&y+step>r[1]-2)collision=true;}
        if(!collision)return true;
        // An unusually dense page remains protected instead of spending unbounded layout time.
        int minX=(int)Math.ceil(plan.box[0]-left),maxX=(int)Math.floor(plan.box[2]-right);
        int minY=(int)Math.ceil(plan.box[1]-top),maxY=(int)Math.floor(plan.box[3]-bottom);
        if(minX>maxX||minY>maxY)return false;
        java.util.TreeSet<Integer> offsets=new java.util.TreeSet<>((a,b)->{int c=Long.compare(Math.abs((long)a),Math.abs((long)b));return c!=0?c:Integer.compare(a,b);});
        offsets.add(Math.max(minX,Math.min(maxX,0)));offsets.add(minX);offsets.add(maxX);
        // A closest free integer position has x=0, a target edge, or a protected-cell edge.
        for(float[] c:cells)for(int[] r:protectedBoxes){int before=(int)Math.floor(r[0]-2-(c[0]+c[2])),after=(int)Math.ceil(r[2]+2-c[0]);
            if(before>=minX&&before<=maxX)offsets.add(before);if(after>=minX&&after<=maxX)offsets.add(after);}
        long best=Long.MAX_VALUE,work=0;int bestX=0,bestY=0;
        for(int dx:offsets){if((long)dx*dx>best)break;
            if(Thread.currentThread().isInterrupted())throw new CancellationException();
            if((work+=pairs)>1_000_000)return false;
            List<int[]> blocked=new ArrayList<>();
            for(float[] c:cells)for(int[] r:protectedBoxes){float x=c[0]+dx;if(x>=r[2]+2||x+c[2]<=r[0]-2)continue;
                int start=Math.max(minY,(int)Math.floor(r[1]-2-(c[1]+c[2]))+1),end=Math.min(maxY,(int)Math.ceil(r[3]+2-c[1])-1);
                if(start<=end)blocked.add(new int[]{start,end});}
            blocked.sort((a,b)->Integer.compare(a[0],b[0]));
            int cursor=minY;Integer dy=null;
            for(int[] interval:blocked){if(interval[0]>cursor){int candidate=Math.max(cursor,Math.min(interval[0]-1,0));if(dy==null||Math.abs(candidate)<Math.abs(dy))dy=candidate;}
                cursor=Math.max(cursor,interval[1]+1);if(cursor>maxY)break;}
            if(cursor<=maxY){int candidate=Math.max(cursor,Math.min(maxY,0));if(dy==null||Math.abs(candidate)<Math.abs(dy))dy=candidate;}
            if(dy!=null){long distance=(long)dx*dx+(long)dy*dy;if(distance<best){best=distance;bestX=dx;bestY=dy;}}
        }
        if(best==Long.MAX_VALUE)return false;
        // Check the actual float coordinates before committing the single rigid translation.
        for(float[] c:cells){float x=c[0]+bestX,y=c[1]+bestY;if(x<plan.box[0]||y<plan.box[1]||x+c[2]>plan.box[2]||y+c[2]>plan.box[3])return false;
            for(int[] r:protectedBoxes)if(x<r[2]+2&&x+c[2]>r[0]-2&&y<r[3]+2&&y+c[2]>r[1]-2)return false;}
        plan.placedCells=new float[count][2];for(int i=0;i<count;i++){plan.placedCells[i][0]=cells[i][0]+bestX;plan.placedCells[i][1]=cells[i][1]+bestY;}
        return true;
    }
    /** Preserve page-scale lettering when the drawing canvas is only a repair crop. */
    static Plan forCrop(int pageWidth,int pageHeight,int[] absoluteAnchor,int cropLeft,int cropTop,boolean vertical,String text){
        return forCrop(pageWidth,pageHeight,absoluteAnchor,cropLeft,cropTop,vertical,text,null);
    }
    static Plan forCrop(int pageWidth,int pageHeight,int[] absoluteAnchor,int cropLeft,int cropTop,boolean vertical,String text,int[][] absoluteSourceLines){
        return forCrop(pageWidth,pageHeight,absoluteAnchor,cropLeft,cropTop,vertical,text,absoluteSourceLines,1f);
    }
    /** {@code scale} multiplies the preferred lettering size (workbench font control); 1 = original arithmetic. */
    static Plan forCrop(int pageWidth,int pageHeight,int[] absoluteAnchor,int cropLeft,int cropTop,boolean vertical,String text,int[][] absoluteSourceLines,float scale){
        Plan result=plan(pageWidth,pageHeight,absoluteAnchor,java.util.Collections.emptyList(),java.util.Collections.emptyList(),text,null,vertical,absoluteSourceLines,scale);
        result.box[0]-=cropLeft;result.box[2]-=cropLeft;result.box[1]-=cropTop;result.box[3]-=cropTop;
        if(result.placedCells!=null)for(float[] cell:result.placedCells){cell[0]-=cropLeft;cell[1]-=cropTop;}
        return result;
    }
    static Plan plan(int w,int h,int[] anchor,List<int[]> foreign,List<int[]> occupied,String text,IntBinaryOperator pixel){
        return plan(w,h,anchor,foreign,occupied,text,pixel,true,null);
    }
    static Plan plan(int w,int h,int[] anchor,List<int[]> foreign,List<int[]> occupied,String text,IntBinaryOperator pixel,boolean vertical,int[][] sourceLines){
        return plan(w,h,anchor,foreign,occupied,text,pixel,vertical,sourceLines,1f);
    }
    static Plan plan(int w,int h,int[] anchor,List<int[]> foreign,List<int[]> occupied,String text,IntBinaryOperator pixel,boolean vertical,int[][] sourceLines,float scale){
        if(w<1||h<1||anchor==null||anchor.length!=4)throw new IllegalArgumentException("无效页面或原文区域");
        if(Thread.currentThread().isInterrupted())throw new CancellationException();
        Plan best=new Plan();best.vertical=vertical;best.points=text.replaceAll("\\s+","").codePoints().toArray();
        if(best.points.length==0)throw new IllegalArgumentException("没有可嵌入的译文");
        int left=Math.max(0,anchor[0]),top=Math.max(0,anchor[1]),right=Math.min(w,anchor[2]),bottom=Math.min(h,anchor[3]);
        if(right<=left||bottom<=top)throw new IllegalArgumentException("原文区域不在页面内");
        best.box=new int[]{left,top,right,bottom};
        best.padding=Math.min(5f,Math.min(right-left,bottom-top)*.05f);
        float width=right-left-2*best.padding,height=bottom-top-2*best.padding;
        float maximumStep=BubbleLayout.preferredFont(BubbleLayout.sourceFont(sourceLines,best.box),sourceLines,best.points.length)*1.3f*scale;
        // The anchor is fixed: shrink the complete grid instead of moving into nearby empty space.
        for(int columns=1;columns<=best.points.length;columns++){
            if(Thread.currentThread().isInterrupted())throw new CancellationException();
            int rows=(best.points.length+columns-1)/columns;
            float step=Math.min(maximumStep,Math.min(width/columns,height/rows));
            if(step>best.step+.0001f||(!vertical&&Math.abs(step-best.step)<.0001f&&columns>best.columns)){best.step=step;best.columns=columns;}
        }
        // Prefer a continuous line in the source reading direction when an extra column/row
        // only buys a small size increase. This keeps tall exclamations from becoming wide grids.
        float readableStep=best.step*.85f;
        for(int count=1;count<=best.points.length;count++){
            if(Thread.currentThread().isInterrupted())throw new CancellationException();
            int columns=vertical?count:best.points.length+1-count,rows=(best.points.length+columns-1)/columns;
            float step=Math.min(maximumStep,Math.min(width/columns,height/rows));
            if(step>=readableStep){best.step=step;best.columns=columns;break;}
        }
        best.font=best.step/1.3f;
        best.offsetX=Math.max(0,(width-best.columns*best.step)/2);
        best.offsetY=Math.max(0,(height-best.rows()*best.step)/2);
        Plan grouped=sourceGrouped(w,h,best,sourceLines,text,scale);
        if(grouped!=null)return grouped;
        compactTrailingMarks(best,width,height);
        return best;
    }
    /** Keep a trailing mark run with its preceding character, at the existing body scale. */
    private static void compactTrailingMarks(Plan plan,float width,float height){
        if(!java.util.Arrays.stream(plan.points).anyMatch(Character::isLetterOrDigit))return;
        boolean needed=false;for(int point:plan.points)needed|=BubbleLayout.glyphAdvance(point)<1;if(!needed)return;
        List<int[]> units=BubbleLayout.units(plan.points);float along=plan.vertical?height:width,across=plan.vertical?width:height;
        float low=0,high=plan.step,step=plan.step;List<int[]> chunks=null;
        for(int attempt=0;attempt<25;attempt++){
            List<int[]> candidate=new ArrayList<>();int first=0;float used=0;boolean fits=true;
            for(int i=0;i<units.size();i++){float size=0;for(int j=units.get(i)[0];j<units.get(i)[1];j++)size+=step*BubbleLayout.glyphAdvance(plan.points[j]);
                if(size>along+.0001f){fits=false;break;}if(used+size>along+.0001f){candidate.add(new int[]{first,i});first=i;used=0;}used+=size;}
            candidate.add(new int[]{first,units.size()});fits&=candidate.size()*step<=across+.0001f;
            if(fits){chunks=candidate;low=step;if(attempt==0)break;}else high=step;
            step=(low+high)/2;
        }
        if(chunks==null)return;step=low;plan.step=step;plan.font=step/1.3f;
        plan.placedCells=new float[plan.points.length][2];plan.placedSteps=new float[plan.points.length];plan.placedFonts=new float[plan.points.length];
        float acrossStart=(across-chunks.size()*step)/2;
        for(int line=0;line<chunks.size();line++){int[] chunk=chunks.get(line);float length=0;for(int i=chunk[0];i<chunk[1];i++)for(int j=units.get(i)[0];j<units.get(i)[1];j++)length+=step*BubbleLayout.glyphAdvance(plan.points[j]);float cursor=(along-length)/2;
            for(int i=chunk[0];i<chunk[1];i++)for(int j=units.get(i)[0];j<units.get(i)[1];j++){float ratio=BubbleLayout.glyphAdvance(plan.points[j]),size=step*ratio;
                plan.placedCells[j][0]=plan.box[0]+plan.padding+(plan.vertical?acrossStart+(chunks.size()-1-line)*step+(step-size)/2:cursor);
                plan.placedCells[j][1]=plan.box[1]+plan.padding+(plan.vertical?cursor:acrossStart+line*step+(step-size)/2);
                plan.placedSteps[j]=size;plan.placedFonts[j]=plan.font*ratio;cursor+=size;}}
    }
    private static boolean phraseEnd(int point){return "，。！？；：、,.!?;:♥♡❤…︙".indexOf(point)>=0;}
    private static int repeatedSuffix(int[] points){
        // Use an exact repeated ending, not an earlier loosely similar utterance in normal type.
        for(int unit=1;unit<=4&&unit*3<points.length;unit++){
            int start=points.length-unit,repeats=1;boolean letter=false;
            for(int i=start;i<points.length;i++)letter|=Character.isLetter(points[i]);
            while(start>=unit){boolean same=true;for(int i=0;i<unit;i++)same&=points[start-unit+i]==points[points.length-unit+i];if(!same)break;start-=unit;repeats++;}
            if(letter&&repeats>=3&&start>0&&phraseEnd(points[start-1]))return start;
        }
        return -1;
    }
    private static Plan repeatedPairs(int w,int h,int[] box,int[] points,int sourceFont,float scale){
        if(points.length<6||points.length%2!=0||!Character.isLetter(points[0])||!phraseEnd(points[1])||Character.isLetter(points[1]))return null;
        for(int i=2;i<points.length;i++)if(points[i]!=points[i%2])return null;
        Plan p=new Plan();p.box=box.clone();p.points=points;p.columns=1;p.sourceGrouped=true;
        float padding=Math.min(5f,Math.min(box[2]-box[0],box[3]-box[1])*.05f),width=box[2]-box[0]-padding*2,height=box[3]-box[1]-padding*2;
        float body=Math.min(sourceFont*1.3f*scale,Math.min(width,height/(points.length/2*1.35f))),mark=body*.35f;
        if(mark/1.3f<8)return null;p.font=body/1.3f;p.step=body;p.placedCells=new float[points.length][2];p.placedSteps=new float[points.length];p.placedFonts=new float[points.length];
        float y=box[1]+padding+(height-points.length/2*(body+mark))/2;
        for(int i=0;i<points.length;i++){float step=i%2==0?body:mark;p.placedCells[i][0]=box[0]+padding+(width-step)/2;p.placedCells[i][1]=y;p.placedSteps[i]=step;p.placedFonts[i]=step/1.3f;y+=step;}
        return p;
    }
    private static final class SourceGroup {
        int[] box;final List<int[]> lines=new ArrayList<>();
        SourceGroup(int[] line){box=line.clone();lines.add(line);}
        int size(){return BubbleLayout.sourceFont(lines.toArray(new int[0][]),box);}
        float weight(){float sum=0;for(int[] r:lines)sum+=(r[3]-r[1])/(float)Math.max(1,r[2]-r[0]);return sum;}
    }
    /** Only split when source geometry and a repeated suffix both establish mixed display/body type. */
    private static Plan sourceGrouped(int w,int h,Plan uniform,int[][] sourceLines,String text,float scale){
        // ponytail: ordinary and horizontal paragraphs keep the common grid; extend mixed styles
        // only with source anchors and text boundaries that can be checked without inventing content.
        if(!uniform.vertical||sourceLines==null||sourceLines.length<3||sourceLines.length>12)return null;
        List<int[]> lines=new ArrayList<>();
        for(int[] r:sourceLines){int[] b={Math.max(uniform.box[0],r[0]),Math.max(uniform.box[1],r[1]),Math.min(uniform.box[2],r[2]),Math.min(uniform.box[3],r[3])};if(b[0]<b[2]&&b[1]<b[3])lines.add(b);}
        if(lines.size()<3)return null;lines.sort((a,b)->Integer.compare(b[0],a[0]));
        int[] emphasis=lines.get(lines.size()-1);int normal=BubbleLayout.sourceFont(lines.subList(0,lines.size()-1).toArray(new int[0][]),uniform.box);
        if(emphasis[2]-emphasis[0]<normal*2||emphasis[3]-emphasis[1]<(emphasis[2]-emphasis[0])*1.5)return null;
        int suffix=repeatedSuffix(uniform.points);if(suffix<0)return null;
        HashSet<Integer> explicitBreaks=new HashSet<>();int wordEnd=0;for(String word:text.trim().split("\\s+")){wordEnd+=word.codePointCount(0,word.length());explicitBreaks.add(wordEnd);}
        List<SourceGroup> groups=new ArrayList<>();
        for(int i=0;i<lines.size()-1;i++)groups.add(new SourceGroup(lines.get(i)));
        if(suffix<groups.size())return null;groups.add(new SourceGroup(emphasis));
        float remainingWeight=0;for(int i=0;i<groups.size()-1;i++)remainingWeight+=groups.get(i).weight();
        Plan result=new Plan();result.box=uniform.box.clone();result.points=uniform.points;result.vertical=true;result.sourceGrouped=true;result.columns=groups.size();
        result.placedCells=new float[result.points.length][2];result.placedSteps=new float[result.points.length];result.placedFonts=new float[result.points.length];
        List<int[]> occupied=new ArrayList<>();int cursor=0;
        for(int index=0;index<groups.size();index++){
            if(Thread.currentThread().isInterrupted())throw new CancellationException();
            SourceGroup group=groups.get(index);int take;
            if(index==groups.size()-1)take=result.points.length-suffix;
            else if(index==groups.size()-2)take=suffix-cursor;
            else{float ideal=(suffix-cursor)*group.weight()/remainingWeight,bestCost=Float.MAX_VALUE;take=1;
                for(int n=1;n<=suffix-cursor-(groups.size()-2-index);n++){float cost=Math.abs(n-ideal)+(phraseEnd(result.points[cursor+n-1])?0:3)-(explicitBreaks.contains(cursor+n)?.75f:0);if(cost<bestCost){bestCost=cost;take=n;}}}
            if(take<=0)return null;String part=new String(result.points,cursor,take);
            List<int[]> candidates=new ArrayList<>();candidates.add(group.box);
            for(int[] prior:occupied){List<int[]> next=new ArrayList<>();for(int[] b:candidates){if(overlap(b,prior)==0){next.add(b);continue;}
                int[][] slices={{b[0],b[1],Math.min(b[2],prior[0]-2),b[3]},{Math.max(b[0],prior[2]+2),b[1],b[2],b[3]},{b[0],b[1],b[2],Math.min(b[3],prior[1]-2)},{b[0],Math.max(b[1],prior[3]+2),b[2],b[3]}};
                for(int[] s:slices)if(s[0]<s[2]&&s[1]<s[3])next.add(s);}candidates=next;}
            Plan chosen=null;int[][] ownLines=group.lines.toArray(new int[0][]);
            for(int[] b:candidates){Plan p=index==groups.size()-1?repeatedPairs(w,h,b,part.codePoints().toArray(),group.size(),scale):null;if(p==null)p=plan(w,h,b,Collections.emptyList(),Collections.emptyList(),part,null,true,ownLines,scale);if(chosen==null||p.font>chosen.font)chosen=p;}
            if(chosen==null||chosen.font<8)return null;
            int l=w,t=h,r=0,b=0;
            for(int i=0;i<take;i++){float x=chosen.cellLeft(i),y=chosen.cellTop(i),step=chosen.cellStep(i);int n=cursor+i;
                result.placedCells[n][0]=x;result.placedCells[n][1]=y;result.placedSteps[n]=step;result.placedFonts[n]=chosen.cellFont(i);
                result.font=Math.max(result.font,chosen.cellFont(i));result.step=Math.max(result.step,step);l=Math.min(l,(int)Math.floor(x));t=Math.min(t,(int)Math.floor(y));r=Math.max(r,(int)Math.ceil(x+step));b=Math.max(b,(int)Math.ceil(y+step));}
            occupied.add(new int[]{l,t,r,b});cursor+=take;remainingWeight-=group.weight();
        }
        return cursor==uniform.points.length?result:null;
    }
}
