package com.gathin.tvhome;

import android.app.Activity;
import android.graphics.Color;
import android.graphics.drawable.ColorDrawable;
import android.graphics.drawable.GradientDrawable;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.os.Handler;
import android.os.Looper;
import android.text.TextUtils;
import android.view.Gravity;
import android.view.KeyEvent;
import android.view.LayoutInflater;
import android.view.View;
import android.view.animation.DecelerateInterpolator;
import android.widget.ImageView;
import android.widget.LinearLayout;
import android.widget.PopupWindow;
import android.widget.ScrollView;
import android.widget.TextView;
import android.widget.Toast;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.DoubleConsumer;

/** Legible, live input picker. Owns its callbacks only while its window is shown. */
final class InputsPopup extends PopupWindow {
    private final Activity activity;
    private final View root,anchor;
    private final boolean animations,reducedMotion;
    private final Consumer<String> openInput;
    private final Runnable openSourcePanel;
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final TvInputManager manager;
    private final float density;
    private final LinearLayout panel,rows;
    private final List<View> buttons=new ArrayList<>();
    private OnDismissListener dismissed;
    private Consumer<Boolean> transitionListener;
    private DoubleConsumer motionListener;
    private float motionProgress;
    private boolean registered,closing,finished,transitionActive;
    private Runnable pendingAction;
    private final Runnable refresh=()->{if(isShowing()&&!closing)rebuildRows();};
    private final TvInputManager.TvInputCallback callback=new TvInputManager.TvInputCallback(){
        @Override public void onInputStateChanged(String id,int state){changed();}
        @Override public void onInputAdded(String id){changed();}
        @Override public void onInputRemoved(String id){changed();}
        @Override public void onInputUpdated(String id){changed();}
        @Override public void onTvInputInfoUpdated(TvInputInfo info){changed();}
    };

    InputsPopup(Activity activity,View root,View anchor,boolean animations,boolean reducedMotion,
                Consumer<String> openInput,Runnable openSourcePanel){
        super();this.activity=activity;this.root=root;this.anchor=anchor;
        this.animations=animations;this.reducedMotion=reducedMotion;this.openInput=openInput;this.openSourcePanel=openSourcePanel;
        density=activity.getResources().getDisplayMetrics().density;
        manager=(TvInputManager)activity.getSystemService(Activity.TV_INPUT_SERVICE);
        panel=new SoundKeyLayout(activity);panel.setOrientation(LinearLayout.VERTICAL);
        panel.setPadding(dp(14),dp(16),dp(14),dp(14));panel.setBackgroundResource(R.drawable.hdmi_menu_panel);
        TextView title=text("Inputs",22,0xFFFFFFFF,true);title.setPadding(dp(8),0,0,dp(4));
        InterTypography.apply(title,700);
        panel.addView(title,new LinearLayout.LayoutParams(-1,dp(34)));
        TextView note=text("Choose a connected device or HDMI port",12,0xC7FFFFFF,false);
        note.setPadding(dp(8),0,0,dp(12));note.setSingleLine(true);note.setEllipsize(TextUtils.TruncateAt.END);
        panel.addView(note,new LinearLayout.LayoutParams(-1,dp(28)));
        ScrollView scroll=new ScrollView(activity);scroll.setVerticalScrollBarEnabled(false);scroll.setFocusable(false);
        scroll.setFillViewport(false);scroll.setClipToPadding(false);
        rows=new LinearLayout(activity);rows.setOrientation(LinearLayout.VERTICAL);rows.setClipToPadding(false);
        scroll.addView(rows,new ScrollView.LayoutParams(-1,-2));
        panel.addView(scroll,new LinearLayout.LayoutParams(-1,0,1));
        TextView footer=text("SELECT AN INPUT  ·  BACK TO CLOSE",9,0xA9C4D6E8,true);
        footer.setLetterSpacing(.04f);footer.setGravity(Gravity.CENTER_VERTICAL);footer.setPadding(dp(8),dp(10),0,0);
        panel.addView(footer,new LinearLayout.LayoutParams(-1,dp(28)));
        setContentView(panel);setFocusable(true);setOutsideTouchable(true);setClippingEnabled(true);
        setBackgroundDrawable(new ColorDrawable(Color.TRANSPARENT));setAnimationStyle(0);setElevation(0);
        super.setOnDismissListener(()->{
            closing=true;finished=true;panel.animate().cancel();
            handler.removeCallbacks(refresh);unregister();transition(false);motion(0);
            if(dismissed!=null)dismissed.onDismiss();
        });
    }

    @Override public void setOnDismissListener(OnDismissListener listener){dismissed=listener;}
    void setTransitionListener(Consumer<Boolean> listener){transitionListener=listener;}
    void setMotionListener(DoubleConsumer listener){motionListener=listener;if(listener!=null)listener.accept(motionProgress);}
    private void motion(float progress){
        motionProgress=Math.max(0f,Math.min(1f,progress));
        if(motionListener!=null)motionListener.accept(motionProgress);
    }
    private void transition(boolean active){
        if(transitionActive==active)return;transitionActive=active;
        if(transitionListener!=null)transitionListener.accept(active);
    }
    void show(){
        if(isShowing()||finished)return;
        transition(animations);
        try{
        int safe=dp(48),screenW=root.getWidth(),screenH=root.getHeight();
        if(screenW<=0)screenW=activity.getResources().getDisplayMetrics().widthPixels;
        if(screenH<=0)screenH=activity.getResources().getDisplayMetrics().heightPixels;
        int width=Math.min(dp(410),Math.max(dp(200),screenW-2*safe));
        rebuildRows();
        int height=Math.min(dp(16+34+28+14+28)+buttons.size()*dp(82),screenH-2*safe);
        setWidth(width);setHeight(height);
        int[] at=new int[2],origin=new int[2];anchor.getLocationOnScreen(at);root.getLocationOnScreen(origin);
        int x=Math.max(safe,Math.min(at[0]-origin[0],screenW-safe-width));
        int y=Math.max(safe,Math.min(at[1]-origin[1]+anchor.getHeight()+dp(12),screenH-safe-height));
        panel.setAlpha(animations?0f:1f);panel.setTranslationY(animations&&!reducedMotion?dp(8):0);
        motion(panel.getAlpha());
        showAtLocation(root,Gravity.TOP|Gravity.LEFT,x,y);
        register();
        if(animations)panel.animate().alpha(1f).translationY(0).setDuration(reducedMotion?100:220)
                .setInterpolator(new DecelerateInterpolator(1.8f)).setUpdateListener(value->motion(panel.getAlpha()))
                .withEndAction(()->{if(!closing){motion(1);transition(false);}}).start();
        if(!buttons.isEmpty())buttons.get(0).requestFocus();
        }catch(RuntimeException failure){panel.animate().cancel();transition(false);motion(0);unregister();throw failure;}
    }

    private void changed(){handler.removeCallbacks(refresh);handler.postDelayed(refresh,80);}
    private void register(){
        if(manager==null||registered)return;
        try{manager.registerCallback(callback,handler);registered=true;}catch(RuntimeException unavailable){/* snapshot remains usable */}
    }
    private void unregister(){
        if(manager==null||!registered)return;
        try{manager.unregisterCallback(callback);}catch(RuntimeException ignored){}registered=false;
    }
    private void rebuildRows(){
        View focus=panel.findFocus();Object oldKey=focus==null?null:focus.getTag();
        rows.removeAllViews();buttons.clear();
        boolean anyInput=false;
        for(HdmiInputs.Port port:HdmiInputs.read(activity)){
            String subtitle=HdmiInputs.stateLabel(port.state)+(port.deviceName.isEmpty()?"":" · "+port.deviceName);
            addRow("hdmi:"+port.number,port.title,subtitle,port.state,R.drawable.ic_input,()->{
                String id=HdmiInputs.resolveInputId(activity,port.number);
                if(id!=null)openInput.accept(id);else if(openSourcePanel!=null)openSourcePanel.run();
                else Toast.makeText(activity,port.title+" is not available",Toast.LENGTH_SHORT).show();
            });
            anyInput|=port.inputId!=null;
        }
        for(TvInputInfo input:HdmiInputs.list(manager)){
            if(input.getType()==TvInputInfo.TYPE_HDMI||!input.isPassthroughInput())continue;
            try{if(input.isHidden(activity))continue;}catch(RuntimeException ignored){}
            String title=HdmiInputs.label(activity,input,true);
            if(title.isEmpty())title=HdmiInputs.label(activity,input,false);
            if(title.isEmpty())title="External input";
            String id=input.getId();
            addRow(id,title,"External input",HdmiInputs.UNKNOWN,R.drawable.ic_av,()->openInput.accept(id));
            anyInput=true;
        }
        if(!anyInput&&openSourcePanel!=null)addRow("source-panel","TV input settings","Open the TV source selector",HdmiInputs.UNKNOWN,R.drawable.ic_input,openSourcePanel);
        LauncherSounds.muteSystemEffects(rows);
        if(oldKey!=null)for(View button:buttons)if(oldKey.equals(button.getTag())){button.requestFocus();return;}
        if(isShowing()&&!buttons.isEmpty())buttons.get(0).requestFocus();
    }
    private void addRow(String key,String title,String subtitle,int state,int icon,Runnable action){
        View row=LayoutInflater.from(activity).inflate(R.layout.hdmi_input_row,rows,false);row.setTag(key);
        row.setContentDescription(title+", "+subtitle);
        ((ImageView)row.findViewById(R.id.hdmiRowIcon)).setImageResource(icon);
        ((TextView)row.findViewById(R.id.hdmiRowTitle)).setText(title);
        ((TextView)row.findViewById(R.id.hdmiRowSubtitle)).setText(subtitle);
        GradientDrawable dot=new GradientDrawable();dot.setShape(GradientDrawable.OVAL);dot.setColor(HdmiInputs.stateColor(state));
        row.findViewById(R.id.hdmiRowDot).setBackground(dot);
        LinearLayout.LayoutParams lp=new LinearLayout.LayoutParams(-1,dp(74));lp.bottomMargin=dp(8);rows.addView(row,lp);
        buttons.add(row);row.setOnClickListener(v->dismissThen(action));
        final boolean[] pressed={false};
        row.setOnKeyListener((v,code,event)->{
            if(code==KeyEvent.KEYCODE_DPAD_CENTER||code==KeyEvent.KEYCODE_ENTER){
                if(event.getAction()==KeyEvent.ACTION_DOWN){if(event.getRepeatCount()==0)pressed[0]=true;return true;}
                boolean activate=pressed[0]&&!event.isCanceled();pressed[0]=false;if(activate)dismissThen(action);return true;
            }
            if(event.getAction()!=KeyEvent.ACTION_DOWN)return false;
            int index=buttons.indexOf(v);
            return code==KeyEvent.KEYCODE_DPAD_LEFT||code==KeyEvent.KEYCODE_DPAD_RIGHT
                    ||code==KeyEvent.KEYCODE_DPAD_UP&&index==0||code==KeyEvent.KEYCODE_DPAD_DOWN&&index==buttons.size()-1;
        });
    }
    @Override public void dismiss(){dismissThen(null);}
    private void dismissThen(Runnable action){
        if(closing||finished)return;closing=true;pendingAction=action;transition(animations&&isShowing());handler.removeCallbacks(refresh);unregister();
        panel.animate().cancel();
        if(!animations||!isShowing()){finish();return;}
        panel.animate().alpha(0).translationY(reducedMotion?0:dp(5)).setDuration(reducedMotion?80:160)
                .setInterpolator(new DecelerateInterpolator(1.4f)).setUpdateListener(value->motion(panel.getAlpha()))
                .withEndAction(this::finish).start();
    }
    void dismissImmediately(){pendingAction=null;closing=true;panel.animate().cancel();finish();}
    private void finish(){
        transition(false);motion(0);if(finished)return;finished=true;InputsPopup.super.dismiss();
        if(pendingAction!=null)pendingAction.run();else if(activity.hasWindowFocus())anchor.requestFocus();
    }
    private TextView text(String value,int size,int color,boolean medium){
        TextView text=new TextView(activity);text.setText(value);text.setTextSize(size);text.setTextColor(color);
        text.setIncludeFontPadding(false);text.setTypeface(activity.getResources().getFont(medium?R.font.inter_medium:R.font.inter_regular));return text;
    }
    private int dp(float value){return Math.round(value*density);}
}
