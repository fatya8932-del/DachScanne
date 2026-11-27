package com.dach.scanner

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.*
import androidx.appcompat.app.AppCompatActivity
import androidx.core.app.ActivityCompat
import java.io.InputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID

class MainActivity : AppCompatActivity() {

    private val sppUuid: UUID = UUID.fromString("00001101-0000-1000-8000-00805F9B34FB")
    private var btSocket: BluetoothSocket? = null
    private var outStream: OutputStream? = null
    private var inStream: InputStream? = null
    private var hardwareMode = "NONE"
    private var isLiveStreaming = false

    private var lastDetectedDtc = ""
    private var lastDetectedVin = "ยังไม่ระบุ"
    private var lastDetectedCar = "ไม่ระบุรุ่นรถ"
    private var lastDetectedEcu = "ไม่ระบุ"

    private lateinit var tvStatus: TextView
    private lateinit var tvConsole: TextView
    private lateinit var spinnerVehicleGroup: Spinner
    private lateinit var spinnerCarBrand: Spinner
    private lateinit var spinnerProtocol: Spinner

    private lateinit var tvDashRpm: TextView
    private lateinit var tvDashSpeed: TextView
    private lateinit var tvDashTemp: TextView
    private lateinit var tvDashVolt: TextView
    private lateinit var tvDashRail: TextView
    private lateinit var tvDashLoad: TextView

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)

            tvStatus = findViewById(R.id.tvStatus)
            tvConsole = findViewById(R.id.tvConsole)
            spinnerVehicleGroup = findViewById(R.id.spinnerVehicleGroup)
            spinnerCarBrand = findViewById(R.id.spinnerCarBrand)
            spinnerProtocol = findViewById(R.id.spinnerProtocol)

            tvDashRpm = findViewById(R.id.tvDashRpm)
            tvDashSpeed = findViewById(R.id.tvDashSpeed)
            tvDashTemp = findViewById(R.id.tvDashTemp)
            tvDashVolt = findViewById(R.id.tvDashVolt)
            tvDashRail = findViewById(R.id.tvDashRail)
            tvDashLoad = findViewById(R.id.tvDashLoad)

            setupVehicleGroups()
            setupProtocols()
            setupButtons()
            checkBluetoothPermissions()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun setupButtons() {
        findViewById<Button>(R.id.btnConnectMulti).setOnClickListener {
            hardwareMode = "MULTI"
            log("กำลังเชื่อมต่อ Multidiag Pro VCI (รองรับระบบ 12V และ 24V)...")
            connectVci("VCI")
        }

        findViewById<Button>(R.id.btnConnectKonnwei).setOnClickListener {
            hardwareMode = "KONNWEI"
            log("กำลังเชื่อมต่อ KONNWEI / ELM327...")
            connectVci("KONNWEI")
        }

        findViewById<Button>(R.id.btnAutoScan).setOnClickListener {
            val group = spinnerVehicleGroup.selectedItem?.toString() ?: ""
            val brand = spinnerCarBrand.selectedItem?.toString() ?: ""
            log("=== เริ่มสแกนอัจฉริยะ (Auto VIN & ECU Info): $brand ===")

            if (hardwareMode == "MULTI") {
                log("[Multidiag Pro] สับรีเลย์สแกน: ECU เครื่องยนต์, เกียร์ TCU, เบรก ABS, ถุงลม SRS")
            }

            if (group.contains("รถบรรทุก") || group.contains("แบคโฮ")) {
                log("ส่งคำสั่งอ่าน VIN & หมายเลขกล่องมาตรฐาน J1939 (PGN 65260 / 65242)...")
                sendHexCommand("18 EA FF F9 00 FE EC")
                sendHexCommand("18 EA FF F9 00 FE DA")
            } else {
                log("กำลังดึงหมายเลขตัวถัง (VIN 17 หลัก) และหมายเลขกล่อง ECU...")
                sendHexCommand("09 02")
                sendHexCommand("09 04")
                sendHexCommand("22 F1 87")
            }
        }

        findViewById<Button>(R.id.btnLiveData).setOnClickListener {
            isLiveStreaming = !isLiveStreaming
            if (isLiveStreaming) {
                log("=== เปิดโหมด Live Dashboard Display (อัปเดตตัวเลขต่อเนื่อง) ===")
                startDashboardStream()
            } else {
                log("=== หยุดการดึงค่าสด Dashboard ===")
            }
        }

        findViewById<Button>(R.id.btnReadDtc).setOnClickListener {
            val group = spinnerVehicleGroup.selectedItem?.toString() ?: ""
            if (group.contains("รถบรรทุก") || group.contains("แบคโฮ")) {
                log("อ่านโค้ดเสีย J1939 (DM1 Active Faults / SPN-FMI)...")
                sendHexCommand("18 FE CA 00")
            } else {
                log("อ่านโค้ดเสีย OBD2 / UDS (Read DTC)...")
                sendHexCommand("03")
            }
        }

        findViewById<Button>(R.id.btnClearDtc).setOnClickListener {
            val group = spinnerVehicleGroup.selectedItem?.toString() ?: ""
            if (group.contains("รถบรรทุก") || group.contains("แบคโฮ")) {
                log("ลบโค้ดเสียรถบรรทุก/เครื่องจักร (J1939 DM11)...")
                sendHexCommand("18 FE CC 00")
            } else {
                log("ลบโค้ดไฟโชว์ (Clear DTC)...")
                sendHexCommand("04")
            }
        }

        findViewById<Button>(R.id.btnCheckObdVolt).setOnClickListener {
            log("=== กำลังตรวจเช็กแรงดันไฟปลั๊ก OBD2 และสายสัญญาณ Data ===")
            log("1. วัดแรงดันไฟเลี้ยงขา 16 (Battery Voltage)...")
            sendHexCommand("AT RV")
            Thread {
                try {
                    Thread.sleep(250)
                    runOnUiThread { log("2. ตรวจสอบสัญญาณสาย Data (CAN High/Low Pin 6/14 & K-Line Pin 7)...") }
                    sendHexCommand("AT DP")
                    Thread.sleep(250)
                    sendHexCommand("01 00")
                } catch (e: Exception) { }
            }.start()
        }

        findViewById<Button>(R.id.btnGoogleDtc).setOnClickListener {
            showGoogleSearchDtcDialog()
        }

        findViewById<Button>(R.id.btnServiceManual).setOnClickListener {
            showServiceManualDialog()
        }

        findViewById<Button>(R.id.btnShareReport).setOnClickListener {
            showSaveAndShareDialog()
        }

        findViewById<Button>(R.id.btnInjectorCut).setOnClickListener { showInjectorCutDialog() }

        findViewById<Button>(R.id.btnActuator).setOnClickListener {
            val items = arrayOf(
                "1. สั่งเปิด-ปิด วาล์ว EGR",
                "2. สั่งเทสวาล์ว SCV / PCV ปั๊มแรงดันสูง",
                "3. สั่งปั๊มติ๊กทำงานไล่ลมระบบน้ำมัน",
                "4. สั่งเทสโซลินอยด์ไฮดรอลิก / สวิง / ปั๊มหลัก (รถแบคโฮ)",
                "5. สั่งเทสปั๊มน้ำหล่อเย็นแบตเตอรี่ / พัดลม (รถ EV)"
            )
            AlertDialog.Builder(this)
                .setTitle("Active Test: สั่งทำงานอุปกรณ์โดยตรง")
                .setItems(items) { _, which ->
                    log("Active Test: ${items[which]}")
                    sendHexCommand("30 01 0${which + 1} 01")
                }.show()
        }

        findViewById<Button>(R.id.btnInjectorCoding).setOnClickListener { showInjectorCodingDialog() }
        findViewById<Button>(R.id.btnSmallQtyLearn).setOnClickListener { showLearningCombinedDialog() }
        findViewById<Button>(R.id.btnDpfRegen).setOnClickListener { showDpfAndAdblueDialog() }
        findViewById<Button>(R.id.btnEvBattery).setOnClickListener { showEvAndBmsDialog() }
        findViewById<Button>(R.id.btnEpbService).setOnClickListener { showEpbAndAbsDialog() }
        findViewById<Button>(R.id.btnSasThrottleReset).setOnClickListener { showAllResetsDialog() }
    }

    private fun showGoogleSearchDtcDialog() {
        val defaultBrand = spinnerCarBrand.selectedItem?.toString()?.substringBefore("(")?.trim() ?: ""
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }
        val inputCar = EditText(this).apply {
            hint = "ชื่อรุ่นรถ / รหัสเครื่อง (แก้ไขได้ เช่น Revo 1GD)"
            setText(defaultBrand)
        }
        val inputDtc = EditText(this).apply {
            hint = "กรอกรหัสโค้ดเสีย เช่น P0087, P0401, C1201"
            setText(lastDetectedDtc)
        }
        layout.addView(inputCar)
        layout.addView(inputDtc)

        AlertDialog.Builder(this)
            .setTitle("ค้นหาโค้ดเสีย & วิธีซ่อม (ไม่ต้องเสียบหัวอ่าน)")
            .setMessage("พิมพ์รหัสโค้ดเสียและรุ่นรถเพื่อค้นหาสาเหตุและวิธีซ่อมบน Google ทันที")
            .setView(layout)
            .setPositiveButton("ค้นหาใน Google") { _, _ ->
                val carName = inputCar.text.toString().trim()
                val dtcCode = inputDtc.text.toString().trim().uppercase()
                if (dtcCode.isNotEmpty()) {
                    lastDetectedDtc = dtcCode
                    val query = Uri.encode("$carName รหัสโค้ด $dtcCode ความหมาย สาเหตุ วิธีซ่อม")
                    log("ค้นหาวิธีซ่อม (Offline Mode): $carName -> $dtcCode")
                     startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://google.com")))

                } else {
                    Toast.makeText(this, "กรุณากรอกรหัสโค้ดเสียก่อนค้นหา", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }

    private fun showServiceManualDialog() {
        val defaultCar = spinnerCarBrand.selectedItem?.toString() ?: ""
        val inputCar = EditText(this).apply {
            hint = "พิมพ์รุ่นรถ/รหัสเครื่อง (เช่น Vigo 1KD, D-Max 1.9, PC200-8)"
            setText(defaultCar)
        }

ฤ
