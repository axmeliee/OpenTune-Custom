package android.content;

import java.io.File;
import java.util.HashMap;
import java.util.Map;

/**
 * The part of Android's Context the shared code reads on the desktop: its
 * folders, its preferences and no system services. Everything lives under
 * one folder, ~/.opentune unless [root] is set.
 */
public class Context {
    public static final int MODE_PRIVATE = 0;

    private final File root;
    private final Map<String, SharedPreferences> prefs = new HashMap<>();

    public Context(File root) {
        this.root = root;
        root.mkdirs();
    }

    public File getFilesDir() {
        File f = new File(root, "files");
        f.mkdirs();
        return f;
    }

    public File getCacheDir() {
        File f = new File(root, "cache");
        f.mkdirs();
        return f;
    }

    public synchronized SharedPreferences getSharedPreferences(String name, int mode) {
        return prefs.computeIfAbsent(name, n -> new FilePreferences(new File(root, "prefs/" + n + ".properties")));
    }

    /** There are no Android system services here; callers take null as "not available". */
    public <T> T getSystemService(Class<T> type) {
        return null;
    }
}
