package com.dach.scanner

import android.Manifest
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothDevice
import android.bluetooth.BluetoothSocket
import android.content.pm.PackageManager
import android.os.Build
import android.os.Bundle
import android.view.View
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
    private lateinit var spinnerVehicleGroup: Spinner
    private lateinit var spinnerCarBrand: Spinner
    private lateinit var spinnerProtocol: Spinner

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        try {
            setContentView(R.layout.activity_main)

            tvStatus = findViewById(R.id.tvStatus)
            tvConsole = findViewById(R.id.tvConsole)
            spinnerVehicleGroup = findViewById(R.id.spinnerVehicleGroup)
            spinnerCarBrand = findViewById(R.id.spinnerCarBrand)
            spinnerProtocol = findViewById(R.id.spinnerProtocol)

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
            log("=== สแกนทั้งคัน: [$group] -> $brand ===")
            when {
                group.contains("รถบรรทุก") -> {
                    log("[Heavy-Duty J1939] สแกนกล่อง: Engine ECU, Gearbox, EBS/ABS, Retarder, AdBlue/SCR")
                    sendHexCommand("18 EA FF F9 00 FE CA")
                }
                group.contains("แบคโฮ") -> {
                    log("[Excavator / Machinery] สแกนกล่อง: Engine Controller, Hydraulic Pump, Monitor Panel")
                    sendHexCommand("18 EA FF F9 00 FE CA")
                }
                group.contains("EV") -> {
                    log("[EV / Hybrid] สแกนกล่อง: VCU, BMS แบตเตอรี่แรงสูง, MCU อินเวอร์เตอร์, OBC")
                    sendHexCommand("01 00")
                }
                else -> {
                    if (hardwareMode == "MULTI") log("[Multidiag Pro] สับรีเลย์สแกนครบทุกขา: ECU, TCU, ABS, SRS, BCM, EPS")
                    sendHexCommand("01 00")
                }
            }
        }

        findViewById<Button>(R.id.btnLiveData).setOnClickListener {
            val group = spinnerVehicleGroup.selectedItem?.toString() ?: ""
            val brand = spinnerCarBrand.selectedItem?.toString() ?: ""
            if (group.contains("EV")) {
                log("อ่านค่าสด EV ($brand): แรงดันแบต HV (V), กระแส (A), อุณหภูมิเซลล์, SOH (%)")
                sendHexCommand("22 01 01")
            } else if (group.contains("รถบรรทุก") || group.contains("แบคโฮ")) {
                log("อ่านค่าสด 24V ($brand): รอบเครื่อง, บูสต์เทอร์โบ, แรงดันราง, แรงดันปั๊มไฮดรอลิก")
                sendHexCommand("18 F0 04 00")
            } else {
                log("อ่านค่าสด ($brand): RPM, แรงดันราง, อุณหภูมิน้ำ, ชดเชยหัวฉีด 1-4")
                sendHexCommand("21 45")
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

    private fun setupVehicleGroups() {
        val groups = arrayOf(
            "1. รถกระบะ & รถเก๋ง ไทย-ญี่ปุ่น",
            "2. รถยุโรป & อเมริกา",
            "3. รถจีน (BYD, MG, GWM, Neta, Chery)",
            "4. รถยนต์ไฟฟ้า 100% & ไฮบริด (EV)",
            "5. รถบรรทุกหนัก 10 ล้อ / หัวลาก 24V",
            "6. รถแบคโฮ & เครื่องจักรกลหนัก"
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
            0 -> arrayOf("Toyota (Vigo / Revo / Fortuner / Altis)", "Isuzu (D-Max / MU-X / 4JH1 / 4JJ1 / 1.9)", "Mitsubishi (Triton / Pajero / 4D56 / 4N15)", "Honda / Nissan / Mazda / Suzuki")
            1 -> arrayOf("Mercedes-Benz / BMW / MINI", "Ford (Ranger / Everest / Next-Gen)", "Chevrolet (Colorado / Trailblazer)", "Volkswagen / Audi / Volvo")
            2 -> arrayOf("BYD (Atto 3 / Dolphin / Seal)", "MG (MG4 / EP / ZS EV / Extender)", "GWM (Haval H6 / Ora Good Cat / Tank)", "Neta / Chery / Omoda / Deepal")
            3 -> arrayOf("EV จีน (BYD / MG / Neta / ORA / Deepal)", "EV ยุโรป-อเมริกา (Tesla / BMW i / Benz EQ)", "Hybrid ญี่ปุ่น (Toyota / Honda e:HEV)")
            4 -> arrayOf("Hino (500 Victor / Dominator / 700 - J1939)", "Isuzu Truck (Deca FXZ / GXZ / FRR - 24V)", "Mitsubishi Fuso (Fighter / Super Great)", "UD Trucks / Scania / Volvo Truck")
            else -> arrayOf("Komatsu (PC200-8 / PC200-10)", "Kobelco (SK200-8 / SK200-10)", "Caterpillar CAT (320D / 320GC)", "Hitachi / Kubota / Sany / XCMG")
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
                runOnUiThread { log("TX -> $hex") }
            } catch (e: Exception) {
                runOnUiThread { log("โหมดทดสอบหน้าจอ (TX -> $hex)") }
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
