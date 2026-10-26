package com.gathin.tvhome;

import java.util.regex.Pattern;

/** Current CEC identity classification. HDMI does not probe the source's GPU. */
enum HdmiDeviceKind {
    UNKNOWN, SWITCH, XBOX, PLAYSTATION, APPLE_TV, PC;

    private static final Pattern NINTENDO_SWITCH=token("nintendo\\s*switch(?:\\s*(?:2|oled|lite))?");
    private static final Pattern SHORT_SWITCH=Pattern.compile("(?iu)^switch(?:\\s+(?:2|oled|lite))?$");
    private static final Pattern XBOX_NAME=token("xbox(?:\\s*(?:360|one(?:\\s*[sx])?|series\\s*[sx]))?");
    private static final Pattern PLAYSTATION_NAME=token("play\\s*station(?:\\s*[345])?|ps\\s*[345](?:\\s*pro)?");
    private static final Pattern APPLE_TV_NAME=token("apple\\s*tv(?:\\s*4k)?");
    private static final Pattern PC_NAME=token("pc|computer|desktop|laptop|macbook|imac|mac\\s+(?:mini|studio|pro)|nvidia|intel|amd|geforce|radeon");
    private static final Pattern SHIELD_NAME=token("shield");
    private static final Pattern GENERIC_PLAYBACK=Pattern.compile("(?iu)^(?:playback(?:[ _-]?[123])?)?$");
    private static final Pattern OTHER_NINTENDO=token("wii(?:\\s*u)?");

    static HdmiDeviceKind detect(String currentCecLabel){
        if(currentCecLabel==null)return UNKNOWN;
        String name=currentCecLabel.trim();if(name.isEmpty())return UNKNOWN;
        if(NINTENDO_SWITCH.matcher(name).find()||SHORT_SWITCH.matcher(name).matches())return SWITCH;
        if(XBOX_NAME.matcher(name).find())return XBOX;
        if(PLAYSTATION_NAME.matcher(name).find())return PLAYSTATION;
        if(APPLE_TV_NAME.matcher(name).find())return APPLE_TV;
        // NVIDIA SHIELD is a streaming source, not evidence of a PC/GPU input.
        if(!SHIELD_NAME.matcher(name).find()&&PC_NAME.matcher(name).find())return PC;
        return UNKNOWN;
    }
    static HdmiDeviceKind detect(String currentCecLabel,HdmiCecMetadata metadata){
        HdmiDeviceKind named=detect(currentCecLabel);
        if(named!=UNKNOWN||metadata==null)return named;
        if(currentCecLabel!=null&&OTHER_NINTENDO.matcher(currentCecLabel).find())return UNKNOWN;
        named=detect(metadata.displayName);
        if(named!=UNKNOWN)return named;
        // A vendor is not a model. Only a present playback source with a generic default name can
        // use these observed CEC identities. Explicit Wii/Wii U or Mac/PC names keep their meaning.
        if(!genericPlayback(currentCecLabel)||!genericPlayback(metadata.displayName)
                ||metadata.deviceType!=4||(metadata.logicalAddress!=4&&metadata.logicalAddress!=8
                &&metadata.logicalAddress!=11))return UNKNOWN;
        if(metadata.vendorId==0x0009BF)return SWITCH;
        if(metadata.vendorId==0x0010FA)return APPLE_TV;
        return UNKNOWN;
    }
    private static boolean genericPlayback(String name){
        return name==null||GENERIC_PLAYBACK.matcher(name.trim()).matches();
    }
    private static Pattern token(String regex){
        return Pattern.compile("(?iu)(?<![\\p{L}\\p{N}])(?:"+regex+")(?![\\p{L}\\p{N}])");
    }
}
