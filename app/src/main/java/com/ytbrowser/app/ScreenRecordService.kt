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
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaFormat
import android.media.MediaMuxer
import android.media.MediaRecorder
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Build
import android.os.Environment
import android.os.HandlerThread
import android.os.IBinder
import android.util.DisplayMetrics
import android.util.Log
import android.view.WindowManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import java.io.File
import java.nio.ByteBuffer

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
        private const val TAG = "ScreenRecordService"

        private const val AUDIO_SAMPLE_RATE = 44_100
        // Mono: đủ dùng cho quay màn hình (âm thanh video YouTube phần lớn cũng không phải stereo
        // thật sự phong phú), giảm băng thông/kích thước file so với stereo.
        private const val AUDIO_CHANNEL_COUNT = 1
        private const val AUDIO_BIT_RATE = 128_000
        private const val VIDEO_BIT_RATE = 10_000_000
        private const val VIDEO_FRAME_RATE = 60
        private const val IFRAME_INTERVAL = 2 // giây giữa 2 khung hình khoá (I-frame)

        // Hệ số tốc độ quay: video phát ở 4x, audio capture từ luồng 4x đó.
        // Khi lưu file: PTS video × 4, PTS audio × 4 → cả hai về 1x → khớp chính xác.
        // PHẢI khớp với RECORD_SPEED_FACTOR trong MainActivity.
        const val SPEED_FACTOR = 4
    }

    private var mediaProjection: MediaProjection? = null
    private var virtualDisplay: VirtualDisplay? = null
    private var isPaused = false
    private var outputPath: String? = null

    // --- Đường ghi CŨ (MediaRecorder + micro) - CHỈ còn dùng làm phương án dự phòng trên Android
    // thấp hơn API 29 (Android 10), nơi KHÔNG có AudioPlaybackCaptureConfiguration nên buộc phải
    // ghi âm thanh qua micro vật lý (xem giải thích ở startInternalPipeline() bên dưới). ---
    private var mediaRecorder: MediaRecorder? = null

    // --- Đường ghi MỚI (API 29+): ghi THẲNG âm thanh app đang phát ra (không qua micro/loa vật
    // lý) bằng AudioPlaybackCaptureConfiguration - xem startInternalPipeline(). MediaRecorder
    // không hỗ trợ nguồn âm thanh này, nên phải tự dựng pipeline bằng MediaCodec (mã hoá riêng
    // video/audio) + MediaMuxer (ghép lại thành 1 file .mp4), thay cho việc phó mặc hết cho
    // MediaRecorder như đường ghi cũ.
    private var usingInternalAudioPipeline = false
    private var videoCodec: MediaCodec? = null
    private var audioCodec: MediaCodec? = null
    private var audioRecord: AudioRecord? = null
    private var muxer: MediaMuxer? = null
    private var videoTrackIndex = -1
    private var audioTrackIndex = -1
    private var muxerStarted = false
    private val muxerLock = Any()
    private var videoCallbackThread: HandlerThread? = null
    private var audioCallbackThread: HandlerThread? = null

    // Mốc thời gian gốc của video (cùng hệ quy chiếu đồng hồ với presentationTimeUs mà hệ thống
    // tự gán cho khung hình lấy từ Surface, tức System.nanoTime()) - dùng để dịch mốc thời gian
    // về 0 khi ghi, VÀ để "cắt" khoảng thời gian tạm dừng ra khỏi trục thời gian video (giữ hành
    // vi gapless giống hệt mediaRecorder.pause()/resume() cũ - không có đoạn đứng hình khi tạm
    // dừng rồi phát tiếp, thời lượng tạm dừng không hề xuất hiện trong file kết quả).
    private var videoBaseTimeNs = -1L
    private var pausedAccumNs = 0L
    private var pauseStartNs = -1L
    // Audio KHÔNG cần trừ lùi thời gian tạm dừng như video: presentationTimeUs của audio do
    // CHÍNH TA tự tính (đếm số mẫu/sample đã đưa vào bộ mã hoá), và việc đọc mẫu mới bị NGƯNG
    // HẲN trong lúc tạm dừng (xem audioCodecCallback.onInputBufferAvailable) - nên tự nhiên đã
    // gapless, không có khoảng trống nào để phải tính bù.
    private var audioSamplesWritten = 0L

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
        // khai báo quyền này (dùng chung với tính năng tìm kiếm giọng nói), nhưng vẫn kiểm tra
        // lại ở đây cho chắc (người dùng có thể đã thu hồi quyền trong Cài đặt hệ thống) để
        // không khởi tạo audio rồi thất bại giữa chừng, kéo cả phần video theo.
        val hasAudioPermission = ContextCompat.checkSelfPermission(
            this, Manifest.permission.RECORD_AUDIO
        ) == PackageManager.PERMISSION_GRANTED

        // SỬA LỖI (ghi âm bị rè khi dùng micro): micro thu CẢ tiếng loa phát ra LẪN tiếng ồn môi
        // trường xung quanh (tay cầm/chạm vào máy, gió, rung loa, vọng âm...) -> ra tiếng rè/ù,
        // đặc biệt rõ khi quay ở tốc độ 8x (RECORD_SPEED_FACTOR) vì âm thanh cũng bị tăng tốc
        // theo. Từ Android 10 (API 29) trở lên, dùng AudioPlaybackCaptureConfiguration để bắt
        // THẲNG luồng âm thanh mà chính app này đang phát ra (không đi qua loa/micro vật lý nào
        // cả) -> hoàn toàn sạch, không còn rè/ồn nền. Tự capture âm thanh CỦA CHÍNH APP MÌNH luôn
        // được phép (không cần app khác "mở khoá" gì thêm) - chỉ cần quyền RECORD_AUDIO như cũ.
        // Vì MediaRecorder không hỗ trợ nguồn âm thanh này, phải tự dựng 1 pipeline riêng bằng
        // MediaCodec + AudioRecord + MediaMuxer (startInternalPipeline() bên dưới) thay cho
        // MediaRecorder. Trên Android cũ hơn (< API 29, không có API này), đành quay lại dùng
        // MediaRecorder + micro như trước (startLegacyMediaRecorderPipeline()).
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
            usingInternalAudioPipeline = true
            startInternalPipeline(width, height, dpi, hasAudioPermission)
        } else {
            usingInternalAudioPipeline = false
            startLegacyMediaRecorderPipeline(width, height, dpi, hasAudioPermission)
        }

        isPaused = false
    }

    // ---------------------------------------------------------------------------------------
    // Đường ghi MỚI (API 29+): MediaCodec (video qua Surface + audio AAC) + AudioRecord (bắt âm
    // thanh nội bộ) + MediaMuxer (ghép 2 luồng đã mã hoá thành 1 file .mp4).
    // ---------------------------------------------------------------------------------------

    private fun startInternalPipeline(width: Int, height: Int, dpi: Int, hasAudioPermission: Boolean) {
        muxer = MediaMuxer(outputPath!!, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
        muxerStarted = false
        videoTrackIndex = -1
        audioTrackIndex = -1
        videoBaseTimeNs = -1L
        pausedAccumNs = 0L
        pauseStartNs = -1L
        audioSamplesWritten = 0L

        // 2 HandlerThread RIÊNG cho callback của video codec và audio codec - KHÔNG dùng chung 1
        // thread, vì callback đọc audio (onInputBufferAvailable của audioCodec) gọi AudioRecord.
        // read() ở chế độ BLOCKING (chờ có đủ dữ liệu mới trả về); nếu dùng chung 1 thread với
        // video, mỗi lần audio phải "chờ dữ liệu" sẽ làm nghẽn luôn cả việc xử lý output của
        // video codec phía sau nó trong hàng đợi -> giật/rớt khung hình.
        videoCallbackThread = HandlerThread("ScreenRecordVideoCodec").apply { start() }
        audioCallbackThread = HandlerThread("ScreenRecordAudioCodec").apply { start() }

        // ---- Video: mã hoá H.264, nhận khung hình qua Surface (giống hệt cách MediaRecorder
        // làm trước đây) - virtualDisplay render thẳng vào Surface này. ----
        val videoFormat = MediaFormat.createVideoFormat(MediaFormat.MIMETYPE_VIDEO_AVC, width, height).apply {
            setInteger(MediaFormat.KEY_COLOR_FORMAT, MediaCodecInfo.CodecCapabilities.COLOR_FormatSurface)
            setInteger(MediaFormat.KEY_BIT_RATE, VIDEO_BIT_RATE)
            setInteger(MediaFormat.KEY_FRAME_RATE, VIDEO_FRAME_RATE)
            setInteger(MediaFormat.KEY_I_FRAME_INTERVAL, IFRAME_INTERVAL)
        }
        videoCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_VIDEO_AVC).apply {
            configure(videoFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        val inputSurface = videoCodec!!.createInputSurface()
        videoCodec!!.setCallback(videoCodecCallback, android.os.Handler(videoCallbackThread!!.looper))
        videoCodec!!.start()

        virtualDisplay = mediaProjection!!.createVirtualDisplay(
            "ScreenRecord",
            width, height, dpi,
            DisplayManager.VIRTUAL_DISPLAY_FLAG_AUTO_MIRROR,
            inputSurface,
            null, null
        )

        // ---- Audio: chỉ bật khi có quyền RECORD_AUDIO, giống hệt điều kiện của đường ghi cũ ----
        if (hasAudioPermission) {
            try {
                startInternalAudioCapture()
            } catch (e: Exception) {
                // Không khởi tạo được audio nội bộ (vd thiết bị/ROM lỗi driver, xung đột hiếm với
                // app khác đang giữ AudioRecord độc quyền...) -> quay TIẾP TỤC nhưng KHÔNG có âm
                // thanh, còn hơn làm hỏng luôn cả phần video vì lỗi audio.
                Log.e(TAG, "Khong khoi tao duoc audio noi bo - quay tiep tuc KHONG co am thanh", e)
                try { audioRecord?.release() } catch (_: Exception) {}
                audioRecord = null
                try { audioCodec?.release() } catch (_: Exception) {}
                audioCodec = null
            }
        }
    }

    private fun startInternalAudioCapture() {
        val playbackConfig = AudioPlaybackCaptureConfiguration.Builder(mediaProjection!!)
            .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
            .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
            .addMatchingUsage(AudioAttributes.USAGE_GAME)
            .build()

        val channelMask = if (AUDIO_CHANNEL_COUNT == 1) AudioFormat.CHANNEL_IN_MONO else AudioFormat.CHANNEL_IN_STEREO
        val minBufSize = AudioRecord.getMinBufferSize(
            AUDIO_SAMPLE_RATE, channelMask, AudioFormat.ENCODING_PCM_16BIT
        ).let { if (it > 0) it else AUDIO_SAMPLE_RATE * 2 }

        audioRecord = AudioRecord.Builder()
            .setAudioFormat(
                AudioFormat.Builder()
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setSampleRate(AUDIO_SAMPLE_RATE)
                    .setChannelMask(channelMask)
                    .build()
            )
            .setBufferSizeInBytes(minBufSize * 2)
            .setAudioPlaybackCaptureConfig(playbackConfig)
            .build()

        val audioFormat = MediaFormat.createAudioFormat(
            MediaFormat.MIMETYPE_AUDIO_AAC, AUDIO_SAMPLE_RATE, AUDIO_CHANNEL_COUNT
        ).apply {
            setInteger(MediaFormat.KEY_AAC_PROFILE, MediaCodecInfo.CodecProfileLevel.AACObjectLC)
            setInteger(MediaFormat.KEY_BIT_RATE, AUDIO_BIT_RATE)
        }
        audioCodec = MediaCodec.createEncoderByType(MediaFormat.MIMETYPE_AUDIO_AAC).apply {
            configure(audioFormat, null, null, MediaCodec.CONFIGURE_FLAG_ENCODE)
        }
        audioCodec!!.setCallback(audioCodecCallback, android.os.Handler(audioCallbackThread!!.looper))
        audioCodec!!.start()
        audioRecord!!.startRecording()
    }

    // Video KHÔNG dùng input buffer thủ công - dữ liệu tới qua Surface (createInputSurface()).
    private val videoCodecCallback = object : MediaCodec.Callback() {
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {}

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            handleVideoOutput(codec, index, info)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "Loi video codec", e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            synchronized(muxerLock) {
                videoTrackIndex = try { muxer?.addTrack(format) ?: -1 } catch (e: Exception) { -1 }
                maybeStartMuxerLocked()
            }
        }
    }

    private val audioCodecCallback = object : MediaCodec.Callback() {
        // Đọc PCM trực tiếp từ AudioRecord NGAY TẠI ĐÂY (thay vì 1 thread đọc riêng rồi tự
        // dequeue/queue input buffer thủ công) - đơn giản hơn và tránh phải tự đồng bộ 2 luồng độc
        // lập; MediaCodec ở chế độ callback (async) không cho phép trộn lẫn với gọi
        // dequeueInputBuffer() thủ công từ thread khác.
        override fun onInputBufferAvailable(codec: MediaCodec, index: Int) {
            if (isPaused) {
                // Đang tạm dừng: KHÔNG đọc dữ liệu mới từ AudioRecord (bỏ qua hẳn khoảng thời
                // gian tạm dừng, y hệt hành vi mediaRecorder.pause() cũ) - chỉ trả buffer rỗng lại
                // cho codec để nó không bị "đói" input mãi.
                try { codec.queueInputBuffer(index, 0, 0, 0, 0) } catch (_: Exception) {}
                return
            }
            val ar = audioRecord
            val buffer = if (ar != null) codec.getInputBuffer(index) else null
            if (ar == null || buffer == null) {
                try { codec.queueInputBuffer(index, 0, 0, 0, 0) } catch (_: Exception) {}
                return
            }
            buffer.clear()
            val read = try { ar.read(buffer, buffer.capacity()) } catch (e: Exception) { -1 }
            if (read > 0) {
                // PTS × SPEED_FACTOR: kéo dãn audio về 1x thời gian thực, khớp với video.
                // Audio capture từ luồng đang phát 4x → 1 giây thu = 4 giây nội dung.
                // samples / 44100 = thời gian thu thật → × 4 = thời gian nội dung thật → 1x.
                val ptsUs = audioSamplesWritten * 1_000_000L * SPEED_FACTOR / AUDIO_SAMPLE_RATE
                audioSamplesWritten += read / (2 * AUDIO_CHANNEL_COUNT) // 2 byte/mẫu (PCM 16-bit)
                try { codec.queueInputBuffer(index, 0, read, ptsUs, 0) } catch (_: Exception) {}
            } else {
                try { codec.queueInputBuffer(index, 0, 0, 0, 0) } catch (_: Exception) {}
            }
        }

        override fun onOutputBufferAvailable(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
            handleAudioOutput(codec, index, info)
        }

        override fun onError(codec: MediaCodec, e: MediaCodec.CodecException) {
            Log.e(TAG, "Loi audio codec", e)
        }

        override fun onOutputFormatChanged(codec: MediaCodec, format: MediaFormat) {
            synchronized(muxerLock) {
                audioTrackIndex = try { muxer?.addTrack(format) ?: -1 } catch (e: Exception) { -1 }
                maybeStartMuxerLocked()
            }
        }
    }

    // PHẢI được gọi trong lúc đang giữ muxerLock.
    private fun maybeStartMuxerLocked() {
        if (muxerStarted) return
        val needsAudio = audioCodec != null
        val videoReady = videoTrackIndex >= 0
        val audioReady = !needsAudio || audioTrackIndex >= 0
        if (videoReady && audioReady) {
            try {
                muxer?.start()
                muxerStarted = true
            } catch (e: Exception) {
                Log.e(TAG, "Loi khoi dong muxer", e)
            }
        }
    }

    // Dịch presentationTimeUs gốc về mốc 0 tại khung hình đầu tiên, trừ thời gian tạm dừng,
    // rồi nhân × SPEED_FACTOR để kéo dãn duration video trong file về 1x thời gian thực.
    //
    // Lý do nhân SPEED_FACTOR:
    //   Video đang phát ở 4x → Surface nhận frame nhanh 4x nhưng PTS gốc tăng theo
    //   đồng hồ thật (1x). Nếu không nhân, 1 giây quay chỉ ghi được 1 giây trong file
    //   nhưng chứa 4 giây nội dung → player phát ra quá nhanh (4x) khi xem lại.
    //   Nhân × 4 → 1 giây quay = 4 giây trong file = đúng thời lượng nội dung thật → 1x.
    //
    // Audio PTS cũng nhân × SPEED_FACTOR (xem audioCodecCallback) → hai track khớp nhau.
    private fun adjustedVideoPtsUs(rawPtsUs: Long): Long {
        val rawNs = rawPtsUs * 1000L
        if (videoBaseTimeNs < 0) videoBaseTimeNs = rawNs
        val adjustedNs = rawNs - videoBaseTimeNs - pausedAccumNs
        val clampedNs = if (adjustedNs < 0) 0L else adjustedNs
        return clampedNs * SPEED_FACTOR / 1000L
    }

    private fun handleVideoOutput(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        try {
            val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            if (!isConfig && info.size > 0 && !isPaused) {
                val buffer: ByteBuffer? = codec.getOutputBuffer(index)
                if (buffer != null) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    val adjusted = MediaCodec.BufferInfo().apply {
                        set(info.offset, info.size, adjustedVideoPtsUs(info.presentationTimeUs), info.flags)
                    }
                    synchronized(muxerLock) {
                        if (muxerStarted && videoTrackIndex >= 0) {
                            try {
                                muxer?.writeSampleData(videoTrackIndex, buffer, adjusted)
                            } catch (e: Exception) {
                                Log.e(TAG, "Loi ghi video sample", e)
                            }
                        }
                    }
                }
            }
        } finally {
            try { codec.releaseOutputBuffer(index, false) } catch (_: Exception) {}
        }
    }

    private fun handleAudioOutput(codec: MediaCodec, index: Int, info: MediaCodec.BufferInfo) {
        try {
            val isConfig = (info.flags and MediaCodec.BUFFER_FLAG_CODEC_CONFIG) != 0
            if (!isConfig && info.size > 0) {
                val buffer: ByteBuffer? = codec.getOutputBuffer(index)
                if (buffer != null) {
                    buffer.position(info.offset)
                    buffer.limit(info.offset + info.size)
                    synchronized(muxerLock) {
                        if (muxerStarted && audioTrackIndex >= 0) {
                            try {
                                muxer?.writeSampleData(audioTrackIndex, buffer, info)
                            } catch (e: Exception) {
                                Log.e(TAG, "Loi ghi audio sample", e)
                            }
                        }
                    }
                }
            }
        } finally {
            try { codec.releaseOutputBuffer(index, false) } catch (_: Exception) {}
        }
    }

    private fun stopInternalPipeline() {
        // Báo hết luồng vào cho video codec rồi chờ 1 chút để codec xả nốt các khung hình còn dở
        // trong hàng đợi trước khi stop() cứng - tránh mất vài khung/mẫu cuối cùng.
        try { videoCodec?.signalEndOfInputStream() } catch (_: Exception) {}
        try { Thread.sleep(150) } catch (_: Exception) {}

        try { audioRecord?.stop() } catch (_: Exception) {}
        try { audioRecord?.release() } catch (_: Exception) {}
        audioRecord = null

        try { videoCodec?.stop() } catch (_: Exception) {}
        try { videoCodec?.release() } catch (_: Exception) {}
        videoCodec = null

        try { audioCodec?.stop() } catch (_: Exception) {}
        try { audioCodec?.release() } catch (_: Exception) {}
        audioCodec = null

        synchronized(muxerLock) {
            if (muxerStarted) {
                try { muxer?.stop() } catch (_: Exception) {}
            }
            try { muxer?.release() } catch (_: Exception) {}
            muxer = null
            muxerStarted = false
            videoTrackIndex = -1
            audioTrackIndex = -1
        }

        videoCallbackThread?.quitSafely()
        videoCallbackThread = null
        audioCallbackThread?.quitSafely()
        audioCallbackThread = null
    }

    // ---------------------------------------------------------------------------------------
    // Đường ghi CŨ (< API 29): MediaRecorder + micro thật - xem lý do ở startRecording().
    // ---------------------------------------------------------------------------------------

    private fun startLegacyMediaRecorderPipeline(width: Int, height: Int, dpi: Int, hasAudioPermission: Boolean) {
        mediaRecorder = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            MediaRecorder(this)
        } else {
            @Suppress("DEPRECATION")
            MediaRecorder()
        }

        mediaRecorder!!.apply {
            if (hasAudioPermission) {
                setAudioSource(MediaRecorder.AudioSource.MIC)
            }
            setVideoSource(MediaRecorder.VideoSource.SURFACE)
            setOutputFormat(MediaRecorder.OutputFormat.MPEG_4)
            if (hasAudioPermission) {
                setAudioEncoder(MediaRecorder.AudioEncoder.AAC)
                setAudioEncodingBitRate(AUDIO_BIT_RATE)
                setAudioSamplingRate(AUDIO_SAMPLE_RATE)
            }
            setVideoEncoder(MediaRecorder.VideoEncoder.H264)
            setVideoSize(width, height)
            // Nâng từ 30fps lên 60fps - mượt hơn khi xem lại, đặc biệt ở các đoạn chuyển động
            // nhanh (kéo theo tăng bitrate bên dưới để 60fps không bị vỡ khối/mờ nhoè do thiếu
            // dữ liệu trên mỗi khung hình).
            setVideoFrameRate(VIDEO_FRAME_RATE)
            setVideoEncodingBitRate(VIDEO_BIT_RATE)
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
    }

    private fun pauseRecording() {
        if (isPaused) return
        isPaused = true
        if (usingInternalAudioPipeline) {
            pauseStartNs = System.nanoTime()
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try { mediaRecorder?.pause() } catch (_: Exception) {}
        }
    }

    private fun resumeRecording() {
        if (!isPaused) return
        isPaused = false
        if (usingInternalAudioPipeline) {
            if (pauseStartNs >= 0) {
                pausedAccumNs += (System.nanoTime() - pauseStartNs)
                pauseStartNs = -1L
            }
        } else if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.N) {
            try { mediaRecorder?.resume() } catch (_: Exception) {}
        }
    }

    // fromProjectionCallback = true khi hàm này được gọi NGƯỢC LẠI từ chính onStop() của
    // MediaProjection (hệ thống/người dùng tự kết thúc phiên chiếu) - lúc đó KHÔNG được gọi lại
    // mediaProjection.stop()/unregisterCallback() vì phiên đã kết thúc rồi, gọi lại có thể ném
    // lỗi hoặc vô nghĩa; chỉ cần dọn dẹp recorder/virtualDisplay và mã hoá file.
    private fun stopRecordingInternal(fromProjectionCallback: Boolean = false) {
        val hasLegacyRecorder = mediaRecorder != null
        val hasInternalPipeline = videoCodec != null || audioCodec != null || muxer != null
        // Nếu đã dọn dẹp rồi (vd ACTION_STOP và onStop() cùng gọi tới đây) thì bỏ qua, tránh mã
        // hoá/xoá file 2 lần.
        if (!hasLegacyRecorder && !hasInternalPipeline) return

        if (usingInternalAudioPipeline) {
            stopInternalPipeline()
        } else {
            try { mediaRecorder?.stop() } catch (_: Exception) {}
            mediaRecorder?.release()
            mediaRecorder = null
        }

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
