package network.retalert.app.platform

import android.content.Context
import dagger.hilt.android.qualifiers.ApplicationContext
import javax.inject.Inject
import javax.inject.Singleton

/**
 * What this phone answered to each received alert (alertId -> "On my way",
 * "Acknowledged"…), so the Inbox can show "You replied". Kept out of the Room
 * database on purpose: a schema change there currently means a destructive
 * migration.
 */
@Singleton
class MyReplies @Inject constructor(@ApplicationContext ctx: Context) {
    private val prefs = ctx.getSharedPreferences("my_replies", Context.MODE_PRIVATE)

    fun set(alertId: String, text: String) {
        if (alertId.isNotEmpty()) prefs.edit().putString(alertId, text).apply()
    }

    fun get(alertId: String): String? = prefs.getString(alertId, null)

    /** Drop answers to alerts no longer in the Inbox. */
    fun retainOnly(alertIds: Set<String>) {
        val stale = prefs.all.keys - alertIds
        if (stale.isNotEmpty()) prefs.edit().apply { stale.forEach(::remove) }.apply()
    }

    companion object { const val ACKNOWLEDGED = "Acknowledged" }
}
