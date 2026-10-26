package com.gathin.tvhome;

import android.content.Context;
import android.media.tv.TvInputInfo;
import android.media.tv.TvInputManager;
import android.util.Log;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** Read-only framework input snapshot. Connection is TV-reported, not a video-signal probe. */
final class HdmiInputs {
    static final int UNKNOWN = -1;
    private static final Pattern PORT_LABEL = Pattern.compile("(?i)\\bHDMI\\s*[-_:]?\\s*([1-3])\\b");
    private static final Map<Integer,String> LOGGED_IDENTITIES=new LinkedHashMap<>();

    static final class Port {
        final int number, state;
        final String title, deviceName, deviceIdentity, rootInputId, inputId;
        final HdmiCecMetadata cecMetadata;
        final HdmiDeviceKind deviceKind;
        Port(int number, int state, String deviceName, String rootInputId, String inputId) {
            this(number,state,deviceName,deviceName,rootInputId,inputId);
        }
        Port(int number,int state,String deviceName,String deviceIdentity,String rootInputId,String inputId){
            this(number,state,deviceName,deviceIdentity,rootInputId,inputId,null);
        }
        Port(int number,int state,String deviceName,String deviceIdentity,String rootInputId,String inputId,HdmiCecMetadata metadata){
            this.number=number;this.state=state;this.title="HDMI "+number;
            this.deviceName=deviceName;this.deviceIdentity=deviceIdentity;this.rootInputId=rootInputId;this.inputId=inputId;
            this.cecMetadata=metadata;this.deviceKind=HdmiDeviceKind.detect(deviceIdentity,metadata);
        }
        String description(){return title+", "+stateLabel(state)+(deviceName.isEmpty()?"":", "+deviceName);}
        @Override public boolean equals(Object other){
            if(!(other instanceof Port))return false;Port p=(Port)other;
            return number==p.number&&state==p.state&&deviceName.equals(p.deviceName)&&deviceIdentity.equals(p.deviceIdentity)
                    &&Objects.equals(rootInputId,p.rootInputId)&&Objects.equals(inputId,p.inputId)
                    &&Objects.equals(cecMetadata,p.cecMetadata);
        }
        @Override public int hashCode(){return Objects.hash(number,state,deviceName,deviceIdentity,rootInputId,inputId,cecMetadata);}
    }

    static List<Port> read(Context context){
        TvInputManager manager=(TvInputManager)context.getSystemService(Context.TV_INPUT_SERVICE);
        List<TvInputInfo> inputs=list(manager);
        Map<String,TvInputInfo> byId=new LinkedHashMap<>();
        Map<String,Integer> states=new LinkedHashMap<>();
        for(TvInputInfo input:inputs){byId.put(input.getId(),input);states.put(input.getId(),state(manager,input.getId()));}
        Map<Integer,TvInputInfo> roots=new LinkedHashMap<>();
        for(TvInputInfo input:inputs){
            if(input.getType()!=TvInputInfo.TYPE_HDMI||!input.isPassthroughInput())continue;
            TvInputInfo parent=byId.get(input.getParentId());
            if(parent!=null&&parent.getType()==TvInputInfo.TYPE_HDMI)continue;
            int port=portNumber(label(context,input,false));
            if(port==0)port=portNumber(label(context,input,true));
            if(port>0){
                TvInputInfo old=roots.get(port);
                // Prefer the actual hardware registration over a duplicate software root.
                if(old==null||(!hardware(old.getId())&&hardware(input.getId())))roots.put(port,input);
            }
        }
        List<Port> result=new ArrayList<>();
        for(int number=1;number<=3;number++){
            TvInputInfo root=roots.get(number);
            if(root==null){Port missing=new Port(number,UNKNOWN,"",null,null);logIdentity(missing);result.add(missing);continue;}
            List<TvInputInfo> children=new ArrayList<>();
            for(TvInputInfo child:inputs)if(child.getType()==TvInputInfo.TYPE_HDMI
                    &&child.isPassthroughInput()&&root.getId().equals(child.getParentId()))children.add(child);
            // CEC reports can differ from their hardware parent; prefer a connected/standby device.
            children.sort(Comparator.comparingInt((TvInputInfo item)->priority(states.getOrDefault(item.getId(),UNKNOWN)))
                    .thenComparing(TvInputInfo::getId));
            TvInputInfo child=children.isEmpty()?null:children.get(0);
            int rootState=states.getOrDefault(root.getId(),UNKNOWN);
            int childState=child==null?UNKNOWN:states.getOrDefault(child.getId(),UNKNOWN);
            int effective=effectiveState(rootState,childState);
            boolean childPresent=child!=null&&(childState==TvInputManager.INPUT_STATE_CONNECTED
                    ||childState==TvInputManager.INPUT_STATE_CONNECTED_STANDBY);
            // Identity comes only from the present CEC device; a port alias is not device detection.
            String identity=childPresent?label(context,child,false):"";
            String name=childPresent?label(context,child,true):"";
            if(name.isEmpty())name=identity;
            if(name.isEmpty())name=label(context,root,true);
            if(name.equalsIgnoreCase("HDMI "+number)||name.equalsIgnoreCase("HDMI"+number))name="";
            String launch=child!=null&&(childState==TvInputManager.INPUT_STATE_CONNECTED
                    ||childState==TvInputManager.INPUT_STATE_CONNECTED_STANDBY)?child.getId():root.getId();
            HdmiCecMetadata metadata=childPresent?HdmiCecMetadata.readAndroid11(child,number,root.getId()):null;
            Port port=new Port(number,effective,name,identity,root.getId(),launch,metadata);
            logIdentity(port);result.add(port);
        }
        return Collections.unmodifiableList(result);
    }

    private static synchronized void logIdentity(Port port){
        HdmiCecMetadata metadata=port.cecMetadata;
        String identity=port.state+":"+port.inputId+":"+port.deviceIdentity+":"+port.deviceKind+":"
                +(metadata==null?"none":metadata.physicalAddress+":"+metadata.logicalAddress+":"
                +metadata.vendorId+":"+metadata.deviceType+":"+metadata.powerStatus+":"+metadata.displayName);
        if(identity.equals(LOGGED_IDENTITIES.put(port.number,identity)))return;
        Log.i("Hdmi.Identity","port="+port.number+" state="+stateLabel(port.state)
                +" vendor="+(metadata==null?-1:metadata.vendorId)
                +" power="+(metadata==null?-1:metadata.powerStatus)
                +" cecType="+(metadata==null?-1:metadata.deviceType)+" kind="+port.deviceKind);
    }

    static String resolveInputId(Context context,int number){
        for(Port port:read(context))if(port.number==number)return port.inputId;
        return null;
    }
    static List<TvInputInfo> list(TvInputManager manager){
        if(manager==null)return Collections.emptyList();
        try{return new ArrayList<>(manager.getTvInputList());}catch(RuntimeException unavailable){return Collections.emptyList();}
    }
    static int state(TvInputManager manager,String id){
        if(manager==null||id==null)return UNKNOWN;
        try{
            int value=manager.getInputState(id);
            return value==TvInputManager.INPUT_STATE_CONNECTED||value==TvInputManager.INPUT_STATE_CONNECTED_STANDBY
                    ||value==TvInputManager.INPUT_STATE_DISCONNECTED?value:UNKNOWN;
        }catch(RuntimeException unavailable){return UNKNOWN;}
    }
    static int effectiveState(int rootState,int childState){
        if(childState==TvInputManager.INPUT_STATE_CONNECTED
                ||childState==TvInputManager.INPUT_STATE_CONNECTED_STANDBY)return childState;
        return rootState==UNKNOWN&&childState!=UNKNOWN?childState:rootState;
    }
    private static int priority(int state){
        if(state==TvInputManager.INPUT_STATE_CONNECTED)return 0;
        if(state==TvInputManager.INPUT_STATE_CONNECTED_STANDBY)return 1;
        return state==UNKNOWN?2:3;
    }
    static String stateLabel(int state){
        if(state==TvInputManager.INPUT_STATE_CONNECTED)return "Connected";
        if(state==TvInputManager.INPUT_STATE_CONNECTED_STANDBY)return "Standby";
        if(state==TvInputManager.INPUT_STATE_DISCONNECTED)return "Not connected";
        return "Unknown";
    }
    static int stateColor(int state){
        if(state==TvInputManager.INPUT_STATE_CONNECTED)return 0xFF72E4B4;
        if(state==TvInputManager.INPUT_STATE_CONNECTED_STANDBY)return 0xFFF0C77B;
        return state==TvInputManager.INPUT_STATE_DISCONNECTED?0xFFB0BAC6:0xFF8395A8;
    }
    static String label(Context context,TvInputInfo input,boolean custom){
        try{
            CharSequence label=custom?input.loadCustomLabel(context):input.loadLabel(context);
            if(label==null)return "";
            String clean=label.toString().replaceAll("[\\p{Cntrl}\\u202A-\\u202E\\u2066-\\u2069]"," ").trim();
            return clean.length()>80?clean.substring(0,80):clean;
        }catch(RuntimeException unavailable){return "";}
    }
    static int portNumber(String label){Matcher match=PORT_LABEL.matcher(label);return match.find()?Integer.parseInt(match.group(1)):0;}
    private static boolean hardware(String id){return id.matches(".*/HW\\d+$");}
}
