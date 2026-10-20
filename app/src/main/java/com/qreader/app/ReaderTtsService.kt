package com.qreader.app

import android.app.*
import android.content.*
import android.media.AudioAttributes
import android.media.AudioFocusRequest
import android.media.AudioManager
import android.media.AudioManager.ACTION_AUDIO_BECOMING_NOISY
import java.io.File
import android.os.*
import android.speech.tts.TextToSpeech
import android.speech.tts.UtteranceProgressListener
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.Executors

/** Foreground TTS: does not require an Activity to remain visible. */
class ReaderTtsService : Service(), TextToSpeech.OnInitListener {
    companion object {
        const val CHANNEL = "qreader_playback"
        const val ACTION_PLAY = "com.qreader.app.PLAY"
        const val ACTION_PAUSE = "com.qreader.app.PAUSE"
        const val ACTION_STOP = "com.qreader.app.STOP"
        const val ACTION_TIMER = "com.qreader.app.TIMER"
        const val ACTION_NEXT = "com.qreader.app.NEXT"
        const val ACTION_PREV = "com.qreader.app.PREV"
        const val ACTION_POSITION = "com.qreader.app.POSITION"
        const val EXTRA_FILE = "file"
        const val EXTRA_OFFSET = "offset"
    }
    private var tts: TextToSpeech? = null
    private var ready = false
    private var sentences: List<Pair<Int,String>> = emptyList()
    private var index = 0
    private var lastText = ""
    private val generation = AtomicInteger()
    private var audioManager: AudioManager? = null
    private var focus: AudioFocusRequest? = null
    private val handler = Handler(Looper.getMainLooper())
    private val loader=Executors.newSingleThreadExecutor()
    private var requestSerial=0
    private var timerAt = 0L
    private var speaking = false
    private var sessionFile: String? = null
    private var initializedForeground = false
    private val noisyReceiver=object: BroadcastReceiver(){
        override fun onReceive(context:Context?,intent:Intent?){if(intent?.action==ACTION_AUDIO_BECOMING_NOISY && speaking)pause()}
    }

    override fun onCreate() {
        super.onCreate()
        timerAt=getSharedPreferences("qreader",MODE_PRIVATE).getLong("sleep_timer_at",0L)
        val channel = NotificationChannel(CHANNEL, "소설 읽어주기", NotificationManager.IMPORTANCE_LOW)
        getSystemService(NotificationManager::class.java).createNotificationChannel(channel)
        audioManager = getSystemService(AUDIO_SERVICE) as AudioManager
        registerReceiver(noisyReceiver,IntentFilter(ACTION_AUDIO_BECOMING_NOISY))
        tts = TextToSpeech(this, this)
    }
    override fun onInit(status: Int) {
        ready = status == TextToSpeech.SUCCESS
        if (ready) {
            tts?.language = Locale.KOREAN
            tts?.setOnUtteranceProgressListener(object : UtteranceProgressListener() {
                override fun onStart(utteranceId: String?) {}
                override fun onError(utteranceId: String?) { advance(utteranceId) }
                override fun onDone(utteranceId: String?) { advance(utteranceId) }
            })
            if (speaking) speakNow()
        } else { speaking = false; notifyPosition() }
    }
    private fun advance(id: String?) {
        val parts = id?.split(":") ?: return
        if (parts.size != 2) return
        val g = parts[0].toIntOrNull() ?: return
        val completedIndex = parts[1].toIntOrNull() ?: return
        handler.post {
            if (g != generation.get() || !speaking || completedIndex != index) return@post
            if (timerAt > 0 && System.currentTimeMillis() >= timerAt) { timerAt=0L; getSharedPreferences("qreader",MODE_PRIVATE).edit().putLong("sleep_timer_at",0L).apply(); pause(); return@post }
            if (index < sentences.lastIndex) { index++; speakNow() } else { pause() }
        }
    }
    private fun segments(s: String): List<Pair<Int,String>> {
        // Preserve source offsets while capping utterances below Android TTS input limits.
        val out = mutableListOf<Pair<Int,String>>()
        val boundary = Regex("(?<=[.!?。！？])\\s+|\\n+")
        var start = 0
        for (m in boundary.findAll(s)) {
            val part = s.substring(start, m.range.first)
            addChunks(out, start, part)
            start = m.range.last + 1
        }
        if (start < s.length) addChunks(out, start, s.substring(start))
        return out
    }
    private fun addChunks(out: MutableList<Pair<Int,String>>, offset: Int, text: String) {
        var p = 0
        while (p < text.length) {
            val end = (p + 1400).coerceAtMost(text.length)
            val raw = text.substring(p,end)
            if (raw.isNotBlank()) out.add((offset + p) to raw)
            p = end
        }
    }
    private fun filtered(raw: String): String {
        val prefs = getSharedPreferences("qreader", MODE_PRIVATE)
        var s = raw.replace(Regex("[ㅋㅎ]{3,}"), " ").replace(Regex("[=*_~]{4,}"), " ")
        if (!prefs.getBoolean("read_latin", true)) s = s.replace(Regex("[A-Za-z]+"), " ")
        if (!prefs.getBoolean("read_hanja", true)) s = s.replace(Regex("[\\u3400-\\u9FFF]+"), " ")
        if (!prefs.getBoolean("read_japanese", true)) s = s.replace(Regex("[\\u3040-\\u30FF]+"), " ")
        val rules = prefs.getString("rules", "") ?: ""
        for (line in rules.lines()) {
            val a = line.split("=>", limit=2)
            if (a.size == 2 && a[0].trim().isNotEmpty()) s = s.replace(a[0].trim(), a[1].trim())
        }
        return s.trim()
    }
    private fun speakNow() {
        if (!ready || !speaking) return
        if (sentences.isEmpty()) { pause(); return }
        val pair = sentences[index]
        notifyPosition()
        val g = generation.get()
        val spoken = filtered(pair.second)
        if (spoken.isBlank()) { handler.post { advance("$g:$index") }; return }
        if(timerAt>0 && System.currentTimeMillis()>=timerAt){timerAt=0L;getSharedPreferences("qreader",MODE_PRIVATE).edit().putLong("sleep_timer_at",0L).apply();pause();return}
        val prefs = getSharedPreferences("qreader", MODE_PRIVATE)
        tts?.setSpeechRate(prefs.getFloat("tts_rate",1f))
        tts?.setPitch(prefs.getFloat("tts_pitch",1f))
        val remaining=if(timerAt>0)timerAt-System.currentTimeMillis() else Long.MAX_VALUE
        val volume=if(remaining in 0..180000) (0.25f+0.75f*(remaining/180000f)).coerceIn(0.25f,1f) else 1f
        val params=Bundle().apply{putFloat(TextToSpeech.Engine.KEY_PARAM_VOLUME,volume)}
        val status=tts?.speak(spoken, TextToSpeech.QUEUE_FLUSH, params, "$g:$index")
        if(status==TextToSpeech.ERROR){speaking=false;notifyPosition();return}
        updateNotification()
    }
    private fun notifyPosition() {
        val intent = Intent(ACTION_POSITION).setPackage(packageName)
        intent.putExtra(EXTRA_OFFSET, if (sentences.isEmpty()) 0 else sentences[index].first)
        intent.putExtra("speaking", speaking)
        sendBroadcast(intent)
    }
    private fun play(file: String?, offset: Int) {
        // A foreground service must issue its notification promptly, even when focus or input fails.
        updateNotification()
        if (file != null) {
            val f=File(file)
            if (!f.canonicalPath.startsWith(cacheDir.canonicalPath + File.separator) || !f.isFile || f.length()>80L*1024*1024) {
                speaking=false;notifyPosition();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return
            }
            if(file==sessionFile && sentences.isNotEmpty() && f.length()>0L){
                beginPlayback(offset)
                return
            }
            val serial=++requestSerial
            loader.execute {
                val parsed=runCatching { val value=f.readText(Charsets.UTF_8);value to segments(value) }.getOrNull()
                handler.post {
                    if(serial!=requestSerial)return@post
                    if(parsed==null){speaking=false;notifyPosition();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return@post}
                    generation.incrementAndGet();tts?.stop();sessionFile=file;lastText=parsed.first;sentences=parsed.second
                    beginPlayback(offset)
                }
            }
            return
        }
        beginPlayback(offset)
    }
    private fun beginPlayback(offset:Int){
        if(sentences.isEmpty()){speaking=false;notifyPosition();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return}
        index=sentences.indexOfLast{it.first<=offset}.coerceAtLeast(0)
        if(!obtainFocus()){speaking=false;notifyPosition();stopForeground(STOP_FOREGROUND_REMOVE);stopSelf();return}
        generation.incrementAndGet();speaking=true;notifyPosition();updateNotification()
        if(ready)speakNow()
    }
    private fun obtainFocus(): Boolean {
        val am = audioManager ?: return false
        val a = AudioAttributes.Builder().setUsage(AudioAttributes.USAGE_MEDIA)
            .setContentType(AudioAttributes.CONTENT_TYPE_SPEECH).build()
        focus?.let { am.abandonAudioFocusRequest(it) }
        val req = AudioFocusRequest.Builder(AudioManager.AUDIOFOCUS_GAIN)
            .setAudioAttributes(a)
            .setOnAudioFocusChangeListener { change ->
                if (change <= 0) handler.post { pause() }
            }.build()
        focus = req
        return am.requestAudioFocus(req) == AudioManager.AUDIOFOCUS_REQUEST_GRANTED
    }
    private fun pause() {
        requestSerial++
        generation.incrementAndGet()
        speaking = false
        tts?.stop()
        notifyPosition()
        updateNotification()
        focus?.let { audioManager?.abandonAudioFocusRequest(it) }
        focus = null
    }
    private fun skip(n: Int) {
        if (sentences.isEmpty()) return
        index = (index+n).coerceIn(0,sentences.lastIndex)
        generation.incrementAndGet()
        tts?.stop()
        notifyPosition()
        if (speaking) speakNow()
    }
    private fun updateNotification() {
        val start = PendingIntent.getActivity(this,0,Intent(this,ReaderActivity::class.java), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        fun action(code:String, req:Int): PendingIntent = PendingIntent.getService(this,req,
            Intent(this,ReaderTtsService::class.java).setAction(code), PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
        val n = Notification.Builder(this, CHANNEL)
            .setContentTitle("뀨 리더 · 소설 읽어주기")
            .setContentText(if(speaking) "재생 중 · 화면을 꺼도 계속 읽습니다" else "일시정지 · 문장부터 다시 재생")
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setContentIntent(start)
            .setOngoing(speaking)
            .addAction(Notification.Action.Builder(null,"이전",action(ACTION_PREV,1)).build())
            .addAction(Notification.Action.Builder(null,if(speaking)"일시정지" else "재개",action(if(speaking) ACTION_PAUSE else ACTION_PLAY,2)).build())
            .addAction(Notification.Action.Builder(null,"다음",action(ACTION_NEXT,3)).build())
            .addAction(Notification.Action.Builder(null,"종료",action(ACTION_STOP,4)).build())
            .build()
        startForeground(111,n);initializedForeground=true
    }
    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when(intent?.action) {
            ACTION_PLAY -> play(intent.getStringExtra(EXTRA_FILE),intent.getIntExtra(EXTRA_OFFSET,if(sentences.isEmpty())0 else sentences[index].first))
            ACTION_TIMER -> { val minutes=intent.getIntExtra("minutes",0);timerAt=if(minutes<=0)0L else System.currentTimeMillis()+minutes*60000L
                getSharedPreferences("qreader",MODE_PRIVATE).edit().putLong("sleep_timer_at",timerAt).apply() }
            ACTION_PAUSE -> pause()
            ACTION_NEXT -> skip(1)
            ACTION_PREV -> skip(-1)
            ACTION_STOP -> { pause(); stopForeground(STOP_FOREGROUND_REMOVE); stopSelf() }
        }
        return START_NOT_STICKY
    }
    override fun onBind(intent: Intent?): IBinder? = null
    override fun onDestroy() {
        unregisterReceiver(noisyReceiver)
        requestSerial++
        loader.shutdownNow()
        tts?.stop(); tts?.shutdown()
        focus?.let {audioManager?.abandonAudioFocusRequest(it)}
        super.onDestroy()
    }
}
