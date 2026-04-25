package com.example.acs

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import io.flutter.embedding.android.FlutterActivity
import io.flutter.embedding.engine.FlutterEngine
import io.flutter.plugin.common.MethodChannel
import com.acs.smartcard.Reader
import java.io.ByteArrayOutputStream
import java.nio.charset.StandardCharsets
import kotlin.experimental.or
class MainActivity : FlutterActivity() {

    private val CHANNEL = "acr39_reader"
    private val ACTION_USB_PERMISSION = "com.example.acs.USB_PERMISSION"

    private lateinit var usbManager: UsbManager
    private lateinit var reader: Reader
    private lateinit var permissionIntent: PendingIntent

    private var pendingDevice: UsbDevice? = null
    private var pendingResult: MethodChannel.Result? = null

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action != ACTION_USB_PERMISSION) return

            val device: UsbDevice? = intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
            val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)

            if (device == null || device != pendingDevice) return

            if (granted) {
                pendingResult?.let { result ->
                    try {
                        result.success(readCardWithDevice(device))
                    } catch (e: Exception) {
                        result.error("READ_ERROR", e.message, null)
                    }
                }
            } else {
                pendingResult?.error("READ_ERROR", "Permission USB refusée", null)
            }

            pendingDevice = null
            pendingResult = null
        }
    }

    override fun configureFlutterEngine(flutterEngine: FlutterEngine) {
        super.configureFlutterEngine(flutterEngine)

        usbManager = getSystemService(Context.USB_SERVICE) as UsbManager
        try {
            reader = Reader(usbManager)
        } catch (e: Exception) {
            // Reader initialization failed, will handle in readCard
        }

        permissionIntent = PendingIntent.getBroadcast(
            this,
            0,
            Intent(ACTION_USB_PERMISSION),
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )

        registerReceiver(usbReceiver, IntentFilter(ACTION_USB_PERMISSION), Context.RECEIVER_EXPORTED)

        MethodChannel(flutterEngine.dartExecutor.binaryMessenger, CHANNEL)
            .setMethodCallHandler { call, result ->
                when (call.method) {
                    "readCard" -> {
                        try {
                            val response = readCard(result)
                            if (response != "") {
                                result.success(response)
                            }
                        } catch (e: Exception) {
                            result.error("READ_ERROR", e.message, null)
                        }
                    }
                    else -> result.notImplemented()
                }
            }
    }

    private fun readCard(result: MethodChannel.Result): Any {
        if (!::reader.isInitialized) {
            return "❌ Lecteur non initialisé"
        }

        val devices = usbManager.deviceList

        if (devices.isEmpty()) {
            return "❌ Aucun lecteur connecté"
        }

        val supportedDevice = devices.values.firstOrNull { reader.isSupported(it) }
            ?: return "❌ Aucun périphérique ACS supporté"

        if (!usbManager.hasPermission(supportedDevice)) {
            pendingDevice = supportedDevice
            pendingResult = result
            usbManager.requestPermission(supportedDevice, permissionIntent)
            return ""
        }

        return readCardWithDevice(supportedDevice)
    }

    private fun readCardWithDevice(device: UsbDevice): Any {
        try {
            reader.open(device)
        } catch (e: Exception) {
            return "❌ Erreur ouverture lecteur: ${e.message}"
        }

        try {
            reader.power(0, Reader.CARD_WARM_RESET)
        } catch (e: Exception) {
            return "❌ Erreur alimentation carte: ${e.message}"
        }

        try {
            reader.setProtocol(0, Reader.PROTOCOL_T0 or Reader.PROTOCOL_T1)
        } catch (e: Exception) {
            return "❌ Erreur protocole: ${e.message}"
        }

        try {
            val state = reader.getState(0)
            if ((state and Reader.CARD_PRESENT) == 0) {
                return "❌ Carte non insérée"
            }
        } catch (e: Exception) {
            return "❌ Erreur état lecteur: ${e.message}"
        }

        val command = byteArrayOf(
            0x00.toByte(),
            0xA4.toByte(),
            0x04.toByte(),
            0x00.toByte(),
            0x07.toByte(),
            0xA0.toByte(),
            0x00.toByte(),
            0x00.toByte(),
            0x02.toByte(),
            0x47.toByte(),
            0x10.toByte(),
            0x01.toByte(),
            0x00.toByte()
        )

        val response = ByteArray(256)

        try {
            val responseLength = reader.transmit(
                0,
                command,
                command.size,
                response,
                response.size
            )

            if (responseLength < 2) {
                return "❌ Aucune réponse de la carte"
            }

            val sw1 = response[responseLength - 2].toInt() and 0xFF
            val sw2 = response[responseLength - 1].toInt() and 0xFF

            if (sw1 != 0x90 || sw2 != 0x00) {
                val hexResponse = response
                    .copyOfRange(0, responseLength)
                    .joinToString(" ") { byte -> "%02X".format(byte) }
                return "❌ Réponse APDU : $hexResponse"
            }

            // AID selected successfully, now read DG1
            selectFile(byteArrayOf(0x01, 0x01))
            val dg1 = readBinaryFull()
            val mrz = extractMrz(dg1)

            // Read DG2 for image
            selectFile(byteArrayOf(0x01, 0x02))
            val dg2 = readBinaryFull()
            val jp2 = extractJp2(dg2)

            val jp2File = java.io.File(getFilesDir(), "face.jp2")
            java.io.FileOutputStream(jp2File).use { it.write(jp2) }

            val displayFile = java.io.File(getFilesDir(), "face.png")
            var pngData: ByteArray? = null
            val imageMessage = try {
                val bitmap = decodeJp2ToBitmap(jp2, jp2File)
                    ?: throw RuntimeException("Impossible de décoder le JP2")

                java.io.FileOutputStream(displayFile).use { out ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                }
                ByteArrayOutputStream().use { baos ->
                    bitmap.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, baos)
                    pngData = baos.toByteArray()
                }
                "✅ Image décodée et renvoyée depuis le natif"
            } catch (e: Exception) {
                "⚠️ Image JP2 sauvegardée (affichage non supporté) :\n${jp2File.absolutePath}"
            }

            return mapOf(
                "mrz" to mrz,
                "message" to imageMessage,
                "pngPath" to displayFile.absolutePath.takeIf { pngData != null },
                "pngData" to pngData,
                "jp2Path" to jp2File.absolutePath,
            )

        } catch (e: Exception) {
            return "❌ Erreur transmission: ${e.message}"
        } finally {
            try {
                reader.close()
            } catch (_: Exception) {
                // Ignore
            }
        }
    }

    override fun onDestroy() {
        try {
            if (::reader.isInitialized) {
                reader.close()
            }
            unregisterReceiver(usbReceiver)
        } catch (_: Exception) {
        }
        super.onDestroy()
    }

    private data class Response(val data: ByteArray, val sw1: Int, val sw2: Int)

    private fun tx(apdu: ByteArray): Response {
        val r = transmit(apdu)
        if (r.sw1 == 0x6C) {
            apdu[apdu.size - 1] = r.sw2.toByte()
            return transmit(apdu)
        }
        return r
    }

    private fun transmit(apdu: ByteArray): Response {
        val response = ByteArray(65536)
        val length = reader.transmit(0, apdu, apdu.size, response, response.size)
        if (length < 2) {
            throw RuntimeException("Invalid card response")
        }
        val sw1 = response[length - 2].toInt() and 0xFF
        val sw2 = response[length - 1].toInt() and 0xFF
        val data = response.copyOfRange(0, length - 2)
        return Response(data, sw1, sw2)
    }

    private fun check(sw1: Int, sw2: Int, label: String) {
        if (!(sw1 == 0x90 && sw2 == 0x00)) {
            throw RuntimeException("$label failed: ${String.format("%02X%02X", sw1, sw2)}")
        }
    }

    private fun selectFile(fid: ByteArray) {
        val apdu = ByteArrayOutputStream()
        apdu.write(0x00)
        apdu.write(0xA4)
        apdu.write(0x02)
        apdu.write(0x0C)
        apdu.write(0x02)
        apdu.write(fid)
        val r = tx(apdu.toByteArray())
        check(r.sw1, r.sw2, "SELECT FILE ${bytesToHex(fid)}")
    }

    private fun readBinaryFull(): ByteArray {
        return readBinaryFull(0xE0, 65536)
    }

    private fun readBinaryFull(chunkSize: Int, maxLen: Int): ByteArray {
        val out = ByteArrayOutputStream()
        var offset = 0
        var expectedLen: Int? = null
        while (offset < maxLen) {
            val p1 = (offset shr 8) and 0x7F
            val p2 = offset and 0xFF
            val apdu = byteArrayOf(
                0x00, 0xB0.toByte(), p1.toByte(), p2.toByte(), chunkSize.toByte()
            )
            val r = tx(apdu)
            check(r.sw1, r.sw2, "READ BINARY offset=$offset")
            if (r.data.isEmpty()) break
            out.write(r.data)
            offset += r.data.size
            val current = out.toByteArray()
            if (expectedLen == null && current.size >= 4) {
                expectedLen = tlvTotalLength(current)
            }
            if (expectedLen != null && current.size >= expectedLen) break
        }
        return out.toByteArray()
    }

    private fun tlvTotalLength(data: ByteArray): Int {
        var i = 1
        if ((data[0].toInt() and 0x1F) == 0x1F) {
            while ((data[i].toInt() and 0x80) != 0) i++
            i++
        }
        val firstLen = data[i].toInt() and 0xFF
        i++
        val valueLen = if (firstLen < 0x80) firstLen else {
            val n = firstLen and 0x7F
            var vl = 0
            for (j in 0 until n) vl = (vl shl 8) or (data[i + j].toInt() and 0xFF)
            i += n
            vl
        }
        return i + valueLen
    }

    private fun extractMrz(dg1: ByteArray): String {
        val marker = hexToBytes("5F1F")
        val idx = indexOf(dg1, marker)
        if (idx == -1) throw RuntimeException("MRZ tag 5F1F not found")
        val lengthByte = dg1[idx + 2].toInt() and 0xFF
        val (length, start) = if (lengthByte < 0x80) {
            lengthByte to idx + 3
        } else {
            val n = lengthByte and 0x7F
            var len = 0
            for (i in 0 until n) len = (len shl 8) or (dg1[idx + 3 + i].toInt() and 0xFF)
            len to idx + 3 + n
        }
        return String(dg1.copyOfRange(start, start + length), StandardCharsets.US_ASCII)
    }

    private fun extractJp2(dg2: ByteArray): ByteArray {
        val sig = hexToBytes("0000000C6A5020200D0A870A")
        val idx = indexOf(dg2, sig)
        if (idx == -1) throw RuntimeException("JP2 image signature not found")
        val candidate = dg2.copyOfRange(idx, dg2.size)
        val length = jp2FileLength(candidate)
        return if (length > 0 && length <= candidate.size) {
            candidate.copyOfRange(0, length)
        } else {
            candidate
        }
    }

    private fun jp2FileLength(data: ByteArray): Int {
        var pos = 0
        while (pos + 8 <= data.size) {
            val length = ((data[pos].toInt() and 0xFF) shl 24) or
                    ((data[pos + 1].toInt() and 0xFF) shl 16) or
                    ((data[pos + 2].toInt() and 0xFF) shl 8) or
                    (data[pos + 3].toInt() and 0xFF)
            if (length == 0) {
                return data.size
            }
            if (length == 1) {
                if (pos + 16 > data.size) return data.size
                val extLen = ((data[pos + 8].toLong() and 0xFF) shl 56) or
                        ((data[pos + 9].toLong() and 0xFF) shl 48) or
                        ((data[pos + 10].toLong() and 0xFF) shl 40) or
                        ((data[pos + 11].toLong() and 0xFF) shl 32) or
                        ((data[pos + 12].toLong() and 0xFF) shl 24) or
                        ((data[pos + 13].toLong() and 0xFF) shl 16) or
                        ((data[pos + 14].toLong() and 0xFF) shl 8) or
                        (data[pos + 15].toLong() and 0xFF)
                if (extLen > Int.MAX_VALUE) return data.size
                val total = pos + extLen.toInt()
                return if (total <= data.size) total else data.size
            }
            if (length < 8) return data.size
            val total = pos + length
            if (total > data.size) return data.size
            pos = total
        }
        return data.size
    }

    private fun decodeJp2ToBitmap(jp2: ByteArray, jp2File: java.io.File): android.graphics.Bitmap? {
        // Try Android's built-in decoder first; some devices may support JP2.
        val bitmap = android.graphics.BitmapFactory.decodeByteArray(jp2, 0, jp2.size)
        if (bitmap != null) return bitmap

        // Fallback: try decoding from the saved file path.
        val fileBitmap = android.graphics.BitmapFactory.decodeFile(jp2File.absolutePath)
        if (fileBitmap != null) return fileBitmap

        return try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.P) {
                val source = android.graphics.ImageDecoder.createSource(jp2File)
                android.graphics.ImageDecoder.decodeBitmap(source)
            } else {
                null
            }
        } catch (_: Exception) {
            null
        }
    }

    private fun indexOf(data: ByteArray, pattern: ByteArray): Int {
        for (i in 0..data.size - pattern.size) {
            var match = true
            for (j in pattern.indices) {
                if (data[i + j] != pattern[j]) {
                    match = false
                    break
                }
            }
            if (match) return i
        }
        return -1
    }

    private fun hexToBytes(hex: String): ByteArray {
        val len = hex.length
        val out = ByteArray(len / 2)
        for (i in 0 until len step 2) {
            out[i / 2] = Integer.parseInt(hex.substring(i, i + 2), 16).toByte()
        }
        return out
    }

    private fun bytesToHex(data: ByteArray): String {
        val sb = StringBuilder()
        for (b in data) sb.append(String.format("%02X", b))
        return sb.toString()
    }
}