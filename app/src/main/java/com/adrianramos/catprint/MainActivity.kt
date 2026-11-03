package com.adrianramos.catprint

import android.Manifest
import android.app.Activity
import android.app.AlertDialog
import android.bluetooth.BluetoothAdapter
import android.bluetooth.BluetoothManager
import android.content.Intent
import android.content.SharedPreferences
import android.content.pm.PackageManager
import android.graphics.Bitmap
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.provider.OpenableColumns
import android.text.Editable
import android.text.TextWatcher
import android.view.View
import android.view.WindowInsets
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ImageView
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import java.util.concurrent.Executors
import java.util.concurrent.LinkedBlockingQueue
import java.util.concurrent.TimeUnit

class MainActivity : Activity() {

    private enum class Source { IMAGE, TEXT }

    private lateinit var prefs: SharedPreferences
    private val ui = Handler(Looper.getMainLooper())
    private val renderer = Executors.newSingleThreadExecutor()
    private val worker = Executors.newSingleThreadExecutor()

    private lateinit var printerLabel: TextView
    private lateinit var source: RadioGroup
    private lateinit var pickImage: Button
    private lateinit var textPanel: View
    private lateinit var text: EditText
    private lateinit var markdown: CheckBox
    private lateinit var sizeLabel: TextView
    private lateinit var preview: ImageView
    private lateinit var empty: TextView
    private lateinit var mode: Spinner
    private lateinit var energy: SeekBar
    private lateinit var energyValue: TextView
    private lateinit var printButton: Button
    private lateinit var status: TextView

    private var src = Source.IMAGE
    private var image: Bitmap? = null
    private var rendered: Gray.Result? = null
    private var renderGeneration = 0
    private var printing = false
    private var fontSize = 24

    // ---- lifecycle ----------------------------------------------------------------------------

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)
        prefs = getSharedPreferences("catprint", MODE_PRIVATE)
        bindViews()
        applyInsets()

        fontSize = prefs.getInt("font_size", 24)
        energy.progress = prefs.getInt("energy", 8) - 1
        text.setText(prefs.getString("text", ""))
        markdown.isChecked = prefs.getBoolean("markdown", false)
        setSource(if (prefs.getString("source", "IMAGE") == "TEXT") Source.TEXT else Source.IMAGE)
        updatePrinterLabel()
        updateLabels()

        if (savedInstanceState == null) handleIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleIntent(intent)
    }

    override fun onPause() {
        super.onPause()
        prefs.edit()
            .putString("text", text.text.toString())
            .putBoolean("markdown", markdown.isChecked)
            .putString("source", src.name)
            .putInt("font_size", fontSize)
            .putInt("energy", energy.progress + 1)
            .apply()
    }

    private fun bindViews() {
        printerLabel = findViewById(R.id.printer)
        source = findViewById(R.id.source)
        pickImage = findViewById(R.id.pick_image)
        textPanel = findViewById(R.id.text_panel)
        text = findViewById(R.id.text)
        markdown = findViewById(R.id.markdown)
        sizeLabel = findViewById(R.id.size)
        preview = findViewById(R.id.preview)
        empty = findViewById(R.id.empty)
        mode = findViewById(R.id.mode)
        energy = findViewById(R.id.energy)
        energyValue = findViewById(R.id.energy_value)
        printButton = findViewById(R.id.print)
        status = findViewById(R.id.status)

        mode.adapter = ArrayAdapter(this, android.R.layout.simple_spinner_dropdown_item, Mode.entries.map { it.label })

        source.setOnCheckedChangeListener { _, id -> setSource(if (id == R.id.src_text) Source.TEXT else Source.IMAGE) }
        pickImage.setOnClickListener { pickImage() }
        preview.setOnClickListener { if (src == Source.IMAGE) pickImage() }
        empty.setOnClickListener { if (src == Source.IMAGE) pickImage() }
        findViewById<View>(R.id.open_file).setOnClickListener { openTextFile() }
        findViewById<View>(R.id.smaller).setOnClickListener { changeFontSize(-2) }
        findViewById<View>(R.id.bigger).setOnClickListener { changeFontSize(+2) }
        markdown.setOnCheckedChangeListener { _, _ -> schedulePreview() }
        text.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun onTextChanged(s: CharSequence?, a: Int, b: Int, c: Int) {}
            override fun afterTextChanged(s: Editable?) = schedulePreview()
        })
        mode.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(p: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                prefs.edit().putString("mode_${src.name}", Mode.entries[pos].name).apply()
                schedulePreview(0)
            }
            override fun onNothingSelected(p: AdapterView<*>?) {}
        }
        energy.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(s: SeekBar?, p: Int, fromUser: Boolean) = updateLabels()
            override fun onStartTrackingTouch(s: SeekBar?) {}
            override fun onStopTrackingTouch(s: SeekBar?) {}
        })
        printButton.setOnClickListener { print() }
        printerLabel.setOnClickListener { changePrinter() }
    }

    /** targetSdk 35+ draws behind the system bars. */
    private fun applyInsets() {
        val root = findViewById<View>(R.id.root)
        val l = root.paddingLeft; val t = root.paddingTop; val r = root.paddingRight; val b = root.paddingBottom
        root.setOnApplyWindowInsetsListener { v, insets ->
            if (Build.VERSION.SDK_INT >= 30) {
                val bars = insets.getInsets(WindowInsets.Type.systemBars() or WindowInsets.Type.ime())
                v.setPadding(l + bars.left, t + bars.top, r + bars.right, b + bars.bottom)
            } else @Suppress("DEPRECATION") {
                v.setPadding(l + insets.systemWindowInsetLeft, t + insets.systemWindowInsetTop,
                    r + insets.systemWindowInsetRight, b + insets.systemWindowInsetBottom)
            }
            insets
        }
    }

    // ---- input --------------------------------------------------------------------------------

    private fun setSource(s: Source) {
        src = s
        source.check(if (s == Source.TEXT) R.id.src_text else R.id.src_image)
        pickImage.visibility = if (s == Source.IMAGE) View.VISIBLE else View.GONE
        textPanel.visibility = if (s == Source.TEXT) View.VISIBLE else View.GONE
        empty.text = if (s == Source.IMAGE) "Toca para elegir una imagen" else "Escribe algo para ver la vista previa"
        val default = if (s == Source.IMAGE) Mode.GRAY else Mode.THRESHOLD
        val m = runCatching { Mode.valueOf(prefs.getString("mode_${s.name}", default.name)!!) }.getOrDefault(default)
        mode.setSelection(m.ordinal, false)
        schedulePreview(0)
    }

    private fun changeFontSize(delta: Int) {
        fontSize = (fontSize + delta).coerceIn(14, 48)
        updateLabels()
        schedulePreview(0)
    }

    private fun updateLabels() {
        sizeLabel.text = fontSize.toString()
        energyValue.text = (energy.progress + 1).toString()
    }

    private fun updatePrinterLabel() {
        val name = prefs.getString("printer_name", null)
        printerLabel.text = if (name != null) "● $name" else "Sin impresora"
    }

    private fun handleIntent(intent: Intent?) {
        intent ?: return
        val uri: Uri? = when (intent.action) {
            Intent.ACTION_SEND -> @Suppress("DEPRECATION") (intent.getParcelableExtra(Intent.EXTRA_STREAM) as? Uri)
            Intent.ACTION_VIEW -> intent.data
            else -> return
        }
        val type = intent.type ?: uri?.let { contentResolver.getType(it) } ?: ""
        when {
            uri != null && type.startsWith("image/") -> loadImage(uri)
            uri != null -> loadTextFile(uri)
            intent.action == Intent.ACTION_SEND -> intent.getStringExtra(Intent.EXTRA_TEXT)?.let {
                setText(it, looksLikeMarkdown(it))
            }
        }
    }

    private fun pickImage() {
        val i = if (Build.VERSION.SDK_INT >= 33) Intent(MediaStore.ACTION_PICK_IMAGES)
        else Intent(Intent.ACTION_GET_CONTENT).setType("image/*").addCategory(Intent.CATEGORY_OPENABLE)
        @Suppress("DEPRECATION") startActivityForResult(i, REQ_IMAGE)
    }

    private fun openTextFile() {
        val i = Intent(Intent.ACTION_OPEN_DOCUMENT).addCategory(Intent.CATEGORY_OPENABLE).setType("*/*")
            .putExtra(Intent.EXTRA_MIME_TYPES, arrayOf("text/*", "application/octet-stream"))
        @Suppress("DEPRECATION") startActivityForResult(i, REQ_TEXT)
    }

    @Deprecated("Activity result API needs AndroidX")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        super.onActivityResult(requestCode, resultCode, data)
        val uri = data?.data ?: return
        if (resultCode != RESULT_OK) return
        when (requestCode) {
            REQ_IMAGE -> loadImage(uri)
            REQ_TEXT -> loadTextFile(uri)
        }
    }

    private fun loadImage(uri: Uri) {
        setSource(Source.IMAGE)
        status.text = "Cargando imagen…"
        renderer.execute {
            val bmp = runCatching { Gray.loadBitmap(this, uri) }
            ui.post {
                bmp.onSuccess { image = it; status.text = ""; schedulePreview(0) }
                    .onFailure { status.text = it.message ?: "No pude abrir la imagen." }
            }
        }
    }

    private fun loadTextFile(uri: Uri) {
        val name = runCatching {
            contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use {
                if (it.moveToFirst()) it.getString(0) else null
            }
        }.getOrNull() ?: uri.lastPathSegment ?: ""
        val content = runCatching {
            contentResolver.openInputStream(uri)!!.use { it.readBytes().toString(Charsets.UTF_8) }
        }.getOrElse { toast("No pude abrir el archivo."); return }
        val isMd = name.lowercase().let { it.endsWith(".md") || it.endsWith(".markdown") } || looksLikeMarkdown(content)
        setText(content, isMd)
    }

    private fun setText(content: String, isMarkdown: Boolean) {
        setSource(Source.TEXT)
        markdown.isChecked = isMarkdown
        text.setText(content)
    }

    private fun looksLikeMarkdown(s: String) =
        Regex("""(?m)^(#{1,6} |\s*[-*+] |\s*\d+\. |> |```|\|.*\|)|\*\*\S|\[\[|\$\$""").containsMatchIn(s)

    // ---- preview ------------------------------------------------------------------------------

    private val previewRunnable = Runnable { renderPreview() }

    private fun schedulePreview(delayMs: Long = 350) {
        ui.removeCallbacks(previewRunnable)
        ui.postDelayed(previewRunnable, delayMs)
    }

    private fun currentMode() = Mode.entries[mode.selectedItemPosition.coerceAtLeast(0)]

    /** Builds the grayscale page for the current input, off the UI thread. */
    private fun buildPage(): () -> Gray? {
        val s = src
        val img = image
        val t = text.text.toString()
        val md = markdown.isChecked
        val size = fontSize.toFloat()
        return {
            when (s) {
                Source.IMAGE -> img?.let { Gray.fromBitmap(it) }
                Source.TEXT -> if (t.isBlank()) null
                else Gray.fromBitmap(if (md) TextRender.markdown(t, size) else TextRender.plain(t, size))
            }
        }
    }

    private fun renderPreview() {
        val gen = ++renderGeneration
        val page = buildPage()
        val m = currentMode()
        renderer.execute {
            val result = runCatching { page()?.render(m) }
            ui.post {
                if (gen != renderGeneration) return@post
                result.onSuccess { r ->
                    rendered = r
                    preview.setImageBitmap(r?.preview)
                    empty.visibility = if (r == null) View.VISIBLE else View.GONE
                    preview.visibility = if (r == null) View.INVISIBLE else View.VISIBLE
                    // With nothing to show, keep any message (e.g. why an image failed to load).
                    if (!printing && r != null) status.text = "${r.rows.size / 8} mm de papel"
                }.onFailure {
                    rendered = null
                    status.text = it.message ?: "No pude generar la vista previa."
                }
            }
        }
    }

    // ---- printing -----------------------------------------------------------------------------

    private fun neededPermissions(): Array<String> =
        if (Build.VERSION.SDK_INT >= 31) arrayOf(Manifest.permission.BLUETOOTH_SCAN, Manifest.permission.BLUETOOTH_CONNECT)
        else arrayOf(Manifest.permission.ACCESS_FINE_LOCATION)

    /** What to run once the Bluetooth permissions are granted. */
    private var afterPermissions: (() -> Unit)? = null

    /** Checks permissions and that Bluetooth is on; if not, asks and returns false. */
    private fun bluetoothReady(then: () -> Unit): Boolean {
        val missing = neededPermissions().filter { checkSelfPermission(it) != PackageManager.PERMISSION_GRANTED }
        if (missing.isNotEmpty()) {
            afterPermissions = then
            requestPermissions(missing.toTypedArray(), REQ_PERMISSIONS)
            return false
        }
        val adapter = (getSystemService(BLUETOOTH_SERVICE) as BluetoothManager).adapter
        if (adapter == null) { toast("Este teléfono no tiene Bluetooth."); return false }
        if (!adapter.isEnabled) {
            @Suppress("DEPRECATION", "MissingPermission")
            startActivityForResult(Intent(BluetoothAdapter.ACTION_REQUEST_ENABLE), REQ_BLUETOOTH)
            return false
        }
        return true
    }

    /** Scans and, when there is more than one printer in range, lets the user pick. Worker thread. */
    private fun choosePrinter(printer: Printer, alwaysAsk: Boolean = false): PrinterInfo {
        val found = printer.scanAll()
        val pick = if (found.size == 1 && !alwaysAsk) found[0]
        else askWhichPrinter(found) ?: throw PrintException("Cancelado.")
        savePrinter(pick)
        return pick
    }

    private fun askWhichPrinter(found: List<PrinterInfo>): PrinterInfo? {
        val answer = LinkedBlockingQueue<Any>()
        val current = prefs.getString("printer_address", null)
        val labels = found.mapIndexed { i, p ->
            val distance = when {
                p.rssi >= -60 -> "muy cerca"
                p.rssi >= -75 -> "cerca"
                else -> "lejos"
            }
            val name = p.name.ifBlank { "Impresora" }
            val id = p.address.takeLast(5) // the end of the MAC tells two units of the same model apart
            buildString {
                append("$name  ·  $id  ·  $distance")
                if (i == 0 && found.size > 1) append(" (la más cercana)")
                if (p.address == current) append("  ✓")
            }
        }
        ui.post {
            if (isFinishing) { answer.offer(Unit); return@post }
            AlertDialog.Builder(this)
                .setTitle(if (found.size > 1) "Hay ${found.size} impresoras: ¿cuál uso?" else "Impresora encontrada")
                .setItems(labels.toTypedArray()) { _, i -> answer.offer(found[i]) }
                .setNegativeButton("Cancelar") { _, _ -> answer.offer(Unit) }
                .setOnCancelListener { answer.offer(Unit) }
                .show()
        }
        return answer.poll(5, TimeUnit.MINUTES) as? PrinterInfo
    }

    /** Tapping the printer name: look around again and pick (or switch) printer. */
    private fun changePrinter() {
        if (printing || !bluetoothReady { changePrinter() }) return
        setPrinting(true, "Buscando…")
        worker.execute {
            val error = runCatching { choosePrinter(Printer(this) { msg -> ui.post { status.text = msg } }, alwaysAsk = true) }
                .exceptionOrNull()
            ui.post {
                setPrinting(false)
                status.text = error?.message ?: "Impresora guardada ✓"
            }
        }
    }

    private fun print() {
        if (printing || !bluetoothReady { print() }) return

        // Render again so the job matches the current input exactly.
        ui.removeCallbacks(previewRunnable)
        val page = buildPage()
        val m = currentMode()
        val energyValue = (energy.progress + 1) * 0xffff / 10

        setPrinting(true)
        worker.execute {
            val printer = Printer(this) { msg -> ui.post { status.text = msg } }
            val error = runCatching {
                val result = page()?.render(m) ?: throw PrintException("No hay nada que imprimir.")
                val job = PrintJob(result.rows, result.levels, energyValue)
                val saved = savedPrinter()
                // Once chosen, a printer is remembered: others in the room are never used by accident.
                val info = saved ?: choosePrinter(printer)
                try {
                    printer.print(info, job)
                } catch (e: ConnectException) {
                    if (saved == null) throw e
                    // The saved printer may be off or out of range: look again and let the user pick.
                    printer.print(choosePrinter(printer, alwaysAsk = true), job)
                }
                if (m == Mode.GRAY && !(savedPrinter()?.isMxw01 ?: true)) {
                    ui.post { toast("Esta impresora no tiene grises: se imprimió con Floyd-Steinberg.") }
                }
            }.exceptionOrNull()
            ui.post {
                setPrinting(false)
                status.text = when (error) {
                    null -> "Listo ✓"
                    is PrintException -> error.message
                    else -> "Error: ${error.message ?: error.javaClass.simpleName}"
                }
            }
        }
    }

    @Deprecated("Deprecated in Java")
    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (requestCode != REQ_PERMISSIONS) return
        val then = afterPermissions
        afterPermissions = null
        if (grantResults.isNotEmpty() && grantResults.all { it == PackageManager.PERMISSION_GRANTED }) then?.invoke()
        else status.text = "Sin permiso de Bluetooth no puedo encontrar la impresora."
    }

    private fun setPrinting(on: Boolean, label: String = "Imprimiendo…") {
        printing = on
        printButton.isEnabled = !on
        printButton.text = if (on) label else "Imprimir"
    }

    private fun savedPrinter(): PrinterInfo? {
        val a = prefs.getString("printer_address", null) ?: return null
        return PrinterInfo(a, prefs.getString("printer_name", "") ?: "")
    }

    private fun savePrinter(p: PrinterInfo) {
        prefs.edit().putString("printer_address", p.address).putString("printer_name", p.name.ifBlank { "Impresora" }).apply()
        ui.post { updatePrinterLabel() }
    }

    private fun toast(s: String) = Toast.makeText(this, s, Toast.LENGTH_LONG).show()

    companion object {
        private const val REQ_IMAGE = 1
        private const val REQ_TEXT = 2
        private const val REQ_PERMISSIONS = 3
        private const val REQ_BLUETOOTH = 4
    }
}
