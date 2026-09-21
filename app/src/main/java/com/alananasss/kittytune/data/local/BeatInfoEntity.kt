package com.alananasss.kittytune.data.local

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "beat_info")
data class BeatInfoEntity(
    @PrimaryKey val songId: String,
    val bpm: Float,
    val firstBeatOffsetMs: Long,
    val confidence: Float,
    val analyzedAt: Long = System.currentTimeMillis(),
    /** Where the incoming track should start: first sustained-energy downbeat past intro. */
    val mixInPointMs: Long? = null,
    /** Where the outgoing track's body ends (outro begins); transition starts here. */
    val mixOutPointMs: Long? = null,
    /** 0=C, 1=C#, ... 11=B. Null when track's tonal chroma signal was too weak to call a key. */
    val keyPitchClass: Int? = null,
    val keyIsMinor: Boolean? = null,
    /**
     * 0..1 "how intense does this track feel" proxy, blended from the analyzed window's
     * loudness (RMS, log-compressed) and tempo. Used to plan an energy arc across a set
     * instead of just judging one transition at a time. Null for rows analyzed before this
     * field existed.
     */
    val energyLevel: Float? = null,
)
