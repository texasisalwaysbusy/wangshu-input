package org.wangshu.ime;
/** Synthetic words and keys only. The desired explicit separator must disambiguate learning. */
public final class LearningSegmentationTest{
 public static void main(String[] args)throws Exception{
  CandidateLearning model=new CandidateLearning();model.pin("xian","仙",1);String[] without=model.candidates("xian",2);String[] with=model.candidates("xi'an",2);
  System.out.println("unsplit pinned visible="+(without.length>0)+"; explicitly split pinned visible="+model.isPinned("xi'an","仙")+"; split candidate count="+with.length);
  if(model.isPinned("xi'an","仙"))throw new AssertionError("RED: split xi'an shares the unsplit xian personal overlay identity");
 }
}
