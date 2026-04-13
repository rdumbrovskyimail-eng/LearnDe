package com.codeextractor.app.domain.avatar

/**
 * ARKit 52 blendshape indices — matching the GLB model topology.
 *
 * Model mesh breakdown:
 *   head   → 51 morph targets  (indices 0..50)
 *   teeth  →  5 morph targets  (JawForward, JawLeft, JawRight, JawOpen, MouthClose)
 *   eyeL   →  4 morph targets  (LookDown, LookIn, LookOut, LookUp)
 *   eyeR   →  4 morph targets  (LookDown, LookIn, LookOut, LookUp)
 *
 * IMPORTANT: TongueOut отсутствует в модели — индекс не резервируется.
 *
 * Почему object, а не enum?
 *   • Прямой доступ по Int без boxing (FloatArray[ARKit.JawOpen] vs FloatArray[idx.ordinal])
 *   • System.arraycopy / SIMD-friendly доступ по числовым константам
 *   • Zero-overhead — константы инлайнятся компилятором
 *
 * Группировка:
 *   EYES        →  0..13
 *   JAW         → 14..17
 *   MOUTH CORE  → 18..22
 *   MOUTH SMILE/FROWN → 23..28
 *   MOUTH SHAPE → 29..40
 *   BROWS       → 41..45
 *   CHEEKS      → 46..48
 *   NOSE        → 49..50
 */
object ARKit {

    // ─── EYES ────────────────────────────────────────────────────────────
    const val EyeBlinkLeft      = 0
    const val EyeLookDownLeft   = 1
    const val EyeLookInLeft     = 2
    const val EyeLookOutLeft    = 3
    const val EyeLookUpLeft     = 4
    const val EyeSquintLeft     = 5
    const val EyeWideLeft       = 6

    const val EyeBlinkRight     = 7
    const val EyeLookDownRight  = 8
    const val EyeLookInRight    = 9
    const val EyeLookOutRight   = 10
    const val EyeLookUpRight    = 11
    const val EyeSquintRight    = 12
    const val EyeWideRight      = 13

    // ─── JAW ─────────────────────────────────────────────────────────────
    const val JawForward        = 14
    const val JawLeft           = 15
    const val JawRight          = 16
    const val JawOpen           = 17

    // ─── MOUTH CORE ──────────────────────────────────────────────────────
    const val MouthClose        = 18
    const val MouthFunnel       = 19
    const val MouthPucker       = 20
    const val MouthRight        = 21
    const val MouthLeft         = 22

    // ─── MOUTH SMILE / FROWN / DIMPLE ────────────────────────────────────
    const val MouthSmileLeft    = 23
    const val MouthSmileRight   = 24
    const val MouthFrownLeft    = 25
    const val MouthFrownRight   = 26
    const val MouthDimpleLeft   = 27
    const val MouthDimpleRight  = 28

    // ─── MOUTH SHAPE ─────────────────────────────────────────────────────
    const val MouthStretchLeft  = 29
    const val MouthStretchRight = 30
    const val MouthRollLower    = 31
    const val MouthRollUpper    = 32
    const val MouthShrugLower   = 33
    const val MouthShrugUpper   = 34
    const val MouthPressLeft    = 35
    const val MouthPressRight   = 36

    // ─── MOUTH VERTICAL ──────────────────────────────────────────────────
    const val MouthLowerDownLeft  = 37
    const val MouthLowerDownRight = 38
    const val MouthUpperUpLeft    = 39
    const val MouthUpperUpRight   = 40

    // ─── BROWS ───────────────────────────────────────────────────────────
    const val BrowDownLeft      = 41
    const val BrowDownRight     = 42
    const val BrowInnerUp       = 43
    const val BrowOuterUpLeft   = 44
    const val BrowOuterUpRight  = 45

    // ─── CHEEKS ──────────────────────────────────────────────────────────
    const val CheekPuff         = 46
    const val CheekSquintLeft   = 47
    const val CheekSquintRight  = 48

    // ─── NOSE ────────────────────────────────────────────────────────────
    const val NoseSneerLeft     = 49
    const val NoseSneerRight    = 50

    // ─── META ─────────────────────────────────────────────────────────────
    const val COUNT = 51

    // ─── ГРУППЫ (для FacePhysicsEngine и CoArticulator) ──────────────────

    /** Веки — критически демпфированы, самые быстрые мышцы лица */
    val GROUP_EYELIDS = intArrayOf(
        EyeBlinkLeft, EyeBlinkRight,
        EyeSquintLeft, EyeSquintRight,
        EyeWideLeft, EyeWideRight,
    )

    /** Движения зрачков — быстрее век */
    val GROUP_PUPILS = intArrayOf(
        EyeLookDownLeft,  EyeLookInLeft,  EyeLookOutLeft,  EyeLookUpLeft,
        EyeLookDownRight, EyeLookInRight, EyeLookOutRight, EyeLookUpRight,
    )

    /** Челюсть — тяжёлая кость, overdamped */
    val GROUP_JAW = intArrayOf(
        JawOpen, JawForward, JawLeft, JawRight,
    )

    /** Губное смыкание — билабиальные П/Б/М, near-critical */
    val GROUP_LIP_SEAL = intArrayOf(
        MouthClose, MouthPressLeft, MouthPressRight,
    )

    /** Округление губ — О/У/Ш */
    val GROUP_LIP_ROUND = intArrayOf(
        MouthPucker, MouthFunnel,
    )

    /** Растяжение губ — Е/И/улыбка */
    val GROUP_LIP_STRETCH = intArrayOf(
        MouthStretchLeft, MouthStretchRight,
        MouthSmileLeft,   MouthSmileRight,
    )

    /** Вертикальные движения губ */
    val GROUP_LIP_VERTICAL = intArrayOf(
        MouthLowerDownLeft, MouthLowerDownRight,
        MouthUpperUpLeft,   MouthUpperUpRight,
        MouthShrugLower,    MouthShrugUpper,
        MouthRollLower,     MouthRollUpper,
    )

    /** Брови — медленные, эмоциональные */
    val GROUP_BROWS = intArrayOf(
        BrowDownLeft, BrowDownRight, BrowInnerUp,
        BrowOuterUpLeft, BrowOuterUpRight,
    )

    /** Щёки и нос — мягкая ткань */
    val GROUP_CHEEKS_NOSE = intArrayOf(
        CheekPuff, CheekSquintLeft, CheekSquintRight,
        NoseSneerLeft, NoseSneerRight,
    )

    // ─── МАППИНГ: количество морф-таргетов → mesh (для идентификации) ────

    /**
     * Вместо хрупкого "when (morphCount) { 51 -> head }" используем
     * явную таблицу: имя меша (из glTF asset) → тип.
     *
     * Заполняется один раз при загрузке модели через GlbMeshIdentifier.
     * Если имена мешей изменятся — менять только здесь.
     */
    enum class MeshType { HEAD, TEETH, EYE_LEFT, EYE_RIGHT, OTHER }

    /**
     * Резервные сигнатуры по количеству морф-таргетов.
     * Используются ТОЛЬКО если имя меша не найдено в glTF.
     * (fallback для совместимости с текущей моделью)
     */
    fun meshTypeByMorphCount(count: Int): MeshType = when (count) {
        51   -> MeshType.HEAD
        5    -> MeshType.TEETH
        4    -> MeshType.EYE_LEFT   // первые 4 → LEFT, последующие → RIGHT
        else -> MeshType.OTHER
    }

    // ─── TEETH MORPH MAPPING ──────────────────────────────────────────────
    // Зубы имеют 5 морф-таргетов, совпадающих с подмножеством ARKit-индексов головы.
    // teethWeights[i] = headWeights[TEETH_SOURCE_INDICES[i]]

    val TEETH_SOURCE_INDICES = intArrayOf(
        JawForward,  // 0
        JawLeft,     // 1
        JawRight,    // 2
        JawOpen,     // 3
        MouthClose,  // 4
    )

    // ─── EYE MORPH MAPPING ───────────────────────────────────────────────
    // Каждый глаз имеет 4 морф-таргета.
    // eyeWeights[i] = headWeights[EYE_SOURCE_INDICES[i]]

    val EYE_SOURCE_INDICES = intArrayOf(
        EyeLookDownLeft,  // 0  (для левого глаза)
        EyeLookInLeft,    // 1
        EyeLookOutLeft,   // 2
        EyeLookUpLeft,    // 3
    )

    // Смещение для правого глаза: EyeLookDownRight = EyeLookDownLeft + 7
    const val EYE_RIGHT_OFFSET = 7
}