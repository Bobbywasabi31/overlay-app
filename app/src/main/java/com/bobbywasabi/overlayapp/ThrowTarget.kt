package com.bobbywasabi.overlayapp

enum class ThrowTarget { GAME, PRACTICE }

object ThrowTargetResolver {
    const val GAME_PACKAGE = "com.nianticlabs.pokemongo"
    fun resolve(packageName: String?, ownPackage: String, practiceVisible: Boolean): ThrowTarget? = when {
        packageName == GAME_PACKAGE -> ThrowTarget.GAME
        packageName == ownPackage && practiceVisible -> ThrowTarget.PRACTICE
        else -> null
    }
}

/** True only while DemoActivity is resumed; the control screen is never a swipe target. */
object PracticeSession {
    var visible = false
        private set
    fun resume() { visible = true; ThrowState.disarm() }
    fun pause() { visible = false; ThrowState.disarm() }
}
