package com.portalstream.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Fullscreen
import androidx.compose.material.icons.filled.Pause
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material.icons.filled.FullscreenExit
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView
import kotlin.math.roundToInt

// ==========================================
// 🚀 NUOVO COMPONENTE: MINIPLAYER TRASCINABILE
// ==========================================
@Composable
fun DraggableMiniPlayerContainer(
    isMinimized: Boolean,
    onExpand: () -> Unit,
    onClose: () -> Unit,
    isPlaying: Boolean,
    onTogglePlayPause: () -> Unit,
    videoContent: @Composable () -> Unit
) {
    if (!isMinimized) {
        // Schermo Intero Standard
        Box(modifier = Modifier.fillMaxSize()) {
            videoContent()
        }
    } else {
        // Miniplayer In-App Trascinabile (Stile YouTube)
        var offsetX by remember { mutableFloatStateOf(30f) }
        var offsetY by remember { mutableFloatStateOf(100f) }

        Box(
            modifier = Modifier
                .offset { IntOffset(offsetX.roundToInt(), offsetY.roundToInt()) }
                .width(240.dp)
                .height(135.dp)
                .pointerInput(Unit) {
                    detectDragGestures { change, dragAmount ->
                        change.consume()
                        offsetX += dragAmount.x
                        offsetY += dragAmount.y
                    }
                }
                .clip(RoundedCornerShape(12.dp))
                .background(Color.Black)
        ) {
            // Contenuto Video
            videoContent()

            // Barra dei controlli sovrapposta in alto al Miniplayer
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.TopEnd)
                    .background(Color.Black.copy(alpha = 0.5f)),
                horizontalArrangement = Arrangement.End,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = onTogglePlayPause, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = if (isPlaying) Icons.Default.Pause else Icons.Default.PlayArrow,
                        contentDescription = "Play/Pausa",
                        tint = Color.White
                    )
                }
                IconButton(onClick = onExpand, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Fullscreen,
                        contentDescription = "Espandi a Schermo Intero",
                        tint = Color.White
                    )
                }
                IconButton(onClick = onClose, modifier = Modifier.size(32.dp)) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Chiudi",
                        tint = Color.White
                    )
                }
            }
        }
    }
}

// ==========================================
// 📱 COMPONENTI ESISTENTI NEL TUO FILE
// ==========================================

@Composable
fun PhonePlayerUI(exoPlayer: ExoPlayer, pipManager: PipManager) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black)
    ) {
        // PlayerView nativo Media3 (controlli play/pausa/seek inclusi)
        AndroidView(
            factory = { context ->
                PlayerView(context).apply {
                    player = exoPlayer
                    useController = true
                }
            },
            modifier = Modifier.fillMaxSize()
        )
    }
}

@Composable
fun TabletPlayerUI(exoPlayer: ExoPlayer) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Text("📱 Tablet Player UI", color = Color.White)
    }
}

@Composable
fun TVPlayerUI(exoPlayer: ExoPlayer) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color.Black),
        contentAlignment = Alignment.Center
    ) {
        Text("📺 TV/Firestick Player UI", color = Color.White)
    }
}

// Schermata di errore: mostra il motivo del crash invece di chiudere l'app.
@Composable
fun CrashScreen(error: Throwable) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(Color(0xFF0F172A))
            .padding(16.dp),
        contentAlignment = Alignment.TopStart
    ) {
        Column {
            Text(
                "⚠️ Errore di avvio",
                color = Color(0xFFEF4444),
                style = MaterialTheme.typography.titleLarge
            )
            Spacer(Modifier.height(8.dp))
            Text(
                error.message ?: "Errore sconosciuto",
                color = Color.White
            )
            Spacer(Modifier.height(16.dp))
            Text(
                error.stackTraceToString(),
                color = Color(0xFF94A3B8),
                style = MaterialTheme.typography.bodySmall
            )
        }
    }
}
