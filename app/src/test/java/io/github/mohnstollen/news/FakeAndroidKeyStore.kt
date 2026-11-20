package io.github.mohnstollen.news

import android.security.keystore.KeyGenParameterSpec
import java.io.InputStream
import java.io.OutputStream
import java.security.Key
import java.security.KeyStoreSpi
import java.security.Provider
import java.security.SecureRandom
import java.security.Security
import java.security.cert.Certificate
import java.security.spec.AlgorithmParameterSpec
import java.util.Collections
import java.util.Date
import java.util.Enumeration
import javax.crypto.KeyGenerator
import javax.crypto.KeyGeneratorSpi
import javax.crypto.SecretKey

/**
 * Stands in for Android's hardware-backed key store, which Robolectric does not have: keys are
 * ordinary in-memory AES keys. Lets tests store the Nextcloud password the way the app does.
 */
class FakeAndroidKeyStore : @Suppress("DEPRECATION") Provider("AndroidKeyStore", 1.0, "Test double for the Android key store") {
    init {
        put("KeyStore.AndroidKeyStore", Store::class.java.name)
        put("KeyGenerator.AES", AesGenerator::class.java.name)
    }

    class Store : KeyStoreSpi() {
        override fun engineGetKey(alias: String, password: CharArray?): Key? = keys[alias]
        override fun engineGetCertificateChain(alias: String): Array<Certificate>? = null
        override fun engineGetCertificate(alias: String): Certificate? = null
        override fun engineGetCreationDate(alias: String): Date? = if (alias in keys) Date() else null
        override fun engineSetKeyEntry(alias: String, key: Key, password: CharArray?, chain: Array<out Certificate>?) {
            keys[alias] = key
        }
        override fun engineSetKeyEntry(alias: String, key: ByteArray, chain: Array<out Certificate>?) =
            throw UnsupportedOperationException()
        override fun engineSetCertificateEntry(alias: String, cert: Certificate) = throw UnsupportedOperationException()
        override fun engineDeleteEntry(alias: String) {
            keys.remove(alias)
        }
        override fun engineAliases(): Enumeration<String> = Collections.enumeration(keys.keys.toList())
        override fun engineContainsAlias(alias: String) = alias in keys
        override fun engineSize() = keys.size
        override fun engineIsKeyEntry(alias: String) = alias in keys
        override fun engineIsCertificateEntry(alias: String) = false
        override fun engineGetCertificateAlias(cert: Certificate): String? = null
        override fun engineStore(stream: OutputStream?, password: CharArray?) = Unit
        override fun engineLoad(stream: InputStream?, password: CharArray?) = Unit
    }

    class AesGenerator : KeyGeneratorSpi() {
        private var alias: String? = null

        override fun engineInit(random: SecureRandom?) = Unit
        override fun engineInit(params: AlgorithmParameterSpec?, random: SecureRandom?) {
            alias = (params as KeyGenParameterSpec).keystoreAlias
        }
        override fun engineInit(keysize: Int, random: SecureRandom?) = Unit
        override fun engineGenerateKey(): SecretKey {
            // The JDK's own AES generator; this provider only offers it under the Android name.
            val key = KeyGenerator.getInstance("AES", "SunJCE").apply { init(256) }.generateKey()
            alias?.let { keys[it] = key }
            return key
        }
    }

    companion object {
        private val keys = HashMap<String, Key>()

        fun install() {
            if (Security.getProvider("AndroidKeyStore") == null) Security.addProvider(FakeAndroidKeyStore())
        }
    }
}
