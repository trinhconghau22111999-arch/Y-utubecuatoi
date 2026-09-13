package com.ytbrowser.app

import android.Manifest
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.AudioManager
import android.media.MediaRecorder
import android.media.audiofx.AutomaticGainControl
import android.media.audiofx.NoiseSuppressor
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File

// ---------------------------------------------------------------------------------------------
// GHI CHÚ HỢP NHẤT (theo yêu cầu "không tách nữa - gộp chung lại, sửa hết logic"): file này
// TRƯỚC ĐÂY có 2 đường ghi hình tách biệt:
//   (1) MediaRecorder + mic vật lý - chỉ dùng cho Android < 10.
//   (2) Tự dựng pipeline riêng bằng MediaCodec + AudioRecord (bắt thẳng âm thanh app qua
//       AudioPlaybackCaptureConfiguration, không qua mic) + MediaMuxer - dùng cho Android 10+,
//       kèm cơ chế quay nhanh 4x rồi kéo giãn PTS video/audio về lại 1x lúc lưu file.
// Đường (2) phức tạp hơn nhiều lần và dù đã thử sửa (thêm allowAudioPlaybackCapture=true vào
// Manifest, tắt preservesPitch khi tăng tốc...) vẫn không ra được tiếng thật trên máy - nhiều khả
// năng âm thanh phát ra từ thẻ <video> bên trong WebView không được hệ thống quy về đúng "app đang
// phát" theo cách AudioPlaybackCaptureConfiguration yêu cầu, hoặc bị 1 tầng nào đó (WebView/OEM)
// âm thầm chặn mà code phía app không có cách nào kiểm soát hay thấy lỗi rõ ràng.
//
// GỘP LẠI về DUY NHẤT 1 đường ghi bằng MediaRecorder + MIC (nguồn VOICE_RECOGNITION để có sẵn
// khử ồn/AGC từ driver âm thanh máy, đỡ rè hơn MIC thô) cho MỌI phiên bản Android - đơn giản, ít
// chỗ có thể hỏng hơn hẳn, và CHẮC CHẮN ra được tiếng thật (đánh đổi duy nhất: mic có thể bắt thêm
// chút tiếng ồn môi trường xung quanh so với bắt thẳng audio nội bộ nếu đường (2) hoạt động đúng).
// Đồng thời bỏ luôn cơ chế quay nhanh 4x (quay lại đúng tốc độ thực 1x) vì mẹo "kéo PTS về 1x" chỉ
// áp dụng được với pipeline muxer thủ công đã bỏ - bỏ luôn để không còn logic nửa vời.
// ---------------------------------------------------------------------------------------------

class ScreenRecordService : Service() {

    companion object {
        const val ACTION_START   = "com.ytbrowser.app.SCREEN_RECORD_START"
        const val ACTION_PAUSE   = "com.ytbrowser.app.SCREEN_RECORD_PAUSE"
        const val ACTION_RESUME  = "com.ytbrowser.app.SCREEN_RECORD_RESUME"
        const val ACTION_STOP    = "com.ytbrowser.app.SCREEN_RECORD_STOP"
        const val EXTRA_RESULT_CODE  = "result_code"
        const val EXTRA_RESULT_DATA  = "result_data"
        const val EXTRA_FILE_INDEX   = "file_index"
        const val EXTRA_VIDEO_TITLE  = "video_title"
        private const val CHANNEL_ID = "screen_record_channel"
        private const val NOTIF_ID   = 2001

        private const val AUDIO_SAMPLE_RATE = 44_100
        private const val AUDIO_BIT_RATE = 128_000
        private const val VIDEO_BIT_RATE = 10_000_000
        private const val VIDEO_FRAME_RATE = 60
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var isPaused = false
    private var outputPath: String? = null

    // Bộ khử ồn/khử rè cho phiên ghi âm qua mic - xem gắn/gỡ ở startRecording()/
    // stopRecordingInternal(). Có thể null trên máy không hỗ trợ (isAvailable() = false).
    private var noiseSuppressor: NoiseSuppressor? = null
    private var automaticGainControl: AutomaticGainControl? = null

    // BẮT BUỘC từ Android 14 (API 34): phải registerCallback() cho MediaProjection TRƯỚC khi
    // gọi createVirtualDisplay(), nếu không hệ thống sẽ ném IllegalStateException ngay lập tức
    // -> đây chính là nguyên nhân app VĂNG khi bấm quay video trên máy Android 14 trở lên.
    // Đồng thời onStop() được gọi khi hệ thống/người dùng tự kết thúc phiên chiếu màn hình
    // (vd bấm "Stop" trên thông báo hệ thống, hoặc thu hồi quyền) - phải dọn dẹp recorder ở
    // đây, nếu không lần quay sau sẽ dùng phải 1 MediaProjection đã chết -> cũng crash.
    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            stopRecordingInternal(fromProjectionCallback = true)
        }
    }

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, -1)
                val resultData = intent.getParcelableExtra<Intent>(EXTRA_RESULT_DATA) ?: return START_NOT_STICKY
                val fileIndex  = intent.getIntExtra(EXTRA_FILE_INDEX, 1)
                val videoTitle = intent.getStringExtra(EXTRA_VIDEO_TITLE)
                startRecording(resultCode, resultData, fileIndex, videoTitle)
            }
            ACTION_PAUSE  -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP   -> stopRecordingInternal()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(resultCode: Int, resultData: Intent, fileIndex: Int, videoTitle: String?) {
        val notif = buildNotification("Đang quay màn hình...")
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            startForeground(NOTIF_ID, notif,
                android.content.pm.ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION)
        } else {
            startForeground(NOTIF_ID, notif)
        }

        val mpm = getSystemService(MEDIA_PROJECTION_SERVICE) as MediaProjectionManager
        mediaProjection = mpm.getMediaProjection(resultCode, resultData)

        // Đăng ký callback NGAY sau khi có MediaProjection, TRƯỚC createVirtualDisplay() bên
        // dưới - thứ tự này bắt buộc để không bỏ lỡ thông báo nào (xem giải thích ở khai báo
        // projectionCallback phía trên).
        mediaProjection?.registerCallback(projectionCallback, android.os.Handler(mainLooper))

        val wm = getSystemService(WINDOW_SERVICE) as WindowManager
        val metrics = DisplayMetrics()
        @Suppress("DEPRECATION")
        wm.defaultDisplay.getRealMetrics(metrics)
        val width  = metrics.widthPixels
        val height = metrics.heightPixels
        val dpi    = metrics.densityDpi

        // Ten file: uu tien dung TEN VIDEO tren Youtube (vd "Ten video.mp4") - chi roi ve kieu
        // cu "y.<so>" khi khong lay duoc tieu de hop le (xem sanitizeVideoTitleForFileName/
        // buildOutputPath ben duoi).
        outputPath = buildOutputPath(fileIndex, videoTitle)

        // Có quyền RECORD_AUDIO hay không quyết định có ghi âm kèm theo hay không - Manifest đã
        // khai báo quyền này, nhưng vẫn kiểm tra lại ở đây cho chắc (người dùng có thể đã thu
        // hồi quyền trong Cài đặt hệ thống) để không cấu hình audio rồi prepare() ném lỗi.
        val hasAudioPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        // Tạo sẵn 1 audioSessionId RIÊNG (không để hệ thống tự cấp mặc định) để có thể gắn thêm
        // NoiseSuppressor/AutomaticGainControl vào ĐÚNG phiên ghi âm này - xem đoạn gắn hiệu ứng
        // ngay sau prepare() bên dưới, giúp đỡ rè/ù khi ghi qua mic vật lý.
        val audioSessionId = if (hasAudioPermission) {
            (getSystemService(AUDIO_SERVICE) as AudioManager).generateAudioSessionId()
        } else null

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        mediaRecorder!!.apply {
            if (hasAudioPermission) {
                // VOICE_RECOGNITION thay vì MIC mặc định: nguồn này tắt sẵn các hiệu ứng làm
                // biến dạng giọng nói (AGC/AEC/NS mặc định của nguồn MIC đôi khi làm ù/rè âm
                // thanh phát ra từ loa máy) và vẫn được driver xử lý ổn định trên hầu hết máy -
                // đây là cách ghi âm CHẮC CHẮN hoạt động (qua mic vật lý thật), đơn giản hơn hẳn
                // pipeline bắt âm thanh nội bộ đã bỏ (xem ghi chú hợp nhất ở đầu file).
                setAudioSource(MediaRecorder.AudioSource.VOICE_RECOGNITION)
            }
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            if (hasAudioPermission) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(AUDIO_BIT_RATE)
                setAudioSamplingRate(AUDIO_SAMPLE_RATE)
                if (audioSessionId != null) setAudioSessionId(audioSessionId)
            }
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(width, height)
            setVideoFrameRate(VIDEO_FRAME_RATE)
            setVideoEncodingBitRate(VIDEO_BIT_RATE)
            setOutputFile(outputPath)
            prepare()
        }

        // Gắn NoiseSuppressor (khử tiếng ồn/rè nền) + AutomaticGainControl (tự cân bằng âm lượng,
        // tránh vỡ tiếng khi loa phát to) vào đúng audioSessionId vừa gán cho recorder ở trên -
        // PHẢI làm sau prepare() (lúc đó audio track của phiên ghi mới thật sự tồn tại để hiệu
        // ứng bám vào), và TRƯỚC start(). Không phải máy nào cũng hỗ trợ (isAvailable() kiểm tra
        // trước) - bỏ qua êm nếu không có, không làm hỏng luồng ghi hình chính.
        if (hasAudioPermission && audioSessionId != null) {
            try {
                if (NoiseSuppressor.isAvailable()) {
                    noiseSuppressor = NoiseSuppressor.create(audioSessionId)?.apply { enabled = true }
                }
                if (AutomaticGainControl.isAvailable()) {
                    automaticGainControl = AutomaticGainControl.create(audioSessionId)?.apply { enabled = true }
                }
            } catch (e: Exception) {
                // Vài máy/OEM có thể ném lỗi lúc tạo hiệu ứng - bỏ qua, không ảnh hưởng ghi hình.
            }
        }

        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "ScreenRecord",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            mediaRecorder!!.surface,
            null, null
        )

        mediaRecorder!!.start()
        isPaused = false
    }

    private fun pauseRecording() {
        if (isPaused) return
        isPaused = true
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try { mediaRecorder?.pause() } catch (_: Exception) {}
        }
    }

    private fun resumeRecording() {
        if (!isPaused) return
        isPaused = false
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try { mediaRecorder?.resume() } catch (_: Exception) {}
        }
    }

    // fromProjectionCallback = true khi hàm này được gọi NGƯỢC LẠI từ chính onStop() của
    // MediaProjection (hệ thống/người dùng tự kết thúc phiên chiếu) - lúc đó KHÔNG được gọi lại
    // mediaProjection.stop()/unregisterCallback() vì phiên đã kết thúc rồi, gọi lại có thể ném
    // lỗi hoặc vô nghĩa; chỉ cần dọn dẹp recorder/virtualDisplay và mã hoá file.
    private fun stopRecordingInternal(fromProjectionCallback: Boolean = false) {
        // Nếu đã dọn dẹp rồi (vd ACTION_STOP và onStop() cùng gọi tới đây) thì bỏ qua, tránh mã
        // hoá/xoá file 2 lần.
        if (mediaRecorder == null && virtualDisplay == null) return

        try { mediaRecorder?.stop() } catch (_: Exception) {}
        mediaRecorder?.release()
        mediaRecorder = null

        noiseSuppressor?.release()
        noiseSuppressor = null
        automaticGainControl?.release()
        automaticGainControl = null

        virtualDisplay?.release()
        virtualDisplay = null
        if (!fromProjectionCallback) {
            mediaProjection?.unregisterCallback(projectionCallback)
            mediaProjection?.stop()
        }
        mediaProjection = null

        // Mã hoá video vừa quay xong (xem VideoCrypto) rồi XOÁ bản gốc .mp4 - từ giờ trở đi,
        // không app xem video/quản lý file nào khác mở được nội dung thật bên trong file này
        // nữa (chỉ app xem riêng - biết trước mật khẩu - mới giải mã lại được). KHÔNG đăng ký
        // MediaStore cho file .locked này vì nó không còn là video phát trực tiếp được nữa.
        outputPath?.let { path ->
            val plainFile = File(path)
            if (plainFile.exists()) {
                val lockedFile = File(plainFile.parentFile, plainFile.nameWithoutExtension + VideoCrypto.LOCKED_EXTENSION)
                try {
                    VideoCrypto.encryptFile(plainFile, lockedFile)
                    plainFile.delete()
                } catch (e: Exception) {
                    // Mã hoá lỗi (vd hết dung lượng) - giữ nguyên file .mp4 gốc thay vì mất trắng,
                    // xoá bản .locked dở dang (nếu có) để không để lại rác hỏng.
                    lockedFile.delete()
                }
            }
        }

        @Suppress("DEPRECATION")
        if (Build.VERSION.SDK_INT >= 33) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        stopSelf()
    }

    private fun buildOutputPath(index: Int, videoTitle: String?): String {
        // Video LUON nam trong thu muc con "vdy" ben trong Downloads (khong nam thang o goc
        // Downloads nua) - de gom lai 1 cho, tach biet khoi cac file tai khac, va de app xem
        // rieng (xemvideoytb1) chi can quet dung 1 thu muc co dinh nay.
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(downloads, "vdy")
        if (!dir.exists()) dir.mkdirs()

        val sanitized = sanitizeVideoTitleForFileName(videoTitle)
        // Neu khong lay duoc tieu de hop le (trang khong phai video, hoac tieu de rong sau khi
        // don) thi quay ve kieu ten cu "y.<so>" de luon co 1 ten file hop le.
        val baseName = sanitized.ifBlank { "y.$index" }

        // Kiem tra trung ten: neu DA co file .mp4/.locked cung ten (vd quay lai chinh video do
        // lan 2, hoac 2 video trung tieu de), gan them " (index)" phia sau de KHONG BAO GIO ghi
        // de/mat file cu, dong thoi van giu duoc ten video de sau nay de nhan dien.
        val candidateBase = if (fileExistsWithBaseName(dir, baseName)) "$baseName ($index)" else baseName
        return File(dir, "$candidateBase.mp4").absolutePath
    }

    private fun fileExistsWithBaseName(dir: File, baseName: String): Boolean {
        return File(dir, "$baseName.mp4").exists() || File(dir, "$baseName${VideoCrypto.LOCKED_EXTENSION}").exists()
    }

    // Chuyen tieu de trang (webView.title, dang "Ten video - YouTube") thanh ten file hop le tren
    // he thong file Android: bo hau to " - YouTube", bo cac ky tu KHONG hop le trong ten file
    // (/ \ : * ? " < > |), gop khoang trang thua, va cat bot neu qua dai (gioi han thuc te cua
    // hau het he thong file Android la 255 byte ten file - chua du cho phan duoi " (n).mp4"/
    // .locked va cho viec tieng Viet co dau ma hoa UTF-8 nhieu hon 1 byte/ky tu).
    private fun sanitizeVideoTitleForFileName(raw: String?): String {
        if (raw.isNullOrBlank()) return ""
        var name = raw.trim().removeSuffix(" - YouTube").trim()
        name = name.replace(Regex("[/\\\\:*?\"<>|]"), " ")
        name = name.replace(Regex("\\s+"), " ").trim()
        if (name.length > 100) name = name.substring(0, 100).trim()
        return name
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val nm = getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(
                    NotificationChannel(CHANNEL_ID, "Quay màn hình", NotificationManager.IMPORTANCE_LOW)
                )
            }
        }
    }

    private fun buildNotification(text: String): Notification =
        NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Y-utube")
            .setContentText(text)
            .setSmallIcon(android.R.drawable.ic_media_play)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setOngoing(true)
            .build()

    override fun onBind(intent: Intent?): IBinder? = null
}
