package com.walktracker.app.util

import android.content.Context
import android.os.Environment
import java.io.File
import java.io.FileWriter
import java.io.IOException
import java.text.SimpleDateFormat
import java.util.*

object FileLogger {

    private const val LOG_FILE_NAME = "walk_tracker_log.txt"
    private var isInitialized = false
    private lateinit var logFile: File
    private const val TAG = "FileLogger"

    fun init(context: Context) {
        if (isInitialized) return

        val logDirectory = File(context.getExternalFilesDir(null), "logs")
        if (!logDirectory.exists()) {
            logDirectory.mkdirs()
        }

        logFile = File(logDirectory, LOG_FILE_NAME)
        if (!logFile.exists()) {
            try {
                logFile.createNewFile()
            } catch (e: IOException) {
                e.printStackTrace()
                return
            }
        }
        isInitialized = true
        log(TAG, "FileLogger initialized.")
    }

    fun log(tag: String, message: String) {
        if (!isInitialized) return

        val timestamp = SimpleDateFormat("yyyy-MM-dd HH:mm:ss.SSS", Locale.getDefault()).format(Date())
        val logMessage = "$timestamp - $tag: $message\n"

        try {
            val fileWriter = FileWriter(logFile, true) // true for append mode
            fileWriter.append(logMessage)
            fileWriter.flush()
            fileWriter.close()
        } catch (e: IOException) {
            e.printStackTrace()
        }
    }
}
