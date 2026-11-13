package com.portalstream.app.ui.home

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
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
import androidx.compose.ui.draw.clip
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

private val NeonCyan = Color(0xFF00E5FF)
private val DarkBg = Color(0xFF111318)
private val CardBg = Color(0xFF161922)

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
                    color = NeonCyan,
                    shape = RoundedCornerShape(24.dp)
                ),
            shape = RoundedCornerShape(24.dp),
            color = DarkBg,
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

    val textFieldColors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = NeonCyan,
        unfocusedBorderColor = Color(0xFF334155),
        focusedLabelColor = NeonCyan,
        unfocusedLabelColor = Color(0xFF888888),
        cursorColor = NeonCyan,
        focusedTextColor = Color.White,
        unfocusedTextColor = Color.White
    )

    val checkboxColors = CheckboxDefaults.colors(
        checkedColor = NeonCyan,
        uncheckedColor = Color(0xFF64748B),
        checkmarkColor = Color.Black
    )

    Dialog(onDismissRequest = onDismiss) {
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ){
        Surface(
            modifier = Modifier
                .width(350.dp)
                .border(
                    width = 1.5.dp,
                    color = NeonCyan,
                    shape = RoundedCornerShape(24.dp)
                ),
            shape = RoundedCornerShape(24.dp),
            color = DarkBg,
            tonalElevation = 6.dp
        ) {
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 18.dp, vertical = 16.dp)
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

                Spacer(Modifier.height(12.dp))

                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome Playlist (opzionale)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true,
                    colors = textFieldColors
                )
                Spacer(Modifier.height(8.dp))

                when (type) {
                    PortalType.M3U -> {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text("Link della playlist") },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(fontSize = 13.sp),
                            singleLine = true,
                            colors = textFieldColors
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            OutlinedButton(
                                onClick = { clipboard.getText()?.text?.let { url = it } },
                                border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.6f))
                            ) {
                                Text("Incolla", color = Color.White)
                            }
                            OutlinedButton(
                                onClick = onPickFile,
                                border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.6f))
                            ) {
                                Text("Sfoglia file", color = Color.White)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    PortalType.TORRENT -> {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text("InfoHash o Magnet") },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(fontSize = 13.sp),
                            maxLines = 2,
                            colors = textFieldColors
                        )
                        Spacer(Modifier.height(6.dp))
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.SpaceBetween
                        ) {
                            OutlinedButton(
                                onClick = { clipboard.getText()?.text?.let { url = it } },
                                border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.6f))
                            ) {
                                Text("Incolla", color = Color.White)
                            }
                            OutlinedButton(
                                onClick = onPickFile,
                                border = BorderStroke(1.dp, NeonCyan.copy(alpha = 0.6f))
                            ) {
                                Text("Sfoglia file", color = Color.White)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                    }
                    PortalType.MAG -> {
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            label = { Text("Link Portale MAG") },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(fontSize = 13.sp),
                            maxLines = 2,
                            colors = textFieldColors
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = mac,
                            onValueChange = { mac = it },
                            label = { Text("MAC Address") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = textFieldColors
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    PortalType.XSTREAM -> {
                        OutlinedTextField(
                            value = server,
                            onValueChange = { server = it },
                            label = { Text("Server:porta") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = textFieldColors
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("Utente") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = textFieldColors
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            colors = textFieldColors,
                            visualTransformation =
                                if (showPassword) VisualTransformation.None
                                else PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { showPassword = !showPassword }) {
                                    Text(if (showPassword) "Nascondi" else "Mostra", color = NeonCyan)
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
                                border = BorderStroke(1.dp, if (streamFormat == "ts") NeonCyan else Color(0xFF334155)),
                                colors = if (streamFormat == "ts")
                                    ButtonDefaults.outlinedButtonColors(containerColor = NeonCyan.copy(alpha = 0.2f))
                                else ButtonDefaults.outlinedButtonColors()
                            ) {
                                Text("TS", color = Color.White)
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { streamFormat = "m3u8" },
                                border = BorderStroke(1.dp, if (streamFormat == "m3u8") NeonCyan else Color(0xFF334155)),
                                colors = if (streamFormat == "m3u8")
                                    ButtonDefaults.outlinedButtonColors(containerColor = NeonCyan.copy(alpha = 0.2f))
                                else ButtonDefaults.outlinedButtonColors()
                            ) {
                                Text("M3U8", color = Color.White)
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = forceLink, onCheckedChange = { forceLink = it }, colors = checkboxColors)
                            Text("Forza utilizzo Link", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                        }
                    }
                    else -> {}
                }

                if (type != PortalType.TORRENT) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = useVpn, onCheckedChange = { useVpn = it }, colors = checkboxColors)
                        Text("Usa VPN", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                    }
                    OutlinedTextField(
                        value = profile,
                        onValueChange = { profile = it },
                        label = { Text("Profilo") },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true,
                        colors = textFieldColors
                    )
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = useCustomUa, onCheckedChange = { useCustomUa = it }, colors = checkboxColors)
                        Text("Usa User Agent", style = MaterialTheme.typography.bodyMedium, color = Color.White)
                    }
                    
                    if (useCustomUa) {
                        Spacer(Modifier.height(4.dp))
                        Text(
                            text = "Modello STB / User-Agent:",
                            style = MaterialTheme.typography.bodySmall,
                            color = Color(0xFFAAAAAA)
                        )
                        Spacer(Modifier.height(6.dp))

                        // Bottone Spinner stile PortalCheck (sfondo #161922 + bordo ciano neon)
                        Box(modifier = Modifier.fillMaxWidth()) {
                            OutlinedButton(
                                onClick = { uaMenu = true },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .height(46.dp),
                                shape = RoundedCornerShape(10.dp),
                                border = BorderStroke(1.5.dp, NeonCyan),
                                colors = ButtonDefaults.outlinedButtonColors(
                                    containerColor = CardBg
                                )
                            ) {
                                val currentLabel = UA_PRESETS.find { it.value == userAgent }?.label ?: "Seleziona modello STB..."
                                Text(
                                    text = currentLabel,
                                    modifier = Modifier.weight(1f),
                                    textAlign = TextAlign.Start,
                                    style = TextStyle(
                                        fontSize = 13.sp,
                                        fontWeight = FontWeight.SemiBold,
                                        color = Color.White
                                    )
                                )
                                Text("▼", color = NeonCyan, fontSize = 12.sp)
                            }

                            // Tendina staccata dal fondo con bordo Neon Ciano e voci animate al tocco
                            DropdownMenu(
                                expanded = uaMenu,
                                onDismissRequest = { uaMenu = false },
                                modifier = Modifier
                                    .fillMaxWidth(0.78f)
                                    .heightIn(max = 240.dp)
                                    .background(CardBg, RoundedCornerShape(12.dp))
                                    .border(1.5.dp, NeonCyan, RoundedCornerShape(12.dp))
                                    .padding(vertical = 4.dp, horizontal = 6.dp)
                            ) {
                                UA_PRESETS.forEach { preset ->
                                    val isSelected = userAgent == preset.value
                                    val interactionSource = remember { MutableInteractionSource() }
                                    val isPressed by interactionSource.collectIsPressedAsState()

                                    // Animazione fluida dell'illuminazione al tocco (come spinner_item_selector.xml)
                                    val itemBgColor by animateColorAsState(
                                        targetValue = when {
                                            isPressed -> NeonCyan
                                            isSelected -> NeonCyan.copy(alpha = 0.22f)
                                            else -> Color.Transparent
                                        },
                                        animationSpec = tween(durationMillis = 120),
                                        label = "itemBg"
                                    )

                                    val itemTextColor by animateColorAsState(
                                        targetValue = when {
                                            isPressed -> Color.Black
                                            isSelected -> NeonCyan
                                            else -> Color.White
                                        },
                                        animationSpec = tween(durationMillis = 120),
                                        label = "itemText"
                                    )

                                    Box(
                                        modifier = Modifier
                                            .fillMaxWidth()
                                            .padding(vertical = 2.dp)
                                            .clip(RoundedCornerShape(8.dp))
                                            .background(itemBgColor)
                                            .then(
                                                if (isSelected && !isPressed) {
                                                    Modifier.border(1.dp, NeonCyan.copy(alpha = 0.7f), RoundedCornerShape(8.dp))
                                                } else Modifier
                                            )
                                            .clickable(
                                                interactionSource = interactionSource,
                                                indication = null
                                            ) {
                                                userAgent = preset.value
                                                uaMenu = false
                                            }
                                            .padding(horizontal = 12.dp, vertical = 11.dp)
                                    ) {
                                        Row(
                                            modifier = Modifier.fillMaxWidth(),
                                            horizontalArrangement = Arrangement.SpaceBetween,
                                            verticalAlignment = Alignment.CenterVertically
                                        ) {
                                            Text(
                                                text = preset.label,
                                                style = TextStyle(
                                                    fontSize = 14.sp,
                                                    fontWeight = if (isPressed || isSelected) FontWeight.Bold else FontWeight.Normal,
                                                    color = itemTextColor
                                                )
                                            )
                                            if (isSelected) {
                                                Text(
                                                    text = "●",
                                                    fontSize = 10.sp,
                                                    color = if (isPressed) Color.Black else NeonCyan
                                                )
                                            }
                                        }
                                    }
                                }
                            }
                        }

                        Spacer(Modifier.height(8.dp))

                        OutlinedTextField(
                            value = userAgent,
                            onValueChange = { userAgent = it },
                            label = { Text("Stringa User-Agent effettiva") },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(fontSize = 11.sp),
                            maxLines = 2,
                            colors = textFieldColors
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
                            color = NeonCyan,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }
    }
}
