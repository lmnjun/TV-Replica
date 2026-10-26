package com.gathin.tvhome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.opengl.GLES20;
import android.opengl.GLSurfaceView;
import android.opengl.Matrix;
import android.os.Handler;
import android.os.Looper;
import android.os.SystemClock;
import android.util.Log;
import android.view.Choreographer;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.View;
import android.view.ViewGroup;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.nio.*;
import java.text.SimpleDateFormat;
import java.util.*;
import javax.microedition.khronos.egl.EGL10;
import javax.microedition.khronos.egl.EGLConfig;
import javax.microedition.khronos.egl.EGLDisplay;
import javax.microedition.khronos.opengles.GL10;
import org.json.JSONObject;

/** Standalone native 3D scene. No network, microphone, overlay permission or background service. */
final class SaturnScene {
    private final Activity activity;
    private final GLSurfaceView surface;
    private SaturnSurfaceSize.Size bufferSize;
    private volatile int surfaceViewWidth,surfaceViewHeight;
    private final View.OnLayoutChangeListener surfaceLayout=(v,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom)->
            updateSurfaceSize(right-left,bottom-top);
    private final Renderer renderer = new Renderer();
    private final Handler handler = new Handler(Looper.getMainLooper());
    private final Choreographer choreographer = Choreographer.getInstance();
    private final SaturnFramePacer framePacer = new SaturnFramePacer();
    private Choreographer.FrameCallback frameCallback;
    private final SharedPreferences prefs;
    private final MoonNames moonNames;
    private final TextView sceneTime,sceneDate,help;
    private final View realTime,realDate;
    private volatile boolean active;
    /** Diagnostic only: foreground transitions never freeze or lower the scene's quality. */
    private volatile boolean uiMotionActive;
    /** Diagnostic navigation state only; focus changes never change scene detail or render cadence. */
    private volatile boolean navigationMotionActive;
    private boolean exploring;
    volatile boolean paused,orbits,labels,simulationClock;
    volatile float yaw=28,pitch=24,distance=6.2f;
    volatile int speed=1;
    private long baseMillis=System.currentTimeMillis(),anchor=SystemClock.elapsedRealtime();
    private static final int[] SPEEDS={1,60,600,3600,86400};
    private final Runnable uiTick=new Runnable(){@Override public void run(){
        if(!active)return;
        if(labels)moonNames.invalidate();
        updateClock();
        handler.postDelayed(this,200);
    }};
    SaturnScene(Activity activity,FrameLayout root) {
        this.activity=activity;prefs=activity.getSharedPreferences("saturn_scene",0);
        yaw=prefs.getFloat("yaw",28);pitch=prefs.getFloat("pitch",24);distance=prefs.getFloat("distance",6.2f);
        orbits=prefs.getBoolean("orbits",false);labels=prefs.getBoolean("labels",false);
        simulationClock=prefs.getBoolean("simulation_clock",false);
        speed=prefs.getInt("speed",1);boolean validSpeed=false;for(int n:SPEEDS)if(n==speed)validSpeed=true;if(!validSpeed)speed=1;
        paused=prefs.getBoolean("paused",false);baseMillis=prefs.getLong("simulation_time",System.currentTimeMillis());
        surface=new GLSurfaceView(activity);surface.setEGLContextClientVersion(2);
        surface.setEGLConfigChooser(new SceneConfigChooser());surface.setPreserveEGLContextOnPause(true);
        surface.setRenderer(renderer);surface.setRenderMode(GLSurfaceView.RENDERMODE_WHEN_DIRTY);
        surface.setImportantForAccessibility(View.IMPORTANT_FOR_ACCESSIBILITY_NO);
        surface.addOnLayoutChangeListener(surfaceLayout);
        root.addView(surface,0,new FrameLayout.LayoutParams(-1,-1));
        moonNames=new MoonNames();root.addView(moonNames,1,new FrameLayout.LayoutParams(-1,-1));
        realTime=root.findViewById(R.id.clock);realDate=root.findViewById(R.id.date);
        LinearLayout block=root.findViewById(R.id.leftBlock);
        block.setOnClickListener(v -> {if(surface.getVisibility()==View.VISIBLE)toggleClock();});block.setFocusable(false);
        sceneTime=copyText((TextView)realTime);sceneDate=copyText((TextView)realDate);
        block.addView(sceneTime,block.indexOfChild(realTime)+1);block.addView(sceneDate,block.indexOfChild(realDate)+1);
        help=new TextView(activity);help.setTextColor(0xE6FFFFFF);help.setTextSize(12);
        help.setTypeface(activity.getResources().getFont(R.font.inter_regular));
        help.setGravity(Gravity.CENTER);help.setPadding(dp(18),dp(12),dp(18),dp(12));
        help.setBackgroundColor(0xB80A101A);help.setVisibility(View.GONE);
        FrameLayout.LayoutParams hp=new FrameLayout.LayoutParams(-1,-2,Gravity.BOTTOM);hp.setMargins(dp(40),0,dp(40),dp(24));root.addView(help,hp);
        updateClock();log("created");
    }
    private int dp(float n){return Math.round(n*activity.getResources().getDisplayMetrics().density);}
    private void updateSurfaceSize(int viewWidth,int viewHeight){
        SaturnSurfaceSize.Size next=SaturnSurfaceSize.fit(viewWidth,viewHeight);
        if(next==null||viewWidth==surfaceViewWidth&&viewHeight==surfaceViewHeight)return;
        surfaceViewWidth=viewWidth;surfaceViewHeight=viewHeight;
        if(!next.equals(bufferSize)){bufferSize=next;surface.getHolder().setFixedSize(next.width,next.height);}
        Log.i("Saturn.GL","Wallpaper buffer requested="+next.width+"x"+next.height+" view="+viewWidth+"x"+viewHeight+" sizing=fixed-budget");
    }
    private TextView copyText(TextView from){
        TextView to=new TextView(activity);to.setTextColor(from.getCurrentTextColor());to.setTextSize(android.util.TypedValue.COMPLEX_UNIT_PX,from.getTextSize());
        to.setTypeface(from.getTypeface());to.setGravity(from.getGravity());to.setTransformationMethod(from.getTransformationMethod());to.setLetterSpacing(from.getLetterSpacing());to.setIncludeFontPadding(false);
        to.setFontVariationSettings(from.getFontVariationSettings());to.setLayoutParams(new LinearLayout.LayoutParams((ViewGroup.MarginLayoutParams)from.getLayoutParams()));
        to.setShadowLayer(dp(5),0,dp(1),0x70000000);return to;
    }
    synchronized long time(){return baseMillis+(!paused&&active?(long)((SystemClock.elapsedRealtime()-anchor)*(double)speed):0);}
    private synchronized void hold(){baseMillis=time();anchor=SystemClock.elapsedRealtime();}
    void start(){
        if(active)return;
        uiMotionActive=false;navigationMotionActive=false;anchor=SystemClock.elapsedRealtime();active=true;
        updateRenderMode();surface.onResume();renderer.dirty=true;renderer.lastDrawNanos=0;
        final long lifecycle=framePacer.start();
        frameCallback=new Choreographer.FrameCallback(){@Override public void doFrame(long frameTimeNanos){
            if(!active||frameCallback!=this)return;
            if(paused&&renderer.dirty){
                long ticket=framePacer.request(lifecycle);
                if(ticket!=0){renderer.dirty=false;surface.requestRender();}
            }
            if(active&&frameCallback==this)choreographer.postFrameCallback(this);
        }};
        choreographer.postFrameCallback(frameCallback);handler.post(uiTick);log("start");
    }
    void stop(){
        uiMotionActive=false;navigationMotionActive=false;
        if(!active)return;
        hold();active=false;
        Choreographer.FrameCallback callback=frameCallback;frameCallback=null;
        if(callback!=null)choreographer.removeFrameCallback(callback);
        framePacer.stop();handler.removeCallbacks(uiTick);updateRenderMode();surface.onPause();save();log("stop");
    }
    /** Continuous GLThread drawing isolates its swap/backpressure from Main's frame callbacks. */
    private void updateRenderMode(){
        surface.setRenderMode(active&&!paused?GLSurfaceView.RENDERMODE_CONTINUOUSLY:GLSurfaceView.RENDERMODE_WHEN_DIRTY);
    }
    /** Main-thread only. Keep the same vsync cadence and material throughout foreground motion. */
    void setUiMotionActive(boolean value){
        uiMotionActive=value;
    }
    /** Main-thread only. Navigation keeps the same mesh, material and vsync cadence. */
    void setNavigationMotionActive(boolean value){
        navigationMotionActive=value;
    }
    void close(){stop();surface.removeOnLayoutChangeListener(surfaceLayout);handler.removeCallbacksAndMessages(null);}
    void setVisible(boolean visible){
        if(!visible){stop();exploring=false;}
        surface.setVisibility(visible?View.VISIBLE:View.GONE);
        moonNames.setVisibility(visible?View.VISIBLE:View.GONE);
        if(visible)updateClock();
        else{
            help.setVisibility(View.GONE);sceneTime.setVisibility(View.GONE);sceneDate.setVisibility(View.GONE);
            realTime.setVisibility(View.VISIBLE);realDate.setVisibility(View.VISIBLE);
        }
    }
    void refreshClockFormat(){updateClock();}
    void togglePause(){hold();paused=!paused;renderer.dirty=true;updateRenderMode();save();log("pause");}
    void cycleSpeed(){hold();int i=0;while(i<SPEEDS.length&&SPEEDS[i]!=speed)i++;speed=SPEEDS[(i+1)%SPEEDS.length];renderer.dirty=true;save();log("speed");}
    void toggleClock(){simulationClock=!simulationClock;updateClock();save();log("clock");}
    void toggleOrbits(){orbits=!orbits;renderer.dirty=true;save();log("orbits");}
    void toggleLabels(){labels=!labels;moonNames.invalidate();save();log("labels");}
    void systemView(){distance=distance<15?27:6.2f;renderer.dirty=true;save();log("view");}
    synchronized void reset(){baseMillis=System.currentTimeMillis();anchor=SystemClock.elapsedRealtime();speed=1;paused=false;yaw=28;pitch=24;distance=6.2f;renderer.dirty=true;save();log("reset");}
    void explore(boolean value){exploring=value;help.setVisibility(value?View.VISIBLE:View.GONE);updateClock();log(value?"explore":"exit");}
    boolean exploring(){return exploring;}
    boolean handle(KeyEvent e){
        if(e.getKeyCode()==KeyEvent.KEYCODE_INFO && e.getAction()==KeyEvent.ACTION_DOWN){log("status");return true;}
        if(!exploring)return false;
        int k=e.getKeyCode();
        if(k==KeyEvent.KEYCODE_BACK||k==KeyEvent.KEYCODE_HOME)return false;
        boolean known=k==KeyEvent.KEYCODE_DPAD_LEFT||k==KeyEvent.KEYCODE_DPAD_RIGHT||k==KeyEvent.KEYCODE_DPAD_UP||k==KeyEvent.KEYCODE_DPAD_DOWN||k==KeyEvent.KEYCODE_DPAD_CENTER||k==KeyEvent.KEYCODE_ENTER||k==KeyEvent.KEYCODE_MENU||k==KeyEvent.KEYCODE_CHANNEL_UP||k==KeyEvent.KEYCODE_CHANNEL_DOWN||k==KeyEvent.KEYCODE_MEDIA_FAST_FORWARD||k==KeyEvent.KEYCODE_MEDIA_REWIND||k==KeyEvent.KEYCODE_MEDIA_PLAY_PAUSE;
        if(!known)return false;if(e.getAction()!=KeyEvent.ACTION_DOWN)return true;
        switch(k){
            case KeyEvent.KEYCODE_DPAD_LEFT:yaw-=e.getRepeatCount()>0?4:8;break;
            case KeyEvent.KEYCODE_DPAD_RIGHT:yaw+=e.getRepeatCount()>0?4:8;break;
            case KeyEvent.KEYCODE_DPAD_UP:pitch=Math.min(80,pitch+5);break;
            case KeyEvent.KEYCODE_DPAD_DOWN:pitch=Math.max(-80,pitch-5);break;
            case KeyEvent.KEYCODE_CHANNEL_UP:case KeyEvent.KEYCODE_MEDIA_FAST_FORWARD:distance=Math.max(3.1f,distance*.90f);break;
            case KeyEvent.KEYCODE_CHANNEL_DOWN:case KeyEvent.KEYCODE_MEDIA_REWIND:distance=Math.min(40,distance/ .90f);break;
            case KeyEvent.KEYCODE_MENU:cycleSpeed();break;
            default:if(e.getRepeatCount()==0)togglePause();break;
        }
        renderer.dirty=true;save();updateClock();log("control");return true;
    }
    private void updateClock(){
        sceneTime.setVisibility(simulationClock?View.VISIBLE:View.GONE);sceneDate.setVisibility(simulationClock?View.VISIBLE:View.GONE);
        realTime.setVisibility(simulationClock?View.GONE:View.VISIBLE);realDate.setVisibility(simulationClock?View.GONE:View.VISIBLE);
        if(simulationClock){Date date=new Date(time());sceneTime.setText(ClockFormat.format(activity,date.getTime()));sceneDate.setText(new SimpleDateFormat("EEE · dd MMM yyyy",Locale.getDefault()).format(date)+"  ·  SIM");}
        if(exploring)help.setText("← → Rotate   ↑ ↓ Tilt   CH ± Zoom   OK "+(paused?"Resume":"Pause")+"   Menu "+speed+"×   Back Exit\n17 moons · compressed distances / enlarged moons · "+new SimpleDateFormat("dd MMM yyyy HH:mm:ss",Locale.getDefault()).format(new Date(time())));
    }
    private void save(){prefs.edit().putInt("speed",speed).putBoolean("paused",paused).putLong("simulation_time",time()).putFloat("yaw",yaw).putFloat("pitch",pitch).putFloat("distance",distance).putBoolean("orbits",orbits).putBoolean("labels",labels).putBoolean("simulation_clock",simulationClock).apply();}
    private void log(String event){try{
        JSONObject o=new JSONObject();o.put("event",event);o.put("time",time());o.put("speed",speed);o.put("paused",paused);o.put("yaw",yaw);o.put("pitch",pitch);o.put("distance",distance);o.put("orbits",orbits);o.put("labels",labels);o.put("simulation_clock",simulationClock);o.put("active",active);o.put("frames",renderer.frames);o.put("navigation_motion",navigationMotionActive);o.put("ui_motion",uiMotionActive);
        double days=(time()-946728000000L)/86400000.0;double[] p=SaturnOrbits.position(0,days,true);o.put("mimas_x",p[0]);o.put("mimas_z",p[2]);Log.i("Saturn.Scene",o.toString());
    }catch(Exception ignored){}}
    private final class MoonNames extends View{
        final Paint paint=new Paint(Paint.ANTI_ALIAS_FLAG);
        MoonNames(){super(activity);paint.setTypeface(activity.getResources().getFont(R.font.inter_regular));paint.setTextSize(dp(10));paint.setColor(0xBFFFFFFF);paint.setShadowLayer(dp(3),0,1,0xFF000000);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_NO);}
        @Override protected void onDraw(Canvas c){
            if(!labels)return;
            float[] p=renderer.projected;
            java.util.List<android.graphics.RectF> used=new ArrayList<>();
            View clock=activity.findViewById(R.id.leftBlock);
            used.add(new android.graphics.RectF(clock.getLeft()-dp(8),clock.getTop()-dp(8),clock.getRight()+dp(8),clock.getBottom()+dp(8)));
            Integer[] order=new Integer[17];for(int i=0;i<17;i++)order[i]=i;
            Arrays.sort(order,(a,b)->Float.compare(p[a*3+1],p[b*3+1]));
            for(int i:order){int k=i*3;float px=p[k]*getWidth(),py=p[k+1]*getHeight();
                if(p[k+2]<=0||px<0||px>getWidth()||py<dp(55)||py>getHeight()-dp(70))continue;
                float tw=paint.measureText(SaturnOrbits.NAMES[i]),x=Math.max(dp(12),Math.min(getWidth()-tw-dp(12),px+dp(6)));
                for(int trial=0;trial<15;trial++){
                    int direction=trial%2==0?1:-1;float y=py-dp(5)+direction*((trial+1)/2)*dp(14);
                    if(y<dp(50)||y>getHeight()-dp(65))continue;
                    android.graphics.RectF rect=new android.graphics.RectF(x-dp(3),y-dp(11),x+tw+dp(3),y+dp(4));
                    boolean blocked=false;for(android.graphics.RectF prior:used)if(android.graphics.RectF.intersects(rect,prior)){blocked=true;break;}
                    if(blocked)continue;
                    if(trial>0){paint.setColor(0x55FFFFFF);paint.setStrokeWidth(dp(.5f));c.drawLine(px,py,x,y-dp(3),paint);}
                    paint.setColor(0xBFFFFFFF);c.drawText(SaturnOrbits.NAMES[i],x,y,paint);used.add(rect);break;
                }
            }
        }
    }
    private static final class Mesh {
        final FloatBuffer buffer;final int count;int vertexBuffer;
        Mesh(float[] data){buffer=ByteBuffer.allocateDirect(data.length*4).order(ByteOrder.nativeOrder()).asFloatBuffer();buffer.put(data).position(0);count=data.length/6;}
        void upload(){
            int[] ids=new int[1];GLES20.glGenBuffers(1,ids,0);vertexBuffer=ids[0];
            if(vertexBuffer==0)return;
            buffer.position(0);GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,vertexBuffer);
            GLES20.glBufferData(GLES20.GL_ARRAY_BUFFER,count*24,buffer,GLES20.GL_STATIC_DRAW);
            int error=GLES20.glGetError();GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,0);
            if(error!=GLES20.GL_NO_ERROR){
                GLES20.glDeleteBuffers(1,ids,0);vertexBuffer=0;
                Log.w("Saturn.GL","Static VBO unavailable; client-buffer fallback error="+error);
            }
        }
    }
    private static Mesh sphere(int slices,int stacks){
        return new Mesh(SaturnGeometry.sphereVertices(slices,stacks));
    }
    private static Mesh ring(){float[] d=new float[512*6*6];int n=0;int[] angle={0,1,0,1,1,0},outer={0,0,1,0,1,1};for(int i=0;i<512;i++)for(int v=0;v<6;v++){
        double a=2*Math.PI*(i+angle[v])/512;float r=outer[v]==0?1.24f:2.30f;
        d[n++]=(float)Math.cos(a)*r;d[n++]=0;d[n++]=(float)Math.sin(a)*r;d[n++]=0;d[n++]=1;d[n++]=0;
    }return new Mesh(d);}

    /** Two coverage samples when supported; otherwise single sample, never a costly 4x/8x/16x allocation. */
    private static final class SceneConfigChooser implements GLSurfaceView.EGLConfigChooser {
        @Override public EGLConfig chooseConfig(EGL10 egl,EGLDisplay display){
            // SAMPLE_BUFFERS/SAMPLES use "at least" matching, so inspect the returned values ourselves.
            int[] spec={EGL10.EGL_RED_SIZE,8,EGL10.EGL_GREEN_SIZE,8,EGL10.EGL_BLUE_SIZE,8,
                    EGL10.EGL_DEPTH_SIZE,16,EGL10.EGL_RENDERABLE_TYPE,4,
                    EGL10.EGL_SURFACE_TYPE,EGL10.EGL_WINDOW_BIT,EGL10.EGL_NONE};
            int[] count=new int[1];
            if(!egl.eglChooseConfig(display,spec,null,0,count)||count[0]==0)
                throw new IllegalArgumentException("No RGB888 ES2 window config");
            EGLConfig[] configs=new EGLConfig[count[0]];
            if(!egl.eglChooseConfig(display,spec,configs,configs.length,count))
                throw new IllegalArgumentException("Unable to enumerate ES2 window configs");
            EGLConfig aa=null,fallback=null;int aaCost=Integer.MAX_VALUE,fallbackCost=Integer.MAX_VALUE;
            Set<Integer> offered=new TreeSet<>();
            for(int i=0;i<count[0];i++){
                EGLConfig candidate=configs[i];
                int buffers=attribute(egl,display,candidate,EGL10.EGL_SAMPLE_BUFFERS);
                int samples=attribute(egl,display,candidate,EGL10.EGL_SAMPLES);
                offered.add(buffers==0?0:samples);
                int cost=attribute(egl,display,candidate,EGL10.EGL_ALPHA_SIZE)*16
                        +Math.max(0,attribute(egl,display,candidate,EGL10.EGL_DEPTH_SIZE)-16)
                        +attribute(egl,display,candidate,EGL10.EGL_STENCIL_SIZE)*4;
                if(buffers==1&&samples==2&&attribute(egl,display,candidate,EGL10.EGL_CONFIG_CAVEAT)==EGL10.EGL_NONE
                        &&cost<aaCost){aa=candidate;aaCost=cost;}
                if(buffers==0&&samples==0&&cost<fallbackCost){fallback=candidate;fallbackCost=cost;}
            }
            EGLConfig chosen=aa!=null?aa:fallback;
            if(chosen==null)throw new IllegalArgumentException("No single-sample or 2x ES2 window config");
            Log.i("Saturn.GL","EGL offered_samples="+offered+" selected_samples="
                    +attribute(egl,display,chosen,EGL10.EGL_SAMPLES)+" depth="
                    +attribute(egl,display,chosen,EGL10.EGL_DEPTH_SIZE)+" alpha="
                    +attribute(egl,display,chosen,EGL10.EGL_ALPHA_SIZE));
            return chosen;
        }
        private static int attribute(EGL10 egl,EGLDisplay display,EGLConfig config,int name){
            int[] value=new int[1];return egl.eglGetConfigAttrib(display,config,name,value)?value[0]:0;
        }
    }
    private static final class SceneProgram {
        final int id,aPos,aNormal,uMvp,uModel,uKind,uColor,uSpin,uPixelFootprint,uRingLut;
        long spinFrame=-1;
        SceneProgram(int id){
            this.id=id;aPos=GLES20.glGetAttribLocation(id,"aPosition");aNormal=GLES20.glGetAttribLocation(id,"aNormal");
            uMvp=GLES20.glGetUniformLocation(id,"uMVP");uModel=GLES20.glGetUniformLocation(id,"uModel");
            uKind=GLES20.glGetUniformLocation(id,"uKind");uColor=GLES20.glGetUniformLocation(id,"uColor");
            uSpin=GLES20.glGetUniformLocation(id,"uSpin");uPixelFootprint=GLES20.glGetUniformLocation(id,"uPixelFootprint");
            uRingLut=GLES20.glGetUniformLocation(id,"uRingLut");
        }
    }
    private final class Renderer implements GLSurfaceView.Renderer{
        Mesh planet,moon,rings,stars,guides,boundMesh;
        SceneProgram mixedProgram,opaqueProgram,currentProgram;
        int ringLutTexture;
        int width,height;volatile boolean dirty=true;volatile long frames;
        volatile float[] projected=new float[51];
        final float[] projection=new float[16],view=new float[16],vp=new float[16],model=new float[16],mvp=new float[16];
        final float[] point=new float[4],clip=new float[4];
        float cameraYaw=28,cameraPitch=24,cameraDistance=6.2f,spin;
        boolean opaqueState,cullState;long frameStart,drawWallNanos,drawWallMaxNanos,drawTimingCount;volatile long lastDrawNanos;
        @Override public void onSurfaceCreated(GL10 unused,EGLConfig config){
            String extensions=GLES20.glGetString(GLES20.GL_EXTENSIONS);
            boolean derivatives=extensions!=null&&Arrays.asList(extensions.split("\\s+")).contains("GL_OES_standard_derivatives");
            int[] range=new int[2],precision=new int[1];
            GLES20.glGetShaderPrecisionFormat(GLES20.GL_FRAGMENT_SHADER,GLES20.GL_HIGH_FLOAT,range,0,precision,0);
            boolean highPrecision=precision[0]>0;
            int mixed;
            try{mixed=link(vertex(highPrecision),fragment(derivatives,highPrecision));}
            catch(IllegalStateException unavailable){
                if(!derivatives)throw unavailable;
                Log.w("Saturn.GL","Derivative shader unavailable; using footprint fallback",unavailable);
                derivatives=false;mixed=link(vertex(highPrecision),fragment(false,highPrecision));
            }
            mixedProgram=new SceneProgram(mixed);opaqueProgram=mixedProgram;
            try{opaqueProgram=new SceneProgram(link(vertex(highPrecision),opaqueFragment(derivatives,highPrecision)));}
            catch(IllegalStateException unavailable){Log.w("Saturn.GL","Opaque shader unavailable; retaining original material program",unavailable);}
            ringLutTexture=0;
            if(highPrecision){
                int texture=bakeRingLut();
                if(texture!=0){
                    SceneProgram lookupMixed=null,lookupOpaque=null;
                    try{
                        lookupMixed=new SceneProgram(link(vertex(true),materialFragment(derivatives,true,false,true)));
                        lookupOpaque=new SceneProgram(link(vertex(true),materialFragment(derivatives,true,true,true)));
                        GLES20.glDeleteProgram(mixedProgram.id);
                        if(opaqueProgram!=mixedProgram)GLES20.glDeleteProgram(opaqueProgram.id);
                        mixedProgram=lookupMixed;opaqueProgram=lookupOpaque;ringLutTexture=texture;
                    }catch(IllegalStateException unavailable){
                        if(lookupMixed!=null)GLES20.glDeleteProgram(lookupMixed.id);
                        if(lookupOpaque!=null)GLES20.glDeleteProgram(lookupOpaque.id);
                        GLES20.glDeleteTextures(1,new int[]{texture},0);
                        Log.w("Saturn.GL","Ring lookup shader unavailable; retaining analytic materials",unavailable);
                    }
                }
            }
            if(ringLutTexture!=0){GLES20.glActiveTexture(GLES20.GL_TEXTURE0);GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,ringLutTexture);}
            currentProgram=null;
            // Smooth close silhouettes without a higher-resolution framebuffer or more shaded pixels.
            planet=sphere(96,56);moon=sphere(20,12);rings=ring();
            Random random=new Random(431960);float[] data=new float[200*6];for(int i=0;i<200;i++){double a=random.nextDouble()*Math.PI*2,z=random.nextDouble()*2-1,r=Math.sqrt(1-z*z)*48;data[i*6]=(float)(Math.cos(a)*r);data[i*6+1]=(float)(z*48);data[i*6+2]=(float)(Math.sin(a)*r);}
            stars=new Mesh(data);
            guides=new Mesh(SaturnGeometry.orbitLines(160));
            for(Mesh mesh:new Mesh[]{planet,moon,rings,stars,guides})mesh.upload();
            boundMesh=null;
            GLES20.glEnable(GLES20.GL_DEPTH_TEST);GLES20.glEnable(GLES20.GL_BLEND);GLES20.glBlendFunc(GLES20.GL_SRC_ALPHA,GLES20.GL_ONE_MINUS_SRC_ALPHA);
            GLES20.glDisable(GLES20.GL_CULL_FACE);GLES20.glCullFace(GLES20.GL_BACK);GLES20.glFrontFace(GLES20.GL_CCW);
            opaqueState=false;cullState=false;drawWallNanos=drawWallMaxNanos=drawTimingCount=0;
            cameraYaw=yaw;cameraPitch=pitch;cameraDistance=distance;
            int[] samples=new int[1];GLES20.glGetIntegerv(GLES20.GL_SAMPLES,samples,0);
            frameStart=SystemClock.elapsedRealtime();Log.i("Saturn.GL","initialized "+GLES20.glGetString(GLES20.GL_RENDERER)
                    +" samples="+samples[0]+" derivatives="+derivatives+" fragment_highp="+highPrecision
                    +" planet_mesh=96x56 moon_mesh=20x12 detail=fixed cadence=continuous-active/vsync-paused opaque_program="
                    +(opaqueProgram!=mixedProgram)+" opaque_cull=CW_BACK opaque_blend=off orbit_draws=1 static_vbo="
                    +(planet.vertexBuffer!=0&&guides.vertexBuffer!=0)+" aa=filtered-native ring_lut="+(ringLutTexture!=0)
                    +" ring_lut_size="+SaturnRingLut.WIDTH+"x"+SaturnRingLut.HEIGHT);
        }
        @Override public void onSurfaceChanged(GL10 unused,int w,int h){
            width=w;height=h;GLES20.glViewport(0,0,w,h);Matrix.perspectiveM(projection,0,42,w/(float)h,.1f,150);projection[8]=-.28f;
            Log.i("Saturn.GL","Wallpaper surface buffer="+w+"x"+h+" view="+surfaceViewWidth+"x"+surfaceViewHeight);
        }
        @Override public void onDrawFrame(GL10 unused){
            final long ticket=framePacer.drawingTicket();
            long drawStarted=SystemClock.elapsedRealtimeNanos();
            try{
                drawFrame();
                long wall=SystemClock.elapsedRealtimeNanos()-drawStarted;
                drawWallNanos+=wall;drawWallMaxNanos=Math.max(drawWallMaxNanos,wall);drawTimingCount++;
                if(frames==1||frames%300==0){
                    int err=GLES20.glGetError();
                    Log.i("Saturn.GL","frame="+frames+" elapsed_ms="+(SystemClock.elapsedRealtime()-frameStart)
                            +" error="+err+" moons=17 navigation="+navigationMotionActive
                            +" draw_submit_wall_avg_ms="+(drawWallNanos/(double)drawTimingCount/1_000_000)
                            +" draw_submit_wall_max_ms="+(drawWallMaxNanos/1_000_000.0));
                    drawWallNanos=drawWallMaxNanos=drawTimingCount=0;
                }
            }finally{framePacer.complete(ticket);}
        }
        private void drawFrame(){
            GLES20.glClearColor(.002f,.003f,.006f,1);GLES20.glClear(GLES20.GL_COLOR_BUFFER_BIT|GLES20.GL_DEPTH_BUFFER_BIT);
            long nowNanos=SystemClock.elapsedRealtimeNanos(),elapsed=lastDrawNanos==0?0:nowNanos-lastDrawNanos;
            lastDrawNanos=nowNanos;
            float turnBlend=SaturnFramePacer.cameraBlend(.22f,elapsed),zoomBlend=SaturnFramePacer.cameraBlend(.16f,elapsed);
            cameraYaw+=(yaw-cameraYaw)*turnBlend;cameraPitch+=(pitch-cameraPitch)*turnBlend;cameraDistance+=(distance-cameraDistance)*zoomBlend;
            if(Math.abs(yaw-cameraYaw)>.01||Math.abs(pitch-cameraPitch)>.01||Math.abs(distance-cameraDistance)>.005)dirty=true;
            double a=Math.toRadians(cameraYaw),p=Math.toRadians(cameraPitch),roll=Math.toRadians(-22);
            Matrix.setLookAtM(view,0,(float)(cameraDistance*Math.sin(a)*Math.cos(p)),(float)(cameraDistance*Math.sin(p)),(float)(cameraDistance*Math.cos(a)*Math.cos(p)),0,0,0,(float)-Math.sin(roll),(float)Math.cos(roll),0);
            Matrix.multiplyMM(vp,0,projection,0,view,0);
            double days=(time()-946728000000L)/86400000.0;
            spin=(float)((days/.444)%1*2*Math.PI);
            Matrix.setIdentityM(model,0);draw(stars,GLES20.GL_POINTS,3,.40f,.43f,.48f,.65f);
            if(orbits){GLES20.glDepthMask(false);draw(guides,GLES20.GL_LINES,4,.45f,.52f,.61f,.20f);GLES20.glDepthMask(true);}
            Matrix.setIdentityM(model,0);Matrix.scaleM(model,0,1,.902f,1);draw(planet,GLES20.GL_TRIANGLES,0,1,1,1,1);
            Matrix.setIdentityM(model,0);draw(rings,GLES20.GL_TRIANGLES,1,1,1,1,1);
            float[] positions=new float[51];
            for(int i=0;i<17;i++){
                double[] xyz=SaturnOrbits.position(i,days,true);float size=.022f+(float)Math.sqrt(SaturnOrbits.RADII[i]/2574.8)*.105f;
                Matrix.setIdentityM(model,0);Matrix.translateM(model,0,(float)xyz[0],(float)xyz[1],(float)xyz[2]);Matrix.scaleM(model,0,size,size,size);
                if(i==5)draw(moon,GLES20.GL_TRIANGLES,2,.83f,.61f,.30f,1);else draw(moon,GLES20.GL_TRIANGLES,2,.69f,.70f,.71f,1);
                point[0]=(float)xyz[0];point[1]=(float)xyz[1];point[2]=(float)xyz[2];point[3]=1;Matrix.multiplyMV(clip,0,vp,0,point,0);
                // Normalized positions keep the separately drawn labels in physical UI coordinates.
                positions[i*3]=clip[0]/clip[3]*.5f+.5f;positions[i*3+1]=.5f-clip[1]/clip[3]*.5f;positions[i*3+2]=clip[3];
            }projected=positions;frames++;
            setOpaqueState(false,false);
        }
        private void setOpaqueState(boolean opaque,boolean cull){
            if(opaqueState!=opaque){
                if(opaque){GLES20.glDisable(GLES20.GL_BLEND);GLES20.glFrontFace(GLES20.GL_CW);}
                else{GLES20.glEnable(GLES20.GL_BLEND);GLES20.glFrontFace(GLES20.GL_CCW);}
                opaqueState=opaque;
            }
            if(cullState!=cull){if(cull)GLES20.glEnable(GLES20.GL_CULL_FACE);else GLES20.glDisable(GLES20.GL_CULL_FACE);cullState=cull;}
        }
        void draw(Mesh mesh,int mode,int kind,float r,float g,float b,float a){
            boolean opaque=kind==0||kind==2;
            SceneProgram program=opaque?opaqueProgram:mixedProgram;
            if(currentProgram!=program){
                GLES20.glUseProgram(program.id);currentProgram=program;boundMesh=null;
                if(program.uRingLut>=0)GLES20.glUniform1i(program.uRingLut,0);
            }
            if(program.spinFrame!=frames){GLES20.glUniform1f(program.uSpin,spin);program.spinFrame=frames;}
            Matrix.multiplyMM(mvp,0,vp,0,model,0);GLES20.glUniformMatrix4fv(program.uMvp,1,false,mvp,0);GLES20.glUniformMatrix4fv(program.uModel,1,false,model,0);GLES20.glUniform1i(program.uKind,kind);GLES20.glUniform4f(program.uColor,r,g,b,a);
            float scale=(float)Math.sqrt(model[0]*model[0]+model[1]*model[1]+model[2]*model[2]);
            float depth=-(view[2]*model[12]+view[6]*model[13]+view[10]*model[14]+view[14]);
            setOpaqueState(opaque,opaque&&SaturnGeometry.sphereCanCull(depth,scale));
            GLES20.glUniform1f(program.uPixelFootprint,(float)(cameraDistance*2*Math.tan(Math.toRadians(21)))/Math.max(1,height)/Math.max(.001f,scale));
            if(boundMesh!=mesh){
                GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,mesh.vertexBuffer);
                if(mesh.vertexBuffer!=0){
                    if(program.aPos>=0)GLES20.glVertexAttribPointer(program.aPos,3,GLES20.GL_FLOAT,false,24,0);
                    if(program.aNormal>=0)GLES20.glVertexAttribPointer(program.aNormal,3,GLES20.GL_FLOAT,false,24,12);
                }else{
                    if(program.aPos>=0){mesh.buffer.position(0);GLES20.glVertexAttribPointer(program.aPos,3,GLES20.GL_FLOAT,false,24,mesh.buffer);}
                    if(program.aNormal>=0){mesh.buffer.position(3);GLES20.glVertexAttribPointer(program.aNormal,3,GLES20.GL_FLOAT,false,24,mesh.buffer);}
                }
                if(program.aPos>=0)GLES20.glEnableVertexAttribArray(program.aPos);
                if(program.aNormal>=0)GLES20.glEnableVertexAttribArray(program.aNormal);boundMesh=mesh;
            }
            GLES20.glDrawArrays(mode,0,mesh.count);
        }
    }
    /** One offscreen draw per new GL context; failures leave the analytic material intact. */
    private static int bakeRingLut(){
        int[] limits=new int[1];GLES20.glGetIntegerv(GLES20.GL_MAX_TEXTURE_SIZE,limits,0);
        if(limits[0]<SaturnRingLut.WIDTH||limits[0]<SaturnRingLut.HEIGHT){Log.w("Saturn.GL","Ring lookup exceeds texture limit");return 0;}
        int[] viewport=new int[4],oldFramebuffer=new int[1],oldProgram=new int[1],oldBuffer=new int[1],oldActiveTexture=new int[1],oldTexture=new int[1];
        boolean[] colorMask=new boolean[4];
        GLES20.glGetIntegerv(GLES20.GL_VIEWPORT,viewport,0);GLES20.glGetIntegerv(GLES20.GL_FRAMEBUFFER_BINDING,oldFramebuffer,0);
        GLES20.glGetIntegerv(GLES20.GL_CURRENT_PROGRAM,oldProgram,0);GLES20.glGetIntegerv(GLES20.GL_ARRAY_BUFFER_BINDING,oldBuffer,0);
        GLES20.glGetIntegerv(GLES20.GL_ACTIVE_TEXTURE,oldActiveTexture,0);GLES20.glActiveTexture(GLES20.GL_TEXTURE0);
        GLES20.glGetIntegerv(GLES20.GL_TEXTURE_BINDING_2D,oldTexture,0);GLES20.glGetBooleanv(GLES20.GL_COLOR_WRITEMASK,colorMask,0);
        int[] capabilities={GLES20.GL_BLEND,GLES20.GL_DEPTH_TEST,GLES20.GL_CULL_FACE,GLES20.GL_SCISSOR_TEST,GLES20.GL_DITHER};
        boolean[] enabled=new boolean[capabilities.length];
        for(int i=0;i<capabilities.length;i++){enabled[i]=GLES20.glIsEnabled(capabilities[i]);GLES20.glDisable(capabilities[i]);}
        int texture=0,framebuffer=0,program=0,position=-1;boolean success=false;
        try{
            int[] id=new int[1];GLES20.glGenTextures(1,id,0);texture=id[0];
            if(texture==0)throw new IllegalStateException("No ring lookup texture");
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,texture);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MIN_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_MAG_FILTER,GLES20.GL_LINEAR);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_S,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexParameteri(GLES20.GL_TEXTURE_2D,GLES20.GL_TEXTURE_WRAP_T,GLES20.GL_CLAMP_TO_EDGE);
            GLES20.glTexImage2D(GLES20.GL_TEXTURE_2D,0,GLES20.GL_RGBA,SaturnRingLut.WIDTH,SaturnRingLut.HEIGHT,0,GLES20.GL_RGBA,GLES20.GL_UNSIGNED_BYTE,null);
            GLES20.glGenFramebuffers(1,id,0);framebuffer=id[0];GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,framebuffer);
            GLES20.glFramebufferTexture2D(GLES20.GL_FRAMEBUFFER,GLES20.GL_COLOR_ATTACHMENT0,GLES20.GL_TEXTURE_2D,texture,0);
            int status=GLES20.glCheckFramebufferStatus(GLES20.GL_FRAMEBUFFER);
            if(framebuffer==0||status!=GLES20.GL_FRAMEBUFFER_COMPLETE)throw new IllegalStateException("Ring lookup framebuffer status="+status);
            GLES20.glGetIntegerv(GLES20.GL_RED_BITS,id,0);int redBits=id[0];
            GLES20.glGetIntegerv(GLES20.GL_GREEN_BITS,id,0);
            if(redBits<8||id[0]<8)throw new IllegalStateException("Ring lookup requires RG8 color precision");
            program=link("attribute vec2 aPosition;void main(){gl_Position=vec4(aPosition,0.,1.);}",SaturnRingLut.bakeFragment());
            position=GLES20.glGetAttribLocation(program,"aPosition");
            if(position<0)throw new IllegalStateException("Ring lookup bake position missing");
            FloatBuffer triangle=ByteBuffer.allocateDirect(6*4).order(ByteOrder.nativeOrder()).asFloatBuffer();
            triangle.put(new float[]{-1,-1,3,-1,-1,3}).position(0);
            GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,0);GLES20.glUseProgram(program);GLES20.glViewport(0,0,SaturnRingLut.WIDTH,SaturnRingLut.HEIGHT);
            GLES20.glColorMask(true,true,true,true);GLES20.glEnableVertexAttribArray(position);GLES20.glVertexAttribPointer(position,2,GLES20.GL_FLOAT,false,0,triangle);
            GLES20.glDrawArrays(GLES20.GL_TRIANGLES,0,3);
            int error=GLES20.glGetError();
            if(error!=GLES20.GL_NO_ERROR)throw new IllegalStateException("Ring lookup bake GL error="+error);
            Log.i("Saturn.GL","Ring lookup baked "+SaturnRingLut.WIDTH+"x"+SaturnRingLut.HEIGHT+" format=RGBA8 dither=off");
            success=true;return texture;
        }catch(RuntimeException unavailable){Log.w("Saturn.GL","Ring lookup bake unavailable; using analytic material",unavailable);return 0;}
        finally{
            // Called during fresh-context setup, before scene attributes have been enabled.
            if(position>=0)GLES20.glDisableVertexAttribArray(position);
            GLES20.glBindFramebuffer(GLES20.GL_FRAMEBUFFER,oldFramebuffer[0]);GLES20.glViewport(viewport[0],viewport[1],viewport[2],viewport[3]);
            GLES20.glUseProgram(oldProgram[0]);GLES20.glBindBuffer(GLES20.GL_ARRAY_BUFFER,oldBuffer[0]);
            GLES20.glBindTexture(GLES20.GL_TEXTURE_2D,oldTexture[0]);GLES20.glActiveTexture(oldActiveTexture[0]);
            GLES20.glColorMask(colorMask[0],colorMask[1],colorMask[2],colorMask[3]);
            for(int i=0;i<capabilities.length;i++){if(enabled[i])GLES20.glEnable(capabilities[i]);else GLES20.glDisable(capabilities[i]);}
            if(program!=0)GLES20.glDeleteProgram(program);if(framebuffer!=0)GLES20.glDeleteFramebuffers(1,new int[]{framebuffer},0);
            if(texture!=0&&!success)GLES20.glDeleteTextures(1,new int[]{texture},0);
        }
    }
    private static int shader(int type,String source){
        int shader=GLES20.glCreateShader(type);GLES20.glShaderSource(shader,source);GLES20.glCompileShader(shader);
        int[] ok=new int[1];GLES20.glGetShaderiv(shader,GLES20.GL_COMPILE_STATUS,ok,0);
        if(ok[0]==0){String error=GLES20.glGetShaderInfoLog(shader);GLES20.glDeleteShader(shader);throw new IllegalStateException(error);}
        return shader;
    }
    private static int link(String vertex,String fragment){
        int program=0,vs=0,fs=0;
        try{
            vs=shader(GLES20.GL_VERTEX_SHADER,vertex);fs=shader(GLES20.GL_FRAGMENT_SHADER,fragment);program=GLES20.glCreateProgram();
            GLES20.glAttachShader(program,vs);GLES20.glAttachShader(program,fs);GLES20.glLinkProgram(program);
            int[] ok=new int[1];GLES20.glGetProgramiv(program,GLES20.GL_LINK_STATUS,ok,0);
            if(ok[0]==0){String error=GLES20.glGetProgramInfoLog(program);GLES20.glDeleteProgram(program);throw new IllegalStateException(error);}
            return program;
        }finally{if(vs!=0)GLES20.glDeleteShader(vs);if(fs!=0)GLES20.glDeleteShader(fs);}
    }
    private static final String VERTEX="attribute vec3 aPosition;attribute vec3 aNormal;uniform mat4 uMVP;uniform mat4 uModel;varying vec3 vLocal;varying vec3 vWorld;varying vec3 vNormal;void main(){vLocal=aPosition;vWorld=(uModel*vec4(aPosition,1.)).xyz;vNormal=mat3(uModel)*aNormal;gl_Position=uMVP*vec4(aPosition,1.);gl_PointSize=1.3;}";
    private static String vertex(boolean highPrecision){return VERTEX.replace("varying vec3",highPrecision?"varying highp vec3":"varying mediump vec3");}
    static String fragment(boolean derivatives){return fragment(derivatives,true);}
    private static String fragment(boolean derivatives,boolean highPrecision){return materialFragment(derivatives,highPrecision,false);}
    static String opaqueFragment(boolean derivatives){return opaqueFragment(derivatives,true);}
    private static String opaqueFragment(boolean derivatives,boolean highPrecision){return materialFragment(derivatives,highPrecision,true);}
    /** Shared material expressions stay identical; opaque coverage has no discard-capable branch. */
    private static String materialFragment(boolean derivatives,boolean highPrecision,boolean opaque){return materialFragment(derivatives,highPrecision,opaque,false);}
    static String lookupFragment(boolean derivatives,boolean opaque){return materialFragment(derivatives,true,opaque,true);}
    private static String materialFragment(boolean derivatives,boolean highPrecision,boolean opaque,boolean lookup){return (derivatives?"#extension GL_OES_standard_derivatives : enable\n":"")
        +(highPrecision?"precision highp float;\n":"precision mediump float;\n")
        +(highPrecision?"varying highp vec3 vLocal;varying highp vec3 vWorld;varying highp vec3 vNormal;":"varying mediump vec3 vLocal;varying mediump vec3 vWorld;varying mediump vec3 vNormal;")
        +"uniform int uKind;uniform vec4 uColor;uniform float uSpin;uniform float uPixelFootprint;"
        +(derivatives?"float footprint(float value,float frequency){return max(fwidth(value),.0002);}":"float footprint(float value,float frequency){return max(uPixelFootprint*frequency,.0002);}")
        +"float wave(float phase,float frequency){float fw=footprint(phase,frequency);return sin(phase)*(1.-smoothstep(1.,3.14159265,fw));}"
        +SaturnRingLut.analyticGlsl()+SaturnRingLut.ringFunction(lookup)
        +"void main(){"+(opaque?"":"if(uKind==3||uKind==4){gl_FragColor=uColor;return;}")+"vec3 L=normalize(vec3(-3.,1.6,4.));float diffuse=max(dot(normalize(vNormal),L),0.);"
        +(opaque?"":"if(uKind==1){float r=length(vLocal.xz),density=rings(r);if(density<.02)discard;vec3 q=vWorld;float t=dot(-q,L);float d=length(q+t*L);float shadow=t>0.?smoothstep(.91,1.05,d):1.;vec3 color=mix(vec3(.28,.24,.19),vec3(.82,.75,.60),density);gl_FragColor=vec4(color*(.24+.76*shadow),density*.94);return;}")
        +"if(uKind==2){float grain=.93+.07*wave(vLocal.x*53.,53.)*wave(vLocal.z*47.,47.);gl_FragColor=vec4(uColor.rgb*grain*(.08+.92*diffuse),1.);return;}"
        +"float lat=vLocal.y,lon=atan(vLocal.z,vLocal.x)+uSpin;float bands=.04*wave(lat*42.+.18*sin(lon*9.),42.);bands+=.015*wave(lat*120.+sin(lon*11.)*.20,120.)+.006*wave(lat*300.,300.);vec3 color=mix(vec3(.64,.54,.38),vec3(.88,.82,.64),.53+.17*sin(lat*14.)+bands);color+=bands;float shadow=1.;if(vWorld.y*L.y<0.){float t=-vWorld.y/L.y;vec3 hit=vWorld+t*L;float r=length(hit.xz);if(t>0.&&r>1.24&&r<2.30)shadow=1.-rings(r)*.72;}float edge=pow(max(0.,1.-abs(dot(normalize(vNormal),normalize(vec3(.4,.4,1.))))),3.)*.025;gl_FragColor=vec4(color*(.035+.965*pow(diffuse,.8)*shadow)+edge*vec3(.48,.42,.31),1.);}";}
}
