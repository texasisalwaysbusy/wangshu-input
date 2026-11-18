package org.wangshu.ime;

/** Immutable per-recording output policy. This layer never reads keyboard or editor state.
 * It does not recognize speech, infer punctuation, commit text, or retain audio/transcripts. */
enum VoiceResultPolicy {
    RAW(0, "原始文字"),
    MATH_SYMBOLS(1, "数学术语转符号"),
    NUMBERS(2, "数字与日期");

    final int setting;
    final String label;

    VoiceResultPolicy(int setting, String label) {
        this.setting = setting;
        this.label = label;
    }

    static VoiceResultPolicy fromSetting(int value) {
        return switch (value) {
            case 1 -> MATH_SYMBOLS;
            case 2 -> NUMBERS;
            default -> RAW;
        };
    }
    interface MathConverter {String convert(String raw);}
    String apply(String raw,boolean dictionaryReady,MathConverter converter){
        if(this==NUMBERS)return VoiceNumberFormat.apply(raw);
        return this==MATH_SYMBOLS&&dictionaryReady?converter.convert(raw):raw;
    }
}
