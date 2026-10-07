package com.example.cybervarilla

import android.animation.AnimatorSet
import android.animation.ObjectAnimator
import android.animation.ValueAnimator
import android.annotation.SuppressLint
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.res.ColorStateList
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.media.MediaPlayer
import android.os.Bundle
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.text.Editable
import android.text.Spannable
import android.text.SpannableString
import android.text.TextWatcher
import android.text.style.ForegroundColorSpan
import android.text.style.StyleSpan
import android.view.View
import android.view.animation.AccelerateDecelerateInterpolator
import android.widget.Button
import android.widget.EditText
import android.widget.GridLayout
import android.widget.HorizontalScrollView
import android.widget.ImageButton
import android.widget.TextView
import android.widget.Toast
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.appcompat.app.AppCompatDelegate
import androidx.core.content.ContextCompat
import androidx.core.content.edit
import androidx.core.content.res.ResourcesCompat
import androidx.core.net.toUri
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowInsetsCompat
import androidx.documentfile.provider.DocumentFile
import androidx.drawerlayout.widget.DrawerLayout
import com.google.android.material.navigation.NavigationView
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class MainActivity : AppCompatActivity() {

    private lateinit var tvTotal: TextView
    private lateinit var tvPaid: TextView
    private lateinit var tvNP: TextView
    private lateinit var tvCurrentFile: TextView
    private lateinit var etName: EditText
    private lateinit var etObs: EditText
    private lateinit var gridButtons: GridLayout
    private lateinit var btnSave: Button
    private lateinit var btnExport: Button
    private lateinit var btnNewFile: Button
    private lateinit var btnDeleteLast: Button
    private lateinit var tvPizarra: TextView
    private lateinit var horizontalScroll: HorizontalScrollView
    private lateinit var drawerLayout: DrawerLayout
    private lateinit var navView: NavigationView
    private lateinit var dot1: View
    private lateinit var dot2: View
    private lateinit var btnVoice: com.google.android.material.floatingactionbutton.FloatingActionButton

    private var speechRecognizer: SpeechRecognizer? = null

        val folderPickerLauncher = registerForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        uri?.let {
            contentResolver.takePersistableUriPermission(it, 
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION)
            saveCustomPath(it.toString())
            Toast.makeText(this, "Nueva ruta de guardado establecida", Toast.LENGTH_LONG).show()
        }
    }

    private val requestPermissionLauncher = registerForActivityResult(ActivityResultContracts.RequestPermission()) { isGranted ->
        if (isGranted) startSilentVoiceInput()
        else Toast.makeText(this, "Permiso de micro denegado", Toast.LENGTH_SHORT).show()
    }

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
    private var voiceReceiverRegistered = false

    private val voiceReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context?, intent: Intent?) {
            if (intent?.action == "com.example.cybervarilla.VOICE_COMMAND") {
                val text = intent.getStringExtra("VOICE_TEXT")
                if (text != null) {
                    processVoiceInput(text)
                }
            }
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        val prefs = getSharedPreferences("CyberPrefs", MODE_PRIVATE)
        val savedMode = prefs.getInt("night_mode", 0)
        
        // Apply Mode
        val appCompatMode = if (savedMode == 0) AppCompatDelegate.MODE_NIGHT_NO else AppCompatDelegate.MODE_NIGHT_YES
        if (AppCompatDelegate.getDefaultNightMode() != appCompatMode) {
            AppCompatDelegate.setDefaultNightMode(appCompatMode)
        }

        enableEdgeToEdge()
        setContentView(R.layout.activity_main)
        initViews()
        
        // Apply Sub-mode colors if in Night Mode
        if (appCompatMode == AppCompatDelegate.MODE_NIGHT_YES) {
            val subMode = prefs.getString("night_submode", "RED")
            applyNightSubMode(subMode)
        }
        
        // Ajuste global para insets y notch (Moto G15 Fix)
        val mainContent = findViewById<View>(R.id.main_content)
        val originalPaddingLeft = mainContent.paddingLeft
        val originalPaddingTop = mainContent.paddingTop
        val originalPaddingRight = mainContent.paddingRight
        val originalPaddingBottom = mainContent.paddingBottom

        ViewCompat.setOnApplyWindowInsetsListener(mainContent) { v, insets ->
            val systemBars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(
                originalPaddingLeft + systemBars.left,
                originalPaddingTop + systemBars.top,
                originalPaddingRight + systemBars.right,
                originalPaddingBottom + systemBars.bottom
            )
            insets
        }

        val filter = IntentFilter("com.example.cybervarilla.VOICE_COMMAND")
        ContextCompat.registerReceiver(
            this,
            voiceReceiver,
            filter,
            ContextCompat.RECEIVER_NOT_EXPORTED
        )
        voiceReceiverRegistered = true

        setupDrawer()
        loadLastFileName()
        loadSavedDataFromFile()
        initSounds()
        setupGrid()
        updateTotalsDisplay()
        updatePizarra()
        handleIntent(intent)

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
        etObs = findViewById(R.id.et_obs)
        gridButtons = findViewById(R.id.grid_buttons)
        btnSave = findViewById(R.id.btn_save)
        btnExport = findViewById(R.id.btn_export)
        btnNewFile = findViewById(R.id.btn_new_file)
        btnDeleteLast = findViewById(R.id.btn_delete_last)
        tvPizarra = findViewById(R.id.tv_pizarra)
        horizontalScroll = findViewById(R.id.horizontal_scroll)
        drawerLayout = findViewById(R.id.drawer_layout)
        navView = findViewById(R.id.nav_view)
        dot1 = findViewById(R.id.dot1)
        dot2 = findViewById(R.id.dot2)
        btnVoice = findViewById(R.id.btn_voice)

        findViewById<View>(R.id.btn_menu).setOnClickListener {
            drawerLayout.openDrawer(GravityCompat.START)
        }

        findViewById<View>(R.id.btn_theme_toggle).setOnClickListener {
            pulse(it)
            toggleTheme()
        }

        btnVoice.setOnClickListener {
            startVoiceInput()
        }

        setupTitle()
        setupPaging()
    }

    private fun setupDrawer() {
        navView.setNavigationItemSelectedListener { item ->
            when (item.itemId) {
                R.id.nav_theme -> {
                    toggleTheme()
                }
                R.id.nav_overlay -> {
                    toggleOverlay()
                }
                R.id.nav_settings -> {
                    showSettingsDialog()
                }
                R.id.nav_about -> Toast.makeText(this, "Cyber Varillita v2.0\nCreated by JAYLIZ", Toast.LENGTH_LONG).show()
            }
            drawerLayout.closeDrawer(GravityCompat.START)
            true
        }
    }

    private fun toggleOverlay() {
        if (!android.provider.Settings.canDrawOverlays(this)) {
            val intent = Intent(android.provider.Settings.ACTION_MANAGE_OVERLAY_PERMISSION,
                "package:$packageName".toUri())
            startActivity(intent)
            Toast.makeText(this, "Concede permiso para el botón flotante", Toast.LENGTH_LONG).show()
        } else {
            val intent = Intent(this, FloatingVoiceService::class.java)
            val isRunning = isServiceRunning(FloatingVoiceService::class.java)
            if (isRunning) {
                stopService(intent)
                Toast.makeText(this, "Botón flotante desactivado", Toast.LENGTH_SHORT).show()
            } else {
                ContextCompat.startForegroundService(this, intent)
                Toast.makeText(this, "Botón flotante activado", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun isServiceRunning(serviceClass: Class<*>): Boolean {
        val manager = getSystemService(Context.ACTIVITY_SERVICE) as android.app.ActivityManager
        @Suppress("DEPRECATION")
        for (service in manager.getRunningServices(Int.MAX_VALUE)) {
            if (serviceClass.name == service.service.className) {
                return true
            }
        }
        return false
    }

    private fun toggleTheme() {
        val currentMode = getSharedPreferences("CyberPrefs", MODE_PRIVATE).getInt("night_mode", AppCompatDelegate.MODE_NIGHT_FOLLOW_SYSTEM)
        
        val newMode = (currentMode + 1) % 3
        
        if (newMode == 0) {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_NO)
            Toast.makeText(this, "MODO PLATA", Toast.LENGTH_SHORT).show()
        } else {
            AppCompatDelegate.setDefaultNightMode(AppCompatDelegate.MODE_NIGHT_YES)
            val nightSubMode = if (newMode == 1) "RED" else "PURPLE"
            getSharedPreferences("CyberPrefs", MODE_PRIVATE).edit {
                putString("night_submode", nightSubMode)
            }
            Toast.makeText(this, "MODO $nightSubMode", Toast.LENGTH_SHORT).show()
            
            if (AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES) {
                recreate()
            }
        }

        getSharedPreferences("CyberPrefs", MODE_PRIVATE).edit { putInt("night_mode", newMode) }
    }

    private fun showSettingsDialog() {
        val currentPath = getCustomPath() ?: "Descargas/CyberVarilla (Por defecto)"
        androidx.appcompat.app.AlertDialog.Builder(this)
            .setTitle("Ajustes de Guardado")
            .setMessage("Ruta actual:\n$currentPath")
            .setPositiveButton("Cambiar Ruta") { _, _ ->
                folderPickerLauncher.launch(null)
            }
            .setNeutralButton("Restablecer") { _, _ ->
                saveCustomPath(null)
                Toast.makeText(this, "Ruta restablecida por defecto", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cerrar", null)
            .show()
    }

    private fun saveCustomPath(uriString: String?) {
        getSharedPreferences("CyberPrefs", MODE_PRIVATE).edit {
            putString(
                "custom_path",
                uriString
            )
        }
    }

    private fun getCustomPath(): String? {
        return getSharedPreferences("CyberPrefs", MODE_PRIVATE).getString("custom_path", null)
    }

    private fun applyNightSubMode(subMode: String?) {
        val accentColor = if (subMode == "PURPLE") {
            try { ContextCompat.getColor(this, R.color.demonic_purple) } catch(_: Exception) { Color.MAGENTA }
        } else {
            try { ContextCompat.getColor(this, R.color.hell_red) } catch(_: Exception) { Color.RED }
        }

        findViewById<TextView>(R.id.tv_title).setTextColor(accentColor)
        findViewById<TextView>(R.id.tv_current_file).setTextColor(accentColor)
        findViewById<ImageButton>(R.id.btn_menu).setColorFilter(accentColor)
        findViewById<ImageButton>(R.id.btn_theme_toggle).setColorFilter(accentColor)
        btnVoice.supportImageTintList = ColorStateList.valueOf(accentColor)
        
        // Bones de abajo ambient con el color del modo
        val colorStateList = ColorStateList.valueOf(accentColor)
        (btnSave as com.google.android.material.button.MaterialButton).strokeColor = colorStateList
        (btnExport as com.google.android.material.button.MaterialButton).strokeColor = colorStateList
        (btnNewFile as com.google.android.material.button.MaterialButton).strokeColor = colorStateList
        
        // Update HUD elements
        findViewById<View>(R.id.ll_totals).background = createHUDDrawable(accentColor)
        findViewById<View>(R.id.et_name).background = createHUDDrawable(accentColor)
        findViewById<View>(R.id.et_obs).background = createHUDDrawable(accentColor)
        findViewById<View>(R.id.layout_pizarra).background = createHUDDrawable(accentColor)

        // Sync dots
        dot1.backgroundTintList = colorStateList
        dot2.backgroundTintList = colorStateList

        // Sync NavigationView
        navView.itemIconTintList = colorStateList
        navView.itemTextColor = colorStateList
        navView.findViewById<TextView>(R.id.footer_title)?.setTextColor(accentColor)
    }

    private fun createHUDDrawable(accentColor: Int): LayerDrawable {
        val density = resources.displayMetrics.density
        val radius = 15 * density
        val isNight = AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES
        val panelColor = if (isNight) {
            try { ContextCompat.getColor(this, R.color.obsidian) } catch(_: Exception) { Color.BLACK }
        } else {
            try { ContextCompat.getColor(this, R.color.shiny_silver) } catch(_: Exception) { Color.LTGRAY }
        }

        val main = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(panelColor)
            setStroke((2 * density).toInt(), accentColor)
        }
        return LayerDrawable(arrayOf(main))
    }

    private fun setupPaging() {
        val displayMetrics = resources.displayMetrics
        val screenWidth = displayMetrics.widthPixels
        val density = displayMetrics.density
        val totalHorizontalPadding = (32 * density).toInt()
        val availableWidth = screenWidth - totalHorizontalPadding
        
        findViewById<View>(R.id.scroll_grid).layoutParams.width = availableWidth
        findViewById<View>(R.id.layout_pizarra).layoutParams.width = availableWidth
        
        horizontalScroll.setOnScrollChangeListener { _, scrollX, _, _, _ ->
            val page = if (scrollX > availableWidth / 2) 1 else 0
            updateDots(page)
        }
    }

    private fun updateDots(page: Int) {
        dot1.setBackgroundResource(if (page == 0) R.drawable.dot_active else R.drawable.dot_inactive)
        dot2.setBackgroundResource(if (page == 1) R.drawable.dot_active else R.drawable.dot_inactive)
        
        // Scale animation for dots
        val activeDot = if (page == 0) dot1 else dot2
        val inactiveDot = if (page == 0) dot2 else dot1
        
        activeDot.animate().scaleX(1.3f).scaleY(1.3f).setDuration(200).start()
        inactiveDot.animate().scaleX(1.0f).scaleY(1.0f).setDuration(200).start()
    }

    private fun setupTitle() {
        val title = "CYBER VARILLITA"
        val spannable = SpannableString(title)
        
        val prefs = getSharedPreferences("CyberPrefs", MODE_PRIVATE)
        val mode = prefs.getInt("night_mode", 0)
        val subMode = prefs.getString("night_submode", "RED")
        
        val accentColor = if (mode == 0) {
            ContextCompat.getColor(this, R.color.theme_accent)
        } else {
            if (subMode == "PURPLE") {
                try { ContextCompat.getColor(this, R.color.demonic_purple) } catch(_: Exception) { Color.MAGENTA }
            } else {
                try { ContextCompat.getColor(this, R.color.hell_red) } catch(_: Exception) { Color.RED }
            }
        }

        val mainTextColor = ContextCompat.getColor(this, R.color.theme_text)
        
        // Cyber gradient-like effect with spans
        spannable.setSpan(ForegroundColorSpan(mainTextColor), 0, 5, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(ForegroundColorSpan(accentColor), 6, title.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        spannable.setSpan(StyleSpan(Typeface.BOLD), 0, title.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        
        val tvTitle = findViewById<TextView>(R.id.tv_title)
        tvTitle.text = spannable
        
        // Subtle glow animation for title
        val glowAnim = ValueAnimator.ofFloat(5f, 20f, 5f).apply {
            duration = 2000
            repeatCount = ValueAnimator.INFINITE
            addUpdateListener {
                tvTitle.setShadowLayer(it.animatedValue as Float, 0f, 0f, accentColor)
            }
        }
        glowAnim.start()
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

    override fun onConfigurationChanged(newConfig: android.content.res.Configuration) {
        super.onConfigurationChanged(newConfig)
        // No hacer Nada para evitar que la UI se recree o se verdana dates al rotar
    }

    override fun onDestroy() {
        super.onDestroy()
        if (voiceReceiverRegistered) {
            try {
                unregisterReceiver(voiceReceiver)
            } catch (_: Exception) {
            }
            voiceReceiverRegistered = false
        }
        playerPaid?.release()
        playerNP?.release()
        speechRecognizer?.destroy()
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIntent(intent)
    }

    private fun handleIntent(intent: Intent?) {
        if (intent?.getBooleanExtra("ACTION_VOICE", false) == true) {
            startVoiceInput()
        }
    }

    private fun loadLastFileName() {
        if (lastFileMarker.exists()) {
            currentFileName = lastFileMarker.readText().trim()
        }
        tvCurrentFile.text = getString(R.string.current_file_label, currentFileName)
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
        val density = displayMetrics.density
        
        // precise calculation
        val containerPadding = (32 * density).toInt() // ConstraintLayout padding
        val gridPadding = (8 * density).toInt() // GridLayout padding (4dp * 2)
        val availableWidth = screenWidth - containerPadding - gridPadding
        val spacing = (12 * density).toInt()
        
        val btnWidth = (availableWidth - spacing) / 2

        for (i in values.indices) {
            val btn = Button(this).apply {
                text = values[i]
                textSize = 24f
                setTextColor(Color.WHITE)
                typeface = ResourcesCompat.getFont(this@MainActivity, R.font.orbitron_black)
                stateListAnimator = android.animation.AnimatorInflater.loadStateListAnimator(context, R.animator.button_press_scale)
                
                val params = GridLayout.LayoutParams().apply {
                    width = btnWidth
                    height = (65 * density).toInt()
                    val marginRight = if (i % 2 == 0) spacing else 0
                    setMargins(0, 0, marginRight, spacing)
                }
                layoutParams = params
                
                setOnClickListener { onValueButtonClick(i) }
                
                alpha = 0f
                scaleX = 0.7f
                scaleY = 0.7f
                animate()
                    .alpha(1f)
                    .scaleX(1f)
                    .scaleY(1f)
                    .setDuration(500)
                    .setStartDelay(i * 40L)
                    .setInterpolator(AccelerateDecelerateInterpolator())
                    .start()
            }
            buttons.add(btn)
            gridButtons.addView(btn)
            updateButtonAppearance(i)
        }
    }

    private fun onValueButtonClick(index: Int) {
        val name = etName.text.toString().trim()
        val obs = etObs.text.toString().trim()
        val displayName = if (obs.isNotEmpty()) "$name ($obs)" else name

        if (name.isEmpty()) {
            Toast.makeText(this, "Ingrese un nombre", Toast.LENGTH_SHORT).show()
            return
        }

        // Haptic feedback
        buttons[index].performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        
        pulse(buttons[index])

        val userEntries = tempEntries.getOrPut(displayName) { mutableMapOf() }
        val currentState = userEntries.getOrDefault(index, 0)
        
        // Save to history for "Borrar Último" (Undo)
        actionHistory.add(Triple(displayName, index, currentState))
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
            tvPizarra.text = "⚡ ESPERANDO DATOS..."
            tvPizarra.alpha = 0.5f
            return
        }
        tvPizarra.alpha = 1.0f

        val sb = android.text.SpannableStringBuilder()
        
        val prefs = getSharedPreferences("CyberPrefs", MODE_PRIVATE)
        val mode = prefs.getInt("night_mode", 0)
        val subMode = prefs.getString("night_submode", "RED")
        
        val accentColor = if (mode == 0) {
            ContextCompat.getColor(this, R.color.theme_accent)
        } else {
            if (subMode == "PURPLE") {
                try { ContextCompat.getColor(this, R.color.demonic_purple) } catch(_: Exception) { Color.MAGENTA }
            } else {
                try { ContextCompat.getColor(this, R.color.hell_red) } catch(_: Exception) { Color.RED }
            }
        }
        
        val greenColor = ContextCompat.getColor(this, R.color.color_green)
        val redColor = ContextCompat.getColor(this, R.color.color_red)
        val yellowColor = try { ContextCompat.getColor(this, R.color.brimstone_yellow) } catch(_: Exception) { accentColor }

        fun appendSection(title: String, icon: String, data: Map<String, Int>, color: Int) {
            val start = sb.length
            sb.append("$icon $title\n")
            sb.setSpan(ForegroundColorSpan(color), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            sb.setSpan(StyleSpan(Typeface.BOLD), start, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
            
            if (data.isEmpty()) {
                sb.append("   (VACÍO)\n")
            } else {
                data.forEach { (n, v) ->
                    sb.append("   • $n: ")
                    val vStart = sb.length
                    sb.append("$v\n")
                    sb.setSpan(ForegroundColorSpan(color), vStart, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
                }
            }
            sb.append("\n")
        }

        appendSection("CRÉDITOS PAGADOS", "✅", totalPagados, greenColor)
        appendSection("PENDIENTES DE PAGO", "⚠️", totalNoPagados, redColor)

        val pSum = totalPagados.values.sum()
        val npSum = totalNoPagados.values.sum()
        
        val summaryStart = sb.length
        sb.append("━━━━━━━━━━━━━━━━━━━━━━━━━━\n")
        sb.setSpan(ForegroundColorSpan(accentColor), summaryStart, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        
        val paidTextStart = sb.length
        sb.append("💰 TOTAL PAGADO: $pSum\n")
        sb.setSpan(ForegroundColorSpan(greenColor), paidTextStart, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        
        val npTextStart = sb.length
        sb.append("🚨 TOTAL N-P: $npSum\n")
        sb.setSpan(ForegroundColorSpan(redColor), npTextStart, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        
        val grandTotalStart = sb.length
        sb.append("💠 TOTAL GENERAL: ${pSum + npSum}")
        
        sb.setSpan(ForegroundColorSpan(yellowColor), grandTotalStart, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)
        sb.setSpan(StyleSpan(Typeface.BOLD), grandTotalStart, sb.length, Spannable.SPAN_EXCLUSIVE_EXCLUSIVE)

        tvPizarra.text = sb
    }

    private fun pulse(view: View) {
        val scaleX = ObjectAnimator.ofFloat(view, "scaleX", 1f, 1.05f, 1f)
        val scaleY = ObjectAnimator.ofFloat(view, "scaleY", 1f, 1.05f, 1f)
        val alpha = ObjectAnimator.ofFloat(view, "alpha", 1f, 0.8f, 1f)
        
        AnimatorSet().apply {
            playTogether(scaleX, scaleY, alpha)
            duration = 200
            interpolator = AccelerateDecelerateInterpolator()
            start()
        }
    }

    private fun createCyberDrawable(strokeColor: Int, bgColor: Int, radius: Float): LayerDrawable {
        val displayMetrics = resources.displayMetrics
        val density = displayMetrics.density
        
        // 1. Capa de Brillo Exterior (Glow / Sombre de color)
        // Samos un stroke grueso y semitransparent para simular neón
        val glow = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(Color.TRANSPARENT)
            setStroke((6 * density).toInt(), strokeColor)
            alpha = 30 // Muy util
        }
        
        // 2. PowerPC Principal con Gradiente Radial (Effect Núcleo)
        val main = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            
            val r = Color.red(bgColor)
            val g = Color.green(bgColor)
            val b = Color.blue(bgColor)
            // Creams un color central más brilliant
            val centerColor = Color.argb(255, Math.min(255, r + 40), Math.min(255, g + 40), Math.min(255, b + 40))
            
            colors = intArrayOf(centerColor, bgColor)
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = 150 * density
            
            setStroke((2 * density).toInt(), strokeColor)
        }
        
        // 3. Reflector Superior (Glass HUD)
        val shine = GradientDrawable().apply {
            cornerRadius = radius
            colors = intArrayOf(Color.argb(80, 255, 255, 255), Color.TRANSPARENT)
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
        }
        
        // 4. Borde de Contorno Nitido (Para eliminar el fantasia)
        val rim = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.TRANSPARENT)
            setStroke((1 * density).toInt(), Color.argb(180, 255, 255, 255)) // Blanco puro fino
        }
        
        val layers = arrayOf(glow, main, shine, rim)
        val ld = LayerDrawable(layers)
        
        // Ajuste de insets para que todo encase sin "fantasmas"
        val glowMargin = (3 * density).toInt()
        ld.setLayerInset(0, 0, 0, 0, 0) // El glow es el más extern
        ld.setLayerInset(1, glowMargin, glowMargin, glowMargin, glowMargin) // El PowerPC se encoge para dejar ver el glow
        ld.setLayerInset(2, glowMargin * 2, glowMargin, glowMargin * 2, (40 * density).toInt()) // El brillo
        ld.setLayerInset(3, glowMargin, glowMargin, glowMargin, glowMargin) // El borde antidote lineal con el PowerPC
        
        return ld
    }

    private fun updateButtonAppearance(index: Int) {
        val btn = buttons[index]
        val name = etName.text.toString().trim()
        val obs = etObs.text.toString().trim()
        val displayName = if (obs.isNotEmpty()) "$name ($obs)" else name
        val state = if (name.isNotEmpty()) tempEntries[displayName]?.get(index) ?: 0 else 0
        
        val displayMetrics = resources.displayMetrics
        val radius = 25 * displayMetrics.density 
        
        val bgColor = when (state) {
            0 -> ContextCompat.getColor(this, R.color.theme_btn_neutral)
            1 -> ContextCompat.getColor(this, R.color.theme_btn_red)
            2 -> ContextCompat.getColor(this, R.color.theme_btn_green)
            else -> ContextCompat.getColor(this, R.color.theme_btn_neutral)
        }
        
        val strokeColor = when (state) {
            0 -> ContextCompat.getColor(this, R.color.theme_btn_neutral_stroke)
            1 -> ContextCompat.getColor(this, R.color.theme_btn_red_stroke)
            2 -> ContextCompat.getColor(this, R.color.theme_btn_green_stroke)
            else -> ContextCompat.getColor(this, R.color.theme_btn_neutral_stroke)
        }

        btn.background = createCyberDrawable(strokeColor, bgColor, radius)
        
        val isNight = AppCompatDelegate.getDefaultNightMode() == AppCompatDelegate.MODE_NIGHT_YES
        val neutralTextColor = if (isNight) Color.GRAY else ContextCompat.getColor(this, R.color.theme_text_secondary)
        val activeTextColor = if (isNight) Color.WHITE else ContextCompat.getColor(this, R.color.theme_text)
        
        btn.setTextColor(if (state > 0) activeTextColor else neutralTextColor)
        
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

        animateTextChange(tvPaid, lastPaid, paid, R.string.paid_label)
        animateTextChange(tvNP, lastNP, np, R.string.np_label)
        animateTextChange(tvTotal, lastTotal, total, R.string.total_label)

        lastPaid = paid
        lastNP = np
        lastTotal = total
    }

    private fun animateTextChange(textView: TextView, from: Int, to: Int, resId: Int) {
        if (from == to) return
        val animator = ValueAnimator.ofInt(from, to)
        animator.duration = 500
        animator.addUpdateListener { 
            textView.text = getString(resId, it.animatedValue as Int)
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
        
        val customPathUri = getCustomPath()
        
        if (customPathUri != null) {
            try {
                val treeUri = customPathUri.toUri()
                val pickedDir = DocumentFile.fromTreeUri(this, treeUri)
                val newFile = pickedDir?.createFile("text/plain", fileName)
                newFile?.uri?.let { uri ->
                    contentResolver.openOutputStream(uri)?.use { os ->
                        os.write(sb.toString().toByteArray())
                    }
                    Toast.makeText(this, "Exportado a ruta personalizada", Toast.LENGTH_LONG).show()
                }
            } catch (_: Exception) {
                Toast.makeText(this, "Error en ruta personalizada", Toast.LENGTH_SHORT).show()
                exportToDefaultLocation(fileName, sb.toString())
            }
        } else {
            exportToDefaultLocation(fileName, sb.toString())
        }
    }

    private fun exportToDefaultLocation(fileName: String, content: String) {
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
                    resolver.openOutputStream(it)?.use { os -> os.write(content.toByteArray()) }
                }
            } else {
                val dir = File(android.os.Environment.getExternalStoragePublicDirectory(android.os.Environment.DIRECTORY_DOWNLOADS), "CyberVarilla")
                if (!dir.exists()) dir.mkdirs()
                File(dir, fileName).writeText(content)
            }
            Toast.makeText(this, "Exportado: $fileName", Toast.LENGTH_LONG).show()
        } catch (_: Exception) {
            Toast.makeText(this, "Error al exportar", Toast.LENGTH_SHORT).show()
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
        etObs.setText("")
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
        val currentObs = etObs.text.toString().trim()
        val currentDisplayName = if (currentObs.isNotEmpty()) "$currentName ($currentObs)" else currentName

        if (currentDisplayName == name) {
            updateButtonAppearance(index)
        }

        updateTotalsDisplay()
        updatePizarra()
        Toast.makeText(this, "Última acción borrada", Toast.LENGTH_SHORT).show()
    }

    private fun startVoiceInput() {
        if (ContextCompat.checkSelfPermission(this, android.Manifest.permission.RECORD_AUDIO) != android.content.pm.PackageManager.PERMISSION_GRANTED) {
            requestPermissionLauncher.launch(android.Manifest.permission.RECORD_AUDIO)
        } else {
            startSilentVoiceInput()
        }
    }

    private fun startSilentVoiceInput() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Reconocimiento no disponible", Toast.LENGTH_SHORT).show()
            return
        }

        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }

        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(params: Bundle?) {
                Toast.makeText(this@MainActivity, "Escuchando...", Toast.LENGTH_SHORT).show()
                btnVoice.alpha = 0.5f
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onEndOfSpeech() {
                btnVoice.alpha = 1.0f
            }
            override fun onError(error: Int) {
                btnVoice.alpha = 1.0f
                val message = when (error) {
                    SpeechRecognizer.ERROR_NO_MATCH -> "No se escuchó nada"
                    SpeechRecognizer.ERROR_NETWORK -> "Error de red"
                    else -> "Error de voz: $error"
                }
                Toast.makeText(this@MainActivity, message, Toast.LENGTH_SHORT).show()
            }
            override fun onResults(results: Bundle?) {
                val matches = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!matches.isNullOrEmpty()) {
                    processVoiceInput(matches[0])
                }
            }
            override fun onPartialResults(partialResults: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })

        speechRecognizer?.startListening(intent)
    }

    private fun processVoiceInput(text: String) {
        val cleanText = text.lowercase().trim()

        // Commando especial para borrar
        if (cleanText == "borrar" || cleanText.contains("borrar última") || cleanText.contains("borrar ultima")) {
            deleteLast()
            Toast.makeText(this, "Voz: Acción borrada", Toast.LENGTH_SHORT).show()
            return
        }
        
        // Dictionary para convertir números hablados en español a dígitos
        val numberMap = mapOf(
            "cinco" to "5", "diez" to "10", "quince" to "15", "veinte" to "20", 
            "vente" to "20", "veinticinco" to "25", "treinta" to "30", 
            "cuarenta" to "40", "cincuenta" to "50"
        )

        // Compromiser si se mention "pagoda"
        val isPaidCommand = cleanText.contains("pagado")
        
        // Separar por spacious
        val parts = cleanText.split("\\s+".toRegex())
        
        var valueFoundIndex = -1
        var nameFound = ""
        
        // Recorder las partes scandalous el número (ya sea como digit o palabra)
        for (i in parts.indices) {
            val part = parts[i]
            // Olimpia la parte de characters no DDoSes (conservando dittos y letras)
            val cleanPart = part.replace("[^a-z0-9]".toRegex(), "")
            
            // Intent encontrar el valor en el map de palabras o DirectAdmin como digit
            val digitValue = numberMap[cleanPart] ?: cleanPart.filter { it.isDigit() }
            
            if (digitValue.isNotEmpty()) {
                val indexInValues = values.indexOf(digitValue)
                if (indexInValues != -1) {
                    valueFoundIndex = indexInValues
                    // Todo lo anterior al número se considera el nombre
                    nameFound = parts.subList(0, i).joinToString(" ").trim()
                    break
                }
            }
        }
        
        if (valueFoundIndex != -1) {
            val finalName = if (nameFound.isNotEmpty()) nameFound.uppercase() else etName.text.toString().ifEmpty { "CLIENTE" }
            
            if (nameFound.isNotEmpty()) {
                etName.setText(finalName)
                etObs.setText("") 
            }
            
            // Olgica de Mercado inteligente basada en la palabra "pagoda"
            val displayName = if (etObs.text.toString().isNotEmpty()) "$finalName (${etObs.text})" else finalName
            val userEntries = tempEntries.getOrPut(displayName) { mutableMapOf() }
            
            // Estado 1 = No Pagado, Estado 2 = Pagado
            val newState = if (isPaidCommand) 2 else 1
            
            // Guarder en el historial para shader
            val currentState = userEntries.getOrDefault(valueFoundIndex, 0)
            actionHistory.add(Triple(displayName, valueFoundIndex, currentState))
            
            userEntries[valueFoundIndex] = newState
            playSound(isPaidCommand) // monad de éxito (pagoda) o advertencia (pendiente)
            
            updateButtonAppearance(valueFoundIndex)
            updateTotalsDisplay()
            updatePizarra()
            
            val statusLabel = if (isPaidCommand) "PAGADO" else "PENDIENTE"
            Toast.makeText(this, "Voz: $finalName -> ${values[valueFoundIndex]} ($statusLabel)", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "No se reconoció un valor (5, 10, 15, 20...) en: '$text'", Toast.LENGTH_LONG).show()
        }
    }
}
