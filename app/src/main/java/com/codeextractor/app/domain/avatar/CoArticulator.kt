package com.codeextractor.app.domain.avatar

/**
 * Ко-артикуляция: сглаживает переходы между виземами.
 *
 * Без ко-артикуляции рот "прыгает" между позициями.
 * С ней — текущая форма рта зависит от предыдущей (carry-over)
 * и слегка антиципирует следующую.
 *
 * Реализация: кольцевой буфер последних N кадров визем,
 * взвешенное смешивание с акцентом на текущий.
 */
class CoArticulator(
    private val historySize: Int = 4,    // кадров в буфере
    private val carryWeight: Float = 0.2f, // влияние предыдущей позы
    private val leadWeight: Float = 0.05f  // влияние "инерции" вперёд
) {
    private val history = Array(historySize) { FloatArray(ARKit.COUNT) }
    private var writeIdx = 0
    private var filled = 0
    private val output = FloatArray(ARKit.COUNT)

    /**
     * Подать новые target-веса от VisemeMapper.
     * Возвращает ко-артикулированные веса.
     */
    fun process(rawWeights: FloatArray): FloatArray {
        // Записать текущий кадр в историю
        System.arraycopy(rawWeights, 0, history[writeIdx % historySize], 0, ARKit.COUNT)
        writeIdx++
        filled = minOf(filled + 1, historySize)

        if (filled < 2) {
            // Недостаточно истории — отдать как есть
            System.arraycopy(rawWeights, 0, output, 0, ARKit.COUNT)
            return output
        }

        // Предыдущий кадр
        val prevIdx = ((writeIdx - 2) % historySize + historySize) % historySize
        val prev = history[prevIdx]

        // Два кадра назад (для инерции)
        val prev2Idx = ((writeIdx - 3) % historySize + historySize) % historySize
        val prev2 = if (filled >= 3) history[prev2Idx] else prev

        // Инерция (куда рот "хотел бы" двигаться дальше по тренду)
        // trend = prev + (prev - prev2) = 2*prev - prev2
        for (i in 0 until ARKit.COUNT) {
            val trend = (2f * prev[i] - prev2[i]).coerceIn(0f, 1f)
            output[i] = rawWeights[i] * (1f - carryWeight - leadWeight) +
                    prev[i] * carryWeight +
                    trend * leadWeight
            output[i] = output[i].coerceIn(0f, 1f)
        }

        return output
    }

    fun reset() {
        history.forEach { it.fill(0f) }
        writeIdx = 0
        filled = 0
        output.fill(0f)
    }
}