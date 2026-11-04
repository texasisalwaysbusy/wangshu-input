package org.wangshu.ime;

/** Immutable per-recording output policy. This layer never reads keyboard or editor state.
 * It does not recognize speech, infer punctuation, commit text, or retain audio/transcripts. */
enum VoiceResultPolicy {
    RAW, MATH_SYMBOLS;
    interface MathConverter {String convert(String raw);}
    String apply(String raw,boolean dictionaryReady,MathConverter converter){
        return this==MATH_SYMBOLS&&dictionaryReady?converter.convert(raw):raw;
    }
}
