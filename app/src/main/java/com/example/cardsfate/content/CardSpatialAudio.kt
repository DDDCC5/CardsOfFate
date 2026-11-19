package com.example.cardsfate.content

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.LoadType
import com.pico.spatial.core.ecs.ObjectAudioComponent
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.ecs.audio.AudioPlayerController
import com.pico.spatial.core.ecs.resource.AudioResource
import com.pico.spatial.core.math.Vector3

/** Three reusable positional emitters, far below the SDK's 40 object/ambient source limit. */
internal class CardSpatialAudio(private val root:Entity):AutoCloseable {
    private data class Voice(val entity:Entity,val resource:AudioResource,val player:AudioPlayerController,var cardId:String?=null)
    private val voices=linkedMapOf<String,Voice>()
    fun load() {
        for(id in listOf("select","flip","cancel")) {
            val entity=Entity()
            root.addChild(entity)
            entity.components.set(ObjectAudioComponent())
            val resource=AudioResource.load("cards-fate-$id","asset://audio/sfx/$id.wav",LoadType.FROM_ASSETS)
            try {
                val player=entity.prepareAudio(resource)
                voices[id]=Voice(entity,resource,player)
            } catch(failure:Exception) {
                entity.destroy();resource.close();throw failure
            }
        }
    }
    fun play(id:String,cardId:String,position:Vector3):Boolean {
        val voice=voices[id] ?: return false
        voice.cardId=cardId
        voice.entity.components.get<TransformComponent>()!!.position=root.convertPositionFrom(position,null)
        voice.player.stop()
        return voice.player.play()
    }
    fun frame(positionOf:(String)->Vector3?) {
        voices.values.forEach { voice -> voice.cardId?.let(positionOf)?.let { p ->
            voice.entity.components.get<TransformComponent>()!!.position=root.convertPositionFrom(p,null)
        } }
    }
    fun stop() { voices.values.filter { it.cardId!=null }.forEach { it.player.stop();it.cardId=null } }
    override fun close() {
        voices.values.forEach { voice ->
            voice.player.stop();voice.player.close();voice.entity.destroy();voice.resource.close()
        }
        voices.clear()
    }
}
