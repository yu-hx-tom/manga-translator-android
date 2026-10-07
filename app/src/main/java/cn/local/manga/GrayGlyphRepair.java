package cn.local.manga;

/** Restore only verified outlined glyph pixels from nearby original neutral paper/texture samples. */
final class GrayGlyphRepair {
    private GrayGlyphRepair(){}
    static WhiteBubbleCleaner.Mask repair(int[] source,int w,int h,WhiteBubbleCleaner.Mask mask){
        boolean[] erase=mask.glyphCandidate;
        if(!mask.texturedBackground||erase==null||source.length>1_000_000)return null;
        int targets=0,paper=0;for(int i=0;i<source.length;i++){if(erase[i])targets++;if(mask.interior[i])paper++;}
        if(targets<4||targets>30_000||targets>paper*.50)return null;
        boolean[] known=new boolean[source.length],donor=new boolean[source.length];int[] repaired=source.clone(),queue=new int[source.length];
        for(int i=0;i<source.length;i++)known[i]=mask.interior[i]&&!erase[i];
        // A donor patch must be entirely original paper. Repaired pixels never become donor samples.
        for(int y=1;y<h-1;y++)for(int x=1;x<w-1;x++){
            boolean good=true;for(int yy=y-1;yy<=y+1&&good;yy++)for(int xx=x-1;xx<=x+1;xx++)if(!known[yy*w+xx]){good=false;break;}
            donor[y*w+x]=good;
        }
        boolean[] queued=new boolean[source.length];int head=0,end=0;
        for(int y=0;y<h;y++)for(int x=0;x<w;x++){int p=y*w+x;if(erase[p]&&neighborKnown(known,p,x,y,w,h)){queue[end++]=p;queued[p]=true;}}
        int filled=0;
        while(head<end){
            if((head&255)==0&&Thread.currentThread().isInterrupted())throw new java.util.concurrent.CancellationException("已取消");
            int p=queue[head++],x=p%w,y=p/w;double best=Double.POSITIVE_INFINITY;int chosen=-1;
            // Small neighboring exemplars preserve paper grain instead of painting a rectangular plate.
            for(int radius=2;radius<=24;radius+=2)for(int direction=0;direction<8;direction++){
                int dx=direction==0||direction==4||direction==7?-radius:direction==1||direction==5||direction==6?radius:0;
                int dy=direction==2||direction==4||direction==5?-radius:direction==3||direction==6||direction==7?radius:0;
                int xx=x+dx,yy=y+dy;if(xx<1||yy<1||xx>=w-1||yy>=h-1||!donor[yy*w+xx])continue;
                double error=0;int samples=0;
                for(int py=-1;py<=1;py++)for(int px=-1;px<=1;px++){
                    int tx=x+px,ty=y+py;if(tx<0||ty<0||tx>=w||ty>=h)continue;int q=ty*w+tx;if(!known[q])continue;
                    int a=gray(repaired[q]),b=gray(source[(yy+py)*w+xx+px]);error+=(a-b)*(a-b);samples++;
                }
                if(samples<2)continue;double score=error/samples+.12*(dx*dx+dy*dy);
                if(score<best){best=score;chosen=yy*w+xx;}
            }
            if(chosen<0){
                // Narrow gaps may have no complete exemplar. Interpolate only from already known paper.
                int total=0,count=0;for(int yy=Math.max(0,y-1);yy<=Math.min(h-1,y+1);yy++)for(int xx=Math.max(0,x-1);xx<=Math.min(w-1,x+1);xx++)if(known[yy*w+xx]){total+=gray(repaired[yy*w+xx]);count++;}
                if(count==0)return null;int shade=(total+count/2)/count;repaired[p]=0xff000000|(shade<<16)|(shade<<8)|shade;
            }else repaired[p]=source[chosen];
            known[p]=true;filled++;
            for(int yy=Math.max(0,y-1);yy<=Math.min(h-1,y+1);yy++)for(int xx=Math.max(0,x-1);xx<=Math.min(w-1,x+1);xx++){int q=yy*w+xx;if(erase[q]&&!queued[q]){queued[q]=true;queue[end++]=q;}}
        }
        if(filled!=targets)return null;
        return new WhiteBubbleCleaner.Mask(erase.clone(),mask.interior,true,targets,repaired,true);
    }
    private static boolean neighborKnown(boolean[] known,int p,int x,int y,int w,int h){return x>0&&known[p-1]||x+1<w&&known[p+1]||y>0&&known[p-w]||y+1<h&&known[p+w];}
    private static int gray(int c){return (((c>>>16)&255)*299+((c>>>8)&255)*587+(c&255)*114)/1000;}
}
