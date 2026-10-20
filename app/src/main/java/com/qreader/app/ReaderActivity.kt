package com.qreader.app

import android.Manifest
import android.app.*
import android.content.*
import android.graphics.Color
import android.graphics.BitmapFactory
import android.graphics.Bitmap
import java.nio.file.Files
import com.google.android.gms.tasks.Tasks
import android.graphics.Typeface
import android.net.Uri
import android.os.*
import android.provider.OpenableColumns
import android.speech.tts.TextToSpeech
import android.view.*
import android.widget.*
import android.text.*
import android.text.TextUtils
import android.text.style.BackgroundColorSpan
import android.text.SpannableString
import android.text.Spanned
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.text.TextRecognition
import com.google.mlkit.vision.text.korean.KoreanTextRecognizerOptions
import org.json.JSONArray
import java.io.*
import java.nio.ByteBuffer
import java.nio.charset.*
import java.security.MessageDigest
import java.util.concurrent.Executors
import java.util.zip.ZipInputStream
import kotlin.math.abs

class ReaderActivity : Activity() {
    private val prefs by lazy { getSharedPreferences("qreader", MODE_PRIVATE) }
    private val executor = Executors.newSingleThreadExecutor()
    private lateinit var root: LinearLayout
    private lateinit var header: TextView
    private lateinit var scroll: ScrollView
    private lateinit var text: TextView
    private lateinit var footer: TextView
    private lateinit var seek: SeekBar
    private lateinit var playTts: Button
    private var openedUri: Uri? = null
    private var docTitle = ""
    private var raw = ""
    private var docKey = ""
    private var autoScroll = false
    private val ui = Handler(Looper.getMainLooper())
    private var readingOffset = 0
    private var latestTtsOffset = 0
    private var beforeJump = -1
    private var followTts = true
    private var ttsPlaying = false
    private var readerColor = Color.rgb(49,49,49)
    private var readerForeground = Color.rgb(237,237,237)
    private var lastTouchX = 0f
    private var lastTouchY = 0f
    private var inGesture = false
    private var brightnessValue = .7f
    private var ignoreSeek = false
    private var draggingSeek = false
    private var trackingTts = false
    private var pendingSpeechOffset = -1
    private var scrollFraction = 0f
    private var lastFrameTime = 0L
    private var hasCustomFont = false
    private var gestureRemainder = 0f
    private var cachedCustomFont: Typeface? = null
    private var currentOpenRequest = 0
    private var fontFamilies = arrayOf("시스템 기본", "명조 (Serif)", "고딕 (Sans)", "고정폭 (Mono)", "사용자 TTF/OTF 글꼴")
    private fun dp(value:Int)=(value*resources.displayMetrics.density+.5f).toInt()
    private fun fontTypeface(index:Int): Typeface = when(index) {
        1 -> Typeface.create("serif",Typeface.NORMAL)
        2 -> Typeface.create("sans-serif",Typeface.NORMAL)
        3 -> Typeface.create("monospace",Typeface.NORMAL)
        4 -> cachedCustomFont ?: runCatching { Typeface.createFromFile(File(filesDir,"reader-custom-font")) }.getOrDefault(Typeface.DEFAULT).also { cachedCustomFont = it }
        else -> Typeface.DEFAULT
    }
    private fun applyTypography() {
        val pos = readingOffset
        text.textSize = prefs.getFloat("font_size",24f)
        text.typeface = fontTypeface(prefs.getInt("font_family",0))
        text.setLineSpacing(prefs.getFloat("line_space",12f),prefs.getFloat("line_multiple",1.06f))
        val margin=dp(prefs.getInt("margin_dp",20))
        text.setPadding(margin,dp(24),margin,dp(65))
        text.post { jumpTo(pos,false) }
    }
    private fun applyTheme() {
        val mode=prefs.getInt("theme",0)
        readerColor=when(mode){1->Color.rgb(248,239,217);2->Color.BLACK;else->Color.rgb(49,49,49)}
        readerForeground=if(mode==1)Color.rgb(38,38,38) else Color.rgb(237,237,237)
        text.setTextColor(readerForeground);text.setBackgroundColor(readerColor);root.setBackgroundColor(readerColor)
    }
    private val tick = object:Runnable {
        override fun run() {
            val now = android.os.SystemClock.uptimeMillis()
            val frameDelta = if(lastFrameTime==0L) 16L else (now-lastFrameTime).coerceIn(1L,100L)
            lastFrameTime=now
            if(autoScroll && ::scroll.isInitialized && raw.isNotEmpty()) {
                scrollFraction += prefs.getFloat("scroll_px_second",30f)*resources.displayMetrics.density*frameDelta/1000f
                val pixels=scrollFraction.toInt()
                if(pixels>0){ scroll.scrollBy(0,pixels);scrollFraction-=pixels }
                val limit=(scroll.getChildAt(0).height-scroll.height).coerceAtLeast(0)
                if(scroll.scrollY>=limit){autoScroll=false;updateKeepScreen()}
            }
            if(::scroll.isInitialized && raw.isNotBlank()) {
                val off=offsetForScroll()
                if(!ttsPlaying && !draggingSeek) readingOffset=off
                if(!draggingSeek){ignoreSeek=true;seek.progress=((readingOffset.toFloat()/raw.length)*10000).toInt();ignoreSeek=false}
                refreshFooter()
            }
            ui.postDelayed(this,if(autoScroll)16L else 300L)
        }
    }
    private val ttsReceiver = object: BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ReaderTtsService.ACTION_POSITION) return
            latestTtsOffset = intent.getIntExtra(ReaderTtsService.EXTRA_OFFSET,0)
            ttsPlaying = intent.getBooleanExtra("speaking",false)
            if (ttsPlaying && followTts && raw.isNotEmpty()) { trackingTts=true; jumpTo(latestTtsOffset, saveHistory=false); trackingTts=false }
            if(ttsPlaying){readingOffset=latestTtsOffset;savePosition()}
            if (::playTts.isInitialized) playTts.text = if(ttsPlaying) "⏸ 듣기" else "▶ 듣기"
        }
    }
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor = Color.rgb(22,22,22)
        window.navigationBarColor = Color.BLACK
        brightnessValue = prefs.getFloat("brightness",.7f)
        applyBrightness()
        if (Build.VERSION.SDK_INT >= 33) {
            registerReceiver(ttsReceiver, IntentFilter(ReaderTtsService.ACTION_POSITION), RECEIVER_NOT_EXPORTED)
            if (checkSelfPermission(Manifest.permission.POST_NOTIFICATIONS) != android.content.pm.PackageManager.PERMISSION_GRANTED)
                requestPermissions(arrayOf(Manifest.permission.POST_NOTIFICATIONS),100)
        } else registerReceiver(ttsReceiver,IntentFilter(ReaderTtsService.ACTION_POSITION))
        buildUi()
        val shared = if (Build.VERSION.SDK_INT >= 33) intent.getParcelableExtra(Intent.EXTRA_STREAM,Uri::class.java)
                     else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM)
        if (shared != null) openUri(shared) else if(intent.data != null) openUri(intent.data!!) else { val last=prefs.getString("last_uri",null);if(last!=null) openUri(Uri.parse(last)) else showLibrary() }
    }
    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        val uri = intent.data
        if(uri != null) openUri(uri) else { val shared=if(Build.VERSION.SDK_INT>=33)intent.getParcelableExtra(Intent.EXTRA_STREAM,Uri::class.java) else intent.getParcelableExtra<Uri>(Intent.EXTRA_STREAM); if(shared!=null)openUri(shared) }
    }
    private fun btn(label:String, onClick:()->Unit): Button = Button(this).apply {
        text=label; textSize=13f; setAllCaps(false)
        setTextColor(Color.WHITE); setBackgroundColor(Color.rgb(62,76,86))
        setOnClickListener {onClick()}
        minHeight=dp(50); minWidth=dp(48)
    }
    private fun makeRow(vararg widgets:android.view.View): LinearLayout = LinearLayout(this).apply {
        orientation=LinearLayout.HORIZONTAL
        widgets.forEach { addView(it,LinearLayout.LayoutParams(0,dp(53),1f)) }
    }
    private fun buildUi() {
        root = LinearLayout(this).apply { orientation=LinearLayout.VERTICAL; setBackgroundColor(readerColor) }
        header=TextView(this).apply { text="뀨 리더"; textSize=15f; setTextColor(Color.rgb(255,210,138)); setPadding(dp(12),dp(10),dp(9),dp(10)); maxLines=1; ellipsize=TextUtils.TruncateAt.MIDDLE }
        root.addView(header)
        root.addView(makeRow(btn("☰ 서재"){ showLibrary() },btn("파일 +"){ pickFiles() },btn("⌕ 검색"){showSearch()},btn("⋮ 설정"){showSettings()}))
        scroll=ScrollView(this).apply {
            isFillViewport=true; isVerticalScrollBarEnabled=false
            setOnTouchListener { _,e -> handleGesture(e) }
        }
        text=TextView(this).apply {
            text="휴대폰의 TXT·ZIP·이미지 파일을 열어 소설을 읽으세요.\n\n'파일 +'을 눌러 시작하세요."
            textSize=prefs.getFloat("font_size",24f)
            setTextColor(readerForeground); setBackgroundColor(readerColor)
            setPadding(dp(20),dp(32),dp(20),dp(75))
            typeface=fontTypeface(prefs.getInt("font_family",0))
            setLineSpacing(prefs.getFloat("line_space",12f),1.06f)
            setTextIsSelectable(false)
            setOnLongClickListener {readingOffset=offsetForScroll();latestTtsOffset=0;startTts();true}
        }
        scroll.addView(text, android.widget.FrameLayout.LayoutParams(-1, -2))
        root.addView(scroll,LinearLayout.LayoutParams(-1,0,1f))
        seek=SeekBar(this).apply {
            max=10000
            setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(bar:SeekBar?,progress:Int,fromUser:Boolean) {if(fromUser && raw.isNotEmpty()) footer.text="이동 위치 ${(progress/100f)}%"}
                override fun onStartTrackingTouch(bar:SeekBar?) {draggingSeek=true}
                override fun onStopTrackingTouch(bar:SeekBar?) {
                    draggingSeek=false
                    if(raw.isNotEmpty()) jumpTo((progress.toDouble()/10000*raw.length).toInt(),true)
                }
            })
        }
        root.addView(seek)
        footer=TextView(this).apply { text="준비됨"; setTextColor(Color.LTGRAY); textSize=12f; setPadding(14,1,14,5) }
        root.addView(footer)
        playTts=btn("▶ 듣기") { if(ttsPlaying) pauseTts() else startTts() }
        root.addView(makeRow(btn("◀ 이전") { jumpTo((readingOffset-900).coerceAtLeast(0),true) },
            btn("⏯ 자동") { if(ttsPlaying)pauseTts();autoScroll=!autoScroll;updateKeepScreen();refreshFooter() },playTts,
            btn("다음 ▶") {jumpTo((readingOffset+900).coerceAtMost(raw.length),true)}))
        root.addView(makeRow(btn("↶ 복귀") {if(beforeJump>=0) jumpTo(beforeJump,false)},
            btn("★ 책갈피") {bookmark()}, btn("≡ 목차") {showChapters()},
            btn("≡ 더보기") {showMore()}))
        setContentView(root)
        applyTheme();applyTypography()
    }
    private fun handleGesture(e:MotionEvent):Boolean {
        val edge=dp(34)
        when(e.actionMasked) {
            MotionEvent.ACTION_DOWN -> {lastTouchX=e.x;lastTouchY=e.y;inGesture=false;gestureRemainder=0f;return false}
            MotionEvent.ACTION_MOVE -> {
                val dy=e.y-lastTouchY
                if(!inGesture && abs(dy)>dp(12) && (lastTouchX<edge || lastTouchX>scroll.width-edge)) inGesture=true
                if(inGesture){
                    if(lastTouchX<edge){ brightnessValue=(brightnessValue-dy/scroll.height).coerceIn(.02f,1f);applyBrightness() }
                    else {gestureRemainder+=dy;val delta=(gestureRemainder/dp(14)).toInt();if(delta!=0){gestureRemainder-=delta*dp(14);prefs.edit().putFloat("scroll_px_second",(prefs.getFloat("scroll_px_second",30f)-delta*3f).coerceIn(4f,240f)).apply()}}
                    lastTouchY=e.y;refreshFooter();return true
                }
                lastTouchY=e.y
            }
            MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> if(inGesture){prefs.edit().putFloat("brightness",brightnessValue).apply();return true}
        }
        return false
    }
    private fun applyBrightness() {window.attributes=window.attributes.apply {screenBrightness=brightnessValue}}
    private fun openUri(uri:Uri) {
        val request = ++currentOpenRequest
        openedUri=uri
        try {contentResolver.takePersistableUriPermission(uri,Intent.FLAG_GRANT_READ_URI_PERMISSION)} catch(_:Exception) {}
        docTitle=fileName(uri)
        val key=uri.toString()
        prefs.edit().putString("last_uri",key).apply()
        rememberLibrary(uri)
        header.text="불러오는 중 · $docTitle"
        executor.execute {
            try {
                val ext=docTitle.substringAfterLast('.',"").lowercase()
                val result=when(ext) {
                    "zip", "cbz" -> readArchive(uri)
                    "jpg","jpeg","png","webp" -> null
                    else -> decodeBytes(readLimited(uri,16*1024*1024))
                }
                if(result==null) { runOnUiThread {if(request==currentOpenRequest) recognizeImage(uri)} }
                else {
                    val stableId = "content:" + sha256Text(result)
                    runOnUiThread {if(request==currentOpenRequest) display(result,stableId,uri.toString())}
                }
            } catch(ex:Exception) {runOnUiThread {error("파일 열기 실패: ${ex.message}")}}
        }
    }
    private fun readLimited(uri:Uri,max:Int):ByteArray {
        contentResolver.openInputStream(uri)?.use { input ->
            val buffer=ByteArrayOutputStream();val data=ByteArray(16384)
            while(true) {val n=input.read(data);if(n<0)break;if(buffer.size()+n>max)throw IOException("파일이 현재 버전 제한(${max/1024/1024}MB)을 넘었습니다")
                buffer.write(data,0,n)}
            return buffer.toByteArray()
        } ?: throw IOException("파일 접근 권한이 없습니다")
    }
    private fun decodeBytes(bytes:ByteArray):String {
        if(bytes.size>=2 && bytes[0]==0xFF.toByte() && bytes[1]==0xFE.toByte())return String(bytes,2,bytes.size-2,Charsets.UTF_16LE)
        if(bytes.size>=2 && bytes[0]==0xFE.toByte() && bytes[1]==0xFF.toByte())return String(bytes,2,bytes.size-2,Charsets.UTF_16BE)
        val data= if(bytes.size>=3 && bytes[0]==0xEF.toByte()&&bytes[1]==0xBB.toByte()&&bytes[2]==0xBF.toByte()) bytes.copyOfRange(3,bytes.size) else bytes
        val forced=prefs.getString("encoding_${digest(openedUri?.toString()?:docTitle)}","auto")?:"auto"
        if(forced!="auto") return String(data,Charset.forName(forced))
        try {return Charsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT).decode(ByteBuffer.wrap(data)).toString()}
        catch(_:Exception) {}
        return String(data,charset949())
    }
    private fun charset949():Charset = listOf("x-windows-949","MS949","EUC-KR")
        .firstNotNullOfOrNull { runCatching { Charset.forName(it) }.getOrNull() } ?: Charsets.UTF_8
    private fun ocrImageBytes(bytes:ByteArray):String {
        val opts=BitmapFactory.Options().apply {inJustDecodeBounds=true}
        BitmapFactory.decodeByteArray(bytes,0,bytes.size,opts)
        val width=opts.outWidth
        val height=opts.outHeight
        val sample=when {width>4000->4;width>2200->2;else->1}
        if(height>10000 && width<=2200) {
            // Long screenshot: tile before OCR so tiny fonts are not downscaled.
            val bounds=BitmapFactory.decodeByteArray(bytes,0,bytes.size,BitmapFactory.Options().apply{inSampleSize=sample}) ?: return "[이미지 인식 실패]"
            val client=TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            return try {
                val pieces=mutableListOf<String>(); var y=0
                while(y<bounds.height){
                    val h=minOf(2800,bounds.height-y)
                    val tile=Bitmap.createBitmap(bounds,0,y,bounds.width,h)
                    try {pieces.add(Tasks.await(client.process(InputImage.fromBitmap(tile,0))).text)} finally{tile.recycle()}
                    y+=h
                }
                pieces.joinToString("\n")
            } finally{client.close();bounds.recycle()}
        }
        val decoded=BitmapFactory.Options().apply{inSampleSize=sample}
        val bitmap=BitmapFactory.decodeByteArray(bytes,0,bytes.size,decoded) ?: return "[이미지 인식 실패]"
        val client=TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
        return try { Tasks.await(client.process(InputImage.fromBitmap(bitmap,0))).text }
        finally {client.close();bitmap.recycle()}
    }
    private fun readSelectedImages(uris:List<Uri>) {
        if(uris.any{fileName(it).substringAfterLast('.',"").lowercase() !in listOf("jpg","jpeg","png","webp")}){
            error("여러 파일 선택은 이미지에만 지원합니다. TXT·ZIP은 하나씩 추가해주세요.");return
        }
        val sorted=uris.sortedBy {fileName(it).replace(Regex("\\d+")){m->m.value.padStart(10,'0')}}
        docTitle="선택한 이미지 ${sorted.size}장"
        header.text="이미지 OCR 진행 중..."
        executor.execute {
            try {
                val parts=sorted.mapIndexed {i,uri->
                    runOnUiThread { header.text="OCR 진행 ${i+1}/${sorted.size}" }
                    "\n\n── ${fileName(uri)} ──\n\n" + ocrImageBytes(readLimited(uri,8*1024*1024))
                }
                runOnUiThread{display(parts.joinToString("\n"),"group:"+sorted.joinToString("|"){it.toString()})}
            } catch(ex:Exception){runOnUiThread{error("이미지 묶음 OCR 오류: ${ex.message}")}}
        }
    }
    private fun readArchive(uri:Uri):String {
        val entries=mutableListOf<Pair<String,String>>()
        var sum=0L
        var images=0
        val charset=runCatching{Charset.forName(prefs.getString("zip_encoding","x-windows-949")?:"x-windows-949")}.getOrElse{charset949()}
        contentResolver.openInputStream(uri)?.use {source ->
            ZipInputStream(BufferedInputStream(source),charset).use {z ->
                while(true) {
                    val item=z.nextEntry ?: break
                    val name=item.name
                    if(item.isDirectory || name.split('/','\\').any{it==".."} || name.startsWith("/") || name.startsWith("\\")) {z.closeEntry();continue}
                    val ext=name.substringAfterLast('.',"").lowercase()
                    if(ext in listOf("txt","jpg","jpeg","png","webp")) {
                        val bytes=ByteArrayOutputStream();val chunk=ByteArray(8192)
                        while(true) {val n=z.read(chunk);if(n<0)break;sum+=n;if(sum>70L*1024*1024)throw IOException("압축 내용 70MB 초과")
                            bytes.write(chunk,0,n);if(bytes.size()>12*1024*1024)throw IOException("ZIP 내부 항목 12MB 초과")}
                        val body=if(ext=="txt")decodeBytes(bytes.toByteArray()) else {
                            images++;if(images>120)throw IOException("이미지는 한 번에 120장까지 지원합니다")
                            ocrImageBytes(bytes.toByteArray())
                        }
                        entries.add(name to body)
                    }
                    z.closeEntry()
                    if(entries.size>500)throw IOException("ZIP 항목 수 제한 초과")
                }
            }
        }
        if(entries.isEmpty()) throw IOException("ZIP에서 지원하는 TXT·이미지를 찾지 못했습니다")
        val sorted=entries.sortedBy {it.first.replace(Regex("\\d+")){m->m.value.padStart(12,'0')} }
        return sorted.joinToString("\n\n") {"\n\n──────── ${it.first} ────────\n\n${it.second}"}
    }
    private fun display(body:String,key:String, legacyKey:String?=null) {
        if(ttsPlaying) pauseTts()
        latestTtsOffset=0
        raw=body;docKey=key
        val newPositionKey="pos_${digest(key)}"
        val oldPositionKey=legacyKey?.let { "pos_${digest(it)}" }
        if (!prefs.contains(newPositionKey) && oldPositionKey!=null && prefs.contains(oldPositionKey)) {
            val edit=prefs.edit().putInt(newPositionKey,prefs.getInt(oldPositionKey,0))
            val oldBookmarks="bookmark_${digest(legacyKey!!)}"
            val newBookmarks="bookmark_${digest(key)}"
            if (!prefs.contains(newBookmarks) && prefs.contains(oldBookmarks)) edit.putString(newBookmarks,prefs.getString(oldBookmarks,""))
            edit.apply()
        }
        readingOffset=prefs.getInt(newPositionKey,0).coerceIn(0,raw.length)
        header.text="$docTitle · ${raw.length}자"
        text.text=raw
        applyTypography()
        text.post { jumpTo(readingOffset,false) }
        refreshFooter()
    }
    private fun sha256Text(s:String):String = MessageDigest.getInstance("SHA-256")
        .digest(s.toByteArray(Charsets.UTF_8)).joinToString("") { "%02x".format(it) }
    private fun digest(s:String):String = MessageDigest.getInstance("SHA-256").digest(s.toByteArray()).take(12).joinToString(""){"%02x".format(it)}
    private fun offsetForScroll():Int {
        if(raw.isEmpty()||text.layout==null)return 0
        val y=scroll.scrollY+32
        val line=text.layout.getLineForVertical((y-text.paddingTop).coerceAtLeast(0))
        return text.layout.getLineStart(line).coerceIn(0,raw.length)
    }
    private fun jumpTo(index:Int,saveHistory:Boolean) {
        if(raw.isEmpty()||text.layout==null)return
        if(saveHistory) beforeJump=readingOffset
        val at=index.coerceIn(0,raw.length)
        val line=text.layout.getLineForOffset(at)
        scroll.scrollTo(0,(text.layout.getLineTop(line)+text.paddingTop-20).coerceAtLeast(0))
        readingOffset=at
        if(saveHistory && ttsPlaying) followTts=false
        if(!ttsPlaying || trackingTts)savePosition()
        refreshFooter()
    }
    private fun savePosition(){if(docKey.isNotEmpty())prefs.edit().putInt("pos_${digest(docKey)}",readingOffset).apply()}
    private fun refreshFooter() {
        if(!::footer.isInitialized)return
        val pct=if(raw.isEmpty())0f else readingOffset.toFloat()/raw.length*100
        footer.text="${"%.1f".format(pct)}%  ·  ${readingOffset}/${raw.length}자  ·  자동 ${if(autoScroll)"켜짐" else "꺼짐"}  ·  밝기 ${(brightnessValue*100).toInt()}%"
    }
    private fun recognizeImage(uri:Uri) {
        header.text="이미지 OCR 처리 중..."
        try {
            val image=InputImage.fromFilePath(this,uri)
            val client=TextRecognition.getClient(KoreanTextRecognizerOptions.Builder().build())
            client.process(image).addOnSuccessListener { result ->
                client.close()
                if(result.text.isBlank()) error("이미지에서 글자를 찾지 못했습니다") else display(result.text,uri.toString()+"#ocr")
            }.addOnFailureListener { client.close();error("OCR 실패: ${it.message}") }
        } catch(ex:Exception) {error("이미지 처리 실패: ${ex.message}")}
    }
    private fun startTts() {
        if(raw.isBlank()){ toast("먼저 소설 파일을 여세요");return }
        autoScroll=false;updateKeepScreen()
        val shareFile=File(cacheDir,"document_${digest(docKey+raw.length+raw.take(96)+raw.takeLast(96))}.txt")
        playTts.text="준비 중"
        val document=raw
        val target=readingOffset
        executor.execute {
            try {
                val expectedSize=document.toByteArray(Charsets.UTF_8).size.toLong()
                if(!shareFile.exists() || shareFile.length()!=expectedSize){
                    val staged=File(shareFile.parentFile,shareFile.name+".tmp")
                    staged.writeText(document,Charsets.UTF_8)
                    if(staged.length()!=expectedSize)throw IOException("TTS 임시 파일 기록 실패")
                    if(!staged.renameTo(shareFile)){
                        staged.copyTo(shareFile,overwrite=true)
                        staged.delete()
                    }
                }
                runOnUiThread { if(docKey.isNotEmpty() && raw===document)launchTts(shareFile,target) }
            } catch(ex:Exception){runOnUiThread{error("TTS 준비 실패: ${ex.message}")}}
        }
    }
    private fun launchTts(shareFile:File,target:Int) {
        followTts=true
        val i=Intent(this,ReaderTtsService::class.java).setAction(ReaderTtsService.ACTION_PLAY)
            .putExtra(ReaderTtsService.EXTRA_FILE,shareFile.absolutePath)
            .putExtra(ReaderTtsService.EXTRA_OFFSET,target)
        try {startForegroundService(i);playTts.text="준비 중"}
        catch(ex:Exception){error("TTS 시작 실패: ${ex.message}")}
    }
    private fun pauseTts() {
        val i=Intent(this,ReaderTtsService::class.java).setAction(ReaderTtsService.ACTION_PAUSE)
        startService(i);ttsPlaying=false
        if(::playTts.isInitialized)playTts.text="▶ 듣기"
    }
    private fun pickFiles() {
        val i=Intent(Intent.ACTION_OPEN_DOCUMENT).apply {addCategory(Intent.CATEGORY_OPENABLE);type="*/*";putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true);addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)}
        startActivityForResult(i,301)
    }
    @Deprecated("Result callback compatible with API 26+")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(requestCode==302 && resultCode==RESULT_OK){data?.data?.let{loadCustomFont(it)};return}
        if(requestCode==301 && resultCode==RESULT_OK) {
            val clip=data?.clipData
            if(clip!=null && clip.itemCount>1) {
                val uris=(0 until clip.itemCount.coerceAtMost(80)).map{clip.getItemAt(it).uri}
                readSelectedImages(uris)
            } else (data?.data ?: clip?.takeIf{it.itemCount>0}?.getItemAt(0)?.uri)?.let{openUri(it)}
        }
    }
    private fun fileName(uri:Uri):String {
        var name=uri.lastPathSegment?:"문서"
        runCatching { contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME),null,null,null)?.use {c->if(c.moveToFirst())name=c.getString(0)?:name} }
        return name
    }
    private fun rememberLibrary(uri:Uri) {
        val arr=try{JSONArray(prefs.getString("library","[]"))}catch(_:Exception){JSONArray()}
        val all=(0 until arr.length()).map{arr.optString(it)}.filter{it!=uri.toString()}.toMutableList()
        all.add(0,uri.toString())
        prefs.edit().putString("library",JSONArray(all.take(80)).toString()).apply()
    }
    private fun showLibrary() {
        val arr=try{JSONArray(prefs.getString("library","[]"))}catch(_:Exception){JSONArray()}
        val uris=(0 until arr.length()).mapNotNull {runCatching{Uri.parse(arr.getString(it))}.getOrNull()}
        val options=(listOf("＋ 휴대폰 파일 선택")+uris.map{runCatching{fileName(it)}.getOrDefault("⚠ 다시 연결 필요") }).toTypedArray()
        AlertDialog.Builder(this).setTitle("내 서재").setItems(options){_,which->if(which==0)pickFiles() else openUri(uris[which-1])}
            .setNeutralButton("작품 찾기"){_,_->showBookSearch()}.setNegativeButton("닫기",null).show()
    }
    private fun showSearch() {
        if(raw.isEmpty()){toast("먼저 소설을 여세요");return}
        val input=EditText(this).apply{hint="본문에서 찾을 단어";setSingleLine(true)}
        val box=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(24,4,24,0);addView(input)}
        AlertDialog.Builder(this).setTitle("본문 전체 검색").setView(box)
            .setPositiveButton("검색"){_,_-> searchWord(input.text.toString())}
            .setNegativeButton("취소",null).show()
    }
    private fun searchWord(word:String) {
        if(word.isBlank())return
        val hits=mutableListOf<Int>();var start=0;var count=0
        while(start<raw.length){val i=raw.indexOf(word,start,ignoreCase=true);if(i<0)break;if(hits.size<200)hits.add(i);count++;start=i+word.length}
        if(hits.isEmpty()){toast("검색 결과가 없습니다");return}
        val labels=hits.map {pos->val a=(pos-22).coerceAtLeast(0);val b=(pos+word.length+36).coerceAtMost(raw.length)
            "${pos+1}: "+raw.substring(a,b).replace('\n',' ')}.toTypedArray()
        AlertDialog.Builder(this).setTitle("검색 '$word' · ${count}건 (목록 최대 200건)")
            .setItems(labels){_,which->jumpTo(hits[which],true);highlightWord(hits[which],word.length)}
            .setNegativeButton("닫기",null).show()
    }
    private fun highlightWord(index:Int,length:Int){
        val styled=SpannableString(raw)
        styled.setSpan(BackgroundColorSpan(Color.rgb(145,105,38)),index,(index+length).coerceAtMost(raw.length),Spanned.SPAN_EXCLUSIVE_EXCLUSIVE)
        text.text=styled
        text.post{jumpTo(index,false)}
    }
    private fun bookmark() {
        if(docKey.isEmpty())return
        val id="bookmark_${digest(docKey)}"
        val positions=prefs.getString(id,"")?.split(",")?.mapNotNull{it.toIntOrNull()}?.toMutableList() ?: mutableListOf()
        positions.add(readingOffset)
        prefs.edit().putString(id,positions.distinct().joinToString(",")).apply();toast("책갈피 추가됨")
    }
    private fun showBookmarks() {
        if(docKey.isEmpty())return
        val positions=prefs.getString("bookmark_${digest(docKey)}","")?.split(",")?.mapNotNull{it.toIntOrNull()}?: emptyList()
        if(positions.isEmpty()){toast("책갈피가 없습니다");return}
        AlertDialog.Builder(this).setTitle("책갈피")
            .setItems(positions.map {"${(it.toFloat()/raw.length*100).toInt()}% · "+raw.substring(it.coerceIn(0,raw.length),(it+40).coerceAtMost(raw.length)).replace('\n',' ')}.toTypedArray())
            {_,which->jumpTo(positions[which],true)}.show()
    }
    private fun showChapters() {
        if(raw.isEmpty())return
        val regex=Regex("(?m)^\\s*(?:제\\s*\\d+\\s*[화장권]|\\d+\\s*화|Chapter\\s+\\d+|EP[.]?\\s*\\d+)[^\\n]{0,45}$",RegexOption.IGNORE_CASE)
        val hits=regex.findAll(raw).take(3000).toList()
        if(hits.isEmpty()){toast("자동 인식된 목차가 없습니다");return}
        AlertDialog.Builder(this).setTitle("목차 · ${hits.size}개")
            .setItems(hits.map{it.value.trim()}.toTypedArray()){_,which->jumpTo(hits[which].range.first,true)}.show()
    }
    private fun showMore() {
        val items=arrayOf("책갈피 목록","이전 읽던 곳으로","현재 듣는 위치로","듣기 위치 따라가기 켜기/끄기","발음·언어 설정","취침 타이머","빠른 탐색","파일 인코딩 변경","압축파일 이름 인코딩","작품 인터넷 검색","TTS 완전히 종료")
        AlertDialog.Builder(this).setTitle("독서 도구").setItems(items){_,n->when(n){
            0->showBookmarks();1->if(beforeJump>=0)jumpTo(beforeJump,false);2->jumpTo(latestTtsOffset,true)
            3->{followTts=!followTts;toast("듣는 위치 따라가기: ${if(followTts)"켬" else "끔"}")}
            4->showSpeechSettings();5->showSleepTimer();6->showQuickNav();7->showEncoding();8->showZipEncoding();9->showBookSearch()
            10-> {startService(Intent(this,ReaderTtsService::class.java).setAction(ReaderTtsService.ACTION_STOP));ttsPlaying=false}
        }}.show()
    }
    private fun showSleepTimer(){
        val choices=arrayOf("타이머 끄기","10분 후","20분 후","30분 후","60분 후")
        val minutes=intArrayOf(0,10,20,30,60)
        AlertDialog.Builder(this).setTitle("취침 타이머 · 문장 종료 후 정지")
            .setItems(choices){_,n->
                startService(Intent(this,ReaderTtsService::class.java)
                    .setAction(ReaderTtsService.ACTION_TIMER)
                    .putExtra("minutes",minutes[n]))
                toast(if(n==0)"취침 타이머를 해제했습니다" else "${minutes[n]}분 뒤 듣기가 정지됩니다")
            }.show()
    }
    private fun showQuickNav(){if(raw.isEmpty())return
        val items=(0..20).map{"${it*5}%"}.toTypedArray()
        AlertDialog.Builder(this).setTitle("빠른 탐색 · 5% 간격").setItems(items){_,n->jumpTo(raw.length*n/20,true)}.show()
    }
    private fun showEncoding() {
        val choices=arrayOf("auto","UTF-8","x-windows-949","EUC-KR","UTF-16LE","UTF-16BE")
        AlertDialog.Builder(this).setTitle("TXT 인코딩 (다시 열기 필요)").setSingleChoiceItems(choices,choices.indexOf(prefs.getString("encoding_${digest(openedUri?.toString()?:docTitle)}","auto"))){d,n->
            prefs.edit().putString("encoding_${digest(openedUri?.toString()?:docTitle)}",choices[n]).apply();d.dismiss();openedUri?.let {openUri(it)}
        }.show()
    }
    private fun showZipEncoding() {
        val choices=arrayOf("x-windows-949","EUC-KR","UTF-8")
        AlertDialog.Builder(this).setTitle("ZIP 내부 파일명 인코딩").setSingleChoiceItems(choices,choices.indexOf(prefs.getString("zip_encoding","x-windows-949"))){d,n->
            prefs.edit().putString("zip_encoding",choices[n]).apply();d.dismiss();openedUri?.let{openUri(it)}
        }.show()
    }
    private fun showBookSearch() {
        val suggested=docTitle.substringBeforeLast('.').replace(Regex("[\\[\\]()]|\\d+[-~]\\d+화|(?i)완결|텍본|完")," ").replace('_',' ').trim()
        val input=EditText(this).apply {setText(suggested);selectAll();hint="소설 제목"}
        AlertDialog.Builder(this).setTitle("작품 정보 인터넷 검색").setView(input)
            .setPositiveButton("네이버 검색"){_,_->browserSearch("https://search.naver.com/search.naver?query=",input.text.toString())}
            .setNeutralButton("구글 검색"){_,_->browserSearch("https://www.google.com/search?q=",input.text.toString()+" 소설 줄거리 평점")}
            .setNegativeButton("취소",null).show()
    }
    private fun browserSearch(base:String,query:String) {
        val url=base+Uri.encode(query)
        startActivity(Intent(Intent.ACTION_VIEW,Uri.parse(url)))
    }
    private fun showSpeechSettings() {
        val panel=LinearLayout(this).apply {orientation=LinearLayout.VERTICAL;setPadding(26,10,26,4)}
        fun check(label:String,key:String,default:Boolean) {
            val c=CheckBox(this).apply {text=label;isChecked=prefs.getBoolean(key,default);setOnCheckedChangeListener{_,v->prefs.edit().putBoolean(key,v).apply()}}
            panel.addView(c)
        }
        check("영어(라틴 문자) 읽기", "read_latin",true)
        check("한자 읽기 (엔진 기본 발음)", "read_hanja",true)
        check("일본어 문자 읽기", "read_japanese",true)
        panel.addView(TextView(this).apply{text="발음 교정: 한 줄에 '원문=>읽을말' 형식";setPadding(0,12,0,5)})
        val edit=EditText(this).apply{minLines=3;maxLines=7;setText(prefs.getString("rules","S급=>에스급\nLv.=>레벨 "))}
        panel.addView(edit)
        AlertDialog.Builder(this).setTitle("언어·발음 설정").setView(ScrollView(this).apply{addView(panel)})
            .setPositiveButton("저장"){_,_->prefs.edit().putString("rules",edit.text.toString()).apply();toast("다음 문장부터 반영됩니다")}
            .setNegativeButton("취소",null).show()
    }
    private fun showSettings() {
        val labels=arrayOf("글꼴 선택","글자 크기 슬라이더","글자 작게","글자 크게","줄 간격 좁게","줄 간격 넓게","문단 여백/좌우 여백","스크롤 느리게","스크롤 빠르게","밝기 낮게","밝기 높게","화면 배경 3종","TTS 속도 느리게","TTS 속도 빠르게","언어·발음 설정","볼륨키 페이지 넘기기 켜기/끄기","화면 켜짐 유지")
        AlertDialog.Builder(this).setTitle("읽기 설정").setItems(labels){_,n->when(n){
            0->showFontPicker()
            1->showFontSizeSlider()
            2,3->{val v=(prefs.getFloat("font_size",24f)+if(n==2)-2f else 2f).coerceIn(12f,54f);prefs.edit().putFloat("font_size",v).apply();applyTypography()}
            4,5->{val v=(prefs.getFloat("line_space",12f)+if(n==4)-3f else 3f).coerceIn(0f,48f);prefs.edit().putFloat("line_space",v).apply();applyTypography()}
            6->showMargins()
            7,8->prefs.edit().putFloat("scroll_px_second",(prefs.getFloat("scroll_px_second",30f)+if(n==7)-5f else 5f).coerceIn(4f,240f)).apply()
            9,10->{brightnessValue=(brightnessValue+if(n==9)-.1f else .1f).coerceIn(.02f,1f);applyBrightness();prefs.edit().putFloat("brightness",brightnessValue).apply()}
            11->{prefs.edit().putInt("theme",(prefs.getInt("theme",0)+1)%3).apply();applyTheme()}
            12,13->{val v=(prefs.getFloat("tts_rate",1f)+if(n==12)-.1f else .1f).coerceIn(.5f,2f);prefs.edit().putFloat("tts_rate",v).apply();toast("TTS 속도 ${"%.1f".format(v)}배")}
            14->showSpeechSettings()
            15->{val v=!prefs.getBoolean("volume_page",false);prefs.edit().putBoolean("volume_page",v).apply();toast("볼륨키 페이지 넘기기: ${if(v)"켜짐" else "꺼짐"}")}
            16->{val v=!prefs.getBoolean("keep_screen",true);prefs.edit().putBoolean("keep_screen",v).apply();updateKeepScreen()}
        };refreshFooter()}.show()
    }
    private fun showFontPicker(){
        AlertDialog.Builder(this).setTitle("글꼴 선택 · 설치된 글꼴에 따라 표시 차이")
            .setSingleChoiceItems(fontFamilies,prefs.getInt("font_family",0)){dialog,n->
                dialog.dismiss()
                if(n==4) pickFont() else {prefs.edit().putInt("font_family",n).apply();applyTypography()}
            }
            .setNegativeButton("닫기",null).show()
    }
    private fun pickFont(){
        val i=Intent(Intent.ACTION_OPEN_DOCUMENT).apply {addCategory(Intent.CATEGORY_OPENABLE);type="*/*";putExtra(Intent.EXTRA_MIME_TYPES,arrayOf("font/ttf","font/otf","application/octet-stream","application/x-font-ttf"))}
        startActivityForResult(i,302)
    }
    private fun loadCustomFont(uri:Uri){
        executor.execute {
            try{
                val name=fileName(uri)
                if(!name.endsWith(".ttf",true) && !name.endsWith(".otf",true)) throw IOException("TTF 또는 OTF 파일만 선택하세요")
                val dest=File(filesDir,"reader-custom-font")
                val staged=File(filesDir,"reader-custom-font.tmp")
                try {
                    contentResolver.openInputStream(uri)?.use { input->
                        staged.outputStream().use{out->val buf=ByteArray(8192);var size=0L;while(true){val n=input.read(buf);if(n<0)break;size+=n;if(size>15L*1024*1024)throw IOException("글꼴 파일은 15MB 이하여야 합니다");out.write(buf,0,n)}}
                    } ?: throw IOException("파일을 열 수 없습니다")
                    // Reject malformed SFNT headers before asking the OS to load the font.
                    val magic=staged.inputStream().use { inp -> ByteArray(4).also { if(inp.read(it)!=4) throw IOException("글꼴 헤더가 없습니다") } }
                    val signature=magic.joinToString("") { "%02x".format(it.toInt() and 255) }
                    if(signature !in setOf("00010000","4f54544f","74727565","74746366")) throw IOException("올바른 TTF/OTF 글꼴이 아닙니다")
                    val checked=Typeface.createFromFile(staged)
                    val replacement=File(filesDir,"reader-custom-font.new")
                    staged.copyTo(replacement,overwrite=true)
                    if(!replacement.renameTo(dest)) {
                        val backup=File(filesDir,"reader-custom-font.bak")
                        if(dest.exists()) dest.copyTo(backup,overwrite=true)
                        try { replacement.copyTo(dest,overwrite=true) } catch(ex:Exception) {
                            if(backup.exists()) backup.copyTo(dest,overwrite=true)
                            throw ex
                        } finally { replacement.delete();backup.delete() }
                    }
                    cachedCustomFont=checked
                } finally { staged.delete() }
                runOnUiThread{prefs.edit().putInt("font_family",4).apply();applyTypography();toast("사용자 글꼴이 적용되었습니다")}
            }catch(ex:Exception){runOnUiThread{error("글꼴 불러오기 실패: ${ex.message}")}}
        }
    }
    private fun showFontSizeSlider(){
        val panel=LinearLayout(this).apply{orientation=LinearLayout.VERTICAL;setPadding(dp(20),dp(12),dp(20),dp(12))}
        val sample=TextView(this).apply{textSize=prefs.getFloat("font_size",24f);text="가나다라마바사 · 소설 미리보기";typeface=fontTypeface(prefs.getInt("font_family",0))}
        val value=TextView(this).apply{text="글자 크기 ${prefs.getFloat("font_size",24f).toInt()}sp"}
        val slider=SeekBar(this).apply{max=42;progress=(prefs.getFloat("font_size",24f).toInt()-12).coerceIn(0,42)}
        slider.setOnSeekBarChangeListener(object:SeekBar.OnSeekBarChangeListener{
            override fun onProgressChanged(seek:SeekBar?,progress:Int,fromUser:Boolean){value.text="글자 크기 ${12+progress}sp";sample.textSize=(12+progress).toFloat()}
            override fun onStartTrackingTouch(seek:SeekBar?){}
            override fun onStopTrackingTouch(seek:SeekBar?){}
        })
        panel.addView(value);panel.addView(slider);panel.addView(sample)
        AlertDialog.Builder(this).setTitle("글자 크기 조절").setView(panel)
            .setPositiveButton("적용"){_,_->prefs.edit().putFloat("font_size",(12+slider.progress).toFloat()).apply();applyTypography()}
            .setNegativeButton("취소",null).show()
    }
    private fun showMargins(){
        val choices=arrayOf("좁게 · 8dp","보통 · 20dp","넓게 · 32dp","매우 넓게 · 44dp")
        val values=intArrayOf(8,20,32,44)
        AlertDialog.Builder(this).setTitle("좌우 여백").setSingleChoiceItems(choices,values.indexOf(prefs.getInt("margin_dp",20))){d,n->prefs.edit().putInt("margin_dp",values[n]).apply();applyTypography();d.dismiss()}.show()
    }
    private fun updateKeepScreen(){
        if(autoScroll && prefs.getBoolean("keep_screen",true)) window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        else window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
    }
    private fun toast(s:String)=Toast.makeText(this,s,Toast.LENGTH_LONG).show()
    private fun error(s:String){header.text="뀨 리더 · 오류";AlertDialog.Builder(this).setTitle("안내").setMessage(s).setPositiveButton("확인",null).show()}
    override fun onPause() {super.onPause();savePosition();autoScroll=false;updateKeepScreen()}
    override fun onStop(){ui.removeCallbacks(tick);super.onStop()}
    override fun onStart(){super.onStart();lastFrameTime=0L;ui.removeCallbacks(tick);ui.post(tick)}
    override fun onDestroy() {ui.removeCallbacks(tick);unregisterReceiver(ttsReceiver);executor.shutdown();super.onDestroy()}
    override fun onKeyDown(keyCode:Int,event:KeyEvent?):Boolean {
        if((keyCode==KeyEvent.KEYCODE_VOLUME_UP || keyCode==KeyEvent.KEYCODE_VOLUME_DOWN) && prefs.getBoolean("volume_page",false) && raw.isNotEmpty()){
            jumpTo((readingOffset+if(keyCode==KeyEvent.KEYCODE_VOLUME_DOWN)800 else -800).coerceIn(0,raw.length),true);return true
        }
        return super.onKeyDown(keyCode,event)
    }
}
