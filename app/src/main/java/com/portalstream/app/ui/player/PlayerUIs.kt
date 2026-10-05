package com.portalstream.app.ui.player

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.media3.exoplayer.ExoPlayer
import androidx.media3.ui.PlayerView

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
