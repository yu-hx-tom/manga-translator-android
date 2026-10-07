package cn.local.manga;

import android.graphics.*;
import android.text.*;

/** Text-only drawing; never calls detection, translation or cleanup. */
final class TextRenderer {
    static void draw(Canvas canvas,PageDraft.Item item,PageComposer.Edit edit){
        if(edit.hidden||edit.format.deleted)return;
        String text=edit.text==null?item.machine:edit.text;if(text==null||text.isEmpty())return;
        TextStyle s=edit.format;Rect r=s.box==null?item.region.box:s.box;int width=Math.max(1,r.width()),height=Math.max(1,r.height());
        TextPaint p=new TextPaint(Paint.ANTI_ALIAS_FLAG);p.setTypeface(Typeface.create(s.fontFamily,Typeface.NORMAL));
        boolean vertical=edit.vertical==null?item.region.vertical:edit.vertical;
        float font=Math.max(8,Math.min(96,s.fontSize*edit.scale));p.setTextSize(font);p.setLetterSpacing(s.letterSpacing/font);
        StaticLayout layout=null;
        for(;;){
            p.setTextSize(font);p.setLetterSpacing(s.letterSpacing/font);
            if(vertical){int rows=Math.max(1,(int)(height/(font*s.lineSpacing)));int columns=(text.codePointCount(0,text.length())+rows-1)/rows;if(!s.autoFit||font<=8||columns*(font+s.letterSpacing)<=width)break;}
            else {layout=layout(text,p,width,s);if(!s.autoFit||font<=8||layout.getHeight()<=height)break;}
            font-=1;
        }
        canvas.save();canvas.rotate(s.rotation,r.exactCenterX(),r.exactCenterY());canvas.clipRect(r);
        if(vertical){
            int rows=Math.max(1,(int)(height/(font*s.lineSpacing))),count=text.codePointCount(0,text.length()),cols=(count+rows-1)/rows;
            float span=cols*(font+s.letterSpacing),start=s.align==0?r.left:s.align==2?r.right-span:r.left+(width-span)/2f;
            int offset=0,index=0;while(offset<text.length()){int cp=text.codePointAt(offset);String letter=new String(Character.toChars(cp));offset+=Character.charCount(cp);int col=index/rows,row=index%rows;float x=start+(cols-1-col)*(font+s.letterSpacing),y=r.top+row*font*s.lineSpacing-p.ascent();
                if(s.strokeWidth>0){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(s.strokeWidth);p.setColor(s.strokeColor);canvas.drawText(letter,x,y,p);}p.setStyle(Paint.Style.FILL);p.setColor(edit.color==0?Color.BLACK:edit.color);canvas.drawText(letter,x,y,p);index++;
            }
        }else if(layout!=null){
            canvas.translate(r.left,r.top+Math.max(0,(height-layout.getHeight())/2f));
            if(s.strokeWidth>0){p.setStyle(Paint.Style.STROKE);p.setStrokeWidth(s.strokeWidth);p.setColor(s.strokeColor);layout.draw(canvas);}p.setStyle(Paint.Style.FILL);p.setColor(edit.color==0?Color.BLACK:edit.color);layout.draw(canvas);
        }
        canvas.restore();
    }
    private static StaticLayout layout(String text,TextPaint p,int width,TextStyle s){return StaticLayout.Builder.obtain(text,0,text.length(),p,width).setAlignment(s.align==0?Layout.Alignment.ALIGN_NORMAL:s.align==2?Layout.Alignment.ALIGN_OPPOSITE:Layout.Alignment.ALIGN_CENTER).setIncludePad(false).setLineSpacing(0,s.lineSpacing).setHyphenationFrequency(0).build();}
}
