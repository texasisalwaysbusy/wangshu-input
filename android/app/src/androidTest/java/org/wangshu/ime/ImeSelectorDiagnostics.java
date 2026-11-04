package org.wangshu.ime;

import android.app.UiAutomation;
import android.graphics.Rect;
import android.os.Build;
import android.os.SystemClock;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.*;

/** Failure-only metadata. IME first; incomplete scans never imply missing product nodes. */
final class ImeSelectorDiagnostics {
    static final String SPACE="空格；按住本地语音，松手结束",PACKAGE="org.wangshu.ime";
    private static final Set<String> LABELS=new HashSet<>(Arrays.asList(SPACE,"数学","字母","中","英","语音","数学语音","剪贴板与常用语","光标与文本编辑","设置","收起输入键盘"));
    private static final class Entry {final AccessibilityNodeInfo node;final int depth;Entry(AccessibilityNodeInfo n,int d){node=n;depth=d;}}
    static AccessibilityNodeInfo currentRoot(AccessibilityWindowInfo window){
        return Build.VERSION.SDK_INT>=33?window.getRoot(AccessibilityNodeInfo.FLAG_PREFETCH_DESCENDANTS_BREADTH_FIRST):window.getRoot();
    }
    static String snapshot(UiAutomation auto){
        StringBuilder out=new StringBuilder("IME_SELECTOR_DIAGNOSTIC metadata only; no text dump or actions\n");long start=SystemClock.elapsedRealtime(),deadline=start+4000;
        if(auto.getServiceInfo()!=null)out.append(" serviceFlags=").append(auto.getServiceInfo().flags).append('\n');
        List<AccessibilityWindowInfo> windows=new ArrayList<>(auto.getWindows());
        // The old diagnostic could spend its budget on system windows before the IME.
        windows.sort(Comparator.comparingInt(w->w.getType()==AccessibilityWindowInfo.TYPE_INPUT_METHOD?0:w.isActive()?1:2));
        int count=0;for(AccessibilityWindowInfo window:windows){
            if(count++>=8||SystemClock.elapsedRealtime()>deadline){out.append("WINDOW_OR_TIME_LIMIT remainingWindows=").append(windows.size()-count+1).append('\n');break;}
            Rect bounds=new Rect();window.getBoundsInScreen(bounds);long windowStart=SystemClock.elapsedRealtime();
            out.append("window id=").append(window.getId()).append(" type=").append(window.getType()).append(" active=").append(window.isActive()).append(" focused=").append(window.isFocused()).append(" bounds=").append(bounds).append('\n');
            AccessibilityNodeInfo root=currentRoot(window);if(root==null){out.append(" root=NULL scan=INCOMPLETE\n");continue;}
            boolean refreshed=root.refresh();out.append(" root pkg=").append(root.getPackageName()).append(" class=").append(root.getClassName()).append(" refreshed=").append(refreshed).append(" visible=").append(root.isVisibleToUser()).append(" enabled=").append(root.isEnabled()).append('\n');
            if(!refreshed){out.append(" scan=INCOMPLETE_STALE_ROOT\n");continue;}
            ArrayDeque<Entry> queue=new ArrayDeque<>();Set<AccessibilityNodeInfo> seen=new HashSet<>();queue.add(new Entry(root,0));seen.add(root);int visited=0,nulls=0,labels=0,childRequests=0;boolean limited=false;
            while(!queue.isEmpty()&&SystemClock.elapsedRealtime()<=deadline){
                Entry entry=queue.removeFirst();AccessibilityNodeInfo node=entry.node;visited++;
                // Log only fixed labels, never editor/candidate/clipboard/transcript text.
                String label=String.valueOf(node.getContentDescription());
                if(LABELS.contains(label)){labels++;Rect r=new Rect();node.getBoundsInScreen(r);out.append(" TARGET desc=").append(label).append(" depth=").append(entry.depth).append(" window=").append(node.getWindowId()).append(" pkg=").append(node.getPackageName()).append(" class=").append(node.getClassName()).append(" visible=").append(node.isVisibleToUser()).append(" enabled=").append(node.isEnabled()).append(" important=").append(node.isImportantForAccessibility()).append(" clickable=").append(node.isClickable()).append(" clickAction=").append((node.getActions()&AccessibilityNodeInfo.ACTION_CLICK)!=0).append(" bounds=").append(r).append('\n');}
                int children=node.getChildCount();if(children>StrictImeSelector.MAX_CHILDREN||entry.depth>=StrictImeSelector.MAX_DEPTH&&children>0)limited=true;
                if(entry.depth>=StrictImeSelector.MAX_DEPTH)continue;
                for(int i=0;i<Math.min(children,StrictImeSelector.MAX_CHILDREN);i++){
                    if(seen.size()>=StrictImeSelector.MAX_NODES||childRequests>=StrictImeSelector.MAX_CHILD_REQUESTS){limited=true;break;}
                    childRequests++;AccessibilityNodeInfo child=node.getChild(i);if(child==null){nulls++;continue;}
                    if(seen.add(child))queue.addLast(new Entry(child,entry.depth+1));
                }
            }
            boolean complete=queue.isEmpty()&&!limited&&nulls==0;
            out.append(" nodes=").append(visited).append(" knownLabels=").append(labels).append(" nullChildren=").append(nulls).append(" remainingQueue=").append(queue.size()).append(" limited=").append(limited).append(" scan=").append(complete?"COMPLETE_SNAPSHOT":"INCOMPLETE").append(" elapsedMs=").append(SystemClock.elapsedRealtime()-windowStart).append('\n');
        }
        return out.append("diagnosticElapsedMs=").append(SystemClock.elapsedRealtime()-start).append("; remote platform calls can exceed the local deadline\n").toString();
    }
}
