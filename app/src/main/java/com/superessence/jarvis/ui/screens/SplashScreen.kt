package com.superessence.jarvis.ui.screens

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.drawWithContent
import androidx.compose.ui.draw.scale
import androidx.compose.ui.graphics.BlendMode
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.CompositingStrategy
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.superessence.jarvis.R
import com.superessence.jarvis.ui.components.clickableNoRipple
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Écran de chargement : signature du studio SuperEssence, puis « JARVIS ». Touchez pour passer. */
@Composable
fun SplashScreen(onDone: () -> Unit) {
    val logoAlpha = remember { Animatable(0f) }
    val logoScale = remember { Animatable(0.92f) }
    val shine = remember { Animatable(-0.3f) }
    val subtitle = remember { Animatable(0f) }
    LaunchedEffect(Unit) {
        launch { logoAlpha.animateTo(1f, tween(700, easing = FastOutSlowInEasing)) }
        launch { logoScale.animateTo(1f, tween(1400, easing = FastOutSlowInEasing)) }
        delay(500)
        launch { shine.animateTo(1.3f, tween(1100, easing = LinearEasing)) }
        delay(700)
        subtitle.animateTo(1f, tween(600))
        delay(900)
        logoAlpha.animateTo(0f, tween(400))
        onDone()
    }
    Box(Modifier.fillMaxSize().background(Color.Black).clickableNoRipple(onDone), contentAlignment = Alignment.Center) {
        Column(horizontalAlignment = Alignment.CenterHorizontally, modifier = Modifier.alpha(logoAlpha.value)) {
            Image(
                painter = painterResource(R.drawable.superessence_logo),
                contentDescription = "SuperEssence",
                contentScale = ContentScale.Fit,
                modifier = Modifier.fillMaxWidth().padding(horizontal = 36.dp).scale(logoScale.value)
                    .graphicsLayer { compositingStrategy = CompositingStrategy.Offscreen }
                    .drawWithContent {
                        drawContent()
                        // Reflet lumineux qui balaie le logo
                        val x = size.width * shine.value
                        drawRect(
                            Brush.linearGradient(
                                listOf(Color.Transparent, Color.White.copy(alpha = 0.55f), Color.Transparent),
                                start = androidx.compose.ui.geometry.Offset(x - size.width * 0.15f, 0f),
                                end = androidx.compose.ui.geometry.Offset(x + size.width * 0.15f, size.height),
                            ),
                            blendMode = BlendMode.SrcAtop,
                        )
                    },
            )
            Spacer(Modifier.height(18.dp))
            Text("présente", color = Color(0xFF9AA6B2), fontSize = 13.sp, letterSpacing = 4.sp, modifier = Modifier.alpha(subtitle.value))
            Spacer(Modifier.height(6.dp))
            Text("J A R V I S", color = Color(0xFF22E4FF), fontSize = 22.sp, fontWeight = FontWeight.Light, letterSpacing = 6.sp,
                modifier = Modifier.alpha(subtitle.value))
        }
    }
}
