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

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus  = findViewById(R.id.tvStatus)
        tvLog     = findViewById(R.id.tvLog)
        btnToggle = findViewById(R.id.btnToggle)
        btnConnect= findViewById(R.id.btnConnect)

        requestPerms()

        btnConnect.setOnClickListener { conectarBluetooth() }
        btnToggle.setOnClickListener  { togglePolling() }
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
        // Dimensiones para rollo 40mm × 30mm a 203 DPI
        val W = 320
        val H = 240
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bmp)
        canvas.drawColor(Color.WHITE)

        val paint = Paint(Paint.ANTI_ALIAS_FLAG)

        // Código de barras
        val bits = MultiFormatWriter().encode(codigo, BarcodeFormat.CODE_128, W - 20, 130)
        paint.color = Color.BLACK
        for (x in 0 until bits.width) {
            for (y in 0 until bits.height) {
                if (bits[x, y]) canvas.drawPoint((x + 10).toFloat(), (y + 10).toFloat(), paint)
            }
        }

        paint.textSize = 18f
        paint.textAlign = Paint.Align.CENTER
        canvas.drawText(codigo, W / 2f, 158f, paint)

        if (nombre.isNotEmpty()) {
            paint.textSize = 14f
            canvas.drawText(nombre.take(36), W / 2f, 178f, paint)
        }
        if (precio.isNotEmpty()) {
            paint.textSize = 16f
            canvas.drawText("$ $precio", W / 2f, 200f, paint)
        }
        return bmp
    }

    // Protocolo Niimbot simplificado (línea a línea)
    private fun enviarImagen(out: OutputStream, bmp: Bitmap) {
        val w = bmp.width
        val h = bmp.height

        // Handshake / inicio de página
        sendPacket(out, 0x01, byteArrayOf(0x01))          // START_PRINT
        sendPacket(out, 0x21, byteArrayOf(0x01))          // SET_LABEL_TYPE
        sendPacket(out, 0x13, intToBytes2(h) + intToBytes2(w)) // SET_DIMENSION
        sendPacket(out, 0x15, byteArrayOf(0x00, 0x01))    // SET_QUANTITY
        sendPacket(out, 0x03, byteArrayOf(0x01))          // START_PAGE

        // Enviar fila por fila
        for (y in 0 until h) {
            val rowBytes = ByteArray((w + 7) / 8)
            for (x in 0 until w) {
                val px = bmp.getPixel(x, y)
                val dark = (Color.red(px) + Color.green(px) + Color.blue(px)) < 382
                if (dark) rowBytes[x / 8] = (rowBytes[x / 8].toInt() or (0x80 shr (x % 8))).toByte()
            }
            val lineData = intToBytes2(y) + byteArrayOf(0x00, 0x00, 0x00) + rowBytes
            sendPacket(out, 0x85, lineData)
        }

        sendPacket(out, 0xE3.toByte().toInt(), byteArrayOf(0x01)) // END_PAGE
        Thread.sleep(300)
        sendPacket(out, 0xF3.toByte().toInt(), byteArrayOf(0x01)) // END_PRINT
        out.flush()
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
        val lines = text.split("\n").takeLast(12)
        tvLog.text = (lines + msg).joinToString("\n")
    }

    override fun onDestroy() {
        super.onDestroy()
        pollingJob?.cancel()
        btSocket?.close()
    }
}
