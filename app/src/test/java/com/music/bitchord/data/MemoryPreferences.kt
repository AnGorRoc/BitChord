package com.music.bitchord.data

import android.content.SharedPreferences

/**
 * [SharedPreferences] held in a map, for tests of what a cache writes and what
 * a restart reads back. Edits apply when [SharedPreferences.Editor.apply] is
 * called, as they do on a device.
 */
class MemoryPreferences : SharedPreferences {
    val data = mutableMapOf<String, Any?>()

    override fun getAll(): Map<String, *> = HashMap(data)
    override fun getString(key: String?, defValue: String?): String? = data[key] as? String ?: defValue
    @Suppress("UNCHECKED_CAST")
    override fun getStringSet(key: String?, defValues: Set<String>?): Set<String>? = data[key] as? Set<String> ?: defValues
    override fun getInt(key: String?, defValue: Int): Int = (data[key] as? Number)?.toInt() ?: defValue
    override fun getLong(key: String?, defValue: Long): Long = (data[key] as? Number)?.toLong() ?: defValue
    override fun getFloat(key: String?, defValue: Float): Float = (data[key] as? Number)?.toFloat() ?: defValue
    override fun getBoolean(key: String?, defValue: Boolean): Boolean = data[key] as? Boolean ?: defValue
    override fun contains(key: String?): Boolean = data.containsKey(key)
    override fun edit(): SharedPreferences.Editor = Editor(this)
    override fun registerOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}
    override fun unregisterOnSharedPreferenceChangeListener(listener: SharedPreferences.OnSharedPreferenceChangeListener?) {}

    private class Editor(private val prefs: MemoryPreferences) : SharedPreferences.Editor {
        private val ops = mutableListOf<(MutableMap<String, Any?>) -> Unit>()

        private fun put(key: String?, value: Any?): SharedPreferences.Editor {
            if (key != null) ops += { it[key] = value }
            return this
        }

        override fun putString(key: String?, value: String?) = put(key, value)
        override fun putStringSet(key: String?, values: Set<String>?) = put(key, values)
        override fun putInt(key: String?, value: Int) = put(key, value)
        override fun putLong(key: String?, value: Long) = put(key, value)
        override fun putFloat(key: String?, value: Float) = put(key, value)
        override fun putBoolean(key: String?, value: Boolean) = put(key, value)
        override fun remove(key: String?): SharedPreferences.Editor {
            if (key != null) ops += { it.remove(key) }
            return this
        }
        override fun clear(): SharedPreferences.Editor {
            ops += { it.clear() }
            return this
        }
        override fun commit(): Boolean { apply(); return true }
        override fun apply() = ops.forEach { it(prefs.data) }
    }
}
