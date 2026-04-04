// ═══════════════════════════════════════════════════════════════════════
// FILE 9: di/AvatarModule.kt — Hilt binding
// ═══════════════════════════════════════════════════════════════════════
package com.codeextractor.app.di

import com.codeextractor.app.data.avatar.AvatarAnimatorImpl
import com.codeextractor.app.domain.avatar.AvatarAnimator
import dagger.Binds
import dagger.Module
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class AvatarModule {
    @Binds
    @Singleton
    abstract fun bindAvatarAnimator(impl: AvatarAnimatorImpl): AvatarAnimator
}


// ═══════════════════════════════════════════════════════════════════════
// INTEGRATION: Changes to VoiceViewModel.kt (промт исправлений)
// ═══════════════════════════════════════════════════════════════════════
//
// 1. Add to constructor:
//    private val avatarAnimator: AvatarAnimator,
//
// 2. In init {} block, add:
//    avatarAnimator.start()
//
// 3. In observeGeminiEvents(), change:
//
//    is GeminiEvent.AudioChunk -> {
//        _state.update { it.copy(isAiSpeaking = true) }
//        audioEngine.enqueuePlayback(event.pcmData)
//        avatarAnimator.feedAudio(event.pcmData)       // ← ADD
//        avatarAnimator.setSpeaking(true)                // ← ADD
//    }
//
//    is GeminiEvent.TurnComplete -> {
//        audioEngine.onTurnComplete()
//        avatarAnimator.setSpeaking(false)               // ← ADD
//        _state.update { it.copy(isAiSpeaking = false) }
//    }
//
//    is GeminiEvent.Interrupted -> {
//        audioEngine.flushPlayback()
//        avatarAnimator.setSpeaking(false)               // ← ADD
//        _state.update { it.copy(isAiSpeaking = false) }
//    }
//
// 4. Expose animator flow to UI:
//    val avatarAnimator: AvatarAnimator = avatarAnimator // public getter
//
// 5. In onCleared():
//    avatarAnimator.stop()
//
// ═══════════════════════════════════════════════════════════════════════


// ═══════════════════════════════════════════════════════════════════════
// INTEGRATION: Changes to AvatarScene.kt
// ═══════════════════════════════════════════════════════════════════════
//
// Replace the static morphWeights parameter with live data from animator:
//
// @Composable
// fun AvatarScene(
//     modifier: Modifier = Modifier,
//     morphWeights: FloatArray? = null,  // ← already exists
// )
//
// In VoiceScreen, collect the flow:
//
//   val renderState by viewModel.avatarAnimator
//       .renderState
//       .collectAsStateWithLifecycle()
//
//   AvatarScene(
//       modifier = Modifier.fillMaxSize(),
//       morphWeights = renderState.morphWeights,
//   )
//
// That's it. The AvatarScene.onFrame callback already applies morphs.
// ═══════════════════════════════════════════════════════════════════════
