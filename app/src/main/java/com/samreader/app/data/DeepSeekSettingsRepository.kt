package com.samreader.app.data

import android.security.keystore.KeyGenParameterSpec
import android.security.keystore.KeyProperties
import android.util.Base64
import java.security.KeyStore
import javax.crypto.Cipher
import javax.crypto.KeyGenerator
import javax.crypto.SecretKey
import javax.crypto.spec.GCMParameterSpec
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*

enum class SpenButtonAction { ERASER, SENTENCE_NOTE, DISABLED }
data class DeepSeekSettings(
    val hasApiKey: Boolean,
    val model: String,
    val promptTemplate: String,
    val spenButtonAction: SpenButtonAction,
    val aiCorrectionEnabled: Boolean,
    val aiCorrectionMaxChangeRatio: Float,
)

class DeepSeekSettingsRepository(private val dao: SamReaderDao) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private val loaded = CompletableDeferred<Unit>()
    @Volatile private var values: Map<String, String> = emptyMap()
    private val _settings = MutableStateFlow(defaults(emptyMap()))
    val settings = _settings.asStateFlow()

    init {
        scope.launch {
            dao.deleteAppSettings(listOf("sentence.tap.action", "sentence.long.action"))
            dao.observeAppSettings().collect { rows ->
                values = rows.associate { it.key to it.value }
                _settings.value = defaults(values)
                if (!loaded.isCompleted) loaded.complete(Unit)
            }
        }
    }

    suspend fun save(
        apiKey: String,
        model: String,
        promptTemplate: String,
        action: SpenButtonAction,
        aiCorrectionEnabled: Boolean,
        aiCorrectionMaxChangeRatio: Float,
    ) {
        val cleanModel = model.trim(); val cleanPrompt = promptTemplate.trim()
        require(cleanModel.isNotEmpty()) { "模型名称不能为空" }
        require("{text}" in cleanPrompt) { "提示词必须包含 {text}" }
        val rows = mutableListOf(
            AppSettingEntity(MODEL, cleanModel),
            AppSettingEntity(PROMPT, cleanPrompt),
            AppSettingEntity(SPEN_ACTION, action.name),
            AppSettingEntity(AI_CORRECTION_ENABLED, aiCorrectionEnabled.toString()),
            AppSettingEntity(
                AI_CORRECTION_MAX_CHANGE_RATIO,
                aiCorrectionMaxChangeRatio.coerceIn(MIN_AI_CORRECTION_RATIO, MAX_AI_CORRECTION_RATIO).toString(),
            ),
        )
        if (apiKey.isNotBlank()) rows += encryptApiKey(apiKey.trim())
        dao.upsertAppSettings(rows)
    }

    suspend fun clearApiKey() = dao.deleteAppSettings(listOf(KEY_IV, KEY_CIPHERTEXT))

    internal suspend fun requireApiKey(): String {
        loaded.await()
        val iv = values[KEY_IV]; val encrypted = values[KEY_CIPHERTEXT]
        require(iv != null && encrypted != null) { "请先在设置中填写 DeepSeek API Key" }
        val cipher = Cipher.getInstance(TRANSFORMATION)
        cipher.init(Cipher.DECRYPT_MODE, secretKey(), GCMParameterSpec(128, Base64.decode(iv, Base64.NO_WRAP)))
        return String(cipher.doFinal(Base64.decode(encrypted, Base64.NO_WRAP)), Charsets.UTF_8)
    }

    internal suspend fun current(): DeepSeekSettings {
        loaded.await()
        return settings.value
    }

    private fun encryptApiKey(value: String): List<AppSettingEntity> {
        val cipher = Cipher.getInstance(TRANSFORMATION).apply { init(Cipher.ENCRYPT_MODE, secretKey()) }
        val encrypted = cipher.doFinal(value.toByteArray())
        return listOf(
            AppSettingEntity(KEY_IV, Base64.encodeToString(cipher.iv, Base64.NO_WRAP)),
            AppSettingEntity(KEY_CIPHERTEXT, Base64.encodeToString(encrypted, Base64.NO_WRAP)),
        )
    }

    private fun secretKey(): SecretKey {
        val store = KeyStore.getInstance("AndroidKeyStore").apply { load(null) }
        (store.getKey(KEY_ALIAS, null) as? SecretKey)?.let { return it }
        return KeyGenerator.getInstance(KeyProperties.KEY_ALGORITHM_AES, "AndroidKeyStore").run {
            init(KeyGenParameterSpec.Builder(KEY_ALIAS, KeyProperties.PURPOSE_ENCRYPT or KeyProperties.PURPOSE_DECRYPT)
                .setBlockModes(KeyProperties.BLOCK_MODE_GCM).setEncryptionPaddings(KeyProperties.ENCRYPTION_PADDING_NONE).setKeySize(256).build())
            generateKey()
        }
    }

    private fun defaults(map: Map<String, String>) = DeepSeekSettings(
        hasApiKey = KEY_CIPHERTEXT in map,
        model = map[MODEL] ?: DEFAULT_MODEL,
        promptTemplate = map[PROMPT] ?: DEFAULT_PROMPT,
        spenButtonAction = map[SPEN_ACTION]?.let { runCatching { SpenButtonAction.valueOf(it) }.getOrNull() } ?: SpenButtonAction.ERASER,
        aiCorrectionEnabled = map[AI_CORRECTION_ENABLED]?.toBooleanStrictOrNull() ?: true,
        aiCorrectionMaxChangeRatio = map[AI_CORRECTION_MAX_CHANGE_RATIO]?.toFloatOrNull()
            ?.coerceIn(MIN_AI_CORRECTION_RATIO, MAX_AI_CORRECTION_RATIO)
            ?: DEFAULT_AI_CORRECTION_RATIO,
    )

    companion object {
        const val DEFAULT_MODEL = "deepseek-v4-flash"
        const val DEFAULT_PROMPT = "结合论文上下文准确翻译下面目标句，统一专业术语并静默修正明显 OCR 错字，只输出译文：\n\n{text}"
        const val MIN_AI_CORRECTION_RATIO = .05f
        const val MAX_AI_CORRECTION_RATIO = 1f
        const val DEFAULT_AI_CORRECTION_RATIO = .25f
        private const val MODEL = "deepseek.model"; private const val PROMPT = "deepseek.prompt"; private const val SPEN_ACTION = "spen.action"
        private const val AI_CORRECTION_ENABLED = "deepseek.full_translation.correction.enabled"
        private const val AI_CORRECTION_MAX_CHANGE_RATIO = "deepseek.full_translation.correction.max_change_ratio"
        private const val KEY_ALIAS = "samreader.deepseek.api-key.v3"; private const val KEY_IV = "deepseek.key.iv"; private const val KEY_CIPHERTEXT = "deepseek.key.cipher"
        private const val TRANSFORMATION = "AES/GCM/NoPadding"
    }
}
