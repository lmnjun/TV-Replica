package com.gathin.tvhome;

/** A fixed wallpaper pixel budget; foreground UI keeps the display's original resolution. */
final class SaturnSurfaceSize {
    static final int MAX_WIDTH=1280,MAX_HEIGHT=720;
    static final class Size {
        final int width,height;
        Size(int width,int height){this.width=width;this.height=height;}
        @Override public boolean equals(Object other){
            return other instanceof Size&&width==((Size)other).width&&height==((Size)other).height;
        }
        @Override public int hashCode(){return 31*width+height;}
    }
    private SaturnSurfaceSize() { }
    static Size fit(int viewWidth,int viewHeight){
        if(viewWidth<=0||viewHeight<=0)return null;
        double scale=Math.min(1,Math.min(MAX_WIDTH/(double)viewWidth,MAX_HEIGHT/(double)viewHeight));
        return new Size(Math.max(1,(int)Math.round(viewWidth*scale)),Math.max(1,(int)Math.round(viewHeight*scale)));
    }
}
