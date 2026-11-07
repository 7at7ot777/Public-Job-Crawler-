package com.jobcrawler.app

import java.util.concurrent.ConcurrentHashMap

/** The state table as a thread-safe map, for tests that need a StateStore. */
class MapState : StateStore {
    val values = ConcurrentHashMap<String, String>()
    override fun getState(key: String) = values[key]
    override fun setState(key: String, value: String) {
        values[key] = value
    }
    override fun statesWithPrefix(prefix: String) = values.filterKeys { it.startsWith(prefix) }.toSortedMap()
}
