package androidx.core.content

import android.content.SharedPreferences

/** AndroidX's edit { } for the desktop's [SharedPreferences]. */
inline fun SharedPreferences.edit(commit: Boolean = false, action: SharedPreferences.Editor.() -> Unit) {
    val editor = edit()
    action(editor)
    if (commit) editor.commit() else editor.apply()
}
