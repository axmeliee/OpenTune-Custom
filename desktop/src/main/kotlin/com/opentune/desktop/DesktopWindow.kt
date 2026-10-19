package com.opentune.desktop

import androidx.compose.ui.unit.dp
import javax.swing.JFrame

/**
 * The window's frame. On a Mac the title bar goes clear and the app runs
 * under it, as Music and other Mac apps do, with the system's own close,
 * minimise and zoom buttons over the sidebar. Windows and Linux keep their
 * usual title bar.
 */
object DesktopWindow {
    val isMac = System.getProperty("os.name").orEmpty().lowercase().startsWith("mac")

    /** Room above the app's content for the Mac's window buttons. */
    val topInset = if (isMac) 28.dp else 0.dp

    fun style(window: JFrame) {
        if (!isMac) return
        window.rootPane.putClientProperty("apple.awt.fullWindowContent", true)
        window.rootPane.putClientProperty("apple.awt.transparentTitleBar", true)
        window.rootPane.putClientProperty("apple.awt.windowTitleVisible", false)
    }
}
