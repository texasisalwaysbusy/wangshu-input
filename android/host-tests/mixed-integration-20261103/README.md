# Mixed composition and prediction integration host checks

Run `python3 run.py PROJECT NEW_OUTPUT_DIRECTORY` with an existing JDK, Python 3, and g++. The directory must not exist. No download, installation, device action, or external write occurs. All generated sources, outputs, and source/method hashes are retained in the requested output.

The runners test actual production Java/C++ method bodies or the complete prediction controller with explicit synthetic Android/TSF boundaries. They do not establish Android API resolution, JNI linkage, OS callback semantics, Windows ABI, native builds, real editor behavior, or device performance.

Cases cover privacy/session invalidation before reads, reentry while hiding suggestions or looking up the editor, marker-free document composition, metadata-only opening/closing/backspace/finalize/cancel, literal symbols and exact case, existing input guards, bound commits, and deferred Windows candidate fencing.
