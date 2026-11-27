package com.example.cardsfate.content

import com.pico.spatial.core.ecs.Entity
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Quat
import com.pico.spatial.core.math.Vector3
import kotlin.math.PI
import kotlin.math.sin

/** Fixed-size main-thread pool. These effects never alter card poses or gesture decisions. */
internal class RitualFeedback(private val root:Entity) {
    private val points=mutableListOf<Entity>()
    private val runes=mutableListOf<Entity>()
    private val lines=mutableListOf<Entity>()
    private val targets=mutableListOf<Entity>()
    private var ring:Entity?=null
    private data class Pulse(val position:Vector3,val rotation:Quat,val start:Float)
    private var landing:Pulse?=null
    private var turning:Pulse?=null
    private val trail=ArrayDeque<Pair<Vector3,Float>>()

    suspend fun load() {
        suspend fun item(name:String)=Entity.loadSuspend("asset://models/$name.glb").also { it.enabled=false;root.addChild(it) }
        repeat(12) { points+=item("fate_light_point") }
        repeat(6) { runes+=item("fate_reveal_rune") }
        repeat(2) { lines+=item("fate_connection_segment") }
        repeat(3) { targets+=item("fate_landing_ring") }
        ring=item("fate_landing_ring")
    }
    fun hide() { (points+runes+lines+targets+listOfNotNull(ring)).forEach { it.enabled=false } }
    fun clear() { hide();trail.clear();landing=null;turning=null }
    fun trail(position:Vector3,now:Float) {
        if(trail.lastOrNull()?.let { (it.first-position).length()<.012f }==true) return
        trail.addLast(position to now)
        while(trail.size>12) trail.removeFirst()
    }
    fun land(position:Vector3,rotation:Quat,now:Float) { landing=Pulse(position,rotation,now) }
    fun reveal(position:Vector3,rotation:Quat,now:Float) { turning=Pulse(position,rotation,now) }
    fun frame(now:Float,relations:Boolean,positions:List<Vector3>,focus:Int,yaw:Quat,
              showTargets:Boolean,occupied:Set<Int>,crystal:Vector3?,safe:(Vector3,Float)->Boolean) {
        hide()
        while(trail.firstOrNull()?.let { now-it.second>.52f }==true) trail.removeFirst()
        trail.forEachIndexed { i,(p,t) ->
            val fraction=(1f-(now-t)/.52f).coerceIn(0f,1f)
            if(safe(p,.004f)) show(points[i],p,Quat.identity(),Vector3(fraction))
        }
        if(showTargets) targets.forEachIndexed { i,entity ->
            if(i !in occupied && safe(positions[i],.05f)) show(entity,positions[i],yaw,Vector3(1f))
        }
        landing?.let { pulse ->
            val t=(now-pulse.start)/.42f
            if(t in 0f..1f && safe(pulse.position,.06f)) {
                val scale=(.7f+.55f*t)*sin(t*PI.toFloat()).coerceAtLeast(.05f)
                ring?.let { show(it,pulse.position+yaw.rotateVector(Vector3(0f,0f,.003f)),pulse.rotation,Vector3(scale)) }
            } else landing=null
        }
        turning?.let { pulse ->
            val t=(now-pulse.start)/.85f
            if(t in 0f..1f) runes.forEachIndexed { i,entity ->
                val fade=sin(((t-i*.04f).coerceIn(0f,1f))*PI.toFloat()).coerceAtLeast(0f)
                if(fade>.05f) {
                    val angle=i*PI.toFloat()/3f
                    val local=Vector3(kotlin.math.cos(angle)*.065f,kotlin.math.sin(angle)*.088f,0f)
                    val p=pulse.position+pulse.rotation.rotateVector(local)
                    if(safe(p,.022f)) show(entity,p,pulse.rotation*Quat(Vector3(0f,0f,1f),angle-PI.toFloat()/2f),Vector3(fade))
                }
            }
            val glow=(now-pulse.start)/1.1f
            if(glow in 0f..1f && crystal!=null && safe(crystal,.012f)) show(points.last(),crystal,Quat.identity(),Vector3(1f+sin(glow*PI.toFloat())*1.25f))
            if(glow>1f) turning=null
        }
        if(relations && focus in 0..2) {
            positions.indices.filter { it!=focus }.forEachIndexed { i,k ->
                val a=positions[focus]+yaw.rotateVector(Vector3(0f,-.21f,0f))
                val b=positions[k]+yaw.rotateVector(Vector3(0f,-.21f,0f))
                if((0..16).all { step -> safe(a+(b-a)*(step/16f),.003f) })
                    show(lines[i],(a+b)*.5f,yaw*Quat(Vector3(0f,0f,1f),PI.toFloat()/2f),Vector3(1f,(b-a).length(),1f))
            }
        }
    }
    private fun show(entity:Entity,p:Vector3,q:Quat,scale:Vector3) {
        entity.components.get<TransformComponent>()!!.apply {
            position=root.convertPositionFrom(p,null);quaternion=root.convertRotationFrom(q,null);scaleVector=scale
        }
        entity.enabled=true
    }
}
