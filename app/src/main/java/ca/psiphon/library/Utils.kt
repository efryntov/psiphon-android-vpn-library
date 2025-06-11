package ca.psiphon.library

import android.content.Context
import android.content.res.Resources
import android.util.Log
import org.json.JSONException
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.nio.charset.Charset
import java.util.*
import androidx.core.content.edit

object Utils {
    private val TAG = Utils::class.java.simpleName
    private const val SERVICE_RUNNING_FLAG_FILE = "service_running_flag_file"

    @Throws(IOException::class, Resources.NotFoundException::class)
    fun readRawResourceFileAsString(context: Context, resourceId: Int): String {
        return context.resources.openRawResource(resourceId).use { inputStream ->
            inputStream.bufferedReader(Charset.forName("UTF-8")).use { reader ->
                reader.readText()
            }
        }
    }


    fun egressRegionsFromEmbeddedServers(embeddedServersString: String): List<String> {
        // Split the server entry string into lines
        val lines = embeddedServersString.split("\n")
        // Use the TreeSet to automatically sort and remove duplicates
        val egressRegionsSet = TreeSet<String>(String.CASE_INSENSITIVE_ORDER)
        var lineNum = 0

        for (line in lines) {
            lineNum++
            // trim the line and check if it is empty, skip if so
            val trimmedLine = line.trim()
            if (trimmedLine.isEmpty()) {
                continue
            }

            val decoded = hexDecode(trimmedLine)
                ?: throw IllegalArgumentException("Failed to hex decode line: $lineNum")

            // Skip past legacy format (4 space delimited fields) to the JSON config
            val parts = decoded.split(" ", limit = 5)
            val json = if (parts.size == 5) parts[4] else null
                ?: throw IllegalArgumentException("Failed to extract JSON from line: $lineNum")

            try {
                val jsonObject = JSONObject(json)
                egressRegionsSet.add(jsonObject.getString("region"))
            } catch (e: JSONException) {
                throw IllegalArgumentException("Failed to parse JSON from line: $lineNum", e)
            }
        }

        return ArrayList(egressRegionsSet)
    }

    private fun hexDecode(s: String): String? {
        if (s.length % 2 != 0) return null

        return buildString {
            for (i in s.indices step 2) {
                val decimal = s.substring(i, i + 2).toInt(16)
                append(decimal.toChar())
            }
        }
    }

    @Synchronized
    fun setServiceRunningFlag(context: Context, isRunning: Boolean) {
        val file = File(context.filesDir, SERVICE_RUNNING_FLAG_FILE)

        if (isRunning) {
            try {
                if (!file.exists()) {
                    val created = file.createNewFile()
                    if (!created) {
                        throw IOException("Failed to create service running flag file.")
                    }
                    Log.d(TAG, "Service running flag file created successfully.")
                } else {
                    Log.d(TAG, "Service running flag file already exists.")
                }
            } catch (e: IOException) {
                Log.e(TAG, "Failed to create service running flag file: $e")
            }
        } else {
            // Delete the file to indicate the service has stopped
            if (file.exists()) {
                if (file.delete()) {
                    Log.d(TAG, "Service running flag file deleted successfully.")
                } else {
                    Log.e(TAG, "Failed to delete service running flag file.")
                }
            }
        }
    }

    @Synchronized
    fun getServiceRunningFlag(context: Context): Boolean {
        val file = File(context.filesDir, SERVICE_RUNNING_FLAG_FILE)
        val exists = file.exists()
        if (exists) {
            Log.d(TAG, "Service running flag file detected.")
        } else {
            Log.d(TAG, "Service running flag file not found.")
        }
        return exists
    }

    fun dataRootDirectory(context: Context): File {
        val rootDirectory = context.applicationContext.filesDir
        val dataRootDirectory = File(rootDirectory, Constants.DATA_ROOT_DIRECTORY_NAME)
        check(!(!dataRootDirectory.exists() && !dataRootDirectory.mkdirs())) { "Failed to create data root directory" }
        return dataRootDirectory
    }
}