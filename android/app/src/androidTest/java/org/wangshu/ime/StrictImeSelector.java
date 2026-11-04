package org.wangshu.ime;

import android.view.accessibility.AccessibilityNodeInfo;
import java.util.ArrayDeque;
import java.util.HashSet;

/** Test-only bounded traversal. Callers must still require TYPE_INPUT_METHOD. */
final class StrictImeSelector {
    static final int MAX_NODES=512,MAX_DEPTH=32,MAX_CHILDREN=128,MAX_CHILD_REQUESTS=1024;
    static final String OWNER="org.wangshu.ime";
    static final class Scan {
        AccessibilityNodeInfo node;
        int visited,enqueued,refreshCalls,staleMatches,rejectedWindow,nullChildren,childRequests;
        boolean limited;String outcome="NOT_FOUND";
        public String toString(){return "outcome="+outcome+" visited="+visited+" enqueued="+enqueued+" refreshCalls="+refreshCalls+" staleMatches="+staleMatches+" rejectedWindow="+rejectedWindow+" nullChildren="+nullChildren+" childRequests="+childRequests+" limited="+limited;}
    }
    private static final class Entry {
        final AccessibilityNodeInfo node;final int depth;
        Entry(AccessibilityNodeInfo node,int depth){this.node=node;this.depth=depth;}
    }
    static AccessibilityNodeInfo findOwned(AccessibilityNodeInfo root,String description){
        return scan(root,description).node;
    }
    private static boolean matches(AccessibilityNodeInfo node,String owner,String label,boolean description,int window){
        CharSequence value=description?node.getContentDescription():node.getText();
        return node.getWindowId()==window&&label.contentEquals(value==null?"":value)&&owner.contentEquals(node.getPackageName()==null?"":node.getPackageName())&&node.isVisibleToUser()&&node.isEnabled();
    }
    static Scan scan(AccessibilityNodeInfo root,String description){
        return scan(root,OWNER,description,true);
    }
    static Scan scan(AccessibilityNodeInfo root,String owner,String label,boolean description){
        Scan out=new Scan();if(root==null||owner==null||label==null||label.isEmpty()){out.outcome="NO_ROOT_OR_DESCRIPTION";return out;}
        // One ancestry refresh, then inspect the current prefetched snapshot. Refreshing
        // every container forces remote requests even when the requested leaf is absent.
        out.refreshCalls++;if(!root.refresh()){out.outcome="STALE_ROOT";return out;}
        int window=root.getWindowId();ArrayDeque<Entry> queue=new ArrayDeque<>();HashSet<AccessibilityNodeInfo> seen=new HashSet<>();
        queue.add(new Entry(root,0));seen.add(root);out.enqueued=1;
        while(!queue.isEmpty()){
            Entry entry=queue.removeFirst();AccessibilityNodeInfo node=entry.node;out.visited++;
            if(node.getWindowId()!=window){out.rejectedWindow++;continue;}
            if(matches(node,owner,label,description,window)){
                boolean fresh=node==root;if(!fresh){out.refreshCalls++;fresh=node.refresh();}
                // Cached text/package/visibility are never sufficient for an action.
                if(fresh&&matches(node,owner,label,description,window)){out.node=node;out.outcome="FOUND_REFRESHED";return out;}
                out.staleMatches++;
            }
            int children=node.getChildCount();if(children==0)continue;
            if(entry.depth>=MAX_DEPTH){out.limited=true;continue;}
            if(children>MAX_CHILDREN)out.limited=true;
            for(int i=0;i<Math.min(children,MAX_CHILDREN);i++){
                if(out.enqueued>=MAX_NODES||out.childRequests>=MAX_CHILD_REQUESTS){out.limited=true;break;}
                out.childRequests++;AccessibilityNodeInfo child=node.getChild(i);if(child==null){out.nullChildren++;continue;}
                if(seen.add(child)){queue.addLast(new Entry(child,entry.depth+1));out.enqueued++;}
            }
        }
        if(out.limited)out.outcome="INCOMPLETE_LIMIT";else if(out.nullChildren>0)out.outcome="INCOMPLETE_NULL_CHILDREN";return out;
    }
}
