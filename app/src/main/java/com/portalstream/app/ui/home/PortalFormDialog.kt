package com.portalstream.app.ui.home

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.portalstream.app.data.Portal
import com.portalstream.app.data.PortalType

private data class UaPreset(val label: String, val value: String)

// Lista completa dei preset STB e Browser basata sui tuoi array originali
private val UA_PRESETS = listOf(
    UaPreset("MAG250", "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 MAG250 stbapp ver: 2.20.06"),
    UaPreset("MAG254", "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG254 stbapp ver: 2.20.06"),
    UaPreset("MAG322 (Versione Base)", "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG322 stbapp ver: 2.20.68.4.1.3"),
    UaPreset("MAG322 (Firmware v4.3)", "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 MAG322 stbapp ver: 4.3.1939"),
    UaPreset("MAG420", "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG420 stbapp ver: 2.22.1"),
    UaPreset("MAG520", "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG520 stbapp ver: 2.24.2"),
    UaPreset("MAG524", "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 (KHTML, like Gecko) MAG524 stbapp ver: 2.24.2"),
    UaPreset("MAG200 (Firmware v4.3)", "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 MAG200 stbapp ver: 4.3.1939"),
    UaPreset("Browser Chrome (Desktop)", "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36")
)

@Composable
fun AddPortalTypeDialog(
    onSelect: (PortalType) -> Unit,
    onDismiss: () -> Unit
) {
    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .width(300.dp)
                .border(
                    width = 1.5.dp,
                    color = Color(0xFF00E5FF),
                    shape = RoundedCornerShape(28.dp)
                ),
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFF111318), // Sfondo ripreso dal tuo layout XML
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Text(
                    text = "Aggiungi playlist",
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    textAlign = TextAlign.Center,
                    fontWeight = FontWeight.Bold
                )

                Spacer(Modifier.height(20.dp))

                TextButton(
                    onClick = { onSelect(PortalType.M3U) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "FILE O LINK PLAYLIST",
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(Modifier.height(8.dp))

                TextButton(
                    onClick = { onSelect(PortalType.MAG) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "MAG PORTALE",
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(Modifier.height(8.dp))

                TextButton(
                    onClick = { onSelect(PortalType.XSTREAM) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "XTREAM CODES PORTAL",
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(Modifier.height(8.dp))

                TextButton(
                    onClick = { onSelect(PortalType.TORRENT) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        "TORRENT STREAM",
                        color = Color.White,
                        fontWeight = FontWeight.Medium,
                        textAlign = TextAlign.Center
                    )
                }

                Spacer(Modifier.height(16.dp))

                TextButton(
                    onClick = onDismiss,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = "Annulla",
                        color = Color(0xFFEF4444),
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        }
    }
}

@Composable
fun PortalFormDialog(
    existing: Portal?,
    type: PortalType,
    onDismiss: () -> Unit,
    onSave: (Portal) -> Unit,
    onPickFile: () -> Unit,
    pickedFileUrl: String? = null,
    pickedFileName: String? = null,
    onPickedFileConsumed: () -> Unit = {}
) {
    var name by remember { mutableStateOf(existing?.name ?: "") }
    var url by remember { mutableStateOf(existing?.url ?: "") }
    var mac by remember { mutableStateOf(existing?.macAddress ?: "") }
    var server by remember { mutableStateOf(existing?.server ?: "") }
    var username by remember { mutableStateOf(existing?.username ?: "") }
    var password by remember { mutableStateOf(existing?.password ?: "") }
    var showPassword by remember { mutableStateOf(false) }
    var streamFormat by remember { mutableStateOf(existing?.streamFormat ?: "m3u8") }
    var forceLink by remember { mutableStateOf(existing?.forceStreamLink ?: false) }
    var useVpn by remember { mutableStateOf(existing?.useVpn ?: false) }
    var profile by remember { mutableStateOf(existing?.profile ?: "") }
    var useCustomUa by remember { mutableStateOf(existing?.useCustomUserAgent ?: false) }
    var userAgent by remember { mutableStateOf(existing?.userAgent ?: "") }
    var uaMenu by remember { mutableStateOf(false) }
    val clipboard = LocalClipboardManager.current

    LaunchedEffect(pickedFileUrl) {
        if (!pickedFileUrl.isNullOrBlank()) {
            url = pickedFileUrl
            if (name.isBlank() && !pickedFileName.isNullOrBlank()) {
                name = pickedFileName
            }
            onPickedFileConsumed()
        }
    }

    val title = when (type) {
        PortalType.M3U -> "File o Link Playlist"
        PortalType.MAG -> "MAG Portale"
        PortalType.XSTREAM -> "Xtream Codes Portal"
        PortalType.TORRENT -> "Torrent Stream"
        else -> "Portale"
    }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            modifier = Modifier
                .width(320.dp)
                .border(
                    width = 1.5.dp,
                    color = Color(0xFF00E5FF), // Cornice Neon Ciano
                    shape = RoundedCornerShape(28.dp)
                ),
            shape = RoundedCornerShape(28.dp),
            color = Color(0xFF111318), // Sfondo scuro coerente con l'XML
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(20.dp)
                    .verticalScroll(rememberScrollState())
            ) {
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleLarge,
                    color = Color.White,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Center
                )

                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome Playlist (opzionale)", color = Color(0xFF888888)) },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Spacer(Modifier.height(8.dp))

                when (type) {
                    PortalType.M3U -> {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text("Link della playlist", color = Color(0xFF888888)) },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(fontSize = 13.sp, color = Color.White),
                            maxLines = 2
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            OutlinedButton(onClick = {
                                clipboard.getText()?.text?.let { url = it }
                            }) {
                                Text("Incolla")
                            }
                            OutlinedButton(onClick = onPickFile) {
                                Text("Sfoglia file")
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    PortalType.TORRENT -> {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text("InfoHash o Magnet", color = Color(0xFF888888)) },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(fontSize = 13.sp, color = Color.White),
                            maxLines = 2
                        )
                        Spacer(Modifier.height(4.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            OutlinedButton(onClick = {
                                clipboard.getText()?.text?.let { url = it }
                            }) {
                                Text("Incolla")
                            }
                            OutlinedButton(onClick = onPickFile) {
                                Text("Sfoglia file")
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    PortalType.MAG -> {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text("Link Portale MAG", color = Color(0xFF888888)) },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(fontSize = 13.sp, color = Color.White),
                            maxLines = 2
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = mac,
                            onValueChange = { mac = it },
                            label = { Text("MAC Address", color = Color(0xFF888888)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = TextStyle(color = Color.White)
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    PortalType.XSTREAM -> {
                        OutlinedTextField(
                            value = server,
                            onValueChange = { server = it },
                            label = { Text("Server:porta", color = Color(0xFF888888)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = TextStyle(color = Color.White)
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("Utente", color = Color(0xFF888888)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = TextStyle(color = Color.White)
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password", color = Color(0xFF888888)) },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            textStyle = TextStyle(color = Color.White),
                            visualTransformation =
                                if (showPassword) VisualTransformation.None
                                else PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { showPassword = !showPassword }) {
                                    Text(if (showPassword) "Nascondi" else "Mostra", color = Color(0xFF00E5FF))
                                }
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Formato Streams",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFF888888)
                        )
                        Row {
                            OutlinedButton(
                                onClick = { streamFormat = "ts" },
                                colors = if (streamFormat == "ts")
                                    ButtonDefaults.outlinedButtonColors(
                                        containerColor = Color(0xFF00E5FF).copy(alpha = 0.2f)
                                    )
                                else ButtonDefaults.outlinedButtonColors()
                            ) {
                                Text("TS", color = Color.White)
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { streamFormat = "m3u8" },
                                colors = if (streamFormat == "m3u8")
                                    ButtonDefaults.outlinedButtonColors(
                                        containerColor = Color(0xFF00E5FF).copy(alpha = 0.2f)
                                    )
                                else ButtonDefaults.outlinedButtonColors()
                            ) {
                                Text("M3U8", color = Color.White)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = forceLink, onCheckedChange = { forceLink = it })
                            Text("Forza utilizzo Link", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                        }
                    }
                    else -> {}
                }

                if (type != PortalType.TORRENT) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = useVpn, onCheckedChange = { useVpn = it })
                        Text("Usa VPN", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                    }
                    OutlinedTextField(
                        value = profile,
                        onValueChange = { profile = it },
                        label = { Text("Profilo", color = Color(0xFF888888)) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        textStyle = TextStyle(color = Color.White)
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = useCustomUa, onCheckedChange = { useCustomUa = it })
                        Text("Usa User Agent", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                    }
                    
                    if (useCustomUa) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Modello STB / User-Agent:",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFAAAAAA)
                        )
                        Spacer(Modifier.height(4.dp))

                        // Spinner a tendina personalizzato con stile coerente
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { uaMenu = true },
                                modifier = Modifier.fillMaxWidth(),
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                val currentLabel = UA_PRESETS.find { it.value == userAgent }?.label ?: "Seleziona modello STB..."
                                Text(
                                    text = currentLabel,
                                    modifier = Modifier.weight(1f),
                                    textAlign = TextAlign.Start,
                                    style = TextStyle(fontSize = 13.sp, color = Color.White)
                                )
                                Text("▼", color = Color(0xFF00E5FF), fontSize = 12.sp)
                            }

                            DropdownMenu(
                                expanded = uaMenu,
                                onDismissRequest = { uaMenu = false },
                                modifier = Modifier
                                    .fillMaxWidth(0.8f)
                                    .background(Color(0xFF111318))
                            ) {
                                UA_PRESETS.forEach { preset ->
                                    DropdownMenuItem(
                                        text = { 
                                            Text(
                                                text = preset.label,
                                                style = TextStyle(fontSize = 14.sp, color = Color.White)
                                            ) 
                                        },
                                        onClick = {
                                            userAgent = preset.value
                                            uaMenu = false
                                        }
                                    )
                                }
                            }
                        }

                        Spacer(Modifier.height(4.dp))

                        OutlinedTextField(
                            value = userAgent,
                            onValueChange = { userAgent = it },
                            label = { Text("Stringa User-Agent effettiva", color = Color(0xFF888888)) },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(fontSize = 11.sp, color = Color.White),
                            maxLines = 2
                        )
                    }
                }

                Spacer(Modifier.height(16.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    TextButton(onClick = onDismiss) {
                        Text("Annulla", color = Color(0xFFEF4444), fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = {
                        val base = existing ?: Portal(id = 0)
                        onSave(
                            base.copy(
                                name = name.trim(),
                                type = type,
                                url = url.trim(),
                                macAddress = mac.trim(),
                                server = server.trim(),
                                username = username.trim(),
                                password = password,
                                streamFormat = streamFormat,
                                forceStreamLink = forceLink,
                                useVpn = if (type == PortalType.TORRENT) false else useVpn,
                                profile = if (type == PortalType.TORRENT) "" else profile.trim(),
                                useCustomUserAgent = if (type == PortalType.TORRENT) false else useCustomUa,
                                userAgent = if (type == PortalType.TORRENT) "" else userAgent.trim()
                            )
                        )
                    }) {
                        Text(
                            text = if (existing == null) "Salva" else "OK",
                            color = Color(0xFF00E5FF),
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
