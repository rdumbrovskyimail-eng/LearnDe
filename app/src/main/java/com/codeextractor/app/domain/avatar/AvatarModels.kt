ПАТЧ 2 — AvatarModels.kt (StateFlow перестанет глотать обновления головы)
Заменить AvatarRenderState:
/** Full render state per frame */
data class AvatarRenderState(
    val morphWeights: FloatArray = FloatArray(ARKit.COUNT),
    val headPitch: Float = 0f,
    val headYaw: Float = 0f,
    val headRoll: Float = 0f,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is AvatarRenderState) return false
        return morphWeights.contentEquals(other.morphWeights)
                && headPitch == other.headPitch
                && headYaw == other.headYaw
                && headRoll == other.headRoll
    }
    override fun hashCode(): Int {
        var result = morphWeights.contentHashCode()
        result = 31 * result + headPitch.hashCode()
        result = 31 * result + headYaw.hashCode()
        result = 31 * result + headRoll.hashCode()
        return result
    }
}