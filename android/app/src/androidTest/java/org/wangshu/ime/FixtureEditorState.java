package org.wangshu.ime;

/** Strict bounded decoder for the explicit, generated-only scanner test fixture.
 * This packet is metadata, never a substitute for reading actual host text. */
final class FixtureEditorState {
    static final String EXTRA="org.wangshu.scan.test.PROBE_SESSION",LABEL="独立应用只读编辑状态",HEADER="wangshu-fixture-editor-v1";
    final String session,instance,wire;final long revision,textRevision;final int length,start,end;
    final boolean attached,shown,focused,windowFocused,alive,overflow;
    private FixtureEditorState(String[] p,String wire){
        session=p[1];instance=p[2];this.wire=wire;revision=number(p[3]);textRevision=number(p[4]);length=position(p[5]);start=position(p[6]);end=position(p[7]);attached=flag(p[8]);shown=flag(p[9]);focused=flag(p[10]);windowFocused=flag(p[11]);alive=flag(p[12]);overflow=flag(p[13]);
        if(textRevision>revision)throw new IllegalArgumentException("Probe text revision exceeds state revision");
    }
    static boolean validId(String id){return id!=null&&id.matches("[0-9a-f]{8}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{4}-[0-9a-f]{12}");}
    static FixtureEditorState parse(String wire,String expectedSession){
        if(wire==null||wire.length()>256||!validId(expectedSession))throw new IllegalArgumentException("Invalid/missing bounded probe");String[] parts=wire.split("\t",-1);
        if(parts.length!=14||!HEADER.equals(parts[0])||!validId(parts[1])||!validId(parts[2])||!expectedSession.equals(parts[1]))throw new IllegalArgumentException("Probe kind/session/schema mismatch");
        return new FixtureEditorState(parts,wire);
    }
    private static long number(String value){if(!value.matches("0|[1-9][0-9]{0,18}"))throw new IllegalArgumentException("Invalid probe counter");try{return Long.parseLong(value);}catch(NumberFormatException bad){throw new IllegalArgumentException("Probe counter overflow",bad);}}
    private static int position(String value){if(value.equals("-1"))return -1;long parsed=number(value);if(parsed>Integer.MAX_VALUE)throw new IllegalArgumentException("Probe position overflow");return (int)parsed;}
    private static boolean flag(String value){if(!value.equals("0")&&!value.equals("1"))throw new IllegalArgumentException("Invalid probe flag");return value.equals("1");}
    boolean ready(){return !overflow&&attached&&shown&&focused&&windowFocused&&alive&&length>=0&&length<=2048&&start>=0&&end>=start&&end<=length;}
    boolean same(FixtureEditorState other){return other!=null&&wire.equals(other.wire);}
    static boolean coherent(FixtureEditorState before,FixtureEditorState after,String instance,int logicalLength,int a11yStart,int a11yEnd){
        if(before==null||!before.same(after)||!after.ready()||!after.instance.equals(instance)||after.length!=logicalLength)return false;
        return (a11yStart==-1&&a11yEnd==-1)||(a11yStart==after.start&&a11yEnd==after.end);
    }
}
