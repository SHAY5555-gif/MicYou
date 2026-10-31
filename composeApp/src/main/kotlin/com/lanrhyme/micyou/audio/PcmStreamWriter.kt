package com.lanrhyme.micyou.audio

import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive

/** Preserve sample order across short writes; only an explicit stop may discard a tail. */
internal suspend fun writePcmFully(
    samples: ShortArray,
    count: Int,
    keepWriting: () -> Boolean = { true },
    write: (ShortArray, Int, Int) -> Int
): Int {
    require(count in 0..samples.size)
    var offset = 0
    while (offset < count && keepWriting()) {
        currentCoroutineContext().ensureActive()
        val remaining = count - offset
        val written = write(samples, offset, remaining)
        check(written in 0..remaining) { "PCM output write failed: $written" }
        if (written == 0) delay(1) else offset += written
    }
    return offset
}
