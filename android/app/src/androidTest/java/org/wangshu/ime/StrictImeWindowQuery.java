package org.wangshu.ime;

import android.app.UiAutomation;
import android.os.Build;
import android.view.accessibility.AccessibilityNodeInfo;
import android.view.accessibility.AccessibilityWindowInfo;
import java.util.*;

/** Read-only, bounded window observation. No actions and no application/cache-data I/O. */
final class StrictImeWindowQuery {
    private static final int MAX_WINDOWS=16;
    static final class Result {
        AccessibilityNodeInfo node;
        // Observation metadata only; callers still must bind/refresh any target.
        boolean systemGuardComplete,requestedWindowSeen;
        final StringBuilder detail=new StringBuilder();
        public String toString(){return detail.toString();}
    }
    static void beginObservation(UiAutomation auto){
        // Invalidate stale child IDs once per awaited target, not on every poll.
        if(Build.VERSION.SDK_INT>=34)auto.clearCache();
    }
    private static List<AccessibilityWindowInfo> windows(UiAutomation auto){
        List<AccessibilityWindowInfo> values=new ArrayList<>(auto.getWindows());
        if(values.size()>MAX_WINDOWS)throw new AssertionError("PRECONDITION window-count limit; no action sent");
        return values;
    }
    private static Set<String> systemWindows(List<AccessibilityWindowInfo> windows){
        Set<String> result=new TreeSet<>();for(AccessibilityWindowInfo w:windows)if(w.getType()==AccessibilityWindowInfo.TYPE_SYSTEM)result.add(w.getId()+":"+w.isActive()+":"+w.isFocused());return result;
    }
    private static void noAnr(AccessibilityNodeInfo root){
        Set<String> names=new HashSet<>(Arrays.asList("Quickstep isn't responding","System UI isn't responding","Process system isn't responding"));
        ArrayDeque<AccessibilityNodeInfo> queue=new ArrayDeque<>();Set<AccessibilityNodeInfo> seen=new HashSet<>();queue.add(root);seen.add(root);int requests=0;
        while(!queue.isEmpty()){
            AccessibilityNodeInfo candidate=queue.removeFirst();String text=String.valueOf(candidate.getText());
            if(names.contains(text)&&candidate.refresh()&&candidate.isVisibleToUser()&&text.contentEquals(candidate.getText()==null?"":candidate.getText()))throw new AssertionError("PRECONDITION system ANR; no action sent");
            int children=candidate.getChildCount();if(children>StrictImeSelector.MAX_CHILDREN)throw new AssertionError("PRECONDITION incomplete system-dialog scan; no action sent");
            for(int i=0;i<children;i++){if(++requests>StrictImeSelector.MAX_CHILD_REQUESTS)throw new AssertionError("PRECONDITION system-dialog child-request limit; no action sent");AccessibilityNodeInfo child=candidate.getChild(i);if(child!=null&&seen.add(child)){if(seen.size()>StrictImeSelector.MAX_NODES)throw new AssertionError("PRECONDITION system-dialog node limit; no action sent");queue.addLast(child);}}
        }
    }
    static Result find(UiAutomation auto,int type,String owner,String label,boolean description){
        Result result=new Result();result.detail.append("label=").append(label).append(" requestedType=").append(type).append(' ');
        List<AccessibilityWindowInfo> before=windows(auto);Set<String> guarded=systemWindows(before);
        // Guard every system window, but do not walk unrelated editors or the
        // complete keyboard before looking for a shallow requested control.
        for(AccessibilityWindowInfo window:before)if(window.getType()==AccessibilityWindowInfo.TYPE_SYSTEM){
            int id=window.getId();
            AccessibilityNodeInfo root=ImeSelectorDiagnostics.currentRoot(window);
            if(root==null){result.detail.append("SYSTEM_ROOT_UNAVAILABLE id=").append(window.getId());return result;}
            if(!root.refresh()||root.getWindowId()!=id||window.getId()!=id||window.getType()!=AccessibilityWindowInfo.TYPE_SYSTEM){result.detail.append("STALE_SYSTEM_ROOT id=").append(id);return result;}
            noAnr(root);
        }
        // Focus/IME windows may have been replaced while the dialog guard read
        // remote nodes. Never query the earlier list or reuse roots keyed by it.
        List<AccessibilityWindowInfo> current=windows(auto);
        if(!guarded.equals(systemWindows(current))){result.detail.append("SYSTEM_WINDOWS_CHANGED_AFTER_GUARD");return result;}
        result.systemGuardComplete=true;
        boolean sawType=false;
        for(AccessibilityWindowInfo window:current){
            int id=window.getId(),actualType=window.getType();result.detail.append("[id=").append(id).append(" type=").append(actualType);
            if(actualType!=type){result.detail.append(" skipped] ");continue;}sawType=true;result.requestedWindowSeen=true;
            AccessibilityNodeInfo root=ImeSelectorDiagnostics.currentRoot(window);
            if(root==null){result.detail.append(" ROOT_NULL] ");continue;}
            if(window.getId()!=id||window.getType()!=type){result.detail.append(" DESCRIPTOR_CHANGED] ");continue;}
            if(root.getWindowId()!=id){result.detail.append(" ROOT_WINDOW_MISMATCH root=").append(root.getWindowId()).append("] ");continue;}
            StrictImeSelector.Scan scan=StrictImeSelector.scan(root,owner,label,description);AccessibilityNodeInfo found=scan.node;
            result.detail.append(' ').append(scan);
            if(found!=null){
                CharSequence value=description?found.getContentDescription():found.getText();
                if(window.getId()==id&&window.getType()==type&&found.getWindowId()==id&&owner.contentEquals(found.getPackageName()==null?"":found.getPackageName())&&value!=null&&label.contentEquals(value)&&found.isVisibleToUser()&&found.isEnabled()){
                    result.node=found;result.detail.append(" ACCEPTED] ");return result;
                }
                result.detail.append(" FINAL_WINDOW_OR_TARGET_GUARD_REJECTED");
            }
            result.detail.append("] ");
        }
        if(!sawType)result.detail.append("NO_REQUESTED_WINDOW");return result;
    }
}
