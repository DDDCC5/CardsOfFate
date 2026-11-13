package com.example.cardsfate.domain.model

/** Require a stable physical release; a single tracking-boundary bounce cannot draw again. */
class StackTapGate(private val releaseMs:Long=180L,private val minimumIntervalMs:Long=180L) {
    private var armed = false
    private var releasedSince:Long?=null
    private var lastSample:Long?=null
    private var lastAccepted:Long?=null
    fun reset() { armed = false; releasedSince=null }
    fun update(pressed: Boolean, targeted: Boolean, timestampMs:Long): Boolean {
        if(lastSample?.let { timestampMs<=it }==true) return false
        if(lastSample?.let { timestampMs-it>250L }==true) { armed=false;releasedSince=null }
        lastSample=timestampMs
        if (!pressed) {
            if(releasedSince==null) releasedSince=timestampMs
            if(timestampMs-releasedSince!!>=releaseMs) armed=true
            return false
        }
        releasedSince=null
        val tap = armed && targeted && (lastAccepted?.let { timestampMs-it>=minimumIntervalMs } ?: true)
        armed = false
        if(tap) lastAccepted=timestampMs
        return tap
    }
}
