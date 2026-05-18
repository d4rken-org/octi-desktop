package eu.darken.octi.desktop.debug.rpc

import io.kotest.matchers.shouldBe
import org.junit.jupiter.api.Test
import org.junit.jupiter.api.assertThrows

class DebugRpcConfigTest {

    @Test
    fun `no flags returns null config and empty leftover`() {
        val parsed = DebugRpcConfig.parse(emptyArray())
        parsed.config shouldBe null
        parsed.remainingArgs.toList() shouldBe emptyList()
    }

    @Test
    fun `enable flag alone produces enabled config with no explicit port`() {
        val parsed = DebugRpcConfig.parse(arrayOf("--enable-debug-rpc"))
        parsed.config shouldBe DebugRpcConfig(enabled = true, explicitPort = null)
        parsed.remainingArgs.toList() shouldBe emptyList()
    }

    @Test
    fun `enable plus port parses the integer`() {
        val parsed = DebugRpcConfig.parse(arrayOf("--enable-debug-rpc", "--debug-rpc-port", "53123"))
        parsed.config shouldBe DebugRpcConfig(enabled = true, explicitPort = 53123)
    }

    @Test
    fun `unknown flags pass through untouched`() {
        val parsed = DebugRpcConfig.parse(arrayOf("--enable-debug-rpc", "--something-else", "value"))
        parsed.config?.enabled shouldBe true
        parsed.remainingArgs.toList() shouldBe listOf("--something-else", "value")
    }

    @Test
    fun `port without value is rejected`() {
        val ex = assertThrows<IllegalArgumentException> {
            DebugRpcConfig.parse(arrayOf("--enable-debug-rpc", "--debug-rpc-port"))
        }
        ex.message!!.contains("requires a value") shouldBe true
    }

    @Test
    fun `non-numeric port is rejected`() {
        val ex = assertThrows<IllegalArgumentException> {
            DebugRpcConfig.parse(arrayOf("--enable-debug-rpc", "--debug-rpc-port", "abc"))
        }
        ex.message!!.contains("must be an integer") shouldBe true
    }

    @Test
    fun `port zero is rejected`() {
        assertThrows<IllegalArgumentException> {
            DebugRpcConfig.parse(arrayOf("--enable-debug-rpc", "--debug-rpc-port", "0"))
        }
    }

    @Test
    fun `port above 65535 is rejected`() {
        assertThrows<IllegalArgumentException> {
            DebugRpcConfig.parse(arrayOf("--enable-debug-rpc", "--debug-rpc-port", "65536"))
        }
    }

    @Test
    fun `negative port is rejected`() {
        assertThrows<IllegalArgumentException> {
            DebugRpcConfig.parse(arrayOf("--enable-debug-rpc", "--debug-rpc-port", "-1"))
        }
    }

    @Test
    fun `duplicate port flag is rejected`() {
        assertThrows<IllegalArgumentException> {
            DebugRpcConfig.parse(
                arrayOf(
                    "--enable-debug-rpc",
                    "--debug-rpc-port", "1234",
                    "--debug-rpc-port", "5678",
                ),
            )
        }
    }

    @Test
    fun `port without enable is rejected`() {
        val ex = assertThrows<IllegalArgumentException> {
            DebugRpcConfig.parse(arrayOf("--debug-rpc-port", "1234"))
        }
        ex.message!!.contains("meaningless without") shouldBe true
    }
}
