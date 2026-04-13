package com.codeextractor.app.domain.avatar

/**
 * ARKit 51 blendshape indices — matching the GLB model topology.
 *
 * Model mesh breakdown:
 *   head   → 51 morph targets  (indices 0..50)
 *   teeth  →  5 morph targets  (JawForward, JawLeft, JawRight, JawOpen, MouthClose)
 *   eyeL   →  4 morph targets  (LookDown, LookIn, LookOut, LookUp)
 *   eyeR   →  4 morph targets  (LookDown, LookIn, LookOut, LookUp)
 *
 * IMPORTANT: TongueOut отсутствует в модели — индекс не резервируется.
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

    val GROUP_EYELIDS = intArrayOf(
        EyeBlinkLeft, EyeBlinkRight,
        EyeSquintLeft, EyeSquintRight,
        EyeWideLeft, EyeWideRight,
    )

    val GROUP_PUPILS = intArrayOf(
        EyeLookDownLeft,  EyeLookInLeft,  EyeLookOutLeft,  EyeLookUpLeft,
        EyeLookDownRight, EyeLookInRight, EyeLookOutRight, EyeLookUpRight,
    )

    val GROUP_JAW = intArrayOf(
        JawOpen, JawForward, JawLeft, JawRight,
    )

    val GROUP_LIP_SEAL = intArrayOf(
        MouthClose, MouthPressLeft, MouthPressRight,
    )

    val GROUP_LIP_ROUND = intArrayOf(
        MouthPucker, MouthFunnel,
    )

    val GROUP_LIP_STRETCH = intArrayOf(
        MouthStretchLeft, MouthStretchRight,
        MouthSmileLeft,   MouthSmileRight,
    )

    val GROUP_LIP_VERTICAL = intArrayOf(
        MouthLowerDownLeft, MouthLowerDownRight,
        MouthUpperUpLeft,   MouthUpperUpRight,
        MouthShrugLower,    MouthShrugUpper,
        MouthRollLower,     MouthRollUpper,
    )

    val GROUP_BROWS = intArrayOf(
        BrowDownLeft, BrowDownRight, BrowInnerUp,
        BrowOuterUpLeft, BrowOuterUpRight,
    )

    val GROUP_CHEEKS_NOSE = intArrayOf(
        CheekPuff, CheekSquintLeft, CheekSquintRight,
        NoseSneerLeft, NoseSneerRight,
    )

    // ─── MESH TYPE IDENTIFICATION ────────────────────────────────────────

    enum class MeshType { HEAD, TEETH, EYE_LEFT, EYE_RIGHT, OTHER }

    fun meshTypeByMorphCount(count: Int): MeshType = when (count) {
        51   -> MeshType.HEAD
        5    -> MeshType.TEETH
        4    -> MeshType.EYE_LEFT
        else -> MeshType.OTHER
    }

    val TEETH_SOURCE_INDICES = intArrayOf(JawForward, JawLeft, JawRight, JawOpen, MouthClose)

    val EYE_SOURCE_INDICES = intArrayOf(
        EyeLookDownLeft, EyeLookInLeft, EyeLookOutLeft, EyeLookUpLeft,
    )

    const val EYE_RIGHT_OFFSET = 7
}
