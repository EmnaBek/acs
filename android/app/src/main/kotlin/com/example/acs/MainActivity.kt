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
                            if (response.isNotEmpty()) {
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

    private fun readCard(result: MethodChannel.Result): String {
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

    private fun readCardWithDevice(device: UsbDevice): String {
        try {
            reader.open(device)
        } catch (e: Exception) {
            return "❌ Erreur ouverture lecteur: ${e.message}"
        }

        try {
            val state = reader.getState(0)
            if ((state and Reader.CARD_PRESENT) == 0) {
                return "❌ Carte non insérée"
            }

            val atr = try {
                reader.power(0, Reader.CARD_WARM_RESET)
            } catch (_: Exception) {
                reader.power(0, Reader.CARD_COLD_RESET)
            }

            val atrHex = atr.joinToString(" ") { byte -> "%02X".format(byte) }

            try {
                reader.setProtocol(0, Reader.PROTOCOL_T0 or Reader.PROTOCOL_T1)
            } catch (_: Exception) {
                // Certains lecteurs/cartes gèrent déjà le protocole automatiquement.
            }

            val command = byteArrayOf(
                0xFF.toByte(),
                0xCA.toByte(),
                0x00.toByte(),
                0x00.toByte(),
                0x00.toByte()
            )

            val response = ByteArray(256)
            val responseLength = reader.transmit(
                0,
                command,
                command.size,
                response,
                response.size
            )

            if (responseLength <= 0) {
                return "❌ Carte détectée mais aucune réponse APDU (ATR: $atrHex)"
            }

            val apdu = response.copyOfRange(0, responseLength)
            val apduHex = apdu.joinToString(" ") { byte -> "%02X".format(byte) }

            if (responseLength >= 2) {
                val sw1 = apdu[responseLength - 2].toInt() and 0xFF
                val sw2 = apdu[responseLength - 1].toInt() and 0xFF
                if (sw1 == 0x90 && sw2 == 0x00 && responseLength > 2) {
                    val uid = apdu.copyOfRange(0, responseLength - 2)
                        .joinToString(" ") { byte -> "%02X".format(byte) }
                    return "✅ Carte lue\nATR: $atrHex\nUID: $uid"
                }
                return "⚠️ Carte détectée\nATR: $atrHex\nRéponse APDU: $apduHex\nStatut: %02X %02X".format(sw1, sw2)
            }

            return "⚠️ Carte détectée\nATR: $atrHex\nRéponse APDU: $apduHex"
        } catch (e: Exception) {
            return "❌ Erreur lecture: ${e.message}"
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
}
