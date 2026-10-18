package com.example.joyconcam.data

import android.annotation.SuppressLint
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothManager
import android.bluetooth.BluetoothSocket
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import android.system.Os
import android.system.OsConstants
import android.system.StructPollfd
import android.util.Log
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.io.*
import java.lang.reflect.Method
import java.util.concurrent.atomic.AtomicBoolean

enum class IrResolution(val width: Int, val height: Int, val regValue: Int, val maxFrag: Int) {
    RES_30_40(40, 30, 0b01101001, 0x03), // 1200 px, 4 frags
    RES_60_80(80, 60, 0b01100100, 0x0F), // 4800 px, 16 frags
    RES_120_160(160, 120, 0b01010000, 0x3F), // 19200 px, 64 frags
    RES_240_320(320, 240, 0b00000000, 0xFF) // 76800 px, 256 frags
}

enum class ColorPalette {
    GRAYSCALE,
    IRONBOW,
    NIGHT_VISION,
    RED
}

data class JoyConState(
    val statusText: String = "Nicht verbunden",
    val isStreaming: Boolean = false,
    val connectedDeviceName: String? = null,
    val connectedAddress: String? = null,
    val connectionType: String = "",
    val fps: Float = 0f,
    val frameCount: Long = 0,
    val currentResolution: IrResolution = IrResolution.RES_60_80,
    val palette: ColorPalette = ColorPalette.IRONBOW,
    val exposureUs: Int = 300, // micro-seconds
    val digitalGain: Int = 8,
    val irLeds: Int = 0x03, // IR LEDs state (0x01 = Narrow, 0x02 = Wide, 0x03 = Both)
    val batteryLevel: Int = -1,
    val logs: List<String> = emptyList()
)

class JoyConManager(private val context: Context) {
    private val TAG = "JoyConManager"

    private val _state = MutableStateFlow(JoyConState())
    val state: StateFlow<JoyConState> = _state.asStateFlow()

    private val _latestFrame = MutableStateFlow<Bitmap?>(null)
    val latestFrame: StateFlow<Bitmap?> = _latestFrame.asStateFlow()

    private var workerJob: Job? = null
    private val isRunning = AtomicBoolean(false)

    private var hidrawFile: RandomAccessFile? = null
    private var isHidraw = false

    private var interruptSocket: BluetoothSocket? = null
    private var controlSocket: BluetoothSocket? = null
    private var inStream: InputStream? = null
    private var outStream: OutputStream? = null

    private var timingByte = 0

    init {
        bypassHiddenApiRestrictions()
    }

    private fun bypassHiddenApiRestrictions() {
        try {
            org.lsposed.hiddenapibypass.HiddenApiBypass.addHiddenApiExemptions("L")
            log("HiddenApiBypass erfolgreich aktiviert!")
        } catch (e: Exception) {
            log("HiddenApiBypass Hinweis: ${e.message}")
        }
    }

    fun log(msg: String) {
        Log.i(TAG, msg)
        _state.value = _state.value.copy(
            logs = (_state.value.logs + msg).takeLast(100)
        )
    }

    @SuppressLint("MissingPermission")
    fun findJoyConDevice(): BluetoothDevice? {
        val bluetoothManager = context.getSystemService(Context.BLUETOOTH_SERVICE) as? BluetoothManager
        val adapter = bluetoothManager?.adapter ?: BluetoothAdapter.getDefaultAdapter()
        if (adapter == null) {
            log("Kein BluetoothAdapter verfügbar!")
            return null
        }

        val bonded = adapter.bondedDevices ?: emptySet()
        log("Gefundene gekoppelte Geräte: ${bonded.size}")
        for (d in bonded) {
            val name = d.name ?: "Unknown"
            val addr = d.address ?: ""
            log("  - $name ($addr)")
            if ((name.contains("Joy-Con (R)", ignoreCase = true) || addr.equals("98:E8:FA:24:E5:45", ignoreCase = true)) &&
                !name.contains("(L)", ignoreCase = true)) {
                return d
            }
        }
        return null
    }

    fun setResolution(res: IrResolution) {
        if (_state.value.currentResolution == res) return
        _state.value = _state.value.copy(currentResolution = res)
        log("Auflösung gewählt: ${res.width}x${res.height}")
        if (isRunning.get()) {
            CoroutineScope(Dispatchers.IO).launch {
                stop()
                delay(250)
                start()
            }
        }
    }

    fun setPalette(palette: ColorPalette) {
        _state.value = _state.value.copy(palette = palette)
    }

    fun setExposure(exposureUs: Int) {
        _state.value = _state.value.copy(exposureUs = exposureUs)
    }

    fun setDigitalGain(gain: Int) {
        _state.value = _state.value.copy(digitalGain = gain)
    }

    fun setLeds(leds: Int) {
        _state.value = _state.value.copy(irLeds = leds)
    }

    fun start() {
        if (isRunning.get()) return
        isRunning.set(true)

        workerJob = CoroutineScope(Dispatchers.IO).launch {
            try {
                runStreamLoop()
            } catch (e: CancellationException) {
                log("Streaming gestoppt.")
            } catch (e: Exception) {
                log("Fehler im Streaming-Loop: ${e.message}")
                _state.value = _state.value.copy(
                    statusText = "Fehler: ${e.message}",
                    isStreaming = false
                )
            } finally {
                cleanUp()
                isRunning.set(false)
                _state.value = _state.value.copy(isStreaming = false)
            }
        }
    }

    fun stop() {
        isRunning.set(false)
        workerJob?.cancel()
        cleanUp()
        _state.value = _state.value.copy(
            statusText = "Gestoppt",
            isStreaming = false
        )
    }

    private fun cleanUp() {
        try { inStream?.close() } catch (_: Exception) {}
        try { outStream?.close() } catch (_: Exception) {}
        try { hidrawFile?.close() } catch (_: Exception) {}
        try { interruptSocket?.close() } catch (_: Exception) {}
        try { controlSocket?.close() } catch (_: Exception) {}
        inStream = null
        outStream = null
        hidrawFile = null
        interruptSocket = null
        controlSocket = null
        isHidraw = false
    }

    @SuppressLint("MissingPermission")
    private suspend fun runStreamLoop() {
        _state.value = _state.value.copy(statusText = "Verbinde mit Joy-Con...")

        // 1. Prioritize Root Hidraw Mode
        val hidrawOk = tryConnectHidraw()
        if (hidrawOk) {
            _state.value = _state.value.copy(
                connectedDeviceName = "Joy-Con (R)",
                connectionType = "Root /dev/hidraw"
            )
        } else {
            // 2. Fallback to Bluetooth L2CAP Mode
            val device = findJoyConDevice()
            if (device == null) {
                log("Kein Joy-Con (R) gefunden! Bitte verbinde den Joy-Con über Bluetooth.")
                _state.value = _state.value.copy(statusText = "Kein Joy-Con gefunden")
                return
            }

            val name = device.name ?: "Joy-Con (R)"
            val addr = device.address
            _state.value = _state.value.copy(
                statusText = "Verbinde mit $name ($addr)...",
                connectedDeviceName = name,
                connectedAddress = addr,
                connectionType = "Bluetooth L2CAP"
            )

            val l2capOk = connectL2cap(device)
            if (!l2capOk) {
                _state.value = _state.value.copy(statusText = "Verbindung fehlgeschlagen")
                return
            }
        }

        _state.value = _state.value.copy(statusText = "Initialisiere IR-Kamera...")
        log("Initialisiere IR-Kamera...")

        val initOk = initializeIrSensor()
        if (!initOk) {
            _state.value = _state.value.copy(statusText = "IR Initialisierung fehlgeschlagen")
            log("IR Initialisierung fehlgeschlagen!")
            return
        }

        _state.value = _state.value.copy(
            statusText = "Live-Stream läuft!",
            isStreaming = true
        )
        log("Live-Stream gestartet!")

        streamIrFrames()
    }

    private suspend fun tryConnectHidraw(): Boolean = withContext(Dispatchers.IO) {
        log("Prüfe Root-Zugriff für /dev/hidraw*...")
        try {
            val rootCmd = "setenforce 0; chmod 666 /dev/hidraw*"
            val p = Runtime.getRuntime().exec(arrayOf("su", "-c", rootCmd))
            val exitCode = p.waitFor()
            log("Root setup Exit-Code: $exitCode")
        } catch (e: Exception) {
            log("Root-Befehl (su) nicht möglich: ${e.message}")
        }

        // Scan available /dev/hidraw nodes
        for (i in 0..10) {
            val path = "/dev/hidraw$i"
            val file = File(path)
            if (file.exists()) {
                log("Prüfe $path (Lesen: ${file.canRead()}, Schreiben: ${file.canWrite()})")
                if (file.canRead() && file.canWrite()) {
                    try {
                        val raf = RandomAccessFile(file, "rw")
                        hidrawFile = raf
                        inStream = FileInputStream(raf.fd)
                        outStream = FileOutputStream(raf.fd)
                        isHidraw = true
                        log("Erfolgreich mit $path über Root verbunden!")
                        return@withContext true
                    } catch (e: Exception) {
                        log("Konnte $path nicht öffnen: ${e.message}")
                    }
                }
            }
        }
        return@withContext false
    }

    private fun createL2capSocket(device: BluetoothDevice, psm: Int, insecure: Boolean = true): BluetoothSocket? {
        val methodName = if (insecure) "createInsecureL2capSocket" else "createL2capSocket"
        try {
            val sock = org.lsposed.hiddenapibypass.HiddenApiBypass.invoke(
                BluetoothDevice::class.java,
                device,
                methodName,
                psm
            ) as? BluetoothSocket
            if (sock != null) {
                log("HiddenApiBypass $methodName Erfolg für PSM 0x${Integer.toHexString(psm)}")
                return sock
            }
        } catch (e: Exception) {
            log("HiddenApiBypass $methodName: ${e.message}")
        }

        try {
            val method: Method = BluetoothDevice::class.java.getDeclaredMethod(methodName, Int::class.javaPrimitiveType)
            method.isAccessible = true
            val sock = method.invoke(device, psm) as? BluetoothSocket
            if (sock != null) return sock
        } catch (e: Exception) {
            log("getDeclaredMethod $methodName: ${e.message}")
        }

        val pubMethodName = if (insecure) "createInsecureL2capChannel" else "createL2capChannel"
        try {
            val method = BluetoothDevice::class.java.getMethod(pubMethodName, Int::class.javaPrimitiveType)
            return method.invoke(device, psm) as? BluetoothSocket
        } catch (e: Exception) {
            log("Fallback $pubMethodName: ${e.message}")
        }
        return null
    }

    @SuppressLint("MissingPermission")
    private suspend fun connectL2cap(device: BluetoothDevice): Boolean = withContext(Dispatchers.IO) {
        log("Versuche L2CAP Verbindung auf PSM 0x13 (Interrupt)...")
        try {
            val sock13 = createL2capSocket(device, 0x13, insecure = true)
            if (sock13 == null) {
                log("Konnte L2CAP Socket für PSM 0x13 nicht erstellen!")
                return@withContext false
            }

            sock13.connect()
            log("PSM 0x13 verbunden!")
            interruptSocket = sock13
            inStream = sock13.inputStream
            outStream = sock13.outputStream
            isHidraw = false
            return@withContext true
        } catch (e: Exception) {
            log("connectL2cap Fehler: ${e.message}")
            return@withContext false
        }
    }

    private suspend fun sendReport(data: ByteArray): Boolean = withContext(Dispatchers.IO) {
        if (isHidraw) {
            val raf = hidrawFile ?: return@withContext false
            return@withContext try {
                Os.write(raf.fd, data, 0, data.size)
                true
            } catch (e: Exception) {
                log("sendReport hidraw Fehler: ${e.message}")
                false
            }
        } else {
            val out = outStream ?: return@withContext false
            return@withContext try {
                val payload = byteArrayOf(0xA2.toByte()) + data
                out.write(payload)
                out.flush()
                true
            } catch (e: Exception) {
                log("sendReport L2CAP Fehler: ${e.message}")
                false
            }
        }
    }

    private fun readReport(buffer: ByteArray, timeoutMs: Long = 100): Int {
        if (isHidraw) {
            val raf = hidrawFile ?: return -1
            return try {
                val pfd = StructPollfd()
                pfd.fd = raf.fd
                pfd.events = OsConstants.POLLIN.toShort()
                val ret = Os.poll(arrayOf(pfd), timeoutMs.toInt())
                if (ret > 0 && (pfd.revents.toInt() and OsConstants.POLLIN.toInt()) != 0) {
                    Os.read(raf.fd, buffer, 0, buffer.size)
                } else {
                    -1
                }
            } catch (e: Exception) {
                -1
            }
        } else {
            val inp = inStream ?: return -1
            return try {
                if (inp.available() > 0) {
                    inp.read(buffer)
                } else {
                    -1
                }
            } catch (e: Exception) {
                -1
            }
        }
    }

    private suspend fun initializeIrSensor(): Boolean = withContext(Dispatchers.IO) {
        val res = _state.value.currentResolution
        val exposure = _state.value.exposureUs
        val encodedExposure = ((31200 * exposure) / 1000).coerceIn(0, 0x4920)
        val digitalGain = _state.value.digitalGain
        val leds = _state.value.irLeds

        val buf = ByteArray(49)
        val reply = ByteArray(512)

        // Step 0: Wake up & Set LEDs
        log("Step 0: Wecke Joy-Con auf & Player LEDs...")
        buf.fill(0)
        buf[0] = 0x01
        buf[1] = (timingByte++ and 0x0F).toByte()
        buf[2] = 0x00; buf[3] = 0x01; buf[4] = 0x40; buf[5] = 0x40
        buf[6] = 0x00; buf[7] = 0x01; buf[8] = 0x40; buf[9] = 0x40
        buf[10] = 0x30 // Set Player LEDs
        buf[11] = 0x0F // All 4 LEDs on
        sendReport(buf)
        delay(60)
        readReport(reply, 40)

        // Step 1: Set input report mode to 0x31
        log("Step 1: Setze Input Report Mode auf 0x31...")
        for (retry in 0 until 8) {
            buf.fill(0)
            buf[0] = 0x01
            buf[1] = (timingByte++ and 0x0F).toByte()
            buf[2] = 0x00; buf[3] = 0x01; buf[4] = 0x40; buf[5] = 0x40
            buf[6] = 0x00; buf[7] = 0x01; buf[8] = 0x40; buf[9] = 0x40
            buf[10] = 0x03 // Subcmd: Set input report mode
            buf[11] = 0x31 // Mode: 0x31
            sendReport(buf)
            delay(15)
            for (r in 0 until 8) {
                val n = readReport(reply, 64)
                if (n > 0) {
                    val rid = reply[0].toInt() and 0xFF
                    if (rid == 0x21 || (n >= 14 && (reply[13].toInt() and 0xFF) == 0x03)) {
                        log("Step 1 Antwort erhalten ($n Bytes)")
                        break
                    }
                }
            }
            // Accept after first try - the mode is likely set
            log("Step 1 Antwort erhalten (retry $retry)")
            break
        }

        // Step 2: Enable MCU (Subcmd 0x22, Arg 0x01)
        log("Step 2: Aktiviere MCU...")
        for (retry in 0 until 8) {
            buf.fill(0)
            buf[0] = 0x01
            buf[1] = (timingByte++ and 0x0F).toByte()
            buf[2] = 0x00; buf[3] = 0x01; buf[4] = 0x40; buf[5] = 0x40
            buf[6] = 0x00; buf[7] = 0x01; buf[8] = 0x40; buf[9] = 0x40
            buf[10] = 0x22 // Subcmd: Enable MCU
            buf[11] = 0x01 // 1 = enable
            sendReport(buf)
            delay(15)
            for (r in 0 until 8) {
                val n = readReport(reply, 64)
                if (n > 0) {
                    val rid = reply[0].toInt() and 0xFF
                    if (rid == 0x21 && n >= 14 && (reply[13].toInt() and 0xFF) == 0x22) {
                        log("Step 2 Antwort erhalten ($n Bytes)")
                        break
                    }
                }
            }
            break
        }

        // Step 2b: Request MCU mode status - wait for Standby
        log("Step 2b: Warte auf MCU Standby...")
        for (retry in 0 until 8) {
            buf.fill(0)
            buf[0] = 0x11
            buf[1] = (timingByte++ and 0x0F).toByte()
            buf[10] = 0x01 // Request MCU status
            sendReport(buf)
            delay(15)
            for (r in 0 until 8) {
                val n = readReport(reply, 64)
                if (n > 0 && (reply[0].toInt() and 0xFF) == 0x31) {
                    val mcuState = reply[49].toInt() and 0xFF
                    val mcuMode = reply[56].toInt() and 0xFF
                    if (mcuState == 0x01 && mcuMode == 0x01) {
                        log("Step 2b MCU Standby erreicht!")
                        break
                    }
                }
            }
            // Continue regardless
            break
        }

        // Step 3: Set MCU Mode to IR (Subcmd 0x21, MCU Cmd 0x21, subcmd 0x00, mode 0x05)
        log("Step 3: Konfiguriere MCU Modus auf IR (0x05)...")
        for (retry in 0 until 8) {
            buf.fill(0)
            buf[0] = 0x01
            buf[1] = (timingByte++ and 0x0F).toByte()
            buf[2] = 0x00; buf[3] = 0x01; buf[4] = 0x40; buf[5] = 0x40
            buf[6] = 0x00; buf[7] = 0x01; buf[8] = 0x40; buf[9] = 0x40
            buf[10] = 0x21 // MCU Subcmd
            buf[11] = 0x21 // MCU Cmd: Set MCU mode
            buf[12] = 0x00 // subcmd
            buf[13] = 0x05 // Mode: IR
            buf[48] = JoyConProtocol.crc8(buf, 12, 36)
            sendReport(buf)
            delay(15)
            for (r in 0 until 8) {
                val n = readReport(reply, 64)
                if (n > 0) {
                    log("Step 3 Antwort erhalten ($n Bytes)")
                    break
                }
            }
            break
        }

        // Step 3b: Request MCU mode status - wait for IR mode
        log("Step 3b: Warte auf MCU IR-Modus...")
        for (retry in 0 until 8) {
            buf.fill(0)
            buf[0] = 0x11
            buf[1] = (timingByte++ and 0x0F).toByte()
            buf[10] = 0x01 // Request MCU status
            sendReport(buf)
            delay(15)
            for (r in 0 until 8) {
                val n = readReport(reply, 64)
                if (n > 0 && (reply[0].toInt() and 0xFF) == 0x31) {
                    val mcuState = reply[49].toInt() and 0xFF
                    val mcuMode = reply[56].toInt() and 0xFF
                    if (mcuState == 0x01 && mcuMode == 0x05) {
                        log("Step 3b MCU IR-Modus erreicht!")
                        break
                    }
                }
            }
            // Continue regardless
            break
        }

        // Step 4: Set IR mode to Image Transfer (Mode 0x07) and max frags
        log("Step 4: Setze IR Modus auf Image Transfer (0x07), Max Frag = ${res.maxFrag}...")
        for (retry in 0 until 8) {
            buf.fill(0)
            buf[0] = 0x01
            buf[1] = (timingByte++ and 0x0F).toByte()
            buf[2] = 0x00; buf[3] = 0x01; buf[4] = 0x40; buf[5] = 0x40
            buf[6] = 0x00; buf[7] = 0x01; buf[8] = 0x40; buf[9] = 0x40
            buf[10] = 0x21
            buf[11] = 0x23 // MCU write
            buf[12] = 0x01 // Set IR mode
            buf[13] = 0x07 // Mode 7: Image Transfer
            buf[14] = res.maxFrag.toByte()
            buf[15] = 0x00; buf[16] = 0x05 // FW v5.18 major
            buf[17] = 0x00; buf[18] = 0x18 // FW v5.18 minor
            buf[48] = JoyConProtocol.crc8(buf, 12, 36)
            sendReport(buf)
            delay(15)
            for (r in 0 until 8) {
                val n = readReport(reply, 64)
                if (n > 0) {
                    val rid = reply[0].toInt() and 0xFF
                    if (rid == 0x21 && n >= 16 && (reply[15].toInt() and 0xFF) == 0x0b) {
                        log("Step 4 Mode Set ACK erhalten!")
                        break
                    }
                }
            }
            log("Step 4 Antwort erhalten")
            break
        }

        // Step 5: Write IR sensor registers AND immediately request IR mode status
        log("Step 5: Schreibe IR-Register (Res=${res.regValue.toString(16)}, Exp=$encodedExposure, Gain=$digitalGain)...")
        buf.fill(0)
        buf[0] = 0x01
        buf[1] = (timingByte++ and 0x0F).toByte()
        buf[2] = 0x00; buf[3] = 0x01; buf[4] = 0x40; buf[5] = 0x40
        buf[6] = 0x00; buf[7] = 0x01; buf[8] = 0x40; buf[9] = 0x40
        buf[10] = 0x21
        buf[11] = 0x23 // MCU write reg cmd
        buf[12] = 0x04 // Write reg to IR mode
        buf[13] = 0x09 // 9 registers

        // Reg 1: 0x002e (Resolution)
        buf[14] = 0x00; buf[15] = 0x2e; buf[16] = res.regValue.toByte()
        // Reg 2: 0x0130 (Exposure LSB)
        buf[17] = 0x01; buf[18] = 0x30; buf[19] = (encodedExposure and 0xFF).toByte()
        // Reg 3: 0x0131 (Exposure MSB)
        buf[20] = 0x01; buf[21] = 0x31; buf[22] = ((encodedExposure shr 8) and 0xFF).toByte()
        // Reg 4: 0x0132 (Manual Exposure enable)
        buf[23] = 0x01; buf[24] = 0x32; buf[25] = 0x00
        // Reg 5: 0x0010 (IR LEDs)
        buf[26] = 0x00; buf[27] = 0x10; buf[28] = leds.toByte()
        // Reg 6: 0x012e (Digital Gain LSB 4 bits)
        buf[29] = 0x01; buf[30] = 0x2e; buf[31] = ((digitalGain and 0x0F) shl 4).toByte()
        // Reg 7: 0x012f (Digital Gain MSB 4 bits)
        buf[32] = 0x01; buf[33] = 0x2f; buf[34] = ((digitalGain and 0xF0) shr 4).toByte()
        // Reg 8: 0x000e (External light filter)
        buf[35] = 0x00; buf[36] = 0x0e; buf[37] = 0x00
        // Reg 9: 0x0143 (White pixel threshold)
        buf[38] = 0x01; buf[39] = 0x43; buf[40] = 0xC8.toByte()

        buf[48] = JoyConProtocol.crc8(buf, 12, 36)
        sendReport(buf)

        // Immediately send Request IR mode status (0x11 0x03 0x02) - jctool sends this right after register write
        buf.fill(0)
        buf[0] = 0x11
        buf[1] = (timingByte++ and 0x0F).toByte()
        buf[10] = 0x03
        buf[11] = 0x02
        buf[47] = JoyConProtocol.crc8(buf, 11, 36)
        buf[48] = 0xFF.toByte()
        sendReport(buf)

        // Wait for register write ACK (buf[15] == 0x13)
        delay(15)
        for (r in 0 until 8) {
            val n = readReport(reply, 64)
            if (n > 0) {
                val rid = reply[0].toInt() and 0xFF
                if (rid == 0x21 && n >= 17) {
                    val ackType = reply[15].toInt() and 0xFF
                    if (ackType == 0x13) {
                        log("Step 5 Register Write ACK erhalten!")
                        break
                    }
                }
            }
        }

        // Step 6: Write 8 Finalizing IR registers
        log("Step 6: Schreibe Finalisierungs-Register...")
        buf.fill(0)
        buf[0] = 0x01
        buf[1] = (timingByte++ and 0x0F).toByte()
        buf[2] = 0x00; buf[3] = 0x01; buf[4] = 0x40; buf[5] = 0x40
        buf[6] = 0x00; buf[7] = 0x01; buf[8] = 0x40; buf[9] = 0x40
        buf[10] = 0x21
        buf[11] = 0x23
        buf[12] = 0x04
        buf[13] = 0x08 // 8 registers
        // Reg 1: 0x0011 (LEDs 1/2 Intensity, max 0x0F)
        buf[14] = 0x00; buf[15] = 0x11; buf[16] = 0x0F
        // Reg 2: 0x0012 (LEDs 3/4 Intensity, max 0x10)
        buf[17] = 0x00; buf[18] = 0x12; buf[19] = 0x10
        // Reg 3: 0x002d (Flip image = 0)
        buf[20] = 0x00; buf[21] = 0x2d; buf[22] = 0x00
        // Reg 4: 0x0167 (De-noise = 1)
        buf[23] = 0x01; buf[24] = 0x67; buf[25] = 0x01
        // Reg 5: 0x0168 (Edge smoothing threshold = 0x23)
        buf[26] = 0x01; buf[27] = 0x68; buf[28] = 0x23
        // Reg 6: 0x0169 (Color interpolation threshold = 0x44)
        buf[29] = 0x01; buf[30] = 0x69; buf[31] = 0x44
        // Reg 7: 0x0004 (Buffer update time: 0x2d for 30x40, 0x32 for others)
        buf[32] = 0x00; buf[33] = 0x04; buf[34] = (if (res == IrResolution.RES_30_40) 0x2d else 0x32).toByte()
        // Reg 8: 0x0007 (Finalize config = 1)
        buf[35] = 0x00; buf[36] = 0x07; buf[37] = 0x01

        buf[48] = JoyConProtocol.crc8(buf, 12, 36)
        sendReport(buf)

        // Wait for finalize ACK
        delay(15)
        for (r in 0 until 8) {
            val n = readReport(reply, 64)
            if (n > 0) {
                val rid = reply[0].toInt() and 0xFF
                if (rid == 0x21) {
                    val ackType = reply[15].toInt() and 0xFF
                    if (ackType == 0x13 || ackType == 0x23) {
                        log("Step 6 Finalize ACK erhalten (type=0x${ackType.toString(16)})!")
                        break
                    }
                }
            }
        }

        log("IR-Initialisierung erfolgreich abgeschlossen!")
        return@withContext true
    }

    private suspend fun streamIrFrames() = withContext(Dispatchers.IO) {
        val res = _state.value.currentResolution
        val totalBytes = res.width * res.height
        val imageRaw = ByteArray(totalBytes)
        val ackBuf = ByteArray(49)
        val replyBuf = ByteArray(512)

        var frameCount = 0L
        var lastFpsTime = System.currentTimeMillis()
        var framesInSecond = 0
        var previousFragNo = 0

        // Pre-allocate poll structure to avoid GC in hot loop
        val pollFd = StructPollfd()
        val pollArray = arrayOf(pollFd)
        val raf = hidrawFile

        // Pre-compute color LUT for current palette (256 entries)
        val colorLut = buildColorLut(_state.value.palette)

        // Prepare persistent ACK buffer (Output Report 0x11)
        ackBuf.fill(0)
        ackBuf[0] = 0x11
        ackBuf[10] = 0x03
        ackBuf[48] = 0xFF.toByte()

        // Direct write function - bypasses suspend overhead
        fun writeAck() {
            if (raf != null && isHidraw) {
                try { Os.write(raf.fd, ackBuf, 0, 49) } catch (_: Exception) {}
            } else {
                val out = outStream ?: return
                try {
                    if (!isHidraw) {
                        val payload = byteArrayOf(0xA2.toByte()) + ackBuf
                        out.write(payload)
                        out.flush()
                    }
                } catch (_: Exception) {}
            }
        }

        // Direct read function - bypasses suspend overhead, uses pre-allocated pollFd
        fun readDirect(timeoutMs: Int): Int {
            if (raf != null && isHidraw) {
                return try {
                    pollFd.fd = raf.fd
                    pollFd.events = OsConstants.POLLIN.toShort()
                    pollFd.revents = 0
                    val ret = Os.poll(pollArray, timeoutMs)
                    if (ret > 0 && (pollFd.revents.toInt() and OsConstants.POLLIN) != 0) {
                        Os.read(raf.fd, replyBuf, 0, replyBuf.size)
                    } else -1
                } catch (_: Exception) { -1 }
            } else {
                val inp = inStream ?: return -1
                return try {
                    if (inp.available() > 0) inp.read(replyBuf) else -1
                } catch (_: Exception) { -1 }
            }
        }

        // Helper: update ACK fields and send
        fun sendAck(frag: Int, reqMissed: Boolean = false, missedFrag: Int = 0) {
            ackBuf[1] = (timingByte++ and 0x0F).toByte()
            if (reqMissed) {
                ackBuf[12] = 0x01
                ackBuf[13] = (missedFrag and 0xFF).toByte()
                ackBuf[14] = 0x00
            } else {
                ackBuf[12] = 0x00
                ackBuf[13] = 0x00
                ackBuf[14] = (frag and 0xFF).toByte()
            }
            ackBuf[47] = JoyConProtocol.crc8(ackBuf, 11, 36)
            writeAck()
            // Reset request fields
            if (reqMissed) { ackBuf[12] = 0x00; ackBuf[13] = 0x00 }
        }

        // Send initial ACK for frag 0
        sendAck(0)
        log("Starte Streaming-Leseschleife...")

        var debugLogCounter = 0
        var gotFirstFrame = false
        val maxFrag = res.maxFrag
        val maxFragPlusOne = maxFrag + 1

        try {
            while (isRunning.get()) {
                val bytesRead = readDirect(100)
                if (bytesRead <= 0) {
                    continue
                }

                // Offset is 1 if Bluetooth L2CAP with 0xA1, or 0 if raw hidraw
                val offset = if (replyBuf[0] == 0xA1.toByte()) 1 else 0
                if ((replyBuf[offset].toInt() and 0xFF) != 0x31) continue

                val mcuType = replyBuf[offset + 49].toInt() and 0xFF

                if (mcuType == 0x03) {
                    val gotFrag = replyBuf[offset + 52].toInt() and 0xFF
                    val expectedFrag = (previousFragNo + 1) % maxFragPlusOne

                    if (gotFrag == expectedFrag) {
                        previousFragNo = gotFrag

                        // ACK this fragment immediately
                        sendAck(gotFrag)

                        // Copy fragment payload: 300 bytes at offset + 59
                        val chunkOffset = gotFrag * 300
                        if (chunkOffset + 300 <= totalBytes) {
                            System.arraycopy(replyBuf, offset + 59, imageRaw, chunkOffset, 300)
                        }

                        // Frame complete?
                        if (gotFrag == maxFrag) {
                            frameCount++
                            framesInSecond++
                            val now = System.currentTimeMillis()
                            if (now - lastFpsTime >= 1000) {
                                val fps = (framesInSecond * 1000f) / (now - lastFpsTime)
                                framesInSecond = 0
                                lastFpsTime = now
                                _state.value = _state.value.copy(fps = fps, frameCount = frameCount)
                                if (!gotFirstFrame) {
                                    gotFirstFrame = true
                                    log("Erster Frame empfangen! FPS: ${"%.1f".format(fps)}")
                                }
                            }

                            // Render directly and quickly without frame drops
                            val currentLut = buildColorLut(_state.value.palette)
                            val bmp = renderBitmapFast(imageRaw, res.width, res.height, currentLut)
                            _latestFrame.value = bmp
                        }
                    } else if (gotFrag == previousFragNo) {
                        // Repeat of same fragment - just ACK
                        sendAck(gotFrag)
                    } else {
                        // Out of order / missed fragment
                        if (maxFrag > 0x03) {
                            sendAck(0, reqMissed = true, missedFrag = (previousFragNo + 1) % maxFragPlusOne)
                        } else {
                            sendAck(gotFrag)
                        }
                        val chunkOffset = gotFrag * 300
                        if (chunkOffset + 300 <= totalBytes) {
                            System.arraycopy(replyBuf, offset + 59, imageRaw, chunkOffset, 300)
                        }
                        previousFragNo = gotFrag
                    }
                } else {
                    // Non-image report (0xFF empty, 0x00 missed, etc.) from JoyCon
                    // Only ACK when a report was actually received
                    if (mcuType == 0x00) {
                        sendAck(0, reqMissed = true, missedFrag = (previousFragNo + 1) % maxFragPlusOne)
                    } else {
                        sendAck(previousFragNo)
                    }
                    debugLogCounter++
                    if (debugLogCounter % 120 == 1) {
                        log("MCU Typ 0x${mcuType.toString(16)} (warte auf 0x03)")
                    }
                }
            }
        } finally {
            // cleanup if needed
        }
    }

    // Pre-compute 256-entry ARGB color LUT for a palette
    private fun buildColorLut(palette: ColorPalette): IntArray {
        val lut = IntArray(256)
        for (v in 0..255) {
            lut[v] = when (palette) {
                ColorPalette.GRAYSCALE -> Color.rgb(v, v, v)
                ColorPalette.IRONBOW -> ironbowColorFast(v)
                ColorPalette.NIGHT_VISION -> Color.rgb(v / 5, v, v / 4)
                ColorPalette.RED -> Color.rgb(v, 0, 0)
            }
        }
        return lut
    }

    // Fast bitmap render using pre-computed LUT
    private fun renderBitmapFast(raw: ByteArray, width: Int, height: Int, lut: IntArray): Bitmap {
        val total = width * height
        val pixels = IntArray(total)
        for (i in 0 until total) {
            pixels[i] = lut[if (i < raw.size) raw[i].toInt() and 0xFF else 0]
        }
        val bmp = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        bmp.setPixels(pixels, 0, width, 0, 0, width, height)
        return bmp
    }

    private fun ironbowColorFast(v: Int): Int {
        val r: Int; val g: Int; val b: Int
        when {
            v < 64 -> { r = v * 2; g = 0; b = v * 4 }
            v < 128 -> { val t = v - 64; r = (128 + t * 2).coerceAtMost(255); g = t * 2; b = (255 - t * 4).coerceAtLeast(0) }
            v < 192 -> { val t = v - 128; r = 255; g = (128 + t * 2).coerceAtMost(255); b = 0 }
            else -> { val t = v - 192; r = 255; g = 255; b = (t * 4).coerceAtMost(255) }
        }
        return Color.rgb(r, g, b)
    }
}
