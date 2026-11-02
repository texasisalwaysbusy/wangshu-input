#!/usr/bin/env python3
"""Actual association controllers; deterministic synthetic Android dispatch and editors."""
from pathlib import Path
import hashlib, json, subprocess, sys

root = Path(__file__).resolve().parent
project = Path(sys.argv[1]).resolve()
src = project / 'android/app/src/main/java/org/wangshu/ime'
variant = 'project'
out = Path(sys.argv[2]).resolve()
out.mkdir(parents=True)
generated = out / 'generated'
files = {
    'android/os/Looper.java': '''package android.os;
public final class Looper {
 private static final Thread OWNER=Thread.currentThread();
 private static final Looper MAIN=new Looper();
 public static Looper getMainLooper(){return MAIN;}
 public static Looper myLooper(){return Thread.currentThread()==OWNER?MAIN:null;}
 public static boolean isMain(){return myLooper()==MAIN;}
}''',
    'android/os/SystemClock.java': '''package android.os;
public final class SystemClock {
 private static volatile long now;
 public static long elapsedRealtime(){return now;}
 static void advance(long delta){now+=delta;}
}''',
    'android/os/Handler.java': '''package android.os;
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
}''',
    'android/view/inputmethod/InputConnection.java': 'package android.view.inputmethod; public interface InputConnection {}',
}
ime = (src / 'WangshuIme.java').read_text()
prefix = 'static boolean sameVoiceSnapshot('
assert ime.count(prefix) == 1
start = ime.index(prefix)
opening = ime.index('{', start)
depth, end = 1, opening + 1
while depth:
    depth += (ime[end] == '{') - (ime[end] == '}')
    end += 1
files['org/wangshu/ime/WangshuIme.java'] = 'package org.wangshu.ime; final class WangshuIme {\n' + ime[start:end] + '\n}\n'
paths = []
for name, value in files.items():
    path = generated / name
    path.parent.mkdir(parents=True, exist_ok=True)
    path.write_text(value)
    paths.append(path)
sources = [src / name for name in ['PersonalPhraseAssociation.java', 'PersonalPhraseSuggestions.java', 'MathAssociation.java', 'ContextMathCandidates.java', 'SplitSelection.java']]
test = root / 'AssociationCancellationChecks.java'
receipt = {'scope': 'Actual controllers and pure providers; synthetic Looper/Handler, clock and editor. Android NOT_RUN',
           'variant': variant,
           'sha256': {str(p): hashlib.sha256(p.read_bytes()).hexdigest() for p in sources + [src / 'WangshuIme.java', test, Path(__file__)]}}
snapshots = out / 'source-snapshots'
snapshots.mkdir()
for p in sources + [src / 'WangshuIme.java', test, Path(__file__)]:
    (snapshots / p.name).write_bytes(p.read_bytes())
receipt['frozen_source_sha256'] = {str(p.relative_to(out)): hashlib.sha256(p.read_bytes()).hexdigest() for p in sorted(snapshots.iterdir())}
(out / 'source-receipt.json').write_text(json.dumps(receipt, indent=2) + '\n')
classes = out / 'classes'
classes.mkdir()
r = subprocess.run(['java', '-XX:-UsePerfData', 'com.sun.tools.javac.Main', '-proc:none', '-d', str(classes)] + [str(p) for p in sources + paths + [test]])
if r.returncode:
    sys.exit(r.returncode)
sys.exit(subprocess.run(['java', '-XX:-UsePerfData', '-cp', str(classes), 'org.wangshu.ime.AssociationCancellationChecks']).returncode)
