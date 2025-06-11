package ca.psiphon.library

import android.content.Context
import android.content.Intent
import android.os.Bundle

data class PsiphonServiceParameters(
    val egressRegionParameter: String
) {
    companion object {
        // Keys and preferences file name
        const val PREFS_NAME = "PsiphonServiceParamsPrefs"

        // Bundle keys
        const val EGRESS_REGION_KEY = "egressRegion"

        // Parse method for Bundle
        fun fromBundle(bundle: Bundle): PsiphonServiceParameters? {
            val egressRegion = bundle.getString(EGRESS_REGION_KEY) ?: return null

            return if (validate(egressRegion)) {
                PsiphonServiceParameters(egressRegion)
            } else {
                null
            }
        }

        // Parse method for Intent
        fun fromIntent(intent: Intent): PsiphonServiceParameters? {
            val bundle = intent.extras ?: return null
            return fromBundle(bundle)
        }

        // Helper to load parameters from preferences
        fun loadStored(context: Context): PsiphonServiceParameters? {
            val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
            val egressRegion = preferences.getString(EGRESS_REGION_KEY, null)
            return if (validate(egressRegion)) {
                PsiphonServiceParameters(egressRegion!!)
            } else {
                null
            }
        }

        // Helper to validate parameters
        private fun validate(egressRegion: String?): Boolean {
            return egressRegion != null
        }
    }

    // Convert instance to Bundle
    fun toBundle(): Bundle {
        return Bundle().apply {
            putString(EGRESS_REGION_KEY, egressRegionParameter)
        }
    }

    // Store the parameters in SharedPreferences and return true if values changed
    fun store(context: Context): Boolean {
        val preferences = context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE)
        val storedEgressRegion = preferences.getString(EGRESS_REGION_KEY, null)

        return if (storedEgressRegion != this.egressRegionParameter) {
            preferences.edit().apply {
                putString(EGRESS_REGION_KEY, this@PsiphonServiceParameters.egressRegionParameter)
                apply()
            }
            true
        } else {
            false
        }
    }

    // Helper to put parameters into an intent
    fun putIntoIntent(intent: Intent) {
        intent.putExtras(toBundle())
    }
}