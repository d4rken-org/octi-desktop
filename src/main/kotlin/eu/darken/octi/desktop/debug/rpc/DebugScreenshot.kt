package eu.darken.octi.desktop.debug.rpc

import java.awt.Frame
import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Best-effort screenshot via AWT [Robot].
 *
 * Prefers the bounds of the active Compose window (looked up via [Frame.getFrames]) so the
 * captured image is exactly the app surface — no surrounding desktop / WM chrome / Xvfb
 * wallpaper. Falls back to the whole primary screen when no visible frame is found (early
 * startup, headed-but-minimized, or atypical multi-window setups).
 *
 * Returns a [Result] so the HTTP route can map a failure (headless host, AWT denied, no display)
 * to `503 screenshot_unavailable` rather than emitting blank-or-garbage bytes that look like a
 * successful 200 to automation.
 */
object DebugScreenshot {

    fun capture(): Result<ByteArray> = runCatching {
        if (GraphicsEnvironment.isHeadless()) {
            error("Headless JVM — no display to capture")
        }
        val bounds = activeWindowBounds() ?: Rectangle(Toolkit.getDefaultToolkit().screenSize)
        val image: BufferedImage = Robot().createScreenCapture(bounds)
        val baos = ByteArrayOutputStream()
        val wrote = ImageIO.write(image, "png", baos)
        if (!wrote) error("ImageIO had no writer for PNG")
        baos.toByteArray()
    }

    /**
     * Bounds of the first visible+showing AWT [Frame]. Compose Desktop's `Window {}` is backed
     * by a single Frame, so this is unambiguous for our single-window app. A non-empty width
     * and height guards against the brief slot between Frame creation and layout where bounds
     * are (0,0)-sized and capturing them would produce a 1x1 PNG.
     */
    private fun activeWindowBounds(): Rectangle? = Frame.getFrames()
        .firstOrNull { it.isVisible && it.isShowing && it.width > 0 && it.height > 0 }
        ?.bounds
}
