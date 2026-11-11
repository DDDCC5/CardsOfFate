package com.example.cardsfate.content

import androidx.compose.runtime.*
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalConfiguration
import com.example.cardsfate.ui.ritual.FateViewModelFactory
import com.pico.spatial.core.ecs.TransformComponent
import com.pico.spatial.core.math.Vector3
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.foundation.content.SpatialView
import java.util.Locale

@Composable
fun HomeStage() {
    val context = LocalContext.current
    var attempt by remember { mutableIntStateOf(0) }
    val systemLanguage = LocalConfiguration.current.locales[0].language
    val owner = remember(attempt, systemLanguage) { runCatching { FateViewModelFactory.get(context) } }
    val vm = owner.getOrNull()
    if (vm != null) {
        FateScene(vm)
    } else {
        val zh = Locale.getDefault().language == "zh"
        SpatialView(
            initial = { content, attachments ->
                attachments.entity("startup-error")?.let {
                    it.components[TransformComponent::class.java]?.setPosition(Vector3(0f, 0f, -1.2f))
                    content.addEntity(it)
                }
            },
            attachments = {
                AttachmentPanel(id = "startup-error") {
                    PicoTheme {
                        SceneDiagnostic(
                            title = if (zh) "内容无法载入" else "Content unavailable",
                            message = if(zh) "请重试载入本地牌组。" else "Please retry loading the local deck.",
                            retryLabel = if (zh) "重试" else "Retry",
                            onRetry = { attempt++ },
                        )
                    }
                }
            },
        )
    }
}
