package cn.yiiguxing.plugin.translate.trans

import cn.yiiguxing.plugin.translate.Settings
import cn.yiiguxing.plugin.translate.SettingsChangeListener
import cn.yiiguxing.plugin.translate.util.*
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.application.ModalityState
import com.intellij.openapi.components.ServiceManager
import com.intellij.openapi.diagnostic.Logger


/**
 * TranslateService
 *
 * Created by Yii.Guxing on 2017/10/30
 */
class TranslateService private constructor() {

    @Volatile
    var translator: Translator = DEFAULT_TRANSLATOR
    private val settings: Settings = Settings.instance
    private val cache = LruCache<CacheKey, Translation>(500)

    companion object {
        val DEFAULT_TRANSLATOR: Translator = GoogleTranslator

        val INSTANCE: TranslateService
            get() = ServiceManager.getService(TranslateService::class.java)

        private val LOGGER = Logger.getInstance(TranslateService::class.java)

        private fun checkThread() = checkDispatchThread(TranslateService::class.java)
    }

    init {
        setTranslator(settings.translator)
        ApplicationManager
                .getApplication()
                .messageBus
                .connect()
                .subscribe(SettingsChangeListener.TOPIC, object : SettingsChangeListener {
                    override fun onTranslatorChanged(settings: Settings, translatorId: String) {
                        setTranslator(translatorId)
                    }
                })
    }

    @Suppress("MemberVisibilityCanPrivate")
    fun setTranslator(translatorId: String) {
        checkThread()
        if (translatorId != translator.id) {
            translator = when (translatorId) {
                YoudaoTranslator.TRANSLATOR_ID -> YoudaoTranslator
                else -> DEFAULT_TRANSLATOR
            }
        }
    }

    fun getCache(text: String, srcLang: Lang, targetLang: Lang): Translation? {
        checkThread()
        return cache[CacheKey(text, srcLang, targetLang, translator.id)]
    }

    fun translate(text: String, srcLang: Lang, targetLang: Lang, listener: TranslateListener) {
        checkThread()

        cache[CacheKey(text, srcLang, targetLang, translator.id)]?.let {
            listener.onSuccess(it)
            return
        }

        executeOnPooledThread {
            try {
                with(translator) {
                    translate(text, srcLang, targetLang).let {
                        cache.put(CacheKey(text, srcLang, targetLang, id), it)
                        invokeLater(ModalityState.any()) { listener.onSuccess(it) }
                    }
                }
            } catch (e: TranslateException) {
                LOGGER.w("translate", e)
                invokeLater(ModalityState.any()) { listener.onError(e.message, e) }
            }
        }
    }

    @Deprecated("Will be deleted.")
    fun translate(text: String, callback: (String, YoudaoResult?) -> Unit) {
        checkThread()
        executeOnPooledThread {
            try {
                translator.translate(text, Lang.AUTO, Lang.AUTO)
                callback(text, YoudaoResult(errorCode = 0))
            } catch (e: Exception) {
                callback(text, YoudaoResult(errorCode = -1, message = e.message))
            }
        }
    }

}