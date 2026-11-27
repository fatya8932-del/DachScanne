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

        val options = arrayOf(
            "1. คู่มือซ่อมฉบับเต็ม (Workshop Service Manual PDF)",
            "2. ผังวายริ่งสายไฟและขาปลั๊ก (ECU Wiring Diagram / Pinout)",
            "3. ตำแหน่งกล่องฟิวส์และรีเลย์ (Fuse Box & Relay Diagram)",
            "4. ค่าแรงขันปอนด์และมาร์คไทม์มิ่ง (Torque Specs & Timing Marks)",
            "5. ค้นหาอะไหล่และพาร์ทแท้ (OEM Parts Catalog / Part Number)",
            "6. เปิดลิงก์เว็บฐานข้อมูลคู่มือซ่อมออนไลน์ (Charm.li)"
        )

        AlertDialog.Builder(this)
            .setTitle("คลังข้อมูล Service Manual (ไม่ต้องเสียบหัวอ่าน)")
            .setView(inputCar)
            .setItems(options) { _, which ->
                val carQuery = inputCar.text.toString().trim().ifEmpty { defaultCar }
                if (which == 5) {
                    log("เปิดฐานข้อมูลคู่มือซ่อมออนไลน์ (Operation CHARM)...")
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://charm.li")))
                } else {
                    val keyword = when (which) {
                        0 -> "Workshop Service Repair Manual PDF"
                        1 -> "ECU Wiring Diagram Pinout"
                        2 -> "Fuse Box Relay Diagram Layout"
                        3 -> "Engine Torque Specs Timing Marks"
                        else -> "OEM Parts Catalog Part Number Diagram"
                    }
                    val fullQuery = Uri.encode("$carQuery $keyword")
                    log("ค้นหาคู่มือซ่อม (ไม่ต้องต่อ VCI): $carQuery -> $keyword")
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/search?q=$fullQuery")))

                }
            }
            .show()
    }

    private fun showSaveAndShareDialog() {
        val options = arrayOf(
            "1. บันทึกและแชร์ใบรายงานผลสแกนส่งให้ลูกค้า (LINE / Messenger)",
            "2. เปิดดูประวัติการสแกนที่บันทึกไว้ย้อนหลัง"
        )
        AlertDialog.Builder(this)
            .setTitle("ระบบบันทึกประวัติ & รายงานลูกค้า")
            .setItems(options) { _, which ->
                if (which == 0) saveAndShareReportToCustomer() else showSavedHistoryDialog()
            }
            .show()
    }

    private fun saveAndShareReportToCustomer() {
        val dateStr = SimpleDateFormat("dd/MM/yyyy HH:mm", Locale.getDefault()).format(Date())
        val selectedCar = spinnerCarBrand.selectedItem?.toString() ?: lastDetectedCar
        val dtcSummary = if (lastDetectedDtc.isNotEmpty()) lastDetectedDtc else "ไม่พบรหัสข้อบกพร่อง (ปกติ)"

        val reportText = """
            📋 รายงานผลการตรวจเช็กด้วยเครื่องสแกน
            🔧 ศูนย์บริการ: DACH SCANNER ULTRA
            📅 วันที่ตรวจเช็ก: $dateStr
            ----------------------------------------
            🚗 รุ่นรถที่เลือก: $selectedCar
            🏷️ ข้อมูลจากกล่อง: $lastDetectedCar
            🔢 เลขตัวถัง (VIN): $lastDetectedVin
            🖥️ หมายเลขกล่อง ECU: $lastDetectedEcu
            🔋 แรงดันไฟ OBD2 / แบตเตอรี่: ${tvDashVolt.text}
            ----------------------------------------
            ⚠️ รหัสโค้ดปัญหาที่ตรวจพบ (DTC):
            $dtcSummary
            ----------------------------------------
            ✅ สถานะ: ตรวจสอบและบันทึกข้อมูลเรียบร้อย
        """.trimIndent()

        val prefs = getSharedPreferences("DachScanHistory", MODE_PRIVATE)
        prefs.edit().putString("report_$dateStr", reportText).apply()
        log("✔ บันทึกประวัติการสแกนลงเครื่องเรียบร้อยแล้ว ($dateStr)")

        val shareIntent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "รายงานผลสแกนรถยนต์ - $selectedCar")
            putExtra(Intent.EXTRA_TEXT, reportText)
        }
        startActivity(Intent.createChooser(shareIntent, "ส่งรายงานให้ลูกค้าหรือบันทึกผ่าน..."))
    }

    private fun showSavedHistoryDialog() {
        val prefs = getSharedPreferences("DachScanHistory", MODE_PRIVATE)
        val allEntries = prefs.all
        if (allEntries.isEmpty()) {
            Toast.makeText(this, "ยังไม่มีประวัติการสแกนที่บันทึกไว้", Toast.LENGTH_SHORT).show()
            return
        }
        val keys = allEntries.keys.sortedDescending().toTypedArray()
        AlertDialog.Builder(this)
            .setTitle("ประวัติการสแกนย้อนหลัง (${keys.size} รายการ)")
            .setItems(keys) { _, which ->
                val savedReport = allEntries[keys[which]]?.toString() ?: ""
                AlertDialog.Builder(this)
                    .setTitle(keys[which])
                    .setMessage(savedReport)
                    .setPositiveButton("แชร์รายงานนี้ให้ลูกค้า") { _, _ ->
                        val shareIntent = Intent(Intent.ACTION_SEND).apply {
                            type = "text/plain"
                            putExtra(Intent.EXTRA_TEXT, savedReport)
                        }
                        startActivity(Intent.createChooser(shareIntent, "แชร์รายงานผ่าน..."))
                    }
                    .setNegativeButton("ปิด", null)
                    .show()
            }
            .show()
    }

    private fun startDashboardStream() {
        Thread {
            while (isLiveStreaming) {
                try {
                    sendHexCommand("01 0C")
                    Thread.sleep(150)
                    sendHexCommand("01 0D")
                    Thread.sleep(150)
                    sendHexCommand("01 05")
                    Thread.sleep(150)
                    sendHexCommand("AT RV")
                    Thread.sleep(150)
                    sendHexCommand("01 23")
                    Thread.sleep(150)
                    sendHexCommand("01 04")
                    Thread.sleep(200)
                } catch (e: Exception) {
                    break
                }
            }
        }.start()
    }

    private fun setupVehicleGroups() {
        val groups = arrayOf(
            "1. รถกระบะ & PPV ดีเซลยอดนิยมในไทย (Pickup / PPV)",
            "2. รถเก๋ง & SUV ญี่ปุ่น (Japanese Passenger & Hybrid)",
            "3. รถตู้ & รถเชิงพาณิชย์ขนาดเล็ก (Van & Commercial)",
            "4. รถยุโรป & อเมริกา (European & American)",
            "5. รถจีนทุกค่าย (Chinese Cars - น้ำมัน / ไฮบริด)",
            "6. รถยนต์ไฟฟ้า 100% (EV / BEV ทุกค่าย)",
            "7. รถบรรทุกหนัก 6-10 ล้อ & หัวลาก 24V (Heavy-Duty Truck)",
            "8. รถแบคโฮ, รถตัก & รถแทรกเตอร์ (Excavator & Machinery)"
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, groups)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerVehicleGroup.adapter = adapter
        spinnerVehicleGroup.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                updateBrandListByGroup(pos)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun updateBrandListByGroup(groupIndex: Int) {
        val brands = when (groupIndex) {
            0 -> arrayOf(
                "Toyota Hilux Tiger D4D (1KD / 2KD - K-Line รุ่นแรก)",
                "Toyota Hilux Vigo / Vigo Champ (1KD / 2KD - K-Line & CAN)",
                "Toyota Hilux Revo / Rocco / GR (1GD / 2GD - CAN / CAN-FD)",
                "Toyota Fortuner (2005-2014 1KD/2KD | 2015+ 1GD/2GD)",
                "Isuzu D-Max รุ่นแรก (4JH1 / 4JA1-T / 4JK1 / 4JJ1 ปี 2002-2011)",
                "Isuzu All-New D-Max (4JK1 / 4JJ1 VGS ปี 2012-2019)",
                "Isuzu D-Max Blue Power 1.9 / 3.0 (RZ4E / 4JJ3 ปี 2016+)",
                "Isuzu MU-7 / MU-X (4JJ1 / RZ4E / 4JJ3)",
                "Mitsubishi Strada / Triton รุ่นแรก (4D56 / 4M41 ปี 2005-2014)",
                "Mitsubishi Triton / All-New Triton (4N15 / 4N16)",
                "Mitsubishi Pajero Sport (4D56 / 4M41 / 4N15)",
                "Nissan Frontier / Navara D40 / NP300 / Terra (YD25 / YS23)",
                "Ford Ranger รุ่นเก่า / DuraTORQ (WL / WE 2.5 / 3.0)",
                "Ford Ranger T6 / Wildtrak / Raptor (2.2 / 3.2 / 2.0 Bi-Turbo)",
                "Ford Ranger Next-Gen / Everest Next-Gen (CAN-FD 2022+)",
                "Mazda Fighter / BT-50 / BT-50 Pro / All-New BT-50",
                "Chevrolet Colorado ตาหวาน / ดูราแม็กซ์ (2.5 / 2.8 / Trailblazer)",
                "MG Extender / Tata Xenon / Foton Tunland"
            )
            1 -> arrayOf(
                "Toyota Vios / Yaris / Yaris Ativ (1NZ / 3NR)",
                "Toyota Corolla Altis / Camry (1ZZ / 3ZR / 2AZ / 2AR)",
                "Toyota C-HR / Corolla Cross / Yaris Cross (Hybrid / เบนซิน)",
                "Toyota Alphard / Vellfire / Wish / Avanza / Innova",
                "Honda City / Jazz / Brio / Mobilio / BR-V (L15 / P10A Turbo)",
                "Honda Civic FD / FB / FC / FE / Accord G7-G11",
                "Honda CR-V / HR-V / Freed / Stepwagon (R20 / K24 / e:HEV)",
                "Nissan March / Almera / Note / Sylphy / Teana / Kicks e-POWER",
                "Mazda 2 / Mazda 3 / CX-3 / CX-30 / CX-5 / CX-8 (Skyactiv)",
                "Suzuki Swift / Ciaz / Celerio / Ertiga / XL7 / Carry",
                "Mitsubishi Mirage / Attrage / Lancer EX / Xpander",
                "Subaru XV / Forester / Outback / WRX"
            )
            2 -> arrayOf(
                "Toyota Hiace / Commuter / Ventury (2KD / 1KD / 1GD หน้ายาว)",
                "Toyota Majesty / Granvia (1GD-FTV)",
                "Hyundai H-1 / Grand Starex / Staria (D4CB 2.5 CRDi)",
                "Kia Carnival / Grand Carnival (2.2 CRDi)",
                "Nissan Urvan NV350 (YD25)",
                "Suzuki Carry / Toyota Hilux Champ / Mitsubishi L300"
            )
            3 -> arrayOf(
                "Mercedes-Benz C-Class / E-Class / S-Class (W204/W205/W212/W213)",
                "Mercedes-Benz GLC / GLE / Vito / Sprinter",
                "BMW Series 3 / Series 5 / Series 7 (E90/F30/G20/F10/G30)",
                "BMW X1 / X3 / X5 / MINI Cooper",
                "Volkswagen Caravelle / Golf / Passat / Scirocco",
                "Audi A4 / A5 / A6 / Q5 / Q7 / TT",
                "Volvo S60 / S90 / XC40 / XC60 / XC90",
                "Porsche Cayenne / Macan / Panamera",
                "Chevrolet Captiva (ดีเซล 2.0 / เบนซิน 2.4) / Cruze / Sonic",
                "Ford Fiesta / Focus / EcoSport / Escape"
            )
            4 -> arrayOf(
                "MG 3 / MG 5 / MG 6 / MG ZS / MG HS / MG VS HEV",
                "GWM Haval H6 / Haval Jolion / Tank 300 / Tank 500",
                "Chery Tiggo / Omoda C5 / Jaecoo 6",
                "Wuling Air EV / Binguo / Almaz",
                "DFSK Glory 560 / i-Auto"
            )
            5 -> arrayOf(
                "BYD Atto 3 / Dolphin / Seal / Sealion 6 / Sealion 7 / M6",
                "MG 4 Electric / MG EP / MG ES / MG ZS EV / MG Maxus 9 / Cyberster",
                "GWM ORA Good Cat / ORA 07",
                "Neta V / Neta V-II / Neta X",
                "Changan Deepal S07 / L07 / Lumin",
                "GAC Aion Y Plus / Aion ES / Hyptec HT",
                "Tesla Model 3 / Model Y / Model S / Model X",
                "Volvo EX30 / XC40 Recharge / C40",
                "BMW i3 / i4 / iX3 / Mercedes-Benz EQB / EQS"
            )
            6 -> arrayOf(
                "Hino 300 Innovator / Dutro (N04C - 24V)",
                "Hino 500 Victor / Dominator / Mega (J08E / J05E / A09C)",
                "Hino 700 Profia หัวลาก (E13C / P11C - J1939)",
                "Isuzu ELF NLR / NMR / NPR / NQR (4JJ1 / 4HK1 - 24V)",
                "Isuzu Forward FRR / FTR / FVM (4HK1 / 6HK1)",
                "Isuzu Deca FXZ / GXZ หัวลาก (6UZ1 / 6WG1 - J1939)",
                "Mitsubishi Fuso Canter / Fighter / Super Great (4M50 / 6M60 / 6R10)",
                "UD Trucks Croner / Quester (GH8E / GH11E)",
                "Scania P-Series / G-Series / R-Series (J1939)",
                "Volvo Truck FM / FH Series (D11 / D13)",
                "Sinotruk Howo / Foton Auman / Dongfeng / JAC"
            )
            else -> arrayOf(
                "Komatsu PC120 / PC130 / PC200-8 / PC200-10 / PC300",
                "Kobelco SK130 / SK140 / SK200-8 / SK200-10 / SK330",
                "Caterpillar CAT 312 / 320D / 320GC / 330 / 336",
                "Hitachi Zaxis ZX130 / ZX200-3 / ZX200-5G / ZX330",
                "Kubota KX / U-Series / รถแทรกเตอร์ M-Series",
                "Yanmar ViO / SV-Series",
                "Sany SY135 / SY215 / SY365",
                "XCMG / Zoomlion / LiuGong / Doosan / Hyundai Excavator"
            )
        }
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, brands)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerCarBrand.adapter = adapter
    }

    private fun setupProtocols() {
        val protocols = arrayOf(
            "0. ค้นหาโปรโตคอลอัตโนมัติ (Auto Detect)",
            "1. ISO 15765-4 CAN 11-bit 500k",
            "2. ISO 15765-4 CAN 29-bit 500k",
            "3. ISO 15765-4 CAN 29-bit 250k",
            "4. SAE J1939 CAN 24V (รถบรรทุก/แบคโฮ)",
            "5. SAE J1708 / J1587 (รถใหญ่รุ่นเก่า)",
            "6. ISO 14230-4 KWP2000 Fast Init",
            "7. ISO 14230-4 KWP2000 5-Baud Init",
            "8. ISO 9141-2 K-Line (รถญี่ปุ่นยุคแรก)",
            "9. CAN-FD (รถใหม่ปี 2022+ / EV)",
            "10. SAE J1850 PWM / VPW (Ford/GM เก่า)",
            "11. Multidiag Pro Direct Relay (สับรีเลย์)"
        )
        val adapter = ArrayAdapter(this, android.R.layout.simple_spinner_item, protocols)
        adapter.setDropDownViewResource(android.R.layout.simple_spinner_dropdown_item)
        spinnerProtocol.adapter = adapter
        spinnerProtocol.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, view: View?, pos: Int, id: Long) {
                if (outStream != null) {
                    val atCmd = when (pos) {
                        0 -> "AT SP 0"; 1 -> "AT SP 6"; 2 -> "AT SP 7"; 3 -> "AT SP 9"
                        4 -> "AT SP A"; 5 -> "AT SP 2"; 6 -> "AT SP 5"; 7 -> "AT SP 4"
                        8 -> "AT SP 3"; 9 -> "AT SP C"; 10 -> "AT SP 1"; else -> "MULTI_RELAY"
                    }
                    log("เลือกโปรโตคอล: ${protocols[pos]}")
                    if (atCmd != "MULTI_RELAY") sendHexCommand(atCmd)
                }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
    }

    private fun showLearningCombinedDialog() {
        val options = arrayOf(
            "1. เลินนิ่งหัวฉีด (Pilot Injection Learning)",
            "2. รีเซ็ตค่าเรียนรู้ซัพพลายปั๊ม (Supply Pump Reset)",
            "3. สั่งเรียนรู้วาล์ว SCV ใหม่ (SCV Valve Init)",
            "4. คาลิเบรตปั๊มไฮดรอลิก & คันเร่ง (รถแบคโฮ)"
        )
        AlertDialog.Builder(this)
            .setTitle("ฟังก์ชันเลินนิ่งหัวฉีด / ซัพพลายปั๊ม")
            .setItems(options) { _, which ->
                log("=== สั่งงาน: ${options[which]} ===")
                sendHexCommand("30 1${which + 3} 01")
            }.show()
    }

    private fun showDpfAndAdblueDialog() {
        val options = arrayOf(
            "1. สั่งเผาเขม่ากรองไอเสีย (DPF Regeneration)",
            "2. รีเซ็ตค่าเถ้าเขม่า DPF หลังล้างท่อ",
            "3. รีเซ็ตระบบน้ำยาไอเสีย AdBlue / SCR"
        )
        AlertDialog.Builder(this)
            .setTitle("ระบบไอเสีย DPF & AdBlue")
            .setItems(options) { _, which ->
                log("=== สั่งงาน: ${options[which]} ===")
                sendHexCommand("31 01 05 0${which + 1}")
            }.show()
    }

    private fun showEvAndBmsDialog() {
        val options = arrayOf(
            "1. เช็กความสมดุลเซลล์แบตเตอรี่ EV (HV Cell Balance)",
            "2. ปรับเทียบระดับพลังงานแบต EV (SOH/SOC)",
            "3. ตั้งค่าแบตเตอรี่ 12V ลูกใหม่ (BMS Registration)"
        )
        AlertDialog.Builder(this)
            .setTitle("ระบบแบตเตอรี่ EV & แบตเตอรี่ 12V")
            .setItems(options) { _, which ->
                if (which == 2) show12VBatteryInput() else {
                    log("=== สั่งงาน EV: ${options[which]} ===")
                    sendHexCommand("31 01 E${which + 1} 00")
                }
            }.show()
    }

    private fun show12VBatteryInput() {
        val inputAh = EditText(this).apply {
            hint = "ระบุความจุแอมป์ (เช่น 65, 75, 80, 100 Ah)"
            inputType = android.text.InputType.TYPE_CLASS_NUMBER
        }
        AlertDialog.Builder(this)
            .setTitle("ลงทะเบียนแบตเตอรี่ 12V (BMS Reset)")
            .setView(inputAh)
            .setPositiveButton("บันทึกค่า") { _, _ ->
                val ah = inputAh.text.toString().ifEmpty { "75" }
                log("ตั้งค่าแบตเตอรี่ 12V ขนาด $ah Ah ลงกล่อง BMS เรียบร้อย")
                sendHexCommand("31 01 0A $ah")
            }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }

    private fun showEpbAndAbsDialog() {
        val options = arrayOf(
            "1. คลายเบรกมือไฟฟ้า (Open EPB - เปลี่ยนผ้าเบรก)",
            "2. ปิดมอเตอร์เบรกมือไฟฟ้า (Close EPB)",
            "3. ไล่ลมเบรก ABS อัตโนมัติ (ABS Bleeding)"
        )
        AlertDialog.Builder(this)
            .setTitle("บริการเบรกมือไฟฟ้า EPB & ไล่ลม ABS")
            .setItems(options) { _, which ->
                log("=== สั่งงาน: ${options[which]} ===")
                sendHexCommand("31 01 06 0${which + 1}")
            }.show()
    }

    private fun showAllResetsDialog() {
        val options = arrayOf(
            "1. รีเซ็ตเซ็นเซอร์มุมพวงมาลัย 0 องศา (SAS Reset)",
            "2. รีเซ็ตลิ้นปีกผีเสื้อไฟฟ้า (Throttle Adaptation)",
            "3. รีเซ็ตค่าเรียนรู้เกียร์ออโต้ (AT / CVT Reset)",
            "4. รีเซ็ตไฟเตือนถ่ายน้ำมันเครื่อง (Oil Reset)",
            "5. รีเซ็ตระบบแอร์ & มอเตอร์ทิศทางลม"
        )
        AlertDialog.Builder(this)
            .setTitle("ฟังก์ชันรีเซ็ตค่ามาตรฐาน")
            .setItems(options) { _, which ->
                log("=== สั่งงาน: ${options[which]} ===")
                sendHexCommand("31 01 0B 0${which + 1}")
            }.show()
    }

    private fun showInjectorCutDialog() {
        val cylinders = arrayOf(
            "ตัดหัวฉีด สูบ 1", "ตัดหัวฉีด สูบ 2", "ตัดหัวฉีด สูบ 3",
            "ตัดหัวฉีด สูบ 4", "ตัดหัวฉีด สูบ 5", "ตัดหัวฉีด สูบ 6",
            "ยกเลิกการตัด (ทำงานครบทุกสูบ)"
        )
        AlertDialog.Builder(this)
            .setTitle("Active Test: ตัดการทำงานหัวฉีด")
            .setItems(cylinders) { _, which ->
                val code = when (which) {
                    0 -> "01"; 1 -> "02"; 2 -> "04"; 3 -> "08"; 4 -> "10"; 5 -> "20"; else -> "00"
                }
                log("Active Test: ${cylinders[which]}")
                sendHexCommand("30 18 07 $code")
            }.show()
    }

    private fun showInjectorCodingDialog() {
        val layout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(40, 20, 40, 10)
        }
        val inputCyl = EditText(this).apply { hint = "ระบุเลขสูบ (1 - 6)" }
        val inputCode = EditText(this).apply { hint = "กรอกรหัสชดเชยหัวฉีด (16 / 24 / 30 หลัก)" }
        layout.addView(inputCyl)
        layout.addView(inputCode)

        AlertDialog.Builder(this)
            .setTitle("ลงทะเบียนรหัสหัวฉีด (Injector ID)")
            .setView(layout)
            .setPositiveButton("เขียนรหัสลงกล่อง") { _, _ ->
                val cyl = inputCyl.text.toString()
                val code = inputCode.text.toString().uppercase()
                log("เขียนรหัสหัวฉีด สูบ $cyl : [$code] ลงกล่อง ECU...")
                sendHexCommand("3B 1$cyl $code")
            }
            .setNegativeButton("ยกเลิก", null)
            .show()
    }

    private fun connectVci(keyword: String) {
        try {
            val btAdapter = BluetoothAdapter.getDefaultAdapter()
            if (btAdapter == null || !btAdapter.isEnabled) {
                Toast.makeText(this, "กรุณาเปิด Bluetooth ที่แท็บเล็ตก่อน", Toast.LENGTH_SHORT).show()
                return
            }

            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S &&
                ActivityCompat.checkSelfPermission(this, Manifest.permission.BLUETOOTH_CONNECT) != PackageManager.PERMISSION_GRANTED) {
                checkBluetoothPermissions()
                return
            }

            val device: BluetoothDevice? = btAdapter.bondedDevices?.firstOrNull {
                it.name.contains(keyword, true) || it.name.contains("Autocom", true) ||
                it.name.contains("Delphi", true) || it.name.contains("OBD", true)
            }

            if (device == null) {
                log("ไม่พบอุปกรณ์ที่จับคู่ไว้ กรุณาจับคู่ Bluetooth ในตั้งค่าแท็บเล็ตก่อน")
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
                        log("เชื่อมต่อ ${device.name} สำเร็จ! พร้อมใช้งานทุกโปรโตคอล")
                    }
                    sendHexCommand("AT RV")
                    listenIncomingData()
                } catch (e: Exception) {
                    runOnUiThread { log("เชื่อมต่อไม่สำเร็จ: ${e.message}") }
                }
            }.start()
        } catch (e: Exception) {
            log("ข้อผิดพลาด Bluetooth: ${e.message}")
        }
    }

    private fun sendHexCommand(hex: String) {
        Thread {
            try {
                val cmd = "$hex\r"
                outStream?.write(cmd.toByteArray())
                outStream?.flush()
                if (!isLiveStreaming) {
                    runOnUiThread { log("TX -> $hex") }
                }
            } catch (e: Exception) {
                if (!isLiveStreaming) {
                    runOnUiThread { log("โหมดทดสอบหน้าจอ (TX -> $hex)") }
                }
            }
        }.start()
    }

    private fun listenIncomingData() {
        val buffer = ByteArray(1024)
        while (true) {
            try {
                val bytes = inStream?.read(buffer) ?: break
                if (bytes > 0) {
                    val rawResponse = String(buffer, 0, bytes).trim()
                    runOnUiThread {
                        if (!isLiveStreaming) log("RX <- $rawResponse")
                        parseVehicleAndEcuInfo(rawResponse)
                        updateDashboardFromHex(rawResponse)
                        parseDtcAndDataLineStatus(rawResponse)
                    }
                }
            } catch (e: Exception) {
                break
            }
        }
    }

    private fun parseDtcAndDataLineStatus(raw: String) {
        if (raw.contains("ERROR", ignoreCase = true) || raw.contains("UNABLE TO CONNECT", ignoreCase = true)) {
            log("⚠️ แจ้งเตือนสาย Data: สัญญาณสาย CAN / K-Line ไม่ตอบสนอง (เช็กสายไฟหรือฟิวส์ OBD2)")
        }
        val cleanHex = raw.replace(" ", "").uppercase()
        val dtcIdx = cleanHex.indexOf("43")
        if (dtcIdx != -1 && cleanHex.length >= dtcIdx + 6) {
            val b1 = cleanHex.substring(dtcIdx + 2, dtcIdx + 4).toIntOrNull(16) ?: 0
            val b2 = cleanHex.substring(dtcIdx + 4, dtcIdx + 6).toIntOrNull(16) ?: 0
            if (b1 != 0 || b2 != 0) {
                val prefix = when ((b1 shr 6) and 0x03) { 0 -> "P"; 1 -> "C"; 2 -> "B"; else -> "U" }
                val code = String.format("%s%02X%02X", prefix, b1 and 0x3F, b2)
                lastDetectedDtc = code
                log("⚠️ ตรวจพบรหัสโค้ดเสีย: $code (กดปุ่ม 'ค้นหาโค้ด & วิธีซ่อม Google' ได้ทันที)")
            }
        }
    }

    private fun parseVehicleAndEcuInfo(raw: String) {
        val cleanHex = raw.replace(" ", "").replace("\r", "").replace("\n", "")
        if (cleanHex.contains("4902") || cleanHex.contains("62F190")) {
            val vinAscii = hexToAscii(cleanHex)
            val vinMatch = Regex("[A-HJ-NPR-Z0-9]{17}").find(vinAscii)
            if (vinMatch != null) {
                val vin = vinMatch.value
                val modelYear = decodeYearFromVin(vin[9])               
                val brandName = decodeBrandFromVin(vin)


                lastDetectedVin = vin
                lastDetectedCar = "$brandName (ปี $modelYear)"
                tvStatus.text = "รถ: $lastDetectedCar\nVIN: $vin"
                log("✔ ตรวจพบรถยนต์: $lastDetectedCar | VIN: $vin")
            }
        }
        if (cleanHex.contains("4904") || cleanHex.contains("62F187") || cleanHex.contains("62F189")) {
            val ecuAscii = hexToAscii(cleanHex).replace(Regex("[^A-Za-z0-9-]"), "")
            if (ecuAscii.length >= 6) {
                lastDetectedEcu = ecuAscii
                tvStatus.append("\nกล่อง ECU: $ecuAscii")
                log("✔ หมายเลขกล่อง ECU / Calibration ID: $ecuAscii")
            }
        }
    }

    private fun updateDashboardFromHex(raw: String) {
        val clean = raw.replace(" ", "").uppercase()
        try {
            if (raw.contains("V", ignoreCase = true) && raw.length <= 8) {
                val voltStr = raw.trim()
                tvDashVolt.text = voltStr
                val voltNum = voltStr.replace("V", "", ignoreCase = true).toFloatOrNull()
                if (voltNum != null && voltNum < 11.5f) {
                    log("⚠️ แรงดันไฟ OBD2 ต่ำ ($voltStr) กรุณาเช็กแบตเตอรี่หรือฟิวส์")
                }
            }
            val rpmIdx = clean.indexOf("410C")
            if (rpmIdx != -1 && clean.length >= rpmIdx + 8) {
                val a = clean.substring(rpmIdx + 4, rpmIdx + 6).toInt(16)
                val b = clean.substring(rpmIdx + 6, rpmIdx + 8).toInt(16)
                tvDashRpm.text = (((a * 256) + b) / 4).toString()
            }
            val spdIdx = clean.indexOf("410D")
            if (spdIdx != -1 && clean.length >= spdIdx + 6) {
                tvDashSpeed.text = clean.substring(spdIdx + 4, spdIdx + 6).toInt(16).toString()
            }
            val tmpIdx = clean.indexOf("4105")
            if (tmpIdx != -1 && clean.length >= tmpIdx + 6) {
                val temp = clean.substring(tmpIdx + 4, tmpIdx + 6).toInt(16) - 40
                tvDashTemp.text = "${temp}°C"
            }
            val railIdx = clean.indexOf("4123")
            if (railIdx != -1 && clean.length >= railIdx + 8) {
                val a = clean.substring(railIdx + 4, railIdx + 6).toInt(16)
                val b = clean.substring(railIdx + 6, railIdx + 8).toInt(16)
                tvDashRail.text = "${((a * 256) + b) * 10} kPa"
            }
            val loadIdx = clean.indexOf("4104")
            if (loadIdx != -1 && clean.length >= loadIdx + 6) {
                val load = (clean.substring(loadIdx + 4, loadIdx + 6).toInt(16) * 100) / 255
                tvDashLoad.text = "$load%"
            }
        } catch (e: Exception) { }
    }

    private fun hexToAscii(hexStr: String): String {
        val output = StringBuilder()
        var i = 0
        while (i < hexStr.length - 1) {
            val str = hexStr.substring(i, i + 2)
            try {
                val charVal = str.toInt(16)
                if (charVal in 32..126) output.append(charVal.toChar())
            } catch (e: Exception) { }
            i += 2
        }
        return output.toString()
    }

    private fun decodeBrandFromVin(vin: String): String {
        return when {
            vin.startsWith("MR0") || vin.startsWith("MHF") || vin.startsWith("JTD") || vin.startsWith("JTE") -> "Toyota (โตโยต้า)"
            vin.startsWith("MP1") || vin.startsWith("MPA") || vin.startsWith("JAL") -> "Isuzu (อีซูซุ)"
            vin.startsWith("MMB") || vin.startsWith("MMT") || vin.startsWith("JMY") -> "Mitsubishi (มิตซูบิชิ)"
            vin.startsWith("MRH") || vin.startsWith("JHM") -> "Honda (ฮอนด้า)"
            vin.startsWith("MNT") || vin.startsWith("JN1") -> "Nissan (นิสสัน)"
            vin.startsWith("MNB") || vin.startsWith("6FP") -> "Ford (ฟอร์ด)"
            vin.startsWith("MM0") || vin.startsWith("JMZ") -> "Mazda (มาสด้า)"
            vin.startsWith("MMM") -> "Chevrolet (เชฟโรเลต)"
            vin.startsWith("LGX") || vin.startsWith("LC0") -> "BYD (บีวายดี EV)"
            vin.startsWith("LSJ") || vin.startsWith("MML") -> "MG (เอ็มจี)"
            vin.startsWith("LGW") -> "GWM / Haval / ORA"
            vin.startsWith("WDD") || vin.startsWith("W1K") || vin.startsWith("WDB") -> "Mercedes-Benz"
            vin.startsWith("WBA") || vin.startsWith("WBS") -> "BMW"
            vin.startsWith("JHH") || vin.startsWith("MHD") -> "Hino Truck (ฮีโน่)"
            else -> "รหัสผู้ผลิต (${vin.take(3)})"
        }
    }

    private fun decodeYearFromVin(yearChar: Char): String {
        return when (yearChar) {
            '4' -> "2004"; '5' -> "2005"; '6' -> "2006"; '7' -> "2007"; '8' -> "2008"; '9' -> "2009"
            'A' -> "2010"; 'B' -> "2011"; 'C' -> "2012"; 'D' -> "2013"; 'E' -> "2014"; 'F' -> "2015"
            'G' -> "2016"; 'H' -> "2017"; 'J' -> "2018"; 'K' -> "2019"; 'L' -> "2020"; 'M' -> "2021"
            'N' -> "2022"; 'P' -> "2023"; 'R' -> "2024"; 'S' -> "2025"; 'T' -> "2026"
            else -> "ไม่ระบุ ($yearChar)"
        }
    }

    private fun log(text: String) {
        tvConsole.append("\n> $text")
    }

    private fun checkBluetoothPermissions() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                ActivityCompat.requestPermissions(
                    this,
                    arrayOf(
                        Manifest.permission.BLUETOOTH_CONNECT,
                        Manifest.permission.BLUETOOTH_SCAN,
                        Manifest.permission.ACCESS_FINE_LOCATION
                    ),
                    100
                )
            }
        } catch (e: Exception) {
            log("แจ้งเตือนสิทธิ์: ${e.message}")
        }
    }
}

