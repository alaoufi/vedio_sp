package com.myvideolibrary.app.ui.youtube

import android.content.Context

/**
 * A tiny, private, on-device list of "subscribed" channels. Purely local — no
 * account, no network — so the user can bookmark channels they browse and come
 * back to them. Backed by SharedPreferences (a URL set plus a name lookup).
 */
object LocalSubscriptions {

    private const val PREFS = "local_subscriptions"
    private const val KEY_URLS = "urls"
    private const val KEY_NAME_PREFIX = "name_"

    private fun prefs(context: Context) =
        context.getSharedPreferences(PREFS, Context.MODE_PRIVATE)

    fun isSubscribed(context: Context, channelUrl: String): Boolean =
        prefs(context).getStringSet(KEY_URLS, emptySet())!!.contains(channelUrl)

    /** Toggles subscription; returns the new subscribed state. */
    fun toggle(context: Context, channelUrl: String, name: String?): Boolean {
        val p = prefs(context)
        val set = HashSet(p.getStringSet(KEY_URLS, emptySet())!!)
        val nowSubscribed: Boolean
        if (set.contains(channelUrl)) {
            set.remove(channelUrl)
            nowSubscribed = false
        } else {
            set.add(channelUrl)
            nowSubscribed = true
        }
        p.edit()
            .putStringSet(KEY_URLS, set)
            .apply {
                if (nowSubscribed && !name.isNullOrBlank()) {
                    putString(KEY_NAME_PREFIX + channelUrl, name)
                } else if (!nowSubscribed) {
                    remove(KEY_NAME_PREFIX + channelUrl)
                }
            }
            .apply()
        return nowSubscribed
    }
}
