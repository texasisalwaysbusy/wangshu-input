// Intermediate shrink-only scheduler; regression fixture, not the public baseline.
        final long[] viewportGeneration={0};
        scroll.addOnLayoutChangeListener((v,left,top,right,bottom,oldLeft,oldTop,oldRight,oldBottom)->{
            final long generation=++viewportGeneration[0];
            // A keyboard/viewport shrink has now actually been laid out.
            if(bottom-top>=oldBottom-oldTop||testEditor==null||!testEditor.hasFocus())return;
            final EditText editor=testEditor;
            scroll.post(()->{
                if(generation==viewportGeneration[0]&&!viewport.isLayoutRequested()&&!scroll.isLayoutRequested()&&scroll.isAttachedToWindow()
                        &&editor==testEditor&&editor.hasFocus()&&editor.hasWindowFocus())
                    editor.requestRectangleOnScreen(new android.graphics.Rect(0,0,editor.getWidth(),editor.getHeight()),true);
            });
        });
