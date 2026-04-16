package com.learnde.app.domain.avatar.linguistics

import com.learnde.app.domain.avatar.LinguisticState
import com.learnde.app.domain.avatar.PhonemeData
import com.learnde.app.domain.avatar.VisemeGroup
import com.learnde.app.domain.avatar.toVisemeGroup
import java.util.concurrent.atomic.AtomicInteger
import kotlin.math.min

/**
 * PhoneticRibbon — Кольцевой SoA-буфер фонем.
 *
 * Structure of Arrays (SoA): три параллельных массива вместо массива объектов.
 * Cache-friendly при sequential scan (look-ahead), zero-alloc в hot path.
 *
 * Writer: WebSocket IO thread (feedTextChunk)
 * Reader: Animator coroutine (peek/advance)
 * Thread-safety: AtomicInteger для позиций.
 */
class PhoneticRibbon(private val cap: Int = 4096) {

    // ── SoA storage ───────────────────────────────────────────────────────
    private val gates     = ByteArray(cap)
    private val profIdx   = ShortArray(cap)
    private val puncts    = ByteArray(cap)
    private val durations = ShortArray(cap)   // estimatedMs per entry

    // ── Profile lookup table ──────────────────────────────────────────────
    private val profileTable = ArrayList<PhonemeData.PhonemeProfile>(64)
    private val profileCache = HashMap<String, Short>(128)

    // ── Atomic positions ──────────────────────────────────────────────────
    private val writePos = AtomicInteger(0)
    private val readPos  = AtomicInteger(0)

    // ── Punctuation propagation ───────────────────────────────────────────
    @Volatile private var lastPunct: Byte = 0

    // ── Language ──────────────────────────────────────────────────────────
    @Volatile var detectedLang: String = "ru"; private set

    private val analyzer = TextPhonemeAnalyzer()

    init {
        profileTable.add(PhonemeData.NEUTRAL)
        profileCache["_neutral"] = 0
    }

    // ══════════════════════════════════════════════════════════════════════
    //  WRITE
    // ══════════════════════════════════════════════════════════════════════

    fun feedTextChunk(text: String) {
        if (text.isBlank()) return
        detectedLang = detectLang(text)
        val tokens = analyzer.analyze(text, detectedLang)

        val wp = writePos.get()
        var pos = wp

        for (token in tokens) {
            when (token.sourceChar) {
                '?' -> lastPunct = 1
                '!' -> lastPunct = 2
                '.' -> lastPunct = 0
                ',', ';', ':', '—', '-' -> lastPunct = 3
            }

            val gate = token.profile.visemeClass.toVisemeGroup()

            // Collapse consecutive silences
            if (gate == VisemeGroup.SILENCE && pos != wp) {
                val prev = (pos - 1 + cap) % cap
                if (gates[prev].toInt() == VisemeGroup.SILENCE.ordinal) continue
            }

            gates[pos]     = gate.ordinal.toByte()
            profIdx[pos]   = getOrCache(token.phonemeKey, token.profile)
            puncts[pos]    = lastPunct
            durations[pos] = token.estimatedMs.toShort()

            pos = (pos + 1) % cap
            if (pos == readPos.get()) break
        }
        writePos.set(pos)
    }

    // ══════════════════════════════════════════════════════════════════════
    //  READ
    // ══════════════════════════════════════════════════════════════════════

    fun peekGate(offset: Int = 0): VisemeGroup {
        if (offset >= readable()) return VisemeGroup.SILENCE
        val idx = (readPos.get() + offset) % cap
        return VisemeGroup.entries[gates[idx].toInt()]
    }

    fun peekProfile(offset: Int = 0): PhonemeData.PhonemeProfile {
        if (offset >= readable()) return PhonemeData.NEUTRAL
        val idx = (readPos.get() + offset) % cap
        val pi = profIdx[idx].toInt()
        return if (pi in profileTable.indices) profileTable[pi] else PhonemeData.NEUTRAL
    }

    fun peekDurationMs(offset: Int = 0): Int {
        if (offset >= readable()) return 80
        val idx = (readPos.get() + offset) % cap
        return durations[idx].toInt()
    }

    /**
     * Look-ahead пунктуация: ищет ближайший знак в следующих N символах.
     * Приоритет: ! > ? > , > neutral
     */
    fun peekPunctuation(lookAhead: Int = 12): LinguisticState.PunctuationType {
        val r = readable()
        val scan = min(lookAhead, r)
        var best = 0
        val rp = readPos.get()
        for (i in 0 until scan) {
            val p = puncts[(rp + i) % cap].toInt()
            if (p > best) best = p
        }
        return when (best) {
            1 -> LinguisticState.PunctuationType.QUESTION
            2 -> LinguisticState.PunctuationType.EXCLAMATION
            3 -> LinguisticState.PunctuationType.PAUSE
            else -> LinguisticState.PunctuationType.NEUTRAL
        }
    }

    fun advance() {
        val rp = readPos.get()
        if (rp != writePos.get()) readPos.set((rp + 1) % cap)
    }

    val hasReadable: Boolean get() = readPos.get() != writePos.get()
    fun readable(): Int {
        val r = readPos.get(); val w = writePos.get()
        return if (w >= r) w - r else cap - r + w
    }

    fun flush() {
        writePos.set(0); readPos.set(0); lastPunct = 0
    }

    // ══════════════════════════════════════════════════════════════════════

    private fun getOrCache(key: String, profile: PhonemeData.PhonemeProfile): Short {
        profileCache[key]?.let { return it }
        val idx = profileTable.size.toShort()
        profileTable.add(profile)
        profileCache[key] = idx
        return idx
    }

    private fun detectLang(text: String): String {
        var cy = 0; var la = 0
        for (c in text) {
            when { c in '\u0400'..'\u04FF' -> cy++; c in 'A'..'z' -> la++ }
            if (cy + la >= 8) break
        }
        return if (cy > la) "ru" else "de"
    }
}