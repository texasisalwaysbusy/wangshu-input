package org.wangshu.ime;

public final class LandscapeViewportPolicyChecks {
    public static void main(String[] args) {
        expect(360, 0, 96, 216);
        expect(240, 16, 96, 128);
        expect(200, 0, 96, 104);
        expect(160, 0, 96, 80);
        expect(720, 60, 192, 396);
        expect(160, 180, 96, 0);
        expect(201, 0, 96, 105);
        System.out.println("PASS seven landscape height budget cases");
    }

    private static void expect(int screenHeight, int systemInsets, int editorMinimum, int expected) {
        int actual = LandscapeKeyboardViewport.maximumHeight(screenHeight, systemInsets, editorMinimum);
        if (actual != expected) {
            throw new AssertionError("Height " + screenHeight + " with insets " + systemInsets
                    + " expected " + expected + " but was " + actual);
        }
    }
}
