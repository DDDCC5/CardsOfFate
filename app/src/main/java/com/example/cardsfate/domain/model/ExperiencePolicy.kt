package com.example.cardsfate.domain.model

object ExperiencePolicy {
    fun historyAvailable(phase:RitualPhase)=phase==RitualPhase.SYSTEMS
}
