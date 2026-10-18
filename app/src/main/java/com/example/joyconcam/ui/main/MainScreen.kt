package com.example.joyconcam.ui.main

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.joyconcam.data.ColorPalette
import com.example.joyconcam.data.IrResolution

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    viewModel: JoyConViewModel = viewModel()
) {
    val state by viewModel.state.collectAsStateWithLifecycle()
    val frame by viewModel.latestFrame.collectAsStateWithLifecycle()

    val scrollState = rememberScrollState()
    var isSmoothingEnabled by remember { mutableStateOf(true) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .verticalScroll(scrollState),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // App Header
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column {
                Text(
                    text = "Joy-Con IR Kamera",
                    fontSize = 24.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
                Text(
                    text = if (state.connectedDeviceName != null) {
                        "Verbunden: ${state.connectedDeviceName}"
                    } else {
                        "Nintendo Switch Joy-Con (R)"
                    },
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            // Connection Status Pill
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (state.isStreaming) Color(0xFF1B5E20) else MaterialTheme.colorScheme.surfaceVariant,
                modifier = Modifier.padding(start = 8.dp)
            ) {
                Text(
                    text = if (state.isStreaming) "LIVE" else "BEREIT",
                    color = if (state.isStreaming) Color(0xFF81C784) else MaterialTheme.colorScheme.onSurfaceVariant,
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp)
                )
            }
        }

        // Camera Viewport Card
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(280.dp),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = Color.Black)
        ) {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                if (frame != null) {
                    Image(
                        bitmap = frame!!.asImageBitmap(),
                        contentDescription = "Joy-Con IR Live Stream",
                        modifier = Modifier.fillMaxSize(),
                        contentScale = ContentScale.Fit,
                        filterQuality = if (isSmoothingEnabled) androidx.compose.ui.graphics.FilterQuality.Medium else androidx.compose.ui.graphics.FilterQuality.None
                    )
                } else {
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.Center
                    ) {
                        Text(
                            text = "📷",
                            fontSize = 48.sp
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            text = state.statusText,
                            color = Color.Gray,
                            fontSize = 14.sp
                        )
                    }
                }

                // Overlay Info Badge
                if (state.isStreaming) {
                    Row(
                        modifier = Modifier
                            .align(Alignment.TopStart)
                            .padding(8.dp)
                            .background(Color(0xAA000000), RoundedCornerShape(8.dp))
                            .padding(horizontal = 8.dp, vertical = 4.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Text(
                            text = "${state.currentResolution.width}x${state.currentResolution.height}",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = "•",
                            color = Color.Gray,
                            fontSize = 12.sp
                        )
                        Text(
                            text = "${"%.1f".format(state.fps)} FPS",
                            color = Color(0xFF69F0AE),
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        // Main Action Buttons
        if (state.isStreaming) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = { frame?.let { viewModel.saveSnapshot(it) } },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.secondary),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "📸 Foto",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }

                Button(
                    onClick = { viewModel.stopStream() },
                    modifier = Modifier
                        .weight(1f)
                        .height(52.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
                    shape = RoundedCornerShape(12.dp)
                ) {
                    Text(
                        text = "⏹ Stoppen",
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }
        } else {
            Button(
                onClick = { viewModel.startStream() },
                modifier = Modifier
                    .fillMaxWidth()
                    .height(52.dp),
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text(
                    text = "▶ IR Live-Stream starten",
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold
                )
            }
        }

        // Status Banner
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(12.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = "Status: ",
                    fontWeight = FontWeight.Bold,
                    fontSize = 13.sp
                )
                Text(
                    text = state.statusText,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }

        // Resolution Section
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Auflösung & Bildrate",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .horizontalScroll(rememberScrollState()),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    IrResolution.RES_30_40 to "30x40 (Flüssig ~15 FPS)",
                    IrResolution.RES_60_80 to "60x80 (~4 FPS)",
                    IrResolution.RES_120_160 to "120x160 (~1 FPS)",
                    IrResolution.RES_240_320 to "240x320 (Foto ~0.2 FPS)"
                ).forEach { (res, label) ->
                    FilterChip(
                        selected = state.currentResolution == res,
                        onClick = { viewModel.setResolution(res) },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }
        }

        // Palette Section
        Column(modifier = Modifier.fillMaxWidth()) {
            Text(
                text = "Farbfilter / Wärmebild",
                fontWeight = FontWeight.Bold,
                fontSize = 14.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(
                    ColorPalette.IRONBOW to "Wärmebild",
                    ColorPalette.NIGHT_VISION to "Nachtsicht",
                    ColorPalette.GRAYSCALE to "Graustufen",
                    ColorPalette.RED to "Infrarot"
                ).forEach { (pal, label) ->
                    FilterChip(
                        selected = state.palette == pal,
                        onClick = { viewModel.setPalette(pal) },
                        label = { Text(label, fontSize = 12.sp) }
                    )
                }
            }
        }

        // Smoothing Section
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = "Bilineare Bild-Glättung",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Text(
                    text = if (isSmoothingEnabled) "Weiches Bild (Anti-Aliasing an)" else "Retro Pixel-Look (Anti-Aliasing aus)",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = isSmoothingEnabled,
                onCheckedChange = { isSmoothingEnabled = it }
            )
        }

        // IR LEDs & Camera Tuning
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant)
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = "IR-Scheinwerfer & Sensor",
                    fontWeight = FontWeight.Bold,
                    fontSize = 14.sp
                )
                Spacer(modifier = Modifier.height(10.dp))

                // LEDs
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    listOf(
                        0x00 to "LEDs Aus",
                        0x01 to "Schmal",
                        0x02 to "Weit",
                        0x03 to "Beide LEDs"
                    ).forEach { (ledVal, label) ->
                        FilterChip(
                            selected = state.irLeds == ledVal,
                            onClick = { viewModel.setLeds(ledVal) },
                            label = { Text(label, fontSize = 11.sp) }
                        )
                    }
                }

                Spacer(modifier = Modifier.height(10.dp))

                // Exposure
                Text(
                    text = "Belichtungszeit: ${if (state.exposureUs >= 2000) "2000 μs (MAX Hardware)" else "${state.exposureUs} μs"}",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = state.exposureUs.toFloat(),
                    onValueChange = { viewModel.setExposure(it.toInt()) },
                    valueRange = 50f..2000f
                )

                // Digital Gain
                Text(
                    text = "Digitaler Gain (Verstärkung): ${state.digitalGain}x",
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Slider(
                    value = state.digitalGain.toFloat(),
                    onValueChange = { viewModel.setDigitalGain(it.toInt()) },
                    valueRange = 1f..64f
                )
            }
        }

        // Live Log Box
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .height(160.dp),
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E1E))
        ) {
            Column(modifier = Modifier.padding(8.dp)) {
                Text(
                    text = "Live Bluetooth & Protokoll-Log",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = Color.LightGray
                )
                Spacer(modifier = Modifier.height(4.dp))
                val logListState = rememberLazyListState()
                LaunchedEffect(state.logs.size) {
                    if (state.logs.isNotEmpty()) {
                        logListState.animateScrollToItem(state.logs.size - 1)
                    }
                }
                LazyColumn(
                    state = logListState,
                    modifier = Modifier.fillMaxSize()
                ) {
                    items(state.logs) { logLine ->
                        Text(
                            text = logLine,
                            color = Color(0xFF81C784),
                            fontSize = 11.sp,
                            fontFamily = FontFamily.Monospace,
                            modifier = Modifier.padding(vertical = 1.dp)
                        )
                    }
                }
            }
        }

        Spacer(modifier = Modifier.height(24.dp))
    }
}
