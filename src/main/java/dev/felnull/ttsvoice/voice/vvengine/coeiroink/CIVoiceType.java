package dev.felnull.ttsvoice.voice.vvengine.coeiroink;

import dev.felnull.ttsvoice.voice.VoiceCategory;
import dev.felnull.ttsvoice.voice.vvengine.VVEVoiceType;
import dev.felnull.ttsvoice.voice.vvengine.VVEngineManager;

public class CIVoiceType extends VVEVoiceType {
    public CIVoiceType(int vveId, String name, String styleName, boolean neta) {
        super(CoeiroInkManager.NAME, vveId, name, styleName, neta);
    }

    @Override
    public VVEngineManager getEngineManager() {
        return CoeiroInkManager.getInstance();
    }

    @Override
    public VoiceCategory getCategory() {
        return CIVoiceCategory.getInstance();
    }
}
