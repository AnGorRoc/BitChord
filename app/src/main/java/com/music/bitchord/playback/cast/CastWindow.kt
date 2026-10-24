package com.music.bitchord.playback.cast

/**
 * Which of the phone's queued tracks the receiver is given to hold beside the
 * one it is playing: the one before it, and [ahead] after it.
 *
 * Deliberately plain functions over plain lists. Every decision the receiver's
 * queue depends on — which of the phone's tracks a receiver could open at all,
 * which of them belong beside the playing one — is a question about a list and a
 * predicate, not about a session or a network, and can be asked and checked
 * without either. The parts that do need a session are the ones that move the
 * queue around; deciding what should be in it is here, and says so about no type
 * in particular.
 */

/**
 * The track before [current], if there is one and [isUsable] accepts it. Null at
 * the top of the list, and for a track a receiver would have to step over to
 * reach anything.
 */
internal fun <T> windowBefore(items: List<T>, current: Int, isUsable: (T) -> Boolean): T? =
    (current - 1)
        .takeIf { it >= 0 }
        ?.let { items[it] }
        ?.takeIf(isUsable)

/**
 * The [ahead] items after [current] that [isUsable] accepts.
 *
 * Skipping the ones it rejects is what keeps the window full: counting positions
 * instead spends the whole window on a few items the receiver cannot open and
 * leaves it with nothing to play next. A queue with unusable tracks between two
 * usable ones gives the same window as a queue that is entirely usable.
 */
internal fun <T> windowAhead(items: List<T>, current: Int, ahead: Int, isUsable: (T) -> Boolean): List<T> {
    if (ahead <= 0) return emptyList()
    val out = ArrayList<T>(ahead)
    var i = current + 1
    while (i < items.size && out.size < ahead) {
        val item = items[i]
        if (isUsable(item)) out.add(item)
        i++
    }
    return out
}
