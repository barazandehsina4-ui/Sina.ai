package com.sinaai.app

import android.Manifest
import android.app.Activity
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.BitmapFactory
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.provider.Settings
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.speech.tts.TextToSpeech
import android.view.Gravity
import android.view.View
import android.view.inputmethod.InputMethodManager
import android.content.Context
import android.app.AlertDialog
import android.widget.VideoView
import android.widget.*
import org.json.JSONArray
import org.json.JSONObject
import java.net.HttpURLConnection
import java.net.URL
import java.util.Locale
import kotlin.concurrent.thread

class MainActivity : Activity(), TextToSpeech.OnInitListener {
    private lateinit var messages: LinearLayout
    private lateinit var input: EditText
    private lateinit var scroll: ScrollView
    private var tts: TextToSpeech? = null
    private var speech: SpeechRecognizer? = null
    private val prefs by lazy { getSharedPreferences("sina_settings", MODE_PRIVATE) }
    private val history = JSONArray()
    private val bg = Color.rgb(8, 15, 32)
    private var wakeEnabled = false

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        tts = TextToSpeech(this, this)
        buildUi()
        addMessage("سلام! من سینا هستم. می‌توانی تایپ کنی، با میکروفون صحبت کنی، حالت آفلاین پایه را به‌کار ببری یا درخواست تصویر و ویدئو بدهی.", false)
        if (intent.getBooleanExtra("wake_detected", false)) { addMessage("جانم! صدات رو شنیدم.", false); speak("جانم، گوش می‌دهم") }
    }

    private fun buildUi() {
        val root = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(16, 18, 16, 12); setBackgroundColor(bg) }
        val header = LinearLayout(this).apply { gravity = Gravity.CENTER_VERTICAL; orientation = LinearLayout.HORIZONTAL }
        val title = TextView(this).apply { text = "SINA AI"; textSize = 25f; setTextColor(Color.WHITE); setPadding(4, 8, 8, 8) }
        val settings = Button(this).apply { text = "تنظیمات"; setOnClickListener { showSettings() } }
        header.addView(title, LinearLayout.LayoutParams(0, -2, 1f)); header.addView(settings)
        root.addView(header)
        val hint = TextView(this).apply { text = "دستیار فارسی • گفت‌وگو، صدا، فرمان‌های گوشی و تصویر"; setTextColor(Color.LTGRAY); textSize = 13f; gravity = Gravity.CENTER; setPadding(0, 0, 0, 10) }
        root.addView(hint)
        scroll = ScrollView(this)
        messages = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(2, 4, 2, 8) }
        scroll.addView(messages)
        root.addView(scroll, LinearLayout.LayoutParams(-1, 0, 1f))
        input = EditText(this).apply { hint = "پیامت را بنویس…"; textSize = 16f; setTextColor(Color.WHITE); setHintTextColor(Color.LTGRAY); setSingleLine(false); maxLines = 3 }
        root.addView(input)
        val row = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL; gravity = Gravity.CENTER }
        row.addView(Button(this).apply { text = "ارسال"; setOnClickListener { sendText() } }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply { text = "🎙 صحبت"; setOnClickListener { startListening() } }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply { text = "🎨 تصویر"; setOnClickListener { val p = input.text.toString().trim(); if (p.isNotEmpty()) generateImage(p) else addMessage("اول توضیح تصویر را در کادر بنویس.", false) } }, LinearLayout.LayoutParams(0, -2, 1f))
        row.addView(Button(this).apply { text = "🎬 ویدئو"; setOnClickListener { val p = input.text.toString().trim(); if (p.isNotEmpty()) generateVideo(p) else addMessage("اول توضیح ویدئو را در کادر بنویس.", false) } }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(row)
        val commands = Button(this).apply { text = "فرمان‌های گوشی"; setOnClickListener { showCommands() } }
        root.addView(commands)
        val extras = LinearLayout(this).apply { orientation = LinearLayout.HORIZONTAL }
        extras.addView(Button(this).apply { text = "🎧 فعال‌سازی بیدارباش"; setOnClickListener { startWakeListener() } }, LinearLayout.LayoutParams(0, -2, 1f))
        extras.addView(Button(this).apply { text = "⏹ توقف بیدارباش"; setOnClickListener { stopService(Intent(this@MainActivity, WakeWordService::class.java)); addMessage("گوش‌به‌زنگ خاموش شد.", false) } }, LinearLayout.LayoutParams(0, -2, 1f))
        root.addView(extras)
        setContentView(root)
    }

    private fun addMessage(text: String, user: Boolean) {
        val bubble = TextView(this).apply {
            this.text = (if (user) "شما: " else "سینا: ") + text
            textSize = 15f; setTextColor(Color.WHITE); setPadding(14, 12, 14, 12)
            setBackgroundColor(if (user) Color.rgb(28, 74, 112) else Color.rgb(35, 43, 62))
        }
        val lp = LinearLayout.LayoutParams(-1, -2).apply { setMargins(0, 5, 0, 5) }
        messages.addView(bubble, lp); scroll.post { scroll.fullScroll(View.FOCUS_DOWN) }
    }

    private fun sendText() {
        val text = input.text.toString().trim(); if (text.isEmpty()) return
        input.setText(""); (getSystemService(Context.INPUT_METHOD_SERVICE) as InputMethodManager).hideSoftInputFromWindow(input.windowToken, 0)
        addMessage(text, true)
        val command = runLocalCommand(text)
        if (command != null) { addMessage(command, false); speak(command); return }
        if (prefs.getBoolean("offline_mode", false)) { val local = offlineReply(text); addMessage(local, false); speak(local); return }
        val key = prefs.getString("api_key", "") ?: ""
        if (key.isBlank()) { addMessage("برای گفت‌وگوی هوشمند، از تنظیمات کلید API و نشانی سرویس را وارد کن. فرمان‌های پایه بدون اینترنت هم کار می‌کنند.", false); return }
        callChat(text)
    }

    private fun startWakeListener() {
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 41); return }
        try { if (android.os.Build.VERSION.SDK_INT >= 26) startForegroundService(Intent(this, WakeWordService::class.java)) else startService(Intent(this, WakeWordService::class.java)); addMessage("گوش‌به‌زنگ فعال شد. عبارت «سینا» یا «ممد قلی» را بگو.", false) }
        catch (e: Exception) { addMessage("فعال‌سازی بیدارباش ممکن نشد: ${e.message}", false) }
    }

    private fun offlineReply(raw: String): String {
        val s = raw.lowercase(Locale("fa"))
        return when {
            s.contains("سلام") -> "سلام! من در حالت آفلاین پایه هستم. برای گفت‌وگوی گسترده‌تر باید یک مدل زبانی محلی روی گوشی نصب شود."
            s.contains("اسمت") || s.contains("کی هستی") -> "من سینا، دستیار فارسی تو هستم."
            s.contains("کمک") || s.contains("چه کار") -> "در حالت آفلاین می‌توانم ساعت، تاریخ و چند فرمان گوشی را انجام بدهم و به پرسش‌های ساده پاسخ بدهم."
            s.contains("ساعت") -> "ساعت دستگاه: " + java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date())
            s.contains("تاریخ") -> "تاریخ امروز: " + java.text.SimpleDateFormat("yyyy/MM/dd", Locale("fa")).format(java.util.Date())
            else -> "حالت آفلاین پایه فعال است، اما هنوز مدل زبانی کامل روی گوشی نصب نشده. برای پاسخ هوشمند آفلاین باید فایل مدل سازگار با گوشی اضافه شود."
        }
    }

    private fun generateVideo(prompt: String) {
        val key = prefs.getString("api_key", "") ?: ""
        val endpoint = prefs.getString("video_endpoint", "") ?: ""
        val model = prefs.getString("video_model", "") ?: ""
        if (key.isBlank() || endpoint.isBlank() || model.isBlank()) {
            addMessage("برای ساخت ویدئو باید در تنظیمات، کلید API، نشانی کامل endpoint و نام مدل ویدئو را وارد کنی. سرویس‌های ویدئوساز API یکسانی ندارند.", false); return
        }
        addMessage("درخواست ساخت ویدئو: $prompt", true)
        thread {
            try {
                val body = JSONObject().put("model", model).put("prompt", prompt)
                val conn = (URL(endpoint).openConnection() as HttpURLConnection).apply { requestMethod = "POST"; connectTimeout = 30000; readTimeout = 180000; doOutput = true; setRequestProperty("Authorization", "Bearer $key"); setRequestProperty("Content-Type", "application/json") }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                val raw = stream.bufferedReader().use { it.readText() }; val code = conn.responseCode; conn.disconnect()
                if (code !in 200..299) throw Exception("HTTP $code: ${raw.take(500)}")
                val json = JSONObject(raw)
                val data = json.optJSONArray("data")?.optJSONObject(0)
                val videoUrl = data?.optString("url")?.takeIf { !it.isNullOrBlank() && it != "null" }
                    ?: json.optString("url").takeIf { it.isNotBlank() && it != "null" }
                    ?: json.optString("output").takeIf { it.startsWith("http") }
                    ?: json.optString("video_url").takeIf { it.startsWith("http") }
                runOnUiThread {
                    if (videoUrl != null) {
                        addMessage("ویدئو آماده شد.", false)
                        try { val view = VideoView(this); view.setVideoURI(Uri.parse(videoUrl)); view.setMediaController(android.widget.MediaController(this).apply { setAnchorView(view) }); messages.addView(view, LinearLayout.LayoutParams(-1, 600)); view.start(); scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }
                        catch (_: Exception) { addMessage("لینک ویدئو: $videoUrl", false) }
                    } else addMessage("درخواست پذیرفته شد، اما پاسخ سرویس لینک مستقیم ویدئو نداشت. پاسخ: ${raw.take(700)}", false)
                }
            } catch (e: Exception) { runOnUiThread { addMessage("ساخت ویدئو ناموفق بود: ${e.message}", false) } }
        }
    }

    private fun runLocalCommand(raw: String): String? {
        val s = raw.lowercase(Locale("fa"))
        return when {
            s.contains("ساعت") -> "ساعت دستگاه: " + java.text.SimpleDateFormat("HH:mm", Locale.getDefault()).format(java.util.Date())
            s.contains("تاریخ") -> "تاریخ امروز: " + java.text.SimpleDateFormat("yyyy/MM/dd", Locale("fa")).format(java.util.Date())
            s.contains("وای فای") || s.contains("وای‌فای") -> { startActivity(Intent(Settings.ACTION_WIFI_SETTINGS)); "تنظیمات وای‌فای باز شد." }
            s.contains("بلوتوث") -> { startActivity(Intent(Settings.ACTION_BLUETOOTH_SETTINGS)); "تنظیمات بلوتوث باز شد." }
            s.contains("تنظیمات گوشی") -> { startActivity(Intent(Settings.ACTION_SETTINGS)); "تنظیمات گوشی باز شد." }
            s.contains("تماس") || s.contains("شماره گیر") || s.contains("شماره‌گیر") -> { startActivity(Intent(Intent.ACTION_DIAL)); "شماره‌گیر باز شد." }
            s.contains("پیامک") -> { startActivity(Intent(Intent.ACTION_MAIN).addCategory(Intent.CATEGORY_APP_MESSAGING)); "برنامه پیامک باز شد." }
            s.contains("دوربین") -> { try { startActivity(Intent("android.media.action.IMAGE_CAPTURE")); "دوربین باز شد." } catch (_: Exception) { "دوربین در دسترس نیست." } }
            s.contains("سینا") && (s.contains("سلام") || s.contains("بیدار")) -> "بله، گوش می‌دهم!"
            else -> null
        }
    }

    private fun callChat(prompt: String) {
        addMessage("در حال فکر کردن…", false)
        thread {
            try {
                val base = (prefs.getString("base_url", "https://api.openai.com/v1") ?: "").trim().trimEnd('/')
                val model = prefs.getString("chat_model", "gpt-4o-mini") ?: "gpt-4o-mini"
                history.put(JSONObject().put("role", "user").put("content", prompt))
                val body = JSONObject().put("model", model).put("messages", JSONArray().put(JSONObject().put("role", "system").put("content", "تو دستیار فارسی SINA AI هستی. پاسخ‌ها را روشن، مفید و به زبان فارسی بده." )).let { sys -> JSONArray().put(sys).also { arr -> for (i in 0 until history.length()) arr.put(history.getJSONObject(i)) } })
                val conn = (URL("$base/chat/completions").openConnection() as HttpURLConnection).apply { requestMethod = "POST"; connectTimeout = 20000; readTimeout = 60000; doOutput = true; setRequestProperty("Authorization", "Bearer ${prefs.getString("api_key", "")}"); setRequestProperty("Content-Type", "application/json") }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                val result = stream.bufferedReader().use { it.readText() }; conn.disconnect()
                val json = JSONObject(result)
                if (json.has("error")) throw Exception(json.getJSONObject("error").optString("message", result))
                val answer = json.getJSONArray("choices").getJSONObject(0).getJSONObject("message").optString("content")
                history.put(JSONObject().put("role", "assistant").put("content", answer))
                runOnUiThread { addMessage(answer, false); speak(answer) }
            } catch (e: Exception) { runOnUiThread { addMessage("خطا در اتصال هوش مصنوعی: ${e.message ?: "اتصال برقرار نشد"}", false) } }
        }
    }

    private fun generateImage(prompt: String) {
        val key = prefs.getString("api_key", "") ?: ""
        if (key.isBlank()) { addMessage("برای ساخت تصویر، ابتدا کلید API را در تنظیمات وارد کن.", false); return }
        addMessage("درخواست ساخت تصویر: $prompt", true)
        thread {
            try {
                val base = (prefs.getString("base_url", "https://api.openai.com/v1") ?: "").trim().trimEnd('/')
                val model = prefs.getString("image_model", "gpt-image-1") ?: "gpt-image-1"
                val body = JSONObject().put("model", model).put("prompt", prompt).put("size", "1024x1024")
                val conn = (URL("$base/images/generations").openConnection() as HttpURLConnection).apply { requestMethod = "POST"; connectTimeout = 20000; readTimeout = 120000; doOutput = true; setRequestProperty("Authorization", "Bearer $key"); setRequestProperty("Content-Type", "application/json") }
                conn.outputStream.use { it.write(body.toString().toByteArray(Charsets.UTF_8)) }
                val stream = if (conn.responseCode in 200..299) conn.inputStream else conn.errorStream
                val result = stream.bufferedReader().use { it.readText() }; conn.disconnect()
                val item = JSONObject(result).getJSONArray("data").getJSONObject(0)
                val b64 = item.optString("b64_json", ""); val imageUrl = item.optString("url", "")
                runOnUiThread {
                    addMessage("تصویر ساخته شد.", false)
                    try {
                        val bitmap = if (b64.isNotEmpty()) BitmapFactory.decodeByteArray(android.util.Base64.decode(b64, android.util.Base64.DEFAULT), 0, android.util.Base64.decode(b64, android.util.Base64.DEFAULT).size) else null
                        if (bitmap != null) { val iv = ImageView(this); iv.setImageBitmap(bitmap); iv.adjustViewBounds = true; messages.addView(iv); scroll.post { scroll.fullScroll(View.FOCUS_DOWN) } }
                        else if (imageUrl.isNotEmpty()) addMessage("نشانی تصویر: $imageUrl", false)
                    } catch (e: Exception) { addMessage("تصویر دریافت شد اما نمایش آن ممکن نشد: ${e.message}", false) }
                }
            } catch (e: Exception) { runOnUiThread { addMessage("ساخت تصویر ناموفق بود: ${e.message}", false) } }
        }
    }

    private fun startListening() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) { addMessage("سرویس تشخیص گفتار روی این گوشی در دسترس نیست.", false); return }
        if (checkSelfPermission(Manifest.permission.RECORD_AUDIO) != PackageManager.PERMISSION_GRANTED) { requestPermissions(arrayOf(Manifest.permission.RECORD_AUDIO), 41); return }
        speech?.destroy(); speech = SpeechRecognizer.createSpeechRecognizer(this)
        speech?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) { runOnUiThread { addMessage("گوش می‌دهم…", false) } }
            override fun onResults(results: Bundle?) { val words = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)?.firstOrNull(); runOnUiThread { if (!words.isNullOrBlank()) { input.setText(words); sendText() } } }
            override fun onError(error: Int) { runOnUiThread { addMessage("تشخیص صدا انجام نشد. دوباره امتحان کن.", false) } }
            override fun onBeginningOfSpeech() {}; override fun onRmsChanged(rmsdB: Float) {}; override fun onBufferReceived(buffer: ByteArray?) {}; override fun onEndOfSpeech() {}; override fun onPartialResults(partialResults: Bundle?) {}; override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply { putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM); putExtra(RecognizerIntent.EXTRA_LANGUAGE, "fa-IR"); putExtra(RecognizerIntent.EXTRA_PROMPT, "با سینا صحبت کن") }
        speech?.startListening(intent)
    }

    private fun speak(text: String) { tts?.speak(text.take(3000), TextToSpeech.QUEUE_FLUSH, null, "sina_reply") }
    override fun onInit(status: Int) { if (status == TextToSpeech.SUCCESS) tts?.language = Locale("fa", "IR") }

    private fun showSettings() {
        val box = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL; setPadding(20, 8, 20, 0) }
        fun field(title: String, key: String, value: String, secret: Boolean = false): EditText { box.addView(TextView(this).apply { text = title; setTextColor(Color.WHITE) }); return EditText(this).apply { setText(prefs.getString(key, value)); hint = title; if (secret) inputType = 129 else inputType = 1; box.addView(this) } }
        val api = field("کلید API", "api_key", "", true)
        val base = field("نشانی سرویس (Base URL)", "base_url", "https://api.openai.com/v1")
        val chat = field("مدل گفت‌وگو", "chat_model", "gpt-4o-mini")
        val image = field("مدل تصویر", "image_model", "gpt-image-1")
        val videoEndpoint = field("نشانی کامل API تولید ویدئو", "video_endpoint", "")
        val videoModel = field("مدل تولید ویدئو", "video_model", "")
        val offline = CheckBox(this).apply { text = "حالت آفلاین پایه (بدون API)"; setTextColor(Color.WHITE); isChecked = prefs.getBoolean("offline_mode", false); box.addView(this) }
        ScrollView(this).apply { addView(box) }
        val dialog = android.app.AlertDialog.Builder(this).setTitle("تنظیمات هوش مصنوعی").setView(box).setPositiveButton("ذخیره") { _, _ -> prefs.edit().putString("api_key", api.text.toString().trim()).putString("base_url", base.text.toString().trim()).putString("chat_model", chat.text.toString().trim()).putString("image_model", image.text.toString().trim()).putString("video_endpoint", videoEndpoint.text.toString().trim()).putString("video_model", videoModel.text.toString().trim()).putBoolean("offline_mode", offline.isChecked).apply(); addMessage("تنظیمات ذخیره شد.", false) }.setNegativeButton("بستن", null).create()
        dialog.show()
    }

    private fun showCommands() {
        val options = arrayOf("باز کردن وای‌فای", "باز کردن بلوتوث", "باز کردن تنظیمات", "باز کردن شماره‌گیر", "باز کردن دوربین", "گفتن ساعت", "گفتن تاریخ")
        android.app.AlertDialog.Builder(this).setTitle("فرمان‌های قابل اجرا").setItems(options) { _, which ->
            val commands = arrayOf("وای فای", "بلوتوث", "تنظیمات گوشی", "شماره گیر", "دوربین", "ساعت", "تاریخ")
            val result = runLocalCommand(commands[which]); addMessage(options[which], true); if (result != null) { addMessage(result, false); speak(result) }
        }.show()
    }

    override fun onNewIntent(intent: Intent) { super.onNewIntent(intent); setIntent(intent); if (intent.getBooleanExtra("wake_detected", false)) { addMessage("جانم! صدات رو شنیدم.", false); speak("جانم، گوش می‌دهم") } }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) { super.onRequestPermissionsResult(requestCode, permissions, grantResults); if (requestCode == 41 && grantResults.firstOrNull() == PackageManager.PERMISSION_GRANTED) startListening() else if (requestCode == 41) addMessage("برای استفاده از میکروفون باید مجوز بدهی.", false) }
    override fun onDestroy() { speech?.destroy(); tts?.stop(); tts?.shutdown(); super.onDestroy() }
}
