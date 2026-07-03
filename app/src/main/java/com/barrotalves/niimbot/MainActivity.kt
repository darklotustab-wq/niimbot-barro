package com.barrotalves.niimbot

import android.Manifest
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.os.Bundle
import android.util.Log
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import androidx.lifecycle.lifecycleScope
import com.google.zxing.BarcodeFormat
import com.google.zxing.MultiFormatWriter
import kotlinx.coroutines.*
import okhttp3.*
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.RequestBody.Companion.toRequestBody
import org.json.JSONObject
import java.io.OutputStream
import java.util.UUID
import java.util.concurrent.TimeUnit

class MainActivity : AppCompatActivity() {

    // ── Configuración ──────────────────────────────────────────────
    private val BRIDGE     = "https://web-production-180a8.up.railway.app"
    private val TOKEN      = "barrotalves2026"
    private val DEVICE_NAME = "D110"          // Nombre del dispositivo Bluetooth
    private val SPP_UUID   = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private val POLL_MS    = 5_000L           // Revisar cada 5 segundos

    // ── Variables ──────────────────────────────────────────────────
    private val http = OkHttpClient.Builder()
        .connectTimeout(10, TimeUnit.SECONDS)
        .readTimeout(10, TimeUnit.SECONDS)
        .build()

    private var pollingJob: Job? = null
    private var btSocket: BluetoothSocket? = null
    private var btStream: OutputStream? = null
    private var isRunning = false

    // ── UI ─────────────────────────────────────────────────────────
    private lateinit var tvStatus: TextView
    private lateinit var tvLog: TextView
    private lateinit var btnToggle: Button
    private lateinit var btnConnect: Button
    private lateinit var btnTest: Button
    private lateinit var btnGuardar: Button
    private lateinit var etAncho: EditText
    private lateinit var etAlto: EditText
    private lateinit var prefs: android.content.SharedPreferences

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus  = findViewById(R.id.tvStatus)
        tvLog     = findViewById(R.id.tvLog)
        btnToggle = findViewById(R.id.btnToggle)
        btnConnect= findViewById(R.id.btnConnect)
        btnTest   = findViewById(R.id.btnTest)
        btnGuardar= findViewById(R.id.btnGuardar)
        etAncho   = findViewById(R.id.etAncho)
        etAlto    = findViewById(R.id.etAlto)
        prefs     = getSharedPreferences("niimbot_prefs", MODE_PRIVATE)

        // Cargar el tamaño guardado (o 12x20mm por defecto)
        etAncho.setText(formatMm(prefs.getFloat("ancho_mm", 12f)))
        etAlto.setText(formatMm(prefs.getFloat("alto_mm", 20f)))

        requestPerms()

        btnConnect.setOnClickListener { conectarBluetooth() }
        btnToggle.setOnClickListener  { togglePolling() }
        btnTest.setOnClickListener    { imprimirTestDebug() }
        btnGuardar.setOnClickListener { guardarTamano() }
    }

    private fun formatMm(v: Float): String =
        if (v == v.toInt().toFloat()) v.toInt().toString() else v.toString()

    private fun guardarTamano() {
        val anchoMm = etAncho.text.toString().toFloatOrNull()
        val altoMm  = etAlto.text.toString().toFloatOrNull()
        if (anchoMm == null || altoMm == null || anchoMm <= 0 || altoMm <= 0) {
            log("❌ Tamaño inválido, revisá los valores")
            return
        }
        prefs.edit().putFloat("ancho_mm", anchoMm).putFloat("alto_mm", altoMm).apply()
        log("💾 Guardado: ${formatMm(anchoMm)}x${formatMm(altoMm)}mm — se va a usar en los pedidos automáticos")
    }

    // ── Modo diagnóstico: imprime un test con tamaño ajustable y log paso a paso ──
    private fun imprimirTestDebug() {
        val stream = btStream
        if (stream == null) {
            log("❌ Conectá la D110 primero")
            return
        }
        val anchoMm = etAncho.text.toString().toFloatOrNull() ?: 12f
        val altoMm  = etAlto.text.toString().toFloatOrNull() ?: 20f

        lifecycleScope.launch(Dispatchers.IO) {
            ulog("═══ TEST ${anchoMm}×${altoMm}mm ═══")
            try {
                val bmp = generarEtiquetaTest(anchoMm, altoMm)
                enviarImagenDebug(stream, bmp)
                ulog("═══ Fin del test ═══")
            } catch (e: Exception) {
                ulog("❌ Excepción en test: ${e.message}")
            }
        }
    }

    private suspend fun ulog(msg: String) = withContext(Dispatchers.Main) { log(msg) }

    private fun generarEtiquetaTest(anchoMm: Float, altoMm: Float): Bitmap {
        val w = Math.round(anchoMm / 25.4f * 203f)
        val h = Math.round(altoMm / 25.4f * 203f)
        val bmp = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        paint.color = Color.BLACK
        paint.style = Paint.Style.STROKE
        paint.strokeWidth = 3f
        canvas.drawRect(2f, 2f, (w - 2).toFloat(), (h - 2).toFloat(), paint) // borde
        paint.style = Paint.Style.FILL
        paint.textAlign = Paint.Align.CENTER
        paint.textSize = (h / 3.5f).coerceAtLeast(10f)
        canvas.drawText("TEST", w / 2f, h / 2f - 2f, paint)
        paint.textSize = (h / 6f).coerceAtLeast(8f)
        canvas.drawText("${anchoMm.toInt()}x${altoMm.toInt()}mm", w / 2f, h - 6f, paint)
        return bmp
    }

    // ── Permisos ───────────────────────────────────────────────────
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
                withContext(Dispatchers.Main) {
                    log("❌ Error BT: ${e.message}")
                }
            }
        }
    }

    // ── Polling ────────────────────────────────────────────────────
    private fun togglePolling() {
        if (isRunning) {
            pollingJob?.cancel()
            isRunning = false
            btnToggle.text = "▶ Iniciar escucha"
            tvStatus.text = "⏸ Pausado"
            log("Escucha detenida")
        } else {
            isRunning = true
            btnToggle.text = "⏹ Detener"
            tvStatus.text = "👂 Escuchando pedidos..."
            log("Escucha iniciada — revisando cada ${POLL_MS/1000}s")
            pollingJob = lifecycleScope.launch(Dispatchers.IO) {
                while (isActive) {
                    revisarPedidos()
                    delay(POLL_MS)
                }
            }
        }
    }

    private suspend fun revisarPedidos() {
        try {
            val req = Request.Builder()
                .url("$BRIDGE/pedidos/pendientes?token=$TOKEN")
                .get().build()
            val resp = http.newCall(req).execute()
            val body = resp.body?.string() ?: return
            val json = JSONObject(body)
            val arr  = json.getJSONArray("pedidos")
            if (arr.length() == 0) return

            withContext(Dispatchers.Main) {
                log("📬 ${arr.length()} pedido(s) nuevo(s)")
            }

            for (i in 0 until arr.length()) {
                val p      = arr.getJSONObject(i)
                val id     = p.getString("id")
                val codigo = p.getString("codigo")
                val nombre = p.optString("nombre", "")
                val precio = p.optString("precio", "")
                val cant   = p.optInt("cantidad", 1)

                withContext(Dispatchers.Main) {
                    log("🖨 Imprimiendo: $codigo ($cant etiquetas)")
                }

                val ok = imprimirEtiqueta(codigo, nombre, precio, cant)
                marcarEstado(id, if (ok) "completado" else "error", if (ok) null else "Fallo BT")
            }
        } catch (e: Exception) {
            withContext(Dispatchers.Main) { log("⚠️ Poll error: ${e.message}") }
        }
    }

    // ── Protocolo Niimbot ──────────────────────────────────────────
    private fun imprimirEtiqueta(codigo: String, nombre: String, precio: String, cant: Int): Boolean {
        val stream = btStream ?: run {
            lifecycleScope.launch(Dispatchers.Main) { log("❌ No hay conexión BT") }
            return false
        }
        return try {
            val bmp = generarEtiqueta(codigo, nombre, precio)
            repeat(cant) {
                enviarImagen(stream, bmp)
                Thread.sleep(500)
            }
            true
        } catch (e: Exception) {
            lifecycleScope.launch(Dispatchers.Main) { log("❌ Error impresión: ${e.message}") }
            false
        }
    }

    private fun generarEtiqueta(codigo: String, nombre: String, precio: String): Bitmap {
        // Tamaño tomado de lo guardado en el modo diagnóstico (default 12x20mm)
        val anchoMm = prefs.getFloat("ancho_mm", 12f)
        val altoMm  = prefs.getFloat("alto_mm", 20f)
        val W = Math.round(anchoMm / 25.4f * 203f)
        val H = Math.round(altoMm / 25.4f * 203f)
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Código de barras (proporcional al alto disponible)
        val altoBarras = (H * 0.55f).toInt().coerceAtLeast(20)
        val bits = MultiFormatWriter().encode(codigo, BarcodeFormat.CODE_128, W - 20, altoBarras)
        paint.color = Color.BLACK
        for (x in 0 until bits.width) {
            for (y in 0 until bits.height) {
                if (bits[x, y]) canvas.drawPoint((x + 10).toFloat(), (y + 4).toFloat(), paint)
            }
        }

        // Código + precio en una sola línea abajo (no entra nombre + precio + código por separado)
        paint.textSize = (H * 0.14f).coerceAtLeast(10f)
        paint.textAlign = Paint.Align.CENTER
        val linea1 = if (precio.isNotEmpty()) "$codigo   $ $precio" else codigo
        canvas.drawText(linea1, W / 2f, altoBarras + 20f, paint)

        if (nombre.isNotEmpty()) {
            paint.textSize = (H * 0.10f).coerceAtLeast(8f)
            canvas.drawText(nombre.take(40), W / 2f, (H - 6).toFloat(), paint)
        }
        return bmp
    }

    // Protocolo Niimbot simplificado (línea a línea)
    private fun enviarImagen(out: OutputStream, bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height

        // Handshake / inicio de página (orden y códigos según protocolo Niimbot real)
        sendPacket(out, 0x23, byteArrayOf(0x01))          // SET_LABEL_TYPE
        sendPacket(out, 0x21, byteArrayOf(0x03))          // SET_LABEL_DENSITY
        sendPacket(out, 0x01, byteArrayOf(0x01))          // START_PRINT
        sendPacket(out, 0x20, byteArrayOf(0x01))          // ALLOW_PRINT_CLEAR
        sendPacket(out, 0x03, byteArrayOf(0x01))          // START_PAGE
        sendPacket(out, 0x13, intToBytes2(w) + intToBytes2(h)) // SET_DIMENSION (ancho, alto)
        sendPacket(out, 0x15, byteArrayOf(0x00, 0x01))    // SET_QUANTITY

        // Enviar fila por fila
        for (y in 0 until h) {
            val rowBytes = ByteArray((w + 7) / 8)
            for (x in 0 until w) {
                val px = bmp.getPixel(x, y)
                val dark = (Color.red(px) + Color.green(px) + Color.blue(px)) < 382
                if (dark) rowBytes[x / 8] = (rowBytes[x / 8].toInt() or (0x80 shr (x % 8))).toByte()
            }
            // Encabezado real: y(2 bytes) + 3 bytes de conteo (0) + 1 byte de repetición (1) = 6 bytes
            val lineData = intToBytes2(y) + byteArrayOf(0x00, 0x00, 0x00, 0x01) + rowBytes
            sendPacket(out, 0x85, lineData)
        }

        sendPacket(out, 0xE3.toByte().toInt(), byteArrayOf(0x01)) // END_PAGE
        Thread.sleep(300)
        sendPacket(out, 0xF3.toByte().toInt(), byteArrayOf(0x01)) // END_PRINT
        out.flush()

        // Diagnóstico: leer lo que responda la impresora (si responde algo)
        leerRespuesta()
    }

    // Versión de diagnóstico: loguea cada comando enviado y su respuesta inmediata.
    private suspend fun enviarImagenDebug(out: OutputStream, bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height
        ulog("Imagen: ${w}x${h}px")

        enviarYLoguear(out, 0x23, byteArrayOf(0x01), "SET_LABEL_TYPE")
        enviarYLoguear(out, 0x21, byteArrayOf(0x03), "SET_LABEL_DENSITY")
        enviarYLoguear(out, 0x01, byteArrayOf(0x01), "START_PRINT")
        enviarYLoguear(out, 0x20, byteArrayOf(0x01), "ALLOW_PRINT_CLEAR")
        enviarYLoguear(out, 0x03, byteArrayOf(0x01), "START_PAGE")
        enviarYLoguear(out, 0x13, intToBytes2(w) + intToBytes2(h), "SET_DIMENSION")
        enviarYLoguear(out, 0x15, byteArrayOf(0x00, 0x01), "SET_QUANTITY")

        ulog("Enviando $h filas de imagen...")
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
        Thread.sleep(300)
        enviarYLoguear(out, 0xF3, byteArrayOf(0x01), "END_PRINT")
        out.flush()

        // Consultar estado de impresión (si el firmware lo soporta)
        Thread.sleep(300)
        enviarYLoguear(out, 0xA3, byteArrayOf(0x01), "GET_PRINT_STATUS")
    }

    // Manda un comando y loguea inmediatamente qué respondió la impresora (o si no respondió nada).
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

    // Lee cualquier byte que la D110 haya devuelto, para diagnosticar en el log.
    private fun leerRespuesta() {
        try {
            val stream = btSocket?.inputStream ?: return
            Thread.sleep(400)
            val disponible = stream.available()
            if (disponible <= 0) {
                lifecycleScope.launch(Dispatchers.Main) { log("📭 D110 no respondió nada") }
                return
            }
            val buf = ByteArray(disponible)
            val leidos = stream.read(buf)
            val hex = buf.take(leidos).joinToString(" ") { "%02X".format(it) }
            lifecycleScope.launch(Dispatchers.Main) { log("📥 Respuesta D110: $hex") }
        } catch (e: Exception) {
            lifecycleScope.launch(Dispatchers.Main) { log("⚠️ Error leyendo respuesta: ${e.message}") }
        }
    }

    private fun sendPacket(out: OutputStream, cmd: Int, data: ByteArray) {
        val packet = ByteArray(data.size + 7)
        packet[0] = 0x55
        packet[1] = 0x55
        packet[2] = cmd.toByte()
        packet[3] = data.size.toByte()
        data.copyInto(packet, 4)
        var cs = packet[2].toInt() and 0xFF
        for (i in 3 until 4 + data.size) cs = cs xor (packet[i].toInt() and 0xFF)
        packet[4 + data.size] = cs.toByte()
        packet[5 + data.size] = 0xAA.toByte()
        packet[6 + data.size] = 0xAA.toByte()
        out.write(packet)
        Thread.sleep(20)
    }

    private fun intToBytes2(v: Int) = byteArrayOf((v shr 8).toByte(), v.toByte())

    // ── Marcar estado en el servidor ───────────────────────────────
    private fun marcarEstado(id: String, estado: String, msg: String?) {
        try {
            val body = JSONObject().apply { if (msg != null) put("mensaje", msg) }
                .toString().toRequestBody("application/json".toMediaType())
            val req = Request.Builder()
                .url("$BRIDGE/pedidos/$id/$estado?token=$TOKEN")
                .post(body).build()
            http.newCall(req).execute().close()
        } catch (_: Exception) {}
    }

    // ── Helpers ────────────────────────────────────────────────────
    private fun log(msg: String) {
        Log.d("NiimbotBarro", msg)
        val text = tvLog.text.toString()
        val lines = text.split("\n").takeLast(40)
        tvLog.text = (lines + msg).joinToString("\n")
    }

    override fun onDestroy() {
        super.onDestroy()
        pollingJob?.cancel()
        btSocket?.close()
    }
}
