package ai.nisu.assistant

import java.util.concurrent.ConcurrentHashMap

/**
 * v3.70: the audio session ids of every microphone recorder that NISU AI itself opens (live assistant, sleep listener, voice check).
 * The service watches all active recordings on the phone; a recording whose session id is NOT in this list belongs to another app
 * (WhatsApp / Messenger voice message, YouTube voice search, ChatGPT voice...), and then NISU AI gives the microphone away.
 * Session ids are unique numbers, so an old id can never match another app's recording; the list is tiny and is never cleared.
 */
object MicOwn {
    private val ids: MutableSet<Int> = ConcurrentHashMap.newKeySet()
    fun add(id: Int) { if (id > 0) ids.add(id) }
    fun has(id: Int): Boolean = id in ids
}
