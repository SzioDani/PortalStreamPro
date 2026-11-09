package com.portalstream.app.ui.player

import android.app.Activity
import android.content.Context
import android.media.AudioManager
import android.provider.Settings
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.gestures.detectVerticalDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.Icon
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

private enum class GestureType { NONE, BRIGHTNESS, VOLUME }

@Composable
fun PlayerGestureOverlay(
    activity: Activity,
    enabled: Boolean,
    onSingleTap: () -> Unit,
    modifier: Modifier = Modifier
) {
    val audioManager = remember(activity) {
        activity.getSystemService(Context.AUDIO_SERVICE) as AudioManager
    }

    var containerSize by remember { mutableStateOf(IntSize.Zero) }
    var activeGesture by remember { mutableStateOf(GestureType.NONE) }
    var gesturePercent by remember { mutableIntStateOf(0) }

    // Accumulatori float per rendere lo scorrimento fluido
    var brightnessFloat by remember { mutableFloatStateOf(-1f) }
    var volumeFloat by remember { mutableFloatStateOf(-1f) }

    Box(
        modifier = modifier
            .fillMaxSize()
            .onSizeChanged { containerSize = it }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectTapGestures(
                    onTap = { onSingleTap() }
                )
            }
            .pointerInput(enabled) {
                if (!enabled) return@pointerInput
                detectVerticalDragGestures(
                    onDragStart = { offset ->
                        val width = containerSize.width.coerceAtLeast(1)
                        val isLeftSide = offset.x < (width / 2f)

                        if (isLeftSide) {
                            activeGesture = GestureType.BRIGHTNESS
                            val currentAttr = activity.window.attributes.screenBrightness
                            brightnessFloat = if (currentAttr in 0.01f..1.0f) {
                                currentAttr
                            } else {
                                val sys = try {
                                    Settings.System.getInt(
                                        activity.contentResolver,
                                        Settings.System.SCREEN_BRIGHTNESS
                                    ) / 255f
                                } catch (_: Exception) {
                                    0.5f
                                }
                                sys.coerceIn(0.05f, 1.0f)
                            }
                            gesturePercent = (brightnessFloat * 100f).roundToInt().coerceIn(0, 100)
                        } else {
                            activeGesture = GestureType.VOLUME
                            val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                            val curVol = audioManager.getStreamVolume(AudioManager.STREAM_MUSIC)
                            volumeFloat = curVol.toFloat() / maxVol.toFloat()
                            gesturePercent = (volumeFloat * 100f).roundToInt().coerceIn(0, 100)
                        }
                    },
                    onVerticalDrag = { change, dragAmount ->
                        change.consume()
                        val height = containerSize.height.coerceAtLeast(1).toFloat()
                        // Scorrendo verso l'alto dragAmount è negativo -> aumentiamo il valore
                        val delta = (-dragAmount / (height * 0.75f))

                        when (activeGesture) {
                            GestureType.BRIGHTNESS -> {
                                brightnessFloat = (brightnessFloat + delta).coerceIn(0.01f, 1.0f)
                                val lp = activity.window.attributes
                                lp.screenBrightness = brightnessFloat
                                activity.window.attributes = lp
                                gesturePercent = (brightnessFloat * 100f).roundToInt().coerceIn(1, 100)
                            }
                            GestureType.VOLUME -> {
                                volumeFloat = (volumeFloat + delta).coerceIn(0f, 1f)
                                val maxVol = audioManager.getStreamMaxVolume(AudioManager.STREAM_MUSIC).coerceAtLeast(1)
                                val targetVol = (volumeFloat * maxVol).roundToInt().coerceIn(0, maxVol)
                                audioManager.setStreamVolume(AudioManager.STREAM_MUSIC, targetVol, 0)
                                gesturePercent = (volumeFloat * 100f).roundToInt().coerceIn(0, 100)
                            }
                            GestureType.NONE -> {}
                        }
                    },
                    onDragEnd = {
                        activeGesture = GestureType.NONE
                    },
                    onDragCancel = {
                        activeGesture = GestureType.NONE
                    }
                )
            }
    ) {
        // Indicatore centrale non invasivo con icone vettoriali
        AnimatedVisibility(
            visible = activeGesture != GestureType.NONE,
            enter = fadeIn(),
            exit = fadeOut(),
            modifier = Modifier.align(Alignment.Center)
        ) {
            val icon = when (activeGesture) {
                GestureType.BRIGHTNESS -> when {
                    gesturePercent < 35 -> Icons.Default.BrightnessLow
                    gesturePercent < 75 -> Icons.Default.BrightnessMedium
                    else -> Icons.Default.BrightnessHigh
                }
                GestureType.VOLUME -> when {
                    gesturePercent == 0 -> Icons.Default.VolumeOff
                    gesturePercent < 50 -> Icons.Default.VolumeDown
                    else -> Icons.Default.VolumeUp
                }
                GestureType.NONE -> Icons.Default.VolumeUp
            }

            val label = if (activeGesture == GestureType.BRIGHTNESS) "Luminosità" else "Volume"

            Column(
                modifier = Modifier
                    .width(170.dp)
                    .clip(RoundedCornerShape(20.dp))
                    .background(Color(0xCC0F172A))
                    .border(1.dp, Color(0xFF00E5FF).copy(alpha = 0.6f), RoundedCornerShape(20.dp))
                    .padding(horizontal = 20.dp, vertical = 16.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = Color(0xFF00E5FF),
                    modifier = Modifier.size(34.dp)
                )
                Spacer(modifier = Modifier.height(6.dp))
                Text(
                    text = "$label  $gesturePercent%",
                    color = Color.White,
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Bold
                )
                Spacer(modifier = Modifier.height(10.dp))
                LinearProgressIndicator(
                    progress = gesturePercent / 100f,
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(6.dp)
                        .clip(RoundedCornerShape(3.dp)),
                    color = Color(0xFF00E5FF),
                    trackColor = Color.White.copy(alpha = 0.2f)
                )
            }
        }
    }
}
