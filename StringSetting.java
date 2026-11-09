package dev.myclient.module;

/** Setare text (de ex. un URL). Se editeaza din GUI cu click. Nu intra in codurile de share ale unui modul. */
public class StringSetting extends Setting<String> {
    public StringSetting(String name, String def) { super(name, def); }
}
