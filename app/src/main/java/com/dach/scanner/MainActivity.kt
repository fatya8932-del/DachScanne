package com.dach.scanner

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var btSocket: BluetoothSocket? = null
    private var outStream: OutputStream? = null
    private var inStream: InputStream? = null
    private var hardwareMode = "NONE"

    private lateinit var tvStatus: TextView
    private lateinit var tvConsole: TextView
    private lateinit var spinnerCarBrand: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        tvStatus = findViewById(R.id.tvStatus)
        tvConsole = findViewById(R.id.tvConsole)
        spinnerCarBrand = findViewById(R.id.spinnerCarBrand)

        checkBluetoothPermissions()
        setupCarList()

        findViewById<Button>(R.id.btnConnectMulti).setOnClickListener {
            hardwareMode = "MULTI"
            log("กำลังเชื่อมต่อกล่อง Multidiag Pro (Bluetooth SPP)...")
            connectVci("VCI")
        }

        findViewById<Button>(R.id.btnConnectKonnwei).setOnClickListener {
            hardwareMode = "KONNWEI"
            log("กำลังเชื่อมต่อหัวอ่าน KONNWEI / ELM327...")
            connectVci("KONNWEI")
        }

        findViewById<Button>(R.id.btnAutoScan).setOnClickListener {
            val car = spinnerCarBrand.selectedItem.toString()
            log("=== เริ่มสแกนทั้งคัน: $car ===")
            if (hardwareMode == "MULTI") {
                log("[Multidiag Pro] สั่งสับรีเลย์ขา 7 (Engine) -> ขา 12/13 (ABS/SRS) -> ขา 6/14 (CAN)")
            }
            sendHexCommand("01 00")
        }

        findViewById<Button>(R.id.btnLiveData).setOnClickListener {
            val car = spinnerCarBrand.selectedItem.toString()
            log("กำลังอ่านค่าสด (RPM / แรงดันราง / ชดเชยหัวฉีด 1-4) สำหรับ $car")
            when {
                car.contains("Toyota") -> sendHexCommand("21 45")
                car.contains("Isuzu") -> sendHexCommand("22 10 05")
                car.contains("Mitsubishi") -> sendHexCommand("21 15")
            }
        }

        findViewById<Button>(R.id.btnReadDtc).setOnClickListener {
            log("ส่งคำสั่งอ่านรหัสข้อบกพร่อง (Read DTC)...")
            sendHexCommand("03")
        }

        findViewById<Button>(R.id.btnClearDtc).setOnClickListener {
            log("ส่งคำสั่งลบโค้ดเสียและดับไฟรูปเครื่อง (Clear DTC)...")
            sendHexCommand("04")
        }

        findViewById<Button>(R.id.btnInjectorCut).setOnClickListener {
            showInjectorCutDialog()
        }

        findViewById<Button>(R.id.btnActuator).setOnClickListener {
            log("เข้าสู่โหมด Active Test: สั่งเปิด-ปิด วาล์ว EGR / SCV / พัดลมหม้อน้ำ")
            sendHexCommand("30 01 07 01")
        }

        findViewById<Button>(R.id.btnInjectorCoding).setOnClickListener {
            showInjectorCodingDialog()
        }

        findViewById<Button>(R.id.btnLearningReset).setOnClickListener {
            val car = spinnerCarBrand.selectedItem.toString()
            log("=== สั่งรีเซ็ตค่าเรียนรู้ปั๊มเชื้อเพลิง ($car) ===")
            when {
                car.contains("Toyota") -> sendHexCommand("30 13 00")
                car.contains("Isuzu") -> sendHexCommand("31 01 FF 01")
                car.contains("Mitsubishi") -> sendHexCommand("30 05 01")
            }
            log("กรุณาปิดสวิตช์กุญแจ OFF 15 วินาที แล้วสตาร์ทเครื่องเดินเบา")
        }
    }

    private fun setupCarList() {
        val cars = arrayOf(
            "Toyota Hilux Vigo / Revo (1KD / 2KD / 1GD)",
            "Isuzu D-Max (4JH1 / 4JK1 / 4JJ1 / 1.9 RZ4E)",
            "Mitsubishi Triton / Pajero (4D56 / 4N15)",
            "Ford Ranger / Mazda BT-50 (2.2 / 3.2)",
            "รถเก๋งทั่วไป (OBD2 CAN Standard)"
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, cars)
        spinnerCarBrand.adapter = adapter
    }

    private fun showInjectorCutDialog() {
        val cylinders = arrayOf("ตัดหัวฉีด สูบที่ 1", "ตัดหัวฉีด สูบที่ 2", "ตัดหัวฉีด สูบที่ 3", "ตัดหัวฉีด สูบที่ 4", "ยกเลิกการตัด (ทำงานครบ 4 สูบ)")
        AlertDialog.Builder(this)
            .setTitle("Active Test: เลือกสูบที่ต้องการตัดหัวฉีด")
            .setItems(cylinders) { _, which ->
                when (which) {
                    0 -> { log("สั่งตัดหัวฉีด สูบ 1"); sendHexCommand("30 18 07 01") }
                    1 -> { log("สั่งตัดหัวฉีด สูบ 2"); sendHexCommand("30 18 07 02") }
                    2 -> { log("สั่งตัดหัวฉีด สูบ 3"); sendHexCommand("30 18 07 04") }
                    3 -> { log("สั่งตัดหัวฉีด สูบ 4"); sendHexCommand("30 18 07 08") }
                    4 -> { log("คืนค่าการทำงานหัวฉีดครบทุกสูบ"); sendHexCommand("30 18 07 00") }
                }
            }
            .show()
    }

    private fun showInjectorCodingDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }
        val inputCyl = EditText(this).apply { hint = "ระบุเลขสูบ (1, 2, 3 หรือ 4)" }
        val inputCode = EditText(this).apply { hint = "กรอกรหัสชดเชยบนหัวฉีด (30 หรือ 16 หลัก)" }
        layout.addView(inputCyl)
        layout.addView(inputCode)

        AlertDialog.Builder(this)
            .setTitle("ลงทะเบียนรหัสหัวฉีด (Injector ID Coding)")
            .setView(layout)
            .setPositiveButton("เขียนรหัสลงกล่อง ECU") { _, _ ->
                val cyl = inputCyl.text.toString()
                val code = inputCode.text.toString().uppercase()
                log("กำลังเขียนรหัสหัวฉีด สูบ $cyl : [$code] ลงกล่อง ECU...")
                sendHexCommand("3B 1$cyl $code")
            }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }

    private fun connectVci(keyword: String) {
        val btAdapter = BluetoothAdapter.getDefaultAdapter()
        if (btAdapter == null || !btAdapter.isEnabled) {
            Toast.makeText(this, "กรุณาเปิด Bluetooth ที่แท็บเล็ตก่อน", Toast.LENGTH_SHORT).show()
            return
        }

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
            ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
            return
        }

        val device: BluetoothDevice? = btAdapter.bondedDevices?.firstOrNull {
            it.name.contains(keyword, true) || it.name.contains("Autocom", true) ||
            it.name.contains("Delphi", true) || it.name.contains("OBD", true)
        }

        if (device == null) {
            log("ไม่พบอุปกรณ์ที่จับคู่ไว้ กรุณาเข้าไปจับคู่ Bluetooth ในตั้งค่าเครื่องก่อน")
            return
        }

        Thread {
            try {
                btSocket = device.createRfcommSocketToServiceRecord(sppUuid)
                btSocket?.connect()
                outStream = btSocket?.outputStream
                inStream = btSocket?.inputStream

                runOnUiThread {
                    tvStatus.text = "เชื่อมต่อแล้ว: ${device.name} [$hardwareMode]"
                    log("เชื่อมต่อ ${device.name} สำเร็จ! พร้อมสแกน")
                }
                listenIncomingData()
            } catch (e: Exception) {
                runOnUiThread { log("เชื่อมต่อไม่สำเร็จ: ${e.message}") }
            }
        }.start()
    }

    private fun sendHexCommand(hex: String) {
        Thread {
            try {
                val cmd = "$hex\r"
                outStream?.write(cmd.toByteArray())
                outStream?.flush()
                runOnUiThread { log("TX -> $hex") }
            } catch (e: Exception) {
                runOnUiThread { log("ยังไม่ได้เชื่อมต่อหัวอ่าน (โหมดจำลอง: ส่งคำสั่ง $hex)") }
            }
        }.start()
    }

    private fun listenIncomingData() {
        val buffer = ByteArray(1024)
        while (true) {
            try {
                val bytes = inStream?.read(buffer) ?: break
                if (bytes > 0) {
                    val response = String(buffer, 0, bytes).trim()
                    runOnUiThread { log("RX <- $response") }
                }
            } catch (e: Exception) {
                break
            }
        }
    }

    private fun log(text: String) {
        tvConsole.append("\n> $text")
    }

    private fun checkBluetoothPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            ActivityCompat.requestPermissions(
                this,
                arrayOf(Manifest.permission.BLUETOOTH_CONNECT, Manifest.permission.BLUETOOTH_SCAN),
                100
            )
        }
    }
}
