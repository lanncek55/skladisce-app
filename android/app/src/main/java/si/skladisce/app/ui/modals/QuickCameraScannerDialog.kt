package si.skladisce.app.ui.modals

import android.Manifest
import android.content.pm.PackageManager
import android.os.SystemClock
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.ZoomIn
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import si.skladisce.app.scanner.CameraOcrAnalyzer
import si.skladisce.app.scanner.NativeCameraController
import si.skladisce.app.scanner.NativeCameraPreview
import si.skladisce.app.scanner.ScanEvent
import si.skladisce.app.ui.QuickScannerTarget
import si.skladisce.app.ui.theme.SkladisceColors

@Composable
fun QuickCameraScannerDialog(
    target: QuickScannerTarget,
    continuousMode: Boolean,
    onToggleContinuous: () -> Unit,
    onDetectedCode: (String) -> Unit,
    onDismiss: () -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    val controller = remember { NativeCameraController() }

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasCameraPermission = it
    }

    var lastCode by remember { mutableStateOf("") }
    var lastTime by remember { mutableLongStateOf(0L) }

    val prompt = when (target) {
        QuickScannerTarget.INVENTORY_SEARCH -> "Skeniraj šifro (SKU) ali črtno kodo za iskanje"
        QuickScannerTarget.REPLENISHMENT -> "Skeniraj cenovko / kodo s police"
        QuickScannerTarget.AUDIT -> "Skeniraj artikel ali QR regala za inventuro"
        QuickScannerTarget.MIXED_BOX_PACK -> "Skeniraj artikel za dodajanje v mešano škatlo"
        QuickScannerTarget.NONE -> ""
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (hasCameraPermission) {
                NativeCameraPreview(
                    modifier = Modifier.fillMaxSize(),
                    controller = controller,
                    isAutoScanEnabled = true,
                    showIdleReticle = true,
                    onScanEvent = { event ->
                        val code = when (event) {
                            is ScanEvent.BoxOcr -> event.result.bestSku
                            is ScanEvent.ProductBarcode -> event.value
                            is ScanEvent.ShelfQr -> "REGAL:${event.info.code}"
                            is ScanEvent.MixedBox -> "BOX-${event.boxId}"
                        }
                        if (code.isNotBlank()) {
                            val now = SystemClock.elapsedRealtime()
                            if (code != lastCode || now - lastTime > 1200L) {
                                lastCode = code
                                lastTime = now
                                onDetectedCode(code)
                            }
                        }
                    }
                )
            } else {
                Column(
                    modifier = Modifier.align(Alignment.Center),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    Text("Potrebno je dovoljenje za kamero", color = Color.White)
                    Button(onClick = { permLauncher.launch(Manifest.permission.CAMERA) }) {
                        Text("Dovoli kamero")
                    }
                }
            }

            // Zgornja vrstica
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0x990B0F19))
                    .statusBarsPadding()
                    .padding(horizontal = 12.dp, vertical = 10.dp)
                    .align(Alignment.TopCenter),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                IconButton(onClick = onDismiss) {
                    Icon(Icons.Default.Close, contentDescription = "Zapri", tint = Color.White)
                }
                Text(
                    prompt,
                    color = Color.White,
                    fontSize = 13.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f).padding(horizontal = 8.dp)
                )
                IconButton(onClick = { controller.toggleTorch() }) {
                    Icon(
                        if (controller.isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                        contentDescription = "Luč",
                        tint = if (controller.isTorchOn) SkladisceColors.Warning else Color.White
                    )
                }
            }

            // Spodnja vrstica
            Column(
                modifier = Modifier
                    .fillMaxWidth()
                    .background(Color(0xAA0B0F19))
                    .navigationBarsPadding()
                    .padding(horizontal = 14.dp, vertical = 12.dp)
                    .align(Alignment.BottomCenter),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                if (target == QuickScannerTarget.AUDIT) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Checkbox(checked = continuousMode, onCheckedChange = { onToggleContinuous() })
                        Text("Zaporedno štetje (+1 ob vsakem pisku)", color = Color.White, fontSize = 12.5.sp)
                    }
                }

                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(onClick = { controller.cycleZoom() }, contentPadding = PaddingValues(horizontal = 12.dp)) {
                        Icon(Icons.Default.ZoomIn, null, modifier = Modifier.size(16.dp), tint = Color.White)
                        Spacer(Modifier.width(4.dp))
                        Text("${controller.zoomFactor}x", color = Color.White, fontSize = 12.sp)
                    }
                    Button(
                        onClick = {
                            controller.captureHighResBitmap(
                                onCaptured = { bmp ->
                                    scope.launch {
                                        val (res, _) = withContext(Dispatchers.Default) {
                                            CameraOcrAnalyzer.analyzeStaticBitmap(bmp)
                                        }
                                        val found = res?.bestSku.orEmpty()
                                        if (found.isNotBlank()) onDetectedCode(found)
                                    }
                                },
                                onError = {}
                            )
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = SkladisceColors.Primary),
                        modifier = Modifier.weight(1f)
                    ) {
                        Icon(Icons.Default.CameraAlt, null, modifier = Modifier.size(16.dp), tint = Color(0xFF042F2E))
                        Spacer(Modifier.width(6.dp))
                        Text("Foto zajem (HD)", color = Color(0xFF042F2E), fontWeight = FontWeight.Bold)
                    }
                    OutlinedButton(onClick = onDismiss) { Text("Zapri", color = Color.White) }
                }
            }
        }
    }
}
