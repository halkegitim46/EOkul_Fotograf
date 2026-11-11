package com.eokul.studio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.setContent
import androidx.lifecycle.lifecycleScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.layout.only
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

class MainActivity : ComponentActivity() {
    private val vm: AppViewModel by viewModels()
    private val studio: StudioViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Kenardan kenara görünüm (Android 15+ / targetSdk 35 için zorunlu); boşluklar AppRoot'ta ayarlanır.
        enableEdgeToEdge()
        Diag.install(this)
        Ops.init(this)
        Ops.appStart(studio.settings)
        Diag.report(this)?.let { r ->
            studio.addStartupNote("Önceki çalışmada sorun kaydedildi (bu metni geliştiriciye iletin):\n" + r)
        }
        setContent {
            // PIN açıkken son uygulamalar ekranında ve ekran görüntülerinde öğrenci bilgileri görünmesin.
            val secure = studio.settings.pinHash != null
            androidx.compose.runtime.SideEffect {
                if (secure) window.addFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
                else window.clearFlags(android.view.WindowManager.LayoutParams.FLAG_SECURE)
            }
            EOkulTheme {
                AppRoot(vm, studio)
            }
        }
    }

    override fun onStop() {
        vm.persistNow()
        studio.onBackgrounded()
        // Öğrenci listesi için son snapshot yazımı bitmeden Activity yaşam döngüsü devam eder.
        lifecycleScope.launch(Dispatchers.IO) {
            try {
                studio.persistAndWait()
            } catch (e: Exception) {
                Ops.error("activity_stop_persist", e)
            }
        }
        super.onStop()
    }

    override fun onStart() {
        super.onStart()
        studio.onForegrounded()
    }
}

private val LightScheme = lightColorScheme(
    primary = Color(0xFF1F4E9C),
    onPrimary = Color(0xFFFFFFFF),
    primaryContainer = Color(0xFFDCE6F7),
    onPrimaryContainer = Color(0xFF0B1F44),
    secondary = Color(0xFF4A6286),
    onSecondary = Color(0xFFFFFFFF),
    secondaryContainer = Color(0xFFE3E9F3),
    onSecondaryContainer = Color(0xFF14233D),
    tertiaryContainer = Color(0xFFFFEBC9),
    onTertiaryContainer = Color(0xFF4A3000),
    background = Color(0xFFF3F5F9),
    onBackground = Color(0xFF1A1F2B),
    surface = Color(0xFFF3F5F9),
    onSurface = Color(0xFF1A1F2B),
    surfaceVariant = Color(0xFFE6EAF1),
    onSurfaceVariant = Color(0xFF4B5565),
    surfaceContainerLowest = Color(0xFFFFFFFF),
    surfaceContainerLow = Color(0xFFFFFFFF),
    surfaceContainer = Color(0xFFFFFFFF),
    surfaceContainerHigh = Color(0xFFEEF1F6),
    surfaceContainerHighest = Color(0xFFFFFFFF),
    outline = Color(0xFF8792A3),
    outlineVariant = Color(0xFFD3D9E3)
)

private val DarkScheme = darkColorScheme(
    primary = Color(0xFF8FB4F5),
    onPrimary = Color(0xFF0A2552),
    primaryContainer = Color(0xFF1B3A6B),
    onPrimaryContainer = Color(0xFFD9E6FF),
    secondary = Color(0xFFB0C3E3),
    onSecondary = Color(0xFF1A2C4A),
    secondaryContainer = Color(0xFF243247),
    onSecondaryContainer = Color(0xFFD5E0F2),
    tertiaryContainer = Color(0xFF4A3A12),
    onTertiaryContainer = Color(0xFFFFE2A8),
    background = Color(0xFF0E1420),
    onBackground = Color(0xFFE3E8F1),
    surface = Color(0xFF0E1420),
    onSurface = Color(0xFFE3E8F1),
    surfaceVariant = Color(0xFF232D3F),
    onSurfaceVariant = Color(0xFFA9B3C4),
    surfaceContainerLowest = Color(0xFF0A0F19),
    surfaceContainerLow = Color(0xFF131B29),
    surfaceContainer = Color(0xFF161F2F),
    surfaceContainerHigh = Color(0xFF1C2638),
    surfaceContainerHighest = Color(0xFF1A2434),
    outline = Color(0xFF6B7689),
    outlineVariant = Color(0xFF2B3649)
)

/** Sade köşeler: kartlar 12 dp, düğmeler/alanlar 8 dp. Gölge yok; kartlar zemin renginden ayrılır. */
private val AppShapes = androidx.compose.material3.Shapes(
    extraSmall = androidx.compose.foundation.shape.RoundedCornerShape(6.dp),
    small = androidx.compose.foundation.shape.RoundedCornerShape(8.dp),
    medium = androidx.compose.foundation.shape.RoundedCornerShape(12.dp),
    large = androidx.compose.foundation.shape.RoundedCornerShape(16.dp)
)

@Composable
fun EOkulTheme(content: @Composable () -> Unit) {
    MaterialTheme(
        colorScheme = if (isSystemInDarkTheme()) DarkScheme else LightScheme,
        shapes = AppShapes,
        content = content
    )
}

@Composable
fun AppRoot(vm: AppViewModel, studio: StudioViewModel) {
    val snackbar = remember { SnackbarHostState() }
    LaunchedEffect(Unit) {
        vm.messages.collect { msg ->
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(msg)
        }
    }
    LaunchedEffect(Unit) {
        studio.messages.collect { msg ->
            snackbar.currentSnackbarData?.dismiss()
            snackbar.showSnackbar(msg)
        }
    }

    // Ana geri tuşu: sınıf akışında yığını geri sarar. Toplu kırpma içindeki düzenleyici / ayarlar kendi
    // BackHandler'larını (daha derinde oldukları için öncelikli) kullanır.
    BackHandler(enabled = studio.stack.size > 1) { studio.handleBack() }

    // Koyu zeminli ekranlar (kamera, inceleme, düzenleyici) sistem çubuklarının arkasında da koyu kalır.
    val sc0 = studio.screen
    val darkScreen = !studio.locked && (
        sc0 is Screen.Camera || sc0 is Screen.Review || sc0 is Screen.CaptureEditor ||
            (sc0 is Screen.Batch && vm.route == Route.Editor)
        )
    val rootColor = when {
        sc0 is Screen.Camera -> Color.Black
        darkScreen -> Color(0xFF101014)
        else -> MaterialTheme.colorScheme.background
    }
    val sysDark = isSystemInDarkTheme()
    val view = androidx.compose.ui.platform.LocalView.current
    androidx.compose.runtime.SideEffect {
        val w = (view.context as? android.app.Activity)?.window
        if (w != null) {
            val c = androidx.core.view.WindowCompat.getInsetsController(w, view)
            val lightBars = !darkScreen && !sysDark
            c.isAppearanceLightStatusBars = lightBars
            c.isAppearanceLightNavigationBars = lightBars
        }
    }
    // Ana, sınıf ve liste ekleme ekranlarında üst başlık durum çubuğunun arkasına uzanır (kendi içinde boşluk bırakır); diğerlerinde tüm güvenli alan.
    val insets = if ((sc0 is Screen.Home || sc0 is Screen.ClassDetail || sc0 is Screen.Import || sc0 is Screen.MultiImport) && !studio.locked) {
        WindowInsets.safeDrawing.only(
            androidx.compose.foundation.layout.WindowInsetsSides.Horizontal + androidx.compose.foundation.layout.WindowInsetsSides.Bottom
        )
    } else {
        WindowInsets.safeDrawing
    }

    Surface(modifier = Modifier.fillMaxSize(), color = rootColor) {
        if (studio.locked) {
            Box(Modifier.fillMaxSize().windowInsetsPadding(WindowInsets.safeDrawing)) {
                LockScreen(studio)
            }
            return@Surface
        }
        Box(Modifier.fillMaxSize().windowInsetsPadding(insets)) {
            when (val sc = studio.screen) {
                is Screen.Home -> HomeScreen(studio)
                is Screen.ClassDetail -> ClassScreen(studio, sc.classId)
                is Screen.Import -> ImportScreen(studio, sc.classId)
                is Screen.MultiImport -> MultiImportScreen(studio)
                is Screen.Sheet -> SheetScreen(studio, sc.classId)
                is Screen.Camera -> CameraScreen(studio, sc.classId, sc.no)
                is Screen.Review -> ReviewScreen(studio)
                is Screen.CaptureEditor -> CaptureEditorScreen(studio)
                is Screen.Settings -> SettingsScreen(vm, studio, showRecrop = false, onClose = { studio.back() })
                is Screen.Batch -> when (vm.route) {
                    Route.Gallery -> GalleryScreen(vm, onBack = { studio.back() })
                    Route.Editor -> EditorScreen(vm)
                    Route.Settings -> SettingsScreen(vm, studio, showRecrop = true, onClose = { vm.closeSettings() })
                }
            }
            SnackbarHost(
                hostState = snackbar,
                modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 96.dp)
            )
        }
    }

    val warning = vm.nameWarning
    if (warning != null) {
        val sample = warning.bad.take(5).joinToString(", ") + if (warning.bad.size > 5) " …" else ""
        AlertDialog(
            onDismissRequest = { vm.dismissNameWarning() },
            title = { Text("Dosya adı uyarısı") },
            text = {
                Text(
                    "${warning.bad.size} dosyanın adı e-Okul kuralına uymuyor:\n$sample\n\n" +
                        "Kural: ${vm.settings.nameRegex}\n\nYine de kaydedilsin mi?"
                )
            },
            confirmButton = { TextButton(onClick = { vm.confirmNameWarning() }) { Text("Yine de kaydet") } },
            dismissButton = { TextButton(onClick = { vm.dismissNameWarning() }) { Text("Vazgeç") } }
        )
    }

    if (vm.failures.isNotEmpty()) {
        val fails = vm.failures
        val detail = fails.take(8).joinToString("\n") { (n, e) -> "• $n: ${e.take(70)}" } +
            if (fails.size > 8) "\n… ve ${fails.size - 8} tane daha" else ""
        AlertDialog(
            onDismissRequest = { vm.dismissFailures() },
            title = { Text("Kaydetme hataları") },
            text = { Text(detail) },
            confirmButton = { TextButton(onClick = { vm.dismissFailures() }) { Text("Tamam") } }
        )
    }

    studio.startupNote?.let { note ->
        AlertDialog(
            onDismissRequest = { studio.dismissStartupNote() },
            title = { Text("Bilgi") },
            text = { Text(note) },
            confirmButton = { TextButton(onClick = { studio.dismissStartupNote() }) { Text("Tamam") } }
        )
    }

    if (vm.confirmResetAll) {
        AlertDialog(
            onDismissRequest = { vm.confirmResetAll = false },
            title = { Text("Tüm kırpmaları sıfırla") },
            text = { Text("Elle yapılan düzenlemeler silinecek ve kırpmalar yeniden otomatik hesaplanacak. Devam edilsin mi?") },
            confirmButton = {
                TextButton(onClick = {
                    vm.confirmResetAll = false
                    vm.resetAllCrops()
                }) { Text("Sıfırla") }
            },
            dismissButton = { TextButton(onClick = { vm.confirmResetAll = false }) { Text("Vazgeç") } }
        )
    }
}

@Composable
private fun CaptureEditorScreen(studio: StudioViewModel) {
    val cap = studio.capture
    val st = cap?.let { c -> studio.classById(c.classId)?.students?.firstOrNull { it.no == c.no } }
    EditorContent(
        s = cap?.session,
        title = if (cap == null) "" else "${cap.no} · ${st?.name ?: ""}",
        settings = studio.settings,
        onCancel = { studio.cancelCaptureEditor() },
        onApply = { studio.applyCaptureEditor() },
        onResetFace = { studio.captureEditorResetToFace() },
        onPickFace = { studio.captureEditorPickFace(it) },
        onPrev = null,
        onNext = null,
        applyLabel = if (cap?.directEdit == true) "Uygula ve kaydet" else "Uygula"
    )
}
