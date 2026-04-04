package com.codeextractor.app.domain.avatar

/**
 * ARKit 51 blendshape indices matching the GLB model.
 * Model has: head(51), teeth(5), eyeL(4), eyeR(4).
 * NO TongueOut — model doesn't have it.
 */
object ARKit {
    const val EyeBlinkLeft = 0;    const val EyeLookDownLeft = 1
    const val EyeLookInLeft = 2;   const val EyeLookOutLeft = 3
    const val EyeLookUpLeft = 4;   const val EyeSquintLeft = 5;   const val EyeWideLeft = 6
    const val EyeBlinkRight = 7;   const val EyeLookDownRight = 8
    const val EyeLookInRight = 9;  const val EyeLookOutRight = 10
    const val EyeLookUpRight = 11; const val EyeSquintRight = 12; const val EyeWideRight = 13
    const val JawForward = 14;     const val JawLeft = 15;        const val JawRight = 16
    const val JawOpen = 17
    const val MouthClose = 18;     const val MouthFunnel = 19;    const val MouthPucker = 20
    const val MouthRight = 21;     const val MouthLeft = 22
    const val MouthSmileLeft = 23; const val MouthSmileRight = 24
    const val MouthFrownLeft = 25; const val MouthFrownRight = 26
    const val MouthDimpleLeft = 27;    const val MouthDimpleRight = 28
    const val MouthStretchLeft = 29;   const val MouthStretchRight = 30
    const val MouthRollLower = 31;     const val MouthRollUpper = 32
    const val MouthShrugLower = 33;    const val MouthShrugUpper = 34
    const val MouthPressLeft = 35;     const val MouthPressRight = 36
    const val MouthLowerDownLeft = 37; const val MouthLowerDownRight = 38
    const val MouthUpperUpLeft = 39;   const val MouthUpperUpRight = 40
    const val BrowDownLeft = 41;   const val BrowDownRight = 42;  const val BrowInnerUp = 43
    const val BrowOuterUpLeft = 44;    const val BrowOuterUpRight = 45
    const val CheekPuff = 46;      const val CheekSquintLeft = 47; const val CheekSquintRight = 48
    const val NoseSneerLeft = 49;  const val NoseSneerRight = 50

    const val COUNT = 51
}