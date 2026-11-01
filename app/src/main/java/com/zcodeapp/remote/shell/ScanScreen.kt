package com.zcodeapp.remote.shell

import android.Manifest
import android.content.pm.PackageManager
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.BackHandler
import androidx.activity.result.contract.ActivityResultContracts
import androidx.annotation.OptIn
import androidx.camera.core.CameraSelector
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageProxy
import androidx.camera.core.Preview
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.google.mlkit.vision.barcode.BarcodeScannerOptions
import com.google.mlkit.vision.barcode.BarcodeScanning
import com.google.mlkit.vision.barcode.common.Barcode
import com.google.mlkit.vision.common.InputImage
import com.zcodeapp.remote.R
import com.zcodeapp.remote.ui.theme.Gap
import com.zcodeapp.remote.ui.theme.Radius
import com.zcodeapp.remote.ui.theme.TextAction
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicBoolean
import kotlinx.coroutines.flow.MutableStateFlow

@Composable
fun ScanScreen(
    onClose: () -> Unit,
    onScanned: (String) -> Boolean,
) {
    val context = LocalContext.current
    var cameraError by remember { mutableStateOf<String?>(null) }
    var scanError by remember { mutableStateOf<String?>(null) }
    // 相机授权状态：授权完成的瞬间必须重建 CameraPreview。
    // 实测：首次进页面时 CameraPreview 在权限还没授予就已组合，bindToLifecycle 失败，
    // 于是预览一直是黑的；退出再进才正常（用户反馈的「第一次黑屏」）。
    var cameraGranted by remember {
        mutableStateOf(
            ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) ==
                PackageManager.PERMISSION_GRANTED
        )
    }
    val deniedMessage = stringResource(R.string.scan_permission_denied)
    val cameraFailedMessage = stringResource(R.string.scan_camera_failed)
    val invalidMessage = stringResource(R.string.scan_invalid)
    val consumed = remember { AtomicBoolean(false) }
    val scanResult = remember { MutableStateFlow<String?>(null) }
    val scanner = remember {
        BarcodeScanning.getClient(
            BarcodeScannerOptions.Builder().setBarcodeFormats(Barcode.FORMAT_QR_CODE).build()
        )
    }
    // scanner 的生命周期跟随整个扫码页，不跟随相机预览的绑定周期。
    DisposableEffect(Unit) {
        onDispose { runCatching { scanner.close() } }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraGranted = granted
        if (!granted) cameraError = deniedMessage else cameraError = null
    }
    LaunchedEffect(Unit) {
        if (!cameraGranted) {
            permissionLauncher.launch(Manifest.permission.CAMERA)
        }
    }

    // 权限被「永久拒绝」后系统不再弹窗，再调 launch 也毫无反应。
    // 这里给一条明确的出路：直接跳到本应用的权限设置页。
    fun openAppSettings() {
        val intent = android.content.Intent(
            android.provider.Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.fromParts("package", context.packageName, null),
        ).addFlags(android.content.Intent.FLAG_ACTIVITY_NEW_TASK)
        runCatching { context.startActivity(intent) }
    }

    // 【2026-10-26 修正】consumed 只在这里消费，分析器不再提前置位。
    // 旧实现：分析器识别成功时先 compareAndSet(false,true)，这里再 compareAndSet(false,true)，
    // 第二次必然失败 → onScanned 永远不会被调用。表现为「二维码能识别、但什么都没发生」。
    LaunchedEffect(scanResult) {
        scanResult.collect { raw ->
            if (raw != null) {
                val handled = onScanned(raw)
                if (handled) {
                    // 交给上层后不再重复上报同一码。
                    consumed.set(true)
                } else {
                    // 不是远控链接：提示并继续扫，但要等一小会儿再放开，
                    // 否则同一帧的同一码会被连续重试刷屏。
                    scanError = invalidMessage
                    consumed.set(false)
                }
                scanResult.value = null
            }
        }
    }

    BackHandler(onBack = onClose)

    Box(modifier = Modifier.fillMaxSize()) {
        // 只有真正拿到相机权限后才组合预览。用 key 让权限变化时彻底重建绑定。
        if (cameraGranted) {
            CameraPreview(
                scanner = scanner,
                consumed = consumed,
                onBarcode = { raw -> scanResult.value = raw },
                onCameraError = { cameraError = it ?: cameraFailedMessage },
            )
        }
        // 取景框：一个居中偏上的方形，带四角标记。让用户知道该把二维码放进哪里，
        // 而不是对着整屏黑底猜（旧版只有一个顶部条，中间什么都没有）。
        ViewfinderOverlay(
            modifier = Modifier
                .align(Alignment.Center)
                .padding(bottom = 60.dp),
            highlight = scanError != null,
        )
        // 顶部条：与其它页面同一套标题层级（标题 13sp 中粗 + 说明 11sp 弱化），
        // 而不是 Material TopAppBar。底色用 surface 半透明，让相机画面能透出来。
        Surface(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .fillMaxWidth()
                .statusBarsPadding(),
            color = MaterialTheme.colorScheme.surface.copy(alpha = 0.92f),
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(56.dp)
                    .padding(horizontal = Gap.md),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = stringResource(R.string.scan_title),
                        style = MaterialTheme.typography.titleSmall,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    Text(
                        text = scanError ?: stringResource(R.string.scan_hint),
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        style = MaterialTheme.typography.labelSmall,
                        color = if (scanError != null) MaterialTheme.colorScheme.error
                        else MaterialTheme.colorScheme.onSurfaceVariant,
                    )
                }
                TextAction(stringResource(R.string.close), onClose)
            }
        }
        cameraError?.let { message ->
            Surface(
                modifier = Modifier
                    .align(Alignment.BottomCenter)
                    .fillMaxWidth()
                    .padding(Gap.md),
                color = MaterialTheme.colorScheme.surface.copy(alpha = 0.94f),
                shape = RoundedCornerShape(Radius.card),
                tonalElevation = 0.dp,
            ) {
                Row(
                    modifier = Modifier.padding(Gap.md),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Text(
                        text = message,
                        modifier = Modifier.weight(1f),
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurface,
                    )
                    TextAction(
                        label = stringResource(R.string.scan_open_settings),
                        onClick = { openAppSettings() },
                        emphasize = true,
                    )
                }
            }
        }
    }
}

@Composable
private fun ViewfinderOverlay(modifier: Modifier = Modifier, highlight: Boolean) {
    val corner = if (highlight) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
    val side = 240.dp
    val arm = 26.dp
    val thickness = 2.dp

    // 四个角画成 L 形：横臂 + 竖臂。整框会挡住二维码边缘，四角标记既能定位又不遮内容。
    @Composable
    fun Corner(align: Alignment, horizontalStart: Boolean, verticalTop: Boolean) {
        Box(modifier = Modifier.fillMaxSize()) {
            Box(
                modifier = Modifier
                    .align(align)
                    .size(arm, thickness)
                    .background(corner),
            )
            Box(
                modifier = Modifier
                    .align(align)
                    .size(thickness, arm)
                    .background(corner),
            )
        }
    }

    Box(modifier = modifier.size(side)) {
        Corner(Alignment.TopStart, true, true)
        Corner(Alignment.TopEnd, false, true)
        Corner(Alignment.BottomStart, true, false)
        Corner(Alignment.BottomEnd, false, false)
    }
}

@OptIn(androidx.camera.core.ExperimentalGetImage::class)
@Composable
private fun CameraPreview(
    scanner: com.google.mlkit.vision.barcode.BarcodeScanner,
    consumed: AtomicBoolean,
    onBarcode: (String) -> Unit,
    onCameraError: (String?) -> Unit,
) {
    val context = LocalContext.current
    val lifecycleOwner = LocalLifecycleOwner.current
    val cameraExecutor: ExecutorService = remember { Executors.newSingleThreadExecutor() }
    val disposed = remember { AtomicBoolean(false) }
    var provider by remember { mutableStateOf<ProcessCameraProvider?>(null) }

    DisposableEffect(Unit) {
        onDispose {
            disposed.set(true)
            provider?.unbindAll()
            // 不在这里 close(scanner)：scanner 由父级 remember 持有并跨重组复用。
            // 旧实现在这里关掉它，权限变化导致 CameraPreview 重建后，
            // 新的分析器拿到的是已关闭的 scanner，process() 直接失败且不报错。
            cameraExecutor.shutdown()
        }
    }

    AndroidView(
        modifier = Modifier.fillMaxSize(),
        factory = { viewContext ->
            PreviewView(viewContext).apply {
                scaleType = PreviewView.ScaleType.FILL_CENTER
                implementationMode = PreviewView.ImplementationMode.COMPATIBLE
                val future = ProcessCameraProvider.getInstance(viewContext)
                future.addListener({
                    val p = runCatching { future.get() }.getOrElse { err ->
                        post { onCameraError(err.message) }
                        return@addListener
                    }
                    if (disposed.get()) {
                        p.unbindAll()
                        return@addListener
                    }
                    provider = p
                    val preview = Preview.Builder().build().also { it.surfaceProvider = surfaceProvider }
                    val analysis = ImageAnalysis.Builder()
                        .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
                        // 【2026-10-26】默认分析分辨率偏小，扫屏幕上的二维码（像素密集、有摩尔纹）
                        // 时识别率很低。提到 1280x720 兼顾速度与可读性。
                        .setTargetResolution(android.util.Size(1280, 720))
                        .build()
                    analysis.setAnalyzer(cameraExecutor) { image: ImageProxy ->
                        val mediaImage = image.image
                        if (mediaImage == null || consumed.get()) {
                            image.close()
                            return@setAnalyzer
                        }
                        // 屏幕上的二维码常见失败原因是分辨率过高导致摩尔纹，
                        // 以及 imageInfo.rotationDegrees 与预览方向不一致。
                        // InputImage 已经按 rotationDegrees 做了旋转，这里保持原样传递。
                        val input = InputImage.fromMediaImage(mediaImage, image.imageInfo.rotationDegrees)
                        scanner.process(input)
                            .addOnSuccessListener { barcodes ->
                                // 不在分析器里置位 consumed：置位由上层收集器在真正处理后负责。
                                // 旧实现先在这里 CAS，导致上层第二次 CAS 必然失败，扫码静默失效。
                                barcodes.firstNotNullOfOrNull { it.rawValue?.takeIf { v -> v.isNotBlank() } }
                                    ?.let { raw -> post { onBarcode(raw) } }
                            }
                            .addOnCompleteListener { image.close() }
                    }
                    runCatching {
                        p.unbindAll()
                        p.bindToLifecycle(lifecycleOwner, CameraSelector.DEFAULT_BACK_CAMERA, preview, analysis)
                    }.onFailure { err -> post { onCameraError(err.message) } }
                }, ContextCompat.getMainExecutor(viewContext))
            }
        },
    )
}
