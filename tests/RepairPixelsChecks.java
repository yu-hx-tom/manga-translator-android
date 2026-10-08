package cn.local.manga;

import java.util.Arrays;

/** Real production CPU mask/composite checks; no Android, model, or network calls. */
public final class RepairPixelsChecks {
    static int checks;
    static void ok(boolean value,String label){checks++;if(!value)throw new AssertionError(label);}
    interface Attempt {void run()throws Exception;}
    static void rejects(Attempt work,String label)throws Exception{boolean rejected=false;try{work.run();}catch(Exception expected){rejected=true;}ok(rejected,label);}
    static int[] fill(int size,int color){int[] pixels=new int[size];Arrays.fill(pixels,color);return pixels;}
    public static void main(String[] args)throws Exception{
        int w=30,h=24;int[][] target={{6,5,22,19}},protectedBoxes={{14,8,19,12}};
        boolean[] mask=RepairPixels.editable(w,h,target,protectedBoxes);int enabled=0,protectedCount=0;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){
            boolean inside=x>=6&&x<22&&y>=5&&y<19,protectedArea=x>=12&&x<21&&y>=6&&y<14;
            ok(!mask[y*w+x]||inside,"editable pixels never extend beyond target rectangle");
            if(protectedArea){ok(!mask[y*w+x],"other paragraph and two-pixel safety edge remain protected");protectedCount++;}
            if(mask[y*w+x])enabled++;
        }
        ok(enabled>8&&protectedCount>0,"fixture contains meaningful editable and protected pixels");
        int[] original=fill(w*h,0xff646464),edited=fill(w*h,0xff707070),before=original.clone();
        for(int i=0;i<mask.length;i++)if(mask[i])edited[i]=0xff303030;
        int[] output=RepairPixels.composite(original,edited,w,h,mask);
        for(int i=0;i<mask.length;i++)ok(mask[i]?((output[i]&255)>=(edited[i]&255)&&(output[i]&255)<=(original[i]&255)):output[i]==original[i],"composite keeps exterior bit-exact and bounded inner colors");
        ok(Arrays.equals(original,before),"successful composite leaves original input untouched");
        ok(output!=original&&output!=edited,"commit produces an independent pixel buffer");
        int[] transparent=edited.clone();for(int i=0;i<mask.length;i++)if(mask[i]){transparent[i]=0x40303030;break;}
        rejects(()->RepairPixels.composite(original,transparent,w,h,mask),"transparent model pixel inside target rejected");
        ok(Arrays.equals(original,before),"rejected transparency cannot partly modify source");
        rejects(()->RepairPixels.composite(original,original.clone(),w,h,mask),"unchanged image refused as ineffective cleanup");
        int[] nearlyUnchanged=fill(w*h,0xff666666);
        rejects(()->RepairPixels.composite(original,nearlyUnchanged,w,h,mask),"minor compression drift alone does not count as removing text");
        int[] changedExterior=original.clone();for(int i=0;i<mask.length;i++)if(!mask[i])changedExterior[i]=0xff686868;
        rejects(()->RepairPixels.composite(original,changedExterior,w,h,mask),"changes only outside target are not mistaken for cleanup");
        int[] drift=fill(w*h,0xffffffff);for(int i=0;i<mask.length;i++)if(mask[i])drift[i]=0xff303030;
        rejects(()->RepairPixels.composite(original,drift,w,h,mask),"large surrounding background drift rejected before commit");
        ok(Arrays.equals(original,before),"background drift failure preserves original input");
        boolean[] covered=RepairPixels.editable(w,h,target,new int[][]{{0,0,w,h}});
        rejects(()->RepairPixels.composite(original,edited,w,h,covered),"fully protected target cannot overwrite another paragraph");
        rejects(()->RepairPixels.composite(original,new int[2],w,h,mask),"decoded pixel length mismatch rejected");
        rejects(()->RepairPixels.composite(original,edited,w,h,new boolean[2]),"mask length mismatch rejected");
        boolean[] edges=RepairPixels.editable(8,8,new int[][]{{0,0,2,3},{6,5,8,8}},new int[0][]);
        for(int y=0;y<8;y++)for(int x=0;x<8;x++)ok(edges[y*8+x]==((x<2&&y<3)||(x>=6&&y>=5)),"disjoint targets preserve edge coordinates without padding");
        boolean[] overlap=RepairPixels.editable(8,8,new int[][]{{1,1,6,6},{3,3,8,8}},new int[][]{{4,4,5,5}});
        ok(!overlap[4*8+4]&&!overlap[2*8+2]&&overlap[1*8+1],"protection wins over union of overlapping targets");
        rejects(()->RepairPixels.editable(4001,1000,target,new int[0][]),"repair area allocation obeys four-million-pixel bound");
        ImageCleanup.validateReturnedSize(1024,1024,w,h);ok(true,"production image bounds permit fixed model canvas for later ROI resize");
        ImageCleanup.validateReturnedSize(4000,4000,w,h);ok(true,"production output pixel budget inclusive boundary");
        rejects(()->ImageCleanup.validateReturnedSize(4001,4000,w,h),"oversized returned image rejected before pixel allocation");
        rejects(()->ImageCleanup.validateReturnedSize(6001,1,w,h),"returned long-side bound enforced");
        rejects(()->ImageCleanup.validateReturnedSize(0,10,w,h),"empty returned image bounds rejected");
        int fw=14,fh=14;boolean[] feather=RepairPixels.editable(fw,fh,new int[][]{{2,2,12,12}},new int[0][]);int[] fOriginal=fill(fw*fh,0xff204060),fEdited=fOriginal.clone();for(int i=0;i<feather.length;i++)if(feather[i])fEdited[i]=0xffe0a080;
        int[] fOutput=RepairPixels.composite(fOriginal,fEdited,fw,fh,feather);
        ok(fOutput[6*fw+2]==0xff204060&&fOutput[6*fw+11]==0xff204060,"left and right inner boundary rings retain original pixels");
        ok(fOutput[2*fw+6]==0xff204060&&fOutput[11*fw+6]==0xff204060,"top and bottom inner boundary rings retain original pixels");
        ok(fOutput[6*fw+3]==0xff807070&&fOutput[6*fw+10]==0xff807070,"second ring blends each RGB channel halfway");
        ok(fOutput[6*fw+4]==0xffe0a080&&fOutput[6*fw+9]==0xffe0a080,"third ring and deeper retain the complete model repair");
        ok(fOutput[2*fw+2]==0xff204060&&fOutput[3*fw+3]==0xff807070&&fOutput[4*fw+4]==0xffe0a080,"corners follow the same interior-distance rule");
        boolean[] split=RepairPixels.editable(28,24,new int[][]{{2,2,26,22}},new int[][]{{13,10,14,11}});int[] sOriginal=fill(28*24,0xff204060),sEdited=fill(28*24,0xffe0a080);for(int i=0;i<split.length;i++)if(!split[i])sEdited[i]=sOriginal[i];int[] sOutput=RepairPixels.composite(sOriginal,sEdited,28,24,split);
        ok(sOutput[10*28+10]==sOriginal[10*28+10],"inner ring around a protected paragraph hole stays original");
        ok(sOutput[10*28+9]==0xff807070&&sOutput[10*28+8]==0xffe0a080,"second and third rings around protected holes are blended and fully repaired respectively");
        boolean[] thin=RepairPixels.editable(14,14,new int[][]{{3,2,5,12}},new int[0][]);int[] thinEdit=fOriginal.clone();for(int i=0;i<thin.length;i++)if(thin[i])thinEdit[i]=0xffe0a080;
        rejects(()->RepairPixels.composite(fOriginal,thinEdit,14,14,thin),"two-pixel-wide editable mask becomes unchanged after blending and must fail");
        boolean[] small=RepairPixels.editable(14,14,new int[][]{{4,4,7,7}},new int[0][]);int[] smallEdit=fOriginal.clone();for(int i=0;i<small.length;i++)if(small[i])smallEdit[i]=0xffe0a080;
        rejects(()->RepairPixels.composite(fOriginal,smallEdit,14,14,small),"only one effective center pixel does not count as meaningful repair");
        int[] boundaryTransparent=fEdited.clone();boundaryTransparent[6*fw+2]=0x10203040;
        rejects(()->RepairPixels.composite(fOriginal,boundaryTransparent,fw,fh,feather),"transparency validation still checks a boundary pixel whose model alpha would be zero");
        rejects(()->RepairPixels.composite(new int[0],new int[0],4001,1000,new boolean[0]),"composite rejects over-four-million geometry before scratch allocation");
        rejects(()->RepairPixels.composite(new int[0],new int[0],0,10,new boolean[0]),"composite rejects empty geometry");
        int size=2000*2000;int[] largeOriginal=fill(size,0xff202020),largeEdited=fill(size,0xffa0a0a0);boolean[] largeMask=new boolean[size];Arrays.fill(largeMask,true);int[] large=RepairPixels.composite(largeOriginal,largeEdited,2000,2000,largeMask);
        ok(large[0]==0xff202020&&large[2001]==0xff606060&&large[4002]==0xffa0a0a0,"inclusive four-million-pixel limit supports bounded distance scratch and exact ring weights");
        System.out.println("RepairPixelsChecks: "+checks+" checks passed (production CPU mask/composite and bounds helper; no Android resize or AI quality claim)");
    }
}
