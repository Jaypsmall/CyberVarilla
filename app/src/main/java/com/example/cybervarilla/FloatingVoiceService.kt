package com.example.cybervarilla

import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.Service
import android.content.Context
import android.content.Intent
import android.graphics.Color
import android.graphics.PixelFormat
import android.graphics.drawable.GradientDrawable
import android.graphics.drawable.LayerDrawable
import android.os.Bundle
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.speech.RecognitionListener
import android.speech.RecognizerIntent
import android.speech.SpeechRecognizer
import android.view.ContextThemeWrapper
import android.view.Gravity
import android.view.LayoutInflater
import android.view.MotionEvent
import android.view.View
import android.view.WindowManager
import android.widget.Toast
import androidx.core.app.NotificationCompat
import com.google.android.material.floatingactionbutton.FloatingActionButton
import java.util.Locale

class FloatingVoiceService : Service() {

    private lateinit var windowManager: WindowManager
    private lateinit var floatingView: View
    private lateinit var fab: FloatingActionButton
    private var speechRecognizer: SpeechRecognizer? = null

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        startForegroundService()
        windowManager = getSystemService(Context.WINDOW_SERVICE) as WindowManager
        
        val contextThemeWrapper = ContextThemeWrapper(this, R.style.Theme_Cybervarilla)
        floatingView = LayoutInflater.from(contextThemeWrapper).inflate(R.layout.floating_voice_button, null)

        val params = WindowManager.LayoutParams(
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.WRAP_CONTENT,
            WindowManager.LayoutParams.TYPE_APPLICATION_OVERLAY,
            WindowManager.LayoutParams.FLAG_NOT_FOCUSABLE or WindowManager.LayoutParams.FLAG_LAYOUT_NO_LIMITS,
            PixelFormat.TRANSLUCENT
        )

        params.gravity = Gravity.TOP or Gravity.START
        params.x = 100
        params.y = 100

        fab = floatingView.findViewById(R.id.fab_voice_overlay)
        
        setupCyberStyle()

        fab.setOnClickListener(object : View.OnClickListener {
            private var clickCount = 0
            private val handler = Handler(Looper.getMainLooper())
            private val CLICK_DELAY: Long = 350

            private val clickRunnable = Runnable {
                when (clickCount) {
                    1 -> startVoiceInput()
                    2 -> {
                        val intent = Intent(applicationContext, MainActivity::class.java).apply {
                            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_SINGLE_TOP)
                        }
                        startActivity(intent)
                    }
                    3 -> {
                        Toast.makeText(applicationContext, "Cerrando asistente flotante", Toast.LENGTH_SHORT).show()
                        stopSelf()
                    }
                }
                clickCount = 0
            }

            override fun onClick(v: View) {
                clickCount++
                handler.removeCallbacks(clickRunnable)
                handler.postDelayed(clickRunnable, CLICK_DELAY)
            }
        })
        
        fab.setOnTouchListener(object : View.OnTouchListener {
            private var initialX: Int = 0
            private var initialY: Int = 0
            private var initialTouchX: Float = 0f
            private var initialTouchY: Float = 0f
            private var isDragging = false
            private val touchSlop = 5 

            override fun onTouch(v: View, event: MotionEvent): Boolean {
                when (event.action) {
                    MotionEvent.ACTION_DOWN -> {
                        initialX = params.x
                        initialY = params.y
                        initialTouchX = event.rawX
                        initialTouchY = event.rawY
                        isDragging = false
                        return true
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val deltaX = (event.rawX - initialTouchX).toInt()
                        val deltaY = (event.rawY - initialTouchY).toInt()
                        if (!isDragging && (Math.abs(deltaX) > touchSlop || Math.abs(deltaY) > touchSlop)) {
                            isDragging = true
                        }
                        if (isDragging) {
                            params.x = initialX + deltaX
                            params.y = initialY + deltaY
                            try { windowManager.updateViewLayout(floatingView, params) } catch (_: Exception) { }
                        }
                        return true
                    }
                    MotionEvent.ACTION_UP -> {
                        if (!isDragging) v.performClick()
                        return true
                    }
                }
                return false
            }
        })

        windowManager.addView(floatingView, params)
    }

    private fun setupCyberStyle() {
        val prefs = getSharedPreferences("CyberPrefs", Context.MODE_PRIVATE)
        val mode = prefs.getInt("night_mode", 0)
        val subMode = prefs.getString("night_submode", "RED")
        
        val accentColor = if (mode != 0) {
            if (subMode == "PURPLE") 0xFF9C27B0.toInt() else 0xFFFF0600.toInt()
        } else {
            0xFFB71C1C.toInt() // deep_blood por defecto en modo claro
        }
        
        val bgColor = if (mode != 0) 0xFF1A1A1A.toInt() else 0xFFD1D5D8.toInt()
        
        fab.background = createCyberDrawable(accentColor, bgColor, 15f * resources.displayMetrics.density)
        fab.supportImageTintList = android.content.res.ColorStateList.valueOf(accentColor)
    }

    private fun createCyberDrawable(strokeColor: Int, bgColor: Int, radius: Float): LayerDrawable {
        val density = resources.displayMetrics.density
        
        val glow = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            setColor(Color.TRANSPARENT)
            setStroke((6 * density).toInt(), strokeColor)
            alpha = 30
        }
        
        val main = GradientDrawable().apply {
            shape = GradientDrawable.RECTANGLE
            cornerRadius = radius
            val r = Color.red(bgColor)
            val g = Color.green(bgColor)
            val b = Color.blue(bgColor)
            val centerColor = Color.argb(255, Math.min(255, r + 40), Math.min(255, g + 40), Math.min(255, b + 40))
            colors = intArrayOf(centerColor, bgColor)
            gradientType = GradientDrawable.RADIAL_GRADIENT
            gradientRadius = 150 * density
            setStroke((2 * density).toInt(), strokeColor)
        }
        
        val shine = GradientDrawable().apply {
            cornerRadius = radius
            colors = intArrayOf(Color.argb(80, 255, 255, 255), Color.TRANSPARENT)
            orientation = GradientDrawable.Orientation.TOP_BOTTOM
        }
        
        val rim = GradientDrawable().apply {
            cornerRadius = radius
            setColor(Color.TRANSPARENT)
            setStroke((1 * density).toInt(), Color.argb(180, 255, 255, 255))
        }
        
        val ld = LayerDrawable(arrayOf(glow, main, shine, rim))
        val glowMargin = (3 * density).toInt()
        ld.setLayerInset(0, 0, 0, 0, 0)
        ld.setLayerInset(1, glowMargin, glowMargin, glowMargin, glowMargin)
        ld.setLayerInset(2, glowMargin * 2, glowMargin, glowMargin * 2, (30 * density).toInt())
        ld.setLayerInset(3, glowMargin, glowMargin, glowMargin, glowMargin)
        
        return ld
    }

    private fun startVoiceInput() {
        if (!SpeechRecognizer.isRecognitionAvailable(this)) {
            Toast.makeText(this, "Voz no disponible", Toast.LENGTH_SHORT).show()
            return
        }
        speechRecognizer?.destroy()
        speechRecognizer = SpeechRecognizer.createSpeechRecognizer(this)
        val intent = Intent(RecognizerIntent.ACTION_RECOGNIZE_SPEECH).apply {
            putExtra(RecognizerIntent.EXTRA_LANGUAGE_MODEL, RecognizerIntent.LANGUAGE_MODEL_FREE_FORM)
            putExtra(RecognizerIntent.EXTRA_LANGUAGE, Locale.getDefault())
        }
        speechRecognizer?.setRecognitionListener(object : RecognitionListener {
            override fun onReadyForSpeech(p: Bundle?) { fab.alpha = 0.5f }
            override fun onEndOfSpeech() { fab.alpha = 1.0f }
            override fun onError(e: Int) { fab.alpha = 1.0f }
            override fun onResults(results: Bundle?) {
                val data = results?.getStringArrayList(SpeechRecognizer.RESULTS_RECOGNITION)
                if (!data.isNullOrEmpty()) {
                    val broadcastIntent = Intent("com.example.cybervarilla.VOICE_COMMAND")
                    broadcastIntent.putExtra("VOICE_TEXT", data[0])
                    sendBroadcast(broadcastIntent)
                }
            }
            override fun onBeginningOfSpeech() {}
            override fun onRmsChanged(rmsdB: Float) {}
            override fun onBufferReceived(buffer: ByteArray?) {}
            override fun onPartialResults(p: Bundle?) {}
            override fun onEvent(eventType: Int, params: Bundle?) {}
        })
        speechRecognizer?.startListening(intent)
    }

    private fun startForegroundService() {
        val channelId = "floating_voice_service"
        val channel = NotificationChannel(channelId, "Floating Assistant", NotificationManager.IMPORTANCE_LOW)
        (getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager).createNotificationChannel(channel)
        val notification = NotificationCompat.Builder(this, channelId)
            .setContentTitle("CyberVarilla")
            .setSmallIcon(R.drawable.app_icon)
            .build()
        startForeground(1, notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        speechRecognizer?.destroy()
        if (::floatingView.isInitialized) windowManager.removeView(floatingView)
    }
}
