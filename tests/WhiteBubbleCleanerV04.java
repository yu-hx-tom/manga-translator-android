package cn.local.manga;

/** Pure pixel cleanup shared by Android rendering and the offline visual regression checks. */
final class WhiteBubbleCleanerV04 {
    static final class Mask { final boolean[] erase; final boolean whiteBackground; final int pixels;
        Mask(boolean[] erase,boolean whiteBackground,int pixels){this.erase=erase;this.whiteBackground=whiteBackground;this.pixels=pixels;}
    }
    static Mask find(int[] pixels,int width,int height,int[][] lines,int characterSize){
        int count=Math.multiplyExact(width,height);if(pixels.length!=count||count>4_000_000)throw new IllegalArgumentException("Invalid cleanup crop");
        int margin=Math.max(3,(int)Math.ceil(characterSize*.65));
        boolean[] scope=new boolean[count],core=new boolean[count],seen=new boolean[count],erase=new boolean[count],protectedInk=new boolean[count];
        for(int[] line:lines){int left=Math.max(1,line[0]-margin),top=Math.max(1,line[1]-margin),right=Math.min(width-1,line[2]+margin),bottom=Math.min(height-1,line[3]+margin);
            for(int y=top;y<bottom;y++)java.util.Arrays.fill(scope,y*width+left,y*width+right,true);
            for(int y=Math.max(0,line[1]);y<Math.min(height,line[3]);y++)java.util.Arrays.fill(core,y*width+Math.max(0,line[0]),y*width+Math.min(width,line[2]),true);
        }
        int[] queue=new int[count];
        // Identify the white background connected to the detected text. Do not erase a nearby illustration across a bubble outline.
        int[] whiteLabels=new int[count];int label=0,bestLabel=0,bestCore=0;
        for(int seed=0;seed<count;seed++)if(whiteLabels[seed]==0&&brightness(pixels[seed])>=235){
            int read=0,end=1,hits=0;queue[0]=seed;whiteLabels[seed]=++label;
            while(read<end){int point=queue[read++],x=point%width,y=point/width;if(core[point])hits++;
                for(int direction=0;direction<4;direction++){int next=direction==0?(x>0?point-1:-1):direction==1?(x+1<width?point+1:-1):direction==2?(y>0?point-width:-1):(y+1<height?point+width:-1);
                    if(next>=0&&whiteLabels[next]==0&&brightness(pixels[next])>=235){whiteLabels[next]=label;queue[end++]=next;}
                }
            }
            if(hits>bestCore){bestCore=hits;bestLabel=label;}
        }
        for(int seed=0;seed<count;seed++){
            if(seen[seed]||brightness(pixels[seed])>=235)continue;
            int read=0,end=1,inside=0,whiteContact=0,minX=width,minY=height,maxX=0,maxY=0;queue[0]=seed;seen[seed]=true;
            while(read<end){int point=queue[read++],x=point%width,y=point/width;if(scope[point])inside++;
                minX=Math.min(minX,x);maxX=Math.max(maxX,x);minY=Math.min(minY,y);maxY=Math.max(maxY,y);
                for(int yy=Math.max(0,y-1);yy<=Math.min(height-1,y+1);yy++)for(int xx=Math.max(0,x-1);xx<=Math.min(width-1,x+1);xx++){
                    int next=yy*width+xx;if(bestLabel!=0&&whiteLabels[next]==bestLabel)whiteContact++;if(!seen[next]&&brightness(pixels[next])<235){seen[next]=true;queue[end++]=next;}
                }
            }
            int w=maxX-minX+1,h=maxY-minY+1;
            boolean touches=minX==0||minY==0||maxX==width-1||maxY==height-1;
            boolean glyph=!touches&&whiteContact>0&&inside>=end*.98&&Math.max(w,h)<=characterSize*5&&Math.min(w,h)<=characterSize*2;
            for(int i=0;i<end;i++){if(glyph)erase[queue[i]]=true;else protectedInk[queue[i]]=true;}
        }
        boolean[] expanded=erase.clone();
        for(int i=0;i<count;i++)if(erase[i]){int x=i%width,y=i/width;
            for(int yy=Math.max(0,y-1);yy<=Math.min(height-1,y+1);yy++)for(int xx=Math.max(0,x-1);xx<=Math.min(width-1,x+1);xx++){
                int next=yy*width+xx;if(scope[next]&&!protectedInk[next]&&brightness(pixels[next])<250)expanded[next]=true;
            }
        }
        int total=0,white=0,clean=0,colored=0,changed=0;
        for(int i=0;i<count;i++)if(core[i]){total++;int p=pixels[i],r=(p>>>16)&255,g=(p>>>8)&255,b=p&255;
            if(brightness(p)>=242)white++;if(brightness(p)>=242||expanded[i])clean++;
            if(Math.max(r,Math.max(g,b))-Math.min(r,Math.min(g,b))>20)colored++;
        }
        for(boolean value:expanded)if(value)changed++;
        boolean solid=total>0&&white>=total*.35&&bestCore>=total*.25&&clean>=total*.90&&colored<=total*.01;
        if(!solid){java.util.Arrays.fill(expanded,false);changed=0;}
        return new Mask(expanded,solid,changed);
    }
    static void apply(int[] pixels,Mask mask){for(int i=0;i<pixels.length;i++)if(mask.erase[i])pixels[i]=0xffffffff;}
    private static int brightness(int color){return (((color>>>16)&255)*299+((color>>>8)&255)*587+(color&255)*114)/1000;}
}

