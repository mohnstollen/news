package io.github.mohnstollen.news.ui

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings
import android.util.Size
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.core.resolutionselector.ResolutionStrategy
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.systemBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.Button
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.LifecycleResumeEffect
import androidx.lifecycle.compose.LocalLifecycleOwner
import io.github.mohnstollen.news.data.NcCredentials
import io.github.mohnstollen.news.data.NcLogin
import io.github.mohnstollen.news.data.QrDecoder
import java.util.concurrent.Executors

/**
 * Full-screen camera view that waits for a Nextcloud login QR code. Calls [onScanned] once with
 * the login data; other QR codes only change the hint.
 */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun QrLoginScanner(onScanned: (NcCredentials) -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    fun permitted() =
        ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED

    var granted by remember { mutableStateOf(permitted()) }
    var denied by remember { mutableStateOf(false) }
    var hint by remember { mutableStateOf("Kamera wird gestartet …") }
    var done by remember { mutableStateOf(false) }
    val permission = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        granted = it
        denied = !it
    }
    LaunchedEffect(Unit) { if (!granted) permission.launch(Manifest.permission.CAMERA) }
    // Back from the system settings, where the user may have allowed the camera.
    LifecycleResumeEffect(Unit) {
        if (!granted && permitted()) granted = true
        onPauseOrDispose {}
    }

    Dialog(
        onDismissRequest = onDismiss,
        properties = DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Surface(color = Color.Black, contentColor = Color.White, modifier = Modifier.fillMaxSize()) {
            Column(Modifier.fillMaxSize().systemBarsPadding()) {
                TopAppBar(
                    title = { Text("QR-Code scannen") },
                    navigationIcon = {
                        IconButton(onClick = onDismiss) { Icon(Icons.Default.Close, contentDescription = "Schließen") }
                    },
                    colors = TopAppBarDefaults.topAppBarColors(
                        containerColor = Color.Black,
                        titleContentColor = Color.White,
                        navigationIconContentColor = Color.White,
                    ),
                )
                Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
                    when {
                        granted -> {
                            CameraPreview(
                                onText = { text ->
                                    if (done) return@CameraPreview
                                    val credentials = NcLogin.parse(text)
                                    if (credentials != null) {
                                        done = true
                                        onScanned(credentials)
                                    } else {
                                        hint = "Das ist kein Nextcloud-Anmelde-QR-Code."
                                    }
                                },
                                onStatus = { hint = it },
                                modifier = Modifier.fillMaxSize(),
                            )
                            // Where to hold the code.
                            Box(
                                Modifier.size(240.dp)
                                    .border(2.dp, Color.White.copy(alpha = 0.8f), RoundedCornerShape(16.dp)),
                            )
                        }
                        denied -> PermissionDenied(onOpenSettings = {
                            context.startActivity(
                                Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(Uri.fromParts("package", context.packageName, null))
                                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK),
                            )
                        })
                        else -> Unit
                    }
                }
                Column(
                    Modifier.fillMaxWidth().background(Color.Black).padding(16.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    if (granted) Text(hint, style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "In Nextcloud: Persönliche Einstellungen → Sicherheit → „Neues App-Passwort erstellen“ " +
                            "→ „QR-Code für mobile Apps anzeigen“.",
                        style = MaterialTheme.typography.bodySmall,
                        color = Color.White.copy(alpha = 0.7f),
                    )
                }
            }
        }
    }
}

@Composable
private fun PermissionDenied(onOpenSettings: () -> Unit) {
    Column(
        Modifier.padding(32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
    ) {
        Text(
            "Ohne Zugriff auf die Kamera lässt sich der QR-Code nicht scannen. " +
                "Du kannst ihn in den System-Einstellungen erlauben oder die Zugangsdaten von Hand eintragen.",
            textAlign = TextAlign.Center,
        )
        Spacer(Modifier.height(16.dp))
        Button(onClick = onOpenSettings) { Text("System-Einstellungen öffnen") }
    }
}

/** Camera preview plus frame analysis. Reports decoded QR text and status messages on the main thread. */
@Composable
private fun CameraPreview(onText: (String) -> Unit, onStatus: (String) -> Unit, modifier: Modifier) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val currentOnText by rememberUpdatedState(onText)
    val currentOnStatus by rememberUpdatedState(onStatus)
    val previewView = remember {
        PreviewView(context).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
        }
    }
    AndroidView(factory = { previewView }, modifier = modifier)

    DisposableEffect(lifecycleOwner) {
        val analysisThread = Executors.newSingleThreadExecutor()
        val main = ContextCompat.getMainExecutor(context)
        val future = ProcessCameraProvider.getInstance(context)
        var provider: ProcessCameraProvider? = null
        var disposed = false
        future.addListener({
            if (disposed) return@addListener
            try {
                val cameras = future.get().also { provider = it }
                val selector = when {
                    cameras.hasCamera(CameraSelector.DEFAULT_BACK_CAMERA) -> CameraSelector.DEFAULT_BACK_CAMERA
                    cameras.hasCamera(CameraSelector.DEFAULT_FRONT_CAMERA) -> CameraSelector.DEFAULT_FRONT_CAMERA
                    else -> {
                        currentOnStatus("Dieses Gerät hat keine Kamera.")
                        return@addListener
                    }
                }
                val preview = Preview.Builder().build()
                preview.setSurfaceProvider(previewView.surfaceProvider)
                val analysis = ImageAnalysis.Builder()
                    .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                    .setResolutionSelector(
                        ResolutionSelector.Builder().setResolutionStrategy(
                            ResolutionStrategy(Size(1280, 720), ResolutionStrategy.FALLBACK_RULE_CLOSEST_LOWER_THEN_HIGHER),
                        ).build(),
                    )
                    .build()
                val decoder = QrDecoder()
                analysis.setAnalyzer(analysisThread) { image ->
                    val text = image.use { decode(decoder, it) }
                    if (text != null) main.execute { if (!disposed) currentOnText(text) }
                }
                cameras.unbindAll()
                cameras.bindToLifecycle(lifecycleOwner, selector, preview, analysis)
                currentOnStatus("Kamera auf den QR-Code halten …")
            } catch (e: Exception) {
                currentOnStatus("Die Kamera lässt sich nicht starten: ${e.message ?: e.javaClass.simpleName}")
            }
        }, main)
        onDispose {
            disposed = true
            provider?.unbindAll()
            analysisThread.shutdown()
        }
    }
}

/** The brightness plane of the frame is all a QR code needs. */
private fun decode(decoder: QrDecoder, image: ImageProxy): String? {
    val plane = image.planes[0]
    val buffer = plane.buffer
    val bytes = ByteArray(buffer.remaining())
    buffer.get(bytes)
    return decoder.decode(bytes, plane.rowStride, image.width, image.height)
}
