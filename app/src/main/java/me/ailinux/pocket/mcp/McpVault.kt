package me.ailinux.pocket.mcp

import android.content.Context
import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.nio.charset.StandardCharsets
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec

/**
 * Android hardware-backed Keystore where available. No plaintext credentials
 * are stored in SharedPreferences. App backup is disabled in AndroidManifest.
 */
class McpVault(context: Context) : McpTokenStore {
    private val file = File(context.filesDir, "mcp_vault_aes_gcm.bin")
    private val alias = "me.ailinux.pocket.mcp.keystore.v1"

    private fun secret(): SecretKey {
        val keystore = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (keystore.getKey(alias, null) as? SecretKey)?.let { return it }
        val gen = KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore")
        gen.init(KeyGenParameterSpec.Builder(
            alias, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT
        ).setBlockModes(KeyProperties.BLOCK_MODE_GCM)
            .setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE)
            .setKeySize(256)
            .setUserAuthenticationRequired(false)
            .build())
        return gen.generateKey()
    }

    @Synchronized
    fun list(): List<McpProfile> {
        if (!file.exists()) return emptyList()
        val array = JSONArray(decrypt(file))
        return (0 until array.length()).map { McpProfile.fromJson(array.getJSONObject(it)) }
    }

    @Synchronized
    override fun upsert(profile: McpProfile) {
        val records = list().toMutableList()
        val position = records.indexOfFirst { it.id == profile.id }
        if (position < 0) records.add(profile) else records[position] = profile
        save(records)
    }

    @Synchronized
    fun delete(id: String) {
        save(list().filterNot { it.id == id })
    }

    @Synchronized
    override fun get(id: String): McpProfile? = list().firstOrNull { it.id == id }

    @Synchronized
    override fun putPendingOAuth(pending: JSONObject) {
        writeEncrypted(
            File(file.parentFile, "mcp_oauth_pending.bin"),
            pending.toString().toByteArray(StandardCharsets.UTF_8)
        )
    }

    @Synchronized
    override fun pendingOAuth(): JSONObject? {
        val saved = File(file.parentFile, "mcp_oauth_pending.bin")
        if (!saved.exists()) return null
        return JSONObject(decrypt(saved))
    }

    @Synchronized
    override fun clearPendingOAuth() {
        File(file.parentFile, "mcp_oauth_pending.bin").delete()
    }

    @Synchronized
    private fun save(records: List<McpProfile>) {
        require(records.size <= 40) { "Maximal 40 MCP-Server" }
        val array = JSONArray()
        records.forEach { array.put(it.toJson()) }
        writeEncrypted(file, array.toString().toByteArray(StandardCharsets.UTF_8))
    }

    private fun decrypt(path: File): String {
        val bytes = path.readBytes()
        require(bytes.size > 16 && bytes.size < 1024 * 1024) { "MCP-Tresor beschädigt" }
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.DECRYPT_MODE, secret(), GCMParameterSpec(128, bytes.copyOfRange(0, 12)))
        return String(cipher.doFinal(bytes.copyOfRange(12, bytes.size)), StandardCharsets.UTF_8)
    }

    private fun writeEncrypted(path: File, raw: ByteArray) {
        val cipher = Cipher.getInstance("AES/GCM/NoPadding")
        cipher.init(Cipher.ENCRYPT_MODE, secret())
        val encrypted = cipher.iv + cipher.doFinal(raw)
        val temp = File(path.parentFile, path.name + ".new")
        temp.outputStream().use { os -> os.write(encrypted); os.flush() }
        if (!temp.renameTo(path)) {
            temp.delete()
            throw IllegalStateException("MCP-Tresor konnte nicht atomar gespeichert werden")
        }
    }
}
