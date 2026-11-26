package com.example.cardsfate.domain.model

object ExperiencePolicy {
    fun historyAvailable(phase:RitualPhase)=phase==RitualPhase.SYSTEMS

    /** Only unselected fan cards are movable. Reading/revealed cards stay world-locked. */
    fun cardMovementAvailable(phase:RitualPhase)=phase==RitualPhase.DRAW

    /** Orbit cards are a preview: hide a blocked card, without invalidating the whole session. */
    fun cardCollisionBlocksSession(phase:RitualPhase)=phase!=RitualPhase.CONTEMPLATE

    /** A retry during contemplation must not clear the question, consent, or session. */
    fun spaceRetryRequiresReset(phase:RitualPhase)=phase !in setOf(
        RitualPhase.LOGO,RitualPhase.SAFETY,RitualPhase.SYSTEMS,RitualPhase.CONTEMPLATE,
    )
}
