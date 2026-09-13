package com.ytbrowser.app

import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.security.SecureRandom
import javax.crypto.Cipher
import javax.crypto.CipherInputStream
import javax.crypto.CipherOutputStream
import javax.crypto.SecretKeyFactory
import javax.crypto.spec.IvParameterSpec
import javax.crypto.spec.PBEKeySpec
import javax.crypto.spec.SecretKeySpec

// ---- Mã hoá video quay màn hình bằng mật khẩu cố định, chỉ xem được trong chính app này ----
// Video quay xong (file .mp4 chuẩn, ai mở cũng xem được ngay) được MÃ HOÁ LẠI thành 1 file nhị
// phân vô nghĩa (.locked) ngay sau khi quay xong (xem ScreenRecordService.stopRecording()), rồi
// XOÁ file .mp4 gốc đi - từ lúc đó, không app xem video/quản lý file nào khác mở được nội dung
// thật bên trong nữa. Muốn xem lại, phải vào đúng app này, chọn file, nhập ĐÚNG mật khẩu
// (xem MainActivity.showSavedVideosDialog()) - app sẽ giải mã ra 1 bản .mp4 tạm (trong cache
// riêng của app, không app nào khác đọc được) rồi mở bằng trình phát video ngoài để xem.
//
// THUẬT TOÁN: AES-256-CBC + PKCS5Padding. Khoá mã hoá KHÔNG lưu trực tiếp mật khẩu, mà tạo ra
// từ mật khẩu bằng PBKDF2WithHmacSHA256 (65536 vòng lặp băm) kết hợp với 1 "salt" ngẫu nhiên
// RIÊNG cho từng file - salt + IV (vector khởi tạo, cũng ngẫu nhiên riêng từng file) được ghi
// thẳng vào ĐẦU file .locked (không cần giữ bí mật, chỉ cần không đoán trước được) để lúc giải
// mã đọc lại đúng salt/IV đã dùng lúc mã hoá.
object VideoCrypto {

    // Mật khẩu xem video đã tải - CỐ ĐỊNH theo yêu cầu, không đổi được từ giao diện.
    const val VIDEO_PASSWORD = "TRINHCONGHAU99"

    // Đuôi file sau khi mã hoá (thay cho .mp4) - đặt tên KHÔNG giống video thường, để Gallery/
    // trình quản lý file khác không cố mở nó ra rồi báo lỗi "video hỏng".
    const val LOCKED_EXTENSION = ".locked"

    private const val MAGIC = "YOYLOCK1" // 8 byte đầu file, dùng để nhận diện đúng định dạng
    private const val SALT_LEN = 16
    private const val IV_LEN = 16
    private const val PBKDF2_ITERATIONS = 65536
    private const val KEY_LEN_BITS = 256

    private fun deriveKey(password: String, salt: ByteArray): SecretKeySpec {
        val factory = SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256")
        val spec = PBEKeySpec(password.toCharArray(), salt, PBKDF2_ITERATIONS, KEY_LEN_BITS)
        val keyBytes = factory.generateSecret(spec).encoded
        return SecretKeySpec(keyBytes, "AES")
    }

    // Mã hoá `plainFile` -> ghi kết quả vào `lockedFile`. Không đọc cả file vào RAM (dùng
    // CipherOutputStream để mã hoá theo luồng), an toàn với video vài trăm MB/vài GB.
    fun encryptFile(plainFile: File, lockedFile: File, password: String = VIDEO_PASSWORD) {
        val random = SecureRandom()
        val salt = ByteArray(SALT_LEN).also { random.nextBytes(it) }
        val iv = ByteArray(IV_LEN).also { random.nextBytes(it) }
        val key = deriveKey(password, salt)

        val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
        cipher.init(Cipher.ENCRYPT_MODE, key, IvParameterSpec(iv))

        FileOutputStream(lockedFile).use { rawOut ->
            rawOut.write(MAGIC.toByteArray(Charsets.US_ASCII))
            rawOut.write(salt)
            rawOut.write(iv)
            CipherOutputStream(rawOut, cipher).use { cOut ->
                FileInputStream(plainFile).use { input ->
                    input.copyTo(cOut, bufferSize = 1 shl 16)
                }
            }
        }
    }

    // Giải mã `lockedFile` -> ghi kết quả vào `outFile`. Trả về true nếu mật khẩu ĐÚNG và định
    // dạng file hợp lệ (kiểm tra bằng 2 lớp: (1) PKCS5Padding không lỗi lúc giải mã - sai mật
    // khẩu gần như luôn làm phép giải đệm này lỗi ngay; (2) sau khi giải mã, 4 byte offset 4-7
    // phải là "ftyp" - chữ ký nhận diện file MP4 chuẩn, phòng trường hợp hiếm hoi đệm vẫn hợp lệ
    // dù sai khoá). Nếu false, `outFile` sẽ bị xoá luôn (không để lại rác nội dung giải sai).
    fun decryptFile(lockedFile: File, outFile: File, password: String = VIDEO_PASSWORD): Boolean {
        try {
            FileInputStream(lockedFile).use { rawIn ->
                val magic = ByteArray(MAGIC.length)
                if (rawIn.read(magic) != magic.size || String(magic, Charsets.US_ASCII) != MAGIC) {
                    return false
                }
                val salt = ByteArray(SALT_LEN)
                val iv = ByteArray(IV_LEN)
                if (rawIn.read(salt) != SALT_LEN || rawIn.read(iv) != IV_LEN) return false

                val key = deriveKey(password, salt)
                val cipher = Cipher.getInstance("AES/CBC/PKCS5Padding")
                cipher.init(Cipher.DECRYPT_MODE, key, IvParameterSpec(iv))

                FileOutputStream(outFile).use { output ->
                    CipherInputStream(rawIn, cipher).use { cIn ->
                        cIn.copyTo(output, bufferSize = 1 shl 16)
                    }
                }
            }

            // Xác nhận thêm bằng chữ ký MP4 thật ("ftyp" ở offset 4) - sai mật khẩu mà vẫn né
            // được lỗi đệm (cực hiếm) thì bước này chặn nốt.
            val header = ByteArray(8)
            FileInputStream(outFile).use { it.read(header) }
            val looksLikeMp4 = header.size >= 8 &&
                header[4] == 'f'.code.toByte() && header[5] == 't'.code.toByte() &&
                header[6] == 'y'.code.toByte() && header[7] == 'p'.code.toByte()

            if (!looksLikeMp4) {
                outFile.delete()
                return false
            }
            return true
        } catch (e: Exception) {
            // Sai mật khẩu -> Cipher ném BadPaddingException/IllegalBlockSizeException ở đây
            outFile.delete()
            return false
        }
    }
}
