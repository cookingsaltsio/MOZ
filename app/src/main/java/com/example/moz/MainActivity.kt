package com.example.moz

import android.app.DownloadManager
import android.content.ContentValues
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.os.Environment
import android.provider.MediaStore
import android.speech.tts.TextToSpeech
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
    private var androidTts: TextToSpeech? = null

    @Volatile
    private var androidTtsReady: Boolean = false

    @Volatile
    private var androidTtsStatus: String = "initializing"

    private val fileChooserLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (fileUploadCallback == null) return@registerForActivityResult
        val results: Array<Uri>? = WebChromeClient.FileChooserParams.parseResult(result.resultCode, result.data)
        fileUploadCallback?.onReceiveValue(results)
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
            return speakWithAndroidTts(text, langTag, rate, pitch, volume)
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
            return speakWithAndroidTts(text, langTag, voiceName, rate, pitch, volume)
        }

        @JavascriptInterface
        fun stopAndroidTts(): Boolean {
            return stopAndroidTtsPlayback()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        val myWebView: WebView = findViewById(R.id.webview)
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

                val intent = Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
                    addCategory(Intent.CATEGORY_OPENABLE)
                    type = "*/*"
                    if (fileChooserParams?.mode == FileChooserParams.MODE_OPEN_MULTIPLE) {
                        putExtra(Intent.EXTRA_ALLOW_MULTIPLE, true)
                    }
                }

                try {
                    fileChooserLauncher.launch(intent)
                } catch (e: Exception) {
                    fileUploadCallback?.onReceiveValue(null)
                    fileUploadCallback = null
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
        androidTtsStatus = "initializing"
        androidTts = TextToSpeech(this) { status ->
            if (status == TextToSpeech.SUCCESS) {
                androidTtsReady = true
                androidTtsStatus = "ready"
            } else {
                androidTtsReady = false
                androidTtsStatus = "error"
            }
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
    ): Boolean {
        return speakWithAndroidTts(text, langTag, "", rate, pitch, volume)
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
    ): Boolean {
        val engine = androidTts ?: return false
        if (!androidTtsReady) return false

        val cleanText = text.trim().take(1000)
        if (cleanText.isBlank()) return false

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
                    return false
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
            val utteranceId = "moz-android-tts-${System.currentTimeMillis()}"
            engine.speak(cleanText, TextToSpeech.QUEUE_FLUSH, params, utteranceId) == TextToSpeech.SUCCESS
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }

    private fun stopAndroidTtsPlayback(): Boolean {
        return try {
            androidTts?.stop()
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
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
