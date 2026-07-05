package com.example.cybervarilla

import android.animation.ValueAnimator
import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.MediaPlayer
import android.os.Bundle
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.ViewGroup
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.*
import androidx.activity.enableEdgeToEdge
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import androidx.core.content.res.ResourcesCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import java.io.File
import java.text.SimpleDateFormat
import java.util.*

class MainActivity : AppCompatActivity() {

    private lateinit var tvTotal: TextView
    private lateinit var tvPaid: TextView
    private lateinit var tvNP: TextView
    private lateinit var tvCurrentFile: TextView
    private lateinit var etName: EditText
    private lateinit var gridButtons: GridLayout
    private lateinit var btnSave: Button
    private lateinit var btnExport: Button
    private lateinit var btnNewFile: Button
    private lateinit var btnDeleteLast: Button
    private lateinit var tvPizarra: TextView
    private lateinit var horizontalScroll: HorizontalScrollView

    private var lastTotal = 0
    private var lastPaid = 0
    private var lastNP = 0

    private val values = listOf("5", "10", "15", "20", "25", "30", "40", "50", "5", "10", "20", "30")
    private val tempEntries = mutableMapOf<String, MutableMap<Int, Int>>() // Name -> (ButtonIndex -> State)
    private val buttons = mutableListOf<Button>()
    private val animators = mutableMapOf<Int, ValueAnimator>()
    private val actionHistory = mutableListOf<Triple<String, Int, Int>>() // Name, Index, OldState
    
    // Memory cache for already saved entries
    private val savedPagados = mutableMapOf<String, Int>()
    private val savedNoPagados = mutableMapOf<String, Int>()
    
    private var currentFileName = "tooloutput_1.txt"
    private val appFolder by lazy { 
        val dir = File(getExternalFilesDir(null), "CyberVarilla")
        if (!dir.exists()) dir.mkdirs()
        dir
    }
    private val lastFileMarker by lazy { File(appFolder, "last_file.txt") }

    private var playerPaid: MediaPlayer? = null
    private var playerNP: MediaPlayer? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        ViewCompat.setOnApplyWindowInsetsListener(findViewById(R.id.main)) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars())
            v.setPadding(systemBars.left, systemBars.top, systemBars.right, systemBars.bottom)
            insets
        }

        initViews()
        loadLastFileName()
        loadSavedDataFromFile()
        initSounds()
        setupGrid()
        updateTotalsDisplay()
        updatePizarra()

        btnSave.setOnClickListener { 
            pulse(it)
            save() 
        }
        btnExport.setOnClickListener {
            pulse(it)
            showExportDialog()
        }
        btnNewFile.setOnClickListener { 
            pulse(it)
            showNewFileDialog() 
        }
        btnDeleteLast.setOnClickListener { 
            pulse(it)
            deleteLast() 
        }

        etName.addTextChangedListener(object : TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: Editable?) {
                updatePizarra()
                for (i in buttons.indices) {
                    updateButtonAppearance(i)
                }
            }
        })
    }

    private fun initViews() {
        tvTotal = findViewById(R.id.tv_total)
        tvPaid = findViewById(R.id.tv_paid)
        tvNP = findViewById(R.id.tv_np)
        tvCurrentFile = findViewById(R.id.tv_current_file)
        etName = findViewById(R.id.et_name)
        gridButtons = findViewById(R.id.grid_buttons)
        btnSave = findViewById(R.id.btn_save)
        btnExport = findViewById(R.id.btn_export)
        btnNewFile = findViewById(R.id.btn_new_file)
        btnDeleteLast = findViewById(R.id.btn_delete_last)
        tvPizarra = findViewById(R.id.tv_pizarra)
        horizontalScroll = findViewById(R.id.horizontal_scroll)

        setupTitle()
        setupPaging()
    }

    private fun setupPaging() {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        
        findViewById<View>(R.id.scroll_grid).layoutParams.width = screenWidth
        findViewById<View>(R.id.layout_pizarra).layoutParams.width = screenWidth
    }

    private fun setupTitle() {
        val title = "CYBER VARILLITA"
        val spannable = SpannableString(title)
        val purpleColor = ContextCompat.getColor(this, R.color.color_purple)
        val greenColor = ContextCompat.getColor(this, R.color.color_green)
        
        spannable.setSpan(ForegroundColorSpan(purpleColor), 0, 5, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(ForegroundColorSpan(greenColor), 6, title.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        
        tvTotal.parent.let { 
             val tvTitle = findViewById<TextView>(R.id.tv_title)
             tvTitle.text = spannable
        }
    }

    private fun initSounds() {
        playerPaid = MediaPlayer.create(this, R.raw.sound_paid)
        playerNP = MediaPlayer.create(this, R.raw.sound_np)
    }

    private fun playSound(isPaid: Boolean) {
        if (isPaid) {
            playerPaid?.start()
        } else {
            playerNP?.start()
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        playerPaid?.release()
        playerNP?.release()
    }

    private fun loadLastFileName() {
        if (lastFileMarker.exists()) {
            currentFileName = lastFileMarker.readText().trim()
        }
        tvCurrentFile.text = "Archivo: $currentFileName"
    }

    private fun loadSavedDataFromFile() {
        savedPagados.clear()
        savedNoPagados.clear()
        
        val internalFile = File(appFolder, currentFileName)
        if (!internalFile.exists()) return

        var section = ""
        internalFile.forEachLine { line ->
            val trimmed = line.trim()
            when {
                trimmed == "PAGADO" -> section = "P"
                trimmed == "NO PAGADO" -> section = "NP"
                trimmed.startsWith("PAGADO TOTAL") || 
                trimmed.startsWith("NO PAGADO TOTAL") || 
                trimmed.startsWith("TOTAL GENERAL") -> { /* ignore */ }
                trimmed.contains(":") && section.isNotEmpty() -> {
                    try {
                        val parts = trimmed.split(":")
                        if (parts.size >= 2) {
                            val n = parts[0].trim()
                            val v = parts[1].trim().toInt()
                            if (section == "P") {
                                savedPagados[n] = (savedPagados[n] ?: 0) + v
                            } else if (section == "NP") {
                                savedNoPagados[n] = (savedNoPagados[n] ?: 0) + v
                            }
                        }
                    } catch (_: Exception) {}
                }
            }
        }
    }

    private fun setupGrid() {
        gridButtons.removeAllViews()
        buttons.clear()
        
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val padding = (32 * displayMetrics.density).toInt() // 16dp each side
        val spacing = (15 * displayMetrics.density).toInt()
        val btnWidth = (screenWidth - padding - spacing) / 2

        for (i in values.indices) {
            val btn = Button(this).apply {
                text = values[i]
                textSize = 24f
                setTextColor(Color.WHITE)
                typeface = ResourcesCompat.getFont(this@MainActivity, R.font.orbitron_black)
                
                val params = GridLayout.LayoutParams().apply {
                    width = btnWidth
                    height = (100 * displayMetrics.density).toInt()
                    setMargins(0, 0, spacing, spacing)
                }
                layoutParams = params
                
                setOnClickListener { onValueButtonClick(i) }
                
                // Pop-in animation
                alpha = 0f
                scaleX = 0f
                scaleY = 0f
                animate().alpha(1f).scaleX(1f).scaleY(1f).setDuration(300).setStartDelay(i * 50L).start()
            }
            buttons.add(btn)
            gridButtons.addView(btn)
            updateButtonAppearance(i)
        }
    }

    private fun onValueButtonClick(index: Int) {
        val name = etName.text.toString().trim()
        if (name.isEmpty()) {
            Toast.makeText(this, "Ingrese un nombre", Toast.LENGTH_SHORT).show()
            return
        }

        pulse(buttons[index])

        val userEntries = tempEntries.getOrPut(name) { mutableMapOf() }
        val currentState = userEntries.getOrDefault(index, 0)
        
        // Save to history for "Borrar Último" (Undo)
        actionHistory.add(Triple(name, index, currentState))
        if (actionHistory.size > 50) actionHistory.removeAt(0)

        val newState = (currentState + 1) % 3
        userEntries[index] = newState
        
        if (newState == 1) playSound(false)
        else if (newState == 2) playSound(true)

        updateButtonAppearance(index)
        updateTotalsDisplay()
        updatePizarra()
    }

    private fun updatePizarra() {
        val totalPagados = mutableMapOf<String, Int>().apply { putAll(savedPagados) }
        val totalNoPagados = mutableMapOf<String, Int>().apply { putAll(savedNoPagados) }

        // Merge temporary entries for real-time visualization
        tempEntries.forEach { (nombre, entries) ->
            entries.forEach { (idx, state) ->
                val v = values[idx].toInt()
                if (state == 2) {
                    totalPagados[nombre] = (totalPagados[nombre] ?: 0) + v
                } else if (state == 1) {
                    totalNoPagados[nombre] = (totalNoPagados[nombre] ?: 0) + v
                }
            }
        }

        if (totalPagados.isEmpty() && totalNoPagados.isEmpty()) {
            tvPizarra.text = "No hay salidas registradas."
            return
        }

        val sb = StringBuilder()
        sb.append("--- ESTADO ACTUAL DEL INFORME ---\n\n")
        
        sb.append("PAGADO\n----------\n")
        totalPagados.forEach { (n, v) -> sb.append("$n: $v\n") }
        
        sb.append("\nNO PAGADO\n-----------\n")
        totalNoPagados.forEach { (n, v) -> sb.append("$n: $v\n") }

        val pSum = totalPagados.values.sum()
        val npSum = totalNoPagados.values.sum()
        
        sb.append("\nPAGADO TOTAL: $pSum\n")
        sb.append("NO PAGADO TOTAL: $npSum\n")
        sb.append("TOTAL GENERAL: ${pSum + npSum}\n")

        tvPizarra.text = sb.toString()
    }

    private fun pulse(view: View) {
        val scaleX = ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.1f, 1f)
        val scaleY = ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.1f, 1f)
        AnimatorSet().apply {
            playTogether(scaleX, scaleY)
            duration = 150
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun createCyberDrawable(strokeColor: Int, bgColor: Int, radius: Float): LayerDrawable {
        val displayMetrics = resources.displayMetrics
        
        // Glow layer (soft)
        val glow = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.TRANSPARENT)
            setStroke((4 * displayMetrics.density).toInt(), strokeColor)
            alpha = 40
        }
        
        // Inner Glow / Shadow
        val shadow = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.parseColor("#40000000"))
        }
        
        // Main layer
        val main = GradientDrawable().apply {
            cornerRadius = radius
            setColor(bgColor)
            setStroke((2 * displayMetrics.density).toInt(), strokeColor)
        }
        
        val layers = arrayOf(glow, shadow, main)
        val ld = LayerDrawable(layers)
        
        ld.setLayerInset(0, 0, 0, 0, 0)
        ld.setLayerInset(1, (3 * displayMetrics.density).toInt(), (3 * displayMetrics.density).toInt(), 0, 0)
        val m = (2 * displayMetrics.density).toInt()
        ld.setLayerInset(2, m, m, m, m)
        
        return ld
    }

    private fun updateButtonAppearance(index: Int) {
        val btn = buttons[index]
        val name = etName.text.toString().trim()
        val state = if (name.isNotEmpty()) tempEntries[name]?.get(index) ?: 0 else 0
        
        val displayMetrics = resources.displayMetrics
        val radius = 20 * displayMetrics.density
        
        val bgColor = when (state) {
            0 -> ContextCompat.getColor(this, R.color.color_panel)
            1 -> Color.parseColor("#FF6666") // Light red
            2 -> Color.parseColor("#00E666") // Light green
            else -> ContextCompat.getColor(this, R.color.color_panel)
        }
        
        val strokeColor = when (state) {
            0 -> Color.WHITE
            1 -> Color.parseColor("#FFB3B3") // Glow red
            2 -> Color.parseColor("#B3FFD9") // Glow green
            else -> Color.WHITE
        }

        btn.background = createCyberDrawable(strokeColor, bgColor, radius)

        // Handle Blinking
        if (state > 0) {
            startBlinking(index, btn)
        } else {
            stopBlinking(index, btn)
        }
    }

    private fun startBlinking(index: Int, btn: View) {
        if (animators.containsKey(index)) return
        
        val animator = ValueAnimator.ofFloat(1f, 0.4f, 1f).apply {
            duration = 800
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener { animation ->
                btn.alpha = animation.animatedValue as Float
            }
        }
        animator.start()
        animators[index] = animator
    }

    private fun stopBlinking(index: Int, btn: View) {
        animators[index]?.cancel()
        animators.remove(index)
        btn.alpha = 1f
    }

    private fun updateTotalsDisplay() {
        val (filePaid, fileNP) = getFileTotals()
        val (tempPaid, tempNP) = getTempTotals()

        val paid = filePaid + tempPaid
        val np = fileNP + tempNP
        val total = paid + np

        animateTextChange(tvPaid, lastPaid, paid, "PAGADO: ")
        animateTextChange(tvNP, lastNP, np, "N-P: ")
        animateTextChange(tvTotal, lastTotal, total, "TOTAL: ")

        lastPaid = paid
        lastNP = np
        lastTotal = total
    }

    private fun animateTextChange(textView: TextView, from: Int, to: Int, prefix: String) {
        if (from == to) return
        val animator = ValueAnimator.ofInt(from, to)
        animator.duration = 500
        animator.addUpdateListener { 
            textView.text = "$prefix${it.animatedValue}"
        }
        animator.start()
    }

    private fun getFileTotals(): Pair<Int, Int> {
        var paid = 0
        var np = 0
        
        val internalFile = File(appFolder, currentFileName)
        if (!internalFile.exists()) return Pair(0, 0)

        var section = ""
        internalFile.forEachLine { line ->
            val trimmed = line.trim()
            when {
                trimmed == "PAGADO" -> section = "P"
                trimmed == "NO PAGADO" -> section = "NP"
                trimmed.startsWith("PAGADO TOTAL") || 
                trimmed.startsWith("NO PAGADO TOTAL") || 
                trimmed.startsWith("TOTAL GENERAL") -> { /* ignore */ }
                trimmed.contains(":") && section.isNotEmpty() -> {
                    try {
                        val value = trimmed.split(":")[1].trim().toInt()
                        if (section == "P") paid += value else np += value
                    } catch (_: Exception) {}
                }
            }
        }
        return Pair(paid, np)
    }

    private fun getTempTotals(): Pair<Int, Int> {
        var paid = 0
        var np = 0
        tempEntries.values.forEach { userEntries ->
            userEntries.forEach { (idx, state) ->
                val v = values[idx].toInt()
                if (state == 1) np += v
                else if (state == 2) paid += v
            }
        }
        return Pair(paid, np)
    }

    private fun showNewFileDialog() {
        val input = EditText(this)
        input.hint = "Nombre del archivo"
        input.setText(currentFileName.replace(".txt", ""))
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Nuevo Archivo de Salidas")
            .setView(input)
            .setPositiveButton("Crear") { _, _ ->
                var name = input.text.toString().trim()
                if (name.isEmpty()) name = "nuevo_informe"
                if (!name.endsWith(".txt")) name += ".txt"
                
                currentFileName = name
                lastFileMarker.writeText(currentFileName)
                tvCurrentFile.text = "Archivo: $currentFileName"
                
                // Reset state
                tempEntries.clear()
                savedPagados.clear()
                savedNoPagados.clear()
                actionHistory.clear()
                loadSavedDataFromFile()
                
                for (idx in buttons.indices) updateButtonAppearance(idx)
                updateTotalsDisplay()
                updatePizarra()
                Toast.makeText(this, "Sesión activa: $currentFileName", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun showExportDialog() {
        val input = EditText(this)
        val sdf = SimpleDateFormat("dd_MM_HHmm", Locale.getDefault())
        input.setText("Export_${sdf.format(Date())}")
        
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Exportar Informe")
            .setMessage("Se guardará en descargas/CyberVarilla")
            .setView(input)
            .setPositiveButton("Exportar") { _, _ ->
                var name = input.text.toString().trim()
                if (name.isEmpty()) name = "informe_exportado"
                if (!name.endsWith(".txt")) name += ".txt"
                exportToFile(name)
            }
            .setNegativeButton("Cancelar", null)
            .show()
    }

    private fun exportToFile(fileName: String) {
        val sb = StringBuilder()
        sb.append(tvPizarra.text.toString())
        
        try {
            if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.Q) {
                val resolver = contentResolver
                val contentValues = android.content.ContentValues().apply {
                    put(android.provider.MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(android.provider.MediaStore.MediaColumns.MIME_TYPE, "text/plain")
                    put(android.provider.MediaStore.MediaColumns.RELATIVE_PATH, "downloads/CyberVarilla/")
                }
                val uri = resolver.insert(android.provider.MediaStore.Downloads.EXTERNAL_CONTENT_URI, contentValues)
                uri?.let {
                    resolver.openOutputStream(it)?.use { os -> os.write(sb.toString().toByteArray()) }
                }
            } else {
                val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "CyberVarilla")
                if (!dir.exists()) dir.mkdirs()
                File(dir, fileName).writeText(sb.toString())
            }
            Toast.makeText(this, "Exportado: $fileName", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Error: ${e.message}", Toast.LENGTH_SHORT).show()
        }
    }

    private fun save() {
        // Merge temp into saved memory maps
        tempEntries.forEach { (nombre, userEntries) ->
            userEntries.forEach { (idx, state) ->
                val v = values[idx].toInt()
                if (state == 2) {
                    savedPagados[nombre] = (savedPagados[nombre] ?: 0) + v
                } else if (state == 1) {
                    savedNoPagados[nombre] = (savedNoPagados[nombre] ?: 0) + v
                }
            }
        }

        if (tempEntries.isEmpty() && savedPagados.isEmpty() && savedNoPagados.isEmpty()) {
             Toast.makeText(this, "Nada que guardar", Toast.LENGTH_SHORT).show()
             return
        }

        val totalPaid = savedPagados.values.sum()
        val totalNP = savedNoPagados.values.sum()
        val total = totalPaid + totalNP

        val df = SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault())
        val content = StringBuilder().apply {
            append("--- ${df.format(Date())} ---\n\n")
            append("PAGADO\n----------\n")
            savedPagados.filter { it.value > 0 }.forEach { (n, v) -> append("$n: $v\n") }
            append("\nNO PAGADO\n-----------\n")
            savedNoPagados.filter { it.value > 0 }.forEach { (n, v) -> append("$n: $v\n") }
            append("\nPAGADO TOTAL: $totalPaid\n")
            append("NO PAGADO TOTAL: $totalNP\n")
            append("TOTAL GENERAL: $total\n")
        }

        val internalFile = File(appFolder, currentFileName)
        internalFile.writeText(content.toString())

        // Reset
        etName.setText("")
        tempEntries.clear()
        actionHistory.clear()
        updatePizarra()
        for (i in buttons.indices) {
            updateButtonAppearance(i)
        }
        updateTotalsDisplay()
        Toast.makeText(this, "Guardado en sesión: $currentFileName", Toast.LENGTH_SHORT).show()
    }

    private fun deleteLast() {
        if (actionHistory.isEmpty()) {
            Toast.makeText(this, "No hay acciones para borrar", Toast.LENGTH_SHORT).show()
            return
        }

        val lastAction = actionHistory.removeAt(actionHistory.size - 1)
        val name = lastAction.first
        val index = lastAction.second
        val oldState = lastAction.third

        // Update tempEntries
        val userEntries = tempEntries.getOrPut(name) { mutableMapOf() }
        if (oldState == 0) {
            userEntries.remove(index)
            if (userEntries.isEmpty()) tempEntries.remove(name)
        } else {
            userEntries[index] = oldState
        }

        // Update Button Appearance if name matches
        val currentName = etName.text.toString().trim()
        if (currentName == name) {
            updateButtonAppearance(index)
        }

        updateTotalsDisplay()
        updatePizarra()
        Toast.makeText(this, "Última acción borrada", Toast.LENGTH_SHORT).show()
    }
}
