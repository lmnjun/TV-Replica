package com.gathin.tvhome;

import android.app.Activity;
import android.content.SharedPreferences;
import android.graphics.Canvas;
import android.graphics.Paint;
import android.graphics.Path;
import android.os.Handler;
import android.os.Looper;
import android.util.Log;
import android.view.Gravity;
import android.view.View;
import android.widget.FrameLayout;
import android.widget.LinearLayout;
import android.widget.TextView;
import java.net.HttpURLConnection;
import java.net.URL;
import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.text.SimpleDateFormat;
import java.util.Date;
import java.util.Locale;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.CancellationException;
import java.util.concurrent.Future;
import org.json.JSONObject;

/** City weather from device/network location, cached locally. */
final class WeatherWidget extends LinearLayout {
    private final Handler handler=new Handler(Looper.getMainLooper());
    private final ExecutorService worker=Executors.newSingleThreadExecutor();
    private final WeatherRequestGuard requests=new WeatherRequestGuard(WeatherCache.LOCK);
    private final SharedPreferences prefs;
    private final TextView temperature,condition,details,source,city;
    private final Glyph glyph;
    private boolean active,loading;
    private volatile boolean closed;
    private Future<?> pendingFetch;
    /** Guarded by WeatherCache.LOCK, including registration versus close. */
    private HttpURLConnection activeConnection;
    private static final long INTERVAL=30*60_000L;

    private final Runnable refresh=new Runnable(){@Override public void run(){if(!active)return;fetch(false);handler.postDelayed(this,INTERVAL);}};
    WeatherWidget(Activity activity,FrameLayout root){
        super(activity);prefs=activity.getSharedPreferences("weather_kuala_lumpur",0);
        setOrientation(VERTICAL);setGravity(Gravity.END);setPadding(0,0,0,0);setImportantForAccessibility(IMPORTANT_FOR_ACCESSIBILITY_YES);
        LinearLayout clocks=activity.findViewById(R.id.leftBlock);
        ((android.view.ViewGroup)clocks.getParent()).removeView(clocks);clocks.setGravity(Gravity.END);
        for(int i=0;i<clocks.getChildCount();i++){View child=clocks.getChildAt(i);if(child instanceof TextView)((TextView)child).setGravity(Gravity.END);else child.setVisibility(GONE);}
        addView(clocks,new LinearLayout.LayoutParams(-1,-2));
        InterTypography.apply(clocks.findViewById(R.id.clock),500);
        InterTypography.apply(clocks.findViewById(R.id.date),500);
        city=text(prefs.getString("city","Locating…"),9,0xB3FFFFFF);city.setLetterSpacing(.08f);city.setEllipsize(android.text.TextUtils.TruncateAt.END);
        InterTypography.apply(city,500);
        LinearLayout.LayoutParams cityLp=new LinearLayout.LayoutParams(-2,-2);cityLp.topMargin=dp(14);addView(city,cityLp);
        LinearLayout row=new LinearLayout(activity);row.setOrientation(HORIZONTAL);row.setGravity(Gravity.CENTER_VERTICAL|Gravity.END);
        glyph=new Glyph();row.addView(glyph,new LinearLayout.LayoutParams(dp(22),dp(22)));
        temperature=text("—°",20,0xF2FFFFFF);LinearLayout.LayoutParams tp=new LinearLayout.LayoutParams(-2,-2);tp.setMargins(dp(10),0,0,0);row.addView(temperature,tp);
        InterTypography.apply(temperature,600);
        condition=text("Loading…",10,0xD9FFFFFF);condition.setTypeface(getResources().getFont(R.font.inter_semibold));condition.setLetterSpacing(.02f);LinearLayout.LayoutParams rp=new LinearLayout.LayoutParams(-1,-2);rp.topMargin=dp(7);addView(row,rp);
        LinearLayout.LayoutParams cp=new LinearLayout.LayoutParams(-2,-2);cp.topMargin=dp(5);addView(condition,cp);
        details=text("",10,0xB3FFFFFF);details.setVisibility(GONE);source=text("",8,0x66FFFFFF);source.setVisibility(GONE);
        FrameLayout.LayoutParams lp=new FrameLayout.LayoutParams(dp(240),-2,Gravity.TOP|Gravity.END);lp.setMargins(0,dp(26),dp(48),0);root.addView(this,lp);
        setOnClickListener(v->fetch(true));setFocusable(false);loadCache(false);
    }
    private int dp(float x){return Math.round(x*getResources().getDisplayMetrics().density);}
    private TextView text(String s,float size,int color){TextView t=new TextView(getContext());t.setText(s);t.setTextSize(size);t.setTextColor(color);t.setTypeface(getResources().getFont(R.font.inter_regular));t.setIncludeFontPadding(false);t.setSingleLine(true);t.setAllCaps(true);t.setGravity(Gravity.END);return t;}
    void start(){if(active||closed)return;active=true;handler.post(refresh);}
    void stop(){active=false;handler.removeCallbacks(refresh);}
    void close(){
        HttpURLConnection connection;
        synchronized(WeatherCache.LOCK){
            if(closed)return;
            closed=true;requests.close();
            connection=activeConnection;activeConnection=null;
        }
        stop();loading=false;
        if(pendingFetch!=null){pendingFetch.cancel(true);pendingFetch=null;}
        worker.shutdownNow();
        if(connection!=null)connection.disconnect();
        handler.removeCallbacksAndMessages(null);
    }
    private void loadCache(boolean offline){
        String data=prefs.getString("json","");if(data.isEmpty()){condition.setText(offline?"Unavailable":"Loading…");return;}
        try{show(new JSONObject(data),offline);}catch(Exception e){condition.setText("Unavailable");}
    }
    private void fetch(boolean force){
        if(closed||loading)return;
        if(!force&&prefs.contains("latitude")&&System.currentTimeMillis()-prefs.getLong("fetched",0)<INTERVAL){loadCache(false);return;}
        final long token=requests.begin();if(token==0)return;
        loading=true;pendingFetch=worker.submit(()->{
            HttpURLConnection connection=null;
            try{
                ensureCurrent(token);detectLocation(token);ensureCurrent(token);
                WeatherCache.Request request=WeatherCache.request(prefs);
                String endpoint="https://api.open-meteo.com/v1/forecast?latitude="+request.latitude+"&longitude="+request.longitude+"&current=temperature_2m,apparent_temperature,weather_code,is_day&daily=temperature_2m_max,temperature_2m_min&timezone=auto&forecast_days=1";
                connection=(HttpURLConnection)new URL(endpoint).openConnection();trackConnection(token,connection);connection.setConnectTimeout(6000);connection.setReadTimeout(8000);connection.setRequestProperty("User-Agent","CleanYourTV-Weather/1.0");
                int code=connection.getResponseCode();if(code!=200)throw new java.io.IOException("HTTP "+code);
                ByteArrayOutputStream bytes=new ByteArrayOutputStream();
                try(InputStream input=connection.getInputStream()){byte[] buffer=new byte[4096];int n;while((n=input.read(buffer))!=-1){ensureCurrent(token);bytes.write(buffer,0,n);if(bytes.size()>128000)throw new java.io.IOException("Oversized response");}}
                String raw=bytes.toString("UTF-8");JSONObject data=new JSONObject(raw);data.getJSONObject("current").getDouble("temperature_2m");data.getJSONObject("daily").getJSONArray("temperature_2m_max").getDouble(0);
                boolean published=requests.runIfCurrent(token,()->WeatherCache.publish(prefs,request,raw));
                handler.post(()->{if(!requests.current(token))return;loading=false;pendingFetch=null;try{if(published)show(data,false);else loadCache(false);}catch(Exception e){loadCache(true);}});
                if(requests.current(token))Log.i("Weather.Widget",prefs.getString("city","Unknown")+" updated: "+data.getJSONObject("current").toString());
            }catch(CancellationException canceled){
                // Closing/replacing Home invalidates both location and forecast publication.
            }catch(Exception error){if(requests.current(token)){Log.w("Weather.Widget","Update failed: "+error.getClass().getSimpleName()+" "+error.getMessage());handler.post(()->{if(!requests.current(token))return;loading=false;pendingFetch=null;loadCache(true);});}}
            finally{releaseConnection(connection);}
        });
    }
    private void ensureCurrent(long token){if(!requests.current(token)||Thread.currentThread().isInterrupted())throw new CancellationException();}
    private void trackConnection(long token,HttpURLConnection connection){
        if(!requests.runIfCurrent(token,()->{activeConnection=connection;return true;}))throw new CancellationException();
    }
    private void releaseConnection(HttpURLConnection connection){
        if(connection==null)return;
        synchronized(WeatherCache.LOCK){if(activeConnection==connection)activeConnection=null;}
        connection.disconnect();
    }
    private void detectLocation(long token)throws Exception{
        ensureCurrent(token);
        if(prefs.contains("latitude")&&System.currentTimeMillis()-prefs.getLong("location_time",0)<6*60*60_000L)return;
        try{
            android.location.LocationManager manager=(android.location.LocationManager)getContext().getSystemService(android.content.Context.LOCATION_SERVICE);
            if(getContext().checkSelfPermission(android.Manifest.permission.ACCESS_FINE_LOCATION)==android.content.pm.PackageManager.PERMISSION_GRANTED){
                android.location.Location loc=manager.getLastKnownLocation(android.location.LocationManager.NETWORK_PROVIDER);
                if(loc!=null&&System.currentTimeMillis()-loc.getTime()<24*60*60_000L){
                    String name="Current location";
                    try{java.util.List<android.location.Address> addresses=new android.location.Geocoder(getContext(),Locale.ENGLISH).getFromLocation(loc.getLatitude(),loc.getLongitude(),1);if(addresses!=null&&!addresses.isEmpty()&&addresses.get(0).getLocality()!=null)name=addresses.get(0).getLocality();}catch(Exception ignored){}
                    saveLocation(token,name,loc.getLatitude(),loc.getLongitude(),"device");return;
                }
            }
        }catch(CancellationException canceled){throw canceled;}catch(Exception ignored){}
        HttpURLConnection geo=null;
        try{
            ensureCurrent(token);geo=(HttpURLConnection)new URL("https://ipwho.is/?fields=success,city,latitude,longitude").openConnection();trackConnection(token,geo);geo.setConnectTimeout(5000);geo.setReadTimeout(6000);
            if(geo.getResponseCode()!=200)throw new java.io.IOException("Location unavailable");
            ByteArrayOutputStream bytes=new ByteArrayOutputStream();try(InputStream input=geo.getInputStream()){byte[] b=new byte[2048];int n;while((n=input.read(b))!=-1){ensureCurrent(token);bytes.write(b,0,n);if(bytes.size()>16000)throw new java.io.IOException("Location response too large");}}
            JSONObject json=new JSONObject(bytes.toString("UTF-8"));if(!json.optBoolean("success"))throw new java.io.IOException("Location unavailable");
            saveLocation(token,json.getString("city"),json.getDouble("latitude"),json.getDouble("longitude"),"network");
        }catch(Exception e){ensureCurrent(token);if(!prefs.contains("latitude"))throw e;}
        finally{releaseConnection(geo);}
    }
    private void saveLocation(long token,String name,double latitude,double longitude,String method)throws Exception{
        if(!Double.isFinite(latitude)||!Double.isFinite(longitude)||Math.abs(latitude)>90||Math.abs(longitude)>180||name.isEmpty())throw new java.io.IOException("Invalid location");
        if(!requests.runIfCurrent(token,()->{
            String lat=Double.toString(latitude),lon=Double.toString(longitude);
            boolean moved=!lat.equals(prefs.getString("latitude",""))||!lon.equals(prefs.getString("longitude",""));
            SharedPreferences.Editor editor=prefs.edit().putString("city",name).putString("latitude",lat).putString("longitude",lon)
                    .putString("location_method",method).putLong("location_time",System.currentTimeMillis());
            if(moved)editor.remove("json").putLong("fetched",0);
            editor.apply();
            return true;
        }))throw new CancellationException();
        handler.post(()->{if(requests.current(token))city.setText(name);});if(requests.current(token))Log.i("Weather.Widget","Auto location: "+name+" ("+method+")");
    }
    private void show(JSONObject data,boolean offline)throws Exception{
        JSONObject c=data.getJSONObject("current"),d=data.getJSONObject("daily");int code=c.getInt("weather_code");
        temperature.setText(Math.round(c.getDouble("temperature_2m"))+"°");condition.setText(description(code)+(offline?" · Cached":""));glyph.code=code;glyph.day=c.optInt("is_day",0)==1;glyph.invalidate();
        details.setText("H "+Math.round(d.getJSONArray("temperature_2m_max").getDouble(0))+"°   L "+Math.round(d.getJSONArray("temperature_2m_min").getDouble(0))+"°   Feels "+Math.round(c.getDouble("apparent_temperature"))+"°");
        long age=System.currentTimeMillis()-prefs.getLong("fetched",0);String time=c.optString("time","").replace('T',' ');
        source.setText("Open-Meteo · "+(offline?"Cached · ":age>3*60*60_000L?"Cached · ":"")+time);
        city.setText(prefs.getString("city","Location unavailable")+(offline?" · CACHED":""));
        setContentDescription(city.getText()+", "+temperature.getText()+", "+condition.getText()+", "+details.getText()+", "+source.getText());
    }
    private static String description(int c){if(c==0)return "Clear";if(c==1)return "Mostly clear";if(c==2)return "Partly cloudy";if(c==3)return "Overcast";if(c<50)return "Fog";if(c<60)return "Drizzle";if(c<70)return "Rain";if(c<80)return "Snow";if(c<90)return "Showers";return "Thunderstorms";}
    private final class Glyph extends View{
        int code=3;boolean day;private int current;
        private android.graphics.drawable.Drawable drawable;
        Glyph(){super(WeatherWidget.this.getContext());}
        @Override protected void onDraw(Canvas canvas){
            int res=code==0?(day?R.drawable.ic_weather_sunny:R.drawable.ic_weather_night)
                    :code<3?(day?R.drawable.ic_weather_partly_day:R.drawable.ic_weather_partly_night)
                    :code==3?R.drawable.ic_weather_cloud:code<50?R.drawable.ic_weather_fog
                    :code<70?R.drawable.ic_weather_rain:code<80?R.drawable.ic_weather_snow
                    :code<90?R.drawable.ic_weather_rain:R.drawable.ic_weather_storm;
            if(current!=res){drawable=getResources().getDrawable(res,getContext().getTheme()).mutate();drawable.setTint(0xD9FFFFFF);current=res;}
            drawable.setBounds(0,0,getWidth(),getHeight());drawable.draw(canvas);
        }
    }
}
