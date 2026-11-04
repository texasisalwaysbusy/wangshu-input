package org.wangshu.ime;
/** Synthetic data: assess whether an unconditional overlay suppression can hide an existing entry. */
public final class OverlaySuppressionTest {
 public static void main(String[] args){CandidateLearning model=new CandidateLearning();model.pin("xian","自造专业候选",1);String[] learned=model.candidates("xi'an",2);if(learned.length!=1)throw new AssertionError("synthetic overlay precondition");
  String[] initial={learned[0],"个人固定"};CandidatePages before=new CandidatePages(initial,i->new String[0],java.util.List.of(),null);CandidatePages proposed=new CandidatePages(new String[0],i->new String[0],java.util.List.of(),null);
  boolean presentBefore=before.page(0).length>0,presentAfter=proposed.page(0).length>0;System.out.println("synthetic overlay-only entry visible before="+presentBefore+", after unconditional suppression="+presentAfter+", underlying overlay remains pinned="+model.isPinned("xian","自造专业候选"));
  if(!presentBefore||!presentAfter)throw new AssertionError("RED: unconditional suppression hides an existing overlay-only candidate");
 }
}
