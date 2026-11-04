package android.os;
import java.util.*;
public final class Handler {
 private static final class Item {
  final Runnable r;final long at,order;
  Item(Runnable r,long at,long order){this.r=r;this.at=at;this.order=order;}
 }
 private static long sequence,posts;
 private static final PriorityQueue<Item> queue=new PriorityQueue<>(Comparator.comparingLong((Item i)->i.at).thenComparingLong(i->i.order));
 public Handler(Looper owner){if(owner!=Looper.getMainLooper())throw new AssertionError("not main looper");}
 public boolean post(Runnable r){synchronized(queue){posts++;}return postDelayed(r,0);}
 public boolean postDelayed(Runnable r,long delay){synchronized(queue){queue.add(new Item(r,SystemClock.elapsedRealtime()+delay,sequence++));}return true;}
 public void removeCallbacks(Runnable r){synchronized(queue){queue.removeIf(i->i.r==r);}}
 public static void pump(){
  if(!Looper.isMain())throw new AssertionError("pump on worker");
  for(int n=0;n<1000;n++){Item next;synchronized(queue){if(queue.isEmpty()||queue.peek().at>SystemClock.elapsedRealtime())return;next=queue.remove();}next.r.run();}
  throw new AssertionError("unbounded main callbacks");
 }
 public static void advance(long millis){SystemClock.advance(millis);pump();}
 public static int pending(){synchronized(queue){return queue.size();}}
 public static long posts(){synchronized(queue){return posts;}}
}