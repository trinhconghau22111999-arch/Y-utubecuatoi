package com.ytbrowser.app

import android.Manifest
import android.annotation.SuppressLint
import android.media.projection.MediaProjectionManager
import android.content.ActivityNotFoundException
import android.content.Context
import android.content.Intent
import android.content.pm.ActivityInfo
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import android.util.Log
import android.view.KeyEvent
import android.view.View
import android.view.ViewGroup
import android.webkit.JavascriptInterface
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.FrameLayout
import android.widget.ProgressBar
import android.widget.Toast
import android.speech.RecognizerIntent
import androidx.activity.result.contract.ActivityResultContracts
import java.util.Locale
import androidx.core.content.ContextCompat
import android.content.pm.PackageManager
import androidx.appcompat.app.AppCompatActivity
import java.io.ByteArrayInputStream
import java.io.BufferedReader
import java.io.InputStreamReader

class MainActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private lateinit var progressBar: ProgressBar
    private lateinit var prefs: android.content.SharedPreferences

    // --- Giữ phát nhạc/video khi tắt màn hình: xem WakeLockBridge + injectBackgroundPlaybackFix() ---
    private var wakeLock: PowerManager.WakeLock? = null
    // Đánh dấu Foreground Service đã đang chạy chưa - để KHÔNG gọi startForegroundService()
    // lặp lại mỗi 30 giây (JS renew wake lock định kỳ), tránh notification bị đăng lại nhiều
    // lần liên tục (nhìn như tự tắt/bật) và rung máy lặp lại trên một số dòng máy.
    private var playbackServiceRunning = false
    // Trạng thái phát THẬT SỰ hiện tại (cập nhật đúng lúc video bắn 'playing'/'pause'/'ended'
    // qua WakeLockBridge.acquire()/release() bên dưới) - dùng ở onPause() để biết có cần tự
    // đánh thức lại WebView và phát tiếp hay không khi app vừa ra nền.
    private var isVideoPlaying = false
    // 1 Handler DÙNG CHUNG cho việc "tự dừng rồi phát lại" ở onPause() bên dưới - tránh tạo mới
    // 1 Handler khác mỗi lần gọi (trước đây làm vậy): nếu bấm Home/mở lại app nhanh liên tiếp
    // nhiều lần, các Handler cũ vẫn còn lịch hẹn treo đó, chồng lên nhau cùng gọi phát lại - dù
    // không gây lỗi (gọi play() khi đã đang phát vô hại) nhưng lãng phí. Dùng 1 Handler cố định
    // + huỷ lịch hẹn cũ (nếu có) trước khi đặt lịch mới để chỉ còn ĐÚNG 1 lịch hẹn tại 1 thời điểm.
    private val resumePlaybackHandler = Handler(Looper.getMainLooper())
    // Đánh dấu đang trong chuỗi TỰ pause() rồi TỰ play() lại ở onPause() bên dưới (để đánh thức
    // WebView) - PHÂN BIỆT với lúc người dùng/YouTube chủ động pause thật sự. Vì tự pause() cũng
    // làm nổ sự kiện 'pause' của video y hệt pause thật -> WakeLockBridge.release() bên dưới cần
    // biết để BỎ QUA, không nhả wake lock/tắt setShowWhenLocked ngay giữa chuỗi - nếu không, có
    // một khoảng hở ngắn (trong lúc chờ tự play() lại) mà app bị hệ thống coi là "không còn giữ
    // hiển thị trên màn hình khoá" nữa, Android có thể (tuỳ máy, tuỳ thời điểm) tranh thủ thu hồi
    // Surface video ngay trong khoảng hở đó trước khi play() kịp chạy lại -> phát lại thất bại.
    // Đây chính là lý do trước đây gặp kiểu lỗi "tắt màn hình lần đầu thì được, lần sau lại không
    // phát tiếp" - xác suất trúng khoảng hở đó khác nhau mỗi lần, không phải lần nào cũng dính.
    private var isAutoPausing = false

    // --- Screen Recording ---
    private var screenRecordIndex = 1          // tăng dần: y.1, y.2, y.3 ...
    private var isRecording       = false
    private var recordResultCode  = -1
    private var recordResultData: Intent? = null

    private val mediaProjectionLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK && result.data != null) {
            recordResultCode = result.resultCode
            recordResultData = result.data
            startScreenRecord()
        }
    }

    // --- Hỗ trợ fullscreen cho video HTML5 (nút phóng to trong trình phát YouTube) ---
    private var fullscreenContainer: FrameLayout? = null
    private var fullscreenView: View? = null
    private var fullscreenCallback: WebChromeClient.CustomViewCallback? = null
    private var originalOrientation: Int = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
    private var originalSystemUiVisibility: Int = 0

    // --- Tìm kiếm bằng giọng nói: nối nút mic của YouTube (Web Speech API không chạy trong
    // WebView) sang bộ nhận dạng giọng nói gốc của Android ---
    private val voiceSearchLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) { result ->
        if (result.resultCode == RESULT_OK) {
            val spokenText = result.data
                ?.getStringArrayListExtra(RecognizerIntent.EXTRA_RESULTS)
                ?.firstOrNull()
            if (!spokenText.isNullOrBlank()) {
                performYoutubeSearch(spokenText)
            }
        }
    }

    private val micPermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            launchVoiceRecognizer()
        } else {
            Toast.makeText(
                this,
                "Cần cấp quyền micro để dùng tìm kiếm bằng giọng nói",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // Danh sách domain cần chặn, đọc từ assets/blocklist.txt
    private val blockedHosts: MutableSet<String> = HashSet()

    // Các domain được PHÉP điều hướng tới (đây là "trình duyệt chuyên biệt", không phải browser đa năng)
    private val allowedHostSuffixes = listOf(
        "youtube.com",
        "youtu.be",
        "ytimg.com",
        "googlevideo.com",
        "ggpht.com",
        "gstatic.com",
        "accounts.google.com"
    )

    private val START_URL = "https://m.youtube.com/"

    // Vị trí/kích thước ô vuông tải xuống (xem addDownloadOverlayButton) - chỉnh 3 số này (dp)
    // nếu ô vuông chưa đè khớp lên đúng vị trí nút Cài đặt thật trên máy đang dùng.
    private val DOWNLOAD_BTN_SIZE_DP = 44
    private val DOWNLOAD_BTN_TOP_DP = 8
    private val DOWNLOAD_BTN_RIGHT_DP = 48
    // Dịch nút sang PHẢI thêm 1 khoảng bằng đúng 1/2 chiều ngang của icon - trừ bớt vào lề phải
    // gốc (rightMargin nhỏ hơn = nằm gần mép phải hơn = dịch sang phải). Xem cách dùng ở
    // addDownloadOverlayButton() bên dưới.
    private val DOWNLOAD_BTN_RIGHT_SHIFT_DP = DOWNLOAD_BTN_SIZE_DP / 2

    @SuppressLint("SetJavaScriptEnabled")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        prefs = getSharedPreferences("ytbrowser_prefs", Context.MODE_PRIVATE)

        webView = findViewById(R.id.webview)
        progressBar = findViewById(R.id.progressBar)

        loadBlocklist()
        setupWebView()

        webView.loadUrl(START_URL)
    }

    // ---- Ô vuông tải xuống, đè cố định lên vị trí nút Cài đặt của trình phát khi toàn màn hình ----
    // Trước đây tính năng tải video dùng cử chỉ vuốt 2 ngón xuống màn hình khi xoay ngang, nhưng
    // cử chỉ khó nhớ/khó bấm trúng. Thay bằng 1 ô vuông nhỏ, native Android (KHÔNG phải phần tử
    // trong WebView), được thêm làm view con CUỐI CÙNG của fullscreenContainer (xem
    // onShowCustomView bên dưới) nên nó luôn nằm TRÊN CÙNG, chặn đúng vị trí nút Cài đặt (gear)
    // của trình phát YouTube bên dưới - người dùng bấm vào đó sẽ trúng ô vuông này (kích hoạt tải
    // luôn, không mở được menu Cài đặt của YouTube nữa) thay vì bấm trúng nút Cài đặt thật.
    // Ô vuông chỉ tồn tại khi đang toàn màn hình, tự bị gỡ bỏ khi thoát toàn màn hình.
    //
    // LƯU Ý VỊ TRÍ: toạ độ nút Cài đặt của YouTube không cố định tuyệt đối giữa các máy/khổ màn
    // hình (phụ thuộc mật độ điểm ảnh, có thanh cắt tai thỏ hay không...), nên DOWNLOAD_BTN_TOP_DP
    // và DOWNLOAD_BTN_RIGHT_DP dưới đây là ước lượng ban đầu - nếu ô vuông chưa đè khớp hẳn lên
    // nút Cài đặt trên máy thật, chỉ cần chỉnh 2 số này (đơn vị dp) rồi build lại.
    private var downloadOverlayButton: View? = null

    private fun addDownloadOverlayButton(container: FrameLayout) {
        if (downloadOverlayButton != null) return
        val density = resources.displayMetrics.density
        val sizePx = (DOWNLOAD_BTN_SIZE_DP * density).toInt()
        val btn = object : View(this) {
            // Cọ vẽ mũi tên (nét liền, đầu/nối tròn cho mượt) và đầu mũi tên (tô đặc) - dùng
            // chung 1 màu xanh lá, tạo mới trong onDraw() vì kích thước view (để tính toạ độ
            // theo %) chỉ có thật khi layout xong, không có sẵn lúc khởi tạo.
            override fun onDraw(canvas: Canvas) {
                super.onDraw(canvas)
                val w = width.toFloat()
                val h = height.toFloat()
                if (w <= 0f || h <= 0f) return
                val green = Color.parseColor("#4CAF50")
                val cx = w / 2f

                val shaftPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = green
                    style = Paint.Style.STROKE
                    strokeWidth = w * 0.09f
                    strokeCap = Paint.Cap.ROUND
                }
                // Thân mũi tên: 1 đường thẳng đứng từ trên xuống gần giữa
                canvas.drawLine(cx, h * 0.20f, cx, h * 0.52f, shaftPaint)
                // Gạch chân dưới đáy (khay tải xuống) - đặc trưng của icon "download"
                canvas.drawLine(w * 0.28f, h * 0.80f, w * 0.72f, h * 0.80f, shaftPaint)

                // Đầu mũi tên: hình tam giác tô đặc, chỉa xuống
                val headPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    color = green
                    style = Paint.Style.FILL
                }
                val headHalfWidth = w * 0.20f
                val headTop = h * 0.44f
                val headTip = h * 0.66f
                val path = Path().apply {
                    moveTo(cx - headHalfWidth, headTop)
                    lineTo(cx + headHalfWidth, headTop)
                    lineTo(cx, headTip)
                    close()
                }
                canvas.drawPath(path, headPaint)
            }
        }.apply {
            // Nền ĐEN (không còn để mờ như trước) - theo đúng yêu cầu, dễ nhận ra hơn.
            setBackgroundColor(Color.BLACK)
            setWillNotDraw(false) // bắt buộc: View trơn mặc định bỏ qua onDraw() để tối ưu
            contentDescription = "Tải video"
            setOnClickListener { onDownloadButtonTapped() }
        }
        val params = FrameLayout.LayoutParams(sizePx, sizePx).apply {
            gravity = android.view.Gravity.TOP or android.view.Gravity.END
            topMargin = (DOWNLOAD_BTN_TOP_DP * density).toInt()
            // Dịch sang phải thêm 1/2 chiều ngang icon so với vị trí gốc (xem
            // DOWNLOAD_BTN_RIGHT_SHIFT_DP) - rightMargin nhỏ hơn nghĩa là nằm gần mép phải hơn.
            rightMargin = ((DOWNLOAD_BTN_RIGHT_DP - DOWNLOAD_BTN_RIGHT_SHIFT_DP) * density).toInt()
        }
        container.addView(btn, params) // thêm SAU CÙNG -> nổi trên cùng, đè lên video/nút cài đặt bên dưới
        downloadOverlayButton = btn
    }

    private fun removeDownloadOverlayButton() {
        downloadOverlayButton?.let { (it.parent as? ViewGroup)?.removeView(it) }
        downloadOverlayButton = null
    }

    private fun onDownloadButtonTapped() {
        if (isRecording) {
            Toast.makeText(this, "Đang quay rồi…", Toast.LENGTH_SHORT).show()
            return
        }
        // Bấm là tải luôn - bỏ qua bước hộp thoại xác nhận trước đây, vì bản thân ô vuông
        // này đã đóng vai trò là "nút bấm để tải".
        requestScreenRecord()
    }

    private fun requestScreenRecord() {
        if (recordResultData != null) {
            // Đã có quyền từ trước
            startScreenRecord()
        } else {
            val mpm = getSystemService(MediaProjectionManager::class.java)
            // SUA LOI (tu dung yeu cau quay UNG DUNG KHAC): tu Android 14 (API 34), goi
            // createScreenCaptureIntent() KHONG THAM SO se khien he thong hien them 1 buoc
            // moi cho nguoi dung chon "Toan bo man hinh" hay "Mot ung dung" (dung nhu anh
            // chup nguoi dung phan anh) - neu nguoi dung lo chon nham 1 app khac (vd Chrome),
            // MediaProjection se CHi quay app do thay vi quay chinh app nay, khien video
            // quay ra sai/rong. Tu Android 14 tro len, ep thang ve "quay toan man hinh thiet
            // bi" bang MediaProjectionConfig.createConfigForDefaultDisplay() de bo qua han
            // buoc chon ung dung nay, giu nguyen hanh vi quay man hinh nhu truoc.
            val captureIntent = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
                mpm.createScreenCaptureIntent(
                    android.media.projection.MediaProjectionConfig.createConfigForDefaultDisplay()
                )
            } else {
                mpm.createScreenCaptureIntent()
            }
            mediaProjectionLauncher.launch(captureIntent)
        }
    }

    private fun startScreenRecord() {
        val data = recordResultData ?: return
        isRecording = true

        // Phát 16x qua JS - dùng forceSpeed() để đồng bộ với biến desiredSpeed trong
        // injectSpeedMemory(), tránh vòng lặp tốc độ ở đó kéo ngược lại sau này (xem giải
        // thích chi tiết tại khai báo window.__ytbrowser_forceSpeed).
        webView.evaluateJavascript(
            "(function(){ if (window.__ytbrowser_forceSpeed) { window.__ytbrowser_forceSpeed(16); } else { var v=document.querySelector('video'); if(v) v.playbackRate=16; } })();", null
        )

        // Inject JS theo dõi video pause/end -> điều khiển recorder
        injectRecordSyncBridge()

        // Khởi động service quay
        val intent = Intent(this, ScreenRecordService::class.java).apply {
            action = ScreenRecordService.ACTION_START
            putExtra(ScreenRecordService.EXTRA_RESULT_CODE, recordResultCode)
            putExtra(ScreenRecordService.EXTRA_RESULT_DATA, data)
            putExtra(ScreenRecordService.EXTRA_FILE_INDEX, screenRecordIndex)
        }
        startForegroundService(intent)

        Toast.makeText(this, "Bắt đầu quay — sẽ lưu vào Downloads/vdy/y.$screenRecordIndex.locked", Toast.LENGTH_SHORT).show()
        screenRecordIndex++
    }

    private fun injectRecordSyncBridge() {
        // SỬA LỖI (quay màn hình không tự dừng khi video kết thúc): bản trước đây chỉ
        // querySelector('video') + gắn listener 'ended' ĐÚNG 1 LẦN vào ĐÚNG 1 phần tử <video>
        // tại thời điểm bắt đầu quay, rồi khoá lại bằng cờ window.__ytbrowser_record_sync
        // (không bao giờ chạy lại). Có 2 tình huống khiến 'ended' không bao giờ bắn tới nữa:
        //   1) Lúc bắt đầu quay, thẻ <video> chưa kịp render xong (document.querySelector trả
        //      về null) -> hàm return sớm nhưng cờ đã bị đánh dấu true -> vĩnh viễn không thử
        //      gắn lại được nữa dù video xuất hiện ngay sau đó.
        //   2) YouTube thay THẺ <video> khác (quảng cáo giữa video, đổi chất lượng, chuyển
        //      sang video tiếp theo...) - listener cũ đã gắn vào phần tử bị gỡ bỏ, phần tử
        //      <video> MỚI (đang phát thật) không hề có listener nào -> sự kiện 'ended' thật
        //      sự không được ghi nhận -> service quay không bao giờ nhận lệnh STOP.
        // Cách sửa: dùng đúng pattern đã áp dụng thành công cho bindVideo() ở WakeLockBridge
        // phía dưới - hàm bindRecordVideo() được gọi lại định kỳ (setInterval) VÀ mỗi khi DOM
        // thay đổi (MutationObserver), tự tìm <video> hiện tại và chỉ gắn listener nếu phần tử
        // ĐÓ chưa từng được gắn (cờ __ytbrowser_record_bound đặt TRÊN CHÍNH phần tử video, không
        // phải trên window) - nhờ vậy nếu <video> bị thay mới, lần bindRecordVideo() kế tiếp
        // (chậm nhất 1 giây sau, hoặc ngay khi MutationObserver bắt được) sẽ tự gắn lại đúng
        // phần tử mới, không phụ thuộc video có tồn tại sẵn lúc bắt đầu quay hay không.
        val js = """
            (function() {
                function bindRecordVideo() {
                    var v = document.querySelector('video');
                    if (!v) return;
                    if (v.__ytbrowser_record_bound) return;
                    v.__ytbrowser_record_bound = true;
                    v.addEventListener('pause', function() {
                        if (window.RecordBridge) RecordBridge.onVideoPause();
                    });
                    v.addEventListener('play', function() {
                        if (window.RecordBridge) RecordBridge.onVideoResume();
                    });
                    v.addEventListener('ended', function() {
                        if (window.RecordBridge) RecordBridge.onVideoEnded();
                    });
                }

                bindRecordVideo();

                // Chỉ khởi tạo interval + observer 1 LẦN (dùng cờ toàn cục ở đây là hợp lý vì
                // đây chỉ là "cơ chế theo dõi", không phải bản thân việc gắn listener) - tránh
                // startScreenRecord() gọi injectRecordSyncBridge() nhiều lần (quay nhiều đoạn
                // liên tiếp) tạo ra nhiều interval/observer chồng lên nhau.
                if (!window.__ytbrowser_record_sync) {
                    window.__ytbrowser_record_sync = true;
                    setInterval(bindRecordVideo, 1000);
                    var mo = new MutationObserver(bindRecordVideo);
                    mo.observe(document.body, { childList: true, subtree: true });
                }
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
        webView.addJavascriptInterface(RecordBridge(), "RecordBridge")
    }

    inner class RecordBridge {
        @JavascriptInterface
        fun onVideoPause() {
            if (!isRecording) return
            startService(Intent(this@MainActivity, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_PAUSE
            })
        }
        @JavascriptInterface
        fun onVideoResume() {
            if (!isRecording) return
            startService(Intent(this@MainActivity, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_RESUME
            })
        }
        @JavascriptInterface
        fun onVideoEnded() {
            if (!isRecording) return
            isRecording = false
            startService(Intent(this@MainActivity, ScreenRecordService::class.java).apply {
                action = ScreenRecordService.ACTION_STOP
            })
            runOnUiThread {
                // Trả lại tốc độ 1x - dùng forceSpeed() để desiredSpeed cũng được cập nhật,
                // tránh bị vòng lặp tốc độ kéo ngược về 16x (xem __ytbrowser_forceSpeed).
                webView.evaluateJavascript(
                    "(function(){ if (window.__ytbrowser_forceSpeed) { window.__ytbrowser_forceSpeed(1); } else { var v=document.querySelector('video'); if(v) v.playbackRate=1; } })();", null
                )
                Toast.makeText(this@MainActivity, "Đã lưu Downloads/vdy/y.${screenRecordIndex - 1}.locked (đã mã hoá)", Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun loadBlocklist() {
        try {
            val input = assets.open("blocklist.txt")
            val reader = BufferedReader(InputStreamReader(input))
            reader.forEachLine { line ->
                val trimmed = line.trim()
                if (trimmed.isNotEmpty() && !trimmed.startsWith("#")) {
                    blockedHosts.add(trimmed.lowercase())
                }
            }
            reader.close()
        } catch (e: Exception) {
            Log.e("YTBrowser", "Khong doc duoc blocklist.txt", e)
        }
    }

    private fun isHostBlocked(host: String?): Boolean {
        if (host == null) return false
        val h = host.lowercase()
        for (blocked in blockedHosts) {
            if (h == blocked || h.endsWith(".$blocked")) return true
        }
        return false
    }

    private fun isHostAllowed(host: String?): Boolean {
        if (host == null) return false
        val h = host.lowercase()
        for (suffix in allowedHostSuffixes) {
            if (h == suffix || h.endsWith(".$suffix")) return true
        }
        return false
    }

    @SuppressLint("SetJavaScriptEnabled", "JavascriptInterface")
    private fun setupWebView() {
        val settings = webView.settings
        settings.javaScriptEnabled = true
        settings.domStorageEnabled = true
        settings.databaseEnabled = true
        settings.mediaPlaybackRequiresUserGesture = false
        settings.loadWithOverviewMode = true
        settings.useWideViewPort = true
        settings.cacheMode = android.webkit.WebSettings.LOAD_DEFAULT
        // Giữ user-agent MẶC ĐỊNH của thiết bị - không gán/ghi đè gì ở đây (dòng gán
        // settings.userAgentString = settings.userAgentString cũ chỉ là gán chính nó
        // cho chính nó, không có tác dụng gì, nên bỏ hẳn - WebView vốn đã tự dùng UA mặc
        // định nếu không đụng vào thuộc tính này).

        webView.addJavascriptInterface(SpeedBridge(), "AndroidSpeed")
        webView.addJavascriptInterface(QualityBridge(), "AndroidQuality")
        webView.addJavascriptInterface(VoiceBridge(), "AndroidVoice")
        webView.addJavascriptInterface(WakeLockBridge(), "AndroidWakeLock")
        webView.addJavascriptInterface(NavBridge(), "AndroidNav")

        webView.webViewClient = object : WebViewClient() {

            // Chặn điều hướng ra ngoài YouTube -> giữ đúng vai trò "trình duyệt chuyên biệt"
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val uri = request.url
                val host = uri.host
                if (isHostBlocked(host)) return true // chặn hẳn, không load
                if (!isHostAllowed(host)) {
                    // Domain lạ (vd link ngoài trong mô tả video) -> vẫn cho mở trong app,
                    // nếu bạn muốn chặn hẳn thì đổi return false -> return true ở đây.
                    return false
                }
                return false
            }

            // Chặn quảng cáo mạng theo domain blocklist ở tầng network request
            override fun shouldInterceptRequest(
                view: WebView,
                request: WebResourceRequest
            ): WebResourceResponse? {
                val host = request.url.host
                if (isHostBlocked(host)) {
                    return WebResourceResponse(
                        "text/plain",
                        "utf-8",
                        ByteArrayInputStream(ByteArray(0))
                    )
                }
                return super.shouldInterceptRequest(view, request)
            }

            override fun onPageStarted(view: WebView, url: String, favicon: android.graphics.Bitmap?) {
                super.onPageStarted(view, url, favicon)
                progressBar.visibility = View.VISIBLE
            }

            override fun onPageFinished(view: WebView, url: String) {
                super.onPageFinished(view, url)
                progressBar.visibility = View.GONE
                injectAdSkipAndUiCleanup()
                injectSpeedMemory()
                injectQualityMemory()
                injectVoiceSearchBridge()
                injectBackgroundPlaybackFix()
                injectBackgroundColor()
                injectHomeLogoRestartHook()
            }
        }

        webView.setOnKeyListener { _, keyCode, event ->
            if (keyCode == KeyEvent.KEYCODE_BACK && event.action == KeyEvent.ACTION_UP) {
                if (fullscreenView != null) {
                    webView.webChromeClient?.onHideCustomView()
                    true
                } else if (webView.canGoBack()) {
                    handleBackToPossibleHome()
                } else false
            } else false
        }

        // Bắt buộc phải có WebChromeClient thì nút phóng to (fullscreen) của trình phát
        // video HTML5 trên YouTube mới hoạt động - nếu không có, nút này sẽ không phản hồi.
        webView.webChromeClient = object : WebChromeClient() {
            override fun onShowCustomView(view: View, callback: CustomViewCallback) {
                if (fullscreenView != null) {
                    callback.onCustomViewHidden()
                    return
                }
                fullscreenView = view
                fullscreenCallback = callback
                originalOrientation = requestedOrientation
                originalSystemUiVisibility = window.decorView.systemUiVisibility

                val decor = window.decorView as ViewGroup
                fullscreenContainer = FrameLayout(this@MainActivity).apply {
                    layoutParams = ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                    setBackgroundColor(android.graphics.Color.BLACK)
                    addView(
                        view,
                        FrameLayout.LayoutParams(
                            ViewGroup.LayoutParams.MATCH_PARENT,
                            ViewGroup.LayoutParams.MATCH_PARENT
                        )
                    )
                }
                decor.addView(
                    fullscreenContainer,
                    ViewGroup.LayoutParams(
                        ViewGroup.LayoutParams.MATCH_PARENT,
                        ViewGroup.LayoutParams.MATCH_PARENT
                    )
                )
                webView.visibility = View.GONE

                // Thêm SAU CÙNG (khi fullscreenContainer đã có trong decor) để ô vuông tải xuống
                // nổi trên cùng, đè cố định lên vị trí nút Cài đặt của trình phát - xem
                // addDownloadOverlayButton() để biết lý do/cách chỉnh vị trí.
                fullscreenContainer?.let { addDownloadOverlayButton(it) }

                hideSystemUi()
                // Cho phép người dùng xoay tự do cả dọc lẫn ngang khi đang xem fullscreen
                requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_SENSOR
            }

            override fun onHideCustomView() {
                removeDownloadOverlayButton()
                val decor = window.decorView as ViewGroup
                fullscreenContainer?.let { decor.removeView(it) }
                fullscreenContainer = null
                fullscreenView = null
                fullscreenCallback?.onCustomViewHidden()
                // Nếu đang quay thì dừng và reset tốc độ khi thoát fullscreen
                if (isRecording) {
                    isRecording = false
                    startService(Intent(this@MainActivity, ScreenRecordService::class.java).apply {
                        action = ScreenRecordService.ACTION_STOP
                    })
                    webView.evaluateJavascript(
                        "(function(){ if (window.__ytbrowser_forceSpeed) { window.__ytbrowser_forceSpeed(1); } else { var v=document.querySelector('video'); if(v) v.playbackRate=1; } })();", null
                    )
                    Toast.makeText(this@MainActivity, "Quay dừng — đã lưu Downloads/vdy/y.${screenRecordIndex - 1}.locked (đã mã hoá)", Toast.LENGTH_SHORT).show()
                }
                fullscreenCallback = null

                webView.visibility = View.VISIBLE
                window.decorView.systemUiVisibility = originalSystemUiVisibility
                requestedOrientation = originalOrientation
            }
        }
    }

    @Suppress("DEPRECATION")
    private fun hideSystemUi() {
        window.decorView.systemUiVisibility = (
            View.SYSTEM_UI_FLAG_LAYOUT_STABLE
                or View.SYSTEM_UI_FLAG_LAYOUT_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_LAYOUT_FULLSCREEN
                or View.SYSTEM_UI_FLAG_HIDE_NAVIGATION
                or View.SYSTEM_UI_FLAG_FULLSCREEN
                or View.SYSTEM_UI_FLAG_IMMERSIVE_STICKY
            )
    }

    // ---- Tìm kiếm bằng giọng nói: gọi từ JS khi người dùng bấm nút mic trên YouTube ----
    private fun launchVoiceRecognizer() {
        if (ContextCompat.checkSelfPermission(this, Manifest.permission.RECORD_AUDIO)
            != PackageManager.PERMISSION_GRANTED
        ) {
            micPermissionLauncher.launch(Manifest.permission.RECORD_AUDIO)
            return
        }

        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
            putExtra(RecognizerIntent.EXTRA_PROMPT, "Nói để tìm kiếm trên YouTube")
        }
        try {
            voiceSearchLauncher.launch(intent)
        } catch (e: ActivityNotFoundException) {
            Toast.makeText(
                this,
                "Thiết bị này không hỗ trợ nhận dạng giọng nói",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun performYoutubeSearch(query: String) {
        // Điều hướng thẳng tới trang kết quả tìm kiếm - đáng tin cậy hơn việc cố gắng
        // điền vào ô search của giao diện YouTube (giao diện SPA có thể đổi cấu trúc DOM).
        val url = "https://m.youtube.com/results?search_query=" + Uri.encode(query)
        webView.loadUrl(url)
    }

    inner class VoiceBridge {
        @JavascriptInterface
        fun startVoiceSearch() {
            runOnUiThread { launchVoiceRecognizer() }
        }
    }

    // ---- Gắn nút mic "Tìm kiếm bằng giọng nói" của YouTube sang AndroidVoice ----
    // Dò theo aria-label/title thay vì class CSS, vì nhãn hỗ trợ tiếp cận (accessibility)
    // ít khi đổi giữa các bản cập nhật giao diện của YouTube.
    private fun injectVoiceSearchBridge() {
        val js = """
            (function() {
                if (window.__ytbrowser_voice_hook) return;
                window.__ytbrowser_voice_hook = true;

                var VOICE_LABELS = [
                    'tìm kiếm bằng giọng nói', 'tim kiem bang giong noi',
                    'search by voice', 'voice search', 'search with your voice'
                ];

                function textOf(el) {
                    return (
                        (el.getAttribute('aria-label') || '') + ' ' + (el.getAttribute('title') || '')
                    ).toLowerCase();
                }
                function matchesAny(text, patterns) {
                    for (var i = 0; i < patterns.length; i++) {
                        if (text.indexOf(patterns[i]) !== -1) return true;
                    }
                    return false;
                }

                function hookVoiceButtons() {
                    document.querySelectorAll('button,[role="button"],a').forEach(function(el) {
                        if (el.__ytbrowser_voice_bound) return;
                        if (!matchesAny(textOf(el), VOICE_LABELS)) return;

                        el.__ytbrowser_voice_bound = true;
                        el.addEventListener('click', function(ev) {
                            if (window.AndroidVoice) {
                                ev.preventDefault();
                                ev.stopPropagation();
                                window.AndroidVoice.startVoiceSearch();
                            }
                        }, true);
                    });
                }

                hookVoiceButtons();
                setInterval(hookVoiceButtons, 1000);
                var mo = new MutationObserver(hookVoiceButtons);
                mo.observe(document.body, { childList: true, subtree: true });
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // Khởi động lại TOÀN BỘ app từ đầu - y hệt vừa mở app lần đầu (dọn sạch mọi trạng thái
    // runtime: trang WebView đang ở đâu, video đang phát, wake lock, Foreground Service, các
    // biến cờ isVideoPlaying/isAutoPausing...) - dùng khi quay lại (back) trang chủ YouTube
    // hoặc bấm logo YouTube góc trái trên để về trang chủ (xem isYoutubeHomeUrl() + NavBridge
    // bên dưới). KHÔNG dùng recreate()/webView.reload() đơn thuần vì chỉ tải lại trang web,
    // không dọn sạch Foreground Service/WakeLock/Activity đang chạy - phải khởi động lại cả
    // tiến trình (kill hẳn process cũ sau khi mở Activity mới) mới thật sự "như mới mở app".
    private fun restartAppFresh() {
        val intent = Intent(this, MainActivity::class.java).apply {
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK)
        }
        startActivity(intent)
        // Hiệu ứng chuyển cảnh "TRÁI QUA PHẢI": activity mới (app vừa mở lại) trượt vào từ bên
        // trái, activity cũ trượt ra bên phải - thay cho hiệu ứng mặc định của hệ thống (phải
        // qua trái) khi không override gì. Phải gọi NGAY sau startActivity(), trước finish().
        @Suppress("DEPRECATION")
        overridePendingTransition(R.anim.slide_in_left, R.anim.slide_out_right)
        finish()
        Runtime.getRuntime().exit(0)
    }

    // Kiểm tra 1 URL có PHẢI LÀ trang chủ YouTube hay không (path rỗng hoặc "/", bỏ qua mọi
    // trang con như /watch, /results, /feed/...) - dùng để phát hiện lúc bấm BACK lùi về đúng
    // trang chủ (xem setOnKeyListener/onBackPressed bên dưới).
    private fun isYoutubeHomeUrl(url: String?): Boolean {
        if (url.isNullOrBlank()) return false
        return try {
            val uri = Uri.parse(url)
            val host = uri.host ?: return false
            val hostIsYoutube = host == "www.youtube.com" || host == "m.youtube.com" || host == "youtube.com"
            val path = uri.path ?: ""
            hostIsYoutube && (path.isEmpty() || path == "/")
        } catch (e: Exception) {
            false
        }
    }

    // Bấm BACK mà mục tiêu lùi về (mục ngay trước trong lịch sử WebView) là trang chủ YouTube
    // -> khởi động lại app từ đầu thay vì chỉ webView.goBack() bình thường (yêu cầu: về trang
    // chủ = load lại toàn bộ app y như mới mở).
    private fun handleBackToPossibleHome(): Boolean {
        val list = webView.copyBackForwardList()
        val prevIndex = list.currentIndex - 1
        val prevUrl = if (prevIndex >= 0) list.getItemAtIndex(prevIndex)?.url else null
        if (isYoutubeHomeUrl(prevUrl)) {
            restartAppFresh()
            return true
        }
        webView.goBack()
        return true
    }

    // Cầu nối JS<->Android cho việc bấm logo YouTube (góc trái trên) - xem
    // injectHomeLogoRestartHook() bên dưới để biết JS phía nào gọi vào đây.
    inner class NavBridge {
        @JavascriptInterface
        fun homeLogoClicked() {
            runOnUiThread { restartAppFresh() }
        }
    }

    // Bấm thẳng vào logo YouTube (ytm-topbar-logo-renderer, góc trái thanh trên cùng) để về
    // trang chủ -> khởi động lại app từ đầu, y như yêu cầu ở handleBackToPossibleHome() phía
    // trên.
    // LƯU Ý: bản đầu chỉ gắn 1 listener delegated trên `document` -> KHÔNG ăn, nhiều khả năng
    // do chính SPA của YouTube cũng có sẵn listener click toàn cục để tự điều hướng nội bộ,
    // gắn TRƯỚC (từ lúc script gốc của trang chạy, trước khi JS này được tiêm vào sau
    // onPageFinished) và gọi stopPropagation() - khiến listener gắn SAU trên CÙNG 1 node
    // document/cùng capture phase không bao giờ được gọi tới nữa, bất kể có match đúng phần tử
    // hay không.
    // Sửa bằng 2 lớp:
    //  1) Gắn THẲNG vào từng phần tử logo tìm được (giống đúng cách injectVoiceSearchBridge() ở
    //     trên đã làm thành công với nút giọng nói) thay vì chỉ delegate từ document - dùng
    //     bound-flag + MutationObserver + setInterval để tự bắt lại nếu phần tử bị YouTube tạo
    //     lại (Polymer re-render).
    //  2) Gắn thêm 1 listener delegated trên `window` (không phải document) ở capture phase làm
    //     lớp dự phòng - window LUÔN được duyệt TRƯỚC document trong capture phase theo đúng thứ
    //     tự cây DOM, bất kể ai đăng ký listener trước, nên chắc chắn chạy trước mọi listener
    //     toàn cục mà YouTube có thể đã gắn trên document. Dùng stopImmediatePropagation() để
    //     chặn tuyệt đối, không cho bất kỳ listener nào khác (kể cả cùng phase) chạy tiếp.
    private fun injectHomeLogoRestartHook() {
        val js = """
            (function() {
                if (window.__ytbrowser_home_restart_hook) return;
                window.__ytbrowser_home_restart_hook = true;

                var LOGO_SELECTORS = [
                    'ytm-topbar-logo-renderer',
                    'a[href="/"]',
                    '[aria-label*="home" i]',
                    '[aria-label*="trang chủ" i]',
                    '[aria-label*="trang chu" i]'
                ];

                function fireRestart(ev) {
                    if (!window.AndroidNav) return false;
                    ev.preventDefault();
                    ev.stopImmediatePropagation();
                    window.AndroidNav.homeLogoClicked();
                    return true;
                }

                // Lớp 1: gắn thẳng vào từng phần tử logo tìm được trong DOM.
                function hookLogoElements() {
                    LOGO_SELECTORS.forEach(function(sel) {
                        try {
                            document.querySelectorAll(sel).forEach(function(el) {
                                if (el.__ytbrowser_home_bound) return;
                                el.__ytbrowser_home_bound = true;
                                el.addEventListener('click', fireRestart, true);
                            });
                        } catch (e) {}
                    });
                }
                hookLogoElements();
                setInterval(hookLogoElements, 1000);
                var mo = new MutationObserver(hookLogoElements);
                mo.observe(document.documentElement, { childList: true, subtree: true });

                // Lớp 2: dự phòng trên window (chạy trước document trong capture phase) - phòng
                // trường hợp phần tử thật nằm sâu trong Shadow DOM khiến lớp 1 không với tới, chỉ
                // còn cách bắt qua target đã được "retarget" khi sự kiện thoát ra ngoài.
                window.addEventListener('click', function(ev) {
                    var logo = ev.target && ev.target.closest && ev.target.closest(LOGO_SELECTORS.join(','));
                    if (!logo) return;
                    fireRestart(ev);
                }, true);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    override fun onBackPressed() {
        if (fullscreenView != null) {
            webView.webChromeClient?.onHideCustomView()
            return
        }
        if (webView.canGoBack()) {
            handleBackToPossibleHome()
            return
        }
        super.onBackPressed()
    }

    override fun onPause() {
        super.onPause()
        if (isVideoPlaying) {
            isAutoPausing = true
            webView.evaluateJavascript(
                "(function(){var v=document.querySelector('video'); if(v) v.pause();})();", null
            )
            resumePlaybackHandler.removeCallbacksAndMessages(null)
            resumePlaybackHandler.postDelayed({
                // Đánh thức WebView trước khi play lại - JS đơn thuần không đánh thức được
                webView.onResume()
                webView.resumeTimers()
                webView.evaluateJavascript(
                    "(function(){var v=document.querySelector('video'); if(v) v.play();})();", null
                )
                isAutoPausing = false
            }, 450L)
        }
    }

    override fun onDestroy() {
        // Nhả wake lock (nếu đang giữ) khi Activity bị đóng hẳn - tránh giữ CPU thức vô ích sau
        // khi app đã thoát, dù wake lock cũng tự hết hạn sau 60 giây nếu lỡ sót release().
        wakeLock?.let { if (it.isHeld) it.release() }
        wakeLock = null
        resumePlaybackHandler.removeCallbacksAndMessages(null)
        playbackServiceRunning = false
        stopService(Intent(this, PlaybackService::class.java))
        super.onDestroy()
    }

    // ---- 1) Tự động skip/tua quảng cáo trong video + 2) Ẩn banner "Mở trong ứng dụng YouTube" ----
    // Dò theo TEXT/aria-label (vd "Skip Ad", "Bỏ qua quảng cáo") thay vì chỉ dựa tên class cố định,
    // vì YouTube hay đổi tên class nhưng chữ hiển thị thì gần như không đổi -> tự sống sót qua
    // phần lớn lần YouTube cập nhật giao diện mà không cần sửa code.
    private fun injectAdSkipAndUiCleanup() {
        val js = """
            (function() {
                if (window.__ytbrowser_ad_loop) return;
                window.__ytbrowser_ad_loop = true;

                var SKIP_TEXT = [
                    'skip ad', 'skip ads', 'bỏ qua quảng cáo', 'bo qua quang cao',
                    // Một số dạng quảng cáo (thẻ tài trợ trong feed, không phải quảng cáo phát
                    // trong video) chỉ ghi ngắn gọn "Bỏ qua" chứ không kèm "quảng cáo" phía sau -
                    // nếu thiếu 2 dòng này thì nút của chúng không khớp được với danh sách trên.
                    'bỏ qua', 'bo qua'
                ];
                var BANNER_TEXT = [
                    'mở trong ứng dụng', 'mo trong ung dung',
                    'mở ứng dụng', 'mo ung dung',
                    'xem trong ứng dụng', 'xem trong ung dung',
                    'trải nghiệm trọn vẹn', 'trai nghiem tron ven',
                    'open in app', 'open the app', 'get the app', 'try the app', 'use app'
                ];
                // Banner quảng cáo overlay kiểu "được tài trợ" (sponsored overlay trên video)
                var SPONSORED_SELECTORS = [
                    '.ytp-ad-overlay-image', '.ytp-ad-overlay-container',
                    '.ytp-ad-overlay-slot', '[class*="ad-overlay"]',
                    '.ytp-ad-text-overlay', '.ytp-ad-player-overlay',
                    'ytm-companion-slot', 'ytm-companion-ad-renderer',
                    '.video-ads.ytp-ad-module', '[class*="companion"]'
                ];

                function textOf(el) {
                    return (((el.innerText || '') + ' ' + (el.getAttribute('aria-label') || '')) + '').toLowerCase();
                }
                function matchesAny(text, patterns) {
                    for (var i = 0; i < patterns.length; i++) {
                        if (text.indexOf(patterns[i]) !== -1) return true;
                    }
                    return false;
                }

                var fallbackBannerSelectors = [
                    'ytd-mealbar-promo-renderer', '.ytp-webview-endscreen', 'ytm-companion-slot',
                    '#mealbar-promo-renderer', '.mobile-topbar-header-app-banner',
                    'ytm-app-banner-renderer', '#app-banner', '.app-banner', '.ytm-promo-renderer',
                    'ytm-you-tube-app-promo-renderer'
                ];
                var fallbackSkipSelectors = [
                    '.ytp-ad-skip-button', '.ytp-ad-skip-button-modern',
                    '.videoAdUiSkipButton', 'button.ytp-ad-skip-button-container',
                    // Overlay/companion ads - nút "Bỏ qua" dạng banner đè lên video
                    '.ytp-ad-overlay-close-button', '.ytp-ad-overlay-slot',
                    '.ytp-ad-button-icon', '[id*="dismiss"]', '[class*="dismiss"]',
                    '.ytp-ad-skip-button-slot button', '.ytp-skip-ad-button'
                ];

                function hideAppBanners() {
                    // LOP BAO VE TUYET DOI (them do van con bao "icon kinh lup bi che/an" du
                    // 2 vong lap ben duoi da duoc sua rieng le truoc do): dinh nghia 1 ham
                    // kiem tra "phan tu nay co PHAI LA hoac co CHUA BEN TRONG nut tim kiem/
                    // logo/thanh dieu huong chinh cua YouTube hay khong" - neu co, TUYET DOI
                    // khong an/xoa no, bat ke selector quang cao nao o tren co khop trung hay
                    // khong. Day la lop chan cuoi cung, ap dung cho CA 3 cho dang go/an phan tu
                    // trong ham nay (thay vi phai doan tiep tung class/selector cu the tiep
                    // theo co the gay nham - vd selector ".mobile-topbar-header-app-banner" o
                    // tren neu vo tinh khop trung ngay chinh thanh topbar that thay vi 1 banner
                    // rieng, se xoa mat ca icon tim kiem ben trong no).
                    // Bảo vệ các phần tử UI CHÍNH của YouTube - tuyệt đối không ẩn/xoá,
                    // kể cả khi selector quảng cáo vô tình khớp trùng.
                    var PROTECTED_SELECTORS =
                        '[aria-label*="search" i], [aria-label*="tìm kiếm" i], [aria-label*="tim kiem" i], ' +
                        '[aria-label*="settings" i], [aria-label*="cài đặt" i], [aria-label*="cai dat" i], ' +
                        '[aria-label*="account" i], [aria-label*="tài khoản" i], ' +
                        '[aria-label*="menu" i], [aria-label*="more" i], ' +
                        'button#search-icon-legacy, ytd-searchbox, tp-yt-paper-icon-button#search-icon, ' +
                        'a[href*="/results?search_query"], ytm-topbar-logo-renderer, ' +
                        'ytm-topbar-menu-button-renderer, #topbar, .topbar, ytm-mobile-topbar-renderer';
                    function isSearchOrTopbarCore(el) {
                        if (!el) return false;
                        try {
                            if (el.matches && el.matches(PROTECTED_SELECTORS)) return true;
                            if (el.querySelector && el.querySelector(PROTECTED_SELECTORS)) return true;
                            // Bảo vệ thêm: nếu el nằm BÊN TRONG thanh topbar thì cũng không đụng
                            if (el.closest && el.closest('#topbar, .topbar, ytm-mobile-topbar-renderer, ytm-topbar-menu-button-renderer')) return true;
                        } catch(e) {}
                        return false;
                    }

                    // Ẩn/xoá overlay ad (banner "được tài trợ" đè lên video)
                    SPONSORED_SELECTORS.forEach(function(sel) {
                        try {
                            document.querySelectorAll(sel).forEach(function(el) {
                                el.style.display = 'none';
                            });
                        } catch(e) {}
                    });

                    fallbackBannerSelectors.forEach(function(sel) {
                        try {
                            document.querySelectorAll(sel).forEach(function(el) {
                                if (isSearchOrTopbarCore(el)) return; // xem giai thich o tren
                                el.remove();
                            });
                        } catch(e) {}
                    });

                    document.querySelectorAll('div,button,a,ytd-mealbar-promo-renderer,ytm-app-banner-renderer,ytm-topbar-menu-button-renderer').forEach(function(el) {
                        // QUAN TRỌNG: el.innerText lấy text của TOÀN BỘ con cháu bên trong (đệ quy),
                        // trong khi el.children.length chỉ đếm con TRỰC TIẾP - 1 div bao ngoài rất
                        // lớn (vd div gốc bọc cả trang) vẫn có thể có rất ít con trực tiếp nhưng bên
                        // trong sâu lại chứa đúng dòng chữ banner ở đâu đó, khiến nó bị nhận NHẦM là
                        // banner nhỏ. Khi đó target bị gán sai thành chính div bao lớn này, và vì nó
                        // thường chứa luôn cả icon tìm kiếm/topbar ở đâu đó bên trong nên
                        // isSearchOrTopbarCore() (tìm bằng querySelector xuống dưới) lại trả về true,
                        // khiến hàm bỏ qua không ẩn gì - đây chính là lý do banner "Mở trong ứng
                        // dụng" không bao giờ bị ẩn thật sự / cứ hiện lại liên tục. Chặn bằng cách
                        // giới hạn luôn TỔNG số phần tử con cháu (không chỉ con trực tiếp) - 1 banner
                        // thật sự chỉ gồm vài dòng chữ + icon/nút thì tổng số phần tử con cháu luôn
                        // rất nhỏ, khác hẳn 1 div bao cả mảng lớn của trang.
                        //
                        // SUA LOI TIEP (banner van thinh thoang "hien lai"): querySelectorAll('*')
                        // ở trên đếm luôn cả các thẻ <path>/<g> bên trong icon SVG (logo app, mũi
                        // tên...) - 1 icon SVG chi tiết có thể chứa hàng chục thẻ <path> khiến tổng
                        // số đếm được vượt quá ngưỡng 60 dù banner vẫn rất nhỏ trên màn hình, làm
                        // điều kiện dưới đây fail và banner KHÔNG được ẩn. Sửa bằng cách loại trừ
                        // toàn bộ nội dung bên trong <svg> khi đếm.
                        var totalDescendants = el.querySelectorAll('*:not(svg):not(svg *)').length;
                        // Thêm 1 tín hiệu đáng tin hơn đếm-số-phần-tử: kích thước hiển thị THẬT.
                        // Một banner "Mở ứng dụng" thật sự không bao giờ cao quá ~40% màn hình -
                        // nếu el cao hơn mức đó, gần như chắc chắn đây là 1 khối bao lớn của trang
                        // (false positive) chứ không phải banner nhỏ, dù nó lọt qua 2 điều kiện đếm
                        // phần tử ở trên.
                        var fitsBannerSize = true;
                        try {
                            var _r = el.getBoundingClientRect();
                            if (_r.height > 0) {
                                var _vh = window.innerHeight || document.documentElement.clientHeight || 0;
                                if (_vh > 0 && _r.height > _vh * 0.4) fitsBannerSize = false;
                            }
                        } catch (e) {}
                        if (el.children && el.children.length < 15 && totalDescendants < 60 && fitsBannerSize && matchesAny(textOf(el), BANNER_TEXT)) {
                            // KHÔNG dùng '[class*="topbar"]' ở đây nữa - selector này thỉnh thoảng
                            // leo lên trúng nguyên cụm thanh trên cùng (chứa cả icon tìm kiếm, menu)
                            // thay vì chỉ đúng cái banner nhỏ "Mở ứng dụng" bên trong, khiến nút tìm
                            // kiếm bị ẩn mất theo. Chỉ tìm trong phạm vi banner/promo/mealbar thật.
                            var container = el.closest('[class*="banner"],[class*="promo"],[class*="mealbar"]') || el;
                            // An toàn thêm 1 lớp: nếu container tìm được vẫn to bất thường (nhiều
                            // hơn 6 phần tử con) thì chỉ ẩn đúng phần tử đã khớp text (el), không
                            // ẩn cả container.
                            var target = (container.children && container.children.length > 6) ? el : container;
                            if (isSearchOrTopbarCore(target)) return; // xem [isSearchOrTopbarCore]
                            target.style.display = 'none';
                        }
                    });

                    // Nút mở app đôi khi không có text match được nhưng trỏ tới app store / deep link
                    document.querySelectorAll('a[href*="play.google.com"], a[href*="apps.apple.com"], a[href^="youtube://"], a[href*="ddl.gg"]').forEach(function(el) {
                        // SUA LOI (nguoi dung phan anh: "icon kinh lup bi che/an" - loi CU tai
                        // xuat hien): TRUOC DAY dung "el.closest('div,button,...')" - RAT RONG,
                        // "div" la the HTML pho bien nhat nen closest() thuong dung lai ngay o
                        // CHA GAN NHAT, nhung neu link "mo app" nay va icon tim kiem tinh co la
                        // 2 ANH EM CUNG 1 CHA (vd deu nam trong cung 1 thanh topbar YouTube) thi
                        // an het CA CHA se an luon ca icon tim kiem theo - dung y het loi da tung
                        // sua o vong lap ben tren (BANNER_TEXT) nhung CHUA duoc ap dung cho vong
                        // lap nay. GIO DAY: ap dung CHINH XAC cung 1 quy tac an toan - CHi leo len
                        // container THAT SU la banner/promo/mealbar, VA chi an ca container do neu
                        // no du NHO (toi da 6 phan tu con) - neu khong, CHi an dung phan tu <a> da
                        // khop, khong dung lieu lam anh huong ca cum cha chua no.
                        var container = el.closest('[class*="banner"],[class*="promo"],[class*="mealbar"]');
                        var target = (container && container.children && container.children.length <= 6) ? container : el;
                        if (isSearchOrTopbarCore(target)) return; // xem [isSearchOrTopbarCore]
                        target.style.display = 'none';
                    });

                    // Nút "Mở ứng dụng" nằm NGAY TRONG THANH TOPBAR (không phải banner/promo/mealbar
                    // to như các trường hợp trên) - xử lý RIÊNG, thật HẸP và CHÍNH XÁC bằng cách khớp
                    // ĐÚNG NGUYÊN VĂN nhãn nút, thay vì nới lỏng isSearchOrTopbarCore() dùng chung cho
                    // mọi thứ (cách đó từng lỡ ẩn nhầm icon tìm kiếm/cài đặt thật - đã lùi lại). Chỉ ẩn
                    // ĐÚNG phần tử nút nhỏ này (không leo lên container cha nào), nên dù nó nằm cạnh
                    // icon tìm kiếm/menu trong cùng thanh topbar cũng không bao giờ kéo theo ẩn mất
                    // icon bên cạnh. (Không có bước này thì isSearchOrTopbarCore() ở trên vô tình
                    // bảo vệ luôn CHÍNH nút "Mở ứng dụng" khi nó nằm trong topbar, khiến nút không
                    // bao giờ bị ẩn được.)
                    var OPEN_APP_EXACT_LABELS = ['mở ứng dụng', 'mo ung dung', 'open app', 'open the app'];
                    document.querySelectorAll('button, a, div[role="button"], [role="button"], tp-yt-paper-button, ytm-button-renderer').forEach(function(el) {
                        if (el.children && el.children.length > 3) return; // chỉ nút nhỏ dạng pill/label, không phải khối lớn
                        var label = (el.innerText || el.textContent || '').trim().toLowerCase();
                        if (OPEN_APP_EXACT_LABELS.indexOf(label) !== -1) {
                            el.style.display = 'none';
                        }
                    });
                }

                var lastSkipRun = 0;
                function skipAds() {
                    var now = Date.now();
                    if (now - lastSkipRun < 100) return; // throttle: toi da ~10 lan/giay
                    lastSkipRun = now;

                    fallbackSkipSelectors.forEach(function(sel) {
                        try {
                            var btn = document.querySelector(sel);
                            if (btn) btn.click();
                        } catch(e) {}
                    });

                    // Quét cả button, div, span, a - vì nút "Bỏ qua" của overlay/companion
                    // ad đôi khi không phải <button> mà là <div> hoặc <a> có role tuỳ ý.
                    document.querySelectorAll('button,[role="button"],div[class*="skip"],div[class*="bo-qua"],a[class*="skip"]').forEach(function(btn) {
                        if (matchesAny(textOf(btn), SKIP_TEXT)) {
                            try { btn.click(); } catch(e) {}
                        }
                    });

                    // Thêm: ẩn hẳn overlay ad container nếu đang hiển thị
                    document.querySelectorAll('.ytp-ad-overlay-container, .ytp-ad-overlay-slot, [class*="ad-overlay"]').forEach(function(el) {
                        try { el.style.display = 'none'; } catch(e) {}
                    });

                    var player = document.querySelector('.html5-video-player, [class*="html5-video-player"]');
                    var isAd = player && /ad-showing|ad-interrupting/.test(player.className || '');
                    var video = document.querySelector('video');

                    if (isAd && video) {
                        // SUA LOI (nguoi dung phan anh: "khong chan duoc 2 quang cao lien
                        // tiep, chi chan duoc 1 cai"): TRUOC DAY chi cam tieng (muted=true)
                        // SAU KHI da seek duoc toi cuoi video (tuc CHi khi video.duration da
                        // co gia tri hop le) - nhung quang cao THU 2 tro di trong 1 chuoi
                        // quang cao lien tuc thuong duoc nap gan nhu NGAY LAP TUC sau khi
                        // quang cao truoc bi ep nhay qua, luc do <video> RAT CO THE CHUA KIP
                        // co duration hop le (con NaN/Infinity trong vai chuc/vai tram ms dau
                        // khi dang tai) - dieu kien "isFinite(video.duration)" THAT BAI ngay
                        // luc do nen code CU KHONG LAM GI CA (khong cam tieng, khong seek),
                        // quang cao thu 2 cu the ma phat het nguyen ven truoc khi vong lap
                        // 150ms sau kip bat duoc trang thai duration da san sang.
                        //
                        // GIO DAY: cam tieng NGAY LAP TUC moi khi phat hien dang co quang cao
                        // (bat ke duration da san sang hay chua) - dam bao it nhat khong con
                        // nghe thay bat ky quang cao nao (kể cả cai thu 2, thu 3...), roi tiep
                        // tuc seek toi cuoi ngay khi duration co gia tri hop le (co the ngay
                        // tuc thi, hoac vai tick 150ms sau tuy tung quang cao).
                        if (!video.muted) {
                            video.muted = true;
                            video.__ytbrowser_force_muted = true;
                        }
                        if (video.duration && isFinite(video.duration)) {
                            try { video.currentTime = video.duration; } catch(e) {}
                        }
                    } else if (video && video.__ytbrowser_force_muted) {
                        // Đã hết quảng cáo (không còn isAd) - trả lại tiếng cho nội dung THẬT,
                        // tránh video chính bị câm vĩnh viễn sau khi từng ép bỏ qua quảng cáo
                        // (chỉ trả tiếng nếu CHÍNH đoạn code này là bên đã câm nó, không đụng
                        // vào nếu người dùng tự tay bấm câm tiếng thật sự).
                        video.muted = false;
                        video.__ytbrowser_force_muted = false;
                    }
                }

                // Quan sat rieng thuoc tinh class cua player: ad-showing/ad-interrupting duoc
                // YouTube gan bang cach doi class, khong phai them/xoa phan tu con -> observer
                // childList/subtree o body khong bat duoc thay doi nay, phai theo doi rieng.
                function attachPlayerObserver() {
                    var player = document.querySelector('.html5-video-player, [class*="html5-video-player"]');
                    if (player && !player.__ytbrowser_class_observed) {
                        player.__ytbrowser_class_observed = true;
                        var playerObserver = new MutationObserver(function() { skipAds(); });
                        playerObserver.observe(player, { attributes: true, attributeFilter: ['class'] });
                    }
                }

                hideAppBanners();
                skipAds();
                attachPlayerObserver();
                setInterval(function() {
                    hideAppBanners();
                    skipAds();
                    attachPlayerObserver();
                }, 100); // Giam tu 150ms xuong 100ms: phan ung nhanh hon giua cac quang cao lien tiep

                var mo = new MutationObserver(function() {
                    hideAppBanners();
                    skipAds();
                    attachPlayerObserver();
                });
                mo.observe(document.body, { childList: true, subtree: true });
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // ---- 3) Ghi nhớ tốc độ phát & tự khôi phục sau khi bị lệch (do quảng cáo / mở lại app) ----
    private fun injectSpeedMemory() {
        val savedSpeed = prefs.getFloat("playback_speed", 1.0f)
        val js = """
            (function() {
                if (window.__ytbrowser_speed_loop) return;
                window.__ytbrowser_speed_loop = true;

                var desiredSpeed = $savedSpeed;

                // Ham dung chung de Android goi khi can EP tot do (vd: bat/tat quay video 16x) -
                // QUAN TRONG: phai cap nhat CA desiredSpeed lan video.playbackRate CUNG LUC, neu
                // khong vong lap applySpeed() ben duoi (chay moi 1 giay) se phat hien lech va tu
                // dong keo tot do quay VE LAI gia tri desiredSpeed CU trong <1 giay - day chinh la
                // ly do truoc day sau khi quay xong video, tot do phat KHONG duoc khoi phuc ve 1x
                // (Android chi set rieng video.playbackRate=1 ma khong biet gi ve desiredSpeed,
                // trong khi desiredSpeed dang la 16 tu luc bat dau quay do event 'ratechange' tu
                // gan cap nhat) - vong lap sau do lai ep nguoc ve 16x.
                window.__ytbrowser_forceSpeed = function(v) {
                    desiredSpeed = v;
                    var video = document.querySelector('video');
                    if (video) video.playbackRate = v;
                };

                function applySpeed() {
                    var video = document.querySelector('video');
                    if (video && Math.abs(video.playbackRate - desiredSpeed) > 0.01) {
                        video.playbackRate = desiredSpeed;
                    }
                }

                // ---- Phat hien chuyen sang video KHAC de tu dong tra tot do ve 1x ----
                // YouTube dieu huong giua cac video (bam video lien quan, playlist, tim kiem...)
                // bang SPA navigation (pushState), KHONG load lai trang, nen onPageFinished ben
                // Android khong chay lai va tot do cu (desiredSpeed) se bi mang sang video moi
                // neu khong xu ly. Ham nay nhan dien video hien tai qua id trong URL (?v=... cho
                // /watch, hoac pathname cho /shorts) - moi khi id doi khac lan truoc, coi la da
                // chuyen video moi va ep tot do ve 1x tu dau.
                function getVideoKey() {
                    try {
                        if (window.location.pathname.indexOf('/shorts/') === 0) {
                            return window.location.pathname;
                        }
                        var v = new URLSearchParams(window.location.search).get('v');
                        return v || window.location.pathname;
                    } catch (e) {
                        return window.location.href;
                    }
                }

                var lastVideoKey = getVideoKey();

                function resetSpeedIfVideoChanged() {
                    var key = getVideoKey();
                    if (key !== lastVideoKey) {
                        lastVideoKey = key;
                        desiredSpeed = 1;
                        var video = document.querySelector('video');
                        if (video) video.playbackRate = 1;
                    }
                }

                // yt-navigate-finish: su kien chinh YouTube tu ban ra ngay sau khi dieu huong
                // SPA xong -> bat duoc thay doi som nhat co the.
                document.addEventListener('yt-navigate-finish', resetSpeedIfVideoChanged);

                function watchVideo() {
                    var video = document.querySelector('video');
                    if (!video) return;
                    if (video.__ytbrowser_bound) { applySpeed(); return; }
                    video.__ytbrowser_bound = true;

                    video.addEventListener('ratechange', function() {
                        // Chi luu lai khi nguoi dung chu dong doi (khong phai do he thong reset ve 1x sau ad)
                        if (video.playbackRate !== 1 || desiredSpeed === 1) {
                            desiredSpeed = video.playbackRate;
                            if (window.AndroidSpeed) {
                                window.AndroidSpeed.saveSpeed(desiredSpeed);
                            }
                        }
                        setTimeout(applySpeed, 300);
                    });

                    applySpeed();
                }

                watchVideo();
                setInterval(function() {
                    resetSpeedIfVideoChanged(); // lop du phong neu yt-navigate-finish khong ban ra
                    watchVideo();
                    applySpeed();
                }, 1000);
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    // ---- 5) Ẩn nút Cài đặt/Phụ đề/Lặp lại trên trình phát KHI ĐANG TOÀN MÀN HÌNH ----
    // Trong app này các nút đó không dùng được (mở ra không có tác dụng gì) nên ẩn hẳn đi cho
    // gọn giao diện. CHỈ ẩn lúc đang toàn màn hình (dựa vào class "ytp-fullscreen" mà chính
    // YouTube tự gắn lên gốc trình phát mỗi khi vào chế độ toàn màn hình, kể cả khi xoay dọc
    // hay ngang) - lúc xem bình thường (chưa phóng to) vẫn giữ nguyên các nút này.
    // Dò theo aria-label/title (giống cách skip quảng cáo ở trên) thay vì chỉ dựa tên class cố
    // định, để tự sống sót qua các lần YouTube đổi tên class mà không cần sửa code.
    private fun injectBackgroundColor() {
        val js = """
            (function() {
                var style = document.getElementById('__yoy_bg__');
                if (!style) {
                    style = document.createElement('style');
                    style.id = '__yoy_bg__';
                    document.head.appendChild(style);
                }
                style.textContent =
                    'ytd-masthead, ytm-mobile-topbar-renderer, #masthead, #header, #masthead-container { background-color: #6DC8F0 !important; }' +
                    'ytd-masthead button, ytd-masthead a, ytd-masthead #search, ytd-masthead #buttons, ytd-masthead yt-icon, ytm-mobile-topbar-renderer button, ytm-mobile-topbar-renderer yt-icon { color: inherit !important; background: transparent !important; visibility: visible !important; opacity: 1 !important; }';
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }



    // ---- 3b) Ghi nhớ CHẤT LƯỢNG VIDEO người dùng đã chọn & không cho tự đổi ----
    // YouTube mặc định tự chọn chất lượng theo băng thông hiện có (chế độ "Auto"), và mỗi khi
    // chuyển sang video KHÁC thì lựa chọn thủ công trước đó cũng bị quên, quay về Auto. Cùng
    // cách làm như injectSpeedMemory() ở trên: bắt sự kiện người dùng bấm chọn 1 mức chất
    // lượng cụ thể trong menu Cài đặt, LƯU LẠI lựa chọn đó, rồi liên tục ép áp dụng lại nó lên
    // player (kể cả khi đổi sang video mới hoặc khi mạng yếu khiến YouTube tự hạ chất lượng).
    // Nếu người dùng chưa từng chọn gì (vẫn để Auto) thì KHÔNG can thiệp - giữ hành vi mặc định.
    private fun injectQualityMemory() {
        val savedQuality = prefs.getString("video_quality", "") ?: ""
        val js = """
            (function() {
                if (window.__ytbrowser_quality_loop) return;
                window.__ytbrowser_quality_loop = true;

                // '' (rong) nghia la nguoi dung CHUA TUNG chon gi - de nguyen Auto nhu binh
                // thuong, khong ep gi ca. Chi khi nao co gia tri cu the (vd "hd720") thi moi
                // bat dau ep.
                var desiredQuality = "$savedQuality";

                function getPlayer() {
                    return document.querySelector('#movie_player') ||
                           document.querySelector('.html5-video-player');
                }

                // Doi ten hien thi ("1080p", "720p", "Auto", "Tự động"...) sang ten noi bo ma
                // YouTube player API dung (giong dung ten voi IFrame Player API chinh thuc).
                var QUALITY_NAME_MAP = {
                    '2160p':'hd2160','1440p':'hd1440','1080p':'hd1080','720p':'hd720',
                    '480p':'large','360p':'medium','240p':'small','144p':'tiny'
                };
                function normalizeQuality(rawLabel) {
                    var t = rawLabel.toLowerCase();
                    if (t.indexOf('auto') !== -1 || t.indexOf('tự động') !== -1 || t.indexOf('tu dong') !== -1) {
                        return ''; // Auto -> khong ep gi ca, tra lai hanh vi mac dinh
                    }
                    var m = t.match(/(\d{3,4})p/);
                    if (!m) return null;
                    return QUALITY_NAME_MAP[m[1] + 'p'] || null;
                }

                function applyQuality() {
                    if (!desiredQuality) return; // dang o Auto, khong can thiep
                    var player = getPlayer();
                    if (!player || typeof player.setPlaybackQuality !== 'function') return;
                    try {
                        var current = player.getPlaybackQuality && player.getPlaybackQuality();
                        if (current !== desiredQuality) {
                            player.setPlaybackQuality(desiredQuality);
                            // Mot so phien ban player con co setPlaybackQualityRange - goi
                            // them de "ghim cung" chat luong nay, chong YouTube tu ha xuong
                            // khi mang yeu (ABR). Bo qua neu ham khong ton tai.
                            if (typeof player.setPlaybackQualityRange === 'function') {
                                try { player.setPlaybackQualityRange(desiredQuality, desiredQuality); } catch (e) {}
                            }
                        }
                    } catch (e) {}
                }

                // Bat click vao cac muc chon chat luong trong menu Cai dat (banh rang) cua
                // trinh phat - text hien thi kieu "1080p", "720p60", "Auto", "Tự động"...
                function hookQualityMenu() {
                    document.querySelectorAll(
                        '[role="menuitemradio"], .ytp-menuitem, [class*="quality"] [role="menuitem"], [class*="quality"] button, [class*="quality"] div'
                    ).forEach(function(el) {
                        if (el.__ytbrowser_quality_bound) return;
                        var label = ((el.innerText || el.getAttribute('aria-label') || '') + '').trim();
                        if (!/(\d{3,4}p|auto|tự động|tu dong)/i.test(label)) return;
                        el.__ytbrowser_quality_bound = true;
                        el.addEventListener('click', function() {
                            var picked = normalizeQuality(label);
                            if (picked === null) return; // khong nhan dien duoc, bo qua
                            desiredQuality = picked;
                            if (window.AndroidQuality) window.AndroidQuality.saveQuality(desiredQuality);
                            setTimeout(applyQuality, 400);
                        }, true);
                    });
                }

                hookQualityMenu();
                applyQuality();
                setInterval(function() {
                    hookQualityMenu();
                    applyQuality();
                }, 1000);

                var mo = new MutationObserver(function() {
                    hookQualityMenu();
                    applyQuality();
                });
                mo.observe(document.body, { childList: true, subtree: true });
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    inner class QualityBridge {
        @JavascriptInterface
        fun saveQuality(quality: String) {
            prefs.edit().putString("video_quality", quality).apply()
        }
    }

    inner class SpeedBridge {
        @JavascriptInterface        fun saveSpeed(speed: Float) {
            prefs.edit().putFloat("playback_speed", speed).apply()
        }
    }

    // ---- 4) Giữ phát nhạc/video khi TẮT MÀN HÌNH: vẫn chạy nền, vẫn phát âm thanh, bật màn
    // hình lại thì đang phát tiếp đúng chỗ cũ (không bị dừng/tải lại) ----
    //
    // 2 nguyên nhân khiến video/nhạc bị dừng khi tắt màn hình, xử lý riêng từng cái:
    //  (1) CPU tự ngủ sau khi màn hình tắt một lúc (cơ chế tiết kiệm pin của Android) -> mọi xử
    //      lý (kể cả giải mã audio đang phát) bị đóng băng giữa chừng. Xử lý bằng WAKE_LOCK loại
    //      PARTIAL (chỉ giữ CPU thức, KHÔNG giữ màn hình sáng - màn hình vẫn tắt bình thường như
    //      người dùng muốn) trong lúc video đang thực sự phát.
    //  (2) Chính trang YouTube (JavaScript của nó) chủ động tự bấm PAUSE khi phát hiện trang đã
    //      bị "ẩn" (qua Page Visibility API - document.hidden/visibilityState, hoặc sự kiện
    //      visibilitychange/blur) - hành vi này ĐÚNG Ý trên 1 tab trình duyệt thường (tiết kiệm
    //      tài nguyên khi chuyển tab), nhưng ở đây lại phá đúng thứ người dùng muốn (nghe tiếp
    //      khi tắt màn hình, giống app YouTube thật/nhạc thật vẫn làm được). Xử lý bằng cách giả
    //      lập trang LUÔN "đang hiện" trước mắt JavaScript của YouTube - ghi đè hẳn thuộc tính
    //      document.hidden/visibilityState về false/'visible' vĩnh viễn, và chặn không cho code
    //      của YouTube đăng ký lắng nghe được các sự kiện báo hiệu bị ẩn/mất focus nữa.
    private fun injectBackgroundPlaybackFix() {
        val js = """
            (function() {
                if (window.__ytbrowser_keepalive) return;
                window.__ytbrowser_keepalive = true;

                // Giả lập trang LUÔN đang hiển thị, dù màn hình thật đã tắt hay app đã ra nền -
                // để mọi đoạn code nào của YouTube có kiểm tra "document.hidden" trước khi quyết
                // định pause đều luôn thấy kết quả là "không, đang hiện bình thường".
                try {
                    Object.defineProperty(document, 'hidden', { get: function() { return false; }, configurable: true });
                    Object.defineProperty(document, 'visibilityState', { get: function() { return 'visible'; }, configurable: true });
                    Object.defineProperty(document, 'webkitHidden', { get: function() { return false; }, configurable: true });
                    Object.defineProperty(document, 'webkitVisibilityState', { get: function() { return 'visible'; }, configurable: true });
                } catch (e) {}

                // Chặn KHÔNG cho code của YouTube đăng ký lắng nghe được các sự kiện "trang bị
                // ẩn/mất focus" trên document/window nữa (visibilitychange, blur, pagehide...) -
                // âm thầm bỏ qua ngay từ lúc đăng ký (addEventListener), thay vì để đăng ký được
                // rồi mới tìm cách chặn lúc bắn ra (không chắc chặn kịp thứ tự với listener của
                // chính YouTube). Không đụng tới addEventListener của các phần tử KHÁC (vd chính
                // thẻ <video>) - chỉ áp dụng đúng 2 mục tiêu document và window.
                var BLOCKED = ['visibilitychange', 'webkitvisibilitychange', 'blur', 'pagehide', 'freeze'];
                [document, window].forEach(function(target) {
                    var originalAdd = target.addEventListener.bind(target);
                    target.addEventListener = function(type, listener, options) {
                        if (BLOCKED.indexOf(type) !== -1) return;
                        return originalAdd(type, listener, options);
                    };
                });

                // addEventListener chỉ là 1 trong 2 cách web có thể lắng nghe sự kiện - code cũng
                // có thể gán thẳng document.onvisibilitychange = fn / window.onblur = fn, cách
                // này KHÔNG đi qua addEventListener nên bị lọt ở bản trước. Vô hiệu hoá luôn kiểu
                // gán trực tiếp bằng cách biến các thuộc tính đó thành no-op (đọc/ghi đều không
                // làm gì).
                var ON_PROP_TARGETS = [
                    [document, 'onvisibilitychange'],
                    [document, 'onwebkitvisibilitychange'],
                    [document, 'onfreeze'],
                    [window, 'onblur'],
                    [window, 'onpagehide']
                ];
                ON_PROP_TARGETS.forEach(function(pair) {
                    try {
                        Object.defineProperty(pair[0], pair[1], {
                            get: function() { return null; },
                            set: function() { /* bo qua, khong gan duoc */ },
                            configurable: true
                        });
                    } catch (e) {}
                });

                // Theo dõi đúng lúc video ĐANG PHÁT thật sự để xin/nhả WAKE_LOCK phía Android -
                // chỉ giữ CPU thức trong lúc thực sự cần (đang phát), nhả ngay khi tạm dừng/kết
                // thúc để không tốn pin vô ích lúc không xem gì. Đồng thời cập nhật
                // navigator.mediaSession.playbackState - đây là API chính thức của trình duyệt
                // để báo "đang có 1 phiên phát media thật" cho engine, khác với việc chỉ giả JS
                // đọc document.hidden ở trên (chỉ đánh lừa được code JS, không đánh lừa được
                // chính engine).
                function updateMediaSession(playing) {
                    try {
                        if (navigator.mediaSession) {
                            navigator.mediaSession.playbackState = playing ? 'playing' : 'paused';
                        }
                    } catch (e) {}
                }

                // QUAN TRỌNG: đây mới là chỗ thật sự ngăn Chromium tự treo/tạm dừng video khi
                // WebView không hiển thị (khoá màn hình / thoát app) - việc giả document.hidden
                // ở trên chỉ đánh lừa được JS của YouTube đọc thấy "vẫn đang hiện", nhưng bản
                // thân ENGINE Chromium vẫn biết chính xác WebView đang bị ẩn ở tầng thấp hơn và
                // có thể tự treo media để tiết kiệm pin, JS không can thiệp được vào đó. Cách
                // CHÍNH THỐNG để engine coi đây là 1 phiên phát nhạc/video nền hợp lệ (như 1
                // trang nghe nhạc) và KHÔNG tự treo, là đăng ký đầy đủ action handler qua
                // navigator.mediaSession - đây là tín hiệu mà chính Chromium dùng để quyết định
                // có tha cho tab/WebView này khỏi bị treo khi chạy nền hay không.
                function setupMediaSessionHandlers(video) {
                    if (!navigator.mediaSession) return;
                    try {
                        navigator.mediaSession.metadata = new MediaMetadata({
                            title: document.title || 'YouTube',
                            artist: 'Tube for me'
                        });
                    } catch (e) {}
                    try {
                        navigator.mediaSession.setActionHandler('play', function() { video.play(); });
                        navigator.mediaSession.setActionHandler('pause', function() { video.pause(); });
                        navigator.mediaSession.setActionHandler('seekbackward', function() {
                            video.currentTime = Math.max(0, video.currentTime - 10);
                        });
                        navigator.mediaSession.setActionHandler('seekforward', function() {
                            video.currentTime = Math.min(video.duration || 1e9, video.currentTime + 10);
                        });
                    } catch (e) {}
                }

                function bindVideo() {
                    var video = document.querySelector('video');
                    if (!video) return;

                    // SỬA LỖI ("lần đầu ổn, lần 2 lại lỗi"): setupMediaSessionHandlers() TRƯỚC ĐÂY
                    // chỉ được gọi ĐÚNG 1 LẦN (nằm chung với khối gắn listener bên dưới, cũng bị
                    // chặn bởi cờ __ytbrowser_wl_bound). Nhưng chính script của YouTube tự đăng ký
                    // lại navigator.mediaSession của nó SAU KHI trang khởi tạo xong (để hỗ trợ
                    // Cast/Picture-in-Picture), ghi đè mất handler mình vừa gắn - lần đầu do thời
                    // điểm may mắn còn giữ được, lần sau thì bị ghi đè mất, không có ai gắn lại.
                    // Gọi lại hàm này ở MỌI lần bindVideo() chạy (mỗi 30 giây qua setInterval, và
                    // mỗi khi DOM đổi qua MutationObserver) - dù có bị YouTube ghi đè lúc nào,
                    // chậm nhất 30 giây sau sẽ được gắn lại đúng handler của mình.
                    setupMediaSessionHandlers(video);

                    if (video.__ytbrowser_wl_bound) return;
                    video.__ytbrowser_wl_bound = true;

                    video.addEventListener('playing', function() {
                        if (window.AndroidWakeLock) window.AndroidWakeLock.acquire();
                        updateMediaSession(true);
                    });
                    video.addEventListener('pause', function() {
                        if (window.AndroidWakeLock) window.AndroidWakeLock.release();
                        updateMediaSession(false);
                    });
                    video.addEventListener('ended', function() {
                        if (window.AndroidWakeLock) window.AndroidWakeLock.release();
                        updateMediaSession(false);
                    });

                    if (!video.paused && !video.ended) {
                        if (window.AndroidWakeLock) window.AndroidWakeLock.acquire();
                        updateMediaSession(true);
                    }
                }

                bindVideo();
                setInterval(function() {
                    bindVideo();
                    // Làm mới (gia hạn) wake lock mỗi 30 giây trong lúc đang phát - phòng hờ lỡ
                    // bỏ lọt sự kiện 'playing' (vd video mới thay thế phần tử <video> cũ mà chưa
                    // kịp bindVideo() lại) thì cũng không bao giờ bị hết hạn giữa chừng lúc đang
                    // nghe/xem dở.
                    var video = document.querySelector('video');
                    if (video && !video.paused && !video.ended && window.AndroidWakeLock) {
                        window.AndroidWakeLock.acquire();
                    }
                }, 30000);

                var mo = new MutationObserver(bindVideo);
                mo.observe(document.body, { childList: true, subtree: true });
            })();
        """.trimIndent()
        webView.evaluateJavascript(js, null)
    }

    inner class WakeLockBridge {
        @JavascriptInterface
        fun acquire() {
            runOnUiThread {
                isVideoPlaying = true
                if (wakeLock == null) {
                    val pm = getSystemService(Context.POWER_SERVICE) as PowerManager
                    // PARTIAL_WAKE_LOCK: chỉ giữ CPU thức, KHÔNG bật/giữ sáng màn hình - đúng ý
                    // muốn "màn hình vẫn tắt bình thường, chỉ âm thanh/video là tiếp tục chạy".
                    wakeLock = pm.newWakeLock(
                        PowerManager.PARTIAL_WAKE_LOCK, "YTBrowser::PlaybackWakeLock"
                    ).apply { setReferenceCounted(false) }
                }
                // Hết hạn tự động sau 60 giây nếu lỡ không nhận được sự kiện release() nào (an
                // toàn hơn là giữ vĩnh viễn) - JS phía trên tự làm mới mỗi 30 giây trong lúc còn
                // đang phát nên không bao giờ bị hết hạn giữa chừng lúc đang nghe/xem dở thật.
                wakeLock?.acquire(60_000L)

                // QUAN TRỌNG NHẤT (nguyên nhân gốc thật sự khiến video/nhạc dừng khi khoá màn
                // hình, sâu hơn cả wake lock/Foreground Service/document.hidden ở trên): khi màn
                // hình khoá, Android che Activity bằng cửa sổ màn hình khoá (keyguard) - từ góc
                // nhìn của hệ thống, Activity/WebView của app coi như KHÔNG CÒN HIỂN THỊ nữa
                // (dù tiến trình vẫn sống nhờ Foreground Service). Ngay khi 1 Window bị coi là
                // "không hiển thị", chính TẦNG NATIVE của Chromium (không phải JavaScript, JS
                // không can thiệp được vào đây) sẽ tự treo bộ giải mã video gắn với Surface của
                // Window đó để tiết kiệm tài nguyên - KỆ document.hidden đã bị giả false ở JS,
                // KỆ đã đăng ký navigator.mediaSession, KỆ CPU vẫn thức nhờ wake lock. Đây chính
                // là "Surface bị hệ thống thu hồi" mà không cơ chế nào ở trên chạm tới được.
                //
                // setShowWhenLocked(true) báo cho Android: "Activity này vẫn tiếp tục hiển thị Ở
                // TRÊN màn hình khoá" - Window không còn bị keyguard che mất nữa, hệ thống vẫn
                // coi nó là cửa sổ hiển thị hợp lệ, Surface của WebView (và bộ giải mã video gắn
                // với nó) không bị thu hồi. Màn hình vẫn TẮT bình thường như người dùng muốn
                // (chỉ ảnh hưởng "cửa sổ nào được hệ thống coi là hiển thị", không phải có bật
                // đèn màn hình hay không - đó là việc riêng của PowerManager, không liên quan).
                //
                // CHỈ bật trong lúc THỰC SỰ đang phát (gọi cùng lúc với acquire() ở trên, tắt lại
                // ở release() bên dưới) - vì bật vĩnh viễn nghĩa là nội dung video sẽ luôn hiện đè
                // lên MÀN HÌNH KHOÁ có mật khẩu (ai cầm điện thoại cũng thấy được nội dung đang
                // xem mà không cần mở khoá) - đánh đổi cần thiết để giữ phát nền thật, nhưng chỉ
                // nên xảy ra đúng lúc có video/nhạc đang phát, không phải mọi lúc.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    setShowWhenLocked(true)
                } else {
                    @Suppress("DEPRECATION")
                    window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
                }

                // Khởi động Foreground Service - đây mới là phần quyết định giúp hệ thống không
                // đóng băng/kill tiến trình khi tắt màn hình (wake lock riêng lẻ không đủ trên
                // nhiều máy, đặc biệt các hãng có trình quản lý pin riêng).
                if (!playbackServiceRunning) {
                    playbackServiceRunning = true
                    startForegroundService(Intent(this@MainActivity, PlaybackService::class.java))
                }
            }
        }

        @JavascriptInterface
        fun release() {
            runOnUiThread {
                // Đang trong chuỗi TỰ pause() rồi TỰ play() lại (xem onPause() + isAutoPausing ở
                // trên) - đây KHÔNG phải người dùng/YouTube chủ động dừng thật, chỉ là bước dọn
                // trạng thái tạm thời trước khi tự phát lại 450ms sau. Bỏ qua hoàn toàn, không
                // nhả wake lock/tắt setShowWhenLocked - tránh tạo khoảng hở khiến Android có thể
                // thu hồi Surface video ngay giữa chuỗi trước khi kịp phát lại.
                if (isAutoPausing) return@runOnUiThread

                isVideoPlaying = false
                wakeLock?.let { if (it.isHeld) it.release() }
                // Tắt lại "hiện đè lên màn hình khoá" khi không còn phát gì nữa - trả màn hình
                // khoá về đúng hành vi bình thường (che kín nội dung app) như trước khi phát.
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
                    setShowWhenLocked(false)
                } else {
                    @Suppress("DEPRECATION")
                    window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED)
                }

            }
        }

    }
}
