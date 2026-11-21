package app.couch.alhasapa.display

import android.content.Context
import app.couch.alhasapa.data.StateRepository
import org.json.JSONObject

/** Loads the built-in table and keeps the person's saved overrides ("לשמור לאתר הזה") in the state document under `displays`. */
object DisplayStore {
    fun load(context: Context): DisplayProfiles =
        DisplayProfiles(context.assets.open("display_profiles.json").bufferedReader().use { it.readText() })

    fun overrides(state: JSONObject?): JSONObject? = state?.optJSONObject("displays")

    /** Saves [profile] as the way [key] (host + path prefix) is shown from now on. Only the fields that differ from the defaults matter. */
    fun save(repo: StateRepository, key: String, profile: DisplayProfile) {
        repo.update { s ->
            val all = s.optJSONObject("displays") ?: JSONObject().also { s.put("displays", it) }
            all.put(key.lowercase(), profile.toJson())
        }
    }

    /** Forgets a saved override, so the built-in profile (or the default) applies again. */
    fun reset(repo: StateRepository, key: String) {
        repo.update { s -> s.optJSONObject("displays")?.remove(key.lowercase()) }
    }
}
