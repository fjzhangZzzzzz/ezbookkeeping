package top.fjzhang233.books

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/** Only the opaque application session is stored. Passwords never reach persistent storage. */
class TokenStore(context: Context, preferenceName: String = "session", keyName: String = "session") {
    private val prefs = context.getSharedPreferences(preferenceName, Context.MODE_PRIVATE)
    private val alias = "family-books-$keyName-v1"
    private fun key(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(alias, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").apply {
            init(KeyGenParameterSpec.Builder(alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).build())
        }.generateKey()
    }
    fun read(): String? = try {
        val blob = prefs.getString("encrypted", null)?.let { Base64.decode(it, Base64.NO_WRAP) }
        if (blob == null) null else Cipher.getInstance("AES/GCM/NoPadding").run {
            init(Cipher.DECRYPT_MODE, key(), GCMParameterSpec(128, blob.copyOfRange(0, 12)))
            doFinal(blob.copyOfRange(12, blob.size)).toString(Charsets.UTF_8)
        }
    } catch (_: Exception) { clear(); null }
    fun write(token: String) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding").apply { init(Cipher.ENCRYPT_MODE, key()) }
        check(prefs.edit().putString("encrypted", Base64.encodeToString(cipher.iv + cipher.doFinal(token.toByteArray()), Base64.NO_WRAP)).commit())
    }
    fun clear() { prefs.edit().clear().commit() }
}
