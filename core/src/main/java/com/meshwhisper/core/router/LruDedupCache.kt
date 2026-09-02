package com.meshwhisper.core.router

/**
 * Pure Kotlin thread-safe Least Recently Used (LRU) cache for packet deduplication.
 * Replaces android.util.LruCache for multiplatform JVM compatibility.
 */
class LruDedupCache<K, V>(private val maxEntries: Int = 4000) {

    private val map = object : LinkedHashMap<K, V>(maxEntries, 0.75f, true) {
        override fun removeEldestEntry(eldest: MutableMap.MutableEntry<K, V>?): Boolean {
            return size > maxEntries
        }
    }

    @Synchronized
    fun put(key: K, value: V): V? {
        return map.put(key, value)
    }

    @Synchronized
    fun get(key: K): V? {
        return map[key]
    }

    @Synchronized
    fun containsKey(key: K): Boolean {
        return map.containsKey(key)
    }

    @Synchronized
    fun remove(key: K): V? {
        return map.remove(key)
    }

    @Synchronized
    fun clear() {
        map.clear()
    }

    @Synchronized
    fun size(): Int {
        return map.size
    }
}
