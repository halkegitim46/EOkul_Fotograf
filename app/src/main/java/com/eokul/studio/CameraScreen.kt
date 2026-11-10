package com.eokul.studio

import android.Manifest
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.provider.Settings as AndroidSettings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.camera.core.Camera
import androidx.camera.core.CameraSelector
import androidx.camera.core.FocusMeteringAction
import androidx.camera.core.ImageAnalysis
import androidx.camera.core.ImageCapture
import androidx.camera.core.ImageCaptureException
import androidx.camera.core.Preview
import androidx.camera.core.resolutionselector.AspectRatioStrategy
import androidx.camera.core.resolutionselector.ResolutionSelector
import androidx.camera.lifecycle.ProcessCameraProvider
import androidx.camera.view.PreviewView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.ui.draw.clip
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.graphics.drawscope.DrawScope
import android.media.MediaActionSound
import android.view.HapticFeedbackConstants
import com.google.mlkit.vision.common.InputImage
import com.google.mlkit.vision.face.FaceLandmark
import com.eokul.studio.core.Quality
import com.eokul.studio.core.Score
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Slider
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import java.io.File

private fun Context.findActivity(): ComponentActivity? {
    var c: Context? = this
    while (c is ContextWrapper) {
        if (c is ComponentActivity) return c
        c = c.baseContext
    }
    return null
}

@Composable
fun CameraScreen(studio: StudioViewModel, classId: String, no: String) {
    BackHandler { studio.back() }
    val ctx = LocalContext.current
    val activity = remember(ctx) { ctx.findActivity() }
    val cls = studio.classById(classId)
    val student = cls?.students?.firstOrNull { it.no == no }
    if (cls == null || student == null || activity == null) {
        Box(Modifier.fillMaxSize().background(Color.Black))
        return
    }
    val index = cls.students.indexOfFirst { it.no == no }

    var hasPerm by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(ctx, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }
    var askedOnce by remember { mutableStateOf(false) }
    val permLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) {
        hasPerm = it
        askedOnce = true
    }
    LaunchedEffect(Unit) {
        if (!hasPerm) permLauncher.launch(Manifest.permission.CAMERA)
    }
    val galleryPicker = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocument()) { uri: Uri? ->
        if (uri != null) studio.onPhotoReady(classId, no, uri, null)
    }

    var lensBack by remember { mutableStateOf(true) }
    var torchOn by remember { mutableStateOf(false) }
    var zoom by remember { mutableStateOf(0f) }
    var camera by remember { mutableStateOf<Camera?>(null) }
    var capturing by remember { mutableStateOf(false) }
    var camError by remember { mutableStateOf<String?>(null) }

    // Anlık yüz tespiti / otomatik çekim durumu
    var live by remember { mutableStateOf<LiveFace?>(null) }
    val liveRaw = remember { java.util.concurrent.atomic.AtomicReference<LiveFace?>(null) }
    var progress by remember { mutableStateOf(0f) }
    var armed by remember { mutableStateOf(false) }
    var focusMark by remember { mutableStateOf<Offset?>(null) }
    var gate by remember { mutableStateOf<LiveEval?>(null) }
    var score by remember { mutableStateOf(-1) }
    val motion = remember { MotionMonitor(ctx) }
    DisposableEffect(Unit) {
        motion.start()
        onDispose { motion.stop() }
    }
    val settingsNow = studio.settings
    val autoOn = settingsNow.autoCapture
    val sound = remember { MediaActionSound().also { try { it.load(MediaActionSound.SHUTTER_CLICK) } catch (_: Throwable) { } } }
    val analysisExecutor = remember { Executors.newSingleThreadExecutor() }
    DisposableEffect(Unit) {
        onDispose {
            analysisExecutor.shutdown()
            sound.release()
        }
    }

    val selector = remember {
        ResolutionSelector.Builder()
            .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
            .build()
    }
    val previewView = remember {
        PreviewView(ctx).apply {
            scaleType = PreviewView.ScaleType.FILL_CENTER
            implementationMode = PreviewView.ImplementationMode.COMPATIBLE
            keepScreenOn = true
        }
    }
    val imageCapture = remember {
        // Yüksek kalite (varsayılan): kameranın en iyi görüntü işlemesi (gürültü azaltma dahil), ~5 MP, JPEG 95.
        // Kapalıysa hızlı mod: ~2 MP (1600x1200), JPEG 90. Ayar bir sonraki kamera açılışında geçerli olur.
        val hq = studio.settings.hqCapture
        ImageCapture.Builder()
            .setCaptureMode(if (hq) ImageCapture.CAPTURE_MODE_MAXIMIZE_QUALITY else ImageCapture.CAPTURE_MODE_MINIMIZE_LATENCY)
            .setJpegQuality(if (hq) 95 else 90)
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        androidx.camera.core.resolutionselector.ResolutionStrategy(
                            if (hq) android.util.Size(2560, 1920) else android.util.Size(1600, 1200),
                            androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
            .build()
    }
    val analysis = remember {
        ImageAnalysis.Builder()
            .setResolutionSelector(
                ResolutionSelector.Builder()
                    .setAspectRatioStrategy(AspectRatioStrategy.RATIO_4_3_FALLBACK_AUTO_STRATEGY)
                    .setResolutionStrategy(
                        androidx.camera.core.resolutionselector.ResolutionStrategy(
                            android.util.Size(640, 480),
                            androidx.camera.core.resolutionselector.ResolutionStrategy.FALLBACK_RULE_CLOSEST_HIGHER_THEN_LOWER
                        )
                    )
                    .build()
            )
            .setBackpressureStrategy(ImageAnalysis.STRATEGY_KEEP_ONLY_LATEST)
            .build()
    }

    DisposableEffect(lensBack, hasPerm) {
        if (!hasPerm) {
            onDispose { }
        } else {
            val future = ProcessCameraProvider.getInstance(ctx)
            future.addListener({
                try {
                    val provider = future.get()
                    val preview = Preview.Builder().setResolutionSelector(selector).build()
                    preview.setSurfaceProvider(previewView.surfaceProvider)
                    provider.unbindAll()
                    val cs = if (lensBack) CameraSelector.DEFAULT_BACK_CAMERA else CameraSelector.DEFAULT_FRONT_CAMERA
                    camera = provider.bindToLifecycle(activity, cs, preview, imageCapture, analysis)
                    val mirror = !lensBack
                    var frameNo = 0
                    var lightCand = 0
                    var lightCandN = 0
                    var lastAnalysisMs = 0L
                    analysis.setAnalyzer(analysisExecutor) { proxy ->
                        // Saniyede ~12 kare yeterli: bellek hareketi ve ısınma azalır
                        val tNow = System.currentTimeMillis()
                        if (tNow - lastAnalysisMs < 80L) {
                            proxy.close()
                            return@setAnalyzer
                        }
                        lastAnalysisMs = tNow
                        // Bu iş parçacığında ML Kit sonucu senkron beklenir: dinleyici/yürütücü yoktur, ekran kapanırken
                        // reddedilen görev (RejectedExecutionException) çökmesi olmaz; her durumda kare kapatılır.
                        var bmpRef: android.graphics.Bitmap? = null
                        try {
                            val bmp = proxy.toBitmap()
                            bmpRef = bmp
                            val rot = proxy.imageInfo.rotationDegrees
                            val uw = (if (rot % 180 == 0) bmp.width else bmp.height).toFloat()
                            val uh = (if (rot % 180 == 0) bmp.height else bmp.width).toFloat()
                            frameNo++
                            val measureNow = frameNo % 2 == 0
                            val faces = com.google.android.gms.tasks.Tasks.await(
                                LiveFaces.detector.process(InputImage.fromBitmap(bmp, rot)),
                                2, TimeUnit.SECONDS
                            )
                            val f = faces.maxByOrNull { it.boundingBox.width() * it.boundingBox.height() }
                            if (f == null) {
                                liveRaw.set(null)
                            } else {
                                val r = f.boundingBox
                                var x1 = r.left / uw
                                var x2 = r.right / uw
                                if (mirror) {
                                    val t = 1f - x2
                                    x2 = 1f - x1
                                    x1 = t
                                }
                                // Göz çizgisinden hassas yan eğiklik
                                var roll = f.headEulerAngleZ
                                val le = f.getLandmark(FaceLandmark.LEFT_EYE)?.position
                                val re = f.getLandmark(FaceLandmark.RIGHT_EYE)?.position
                                if (le != null && re != null && Math.abs(re.x - le.x) > 8f) {
                                    var ang = Math.toDegrees(Math.atan2((re.y - le.y).toDouble(), (re.x - le.x).toDouble())).toFloat()
                                    if (ang > 90f) ang -= 180f
                                    if (ang < -90f) ang += 180f
                                    roll = ang
                                }
                                val lo = f.leftEyeOpenProbability
                                val ro = f.rightEyeOpenProbability
                                val eyeOpen = if (lo != null && ro != null) Math.min(lo, ro) else -1f
                                // Netlik / ışık ölçümü (her 3 karede bir; aradaki karelerde son değer korunur)
                                val prev = liveRaw.get()
                                var sharp = prev?.sharp ?: Float.NaN
                                var bright = prev?.bright ?: Float.NaN
                                var contrast = prev?.contrast ?: Float.NaN
                                var light = prev?.light ?: 0
                                if (measureNow) {
                                    // Işık durumu: ani titreşim olmasın diye aynı sonuç art arda 3 ölçümde görülünce kabul edilir
                                    val lr = measureLight(bmp, rot, r)
                                    if (lr != null) {
                                        if (lr.code == lightCand) lightCandN++ else { lightCand = lr.code; lightCandN = 1 }
                                        if (lightCandN >= 3) light = lightCand
                                    }
                                    val q = measureFace(bmp, rot, r)
                                    if (q != null) {
                                        sharp = q.blurRatio.toFloat()
                                        bright = q.brightness.toFloat()
                                        contrast = q.contrast.toFloat()
                                    }
                                }
                                liveRaw.set(
                                    LiveFace(
                                        x1, r.top / uh, x2, r.bottom / uh,
                                        roll, f.headEulerAngleY, System.currentTimeMillis(),
                                        eyeOpen, sharp, bright, contrast, light
                                    )
                                )
                            }
                        } catch (e: Throwable) {
                            // tek kare hatası önizlemeyi durdurmaz
                        } finally {
                            try { bmpRef?.recycle() } catch (_: Exception) { }
                            try { proxy.close() } catch (_: Exception) { }
                        }
                    }
                    camError = null
                } catch (e: Exception) {
                    camError = "Kamera açılamadı: ${e.message ?: e.javaClass.simpleName}"
                    if (!lensBack) lensBack = true
                }
            }, ContextCompat.getMainExecutor(ctx))
            onDispose {
                try {
                    analysis.clearAnalyzer()
                    liveRaw.set(null)
                    live = null
                    if (future.isDone) future.get().unbindAll()
                } catch (e: Exception) {
                    // yoksay
                }
                camera = null
            }
        }
    }
    LaunchedEffect(camera, torchOn) {
        camera?.cameraControl?.enableTorch(torchOn)
    }
    LaunchedEffect(camera, zoom) {
        camera?.cameraControl?.setLinearZoom(zoom)
    }

    fun shoot() {
        if (capturing) return
        capturing = true
        val dir = File(ctx.cacheDir, "capture")
        dir.mkdirs()
        val stamp = System.currentTimeMillis()
        val total = if (studio.settings.smartShot) 3 else 1
        val flip = if (lensBack) studio.settings.flipCapture else studio.settings.flipFront
        val shots = ArrayList<BurstPick.Shot>()
        val scores = ArrayList<Int>()   // kare puanları (shots ile aynı sıra)
        previewView.display?.let { imageCapture.targetRotation = it.rotation }
        try { sound.play(MediaActionSound.SHUTTER_CLICK) } catch (_: Throwable) { }
        previewView.performHapticFeedback(HapticFeedbackConstants.VIRTUAL_KEY)

        fun finish() {
            val list = ArrayList(shots)
            val sc = ArrayList(scores)
            Thread {
                val best = try { BurstPick.bestOf(list, sc) } catch (e: Throwable) { list[0].file }
                if (flip) {
                    // EXIF yönünü sağ-sol çevir (kayıpsız). Yön değeri kendi tablomuzla hesaplanır ve yazıldıktan sonra
                    // geri okunarak doğrulanır; kütüphanenin flipHorizontally() çağrısına güvenilmez.
                    try {
                        val ei = androidx.exifinterface.media.ExifInterface(best.absolutePath)
                        val cur = ei.getAttributeInt(
                            androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION,
                            androidx.exifinterface.media.ExifInterface.ORIENTATION_NORMAL
                        )
                        val next = when (cur) {
                            1 -> 2; 2 -> 1; 3 -> 4; 4 -> 3; 5 -> 8; 8 -> 5; 6 -> 7; 7 -> 6
                            else -> 2
                        }
                        ei.setAttribute(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, next.toString())
                        ei.saveAttributes()
                        val back = androidx.exifinterface.media.ExifInterface(best.absolutePath)
                            .getAttributeInt(androidx.exifinterface.media.ExifInterface.TAG_ORIENTATION, 1)
                        if (back != next) android.util.Log.w("EOkul", "Sağ-sol çevirme doğrulanamadı: beklenen $next, okunan $back")
                    } catch (e: Throwable) {
                        android.util.Log.w("EOkul", "Sağ-sol çevirme başarısız", e)
                    }
                }
                ContextCompat.getMainExecutor(ctx).execute {
                    capturing = false
                    if (studio.screen is Screen.Camera) {
                        studio.onPhotoReady(classId, no, Uri.fromFile(best), best)
                    } else {
                        best.delete()   // kullanıcı çekim bitmeden ekrandan ayrıldı
                    }
                }
            }.start()
        }

        fun one(i: Int) {
            val file = File(dir, "${no}_${stamp}_$i.jpg")
            val eye = liveRaw.get()?.eyeOpen ?: -1f
            imageCapture.takePicture(
                ImageCapture.OutputFileOptions.Builder(file).build(),
                ContextCompat.getMainExecutor(ctx),
                object : ImageCapture.OnImageSavedCallback {
                    override fun onImageSaved(output: ImageCapture.OutputFileResults) {
                        val shot = BurstPick.Shot(file, eye)
                        shots.add(shot)
                        if (total == 1) {
                            finish()
                            return
                        }
                        // Kareyi hemen puanla: yeterince iyiyse kalan kareler çekilmez (hız); değilse sıradaki kare çekilir.
                        Thread {
                            val sc = try { BurstPick.score(shot) } catch (e: Throwable) { -1 }
                            ContextCompat.getMainExecutor(ctx).execute {
                                scores.add(sc)
                                if (sc >= BurstPick.GOOD_ENOUGH || i + 1 >= total) finish() else one(i + 1)
                            }
                        }.start()
                    }

                    override fun onError(exception: ImageCaptureException) {
                        file.delete()
                        if (shots.isEmpty()) {
                            capturing = false
                            camError = "Çekim başarısız: ${exception.message ?: "hata"}"
                        } else {
                            finish()   // elde olan karelerle devam
                        }
                    }
                }
            )
        }
        one(0)
    }

    fun focusAt(nx: Float, ny: Float, mark: Boolean) {
        val cam = camera ?: return
        val pw = previewView.width.toFloat()
        val ph = previewView.height.toFloat()
        if (pw <= 0f || ph <= 0f) return
        try {
            val pt = previewView.meteringPointFactory.createPoint(nx * pw, ny * ph)
            cam.cameraControl.startFocusAndMetering(
                FocusMeteringAction.Builder(pt, FocusMeteringAction.FLAG_AF or FocusMeteringAction.FLAG_AE)
                    .setAutoCancelDuration(3, TimeUnit.SECONDS)
                    .build()
            )
            if (mark) focusMark = Offset(nx, ny)
        } catch (e: Exception) {
            // odaklanma desteklenmiyorsa yoksay
        }
    }

    // Öğrenci değişince otomatik çekim yeniden silahlanır (önceki öğrencinin hâlâ çerçevede olması durumuna karşı)
    LaunchedEffect(no) {
        armed = false
        progress = 0f
    }
    // Anlık değerlendirme: yüze otomatik odak, hazır ve sabitse geri sayım ve otomatik çekim
    LaunchedEffect(autoOn, hasPerm, camera) {
        var readySince = 0L
        var noReadySince = System.currentTimeMillis()
        var lastCx = -1f
        var lastCy = -1f
        var lastFocusMs = 0L
        var lastFocusX = -1f
        var lastFocusY = -1f
        var sharpSince = 0L
        var lightSince = 0L
        while (isActive) {
            delay(100)
            val now = System.currentTimeMillis()
            live = liveRaw.get()   // arayüz durumu yalnızca 10 Hz güncellenir (30 Hz yeniden çizim yok)
            val l = live?.takeIf { now - it.ts < 700 }
            if (l == null) {
                gate = null
                score = -1
                sharpSince = 0L
                readySince = 0L
                progress = 0f
                lastCx = -1f
                lastCy = -1f
                if (noReadySince == 0L) noReadySince = now
                if (now - noReadySince >= 1000L) armed = true
                continue
            }
            // Yüze sürekli odak / pozlama
            val moved = Math.abs(l.cx - lastFocusX) > 0.08f || Math.abs(l.cy - lastFocusY) > 0.08f
            if (camera != null && !capturing && (now - lastFocusMs > 2500L || (moved && now - lastFocusMs > 800L))) {
                focusAt(l.cx, l.cy, false)
                lastFocusMs = now
                lastFocusX = l.cx
                lastFocusY = l.cy
            }
            val smart = studio.settings.smartShot
            val motionOk = motion.steady
            var ev = evaluateLive(l, studio.settings, motionOk, smart, false, false)
            // Işık engeli yalnızca uyarıdır: 4 sn sonra vazgeçilir (ortam değiştirilemiyorsa çekim kilitlenmesin)
            var lightIgnored = false
            if (ev.lightOnly) {
                if (lightSince == 0L) lightSince = now
                if (now - lightSince > 4000L) {
                    lightIgnored = true
                    ev = evaluateLive(l, studio.settings, motionOk, smart, false, true)
                }
            } else {
                lightSince = 0L
            }
            // Yalnızca netlik engeliyse 3 sn sonra vazgeç (eşik uygun olmayan ortamda çekimi kilitlememek için)
            if (ev.sharpOnly) {
                if (sharpSince == 0L) sharpSince = now
                if (now - sharpSince > 3000L) ev = evaluateLive(l, studio.settings, motionOk, smart, true, lightIgnored)
            } else {
                sharpSince = 0L
            }
            if (lightIgnored && ev.ready) ev = LiveEval(true, "Hazır — ışık ideal değil, sabit durun", ev.dev)
            gate = ev
            score = Score.total(
                ev.dev.toDouble(), l.sharp.toDouble(), Math.abs(l.roll).toDouble(), Math.abs(l.yaw).toDouble(),
                l.bright.toDouble(), l.contrast.toDouble(), l.eyeOpen.toDouble()
            )
            val still = lastCx < 0f || (Math.abs(l.cx - lastCx) < 0.03f && Math.abs(l.cy - lastCy) < 0.03f)
            lastCx = l.cx
            lastCy = l.cy
            if (!ev.ready || !still) {
                readySince = 0L
                progress = 0f
                if (!ev.ready) {
                    if (noReadySince == 0L) noReadySince = now
                    if (now - noReadySince >= 1000L) armed = true
                }
                continue
            }
            // hazır
            if (!autoOn || capturing) continue
            if (!armed) {
                // önceki öğrenci hâlâ çerçevedeyse: önce yüz hazır değil durumuna geçmesi beklenir
                progress = 0f
                continue
            }
            noReadySince = 0L
            if (readySince == 0L) readySince = now
            progress = ((now - readySince) / studio.settings.autoHoldMs.toFloat()).coerceIn(0f, 1f)
            if (progress >= 1f) {
                armed = false
                readySince = 0L
                progress = 0f
                noReadySince = 0L
                // Odak ve pozlamayı yüze kilitle, kısa süre otursun, sonra çek
                focusAt(l.cx, l.cy, false)
                delay(250)
                shoot()
            }
        }
    }

    Column(Modifier.fillMaxSize().background(Color.Black)) {
        // ---- Öğrenci başlığı
        Row(
            Modifier.fillMaxWidth().padding(horizontal = 4.dp, vertical = 4.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            CloseBtn(onClick = { studio.back() })
            Column(Modifier.weight(1f)) {
                Text(
                    student.name, color = Color.White, fontWeight = FontWeight.Bold,
                    maxLines = 1, overflow = TextOverflow.Ellipsis, style = MaterialTheme.typography.titleMedium
                )
                Text(
                    "${student.no}  ·  ${cls.name}  ·  ${index + 1}/${cls.students.size}" +
                        if (student.captured) "  ·  ✅" else "",
                    color = Color(0xFFB0B6C8), fontSize = 12.sp
                )
            }
            PrevBtn(onClick = { studio.cameraMove(classId, no, -1) }, enabled = index > 0)
            NextBtn(onClick = { studio.cameraMove(classId, no, 1) }, enabled = index < cls.students.size - 1)
        }

        // ---- Önizleme (3:4) ve kılavuz
        Box(Modifier.weight(1f).fillMaxWidth(), contentAlignment = Alignment.Center) {
            if (hasPerm) {
                Box(Modifier.fillMaxHeight().aspectRatio(3f / 4f)) {
                    AndroidView(factory = { previewView }, modifier = Modifier.fillMaxSize())
                    GuideOverlay(studio.settings)
                    LiveOverlay(live, gate?.ready == true, progress, focusMark)
                    Box(
                        Modifier.fillMaxSize().pointerInput(camera) {
                            detectTapGestures { off ->
                                focusAt(off.x / size.width, off.y / size.height, true)
                            }
                        }
                    )
                    val ev = gate
                    val hint = when {
                        ev != null -> ev.hint
                        else -> "Yüzü çerçeveye alın"
                    }
                    Surface(
                        color = Color.Black.copy(alpha = 0.55f),
                        shape = RoundedCornerShape(16.dp),
                        modifier = Modifier.align(Alignment.TopCenter).padding(top = 10.dp)
                    ) {
                        Text(
                            hint,
                            color = if (ev?.ready == true) Color(0xFF9ECE6A) else Color(0xFFE0AF68),
                            fontSize = 14.sp, fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(horizontal = 14.dp, vertical = 6.dp)
                        )
                    }
                    val lf = live
                    if (lf != null && studio.settings.smartShot) {
                        val lh = lightHint(lf.light)
                        val lcol = if (lh == null) Color(0xFF9ECE6A) else Color(0xFFE0AF68)
                        Surface(
                            color = Color.Black.copy(alpha = 0.55f),
                            shape = RoundedCornerShape(12.dp),
                            modifier = Modifier.align(Alignment.TopCenter).padding(top = 52.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                modifier = Modifier.padding(horizontal = 10.dp, vertical = 3.dp)
                            ) {
                                Box(Modifier.size(8.dp).clip(CircleShape).background(lcol))
                                Text(
                                    "  Işık: " + when (lf.light) {
                                        com.eokul.studio.core.Lighting.BACKLIT -> "arka ışık"
                                        com.eokul.studio.core.Lighting.UNEVEN -> "yan ışık"
                                        com.eokul.studio.core.Lighting.GLARE -> "parlama"
                                        else -> "iyi"
                                    },
                                    color = lcol, fontSize = 12.sp, fontWeight = FontWeight.SemiBold
                                )
                            }
                        }
                    }
                    QualityBadge(
                        score = if (live != null) score else -1,
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 10.dp)
                    )
                }
            } else {
                Column(
                    Modifier.fillMaxSize().padding(24.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Text("Kamera izni gerekli", color = Color.White, style = MaterialTheme.typography.titleMedium)
                    Text(
                        "Fotoğraf çekmek için kamera izni verin veya galeriden bir fotoğraf seçin.",
                        color = Color(0xFFB0B6C8), modifier = Modifier.padding(vertical = 8.dp)
                    )
                    Button(onClick = {
                        if (askedOnce) {
                            ctx.startActivity(
                                Intent(AndroidSettings.ACTION_APPLICATION_DETAILS_SETTINGS)
                                    .setData(Uri.fromParts("package", ctx.packageName, null))
                            )
                        } else {
                            permLauncher.launch(Manifest.permission.CAMERA)
                        }
                    }) { Text(if (askedOnce) "Uygulama ayarlarını aç" else "İzin ver") }
                }
            }
            if (capturing) CircularProgressIndicator(Modifier.size(48.dp))
        }

        camError?.let {
            Text(it, color = Color(0xFFE0AF68), fontSize = 12.sp, modifier = Modifier.padding(horizontal = 12.dp))
        }

        // ---- Kontrol paneli
        Surface(
            color = Color(0xFF131A27),
            shape = RoundedCornerShape(topStart = 20.dp, topEnd = 20.dp),
            modifier = Modifier.fillMaxWidth()
        ) {
            Column(Modifier.padding(horizontal = 12.dp, vertical = 8.dp)) {
                if (hasPerm) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        ModeChip("Otomatik çekim", autoOn) {
                            studio.updateSettings(studio.settings.copy(autoCapture = !autoOn))
                        }
                        Spacer(Modifier.size(8.dp))
                        ModeChip("Akıllı", studio.settings.smartShot) {
                            studio.updateSettings(studio.settings.copy(smartShot = !studio.settings.smartShot))
                        }
                        Spacer(Modifier.size(8.dp))
                        val flipOn = if (lensBack) studio.settings.flipCapture else studio.settings.flipFront
                        ModeChip(if (lensBack) "Çevir (arka)" else "Çevir (ön)", flipOn) {
                            studio.updateSettings(
                                if (lensBack) studio.settings.copy(flipCapture = !studio.settings.flipCapture)
                                else studio.settings.copy(flipFront = !studio.settings.flipFront)
                            )
                        }
                        Slider(
                            value = zoom, onValueChange = { zoom = it }, valueRange = 0f..0.6f,
                            modifier = Modifier.weight(1f).padding(start = 12.dp)
                        )
                        Text(
                            "${"%.1f".format(1f + zoom * 3f)}×", color = Color(0xFFB0B6C8), fontSize = 12.sp,
                            modifier = Modifier.padding(start = 6.dp, end = 4.dp)
                        )
                    }
                }
                Row(
                    Modifier.fillMaxWidth().padding(top = 4.dp, bottom = 4.dp),
                    horizontalArrangement = Arrangement.SpaceEvenly,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    IconBtn(CamIcons.Gallery, "Galeriden seç", { galleryPicker.launch(arrayOf("image/*")) }, tint = Color.White)
                    IconBtn(
                        if (torchOn) CamIcons.FlashOn else CamIcons.FlashOff,
                        if (torchOn) "Işığı kapat" else "Işığı aç",
                        { torchOn = !torchOn },
                        tint = if (torchOn) Color(0xFFFFD54F) else Color.White,
                        enabled = hasPerm && lensBack
                    )
                    val shutterOk = hasPerm && camera != null && !capturing
                    Box(
                        Modifier
                            .size(76.dp)
                            .border(4.dp, if (shutterOk) Color.White else Color.Gray, CircleShape)
                            .padding(7.dp)
                            .clip(CircleShape)
                            .background(if (shutterOk) Color.White else Color.Gray)
                            .clickable(enabled = shutterOk) { shoot() }
                    )
                    IconBtn(
                        androidx.compose.material.icons.Icons.Filled.Refresh, "Kamerayı çevir",
                        {
                            torchOn = false
                            lensBack = !lensBack
                        },
                        tint = Color.White, enabled = hasPerm
                    )
                    IconBtn(
                        CamIcons.SkipNext, "Bu öğrenciyi atla",
                        { studio.cameraMove(classId, no, 1) },
                        tint = Color.White, enabled = index < cls.students.size - 1
                    )
                }
            }
        }
    }
}

/** Açık/kapalı durumu belli olan küçük mod düğmesi. */
@Composable
private fun ModeChip(label: String, on: Boolean, onClick: () -> Unit) {
    Surface(
        color = if (on) Color(0xFF2F5FC4) else Color(0xFF232C3D),
        shape = RoundedCornerShape(50),
        modifier = Modifier.clip(RoundedCornerShape(50)).clickable { onClick() }
    ) {
        Text(
            label,
            color = if (on) Color.White else Color(0xFFB0B6C8),
            fontSize = 12.sp, fontWeight = FontWeight.SemiBold,
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 7.dp)
        )
    }
}

/** Belirgin canlı kalite göstergesi: renkli nokta, büyük puan ve sözel derece. Yüz yoksa soluk "—". */
@Composable
private fun QualityBadge(score: Int, modifier: Modifier = Modifier) {
    val col = when {
        score < 0 -> Color(0xFF8A93A8)
        score >= 80 -> Color(0xFF4CC38A)
        score >= 60 -> Color(0xFFE0AF68)
        else -> Color(0xFFE5645F)
    }
    Surface(color = Color.Black.copy(alpha = 0.62f), shape = RoundedCornerShape(50), modifier = modifier) {
        Row(
            Modifier.padding(start = 10.dp, end = 14.dp, top = 5.dp, bottom = 5.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(Modifier.size(10.dp).clip(CircleShape).background(col))
            Text(
                if (score < 0) "—" else score.toString(),
                color = col, fontSize = 22.sp, fontWeight = FontWeight.Bold,
                modifier = Modifier.padding(start = 8.dp)
            )
            Text(
                if (score < 0) "Yüz bekleniyor" else Score.label(score),
                color = Color.White, fontSize = 12.sp, modifier = Modifier.padding(start = 8.dp)
            )
        }
    }
}

/** Çerçeve: e-Okul kırpma alanı, baş ovali ve çene çizgisi (ayarlardaki oranlara göre). */
@Composable
private fun GuideOverlay(s: Settings) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        // Kırpma alanı: genişliğin %88'i, 133:171 oranında
        val cw = w * 0.88f
        val ch = (cw * TARGET_H / TARGET_W).coerceAtMost(h * 0.96f)
        val cwAdj = ch * TARGET_W / TARGET_H
        val left = (w - cwAdj) / 2f
        val top = (h - ch) / 2f

        val dim = Color.Black.copy(alpha = 0.40f)
        drawRect(dim, Offset(0f, 0f), Size(w, top))
        drawRect(dim, Offset(0f, top + ch), Size(w, h - top - ch))
        drawRect(dim, Offset(0f, top), Size(left, ch))
        drawRect(dim, Offset(left + cwAdj, top), Size(w - left - cwAdj, ch))
        drawRect(Color.White.copy(alpha = 0.8f), Offset(left, top), Size(cwAdj, ch), style = Stroke(width = 2f))

        // Saç tepesi ve çene çizgileri
        val hairY = top + ch * s.topGap.toFloat()
        val chinY = hairY + ch * s.headFrac.toFloat()
        val headH = chinY - hairY
        val headW = headH * 0.74f
        val cx = w / 2f
        val dash = PathEffect.dashPathEffect(floatArrayOf(14f, 10f))
        drawRoundRect(
            color = Color(0xFF9ECE6A),
            topLeft = Offset(cx - headW / 2f, hairY),
            size = Size(headW, headH),
            cornerRadius = CornerRadius(headW / 2f, headH / 2f),
            style = Stroke(width = 3f, pathEffect = dash)
        )
        drawLine(Color(0xFF9ECE6A).copy(alpha = 0.7f), Offset(left, chinY), Offset(left + cwAdj, chinY), strokeWidth = 1.5f)
        // Omuz çizgisi için yumuşak ipucu
        drawLine(
            Color.White.copy(alpha = 0.35f),
            Offset(left, top + ch * 0.86f), Offset(left + cwAdj, top + ch * 0.86f),
            strokeWidth = 1f, pathEffect = dash
        )
    }
}

/** Anlık yüz kutusu (hazırsa yeşil), otomatik çekim geri sayım halkası ve dokunarak odak işareti. */
@Composable
private fun LiveOverlay(live: LiveFace?, ok: Boolean, progress: Float, focusMark: Offset?) {
    Canvas(Modifier.fillMaxSize()) {
        val w = size.width
        val h = size.height
        if (live != null) {
            val col = if (ok) Color(0xFF9ECE6A) else Color(0xFFE0AF68)
            drawRoundRect(
                color = col,
                topLeft = Offset(live.x1 * w, live.y1 * h),
                size = Size((live.x2 - live.x1) * w, (live.y2 - live.y1) * h),
                cornerRadius = CornerRadius(14f, 14f),
                style = Stroke(width = 3f)
            )
            if (progress > 0f) {
                val r = (live.x2 - live.x1) * w * 0.55f
                drawArc(
                    color = Color(0xFF9ECE6A),
                    startAngle = -90f,
                    sweepAngle = 360f * progress,
                    useCenter = false,
                    topLeft = Offset(live.cx * w - r, live.cy * h - r),
                    size = Size(r * 2, r * 2),
                    style = Stroke(width = 8f)
                )
            }
        }
        if (focusMark != null) {
            drawCircle(
                Color.White.copy(alpha = 0.8f), radius = 36f,
                center = Offset(focusMark.x * w, focusMark.y * h), style = Stroke(width = 2f)
            )
        }
    }
}
