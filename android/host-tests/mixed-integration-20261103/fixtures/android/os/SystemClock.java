package android.os;
public final class SystemClock {
 private static volatile long now;
 public static long elapsedRealtime(){return now;}
 static void advance(long delta){now+=delta;}
}