package com.barrotalves.niimbot

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import android.widget.Button
import android.widget.EditText
import android.widget.ImageView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.OutputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private val DEVICE_NAME = "D110"
    private val SPP_UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")

    private var btSocket: BluetoothSocket? = null
    private var btStream: OutputStream? = null

    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnConnect: Button
    private lateinit var btnTest: Button
    private lateinit var ivPreview: ImageView
    private lateinit var etAncho: EditText
    private lateinit var etAlto: EditText
    private lateinit var etDpi: EditText

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)
            initUI()
        } catch (e: Throwable) {
            // Si algo falla al iniciar, mostramos el error DIRECTO en pantalla
            // (sin diálogos ni ventanas extra que puedan fallar también).
            val tv = TextView(this)
            tv.text = "❌ Error al iniciar la app:\n\n" + Log.getStackTraceString(e)
            tv.textSize = 12f
            tv.setPadding(30, 60, 30, 30)
            setContentView(tv)
        }
    }

    private fun initUI() {
        tvStatus  = findViewById(R.id.tvStatus)
        tvLog     = findViewById(R.id.tvLog)
        btnConnect= findViewById(R.id.btnConnect)
        btnTest   = findViewById(R.id.btnTest)
        ivPreview = findViewById(R.id.ivPreview)
        etAncho   = findViewById(R.id.etAncho)
        etAlto    = findViewById(R.id.etAlto)
        etDpi     = findViewById(R.id.etDpi)

        requestPerms()

        btnConnect.setOnClickListener { conectarBluetooth() }
        btnTest.setOnClickListener { generarYEnviarTest() }

        mostrarPreview()
    }

    private fun log(msg: String) {
        Log.d("NiimbotBarro", msg)
        val text = tvLog.text.toString()
        val lines = text.split("\n").takeLast(40)
        tvLog.text = (lines + msg).joinToString("\n")
    }

    private fun requestPerms() {
        val perms = arrayOf(
            Manifest.permission.BLUETOOTH_CONNECT,
            Manifest.permission.BLUETOOTH_SCAN,
            Manifest.permission.ACCESS_FINE_LOCATION
        )
        ActivityCompat.requestPermissions(this, perms, 1)
    }

    // ── Bluetooth ──────────────────────────────────────────────────
    private fun conectarBluetooth() {
        val adapter = BluetoothAdapter.getDefaultAdapter()
        if (adapter == null || !adapter.isEnabled) {
            log("⚠️ Bluetooth apagado")
            return
        }
        if (ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT)
            != PackageManager.PERMISSION_GRANTED) {
            log("⚠️ Sin permiso Bluetooth")
            return
        }
        val device: BluetoothDevice? = adapter.bondedDevices.firstOrNull {
            it.name?.contains(DEVICE_NAME, ignoreCase = true) == true
        }
        if (device == null) {
            log("❌ D110 no encontrada. Emparejala en Bluetooth primero.")
            return
        }
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                btSocket?.close()
                btSocket = device.createRfcommSocketToServiceRecord(SPP_UUID)
                adapter.cancelDiscovery()
                btSocket!!.connect()
                btStream = btSocket!!.outputStream
                withContext(Dispatchers.Main) {
                    log("✅ Conectada a ${device.name}")
                    tvStatus.text = "🖨 D110 conectada"
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) { log("❌ Error BT: ${e.message}") }
            }
        }
    }

    // ── Calibración ────────────────────────────────────────────────
    private fun mostrarPreview() {
        try {
            val anchoMm = etAncho.text.toString().toFloatOrNull() ?: 12f
            val altoMm  = etAlto.text.toString().toFloatOrNull() ?: 20f
            val dpi     = etDpi.text.toString().toFloatOrNull() ?: 203f
            val bmp = generarTest(anchoMm, altoMm, dpi)
            ivPreview.setImageBitmap(conBordeRojo(bmp))
        } catch (e: Exception) {
            log("⚠️ Vista previa: ${e.message}")
        }
    }

    // Genera una imagen de prueba: tamaño FÍSICO fijo (w,h = ancho,alto reales),
    // contenido dibujado "acostado" y rotado 90° para no alterar el tamaño enviado.
    private fun generarTest(anchoMm: Float, altoMm: Float, dpi: Float): Bitmap {
        val w = Math.round(anchoMm / 25.4f * dpi).coerceAtLeast(8)
        val h = Math.round(altoMm / 25.4f * dpi).coerceAtLeast(8)
        val contentW = h
        val contentH = w
        val content = Bitmap.createBitmap(contentW, contentH, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(content)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.BLACK
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        canvas.drawRect(2f, 2f, (contentW - 2).toFloat(), (contentH - 2).toFloat(), paint)
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = (contentH / 3.2f).coerceAtLeast(10f)
        canvas.drawText("TEST", contentW / 2f, contentH / 2f - 2f, paint)
        paint.textSize = (contentH / 6f).coerceAtLeast(8f)
        canvas.drawText("${anchoMm.toInt()}x${altoMm.toInt()}mm ${dpi.toInt()}dpi", contentW / 2f, contentH - 6f, paint)

        return rotar90(content)
    }

    // Copia la imagen agregándole un borde rojo — SOLO para mostrar en pantalla,
    // nunca se manda esta versión a la impresora (el rojo se imprimiría como negro).
    private fun conBordeRojo(bmp: Bitmap): Bitmap {
        val out = Bitmap.createBitmap(bmp.width, bmp.height, Bitmap.Config.ARGB_8888)
        val oc = Canvas(out)
        oc.drawBitmap(bmp, 0f, 0f, null)
        val rp = Paint()
        rp.color = Color.RED
        rp.style = Paint.Style.STROKE
        rp.strokeWidth = 3f
        oc.drawRect(1f, 1f, (out.width - 1).toFloat(), (out.height - 1).toFloat(), rp)
        return out
    }

    private fun rotar90(bmp: Bitmap): Bitmap {
        val matrix = Matrix()
        matrix.postRotate(90f)
        return Bitmap.createBitmap(bmp, 0, 0, bmp.width, bmp.height, matrix, true)
    }

    // ── Test: generar, previsualizar y enviar ─────────────────────
    private fun generarYEnviarTest() {
        val anchoMm = etAncho.text.toString().toFloatOrNull() ?: 12f
        val altoMm  = etAlto.text.toString().toFloatOrNull() ?: 20f
        val dpi     = etDpi.text.toString().toFloatOrNull() ?: 203f

        val bmp = generarTest(anchoMm, altoMm, dpi)
        ivPreview.setImageBitmap(conBordeRojo(bmp))

        val stream = btStream
        if (stream == null) {
            log("❌ Conectá la D110 primero")
            return
        }

        lifecycleScope.launch(Dispatchers.IO) {
            ulog("═══ TEST ${anchoMm}×${altoMm}mm @ ${dpi}dpi → ${bmp.width}x${bmp.height}px ═══")
            try {
                enviarImagen(stream, bmp)
                ulog("═══ Fin del test ═══")
            } catch (e: Exception) {
                ulog("❌ Error enviando: ${e.message}")
            }
        }
    }

    private suspend fun ulog(msg: String) = withContext(Dispatchers.Main) { log(msg) }

    // ── Protocolo Niimbot ──────────────────────────────────────────
    private suspend fun enviarImagen(out: OutputStream, bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height

        enviarYLoguear(out, 0x23, byteArrayOf(0x01), "SET_LABEL_TYPE")
        enviarYLoguear(out, 0x21, byteArrayOf(0x03), "SET_LABEL_DENSITY")
        enviarYLoguear(out, 0x01, byteArrayOf(0x01), "START_PRINT")
        enviarYLoguear(out, 0x20, byteArrayOf(0x01), "ALLOW_PRINT_CLEAR")
        enviarYLoguear(out, 0x03, byteArrayOf(0x01), "START_PAGE")
        enviarYLoguear(out, 0x13, intToBytes2(w) + intToBytes2(h), "SET_DIMENSION")
        enviarYLoguear(out, 0x15, byteArrayOf(0x00, 0x01), "SET_QUANTITY")

        ulog("Enviando $h filas de imagen ($w px de ancho)...")
        for (y in 0 until h) {
            val rowBytes = ByteArray((w + 7) / 8)
            for (x in 0 until w) {
                val px = bmp.getPixel(x, y)
                val dark = (Color.red(px) + Color.green(px) + Color.blue(px)) < 382
                if (dark) rowBytes[x / 8] = (rowBytes[x / 8].toInt() or (0x80 shr (x % 8))).toByte()
            }
            val lineData = intToBytes2(y) + byteArrayOf(0x00, 0x00, 0x00, 0x01) + rowBytes
            sendPacket(out, 0x85, lineData)
        }
        ulog("Filas enviadas ✓")

        enviarYLoguear(out, 0xE3, byteArrayOf(0x01), "END_PAGE")
        delay(300)
        enviarYLoguear(out, 0xF3, byteArrayOf(0x01), "END_PRINT")
        out.flush()
        delay(300)
        enviarYLoguear(out, 0xA3, byteArrayOf(0x01), "GET_PRINT_STATUS")
    }

    private suspend fun enviarYLoguear(out: OutputStream, cmd: Int, data: ByteArray, nombre: String) {
        sendPacket(out, cmd, data)
        val stream = btSocket?.inputStream
        if (stream == null) {
            ulog("  ⚠️ $nombre: sin conexión de entrada")
            return
        }
        val start = System.currentTimeMillis()
        while (stream.available() <= 0 && System.currentTimeMillis() - start < 800) {
            delay(30)
        }
        val disponible = stream.available()
        if (disponible <= 0) {
            ulog("  📭 $nombre: sin respuesta")
            return
        }
        val buf = ByteArray(disponible)
        val leidos = stream.read(buf)
        val hex = buf.take(leidos).joinToString(" ") { "%02X".format(it) }
        ulog("  📥 $nombre → $hex")
    }

    private fun sendPacket(out: OutputStream, cmd: Int, data: ByteArray) {
        val packet = ByteArray(data.size + 7)
        packet[0] = 0x55.toByte(); packet[1] = 0x55.toByte()
        packet[2] = cmd.toByte()
        packet[3] = data.size.toByte()
        System.arraycopy(data, 0, packet, 4, data.size)
        var cs = packet[2].toInt() and 0xFF
        for (i in 3 until 4 + data.size) cs = cs xor (packet[i].toInt() and 0xFF)
        packet[4 + data.size] = cs.toByte()
        packet[5 + data.size] = 0xAA.toByte()
        packet[6 + data.size] = 0xAA.toByte()
        out.write(packet)
        Thread.sleep(15)
    }

    private fun intToBytes2(v: Int): ByteArray =
        byteArrayOf(((v shr 8) and 0xFF).toByte(), (v and 0xFF).toByte())
}
