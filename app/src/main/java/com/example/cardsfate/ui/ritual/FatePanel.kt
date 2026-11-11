package com.example.cardsfate.ui.ritual

import android.graphics.BitmapFactory
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.shape.GenericShape
import androidx.compose.runtime.*
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.example.cardsfate.domain.model.*
import com.example.cardsfate.ui.theme.*
import com.pico.spatial.ui.design.Button
import com.pico.spatial.ui.design.ButtonDefaults
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import org.json.JSONObject
import java.text.DateFormat
import java.util.Date

private val SealShape = GenericShape { size, _ ->
    val cut = 12f
    moveTo(cut, 0f); lineTo(size.width-cut, 0f); lineTo(size.width, cut)
    lineTo(size.width,size.height-cut); lineTo(size.width-cut,size.height)
    lineTo(cut,size.height); lineTo(0f,size.height-cut); lineTo(0f,cut); close()
}

@Composable
fun FatePanel(id: String, vm: FateViewModel, onRetrySpace: () -> Unit) {
    val ui by vm.state.collectAsStateWithLifecycle()
    val zh = ui.language == ReadingLanguage.ZH
    val context = LocalContext.current
    val strings = remember(zh) { JSONObject(context.assets.open("data/strings-${if(zh) "zh" else "en"}.json").bufferedReader().use { it.readText() }) }
    fun copy(key: String) = strings.getString(key)
    fun local(chinese: String, english: String) = if(zh) chinese else english
    val phase = ui.ritual.phase
    if(id == "main" && phase == RitualPhase.LOGO) {
        FateLogo(copy("logo-title")) { vm.onEvent(RitualEvent.LogoFinished) }
        return
    }
    val fault = when {
        ui.controllerActive -> "controllers"
        ui.ritual.suspension == SuspensionReason.HAND_TRACKING_LOST -> "tracking-lost"
        ui.ritual.suspension == SuspensionReason.SPACE_UNAVAILABLE -> "space-error"
        else -> null
    }
    if(id == "status" && fault == null && ui.audioError == null && ui.saveError == null) return
    if(id == "main" && phase in setOf(RitualPhase.READING,RitualPhase.SUMMARY)) return
    val interact = !ui.controllerActive && ui.handTrackingReady
    val frame = remember(zh) { context.assets.open("ui/${if(zh) "zh" else "en"}/interpretation.png").use { BitmapFactory.decodeStream(it).asImageBitmap() } }
    val panelWidth = if(id == "reading") 520.dp else 640.dp
    Box(Modifier.width(panelWidth).height(if(id == "status") 230.dp else 440.dp)) {
        // Approved custom engraved panel artwork, rather than a system control skin.
        Image(frame, null, Modifier.matchParentSize(), contentScale = ContentScale.FillBounds)
        Column(Modifier.fillMaxSize().padding(horizontal=36.dp,vertical=28.dp).verticalScroll(rememberScrollState()),
            verticalArrangement=Arrangement.spacedBy(16.dp), horizontalAlignment=Alignment.CenterHorizontally) {
            @Composable fun title(value: String) { Text(value,style=PicoTheme.typography.headlineSmall.copy(fontFamily=FateFont),color=FatePalette.ritualGold,textAlign=TextAlign.Center) }
            @Composable fun body(value: String) { Text(value,style=PicoTheme.typography.bodyLarge.copy(fontFamily=FateFont),color=FatePalette.ritualIvory,textAlign=TextAlign.Center) }
            @Composable fun action(label: String, enabled: Boolean = true, click: () -> Unit) {
                FateButton(label,enabled && interact) { vm.playEffect("button"); click() }
            }
            when(id) {
                "status" -> {
                    if(fault != null) {
                        title(copy("$fault-title")); body(copy("$fault-copy"))
                        if(fault == "space-error") action(copy("space-error-primary")) { onRetrySpace() }
                    } else if(ui.audioError != null) {
                        title(local("旁白暂时中断","Narration interrupted"))
                        body(local("重试后将重新播放当前解读。","Retry to play the current reading again."))
                        action(local("重试播放","Retry narration")) { vm.retryAudio() }
                    } else {
                        title(copy("history-save-error-title")); body(copy("history-save-error-copy"))
                        action(copy("history-save-error-primary")) { vm.retrySave() }
                    }
                }
                "history" -> {
                    val detail=ui.historyDetail
                    title(copy(if(detail == null) "history-title" else "history-detail-title"))
                    if(detail == null) {
                        body(copy("history-copy"))
                        if(ui.historyRecords.isEmpty()) body(copy("history-empty-copy"))
                        ui.historyRecords.forEach { record ->
                            val date=DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date(record.completedAtEpochMillis))
                            action(date) { vm.onUiEvent(FateUiEvent.OpenRecord(record)) }
                            body(record.cards.joinToString(" · ") { vm.title(it.cardId)+if(it.reversed) local("（逆位）"," (reversed)") else local("（正位）"," (upright)") })
                        }
                        action(copy("history-back")) { vm.onUiEvent(FateUiEvent.ToggleHistory) }
                    } else {
                        detail.cards.forEachIndexed { index,card ->
                            body(position(index,zh)+" · "+vm.title(card.cardId)+" · "+orientation(card,zh))
                        }
                        body(detail.summary)
                        action(copy("history-detail-primary")) { vm.onUiEvent(FateUiEvent.OpenRecord(null)) }
                    }
                }
                "reading" -> {
                    if(phase == RitualPhase.SUMMARY) {
                        title(copy("summary-title")); body(ui.ritual.result?.summary.orEmpty())
                        action(copy("summary-primary")) { vm.onEvent(RitualEvent.Reset) }
                        body(local("也可双手合十，再张开重新开始。","You can also bring your palms together, then open them to restart."))
                    } else {
                        val i=(ui.ritual.revealedCount-1).coerceAtLeast(0)
                        val card=ui.ritual.selectedCards.getOrNull(i)
                        title(if(card != null && ui.ritual.revealedCount > 0) position(i,zh)+" · "+vm.title(card.cardId) else local("过去 · 现在 · 未来","Past · Present · Future"))
                        if(ui.ritual.revealedCount>0 && card != null) {
                            body(orientation(card,zh)); body(ui.narrationText.orEmpty())
                        }
                        if(!ui.ritual.narrationPlaying && ui.ritual.revealedCount<3) body(local("指向下一张牌，捏合并转动手掌，也可直接触碰。","Point at the next card, pinch and turn your palm, or touch it directly."))
                    }
                    action(copy("summary-history")) { vm.onUiEvent(FateUiEvent.ToggleHistory) }
                }
                else -> {
                    val key=when(phase) {
                        RitualPhase.SAFETY -> "safety"
                        RitualPhase.SYSTEMS -> "systems"
                        RitualPhase.CONTEMPLATE -> "contemplate"
                        RitualPhase.GATHER -> "gather"
                        RitualPhase.SHUFFLE -> "shuffle"
                        RitualPhase.INTERLACE -> if(ui.ritual.interlaceCount==0) "interlace-one" else "interlace-two"
                        RitualPhase.DRAW -> "draw"
                        RitualPhase.SPREAD -> "spread"
                        else -> "contemplate"
                    }
                    title(copy("$key-title")); body(copy("$key-copy"))
                    when(phase) {
                        RitualPhase.SAFETY -> {
                            action(copy("safety-primary")) { vm.onEvent(RitualEvent.AcceptSafety) }
                            if(!ui.handTrackingReady && !ui.controllerActive) body(copy("tracking-lost-copy"))
                        }
                        RitualPhase.SYSTEMS -> {
                            action(copy("systems-primary"), ui.sceneReady && ui.spaceReady && ui.handTrackingReady) { vm.onEvent(RitualEvent.SystemsReady) }
                            if(!ui.sceneReady) body(local("牌组正在进入空间…","Bringing the deck into your space…"))
                            else if(!ui.spaceReady) {
                                body(copy("space-error-copy")); action(copy("space-error-primary")) { onRetrySpace() }
                            } else if(!ui.handTrackingReady) body(copy("tracking-lost-copy"))
                        }
                        RitualPhase.CONTEMPLATE -> action(copy("contemplate-primary"),ui.ritual.suspension==null) { vm.onEvent(RitualEvent.ContemplationFinished) }
                        RitualPhase.DRAW -> {
                            body(local("已选 ${ui.ritual.selectedIndices.size} / 3 张","${ui.ritual.selectedIndices.size} / 3 selected"))
                            if(!ui.gazeAvailable) body(copy("eye-fallback-copy"))
                        }
                        else -> Unit
                    }
                    if(phase != RitualPhase.SAFETY) action(copy("history-title")) { vm.onUiEvent(FateUiEvent.ToggleHistory) }
                }
            }
        }
    }
}

@Composable
internal fun FateButton(label: String, enabled: Boolean, onClick: () -> Unit) {
    // SpatialUI owns hover, pressed and disabled feedback; approved plaque silhouette/colors.
    Button(onClick=onClick,enabled=enabled,shape=SealShape,
        colors=ButtonDefaults.buttonColors(containerColor=FatePalette.ritualGold,contentColor=FatePalette.ritualInk),
        modifier=Modifier.fillMaxWidth().heightIn(min=52.dp)) {
        Text(label,style=PicoTheme.typography.titleMedium.copy(fontFamily=FateFont),textAlign=TextAlign.Center)
    }
}
private fun position(index: Int, zh: Boolean) = (if(zh) listOf("过去","现在","未来") else listOf("Past","Present","Future"))[index]
private fun orientation(card: DrawnCard, zh: Boolean) = if(card.reversed) { if(zh) "逆位" else "Reversed" } else { if(zh) "正位" else "Upright" }
