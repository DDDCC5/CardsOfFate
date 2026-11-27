package com.example.cardsfate.ui.ritual.components

import com.example.cardsfate.ui.ritual.FateButton

import androidx.compose.foundation.layout.*
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.example.cardsfate.domain.model.*
import com.example.cardsfate.ui.theme.*
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text

@Composable
internal fun ComfortControls(draft:ComfortPreference,validation:Boolean?,preview:ComfortPreviewKind,block:WorkspaceBlock,error:Boolean,language:ReadingLanguage,interact:Boolean,onSet:(ComfortPreference)->Unit,onRecenter:()->Unit,onCancel:()->Unit,onConfirm:()->Unit) {
    val zh=language==ReadingLanguage.ZH
    fun local(a:String,b:String)=if(zh)a else b
    Text(local("让牌组停在舒服的位置","Place the cards comfortably"),style=PicoTheme.typography.headlineSmall.copy(fontFamily=FateFont),color=FatePalette.ritualGold)
    Text(local("距离：${(draft.distance*100).toInt()} 厘米 · 高度偏移：${(draft.height*100).toInt()} 厘米",
        "Distance: ${(draft.distance*100).toInt()} cm · Height: ${(draft.height*100).toInt()} cm"),color=FatePalette.ritualIvory)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf(.42,.52).forEach { value -> Box(Modifier.weight(1f)) { FateButton(local(if(value==.42) "近 · 42 厘米" else "中 · 52 厘米",if(value==.42) "Near · 42 cm" else "Medium · 52 cm"),interact) { onSet(draft.copy(distance=value)) } } }
    }
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        listOf(-.06,0.0,.06).forEachIndexed { index,value -> Box(Modifier.weight(1f)) { FateButton((if(zh) listOf("低一点","默认","高一点") else listOf("Lower","Default","Higher"))[index],interact) { onSet(draft.copy(height=value)) } } }
    }
    FateButton(local("以当前朝向居中","Center on this direction"),interact) { onRecenter() }
    Text(when {
        block==WorkspaceBlock.TRACKING -> local("系统空间信息暂不可用，预览已暂停。恢复后会继续设置。","Room tracking is temporarily unavailable. The preview will resume when it returns.")
        validation==true -> local("位置检查通过，确认后保持固定。","Position is clear. Confirm to keep it fixed.")
        validation==null -> local("正在检查位置…","Checking position…")
        else -> when(block) {
            WorkspaceBlock.FAN -> local("下方牌扇与真实环境冲突，当前设置不能确认。","The lower fan conflicts with the room. This setting cannot be confirmed.")
            WorkspaceBlock.STACK -> local("牌堆位置与真实环境冲突，请调高一点或更换距离。","The pile conflicts with the room. Raise it or choose another distance.")
            else -> local("目标位置或翻牌路径受阻，请调高一点或更换距离。","The position or reveal path is blocked. Raise it or choose another distance.")
        }
    },color=FatePalette.ritualIvory)
    if(validation==false && preview==ComfortPreviewKind.LAST_SAFE) Text(local("牌暂留在上一个仍安全的预览位置，新设置尚未应用。","Cards remain at the last clear preview position. The new setting has not been applied."),color=FatePalette.ritualIvory)
    if(validation==false && preview==ComfortPreviewKind.UNAVAILABLE) Text(local("当前没有可安全显示的预览位置；请恢复默认或调整距离。","No clear preview position is available. Restore the default or adjust distance."),color=FatePalette.ritualIvory)
    if(error) Text(local("设置尚未保存，请重试确认。","Settings could not be saved. Please retry."),color=FatePalette.ritualIvory)
    Row(Modifier.fillMaxWidth(),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
        Box(Modifier.weight(1f)){FateButton(local("取消","Cancel"),interact){onCancel()}}
        Box(Modifier.weight(1f)){FateButton(local("确认位置","Confirm position"),interact && validation==true){onConfirm()}}
    }
}
