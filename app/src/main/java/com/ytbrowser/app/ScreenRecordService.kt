package com.ytbrowser.app

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Intent
import android.hardware.display.DisplayManager
import android.hardware.display.VirtualDisplay
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.IBinder
import android.util.DisplayMetrics
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import java.io.File

class ScreenRecordService : Service() {

    companion object {
        const val ACTION_START   = "com.ytbrowser.app.SCREEN_RECORD_START"
        const val ACTION_PAUSE   = "com.ytbrowser.app.SCREEN_RECORD_PAUSE"
        const val ACTION_RESUME  = "com.ytbrowser.app.SCREEN_RECORD_RESUME"
        const val ACTION_STOP    = "com.ytbrowser.app.SCREEN_RECORD_STOP"
        const val EXTRA_RESULT_CODE  = "result_code"
        const val EXTRA_RESULT_DATA  = "result_data"
        const val EXTRA_FILE_INDEX   = "file_index"
        private const val CHANNEL_ID = "screen_record_channel"
        private const val NOTIF_ID   = 2001
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var mediaRecorder: MediaRecorder? = null
    private var isPaused = false
    private var outputPath: String? = null

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
                startRecording(resultCode, resultData, fileIndex)
            }
            ACTION_PAUSE  -> pauseRecording()
            ACTION_RESUME -> resumeRecording()
            ACTION_STOP   -> stopRecordingInternal()
        }
        return START_NOT_STICKY
    }

    private fun startRecording(resultCode: Int, resultData: Intent, fileIndex: Int) {
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

        // Tên file: y.1, y.2, y.3 ...
        outputPath = buildOutputPath(fileIndex)

        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        mediaRecorder!!.apply {
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(width, height)
            setVideoFrameRate(30)
            setVideoEncodingBitRate(6_000_000)
            setOutputFile(outputPath)
            prepare()
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
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try { mediaRecorder?.pause() } catch (_: Exception) {}
            isPaused = true
        }
    }

    private fun resumeRecording() {
        if (!isPaused) return
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try { mediaRecorder?.resume() } catch (_: Exception) {}
            isPaused = false
        }
    }

    // fromProjectionCallback = true khi hàm này được gọi NGƯỢC LẠI từ chính onStop() của
    // MediaProjection (hệ thống/người dùng tự kết thúc phiên chiếu) - lúc đó KHÔNG được gọi lại
    // mediaProjection.stop()/unregisterCallback() vì phiên đã kết thúc rồi, gọi lại có thể ném
    // lỗi hoặc vô nghĩa; chỉ cần dọn dẹp recorder/virtualDisplay và mã hoá file.
    private fun stopRecordingInternal(fromProjectionCallback: Boolean = false) {
        // Nếu recorder đã được dọn dẹp rồi (vd ACTION_STOP và onStop() cùng gọi tới đây) thì
        // bỏ qua, tránh mã hoá/xoá file 2 lần.
        if (mediaRecorder == null && virtualDisplay == null) return

        try {
            mediaRecorder?.stop()
        } catch (_: Exception) {}
        mediaRecorder?.release()
        mediaRecorder = null
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
        if (android.os.Build.VERSION.SDK_INT >= 33) {
            stopForeground(STOP_FOREGROUND_REMOVE)
        } else {
            stopForeground(true)
        }
        stopSelf()
    }

    private fun buildOutputPath(index: Int): String {
        // Video LUÔN nằm trong thư mục con "vdy" bên trong Downloads (không nằm thẳng ở gốc
        // Downloads nữa) - để gom lại 1 chỗ, tách biệt khỏi các file tải khác, và để app xem
        // riêng (xemvideoytb1) chỉ cần quét đúng 1 thư mục cố định này.
        val downloads = Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_DOWNLOADS)
        val dir = File(downloads, "vdy")
        if (!dir.exists()) dir.mkdirs()
        return File(dir, "y.$index.mp4").absolutePath
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
