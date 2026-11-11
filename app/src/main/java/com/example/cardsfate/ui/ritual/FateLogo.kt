package com.example.cardsfate.ui.ritual

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.CubicBezierEasing
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.keyframes
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.drawscope.clipPath
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.cardsfate.R
import com.pico.spatial.ui.design.PicoTheme
import com.pico.spatial.ui.design.Text
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Four unchanged SVG layers, animated on the approved 6.2-second startup timeline. */
@Composable
fun FateLogo(title: String, onFinished: () -> Unit) {
    val orbit = remember { Animatable(0f) }
    val trace = remember { Animatable(0f) }
    val cards = remember { Animatable(0f) }
    val spark = remember { Animatable(0f) }
    val halo = remember { Animatable(0f) }
    val lettering = remember { Animatable(0f) }
    val currentOnFinished = rememberUpdatedState(onFinished)
    val serif = remember { FontFamily(Font(R.font.fate_serif, FontWeight.Medium)) }
    val cardRisePx = with(LocalDensity.current) { 340.dp.toPx() * 30f / 460f }
    val ritualIvory = Color(0xFFF1E7D2) // design-style: fixed-figma-color ritualIvory design-spec.json

    LaunchedEffect(Unit) {
        coroutineScope {
            launch { orbit.animateTo(1f, tween(5600, easing = CubicBezierEasing(.2f, .7f, .2f, 1f))) }
            launch { trace.animateTo(1f, tween(2700, easing = FastOutSlowInEasing)) }
            launch {
                delay(1250)
                cards.animateTo(1f, tween(1700, easing = FastOutSlowInEasing))
            }
            launch {
                delay(2300)
                halo.animateTo(.6f, keyframes {
                    durationMillis = 2000
                    0f at 0
                    1f at 1300
                    .6f at 2000
                })
            }
            launch {
                delay(2400)
                spark.animateTo(1f, tween(1600, easing = FastOutSlowInEasing))
            }
            launch {
                delay(3100)
                lettering.animateTo(1f, tween(1500, easing = FastOutSlowInEasing))
            }
            delay(6200)
            currentOnFinished.value()
        }
    }

    // The component is transparent; its owning Stage/container controls the approved background.
    PicoTheme {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(24.dp),
        ) {
            Box(Modifier.size(340.dp), contentAlignment = Alignment.Center) {
                Image(
                    painterResource(R.drawable.fate_logo_halo), contentDescription = null,
                    modifier = Modifier.fillMaxSize().graphicsLayer { alpha = halo.value },
                )
                Image(
                    painterResource(R.drawable.fate_logo_orbit), contentDescription = null,
                    modifier = Modifier.fillMaxSize()
                        .graphicsLayer {
                            rotationZ = -55f * (1f - orbit.value)
                            alpha = orbit.value
                        }
                        .drawWithContent {
                            val progress = trace.value
                            if (progress >= 1f) drawContent()
                            else if (progress > 0f) {
                                // A progressive radial trace preserves the exact rasterized orbit artwork.
                                val radius = size.maxDimension
                                val sector = Path().apply {
                                    moveTo(center.x, center.y)
                                    arcTo(Rect(center.x - radius, center.y - radius,
                                        center.x + radius, center.y + radius), -90f, 360f * progress, false)
                                    close()
                                }
                                clipPath(sector) { this@drawWithContent.drawContent() }
                            }
                        },
                )
                Image(
                    painterResource(R.drawable.fate_logo_cards), contentDescription = null,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        alpha = cards.value
                        translationY = cardRisePx * (1f - cards.value)
                    },
                )
                Image(
                    painterResource(R.drawable.fate_logo_spark), contentDescription = null,
                    modifier = Modifier.fillMaxSize().graphicsLayer {
                        alpha = spark.value
                        scaleX = .65f + .35f * spark.value
                        scaleY = scaleX
                        transformOrigin = TransformOrigin(.5f, 220f / 460f)
                    },
                )
            }
            Text(
                text = title,
                modifier = Modifier.graphicsLayer { alpha = lettering.value },
                color = ritualIvory,
                fontFamily = serif,
                fontWeight = FontWeight.Medium,
                fontSize = 38.sp,
                lineHeight = 52.sp,
                textAlign = TextAlign.Center,
            )
        }
    }
}
