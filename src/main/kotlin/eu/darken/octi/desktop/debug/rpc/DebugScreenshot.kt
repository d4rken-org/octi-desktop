package eu.darken.octi.desktop.debug.rpc

import java.awt.GraphicsEnvironment
import java.awt.Rectangle
import java.awt.Robot
import java.awt.Toolkit
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import javax.imageio.ImageIO

/**
 * Best-effort screenshot via AWT [Robot]. The desktop client doesn't track its own window
 * geometry in the graph (Compose owns it), so for MVP we capture the *whole primary screen*.
 * That's good enough to see what the app looks like in CI / a remote debug session and avoids
 * coupling this to Compose internals.
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
        val screenSize = Toolkit.getDefaultToolkit().screenSize
        val image: BufferedImage = Robot().createScreenCapture(Rectangle(screenSize))
        val baos = ByteArrayOutputStream()
        val wrote = ImageIO.write(image, "png", baos)
        if (!wrote) error("ImageIO had no writer for PNG")
        baos.toByteArray()
    }
}
