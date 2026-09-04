package com.example.mywatchtimerv2application.presentation

import android.annotation.SuppressLint
import android.app.Activity
import android.app.AlertDialog
import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.BroadcastReceiver
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.ServiceConnection
import android.os.Binder
import android.os.Build
import android.os.Bundle
import android.os.CountDownTimer
import android.os.IBinder
import android.text.InputType
import android.util.Log
import android.view.View
import android.widget.EditText
import android.widget.ImageButton
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.compose.ui.semantics.text
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
//import androidx.glance.visibility
import com.example.mywatchtimerv2application.R
import org.json.JSONArray
import org.json.JSONObject
import java.text.SimpleDateFormat
import java.time.OffsetDateTime
import java.time.format.DateTimeFormatter
import java.util.Locale
import java.util.UUID
import java.util.concurrent.TimeUnit


data class SmokeEntry(val id: String, val time: String)

// ---
// 1. TIMER SERVICE CLASS (Unchanged)
// ---
class TimerService : Service() {
    private val TAG = "TimerService"

    companion object {
        const val ACTION_START = "ACTION_START"
        const val ACTION_STOP = "ACTION_STOP"
        const val ACTION_RESTART = "ACTION_RESTART"
        const val TIMER_BR = "com.example.myapplication.presentation.timer_broadcast"
        const val TIME_LEFT_KEY = "time_left"
        const val IS_OVERTIME_KEY = "is_overtime"

        var INITIAL_TIME_MS = TimeUnit.HOURS.toMillis(1) + TimeUnit.MINUTES.toMillis(15)
        private const val NOTIFICATION_ID = 101
        private const val CHANNEL_ID = "TimerChannel"
    }

    private var mainTimer: CountDownTimer? = null
    private var overtimeTimer: CountDownTimer? = null
    var timeRemaining: Long = INITIAL_TIME_MS // Make public for editing
    private var overtimeCounter: Long = 0
    private var isRunning: Boolean = false
    private var isOvertime: Boolean = false

    private val binder = LocalBinder()
    inner class LocalBinder : Binder() {
        fun getService(): TimerService = this@TimerService
    }

    override fun onBind(intent: Intent?): IBinder = binder

    override fun onCreate() {
        super.onCreate()
        createNotificationChannel()
        startForeground(NOTIFICATION_ID, buildNotification(timeRemaining, "Paused"))
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> startTimer()
            ACTION_STOP -> pauseTimer()
            ACTION_RESTART -> restartTimer()
        }
        return START_STICKY // This tells Android to recreate service if killed
    }


    fun startTimer() {
        if (isRunning) return
        isRunning = true

        if (isOvertime) {
            startOvertimeTimer()
        } else {
            mainTimer?.cancel()
            mainTimer = object : CountDownTimer(timeRemaining, 1000) {
                override fun onTick(millisUntilFinished: Long) {
                    timeRemaining = millisUntilFinished
                    updateNotification(timeRemaining, "Running...")
                    sendUpdateBroadcast()
                }

                override fun onFinish() {
                    isOvertime = true
                    timeRemaining = 0
                    overtimeCounter = 0
                    startOvertimeTimer()
                }
            }.start()
        }
        updateNotification(if (isOvertime) overtimeCounter else timeRemaining, "Running...")
        sendUpdateBroadcast()
    }

    private fun startOvertimeTimer() {
        overtimeTimer?.cancel()
        overtimeTimer = object : CountDownTimer(Long.MAX_VALUE, 1000) {
            override fun onTick(millisUntilFinished: Long) {
                overtimeCounter += 1000
                updateNotification(overtimeCounter, "Overtime...")
                sendUpdateBroadcast()
            }
            override fun onFinish() { /* Unreachable */ }
        }.start()
    }

    fun pauseTimer() {
        isRunning = false
        mainTimer?.cancel()
        overtimeTimer?.cancel()
        updateNotification(if (isOvertime) overtimeCounter else timeRemaining, "Paused")
        sendUpdateBroadcast()
    }

    fun restartTimer() {
        pauseTimer()
        timeRemaining = INITIAL_TIME_MS
        isOvertime = false
        overtimeCounter = 0
        startTimer()
    }

    fun setTimer(newTimeMs: Long) {
        pauseTimer()
        timeRemaining = newTimeMs
        isOvertime = false
        overtimeCounter = 0
        sendUpdateBroadcast()
        startTimer()
        //updateNotification(timeRemaining, "Paused")
    }

    fun isTimerRunning(): Boolean = isRunning
    fun getRemainingTimeExplicitly(): Long = timeRemaining
    fun getOvertime(): Long = overtimeCounter
    fun isOvertime(): Boolean = isOvertime

    private fun sendUpdateBroadcast() {
        val intent = Intent(TIMER_BR).setPackage(packageName).apply {
            putExtra(TIME_LEFT_KEY, if (isOvertime) overtimeCounter else timeRemaining)
            putExtra(IS_OVERTIME_KEY, isOvertime)
        }
        sendBroadcast(intent)
    }

    private fun formatTime(timeMs: Long, isNegative: Boolean): String {
        val totalSeconds = timeMs / 1000
        val hours = totalSeconds / 3600
        val minutes = (totalSeconds % 3600) / 60
        val seconds = totalSeconds % 60
        val prefix = if (isNegative) "-" else ""
        return String.format("%s%02d:%02d:%02d", prefix, hours, minutes, seconds)
    }

    private fun updateNotification(time: Long, status: String) {
        val notification = buildNotification(time, status)
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.notify(NOTIFICATION_ID, notification)
    }

    private fun buildNotification(time: Long, status: String): Notification {
        val timeStr = formatTime(time, isOvertime)
        val notificationIntent = Intent(this, MainActivity::class.java)
        val pendingIntent = PendingIntent.getActivity(this, 0, notificationIntent, PendingIntent.FLAG_IMMUTABLE)

        val builder = NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle("Timer")
            .setContentText("$timeStr | $status")
            .setSmallIcon(R.drawable.cigarette_icon)
            .setOngoing(true)
            .setContentIntent(pendingIntent)

        if (isRunning) {
            builder.addAction(0, "Pause", getPendingIntent(ACTION_STOP))
        } else {
            builder.addAction(0, "Resume", getPendingIntent(ACTION_START))
        }
        builder.addAction(0, "Restart", getPendingIntent(ACTION_RESTART))

        return builder.build()
    }

    private fun createNotificationChannel() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val serviceChannel = NotificationChannel(CHANNEL_ID, "Timer Service Channel", NotificationManager.IMPORTANCE_LOW)
            val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
            manager.createNotificationChannel(serviceChannel)
        }
    }

    private fun getPendingIntent(action: String): PendingIntent {
        val intent = Intent(this, TimerService::class.java).apply { this.action = action }
        return PendingIntent.getService(this, action.hashCode(), intent, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    override fun onDestroy() {
        super.onDestroy()
        mainTimer?.cancel()
        overtimeTimer?.cancel()
        //Log.d(TAG, "Service destroyed.")
    }
}

// ---
// 2. MAIN ACTIVITY CLASS (With Networking Logic)
// ---
class MainActivity : Activity() {

    private val TAG = "MainActivity"

    // Hour of day (0-23) at which the daily counters reset.
    private val RESET_HOUR = 9

    // UI
    private lateinit var timerText: TextView
    private lateinit var tvEndTime: TextView
    private lateinit var btnCigarette: ImageButton
    private lateinit var btnWeed: ImageButton
    private lateinit var btnThc: ImageButton
    private lateinit var btnReset: ImageButton
    private lateinit var tvCigCount: TextView
    private lateinit var tvWeedCount: TextView
    private lateinit var tvThcCount: TextView
    private lateinit var llCigEntries: LinearLayout
    private lateinit var llWeedEntries: LinearLayout
    private lateinit var llThcEntries: LinearLayout
    private lateinit var tvCigTitle: TextView
    private lateinit var tvWeedTitle: TextView
    private lateinit var tvThcTitle: TextView

    // State
    private var timerService: TimerService? = null
    private var isBound = false
    private var endTimeMillis: Long = 0

    // NOTE: cigEntries and weedEntries are now populated from the network, not SharedPreferences
    // State
    private var cigEntries = mutableListOf<SmokeEntry>()
    private var weedEntries = mutableListOf<SmokeEntry>()
    private var thcEntries = mutableListOf<SmokeEntry>()

    private lateinit var progressBar: ProgressBar

    private val serviceConnection = object : ServiceConnection {
        override fun onServiceConnected(name: ComponentName?, service: IBinder?) {
            val binder = service as TimerService.LocalBinder
            timerService = binder.getService()
            isBound = true

            // If the service isn't running a timer yet, but we have a saved end time in the future
            val now = System.currentTimeMillis()
            if (timerService?.isTimerRunning() == false && endTimeMillis > now) {
                val remaining = endTimeMillis - now
                timerService?.setTimer(remaining)
                timerService?.startTimer()
            }

            updateUI() // This will update the timer display
        }

        override fun onServiceDisconnected(name: ComponentName?) {
            isBound = false
            timerService = null
        }
    }

    private val timerReceiver = object : BroadcastReceiver() {
        override fun onReceive(context: Context, intent: Intent) {
            if (intent.action == TimerService.TIMER_BR) {
                val time = intent.getLongExtra(TimerService.TIME_LEFT_KEY, 0)
                val isOvertime = intent.getBooleanExtra(TimerService.IS_OVERTIME_KEY, false)
                updateTimerDisplay(time, isOvertime)
                // We no longer manage button state here, but we can leave it
                updateButtonStates()
            }
        }
    }

    override fun onResume() {
        super.onResume()
        // Re-render in case the 9:00am reset boundary was crossed while backgrounded.
        renderLocalData()
    }

    private fun processResponse(response: String) {
        try {
            cigEntries.clear()
            weedEntries.clear()
            thcEntries.clear()

            // CHANGE THIS LINE:
            // From: val jsonArray = JSONArray(response)
            // To:
            val jsonResponse = JSONObject(response)
            val jsonArray = jsonResponse.getJSONArray("data")

            for (i in 0 until jsonArray.length()) {
                val entryObject = jsonArray.getJSONObject(i)
                val type = entryObject.getString("type")
                val createdAt = entryObject.getString("createdAt")

                try {
                    val entryDateTime = OffsetDateTime.parse(createdAt)
                    val formatter = DateTimeFormatter.ofPattern("HH:mm", Locale.getDefault())
                    val formattedTime = entryDateTime.format(formatter)
                    val entryId = entryObject.getString("id")

                    if (type == "cigarette") {
                        cigEntries.add(SmokeEntry(entryId, formattedTime))
                    } else if (type == "joint") {
                        weedEntries.add(SmokeEntry(entryId, formattedTime))
                    } else if (type == "thc_joint") {
                        thcEntries.add(SmokeEntry(entryId, formattedTime))
                    }
                } catch (e: Exception) {
                    //Log.e(TAG, "Parsing error: $e")
                }
            }
            updateCounterUI()
            updateEntriesUI()
        } catch (e: Exception) {
            //Log.e(TAG, "JSON process error: $e")
        }
    }


    // --- Load entries from local storage and render the current period's data ---
    // The "day" for counting purposes starts at 9:00am. Counters therefore reset
    // once per day at 9:00am: before 9:00am we still count from yesterday's 9:00am,
    // at/after 9:00am we count from today's 9:00am.
    private fun renderLocalData() {
        val all = loadLocalEntries()
        val periodStart = currentPeriodStart()
        val todayArray = JSONArray()

        for (i in 0 until all.length()) {
            val entry = all.getJSONObject(i)
            try {
                val createdAt = OffsetDateTime.parse(entry.getString("createdAt"))
                if (!createdAt.isBefore(periodStart)) {
                    todayArray.put(entry)
                }
            } catch (e: Exception) {
                //Log.e(TAG, "Skipping malformed entry: $e")
            }
        }

        val response = JSONObject().put("data", todayArray)
        processResponse(response.toString())
    }

    // Start of the current counting period: the most recent 9:00am boundary.
    private fun currentPeriodStart(): OffsetDateTime {
        val now = OffsetDateTime.now()
        val nineToday = now.toLocalDate().atTime(RESET_HOUR, 0)
            .atOffset(now.offset)
        return if (now.isBefore(nineToday)) nineToday.minusDays(1) else nineToday
    }

    // --- Add a new entry directly to local storage ---
    private fun postSmokeEntry(type: String) {
        val all = loadLocalEntries()
        val entry = JSONObject().apply {
            put("id", UUID.randomUUID().toString())
            put("type", type)
            put("createdAt", OffsetDateTime.now().format(DateTimeFormatter.ISO_OFFSET_DATE_TIME))
        }
        all.put(entry)
        saveLocalEntries(all)
        renderLocalData()
    }

    private fun showDeleteConfirmationDialog(entryId: String) {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Delete Entry")
            .setMessage("Are you sure you want to delete this entry?")
            .setPositiveButton("Delete") { _, _ ->
                deleteSmokeEntry(entryId)
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun deleteSmokeEntry(entryId: String) {
        val all = loadLocalEntries()
        val remaining = JSONArray()
        for (i in 0 until all.length()) {
            val entry = all.getJSONObject(i)
            if (entry.optString("id") != entryId) {
                remaining.put(entry)
            }
        }
        saveLocalEntries(remaining)
        renderLocalData()
        Toast.makeText(this, "Entry deleted", Toast.LENGTH_SHORT).show()
    }

    @SuppressLint("UnspecifiedRegisterReceiverFlag")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContentView(R.layout.activity_main)

        // Initialize UI components
        progressBar = findViewById(R.id.progressBar)
        timerText = findViewById(R.id.timer_text)
        tvEndTime = findViewById(R.id.tvEndTime)
        btnCigarette = findViewById(R.id.btnCigarette)
        btnWeed = findViewById(R.id.btnWeed)
        btnThc = findViewById(R.id.btnThc)
        btnReset = findViewById(R.id.btnReset)
        tvCigCount = findViewById(R.id.tvCigCount)
        tvWeedCount = findViewById(R.id.tvWeedCount)
        tvThcCount = findViewById(R.id.tvThcCount)
        llCigEntries = findViewById(R.id.llCigEntries)
        llWeedEntries = findViewById(R.id.llWeedEntries)
        llThcEntries = findViewById(R.id.llThcEntries)
        tvCigTitle = findViewById(R.id.tvCigTitle)
        tvWeedTitle = findViewById(R.id.tvWeedTitle)
        tvThcTitle = findViewById(R.id.tvThcTitle)

        // --- Load entries from local storage for instant view ---
        renderLocalData()

        loadEndTime(this) // Load timer state
        checkAndResetCountersIfNeeded()
        updateEndTimeDisplay()

        val serviceIntent = Intent(this, TimerService::class.java)
        bindService(serviceIntent, serviceConnection, Context.BIND_AUTO_CREATE)
        ContextCompat.registerReceiver(this, timerReceiver, IntentFilter(TimerService.TIMER_BR), ContextCompat.RECEIVER_NOT_EXPORTED)

        // Setup Click Listeners
        btnCigarette.setOnClickListener {
            postSmokeEntry("cigarette")
            restartTimer()
        }

        btnWeed.setOnClickListener {
            postSmokeEntry("joint")
            restartTimer()
        }

        btnThc.setOnClickListener {
            postSmokeEntry("thc_joint")
            restartTimer()
        }

        btnReset.setOnClickListener {
            showResetConfirmationDialog()
        }

        timerText.setOnLongClickListener {
            if (isBound && timerService != null) {
                showEditTimerDialog()
            }
            true
        }
    }

    private fun updateUI() {
        if (!isBound) return
        timerService?.let {
            updateTimerDisplay(it.getRemainingTimeExplicitly(), it.isOvertime())
            updateButtonStates()
        }
    }

    private fun updateTimerDisplay(time: Long, isOvertime: Boolean) {
        val prefix = if (isOvertime) "+" else ""
        timerText.text = prefix + formatTime(time)
    }

    private fun updateCounterUI() {
        tvCigCount.text = "Cigarettes: " + cigEntries.size.toString()
        tvWeedCount.text = "Joints: " + weedEntries.size.toString()
        tvThcCount.text = "THC Count: " + thcEntries.size.toString()
    }


    private fun updateEntriesUI() {
        llCigEntries.removeAllViews()
        llWeedEntries.removeAllViews()
        llThcEntries.removeAllViews()

        // --- Cigarettes ---
        if (cigEntries.isNotEmpty()) {
            tvCigTitle.visibility = View.VISIBLE
            // Note: Assuming cigEntries is now a List of Objects with .id and .time
            cigEntries.forEach { entry ->
                val textView = TextView(this).apply {
                    text = entry.time // The HH:mm string
                    textSize = 18f
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                    setPadding(0, 8, 0, 8)
                    // Set a click listener to delete
                    setOnClickListener {
                        showDeleteConfirmationDialog(entry.id)
                    }
                }
                llCigEntries.addView(textView)
            }
        } else {
            tvCigTitle.visibility = View.GONE
        }

        // --- Joints ---
        if (weedEntries.isNotEmpty()) {
            tvWeedTitle.visibility = View.VISIBLE
            weedEntries.forEach { entry ->
                val textView = TextView(this).apply {
                    text = entry.time
                    textSize = 18f
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                    setPadding(0, 8, 0, 8)
                    setOnClickListener {
                        showDeleteConfirmationDialog(entry.id)
                    }
                }
                llWeedEntries.addView(textView)
            }
        } else {
            tvWeedTitle.visibility = View.GONE
        }

        // --- THC Joints ---
        if (thcEntries.isNotEmpty()) {
            tvThcTitle.visibility = View.VISIBLE
            thcEntries.forEach { entry ->
                val textView = TextView(this).apply {
                    text = entry.time
                    textSize = 18f
                    textAlignment = View.TEXT_ALIGNMENT_CENTER
                    setPadding(0, 8, 0, 8)
                    setOnClickListener {
                        showDeleteConfirmationDialog(entry.id)
                    }
                }
                llThcEntries.addView(textView)
            }
        } else {
            tvThcTitle.visibility = View.GONE
        }
    }

    private fun updateButtonStates() {
        // This function might not be needed anymore if buttons are always active
    }

    private fun restartTimer() {
        if (isBound) {
            // 1. Calculate new end time
            val newEndTime = System.currentTimeMillis() + TimerService.INITIAL_TIME_MS
            endTimeMillis = newEndTime
            saveEndTime(this, newEndTime)

            // 2. Explicitly START the service so it lives in the background
            val intent = Intent(this, TimerService::class.java).apply {
                action = TimerService.ACTION_RESTART
            }
            startService(intent) // This keeps it alive after app closure

            updateEndTimeDisplay()
        }
    }


    private fun startTimer() {
        if (isBound) {
            timerService?.startTimer()
        }
    }

    // --- DIALOGS and PERSISTENCE (Mostly unchanged, but local entry saving is removed) ---

    private fun showEditTimerDialog() {
        val builder = AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
        builder.setTitle("Set Timer (MMSS)")

        val input = EditText(builder.context).apply {
            inputType = InputType.TYPE_CLASS_NUMBER
            hint = "e.g., 4500 for 45:00"
            setTextColor(ContextCompat.getColor(context, android.R.color.white))
            setHintTextColor(ContextCompat.getColor(context, android.R.color.darker_gray))
        }
        builder.setView(input)

        builder.setPositiveButton("Set") { _, _ ->
            val inputText = input.text.toString()
            var newTimeMs: Long? = null

            if (inputText.isNotEmpty()) {
                try {
                    val minutes = if (inputText.length > 2) inputText.substring(0, inputText.length - 2).toLong() else 0
                    val seconds = if (inputText.length >= 2) inputText.substring(inputText.length - 2).toLong() else inputText.toLong()

                    if (seconds >= 60) {
                        Toast.makeText(this, "Seconds must be less than 60.", Toast.LENGTH_SHORT).show()
                    } else {
                        newTimeMs = TimeUnit.MINUTES.toMillis(minutes) + TimeUnit.SECONDS.toMillis(seconds)
                    }
                } catch (e: Exception) {
                    Toast.makeText(this, "Invalid time format.", Toast.LENGTH_SHORT).show()
                }
            }

            newTimeMs?.let { time ->
                timerService?.setTimer(time)
                TimerService.INITIAL_TIME_MS = time // Update the default restart time
                val newEndTime = System.currentTimeMillis() + time
                endTimeMillis = newEndTime
                saveEndTime(this, newEndTime)
                updateEndTimeDisplay()
            }
        }
        builder.setNegativeButton("Cancel") { dialog, _ -> dialog.cancel() }
        builder.show()
    }

    private fun showResetConfirmationDialog() {
        AlertDialog.Builder(this, android.R.style.Theme_DeviceDefault_Dialog_Alert)
            .setTitle("Reset Counters")
            .setMessage("This will permanently delete all locally stored entries. Are you sure?")
            .setPositiveButton("Reset") { _, _ -> performReset() }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performReset() {
        // Clear all locally stored entries
        saveLocalEntries(JSONArray())
        cigEntries.clear()
        weedEntries.clear()
        thcEntries.clear()
        updateCounterUI()
        updateEntriesUI()
    }

    private fun updateEndTimeDisplay() {
        if (endTimeMillis > System.currentTimeMillis()) {
            tvEndTime.text = "Ends at ${formatTimestamp(endTimeMillis)}"
            tvEndTime.visibility = View.VISIBLE
        } else {
            tvEndTime.visibility = View.GONE
        }
    }

    // --- HELPER and PERSISTENCE functions ---

    private fun formatTime(millis: Long): String {
        val hours = TimeUnit.MILLISECONDS.toHours(millis)
        val minutes = TimeUnit.MILLISECONDS.toMinutes(millis) % 60
        val seconds = TimeUnit.MILLISECONDS.toSeconds(millis) % 60
        return String.format(Locale.getDefault(), "%02d:%02d:%02d", hours, minutes, seconds)
    }

    private fun formatTimestamp(millis: Long): String {
        val sdf = SimpleDateFormat("HH:mm", Locale.getDefault())
        return sdf.format(millis)
    }

    private fun saveEndTime(context: Context, endTime: Long) {
        val prefs = context.getSharedPreferences("TimerPrefs", Context.MODE_PRIVATE)
        prefs.edit().putLong("endTime", endTime).apply()
    }

    private fun loadEndTime(context: Context) {
        val prefs = context.getSharedPreferences("TimerPrefs", Context.MODE_PRIVATE)
        endTimeMillis = prefs.getLong("endTime", 0L)
    }

    // --- Local entry storage (source of truth) ---
    private fun loadLocalEntries(): JSONArray {
        val prefs = getSharedPreferences("SmokeLocal", Context.MODE_PRIVATE)
        val raw = prefs.getString("entries", null) ?: return JSONArray()
        return try {
            JSONArray(raw)
        } catch (e: Exception) {
            JSONArray()
        }
    }

    private fun saveLocalEntries(entries: JSONArray) {
        val prefs = getSharedPreferences("SmokeLocal", Context.MODE_PRIVATE)
        prefs.edit().putString("entries", entries.toString()).apply()
    }


    // These functions below are less critical now but can be kept for other purposes.
    private fun checkAndResetCountersIfNeeded() { /* ... unchanged ... */ }
    private fun loadLastResetTime(context: Context, key: String): Long { /* ... unchanged ... */ return 0L }
    private fun saveLastResetTime(context: Context, key: String, time: Long) { /* ... unchanged ... */ }
    private fun loadAllData() { /* This is now replaced by fetchSmokeData */ }
    private fun saveEntryData(context: Context, countKey: String, count: Int, entriesKey: String, entries: Set<String>) { /* No longer the source of truth */ }

    // --- You will need to define these constants or remove their usage ---
    private val CIG_COUNT_KEY = "cig_count"
    private val WEED_COUNT_KEY = "weed_count"
    private val CIG_ENTRIES_KEY = "cig_entries"
    private val WEED_ENTRIES_KEY = "weed_entries"
    private val LAST_APP_OPEN_KEY = "last_open"
}
