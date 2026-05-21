package eu.darken.octi.desktop.ui.startup

import java.awt.BorderLayout
import java.awt.Color
import java.awt.GraphicsEnvironment
import java.awt.event.WindowAdapter
import java.awt.event.WindowEvent
import java.lang.reflect.InvocationTargetException
import javax.swing.BoxLayout
import javax.swing.JDialog
import javax.swing.JLabel
import javax.swing.JOptionPane
import javax.swing.JPanel
import javax.swing.JPasswordField
import javax.swing.JTextArea
import javax.swing.SwingUtilities
import javax.swing.UIManager
import javax.swing.border.EmptyBorder

internal object StartupPassphrasePrompt {

    fun show(errorMessage: String? = null): CharArray {
        if (GraphicsEnvironment.isHeadless()) {
            throw PassphrasePromptUnavailableException(
                "No graphical environment is available to ask for the passphrase.",
            )
        }

        var validationError = errorMessage
        while (true) {
            when (val result = promptOnce(validationError)) {
                PromptResult.Canceled -> throw PassphrasePromptCanceledException()
                is PromptResult.Submitted -> {
                    if (result.passphrase.isNotEmpty()) return result.passphrase
                    result.passphrase.fill('\u0000')
                    validationError = "Passphrase cannot be empty."
                }
            }
        }
    }

    private fun promptOnce(errorMessage: String?): PromptResult {
        if (SwingUtilities.isEventDispatchThread()) return showDialog(errorMessage)

        var result: PromptResult? = null
        try {
            SwingUtilities.invokeAndWait {
                result = showDialog(errorMessage)
            }
        } catch (e: InterruptedException) {
            Thread.currentThread().interrupt()
            throw PassphrasePromptUnavailableException("Passphrase prompt was interrupted.", e)
        } catch (e: InvocationTargetException) {
            val cause = e.targetException
            when (cause) {
                is RuntimeException -> throw cause
                is Error -> throw cause
                else -> throw PassphrasePromptUnavailableException("Could not show passphrase prompt.", cause)
            }
        }
        return result ?: throw PassphrasePromptUnavailableException("Passphrase prompt did not return a result.")
    }

    private fun showDialog(errorMessage: String?): PromptResult {
        val passwordField = JPasswordField(28)
        val content = JPanel().apply {
            layout = BoxLayout(this, BoxLayout.Y_AXIS)
            border = EmptyBorder(2, 2, 2, 2)
            add(messageText())
            errorMessage?.let { add(errorText(it)) }
            add(passwordPanel(passwordField))
        }

        val pane = JOptionPane(
            content,
            JOptionPane.WARNING_MESSAGE,
            JOptionPane.OK_CANCEL_OPTION,
        )
        val dialog = pane.createDialog(null, "Octi passphrase")
        dialog.defaultCloseOperation = JDialog.DISPOSE_ON_CLOSE
        dialog.addWindowFocusListener(object : WindowAdapter() {
            override fun windowGainedFocus(e: WindowEvent) {
                passwordField.requestFocusInWindow()
            }
        })

        dialog.isVisible = true
        dialog.dispose()

        return if (pane.value == JOptionPane.OK_OPTION) {
            val passphrase = passwordField.password
            passwordField.text = ""
            PromptResult.Submitted(passphrase)
        } else {
            PromptResult.Canceled
        }
    }

    private fun messageText() = JTextArea(
        "Octi could not use this computer's OS keystore. Enter a passphrase to encrypt " +
            "credentials in a local file.\n\n" +
            "Use the same passphrase every time Octi starts while this fallback is active.",
    ).apply {
        columns = 42
        lineWrap = true
        wrapStyleWord = true
        isEditable = false
        isFocusable = false
        isOpaque = false
        font = UIManager.getFont("Label.font")
        border = EmptyBorder(0, 0, 10, 0)
    }

    private fun errorText(message: String) = JLabel(message).apply {
        foreground = UIManager.getColor("Label.errorForeground") ?: Color(0xB00020)
        border = EmptyBorder(0, 0, 8, 0)
    }

    private fun passwordPanel(passwordField: JPasswordField) = JPanel(BorderLayout(0, 4)).apply {
        isOpaque = false
        add(JLabel("Passphrase"), BorderLayout.NORTH)
        add(passwordField, BorderLayout.CENTER)
    }

    private sealed interface PromptResult {
        data class Submitted(val passphrase: CharArray) : PromptResult
        data object Canceled : PromptResult
    }
}

internal class PassphrasePromptCanceledException : RuntimeException("Passphrase prompt canceled")

internal class PassphrasePromptUnavailableException(
    message: String,
    cause: Throwable? = null,
) : RuntimeException(message, cause)
