package com.callagent.gateway.ui

import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageButton
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.fragment.app.Fragment
import androidx.lifecycle.ViewModelProvider
import com.callagent.gateway.R
import com.callagent.gateway.service.GatewayService

class SettingsFragment : Fragment() {

    private val vm: GatewayViewModel by lazy {
        ViewModelProvider(requireActivity()).get(GatewayViewModel::class.java)
    }
    private var activeConfigDialog: AlertDialog? = null

    private lateinit var statusDot: View
    private lateinit var tvStatusText: TextView
    private lateinit var tvUptime: TextView
    private lateinit var tvLog: TextView
    private lateinit var svLog: ScrollView
    private lateinit var btnStart: Button
    private lateinit var btnCopyLog: Button
    private lateinit var btnConfig: ImageButton
    private lateinit var btnInfo: ImageButton

    private val importConfigLauncher =
        registerForActivityResult(androidx.activity.result.contract.ActivityResultContracts.GetContent()) { uri: Uri? ->
            uri?.let { loadConfigFromFile(it) }
        }

    override fun onCreateView(
        inflater: LayoutInflater,
        container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View = inflater.inflate(R.layout.fragment_settings, container, false)

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        statusDot = view.findViewById(R.id.statusDot)
        tvStatusText = view.findViewById(R.id.tvStatusText)
        tvUptime = view.findViewById(R.id.tvUptime)
        tvLog = view.findViewById(R.id.tvLog)
        svLog = view.findViewById(R.id.svLog)
        btnStart = view.findViewById(R.id.btnStart)
        btnCopyLog = view.findViewById(R.id.btnCopyLog)
        btnConfig = view.findViewById(R.id.btnConfig)
        btnInfo = view.findViewById(R.id.btnInfo)

        // Fresh log on every view creation — clear both the view and the service buffer
        tvLog.text = ""
        GatewayService.drainLogBuffer()

        btnStart.setOnClickListener {
            if (vm.gatewayRunning.value == true) stopGateway() else startGateway()
        }
        btnCopyLog.setOnClickListener { copyLog() }
        btnConfig.setOnClickListener { showConfigDialog() }
        btnInfo.setOnClickListener { DiagnosticsController(requireActivity()).show() }

        vm.statusText.observe(viewLifecycleOwner) { tvStatusText.text = it }
        vm.statusDotColor.observe(viewLifecycleOwner) { color ->
            statusDot.backgroundTintList =
                android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor(color))
        }
        vm.uptimeText.observe(viewLifecycleOwner) { tvUptime.text = it }
        vm.gatewayRunning.observe(viewLifecycleOwner) { running -> updateToggleButton(running) }
        vm.logText.observe(viewLifecycleOwner) { sb ->
            tvLog.text = sb.toString()
            svLog.post { svLog.fullScroll(ScrollView.FOCUS_DOWN) }
        }
    }

    private fun startGateway() {
        val prefs = requireActivity().getSharedPreferences("gateway", Context.MODE_PRIVATE)
        val server = prefs.getString("server", "") ?: ""
        val port = prefs.getInt("port", 5060)
        val user = prefs.getString("user", "") ?: ""
        val pass = prefs.getString("pass", "") ?: ""
        val localServer = prefs.getBoolean("local_server", false)

        if (server.isEmpty() || user.isEmpty()) {
            (requireActivity() as GatewayHost).appendLog("ERROR: Open config and set server + username first")
            return
        }

        GatewayService.start(requireContext(), server, port, user, pass, localServer)
        vm.setGatewayRunning(true)
        (requireActivity() as GatewayHost).appendLog("Starting gateway: $user@$server:$port")
    }

    private fun stopGateway() {
        GatewayService.stop(requireContext())
        vm.setGatewayRunning(false)
    }

    private fun updateToggleButton(running: Boolean) {
        if (running) {
            btnStart.text = "STOP"
            btnStart.backgroundTintList =
                android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#DC2626"))
        } else {
            btnStart.text = "START"
            btnStart.backgroundTintList =
                android.content.res.ColorStateList.valueOf(android.graphics.Color.parseColor("#16A34A"))
        }
    }

    private fun showConfigDialog() {
        val prefs = requireActivity().getSharedPreferences("gateway", Context.MODE_PRIVATE)
        val view = layoutInflater.inflate(R.layout.dialog_config, null)

        val etServer = view.findViewById<EditText>(R.id.etSipServer)
        val etPort = view.findViewById<EditText>(R.id.etSipPort)
        val etUser = view.findViewById<EditText>(R.id.etSipUser)
        val etPassword = view.findViewById<EditText>(R.id.etSipPassword)
        val cbAutoconnect = view.findViewById<CheckBox>(R.id.cbAutoconnect)
        val cbLocalServer = view.findViewById<CheckBox>(R.id.cbLocalServer)
        val btnLoadConfig = view.findViewById<Button>(R.id.btnLoadConfig)

        etServer.setText(prefs.getString("server", "sip.callagent.pro"))
        etPort.setText(prefs.getInt("port", 5060).toString())
        etUser.setText(prefs.getString("user", ""))
        etPassword.setText(prefs.getString("pass", ""))
        cbAutoconnect.isChecked = prefs.getBoolean("autoconnect", true)
        cbLocalServer.isChecked = prefs.getBoolean("local_server", false)

        btnLoadConfig.setOnClickListener {
            activeConfigDialog?.dismiss()
            importConfigLauncher.launch("*/*")
        }

        activeConfigDialog = AlertDialog.Builder(requireContext())
            .setTitle("SIP Configuration")
            .setView(view)
            .setPositiveButton("Save") { _, _ ->
                val server = etServer.text.toString().trim()
                val port = etPort.text.toString().trim().toIntOrNull() ?: 5060
                val user = etUser.text.toString().trim()
                val pass = etPassword.text.toString().trim()
                prefs.edit()
                    .putString("server", server)
                    .putInt("port", port)
                    .putString("user", user)
                    .putString("pass", pass)
                    .putBoolean("autoconnect", cbAutoconnect.isChecked)
                    .putBoolean("local_server", cbLocalServer.isChecked)
                    .apply()
                (requireActivity() as GatewayHost).appendLog(
                    "Config saved: $user@$server:$port (autoconnect=${cbAutoconnect.isChecked}, local=${cbLocalServer.isChecked})"
                )
            }
            .setNegativeButton("Cancel", null)
            .setOnDismissListener { activeConfigDialog = null }
            .show()
    }

    private fun loadConfigFromFile(uri: Uri) {
        val host = requireActivity() as GatewayHost
        try {
            requireContext().contentResolver.openInputStream(uri)?.use { inputStream ->
                val jsonText = inputStream.bufferedReader().readText()
                val json = org.json.JSONObject(jsonText)

                val prefs = requireActivity().getSharedPreferences("gateway", Context.MODE_PRIVATE)
                val editor = prefs.edit()

                if (json.has("server")) editor.putString("server", json.getString("server"))
                if (json.has("port")) editor.putInt("port", json.getInt("port"))
                if (json.has("user")) editor.putString("user", json.getString("user"))
                if (json.has("pass")) editor.putString("pass", json.getString("pass"))
                if (json.has("local_server")) editor.putBoolean("local_server", json.getBoolean("local_server"))

                editor.apply()

                Toast.makeText(requireContext(), "Config loaded successfully", Toast.LENGTH_SHORT).show()
                showConfigDialog()
            }
        } catch (e: Exception) {
            host.appendLog("ERROR loading config: ${e.message}")
            Toast.makeText(requireContext(), "Failed to load config file", Toast.LENGTH_LONG).show()
        }
    }

    private fun copyLog() {
        val logText = tvLog.text.toString()
        if (logText.isEmpty()) {
            Toast.makeText(requireContext(), "Log is empty", Toast.LENGTH_SHORT).show()
            return
        }
        val clipboard = requireContext().getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        clipboard.setPrimaryClip(ClipData.newPlainText("callagent log", logText))
        Toast.makeText(requireContext(), "Log copied to clipboard", Toast.LENGTH_SHORT).show()
    }
}
