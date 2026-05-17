package eu.darken.octi.desktop.linking

import eu.darken.octi.desktop.protocol.collections.toGzip
import eu.darken.octi.desktop.protocol.encryption.PayloadEncryption
import eu.darken.octi.desktop.protocol.octiserver.DeviceMetadata
import eu.darken.octi.desktop.protocol.octiserver.LinkingData
import eu.darken.octi.desktop.protocol.octiserver.OctiServer
import eu.darken.octi.desktop.protocol.octiserver.OctiServerHttpClient
import eu.darken.octi.desktop.protocol.octiserver.OctiServerHttpException
import eu.darken.octi.desktop.protocol.octiserver.dto.RegisterResponse
import eu.darken.octi.desktop.protocol.serialization.Serialization
import eu.darken.octi.desktop.protocol.sync.DeviceId
import eu.darken.octi.desktop.storage.keystore.Keystore
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.ktor.http.HttpStatusCode
import io.mockk.coEvery
import io.mockk.coVerify
import io.mockk.every
import io.mockk.justRun
import io.mockk.mockk
import io.mockk.verify
import kotlinx.coroutines.test.runTest
import okio.ByteString
import okio.ByteString.Companion.encodeUtf8
import okio.ByteString.Companion.toByteString
import org.junit.jupiter.api.DisplayName
import org.junit.jupiter.api.Test
import java.util.concurrent.atomic.AtomicInteger

class LinkControllerTest {

    private val json = Serialization.json
    private val deviceMetadata = DeviceMetadata(
        version = "0.0.1-test",
        platform = "desktop-test",
        label = "test-host",
    )
    private val newDeviceId = DeviceId("11111111-2222-3333-4444-555555555555")

    /** Factory that throws if invoked. Use when a test must prove the network was never touched. */
    private val rejectingFactory = LinkController.HttpClientFactory { _, _, _, _ ->
        error("HttpClientFactory must not be invoked when local validation fails")
    }

    private fun newKeystore(): Keystore = object : Keystore {
        private val storage = mutableMapOf<String, ByteArray>()
        override val backendDescription: String = "test"
        override fun store(key: String, value: ByteArray) {
            storage[key] = value
        }
        override fun load(key: String): ByteArray? = storage[key]
        override fun delete(key: String) {
            storage.remove(key)
        }
    }

    /** Build a real LinkingData with a real Tink keyset and encode it. */
    private fun validEncodedLink(): String {
        val keyset = PayloadEncryption().exportKeyset()
        return LinkingData(
            serverAdress = OctiServer.Address(domain = "test.example.com"),
            linkCode = OctiServer.Credentials.LinkCode("share-abc"),
            encryptionKeyset = keyset,
        ).toEncodedString(json)
    }

    // --- Local validation (factory never invoked) ---

    @Test
    @DisplayName("Empty input → InvalidBase64, factory never invoked")
    fun emptyInput() = runTest {
        val controller = LinkController(deviceMetadata, CredentialsStore(newKeystore()), rejectingFactory)
        controller.link("", newDeviceId) shouldBe LinkResult.InvalidBase64
    }

    @Test
    @DisplayName("Non-base64 garbage → InvalidBase64, factory never invoked")
    fun invalidBase64() = runTest {
        val controller = LinkController(deviceMetadata, CredentialsStore(newKeystore()), rejectingFactory)
        controller.link("%%% not base64 %%%", newDeviceId) shouldBe LinkResult.InvalidBase64
    }

    @Test
    @DisplayName("Valid base64, bad gzip stream → InvalidGzip, factory never invoked")
    fun invalidGzip() = runTest {
        val controller = LinkController(deviceMetadata, CredentialsStore(newKeystore()), rejectingFactory)
        // Plain ASCII, valid base64, definitely not a gzip stream.
        val encoded = "hello world".encodeUtf8().base64()
        controller.link(encoded, newDeviceId) shouldBe LinkResult.InvalidGzip
    }

    @Test
    @DisplayName("Valid base64+gzip but wrong JSON shape → InvalidJson, factory never invoked")
    fun invalidJsonShape() = runTest {
        val controller = LinkController(deviceMetadata, CredentialsStore(newKeystore()), rejectingFactory)
        val gzipped = """{"unrelated": "shape"}""".encodeUtf8().toGzip().base64()
        val result = controller.link(gzipped, newDeviceId)
        result.shouldBeInstanceOf<LinkResult.InvalidJson>()
    }

    @Test
    @DisplayName("Valid LinkingData JSON but Tink rejects the keyset bytes → InvalidKeyset, factory never invoked")
    fun invalidKeyset() = runTest {
        val controller = LinkController(deviceMetadata, CredentialsStore(newKeystore()), rejectingFactory)
        // Build a LinkingData whose keyset bytes are garbage; the JSON shape is valid so we get
        // past gzip+JSON but Tink's parseKeyset throws.
        val badKeyset = PayloadEncryption.KeySet(
            type = "AES256_GCM_SIV",
            key = "definitely-not-a-real-tink-keyset".encodeUtf8(),
        )
        val link = LinkingData(
            serverAdress = OctiServer.Address(domain = "test.example.com"),
            linkCode = OctiServer.Credentials.LinkCode("share-abc"),
            encryptionKeyset = badKeyset,
        )
        val encoded = link.toEncodedString(json)
        val result = controller.link(encoded, newDeviceId)
        result.shouldBeInstanceOf<LinkResult.InvalidKeyset>()
    }

    // --- Server-stage paths (factory is invoked) ---

    @Test
    @DisplayName("Happy path: server returns credentials → save → Success")
    fun happyPath() = runTest {
        val client = mockk<OctiServerHttpClient>(relaxed = true)
        coEvery { client.register(shareCode = any()) } returns RegisterResponse(
            accountID = "acct-123",
            password = "pw-shhh",
        )

        val keystore = newKeystore()
        val store = CredentialsStore(keystore)
        val factory = LinkController.HttpClientFactory { _, _, _, _ -> client }
        val controller = LinkController(deviceMetadata, store, factory)

        val result = controller.link(validEncodedLink(), newDeviceId)
        result shouldBe LinkResult.Success
        // Sanity: credentials really landed in the store.
        val loaded = store.load()
        check(loaded != null) { "credentials must be persisted" }
        loaded.accountId.id shouldBe "acct-123"
        loaded.devicePassword.password shouldBe "pw-shhh"
        coVerify { client.register(shareCode = "share-abc") }
    }

    @Test
    @DisplayName("Expired share code (404) → ShareCodeExpiredOrConsumed, no credentials saved")
    fun expiredShareCode() = runTest {
        val client = mockk<OctiServerHttpClient>(relaxed = true)
        coEvery { client.register(shareCode = any()) } throws OctiServerHttpException(HttpStatusCode.NotFound, "test")

        val keystore = newKeystore()
        val store = CredentialsStore(keystore)
        val factory = LinkController.HttpClientFactory { _, _, _, _ -> client }
        val controller = LinkController(deviceMetadata, store, factory)

        controller.link(validEncodedLink(), newDeviceId) shouldBe LinkResult.ShareCodeExpiredOrConsumed
        check(store.load() == null) { "no credentials should be saved on expired share code" }
    }

    @Test
    @DisplayName("Keystore write fails → rollback DELETE called → KeystoreFailureRolledBack")
    fun keystoreFailureTriggersRollback() = runTest {
        val unauthedClient = mockk<OctiServerHttpClient>(relaxed = true)
        coEvery { unauthedClient.register(shareCode = any()) } returns RegisterResponse(
            accountID = "acct-456",
            password = "pw-789",
        )

        val authedClient = mockk<OctiServerHttpClient>(relaxed = true)
        justRun { authedClient.close() }
        coEvery { authedClient.deleteDevice(any()) } returns Unit

        val failingKeystore = object : Keystore {
            override val backendDescription = "test-fail"
            override fun store(key: String, value: ByteArray) = throw RuntimeException("disk full")
            override fun load(key: String): ByteArray? = null
            override fun delete(key: String) = Unit
        }

        // First factory invocation = unauthed (register), second = authed (rollback DELETE).
        val callIndex = AtomicInteger(0)
        val factory = LinkController.HttpClientFactory { _, _, _, credentials ->
            when (callIndex.getAndIncrement()) {
                0 -> {
                    check(credentials == null) { "first factory call must be unauthed" }
                    unauthedClient
                }
                1 -> {
                    check(credentials != null) { "rollback factory call must carry credentials" }
                    credentials.accountId.id shouldBe "acct-456"
                    authedClient
                }
                else -> error("factory called more than twice")
            }
        }

        val controller = LinkController(deviceMetadata, CredentialsStore(failingKeystore), factory)
        val result = controller.link(validEncodedLink(), newDeviceId)
        result.shouldBeInstanceOf<LinkResult.KeystoreFailureRolledBack>()
        coVerify(exactly = 1) { authedClient.deleteDevice(newDeviceId) }
        callIndex.get() shouldBe 2
    }

    @Test
    @DisplayName("Keystore fails AND rollback DELETE also fails → OrphanedDevice")
    fun keystoreFailureRollbackAlsoFails() = runTest {
        val unauthedClient = mockk<OctiServerHttpClient>(relaxed = true)
        coEvery { unauthedClient.register(shareCode = any()) } returns RegisterResponse(
            accountID = "acct-xyz",
            password = "pw-xyz",
        )

        val authedClient = mockk<OctiServerHttpClient>(relaxed = true)
        coEvery { authedClient.deleteDevice(any()) } throws RuntimeException("server unreachable")

        val failingKeystore = object : Keystore {
            override val backendDescription = "test-fail"
            override fun store(key: String, value: ByteArray) = throw RuntimeException("permission denied")
            override fun load(key: String): ByteArray? = null
            override fun delete(key: String) = Unit
        }

        val callIndex = AtomicInteger(0)
        val factory = LinkController.HttpClientFactory { _, _, _, credentials ->
            if (callIndex.getAndIncrement() == 0) unauthedClient else authedClient
        }

        val controller = LinkController(deviceMetadata, CredentialsStore(failingKeystore), factory)
        val result = controller.link(validEncodedLink(), newDeviceId)
        result.shouldBeInstanceOf<LinkResult.OrphanedDevice>()
    }
}
