package com.example.cardsfate.domain.model

enum class ComfortPreviewKind { TARGET, LAST_SAFE, UNAVAILABLE }
enum class WorkspaceBlock { NONE, STACK, READING, FAN, TRANSITION, TRACKING }
data class ComfortPreviewResult<T>(val pose:T?,val fallback:Boolean)

/** Rendering permission is independent of full-workspace confirmation. Revalidate cached geometry every time. */
class ComfortPreviewCache<T> {
    private var last:T?=null
    fun reset(){last=null}
    fun select(requested:T,isClear:(T)->Boolean):ComfortPreviewResult<T> {
        if(isClear(requested)) { last=requested;return ComfortPreviewResult(requested,false) }
        val fallback=last?.takeIf(isClear)
        return ComfortPreviewResult(fallback,fallback!=null)
    }
}
