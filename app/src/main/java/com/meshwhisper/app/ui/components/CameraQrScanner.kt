package com.meshwhisper.app.ui.components

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraControl
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.animation.core.*
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LocalLifecycleOwner
import com.meshwhisper.app.ui.theme.*
import java.util.concurrent.Executors

/**
 * Live CameraX Preview Viewfinder with active QR Code ImageAnalysis.
 */
@Composable
fun CameraQrScannerView(
    modifier: Modifier = Modifier,
    torchEnabled: Boolean = false,
    useFrontCamera: Boolean = false,
    onQrScanned: (String) -> Unit
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor = remember { Executors.newSingleThreadExecutor() }
    var cameraControl by remember { mutableStateOf<CameraControl?>(null) }

    val qrAnalyzer = remember {
        QrCodeAnalyzer { scannedText ->
            onQrScanned(scannedText)
        }
    }

    DisposableEffect(Unit) {
        onDispose {
            cameraExecutor.shutdown()
        }
    }

    LaunchedEffect(torchEnabled) {
        try {
            cameraControl?.enableTorch(torchEnabled)
        } catch (_: Exception) {}
    }

    AndroidView(
        modifier = modifier.fillMaxSize(),
        factory = { ctx ->
            val previewView = PreviewView(ctx).apply {
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            }

            val cameraProviderFuture = ProcessCameraProvider.getInstance(ctx)
            cameraProviderFuture.addListener({
                try {
                    val cameraProvider = cameraProviderFuture.get()

                    val preview = Preview.Builder().build().also {
                        it.setSurfaceProvider(previewView.surfaceProvider)
                    }

                    val imageAnalysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        .build()

                    imageAnalysis.setAnalyzer(cameraExecutor, qrAnalyzer)

                    val cameraSelector = if (useFrontCamera) {
                        CameraSelector.DEFAULT_FRONT_CAMERA
                    } else {
                        CameraSelector.DEFAULT_BACK_CAMERA
                    }

                    cameraProvider.unbindAll()
                    val camera = cameraProvider.bindToLifecycle(
                        lifecycleOwner,
                        cameraSelector,
                        preview,
                        imageAnalysis
                    )
                    cameraControl = camera.cameraControl
                    try {
                        cameraControl?.enableTorch(torchEnabled)
                    } catch (_: Exception) {}
                } catch (e: Exception) {
                    android.util.Log.e("CameraQrScanner", "CameraX binding failure", e)
                }
            }, ContextCompat.getMainExecutor(ctx))

            previewView
        }
    )
}

/**
 * Tactical Military/Disaster HUD Viewfinder Overlay with Animated Reticle.
 */
@Composable
fun ScannerOverlay(
    modifier: Modifier = Modifier,
    reticleSizeDp: Int = 260
) {
    val infiniteTransition = rememberInfiniteTransition(label = "laser_sweep")
    val laserFraction by infiniteTransition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(2200, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "laser_fraction"
    )

    Box(
        modifier = modifier.fillMaxSize(),
        contentAlignment = Alignment.Center
    ) {
        // Reticle Target Box
        Box(
            modifier = Modifier
                .size(reticleSizeDp.dp)
                .clip(RoundedCornerShape(16.dp))
                .border(1.5.dp, SaharaPrimary.copy(alpha = 0.4f), RoundedCornerShape(16.dp))
        ) {
            // Animated Laser Sweep Line
            Canvas(modifier = Modifier.fillMaxSize()) {
                val yPos = size.height * laserFraction
                drawLine(
                    brush = Brush.horizontalGradient(
                        colors = listOf(
                            Color.Transparent,
                            Color(0xFF00E676).copy(alpha = 0.8f),
                            Color(0xFF00E676),
                            Color(0xFF00E676).copy(alpha = 0.8f),
                            Color.Transparent
                        )
                    ),
                    start = Offset(0f, yPos),
                    end = Offset(size.width, yPos),
                    strokeWidth = 3.dp.toPx()
                )
            }

            // Tactical Reticle Corner Brackets
            Canvas(modifier = Modifier.fillMaxSize()) {
                val cornerLen = 28.dp.toPx()
                val stroke = 3.5.dp.toPx()
                val cornerColor = Color(0xFF00E676)

                // Top-Left Corner
                drawLine(cornerColor, Offset(0f, 0f), Offset(cornerLen, 0f), stroke)
                drawLine(cornerColor, Offset(0f, 0f), Offset(0f, cornerLen), stroke)

                // Top-Right Corner
                drawLine(cornerColor, Offset(size.width, 0f), Offset(size.width - cornerLen, 0f), stroke)
                drawLine(cornerColor, Offset(size.width, 0f), Offset(size.width, cornerLen), stroke)

                // Bottom-Left Corner
                drawLine(cornerColor, Offset(0f, size.height), Offset(cornerLen, size.height), stroke)
                drawLine(cornerColor, Offset(0f, size.height), Offset(0f, size.height - cornerLen), stroke)

                // Bottom-Right Corner
                drawLine(cornerColor, Offset(size.width, size.height), Offset(size.width - cornerLen, size.height), stroke)
                drawLine(cornerColor, Offset(size.width, size.height), Offset(size.width, size.height - cornerLen), stroke)
            }
        }
    }
}

/**
 * Fullscreen Interactive Camera QR Scanner Dialog.
 * Closes the trust loop by providing authentic live camera QR code scanning for
 * Peer Safety Numbers and Team Channel credentials.
 */
@Composable
fun CameraQrScannerDialog(
    onDismissRequest: () -> Unit,
    onQrCodeScanned: (String) -> Unit,
    title: String = "Scan Mesh QR Code",
    subtitle: String = "Align peer identity or team channel QR inside the reticle"
) {
    val context = LocalContext.current
    val haptic = LocalHapticFeedback.current

    var hasCameraPermission by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        )
    }

    var torchEnabled by remember { mutableStateOf(false) }
    var useFrontCamera by remember { mutableStateOf(false) }
    var showManualFallback by remember { mutableStateOf(false) }
    var manualInputText by remember { mutableStateOf("") }

    val permissionLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestPermission()
    ) { granted ->
        hasCameraPermission = granted
    }

    LaunchedEffect(Unit) {
        if (!hasCameraPermission) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    Dialog(
        onDismissRequest = onDismissRequest,
        properties = DialogProperties(usePlatformDefaultWidth = false)
    ) {
        Box(
            modifier = Modifier
                .fillMaxSize()
                .background(Color.Black)
        ) {
            if (hasCameraPermission) {
                // Live CameraX Feed
                CameraQrScannerView(
                    modifier = Modifier.fillMaxSize(),
                    torchEnabled = torchEnabled,
                    useFrontCamera = useFrontCamera,
                    onQrScanned = { result ->
                        haptic.performHapticFeedback(HapticFeedbackType.LongPress)
                        onQrCodeScanned(result)
                    }
                )

                // Translucent Framing & HUD Reticle
                ScannerOverlay(
                    modifier = Modifier.fillMaxSize(),
                    reticleSizeDp = 260
                )
            } else {
                // Permission Denied / Rationale View
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                    verticalArrangement = Arrangement.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.CameraAlt,
                        contentDescription = null,
                        tint = SaharaPrimary,
                        modifier = Modifier.size(64.dp)
                    )
                    Spacer(modifier = Modifier.height(16.dp))
                    Text(
                        text = "Camera Permission Required",
                        color = SaharaOnSurface,
                        fontFamily = EBGaramondFamily,
                        fontSize = 22.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        text = "MeshWhisper requires local camera access to authenticate peer safety numbers and cryptographic channel keys directly on-screen without internet.",
                        color = SaharaOnSurfaceVariant,
                        fontSize = 13.sp,
                        fontFamily = ManropeFamily,
                        textAlign = TextAlign.Center
                    )
                    Spacer(modifier = Modifier.height(24.dp))
                    Button(
                        onClick = { permissionLauncher.launch(Manifest.permission.CAMERA) },
                        colors = ButtonDefaults.buttonColors(containerColor = SaharaPrimary),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text("Grant Camera Permission")
                    }
                }
            }

            // Top HUD Bar
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 16.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(
                    onClick = onDismissRequest,
                    modifier = Modifier
                        .size(42.dp)
                        .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                ) {
                    Icon(Icons.Default.Close, contentDescription = "Close", tint = Color.White)
                }

                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text(
                        text = title,
                        color = Color.White,
                        fontFamily = EBGaramondFamily,
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp
                    )
                    Text(
                        text = "TACTICAL CIPHER VIEW",
                        color = Color(0xFF00E676),
                        fontFamily = ManropeFamily,
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 10.sp,
                        letterSpacing = 1.5.sp
                    )
                }

                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    if (hasCameraPermission) {
                        IconButton(
                            onClick = { torchEnabled = !torchEnabled },
                            modifier = Modifier
                                .size(42.dp)
                                .background(
                                    if (torchEnabled) Color(0xFF00E676).copy(alpha = 0.3f) else Color.Black.copy(alpha = 0.5f),
                                    CircleShape
                                )
                        ) {
                            Icon(
                                imageVector = if (torchEnabled) Icons.Default.FlashOn else Icons.Default.FlashOff,
                                contentDescription = "Torch",
                                tint = if (torchEnabled) Color(0xFF00E676) else Color.White
                            )
                        }

                        IconButton(
                            onClick = { useFrontCamera = !useFrontCamera },
                            modifier = Modifier
                                .size(42.dp)
                                .background(Color.Black.copy(alpha = 0.5f), CircleShape)
                        ) {
                            Icon(Icons.Default.FlipCameraAndroid, contentDescription = "Flip", tint = Color.White)
                        }
                    }
                }
            }

            // Bottom Instruction & Manual Entry Card
            Column(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .navigationBarsPadding()
                    .padding(24.dp),
                horizontalAlignment = Alignment.CenterHorizontally
            ) {
                Surface(
                    color = Color.Black.copy(alpha = 0.75f),
                    shape = RoundedCornerShape(12.dp),
                    border = androidx.compose.foundation.BorderStroke(1.dp, SaharaOutlineVariant.copy(alpha = 0.4f))
                ) {
                    Text(
                        text = subtitle,
                        color = SaharaOnSurface,
                        fontSize = 12.sp,
                        fontFamily = ManropeFamily,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp)
                    )
                }

                Spacer(modifier = Modifier.height(14.dp))

                TextButton(
                    onClick = { showManualFallback = true }
                ) {
                    Icon(Icons.Default.Keyboard, contentDescription = null, tint = SaharaPrimary, modifier = Modifier.size(16.dp))
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                        text = "Enter Link Manually",
                        color = SaharaPrimary,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.SemiBold
                    )
                }
            }
        }
    }

    // Manual Input Fallback Dialog
    if (showManualFallback) {
        AlertDialog(
            onDismissRequest = { showManualFallback = false },
            title = {
                Text(
                    text = "Enter URI Code",
                    fontFamily = EBGaramondFamily,
                    fontWeight = FontWeight.Bold,
                    color = SaharaPrimary
                )
            },
            text = {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    Text(
                        text = "Paste or enter a meshwhisper:// URI link:",
                        fontSize = 12.sp,
                        color = SaharaOnSurfaceVariant
                    )
                    OutlinedTextField(
                        value = manualInputText,
                        onValueChange = { manualInputText = it },
                        placeholder = { Text("meshwhisper://node?id=...", color = SaharaOutline) },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = false,
                        maxLines = 3
                    )
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        val input = manualInputText.trim()
                        if (input.isNotEmpty()) {
                            showManualFallback = false
                            onQrCodeScanned(input)
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = SaharaPrimary)
                ) {
                    Text("Verify & Submit")
                }
            },
            dismissButton = {
                TextButton(onClick = { showManualFallback = false }) {
                    Text("Cancel", color = SaharaOnSurfaceVariant)
                }
            },
            containerColor = SaharaSurfaceContainerLowest,
            shape = RoundedCornerShape(16.dp)
        )
    }
}
