package com.gathin.tvhome;

import android.content.pm.ResolveInfo;
import android.graphics.drawable.Icon;
import android.media.tv.TvInputInfo;
import android.net.Uri;
import android.os.Build;
import android.os.Parcel;
import android.os.Parcelable;
import android.text.TextUtils;
import java.util.Objects;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/** A bounded Android 11 serialization fallback; no hidden getter, reflection, or CEC permission. */
final class HdmiCecMetadata {
    private static final int MAX_PARCEL_BYTES=128*1024;
    private static final String CEC_CLASS="android.hardware.hdmi.HdmiDeviceInfo";
    private static final Pattern CEC_ID=Pattern.compile(".*/HDMI([0-9A-Fa-f]{4})([0-9A-Fa-f]{2})$");
    final int physicalAddress,portId,logicalAddress,deviceType,vendorId,powerStatus;
    final String displayName;

    private HdmiCecMetadata(int physical,int port,int logical,int type,int vendor,int power,String name){
        physicalAddress=physical;portId=port;logicalAddress=logical;deviceType=type;
        vendorId=vendor;powerStatus=power;displayName=name;
    }

    /** Only the documented AOSP R wire layout is accepted; later SDK layouts are deliberately ignored.
     * Sources: AOSP android-11.0.0_r1 media/java/android/media/tv/TvInputInfo.java writeToParcel,
     * core/java/android/hardware/hdmi/HdmiDeviceInfo.java writeToParcel.
     * The framework already supplied this Parcelable through getTvInputList(). We serialize that
     * public object and read its fields, never load or invoke the hidden HdmiDeviceInfo class. */
    static HdmiCecMetadata readAndroid11(TvInputInfo input,int expectedPort,String expectedParent){
        if(Build.VERSION.SDK_INT!=30||input==null||input.getType()!=TvInputInfo.TYPE_HDMI
                ||expectedParent==null||!expectedParent.equals(input.getParentId()))return null;
        Parcel parcel=Parcel.obtain();
        try{
            input.writeToParcel(parcel,0);
            if(parcel.dataSize()<64||parcel.dataSize()>MAX_PARCEL_BYTES)return null;
            parcel.setDataPosition(0);
            ResolveInfo service=ResolveInfo.CREATOR.createFromParcel(parcel);
            if(service==null||service.serviceInfo==null)return null;
            String id=parcel.readString();int type=parcel.readInt();int hardware=parcel.readByte();
            if(!input.getId().equals(id)||type!=TvInputInfo.TYPE_HDMI||hardware!=1)return null;
            TextUtils.CHAR_SEQUENCE_CREATOR.createFromParcel(parcel);
            Parcelable uri=parcel.readParcelable(null);
            if(uri!=null&&!(uri instanceof Uri))return null;
            parcel.readInt(); // label resource
            for(int i=0;i<3;i++){
                Parcelable icon=parcel.readParcelable(null);
                if(icon!=null&&!(icon instanceof Icon))return null;
            }
            parcel.readString(); // setup activity
            int record=parcel.readByte();int tunerCount=parcel.readInt();
            if((record!=0&&record!=1)||tunerCount<0||tunerCount>64)return null;
            HdmiCecMetadata metadata=readCec(new Fields(){
                @Override public int readInt(){requireBytes(parcel,4);return parcel.readInt();}
                @Override public String readString(){
                    requireBytes(parcel,4);String value=parcel.readString();
                    if(parcel.dataPosition()>parcel.dataSize())throw new IllegalArgumentException("string bounds");
                    return value;
                }
            },id,expectedPort);
            if(metadata==null)return null;
            requireBytes(parcel,12);
            int switched=parcel.readByte();int relative=parcel.readInt();String parent=parcel.readString();
            if(!validEnvelope(expectedParent,parent,switched,relative))return null;
            requireBytes(parcel,4);parcel.readBundle();
            return parcel.dataAvail()==0?metadata:null;
        }catch(RuntimeException|LinkageError incompatibleLayout){return null;}
        finally{parcel.recycle();}
    }

    interface Fields {int readInt();String readString();}

    /** Kept independent of Android runtime so truncation/type/range checks have real JVM coverage. */
    static HdmiCecMetadata readCec(Fields fields,String expectedId,int expectedPort){
        try{
            if(!CEC_CLASS.equals(fields.readString()))return null;
            int hdmiType=fields.readInt(),physical=fields.readInt(),port=fields.readInt();
            if(hdmiType!=0)return null; // CEC only; MHL/hardware/inactive use different layouts.
            int logical=fields.readInt(),type=fields.readInt(),vendor=fields.readInt(),power=fields.readInt();
            String name=fields.readString();
            return validated(expectedId,expectedPort,physical,port,logical,type,vendor,power,name);
        }catch(RuntimeException malformed){return null;}
    }

    static HdmiCecMetadata validated(String inputId,int expectedPort,int physical,int port,int logical,
            int type,int vendor,int power,String name){
        if(inputId==null||inputId.length()>1024||expectedPort<1||expectedPort>3||port!=expectedPort
                ||logical<0||logical>14||type<0||type>7||vendor<0||vendor>0xFFFFFF
                ||power< -1||power>3||name==null||name.length()>80||!validPhysical(physical)
                ||((physical>>>12)&15)!=port)return null;
        Matcher id=CEC_ID.matcher(inputId);
        if(!id.matches()||Integer.parseInt(id.group(1),16)!=physical
                ||Integer.parseInt(id.group(2),16)!=logical)return null;
        // Never let a control/bidi character make a generic label look like a branded source.
        for(int i=0;i<name.length();i++){
            char ch=name.charAt(i);
            if(Character.isISOControl(ch)||(ch>=0x202A&&ch<=0x202E)||(ch>=0x2066&&ch<=0x2069))return null;
        }
        return new HdmiCecMetadata(physical,port,logical,type,vendor,power,name.trim());
    }

    static boolean validEnvelope(String expectedParent,String parent,int switched,int relative){
        return expectedParent!=null&&expectedParent.equals(parent)&&(switched==0||switched==1)
                &&relative>=0&&relative<=7;
    }
    private static boolean validPhysical(int physical){
        if(physical<=0||physical>=0xFFFF)return false;
        boolean zero=false;
        for(int shift=12;shift>=0;shift-=4){
            int digit=(physical>>>shift)&15;
            if(digit==0)zero=true;else if(zero)return false;
        }
        return true;
    }
    private static void requireBytes(Parcel parcel,int bytes){
        if(parcel.dataAvail()<bytes)throw new IllegalArgumentException("truncated metadata");
    }
    @Override public boolean equals(Object other){
        if(!(other instanceof HdmiCecMetadata))return false;HdmiCecMetadata m=(HdmiCecMetadata)other;
        return physicalAddress==m.physicalAddress&&portId==m.portId&&logicalAddress==m.logicalAddress
                &&deviceType==m.deviceType&&vendorId==m.vendorId&&powerStatus==m.powerStatus&&displayName.equals(m.displayName);
    }
    @Override public int hashCode(){return Objects.hash(physicalAddress,portId,logicalAddress,deviceType,vendorId,powerStatus,displayName);}
}
