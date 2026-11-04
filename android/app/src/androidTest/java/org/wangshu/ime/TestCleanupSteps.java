package org.wangshu.ime;

import java.util.ArrayList;

/** Every test cleanup step runs; secondary failures cannot erase a primary error. */
final class TestCleanupSteps {
    interface Step{void run()throws Throwable;}
    static final class Outcome {
        final ArrayList<Throwable> failures=new ArrayList<>();
        void attachOrThrow(Throwable primary)throws Exception{
            if(failures.isEmpty())return;
            if(primary!=null){for(Throwable error:failures)if(error!=primary)primary.addSuppressed(error);return;}
            Throwable first=failures.get(0);for(int i=1;i<failures.size();i++)if(failures.get(i)!=first)first.addSuppressed(failures.get(i));
            if(first instanceof Error)throw (Error)first;if(first instanceof Exception)throw (Exception)first;throw new AssertionError("Fixture cleanup failed",first);
        }
    }
    static Outcome run(Step...steps){Outcome result=new Outcome();for(Step step:steps)try{step.run();}catch(Throwable failure){result.failures.add(failure);}return result;}
    private TestCleanupSteps(){}
}
