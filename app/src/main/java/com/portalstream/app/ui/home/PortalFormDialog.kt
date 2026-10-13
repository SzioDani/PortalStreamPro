package com.portalstream.app.ui.home

import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalClipboardManager
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portalstream.app.data.Portal
import com.portalstream.app.data.PortalType

private data class UaPreset(val label: String, val value: String)

private val UA_PRESETS = listOf(
    UaPreset(
        "MAG200",
        "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 MAG200 stbapp ver: 4.3.1939"
    ),
    UaPreset(
        "MAG250",
        "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 MAG250 stbapp ver: 4.3.1939"
    ),
    UaPreset(
        "MAG322",
        "Mozilla/5.0 (QtEmbedded; U; Linux; C) AppleWebKit/533.3 MAG322 stbapp ver: 4.3.1939"
    ),
    UaPreset(
        "Browser generico",
        "Mozilla/5.0 (Windows NT 10.0; Win64; x64) AppleWebKit/537.36 (KHTML, like Gecko) Chrome/120.0 Safari/537.36"
    )
)

@Composable
fun AddPortalTypeDialog(
    onSelect: (PortalType) -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Aggiungi playlist") },
        text = {
            Column {
                TextButton(
                    onClick = { onSelect(PortalType.M3U) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("FILE O LINK PLAYLIST")
                }
                TextButton(
                    onClick = { onSelect(PortalType.MAG) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("MAG PORTALE")
                }
                TextButton(
                    onClick = { onSelect(PortalType.XSTREAM) },
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text("XTREAM CODES PORTAL")
                }
            }
        },
        confirmButton = {},
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annulla") }
        }
    )
}

@Composable
fun PortalFormDialog(
    existing: Portal?,
    type: PortalType,
    onDismiss: () -> Unit,
    onSave: (Portal) -> Unit,
    onPickFile: () -> Unit
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

    val title = when (type) {
        PortalType.M3U -> "File o Link Playlist"
        PortalType.MAG -> "MAG Portale"
        PortalType.XSTREAM -> "Xtream Codes Portal"
        else -> "Portale"
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title) },
        text = {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    label = { Text("Nome Playlist (opzionale)") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
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
                            maxLines = 2
                        )
                        Spacer(Modifier.height(4.dp))
                        Row {
                            OutlinedButton(onClick = {
                                clipboard.getText()?.text?.let { url = it }
                            }) {
                                Text("Incolla")
                            }
                            Spacer(Modifier.width(8.dp))
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
                            label = { Text("Link Portale MAG") },
                            modifier = Modifier.fillMaxWidth(),
                            textStyle = TextStyle(fontSize = 13.sp),
                            maxLines = 2
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = mac,
                            onValueChange = { mac = it },
                            label = { Text("MAC Address") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(Modifier.height(4.dp))
                    }
                    PortalType.XSTREAM -> {
                        OutlinedTextField(
                            value = server,
                            onValueChange = { server = it },
                            label = { Text("Server:porta") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = username,
                            onValueChange = { username = it },
                            label = { Text("Utente") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true
                        )
                        Spacer(Modifier.height(8.dp))
                        OutlinedTextField(
                            value = password,
                            onValueChange = { password = it },
                            label = { Text("Password") },
                            modifier = Modifier.fillMaxWidth(),
                            singleLine = true,
                            visualTransformation =
                                if (showPassword) VisualTransformation.None
                                else PasswordVisualTransformation(),
                            trailingIcon = {
                                TextButton(onClick = { showPassword = !showPassword }) {
                                    Text(if (showPassword) "Nascondi" else "Mostra")
                                }
                            }
                        )
                        Spacer(Modifier.height(8.dp))
                        Text(
                            text = "Formato Streams",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Row {
                            OutlinedButton(
                                onClick = { streamFormat = "ts" },
                                colors = if (streamFormat == "ts")
                                    ButtonDefaults.outlinedButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer
                                    )
                                else ButtonDefaults.outlinedButtonColors()
                            ) {
                                Text("TS")
                            }
                            Spacer(Modifier.width(8.dp))
                            OutlinedButton(
                                onClick = { streamFormat = "m3u8" },
                                colors = if (streamFormat == "m3u8")
                                    ButtonDefaults.outlinedButtonColors(
                                        containerColor = MaterialTheme.colorScheme.primaryContainer
                                    )
                                else ButtonDefaults.outlinedButtonColors()
                            ) {
                                Text("M3U8")
                            }
                        }
                        Spacer(Modifier.height(4.dp))
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Checkbox(checked = forceLink, onCheckedChange = { forceLink = it })
                            Text("Forza utilizzo Link", style = MaterialTheme.typography.bodyMedium)
                        }
                    }
                    else -> {}
                }

                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useVpn, onCheckedChange = { useVpn = it })
                    Text("Usa VPN", style = MaterialTheme.typography.bodyMedium)
                }
                OutlinedTextField(
                    value = profile,
                    onValueChange = { profile = it },
                    label = { Text("Profilo") },
                    modifier = Modifier.fillMaxWidth(),
                    singleLine = true
                )
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = useCustomUa, onCheckedChange = { useCustomUa = it })
                    Text("Usa User Agent", style = MaterialTheme.typography.bodyMedium)
                }
                if (useCustomUa) {
                    OutlinedTextField(
                        value = userAgent,
                        onValueChange = { userAgent = it },
                        label = { Text("Inserisci o seleziona User-Agent") },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = TextStyle(fontSize = 12.sp),
                        maxLines = 2
                    )
                    Spacer(Modifier.height(4.dp))
                    OutlinedButton(onClick = { uaMenu = true }) {
                        Text("Seleziona preset")
                    }
                    DropdownMenu(expanded = uaMenu, onDismissRequest = { uaMenu = false }) {
                        UA_PRESETS.forEach { preset ->
                            DropdownMenuItem(
                                text = { Text(preset.label) },
                                onClick = {
                                    userAgent = preset.value
                                    uaMenu = false
                                }
                            )
                        }
                    }
                }
            }
        },
        confirmButton = {
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
                        useVpn = useVpn,
                        profile = profile.trim(),
                        useCustomUserAgent = useCustomUa,
                        userAgent = userAgent.trim()
                    )
                )
            }) {
                Text(if (existing == null) "Salva" else "OK")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Annulla") }
        }
    )
}
