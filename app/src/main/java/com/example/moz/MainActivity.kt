package com.example.moz

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import android.util.Base64
import android.webkit.JavascriptInterface
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.Keep
import androidx.appcompat.app.AppCompatActivity
import org.json.JSONArray
import org.json.JSONObject
import java.io.OutputStream
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private var fileUploadCallback: ValueCallback<Array<Uri>>? = null
    private var pendingSaveOutputStream: OutputStream? = null
    private var pendingSaveUri: Uri? = null
    private var pendingSaveFileName: String? = null
    private lateinit var webView: WebView
    private var androidTts: TextToSpeech? = null
    private val androidTtsLock = Any()

    @Volatile
    private var androidTtsReady: Boolean = false

    @Volatile
    private var androidTtsStatus: String = "initializing"

    @Volatile
    private var lastAndroidTtsUtteranceId: String = ""

    @Volatile
    private var activeAndroidTtsUtteranceId: String = ""

    private var androidTtsRequestSeq: Long = 0
    private var androidTtsConsecutiveFailures: Int = 0

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (fileUploadCallback == null) {
            notifyFileChooserEvent("result-ignored", "callback is null", result.resultCode, result.data, null)
            return@registerForActivityResult
        }
        val parsedResults: Array<Uri>? = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        val manualResults: Array<Uri>? = extractChooserUris(result.data)
        val results: Array<Uri>? = parsedResults ?: manualResults
        notifyFileChooserEvent("result", "chooser returned", result.resultCode, result.data, results)
        takePersistableReadPermissions(result.data, results)
        fileUploadCallback?.onReceiveValue(results)
        notifyFileChooserEvent("delivered", "uris delivered to WebView", result.resultCode, result.data, results)
        fileUploadCallback = null
    }

    // JavaScriptから呼び出されるインターフェース
    @Keep
    inner class WebAppInterface {
        @JavascriptInterface
        fun saveFile(base64Data: String, fileName: String, mimeType: String) {
            saveBase64ToDownloads(base64Data, fileName, mimeType)
        }

        @JavascriptInterface
        fun beginSaveFile(fileName: String, mimeType: String): Boolean {
            return beginChunkedSave(fileName, mimeType)
        }

        @JavascriptInterface
        fun appendSaveFileChunk(base64Data: String): Boolean {
            return appendChunkedSave(base64Data)
        }

        @JavascriptInterface
        fun finishSaveFile(): Boolean {
            return finishChunkedSave()
        }

        @JavascriptInterface
        fun cancelSaveFile() {
            cancelChunkedSave()
        }

        @JavascriptInterface
        fun finishApp() {
            finish() // アプリを終了する
        }

        @JavascriptInterface
        fun getAndroidTtsStatus(): String {
            return androidTtsStatus
        }

        @JavascriptInterface
        fun getAndroidTtsVoices(): String {
            return getAndroidTtsVoicesJson()
        }

        @JavascriptInterface
        fun speakAndroidTts(
            text: String,
            langTag: String,
            rate: String,
            pitch: String,
            volume: String
        ): Boolean {
            return speakWithAndroidTts(text, langTag, rate, pitch, volume).isNotBlank()
        }

        @JavascriptInterface
        fun speakAndroidTtsVoice(
            text: String,
            langTag: String,
            voiceName: String,
            rate: String,
            pitch: String,
            volume: String
        ): Boolean {
            return speakWithAndroidTts(text, langTag, voiceName, rate, pitch, volume).isNotBlank()
        }

        @JavascriptInterface
        fun speakAndroidTtsRequest(
            text: String,
            langTag: String,
            rate: String,
            pitch: String,
            volume: String
        ): String {
            return speakWithAndroidTts(text, langTag, rate, pitch, volume)
        }

        @JavascriptInterface
        fun speakAndroidTtsVoiceRequest(
            text: String,
            langTag: String,
            voiceName: String,
            rate: String,
            pitch: String,
            volume: String
        ): String {
            return speakWithAndroidTts(text, langTag, voiceName, rate, pitch, volume)
        }

        @JavascriptInterface
        fun stopAndroidTts(): Boolean {
            return stopAndroidTtsPlayback()
        }

        @JavascriptInterface
        fun resetAndroidTts(reason: String): Boolean {
            return resetAndroidTtsEngine(reason)
        }
    }

    private fun extractChooserUris(data: Intent?): Array<Uri>? {
        if (data == null) return null
        val uris = LinkedHashSet<Uri>()
        data.data?.let { uris.add(it) }
        val clipData = data.clipData
        if (clipData != null) {
            for (i in 0 until clipData.itemCount) {
                clipData.getItemAt(i)?.uri?.let { uris.add(it) }
            }
        }
        return if (uris.isEmpty()) null else uris.toTypedArray()
    }

    private fun takePersistableReadPermissions(data: Intent?, uris: Array<Uri>?) {
        if (uris.isNullOrEmpty()) return
        val flags = data?.flags ?: 0
        if ((flags and Intent.FLAG_GRANT_READ_URI_PERMISSION) == 0) return
        uris.forEach { uri ->
            try {
                contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
            } catch (_: Exception) {
                // 一部のピッカーは永続化不可のURIを返すため、その場合はWebViewへの一時URI受け渡しだけにする。
            }
        }
    }

    private fun notifyFileChooserEvent(
        type: String,
        message: String,
        resultCode: Int? = null,
        data: Intent? = null,
        uris: Array<Uri>? = null,
        acceptTypes: Array<String>? = null,
        mode: Int? = null
    ) {
        if (!::webView.isInitialized) return
        val payload = JSONObject()
        payload.put("type", type)
        payload.put("message", message)
        if (resultCode != null) payload.put("resultCode", resultCode)
        if (mode != null) payload.put("mode", mode)
        if (acceptTypes != null) {
            payload.put("accept", acceptTypes.filter { it.isNotBlank() }.joinToString(",").ifBlank { "(empty)" })
        }
        if (data != null) {
            payload.put("clipCount", data.clipData?.itemCount ?: 0)
            payload.put("hasDataUri", data.data != null)
        }
        if (uris != null) payload.put("uriCount", uris.size)
        runOnUiThread {
            webView.evaluateJavascript(
                "window.onAndroidFileChooserEvent && window.onAndroidFileChooserEvent($payload);",
                null
            )
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        webView = findViewById(R.id.webview)
        val myWebView: WebView = webView
        val webSettings: WebSettings = myWebView.settings

        webSettings.javaScriptEnabled = true
        webSettings.domStorageEnabled = true
        webSettings.mediaPlaybackRequiresUserGesture = false
        webSettings.allowFileAccess = true
        webSettings.allowContentAccess = true

        initAndroidTts()

        myWebView.webViewClient = WebViewClient()

        // ファイル選択ダイアログの制御
        myWebView.webChromeClient = object : WebChromeClient() {
            override fun onShowFileChooser(
                webView: WebView?,
                filePathCallback: ValueCallback<Array<Uri>>?,
                fileChooserParams: FileChooserParams?
            ): Boolean {
                fileUploadCallback?.onReceiveValue(null)
                fileUploadCallback = filePathCallback
                notifyFileChooserEvent(
                    "open",
                    "onShowFileChooser called",
                    acceptTypes = fileChooserParams?.acceptTypes,
                    mode = fileChooserParams?.mode
                )

                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                }

                try {
                    fileChooserLauncher.launch(intent)
                    notifyFileChooserEvent("launched", "ACTION_OPEN_DOCUMENT launched")
                } catch (e: Exception) {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = null
                    notifyFileChooserEvent("launch-error", e.message ?: "file chooser launch failed")
                    return false
                }
                return true
            }
        }

        // JavaScriptインターフェースを登録
        myWebView.addJavascriptInterface(WebAppInterface(), "Android")

        myWebView.loadUrl("file:///android_asset/index.html")

        // 戻るボタン制御（スワイプバック対応）
        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                // HTML側の関数を呼び出して、画面遷移を制御させる
                myWebView.evaluateJavascript("if (typeof onAndroidBack === 'function') { onAndroidBack(); } else { 'not_found'; }") { result ->
                    // もしHTML側に関数がなければ（通常ありえないが）、デフォルトの挙動
                    if (result == "\"not_found\"") {
                        if (myWebView.canGoBack()) {
                            myWebView.goBack()
                        } else {
                            isEnabled = false
                            onBackPressedDispatcher.onBackPressed()
                        }
                    }
                }
            }
        })
    }

    private fun initAndroidTts() {
        synchronized(androidTtsLock) {
            androidTtsReady = false
            androidTtsStatus = "initializing"
            activeAndroidTtsUtteranceId = ""
            lastAndroidTtsUtteranceId = ""
        }

        var createdEngine: TextToSpeech? = null
        createdEngine = TextToSpeech(this) { status ->
            val engine = createdEngine
            var shouldInstallListener = false
            var shouldShutdownStaleEngine = false

            synchronized(androidTtsLock) {
                if (engine != null && androidTts !== engine) {
                    shouldShutdownStaleEngine = true
                } else if (status == TextToSpeech.SUCCESS && engine != null) {
                    androidTtsReady = true
                    androidTtsStatus = "ready"
                    androidTtsConsecutiveFailures = 0
                    shouldInstallListener = true
                } else {
                    androidTtsReady = false
                    androidTtsStatus = "error"
                }
            }

            if (shouldShutdownStaleEngine) {
                try {
                    engine?.shutdown()
                } catch (_: Exception) {
                }
                return@TextToSpeech
            }

            if (shouldInstallListener && engine != null) {
                installAndroidTtsListener(engine)
                notifyAndroidTtsEvent("ready", "")
            }
        }

        synchronized(androidTtsLock) {
            androidTts = createdEngine
        }
    }

    private fun parseLocale(langTag: String): Locale {
        val normalized = langTag.trim().replace('_', '-')
        if (normalized.isBlank()) return Locale.US
        val locale = Locale.forLanguageTag(normalized)
        return if (locale.language.isNullOrBlank()) Locale.US else locale
    }

    private fun speakWithAndroidTts(
        text: String,
        langTag: String,
        rate: String,
        pitch: String,
        volume: String
    ): String {
        return speakWithAndroidTts(text, langTag, "", rate, pitch, volume)
    }

    private fun resetAndroidTtsEngine(reason: String = "recovery"): Boolean {
        val oldEngine = synchronized(androidTtsLock) {
            val current = androidTts
            androidTts = null
            androidTtsReady = false
            androidTtsStatus = "initializing"
            activeAndroidTtsUtteranceId = ""
            lastAndroidTtsUtteranceId = ""
            androidTtsConsecutiveFailures = 0
            current
        }

        try {
            oldEngine?.stop()
        } catch (_: Exception) {
        }
        try {
            oldEngine?.shutdown()
        } catch (_: Exception) {
        }

        initAndroidTts()
        return true
    }

    private fun getAndroidTtsVoicesJson(): String {
        val engine = androidTts ?: return "[]"
        if (!androidTtsReady) return "[]"
        return try {
            val currentVoiceName = engine.voice?.name.orEmpty()
            val array = JSONArray()
            engine.voices
                ?.sortedWith(compareBy({ it.locale?.toLanguageTag().orEmpty() }, { it.name }))
                ?.forEach { voice ->
                    val locale = voice.locale
                    array.put(JSONObject().apply {
                        put("source", "android")
                        put("name", voice.name)
                        put("voiceURI", voice.name)
                        put("lang", locale?.toLanguageTag().orEmpty())
                        put("default", voice.name == currentVoiceName)
                        put("localService", !voice.isNetworkConnectionRequired)
                        put("networkRequired", voice.isNetworkConnectionRequired)
                        put("quality", voice.quality)
                        put("latency", voice.latency)
                    })
                }
            array.toString()
        } catch (e: Exception) {
            e.printStackTrace()
            "[]"
        }
    }

    private fun speakWithAndroidTts(
        text: String,
        langTag: String,
        voiceName: String,
        rate: String,
        pitch: String,
        volume: String
    ): String {
        val engine = synchronized(androidTtsLock) {
            if (!androidTtsReady) return ""
            androidTts ?: return ""
        }

        val cleanText = text.trim().take(1000)
        if (cleanText.isBlank()) return ""

        return try {
            val requestedLocale = parseLocale(langTag)
            val requestedVoice = engine.voices?.firstOrNull { it.name == voiceName }
            if (requestedVoice != null) {
                engine.voice = requestedVoice
            }

            val localeForCheck = requestedVoice?.locale ?: requestedLocale
            val localeToUse = if (requestedVoice != null) {
                localeForCheck
            } else {
                val requestedAvailability = engine.isLanguageAvailable(localeForCheck)
                val fallbackLocale = if (
                    requestedAvailability == TextToSpeech.LANG_MISSING_DATA ||
                    requestedAvailability == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    Locale.US
                } else {
                    localeForCheck
                }

                val fallbackAvailability = engine.isLanguageAvailable(fallbackLocale)
                if (
                    fallbackAvailability == TextToSpeech.LANG_MISSING_DATA ||
                    fallbackAvailability == TextToSpeech.LANG_NOT_SUPPORTED
                ) {
                    return ""
                }
                fallbackLocale
            }

            if (requestedVoice == null) {
                engine.language = localeToUse
            }
            engine.setSpeechRate((rate.toFloatOrNull() ?: 1.0f).coerceIn(0.1f, 3.0f))
            engine.setPitch((pitch.toFloatOrNull() ?: 1.0f).coerceIn(0.1f, 2.0f))
            engine.stop()

            val params = Bundle().apply {
                putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME, (volume.toFloatOrNull() ?: 1.0f).coerceIn(0.0f, 1.0f))
            }
            val utteranceId = synchronized(androidTtsLock) {
                androidTtsRequestSeq += 1
                "moz-android-tts-${System.currentTimeMillis()}-$androidTtsRequestSeq"
            }
            val result = engine.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, utteranceId)
            if (result == TextToSpeech.SUCCESS) {
                synchronized(androidTtsLock) {
                    lastAndroidTtsUtteranceId = utteranceId
                    activeAndroidTtsUtteranceId = utteranceId
                    androidTtsConsecutiveFailures = 0
                    androidTtsStatus = "ready"
                }
                utteranceId
            } else {
                noteAndroidTtsFailure(utteranceId)
                notifyAndroidTtsEvent("error", utteranceId)
                ""
            }
        } catch (e: Exception) {
            e.printStackTrace()
            noteAndroidTtsFailure("")
            ""
        }
    }

    private fun noteAndroidTtsFailure(utteranceId: String?) {
        val shouldReset = synchronized(androidTtsLock) {
            if (!utteranceId.isNullOrBlank() && activeAndroidTtsUtteranceId == utteranceId) {
                activeAndroidTtsUtteranceId = ""
            }
            androidTtsConsecutiveFailures += 1
            androidTtsConsecutiveFailures >= 3
        }
        if (shouldReset) {
            resetAndroidTtsEngine("native-failure")
        }
    }

    private fun stopAndroidTtsPlayback(): Boolean {
        return try {
            val stoppedId = synchronized(androidTtsLock) {
                val id = activeAndroidTtsUtteranceId.ifBlank { lastAndroidTtsUtteranceId }
                activeAndroidTtsUtteranceId = ""
                lastAndroidTtsUtteranceId = ""
                id
            }
            synchronized(androidTtsLock) { androidTts }?.stop()
            if (stoppedId.isNotBlank()) {
                notifyAndroidTtsEvent("stop", stoppedId)
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun installAndroidTtsListener(engine: TextToSpeech) {
        engine.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
            override fun onStart(utteranceId: String?) {
                notifyAndroidTtsEvent("start", utteranceId)
            }

            override fun onDone(utteranceId: String?) {
                synchronized(androidTtsLock) {
                    if (!utteranceId.isNullOrBlank() && activeAndroidTtsUtteranceId == utteranceId) {
                        activeAndroidTtsUtteranceId = ""
                    }
                    if (!utteranceId.isNullOrBlank() && lastAndroidTtsUtteranceId == utteranceId) {
                        lastAndroidTtsUtteranceId = ""
                    }
                    androidTtsConsecutiveFailures = 0
                }
                notifyAndroidTtsEvent("done", utteranceId)
            }

            @Deprecated("Deprecated in Java")
            override fun onError(utteranceId: String?) {
                noteAndroidTtsFailure(utteranceId)
                notifyAndroidTtsEvent("error", utteranceId)
            }

            override fun onError(utteranceId: String?, errorCode: Int) {
                noteAndroidTtsFailure(utteranceId)
                notifyAndroidTtsEvent("error", utteranceId, errorCode)
            }

            override fun onStop(utteranceId: String?, interrupted: Boolean) {
                synchronized(androidTtsLock) {
                    if (!utteranceId.isNullOrBlank() && activeAndroidTtsUtteranceId == utteranceId) {
                        activeAndroidTtsUtteranceId = ""
                    }
                    if (!utteranceId.isNullOrBlank() && lastAndroidTtsUtteranceId == utteranceId) {
                        lastAndroidTtsUtteranceId = ""
                    }
                }
                notifyAndroidTtsEvent("stop", utteranceId)
            }
        })
    }

    private fun notifyAndroidTtsEvent(type: String, utteranceId: String?, errorCode: Int? = null) {
        val payload = JSONObject()
        payload.put("type", type)
        payload.put("utteranceId", utteranceId ?: "")
        if (errorCode != null) payload.put("errorCode", errorCode)
        runOnUiThread {
            if (::webView.isInitialized) {
                webView.evaluateJavascript("window.MOZAndroidTts && window.MOZAndroidTts.onEvent($payload);", null)
            }
        }
    }

    private fun beginChunkedSave(fileName: String, mimeType: String): Boolean {
        return try {
            cancelChunkedSave()

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            val resolver = applicationContext.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                ?: throw Exception("URI is null")
            val outputStream = resolver.openOutputStream(uri)
                ?: throw Exception("OutputStream is null")

            pendingSaveUri = uri
            pendingSaveOutputStream = outputStream
            pendingSaveFileName = fileName
            true
        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread {
                Toast.makeText(this, "保存開始失敗: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            false
        }
    }

    private fun appendChunkedSave(base64Data: String): Boolean {
        return try {
            val outputStream = pendingSaveOutputStream ?: throw Exception("保存先が開かれていません")
            val cleanBase64 = if (base64Data.contains(",")) {
                base64Data.substring(base64Data.indexOf(",") + 1)
            } else {
                base64Data
            }
            val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)
            outputStream.write(bytes)
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun finishChunkedSave(): Boolean {
        return try {
            pendingSaveOutputStream?.flush()
            pendingSaveOutputStream?.close()
            val fileName = pendingSaveFileName ?: "backup.json"
            pendingSaveOutputStream = null
            pendingSaveUri = null
            pendingSaveFileName = null
            runOnUiThread {
                Toast.makeText(this, "保存しました: $fileName", Toast.LENGTH_LONG).show()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread {
                Toast.makeText(this, "保存完了失敗: ${e.message}", Toast.LENGTH_SHORT).show()
            }
            false
        }
    }

    private fun cancelChunkedSave() {
        try {
            pendingSaveOutputStream?.close()
        } catch (_: Exception) {
        }
        val uri = pendingSaveUri
        if (uri != null) {
            try {
                applicationContext.contentResolver.delete(uri, null, null)
            } catch (_: Exception) {
            }
        }
        pendingSaveOutputStream = null
        pendingSaveUri = null
        pendingSaveFileName = null
    }

    // MediaStoreを使ってファイルを保存する
    private fun saveBase64ToDownloads(base64Data: String, fileName: String, mimeType: String) {
        try {
            val cleanBase64 = if (base64Data.contains(",")) {
                base64Data.substring(base64Data.indexOf(",") + 1)
            } else {
                base64Data
            }
            val bytes = Base64.decode(cleanBase64, Base64.DEFAULT)

            val contentValues = ContentValues().apply {
                put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                put(MediaStore.MediaColumns.MIME_TYPE, mimeType)
                put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS)
            }

            val resolver = applicationContext.contentResolver
            val uri = resolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)

            if (uri != null) {
                resolver.openOutputStream(uri).use { outputStream ->
                    outputStream?.write(bytes)
                }
                runOnUiThread {
                    Toast.makeText(this, "保存しました: $fileName", Toast.LENGTH_LONG).show()
                }
            } else {
                throw Exception("URI is null")
            }

        } catch (e: Exception) {
            e.printStackTrace()
            runOnUiThread {
                Toast.makeText(this, "保存失敗: ${e.message}", Toast.LENGTH_SHORT).show()
            }
        }
    }

    override fun onDestroy() {
        androidTts?.stop()
        androidTts?.shutdown()
        androidTts = null
        super.onDestroy()
    }
}
