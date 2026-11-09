package com.portalstream.app.ui.player

import android.content.res.Configuration
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.expandVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.shrinkVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsPressedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Shadow
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.portalstream.app.network.UiAudioTrack

enum class SubtitleStylePreset(
    val id: String,
    val label: String,
    val textColor: Color,
    val bgColor: Color,
    val borderColor: Color
) {
    WHITE_OUTLINE("white", "Bianco Nitido", Color.White, Color.Transparent, Color.Black),
    CINEMA_YELLOW("yellow", "Giallo Cinema", Color(0xFFFFEA00), Color.Transparent, Color.Black),
    CYAN_NEON("cyan", "Ciano", Color(0xFF00E5FF), Color.Transparent, Color.Black),
    GREEN_NEON("green", "Verde Fluido", Color(0xFF00E676), Color.Transparent, Color.Black),
    DARK_BOX("box", "Sfondo Scuro", Color.White, Color(0xCC000000), Color.Transparent);

    companion object {
        fun fromId(id: String?): SubtitleStylePreset =
            values().firstOrNull { it.id == id } ?: WHITE_OUTLINE
    }
}

private enum class OptionSection {
    NONE, AUDIO, ASPECT_RATIO, SUBTITLES, SUBTITLE_STYLE
}

@Composable
fun PlayerOptionsMenu(
    visible: Boolean,
    onDismiss: () -> Unit,
    // Audio
    audioTracks: List<UiAudioTrack>,
    currentAudioTrackId: Int,
    currentAudioInfo: String,
    onAudioTrackSelected: (UiAudioTrack) -> Unit,
    // Proporzioni
    currentAspectRatio: String?,
    currentScale: Float,
    onAspectRatioSelected: (String?) -> Unit,
    // Sottotitoli OpenSubtitles
    openSubtitleItems: List<OpenSubtitleItem>,
    selectedOpenSubId: String,
    isLoadingSubtitles: Boolean,
    preferredSubLanguage: String,
    onPreferredSubLanguageChanged: (String) -> Unit,
    onOpenSubtitleSelected: (OpenSubtitleItem?) -> Unit,
    onRefreshSubtitles: () -> Unit,
    // Stile e Dimensione Sottotitoli
    subtitleSizeScale: Float,
    subtitleStyle: SubtitleStylePreset,
    onSubtitleSizeChanged: (Float) -> Unit,
    onSubtitleStyleChanged: (SubtitleStylePreset) -> Unit
) {
    if (!visible) return

    val configuration = LocalConfiguration.current
    val isLandscape = configuration.orientation == Configuration.ORIENTATION_LANDSCAPE

    var expandedSection by remember { mutableStateOf(OptionSection.NONE) }

    val activeRatioLabel = remember(currentAspectRatio, currentScale) {
        when {
            currentScale == 1.45f || currentAspectRatio == "FILL" -> "Riempi Schermo"
            currentScale == 1.10f || currentAspectRatio == "ZOOM110" -> "Zoom 110%"
            currentScale == 1.20f || currentAspectRatio == "ZOOM120" -> "Zoom 120%"
            currentScale == 1.25f || currentAspectRatio == "ZOOM125" -> "Zoom 125%"
            currentScale == 1.30f || currentAspectRatio == "ZOOM130" -> "Zoom 130%"
            currentScale == 1.35f || currentAspectRatio == "ZOOM135" -> "Zoom 135%"
            currentAspectRatio.isNullOrBlank() -> "Originale"
            else -> currentAspectRatio
        }
    }

    val activeSubLabel = remember(openSubtitleItems, selectedOpenSubId, preferredSubLanguage, isLoadingSubtitles) {
        val active = openSubtitleItems.firstOrNull { it.id == selectedOpenSubId }
        when {
            isLoadingSubtitles -> "Ricerca su OpenSubtitles (${preferredSubLanguage.uppercase()})..."
            selectedOpenSubId.isBlank() -> "Disattivati • Lingua: ${preferredSubLanguage.uppercase()}"
            active != null -> active.title
            else -> "OpenSubtitles (${preferredSubLanguage.uppercase()})"
        }
    }

    val activeStyleLabel = remember(subtitleSizeScale, subtitleStyle) {
        "${subtitleStyle.label} • ${(subtitleSizeScale * 100).toInt()}%"
    }

    Box(
        modifier = Modifier
            .fillMaxSize()
            .background(
                if (isLandscape) Color.Transparent else Color.Black.copy(alpha = 0.88f)
            )
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null
            ) {
                onDismiss()
            }
    ) {
        // In Orizzontale: finestra compatta a sinistra semitrasparente.
        // In Verticale: pannello a tutto schermo.
        val panelModifier = if (isLandscape) {
            Modifier
                .align(Alignment.CenterStart)
                .fillMaxHeight()
                .width(345.dp)
                .statusBarsPadding()
                .navigationBarsPadding()
                .padding(start = 12.dp, top = 10.dp, bottom = 10.dp)
                .clip(RoundedCornerShape(22.dp))
                .background(Color(0xC80F172A))
                .border(1.2.dp, Color(0xFF00E5FF).copy(alpha = 0.45f), RoundedCornerShape(22.dp))
        } else {
            Modifier
                .fillMaxSize()
                .background(Color(0xF20F172A))
                .statusBarsPadding()
                .navigationBarsPadding()
        }

        Column(
            modifier = panelModifier
                .clickable(
                    interactionSource = remember { MutableInteractionSource() },
                    indication = null
                ) { /* Mantiene il pannello aperto al tocco interno */ }
                .padding(horizontal = 16.dp, vertical = 14.dp)
        ) {
            // Intestazione
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Icon(
                        imageVector = Icons.Default.Tune,
                        contentDescription = null,
                        tint = Color(0xFF00E5FF),
                        modifier = Modifier.size(22.dp)
                    )
                    Spacer(modifier = Modifier.width(10.dp))
                    Text(
                        text = "Opzioni Riproduzione",
                        color = Color.White,
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold
                    )
                }
                IconButton(
                    onClick = onDismiss,
                    modifier = Modifier
                        .size(34.dp)
                        .background(Color.White.copy(alpha = 0.1f), CircleShape)
                ) {
                    Icon(
                        imageVector = Icons.Default.Close,
                        contentDescription = "Chiudi",
                        tint = Color.White,
                        modifier = Modifier.size(18.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .weight(1f, fill = false)
                    .verticalScroll(rememberScrollState()),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                // =====================================================
                // 1. BOTTONE SEZIONE AUDIO
                // =====================================================
                OptionSectionButton(
                    icon = Icons.Default.GraphicEq,
                    title = "Traccia Audio",
                    subtitle = currentAudioInfo,
                    isExpanded = expandedSection == OptionSection.AUDIO,
                    onClick = {
                        expandedSection = if (expandedSection == OptionSection.AUDIO) {
                            OptionSection.NONE
                        } else {
                            OptionSection.AUDIO
                        }
                    }
                )
                AnimatedVisibility(
                    visible = expandedSection == OptionSection.AUDIO,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    SectionCardContainer {
                        if (audioTracks.isEmpty()) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(8.dp)
                            ) {
                                Icon(
                                    imageVector = Icons.Default.VolumeOff,
                                    contentDescription = null,
                                    tint = Color.LightGray,
                                    modifier = Modifier.size(18.dp)
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                                Text(
                                    text = "Nessuna traccia audio rilevata",
                                    color = Color.LightGray,
                                    style = MaterialTheme.typography.bodySmall
                                )
                            }
                        } else {
                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                audioTracks.forEach { track ->
                                    SelectableOptionRow(
                                        label = track.formattedInfo,
                                        selected = track.id == currentAudioTrackId,
                                        icon = Icons.Default.Audiotrack,
                                        onClick = { onAudioTrackSelected(track) }
                                    )
                                }
                            }
                        }
                    }
                }

                // =====================================================
                // 2. BOTTONE SEZIONE PROPORZIONI VIDEO
                // =====================================================
                OptionSectionButton(
                    icon = Icons.Default.AspectRatio,
                    title = "Proporzioni Video",
                    subtitle = activeRatioLabel,
                    isExpanded = expandedSection == OptionSection.ASPECT_RATIO,
                    onClick = {
                        expandedSection = if (expandedSection == OptionSection.ASPECT_RATIO) {
                            OptionSection.NONE
                        } else {
                            OptionSection.ASPECT_RATIO
                        }
                    }
                )
                AnimatedVisibility(
                    visible = expandedSection == OptionSection.ASPECT_RATIO,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    val ratios = listOf(
                        "Originale" to null,
                        "16:9 (Standard HD)" to "16:9",
                        "4:3 (Classico)" to "4:3",
                        "21:9 (Cinematic)" to "21:9",
                        "1.85:1 (Film)" to "1.85:1",
                        "2.35:1 (CinemaScope)" to "2.35:1",
                        "Riempi Schermo" to "FILL",
                        "Zoom 110%" to "ZOOM110",
                        "Zoom 120%" to "ZOOM120",
                        "Zoom 125%" to "ZOOM125",
                        "Zoom 130%" to "ZOOM130",
                        "Zoom 135%" to "ZOOM135"
                    )
                    SectionCardContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                            ratios.forEach { (label, ratioCode) ->
                                val isSelected = when (ratioCode) {
                                    null -> currentAspectRatio == null && currentScale == 0f
                                    "FILL" -> currentScale == 1.45f || currentAspectRatio == "FILL"
                                    "ZOOM110" -> currentScale == 1.10f || currentAspectRatio == "ZOOM110"
                                    "ZOOM120" -> currentScale == 1.20f || currentAspectRatio == "ZOOM120"
                                    "ZOOM125" -> currentScale == 1.25f || currentAspectRatio == "ZOOM125"
                                    "ZOOM130" -> currentScale == 1.30f || currentAspectRatio == "ZOOM130"
                                    "ZOOM135" -> currentScale == 1.35f || currentAspectRatio == "ZOOM135"
                                    else -> currentAspectRatio == ratioCode && currentScale == 0f
                                }
                                SelectableOptionRow(
                                    label = label,
                                    selected = isSelected,
                                    icon = if (ratioCode?.startsWith("ZOOM") == true || ratioCode == "FILL") {
                                        Icons.Default.CropFree
                                    } else {
                                        Icons.Default.Tv
                                    },
                                    onClick = { onAspectRatioSelected(ratioCode) }
                                )
                            }
                        }
                    }
                }

                // =====================================================
                // 3. BOTTONE SEZIONE SOTTOTITOLI ESTERNI (OPENSUBTITLES)
                // =====================================================
                OptionSectionButton(
                    icon = Icons.Default.Subtitles,
                    title = "Sottotitoli (OpenSubtitles)",
                    subtitle = activeSubLabel,
                    isExpanded = expandedSection == OptionSection.SUBTITLES,
                    onClick = {
                        expandedSection = if (expandedSection == OptionSection.SUBTITLES) {
                            OptionSection.NONE
                        } else {
                            OptionSection.SUBTITLES
                        }
                    }
                )
                AnimatedVisibility(
                    visible = expandedSection == OptionSection.SUBTITLES,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    val languages = listOf(
                        "it" to "IT",
                        "en" to "EN",
                        "es" to "ES",
                        "fr" to "FR",
                        "de" to "DE"
                    )
                    SectionCardContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.Language,
                                        contentDescription = null,
                                        tint = Color(0xFF00E5FF),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Lingua OpenSubtitles",
                                        color = Color(0xFF00E5FF),
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.Bold
                                    )
                                }

                                IconButton(
                                    onClick = onRefreshSubtitles,
                                    modifier = Modifier.size(28.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.Refresh,
                                        contentDescription = "Ricarica Sottotitoli",
                                        tint = Color(0xFF00E5FF),
                                        modifier = Modifier.size(18.dp)
                                    )
                                }
                            }

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                languages.forEach { (code, label) ->
                                    val isLangSelected = preferredSubLanguage.equals(code, ignoreCase = true)
                                    CompactTogglePill(
                                        text = label,
                                        selected = isLangSelected,
                                        onClick = { onPreferredSubLanguageChanged(code) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))

                            SelectableOptionRow(
                                label = "Disattiva Sottotitoli",
                                selected = selectedOpenSubId.isBlank(),
                                icon = Icons.Default.SubtitlesOff,
                                onClick = { onOpenSubtitleSelected(null) }
                            )

                            if (isLoadingSubtitles) {
                                Row(
                                    modifier = Modifier
                                        .fillMaxWidth()
                                        .padding(vertical = 8.dp),
                                    horizontalArrangement = Arrangement.Center,
                                    verticalAlignment = Alignment.CenterVertically
                                ) {
                                    CircularProgressIndicator(
                                        color = Color(0xFF00E5FF),
                                        strokeWidth = 2.dp,
                                        modifier = Modifier.size(18.dp)
                                    )
                                    Spacer(modifier = Modifier.width(10.dp))
                                    Text(
                                        text = "Ricerca sottotitoli su OpenSubtitles...",
                                        color = Color.LightGray,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            } else if (openSubtitleItems.isEmpty()) {
                                Row(
                                    verticalAlignment = Alignment.CenterVertically,
                                    modifier = Modifier.padding(horizontal = 4.dp, vertical = 6.dp)
                                ) {
                                    Icon(
                                        imageVector = Icons.Default.CloudDownload,
                                        contentDescription = null,
                                        tint = Color.LightGray,
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                    Text(
                                        text = "Nessun sottotitolo trovato per questa lingua. Tocca l'icona aggiorna o cambia lingua.",
                                        color = Color.LightGray,
                                        style = MaterialTheme.typography.bodySmall
                                    )
                                }
                            } else {
                                openSubtitleItems.forEach { subItem ->
                                    SelectableOptionRow(
                                        label = subItem.title,
                                        selected = subItem.id == selectedOpenSubId,
                                        icon = Icons.Default.ClosedCaption,
                                        onClick = { onOpenSubtitleSelected(subItem) }
                                    )
                                }
                            }
                        }
                    }
                }

                // =====================================================
                // 4. BOTTONE SEZIONE STILE E DIMENSIONE CARATTERI
                // =====================================================
                OptionSectionButton(
                    icon = Icons.Default.FormatSize,
                    title = "Stile e Dimensione Sottotitoli",
                    subtitle = activeStyleLabel,
                    isExpanded = expandedSection == OptionSection.SUBTITLE_STYLE,
                    onClick = {
                        expandedSection = if (expandedSection == OptionSection.SUBTITLE_STYLE) {
                            OptionSection.NONE
                        } else {
                            OptionSection.SUBTITLE_STYLE
                        }
                    }
                )
                AnimatedVisibility(
                    visible = expandedSection == OptionSection.SUBTITLE_STYLE,
                    enter = expandVertically() + fadeIn(),
                    exit = shrinkVertically() + fadeOut()
                ) {
                    SectionCardContainer {
                        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                            // Anteprima Live fedele al 100% al rendering sul video
                            Box(
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clip(RoundedCornerShape(10.dp))
                                    .background(Color(0xFF1E293B))
                                    .padding(vertical = 12.dp, horizontal = 8.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                val hasDarkBox = subtitleStyle == SubtitleStylePreset.DARK_BOX
                                Box(
                                    modifier = Modifier
                                        .clip(RoundedCornerShape(6.dp))
                                        .background(subtitleStyle.bgColor)
                                        .padding(horizontal = 10.dp, vertical = 4.dp)
                                ) {
                                    Text(
                                        text = "Anteprima Sottotitoli",
                                        color = subtitleStyle.textColor,
                                        fontSize = (16f * subtitleSizeScale).sp,
                                        fontWeight = FontWeight.Bold,
                                        style = TextStyle(
                                            shadow = if (!hasDarkBox) {
                                                Shadow(
                                                    color = Color.Black,
                                                    offset = Offset(2f, 2f),
                                                    blurRadius = 4f
                                                )
                                            } else null
                                        )
                                    )
                                }
                            }

                            // Regolazione Dimensione Caratteri
                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.SpaceBetween,
                                verticalAlignment = Alignment.CenterVertically
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Icon(
                                        imageVector = Icons.Default.TextFields,
                                        contentDescription = null,
                                        tint = Color(0xFF00E5FF),
                                        modifier = Modifier.size(16.dp)
                                    )
                                    Spacer(modifier = Modifier.width(6.dp))
                                    Text(
                                        text = "Dimensione Caratteri",
                                        color = Color.White,
                                        style = MaterialTheme.typography.labelMedium,
                                        fontWeight = FontWeight.SemiBold
                                    )
                                }
                                Text(
                                    text = "${(subtitleSizeScale * 100).toInt()}%",
                                    color = Color(0xFF00E5FF),
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.Bold
                                )
                            }

                            Slider(
                                value = subtitleSizeScale,
                                onValueChange = onSubtitleSizeChanged,
                                valueRange = 0.75f..1.75f,
                                colors = SliderDefaults.colors(
                                    thumbColor = Color(0xFF00E5FF),
                                    activeTrackColor = Color(0xFF00E5FF),
                                    inactiveTrackColor = Color.White.copy(alpha = 0.2f)
                                )
                            )

                            Row(
                                modifier = Modifier.fillMaxWidth(),
                                horizontalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val sizePresets = listOf(
                                    "Piccolo" to 0.80f,
                                    "Normale" to 1.0f,
                                    "Grande" to 1.25f,
                                    "Max" to 1.55f
                                )
                                sizePresets.forEach { (lbl, scaleVal) ->
                                    CompactTogglePill(
                                        text = lbl,
                                        selected = kotlin.math.abs(subtitleSizeScale - scaleVal) < 0.08f,
                                        onClick = { onSubtitleSizeChanged(scaleVal) },
                                        modifier = Modifier.weight(1f)
                                    )
                                }
                            }

                            HorizontalDivider(color = Color.White.copy(alpha = 0.12f))

                            // Regolazione Stile e Colore Caratteri
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                Icon(
                                    imageVector = Icons.Default.Palette,
                                    contentDescription = null,
                                    tint = Color(0xFF00E5FF),
                                    modifier = Modifier.size(16.dp)
                                )
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = "Stile e Colore Caratteri",
                                    color = Color.White,
                                    style = MaterialTheme.typography.labelMedium,
                                    fontWeight = FontWeight.SemiBold
                                )
                            }

                            Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                                SubtitleStylePreset.values().forEach { preset ->
                                    SelectableOptionRow(
                                        label = preset.label,
                                        selected = subtitleStyle == preset,
                                        icon = Icons.Default.Style,
                                        onClick = { onSubtitleStyleChanged(preset) }
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun OptionSectionButton(
    icon: ImageVector,
    title: String,
    subtitle: String,
    isExpanded: Boolean,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()
    val scale by animateFloatAsState(
        targetValue = if (isPressed) 0.97f else 1f,
        label = "button_press_scale"
    )

    val borderColor = when {
        isPressed -> Color(0xFF00E5FF)
        isExpanded -> Color(0xFF00E5FF).copy(alpha = 0.85f)
        else -> Color.White.copy(alpha = 0.14f)
    }

    val containerColor = when {
        isPressed -> Color(0xFF00E5FF).copy(alpha = 0.22f)
        isExpanded -> Color(0xFF00E5FF).copy(alpha = 0.14f)
        else -> Color.White.copy(alpha = 0.07f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .scale(scale)
            .clip(RoundedCornerShape(14.dp))
            .background(containerColor)
            .border(1.2.dp, borderColor, RoundedCornerShape(14.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 14.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(38.dp)
                .clip(RoundedCornerShape(10.dp))
                .background(
                    if (isExpanded || isPressed) Color(0xFF00E5FF).copy(alpha = 0.25f)
                    else Color.White.copy(alpha = 0.1f)
                ),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = icon,
                contentDescription = title,
                tint = if (isExpanded || isPressed) Color(0xFF00E5FF) else Color.White,
                modifier = Modifier.size(20.dp)
            )
        }

        Spacer(modifier = Modifier.width(12.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                color = Color.White,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
            Text(
                text = subtitle,
                color = if (isExpanded) Color(0xFF00E5FF) else Color.LightGray,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }

        Icon(
            imageVector = if (isExpanded) Icons.Default.KeyboardArrowUp else Icons.Default.KeyboardArrowDown,
            contentDescription = null,
            tint = if (isExpanded) Color(0xFF00E5FF) else Color.LightGray
        )
    }
}

@Composable
private fun SectionCardContainer(content: @Composable ColumnScope.() -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(14.dp))
            .background(Color.Black.copy(alpha = 0.45f))
            .border(1.dp, Color.White.copy(alpha = 0.1f), RoundedCornerShape(14.dp))
            .padding(12.dp),
        content = content
    )
}

@Composable
private fun SelectableOptionRow(
    label: String,
    selected: Boolean,
    icon: ImageVector,
    onClick: () -> Unit
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    val bgColor = when {
        isPressed -> Color(0xFF00E5FF).copy(alpha = 0.28f)
        selected -> Color(0xFF00E5FF).copy(alpha = 0.18f)
        else -> Color.White.copy(alpha = 0.05f)
    }

    val borderCol = if (selected || isPressed) Color(0xFF00E5FF) else Color.Transparent

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(10.dp))
            .background(bgColor)
            .border(1.dp, borderCol, RoundedCornerShape(10.dp))
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(horizontal = 12.dp, vertical = 10.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = if (selected) Color(0xFF00E5FF) else Color.LightGray,
            modifier = Modifier.size(18.dp)
        )
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = label,
            color = if (selected) Color.White else Color.LightGray,
            style = MaterialTheme.typography.bodySmall,
            fontWeight = if (selected) FontWeight.Bold else FontWeight.Normal,
            modifier = Modifier.weight(1f)
        )
        if (selected) {
            Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = "Selezionato",
                tint = Color(0xFF00E5FF),
                modifier = Modifier.size(18.dp)
            )
        }
    }
}

@Composable
private fun CompactTogglePill(
    text: String,
    selected: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val interactionSource = remember { MutableInteractionSource() }
    val isPressed by interactionSource.collectIsPressedAsState()

    Box(
        modifier = modifier
            .clip(RoundedCornerShape(8.dp))
            .background(
                when {
                    isPressed -> Color(0xFF00E5FF).copy(alpha = 0.35f)
                    selected -> Color(0xFF00E5FF).copy(alpha = 0.22f)
                    else -> Color.White.copy(alpha = 0.08f)
                }
            )
            .border(
                width = 1.dp,
                color = if (selected || isPressed) Color(0xFF00E5FF) else Color.White.copy(alpha = 0.15f),
                shape = RoundedCornerShape(8.dp)
            )
            .clickable(
                interactionSource = interactionSource,
                indication = null,
                onClick = onClick
            )
            .padding(vertical = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = text,
            color = if (selected) Color(0xFF00E5FF) else Color.White,
            style = MaterialTheme.typography.labelSmall,
            fontWeight = FontWeight.Bold
        )
    }
}
                    
