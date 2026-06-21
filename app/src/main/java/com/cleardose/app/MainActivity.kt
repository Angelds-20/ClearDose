package com.cleardose.app

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.opengl.GLSurfaceView
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.util.Log
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.*
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.ContextCompat
import com.cleardose.app.ui.theme.*
import kotlin.math.*
import kotlin.math.roundToInt
import com.cleardose.app.R
import android.net.Uri
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Matrix
import android.media.ExifInterface
import java.io.File
import java.io.ByteArrayOutputStream
import androidx.core.content.FileProvider
import com.cleardose.app.network.GeminiClient
import com.cleardose.app.network.VehicleSpecs
import com.cleardose.app.network.GeminiResult
import kotlinx.coroutines.launch
import kotlinx.coroutines.Dispatchers

object DosageLimits {
    const val MAX_VEHICLE_AREA_M2 = 40.0
    const val MAX_WATER_LITERS = 150.0 
    const val MAX_PRODUCT_LITERS = 2.0
    const val TRADITIONAL_WASH_LITERS = 150.0
}

enum class AppStep { SCAN_CAR_AR, AREA_RESULT, PRODUCT_SELECTION, RESULT }
enum class AppTab { GUIDE, MEASURE, HISTORY, PROFILE }

enum class ProductType(val label: String, val ratioPerLiter: Double) {
    JABON("Jabón Concentrado", 10.0),
    DETERGENTE("Detergente Activo", 20.0)
}

enum class DirtinessLevel(val label: String, val factor: Double) {
    LOW("BAJA (POLVO/LIGERA)", 0.8),
    MEDIUM("MEDIA (SUCIEDAD ESTÁNDAR)", 1.0),
    HIGH("ALTA (LODO/MUGRE SECA)", 1.4)
}

data class VehicleDimensions(
    val length: Double,
    val height: Double,
    val width: Double,
    val totalArea: Double,
    val isSimulated: Boolean = false,
    val dirtinessLevel: DirtinessLevel = DirtinessLevel.MEDIUM
)

enum class WashMethod(val label: String, val waterPerM2: Double, val description: String) {
    PRESSURE_WASHER("Hidrolavadora (Eco)", 1.5, "Alta presión y consumo optimizado."),
    BUCKET("Balde Manual (Eco)", 0.0, "Uso controlado con baldes. Agua fija (15L)."),
    HOSE("Manguera Tradicional", 4.5, "Flujo continuo. Mayor consumo de agua.")
}

fun calculateDosage(dimensions: VehicleDimensions, method: WashMethod, product: ProductType): Pair<Double, Int> {
    val rawWaterLiters = if (method == WashMethod.BUCKET) {
        15.0
    } else {
        dimensions.totalArea * method.waterPerM2
    }
    val waterLiters = (rawWaterLiters.coerceIn(0.0, DosageLimits.MAX_WATER_LITERS) * 10).roundToInt() / 10.0
    val productMl = waterLiters * product.ratioPerLiter * dimensions.dirtinessLevel.factor
    val finalProductMl = productMl.coerceAtMost(DosageLimits.MAX_PRODUCT_LITERS * 1000.0).roundToInt()
    return Pair(waterLiters, finalProductMl)
}

class MainActivity : ComponentActivity() {
    private var arManager: ARCoreManager? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        arManager = ARCoreManager(this)
        enableEdgeToEdge()
        setContent { ClearDoseTheme { MainScreen(arManager!!) } }
    }

}

@Composable
fun MainScreen(arManager: ARCoreManager) {
    var currentTab by remember { mutableStateOf(AppTab.GUIDE) }
    var currentStep by remember { mutableStateOf(AppStep.SCAN_CAR_AR) }
    var vehicleDimensions by remember { mutableStateOf(VehicleDimensions(0.0, 0.0, 0.0, 0.0)) } 
    var selectedWashMethod by remember { mutableStateOf(WashMethod.PRESSURE_WASHER) }
    var selectedProduct by remember { mutableStateOf(ProductType.JABON) }
    var isPremiumService by remember { mutableStateOf(false) }

    val historyList = remember {
        mutableStateListOf(
            HistoryRecord("CD-9048", "SUV FAMILIAR", "Hace 15 min", "4.85m x 1.45m", 112.5, 450, R.drawable.suv_glass, isPremium = true, waxMl = 35),
            HistoryRecord("CD-9047", "SEDÁN COMERCIAL", "Hoy 11:20", "4.30m x 1.40m", 125.0, 500, R.drawable.sedan_glass, isPremium = false, waxMl = 0),
            HistoryRecord("CD-9046", "PICKUP INDUSTRIAL", "Hoy 09:45", "5.40m x 1.80m", 98.0, 390, R.drawable.pickup_glass, isPremium = false, waxMl = 0),
            HistoryRecord("CD-9045", "SEDÁN COMERCIAL", "Ayer 16:30", "4.20m x 1.38m", 126.5, 506, R.drawable.sedan_glass, isPremium = true, waxMl = 28),
            HistoryRecord("CD-9044", "SUV FAMILIAR", "Ayer 10:15", "4.70m x 1.42m", 115.0, 460, R.drawable.suv_glass, isPremium = false, waxMl = 0)
        )
    }


    val context = LocalContext.current
    val sharedPrefs = remember { context.getSharedPreferences("cleardose_prefs", Context.MODE_PRIVATE) }
    var apiKey by remember { 
        val savedKey = sharedPrefs.getString("gemini_api_key", "") ?: ""
        mutableStateOf(savedKey)
    }
    var measureMethod by remember { mutableStateOf(sharedPrefs.getString("measure_method", "AR") ?: "AR") }

    val updateMeasureMethod = { method: String ->
        measureMethod = method
        sharedPrefs.edit().putString("measure_method", method).apply()
    }

    var selectedModel by remember { mutableStateOf(sharedPrefs.getString("selected_model", "gemini-2.5-flash") ?: "gemini-2.5-flash") }

    val updateSelectedModel = { model: String ->
        selectedModel = model
        sharedPrefs.edit().putString("selected_model", model).apply()
    }

    var hasCameraPermission by remember {
        mutableStateOf(ContextCompat.checkSelfPermission(context, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED)
    }

    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { hasCameraPermission = it }

    val radialGradient = Brush.radialGradient(
        colors = listOf(NavyBlue, DeepBlack),
        center = Offset.Unspecified,
        radius = Float.POSITIVE_INFINITY
    )

    Surface(modifier = Modifier.fillMaxSize(), color = DeepBlack) {
        Box(modifier = Modifier.fillMaxSize()) {
            when (currentTab) {
                AppTab.GUIDE -> {
                    Box(modifier = Modifier.fillMaxSize().background(radialGradient)) {
                        OnboardingGuideScreen(
                            onStartMeasure = {
                                currentTab = AppTab.MEASURE
                                currentStep = AppStep.SCAN_CAR_AR
                            }
                        )
                    }
                }
                AppTab.MEASURE -> {
                    if (hasCameraPermission) {
                        if (currentStep == AppStep.SCAN_CAR_AR) {
                            if (measureMethod == "AR") {
                                ARSurfaceView(arManager)
                                CarARScannerScreen(
                                    arManager = arManager,
                                    onComplete = { dims ->
                                        vehicleDimensions = dims
                                        currentStep = AppStep.AREA_RESULT
                                    },
                                    onSwitchToPhoto = {
                                        updateMeasureMethod("PHOTO")
                                    }
                                )
                            } else {
                                CarPhotoScannerScreen(
                                    apiKey = apiKey,
                                    selectedModel = selectedModel,
                                    onModelChanged = updateSelectedModel,
                                    onComplete = { dims ->
                                        vehicleDimensions = dims
                                        currentStep = AppStep.AREA_RESULT
                                    },
                                    onSwitchToAR = {
                                        updateMeasureMethod("AR")
                                    },
                                    onNavigateToProfile = {
                                        currentTab = AppTab.PROFILE
                                    }
                                )
                            }
                        } else {
                            Box(modifier = Modifier.fillMaxSize().background(radialGradient)) {
                                when (currentStep) {
                                    AppStep.AREA_RESULT -> AreaResultScreen(vehicleDimensions) {
                                        currentStep = AppStep.PRODUCT_SELECTION
                                    }
                                    AppStep.PRODUCT_SELECTION -> ProductSelectionScreen(
                                        dimensions = vehicleDimensions,
                                        isPremium = isPremiumService,
                                        onPremiumToggle = { isPremiumService = it }
                                    ) { method, product ->
                                        selectedWashMethod = method
                                        selectedProduct = product
                                        currentStep = AppStep.RESULT
                                    }
                                    AppStep.RESULT -> FinalResultScreen(
                                        dimensions = vehicleDimensions,
                                        method = selectedWashMethod,
                                        product = selectedProduct,
                                        isPremium = isPremiumService
                                    ) {
                                        val (waterLiters, finalProductMl) = calculateDosage(vehicleDimensions, selectedWashMethod, selectedProduct)
                                        val savedLiters = (DosageLimits.TRADITIONAL_WASH_LITERS - waterLiters).coerceAtLeast(0.0)
                                        val waxMl = if (isPremiumService) (vehicleDimensions.totalArea * 3.0).roundToInt() else 0
                                        val label = when {
                                            vehicleDimensions.length < 4.5 -> "SEDÁN COMERCIAL"
                                            vehicleDimensions.length < 5.2 -> "SUV FAMILIAR"
                                            else -> "PICKUP INDUSTRIAL"
                                        }
                                        val imgRes = when {
                                            vehicleDimensions.length < 4.5 -> R.drawable.sedan_glass
                                            vehicleDimensions.length < 5.2 -> R.drawable.suv_glass
                                            else -> R.drawable.pickup_glass
                                        }
                                        val formattedSavedLiters = (savedLiters * 10).roundToInt() / 10.0

                                        historyList.add(0, HistoryRecord(
                                            id = "CD-${(9049..9999).random()}",
                                            type = label,
                                            time = "Hace un momento",
                                            dims = "${String.format(java.util.Locale.US, "%.2f", vehicleDimensions.length)}m x ${String.format(java.util.Locale.US, "%.2f", vehicleDimensions.height)}m",
                                            waterSaved = formattedSavedLiters,
                                            soapMl = finalProductMl,
                                            imageRes = imgRes,
                                            isPremium = isPremiumService,
                                            waxMl = waxMl
                                        ))

                                        isPremiumService = false
                                        currentStep = AppStep.SCAN_CAR_AR
                                    }
                                    else -> {}
                                }
                            }
                        }
                    } else {
                        Box(modifier = Modifier.fillMaxSize().background(radialGradient)) {
                            CameraPermissionRequestScreen {
                                launcher.launch(Manifest.permission.CAMERA)
                            }
                        }
                    }
                }
                AppTab.HISTORY -> {
                    Box(modifier = Modifier.fillMaxSize().background(radialGradient)) {
                        HistoryScreen(items = historyList)
                    }
                }
                AppTab.PROFILE -> {
                    Box(modifier = Modifier.fillMaxSize().background(radialGradient)) {
                        ProfileScreen(
                            apiKey = apiKey,
                            onApiKeyChanged = { newKey ->
                                apiKey = newKey
                                sharedPrefs.edit().putString("gemini_api_key", newKey).apply()
                            },
                            selectedModel = selectedModel,
                            onModelChanged = updateSelectedModel
                        )
                    }
                }
            }
            
            // Top Stepper (Premium Weather-App Style)
            if (currentTab == AppTab.MEASURE && hasCameraPermission) {
                TopStepper(currentStep)
            } else {
                TopAppBarHeader(currentTab)
            }
            
            // HUD corner lines only in Scanning step (very clean)
            if (currentTab == AppTab.MEASURE && hasCameraPermission && currentStep == AppStep.SCAN_CAR_AR) {
                HUDCornerLines()
            }
            
            // Bottom Tab Navigation Bar
            Box(modifier = Modifier.align(Alignment.BottomCenter)) {
                BottomNavigationBar(currentTab) { selectedTab ->
                    triggerHapticFeedback(context)
                    currentTab = selectedTab
                    if (selectedTab == AppTab.MEASURE && currentStep == AppStep.RESULT) {
                        currentStep = AppStep.SCAN_CAR_AR
                    }
                }
            }
        }
    }
}


@Composable
fun ARSurfaceView(arManager: ARCoreManager) {
    val lifecycleOwner = androidx.lifecycle.compose.LocalLifecycleOwner.current
    
    DisposableEffect(lifecycleOwner, arManager) {
        val observer = androidx.lifecycle.LifecycleEventObserver { _, event ->
            if (event == androidx.lifecycle.Lifecycle.Event.ON_RESUME) {
                arManager.resume()
            } else if (event == androidx.lifecycle.Lifecycle.Event.ON_PAUSE) {
                arManager.pause()
            }
        }
        
        lifecycleOwner.lifecycle.addObserver(observer)
        
        if (lifecycleOwner.lifecycle.currentState.isAtLeast(androidx.lifecycle.Lifecycle.State.RESUMED)) {
            arManager.resume()
        }
        
        onDispose {
            lifecycleOwner.lifecycle.removeObserver(observer)
            arManager.pause()
        }
    }

    AndroidView(
        factory = { ctx ->
            GLSurfaceView(ctx).apply {
                setEGLContextClientVersion(2)
                setRenderer(arManager)
            }
        },
        modifier = Modifier.fillMaxSize()
    )
}

@Composable
fun TopStepper(step: AppStep) {
    val steps = listOf(
        AppStep.SCAN_CAR_AR to "ESCANEO",
        AppStep.AREA_RESULT to "DIAGNÓSTICO",
        AppStep.PRODUCT_SELECTION to "PRODUCTO",
        AppStep.RESULT to "DOSIS"
    )
    
    val currentIdx = steps.indexOfFirst { it.first == step }.coerceAtLeast(0)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp, start = 20.dp, end = 20.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(CardBgColor)
            .border(0.5.dp, CardBorderColor, RoundedCornerShape(16.dp))
            .padding(vertical = 12.dp, horizontal = 16.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween
    ) {
        steps.forEachIndexed { idx, (_, label) ->
            val isActive = idx == currentIdx
            val isDone = idx < currentIdx
            val tintColor = if (isActive) NeonCyan else if (isDone) NeonGreen else SmokeGrey.copy(alpha = 0.5f)

            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                verticalArrangement = Arrangement.Center
            ) {
                Text(
                    text = label,
                    color = tintColor,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
                Spacer(modifier = Modifier.height(4.dp))
                Box(
                    modifier = Modifier
                        .size(6.dp)
                        .background(tintColor, CircleShape)
                )
            }

            if (idx < steps.size - 1) {
                Box(
                    modifier = Modifier
                        .weight(1f)
                        .padding(horizontal = 4.dp)
                        .height(1.dp)
                        .background(if (idx < currentIdx) NeonGreen else CardBorderColor)
                )
            }
        }
    }
}

data class TabItem(
    val label: String,
    val tab: AppTab,
    val icon: @Composable (Color) -> Unit
)

@Composable
fun BottomNavigationBar(currentTab: AppTab, onTabSelected: (AppTab) -> Unit) {
    val tabs = listOf(
        TabItem("Medir", AppTab.MEASURE) { tint ->
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(tint, radius = size.minDimension / 3f, style = Stroke(1.5.dp.toPx()))
                drawCircle(tint, radius = 2.dp.toPx())
            }
        },
        TabItem("Historial", AppTab.HISTORY) { tint ->
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawArc(tint, 0f, 270f, false, style = Stroke(1.5.dp.toPx()))
                drawLine(tint, start = Offset(size.width / 2f, size.height / 2f), end = Offset(size.width / 2f, size.height * 0.25f), strokeWidth = 1.5.dp.toPx())
                drawLine(tint, start = Offset(size.width / 2f, size.height / 2f), end = Offset(size.width * 0.7f, size.height / 2f), strokeWidth = 1.5.dp.toPx())
            }
        },
        TabItem("Guía de Dosis", AppTab.GUIDE) { tint ->
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawRect(tint, size = Size(size.width, size.height * 0.8f), style = Stroke(1.5.dp.toPx()))
                drawLine(tint, start = Offset(size.width * 0.2f, size.height * 0.3f), end = Offset(size.width * 0.8f, size.height * 0.3f), strokeWidth = 1.dp.toPx())
                drawLine(tint, start = Offset(size.width * 0.2f, size.height * 0.5f), end = Offset(size.width * 0.6f, size.height * 0.5f), strokeWidth = 1.dp.toPx())
            }
        },
        TabItem("Perfil", AppTab.PROFILE) { tint ->
            Canvas(modifier = Modifier.fillMaxSize()) {
                drawCircle(tint, radius = size.minDimension / 4f, center = Offset(size.width / 2f, size.height * 0.35f), style = Stroke(1.5.dp.toPx()))
                drawArc(tint, 180f, 180f, false, topLeft = Offset(0f, size.height * 0.6f), size = Size(size.width, size.height * 0.8f), style = Stroke(1.5.dp.toPx()))
            }
        }
    )

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .height(72.dp)
            .background(
                brush = Brush.verticalGradient(
                    colors = listOf(Color.Transparent, DeepBlack.copy(0.95f))
                )
            )
            .border(BorderStroke(0.5.dp, CardBorderColor))
            .padding(horizontal = 16.dp),
        horizontalArrangement = Arrangement.SpaceAround,
        verticalAlignment = Alignment.CenterVertically
    ) {
        tabs.forEach { (label, tab, iconLambda) ->
            val isSelected = currentTab == tab
            val tint = if (isSelected) NeonBlue else SmokeGrey
            Column(
                horizontalAlignment = Alignment.CenterHorizontally,
                modifier = Modifier
                    .clickable(
                        interactionSource = remember { MutableInteractionSource() },
                        indication = null
                    ) {
                        onTabSelected(tab)
                    }
                    .padding(8.dp)
            ) {
                Box(
                    modifier = Modifier.size(20.dp),
                    contentAlignment = Alignment.Center
                ) {
                    iconLambda(tint)
                }
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = label,
                    color = tint,
                    fontSize = 10.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal
                )
            }
        }
    }
}

@Composable
fun HolographicScaleHUD(refMeters: Double) {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val centerX = size.width / 2f
        val centerY = size.height / 2f
        
        val scaleHeight = (250.dp.toPx() * refMeters.toFloat()).coerceIn(40.dp.toPx(), 400.dp.toPx())
        
        drawLine(
            color = NeonCyan,
            start = Offset(centerX + 60.dp.toPx(), centerY - scaleHeight / 2f),
            end = Offset(centerX + 60.dp.toPx(), centerY + scaleHeight / 2f),
            strokeWidth = 2.dp.toPx()
        )
        
        val marks = listOf(-0.5f, 0f, 0.5f)
        marks.forEach { pos ->
            drawLine(
                color = NeonCyan,
                start = Offset(centerX + 50.dp.toPx(), centerY + pos * scaleHeight),
                end = Offset(centerX + 70.dp.toPx(), centerY + pos * scaleHeight),
                strokeWidth = 2.dp.toPx()
            )
        }
    }
}

@Composable
fun CarARScannerScreen(
    arManager: ARCoreManager,
    onComplete: (VehicleDimensions) -> Unit,
    onSwitchToPhoto: () -> Unit
) {
    val context = LocalContext.current
    var stepIndex by remember { mutableStateOf(0) }
    val capturedPoints = remember { mutableStateListOf<RealWorldPoint>() }
    var calibrationRefMeters by remember { mutableStateOf(1.0) }
    var calibratedDistance by remember { mutableStateOf(2.0f) }
    var isTargetingSurface by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        while (true) {
            isTargetingSurface = arManager.hitTestCenter() != null
            kotlinx.coroutines.delay(150)
        }
    }
    
    val refText = if (calibrationRefMeters < 1.0) "${(calibrationRefMeters*100).toInt()} cm" else "${calibrationRefMeters.toInt()} m"
    val instructions = listOf(
        "IA: Alinea la VARA CIAN con $refText real",
        "IA: Marca el FOCO DELANTERO (Punto 1/4)",
        "IA: Marca el FOCO TRASERO (Punto 2/4)",
        "IA: Marca el TECHO (Punto 3/4)",
        "IA: Marca el SUELO (Punto 4/4)"
    )

    Box(modifier = Modifier.fillMaxSize()) {
        HolographicScaleHUD(calibrationRefMeters)
        MeshScanEffect()
        BoundingBoxValidator(stepIndex)
        
        // Mode Switcher Toggle
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 100.dp)
                .width(240.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Charcoal.copy(0.9f))
                .border(1.dp, NeonCyan.copy(0.5f), RoundedCornerShape(18.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(18.dp))
                    .background(NeonCyan),
                contentAlignment = Alignment.Center
            ) {
                Text("CÁMARA AR", color = Charcoal, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(18.dp))
                    .clickable { onSwitchToPhoto() },
                contentAlignment = Alignment.Center
            ) {
                Text("FOTO IA", color = Color.White.copy(0.8f), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }
        
        // Instruction Box (shifted down)
        Column(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 152.dp)
                .clip(RoundedCornerShape(8.dp))
                .background(Charcoal.copy(0.9f))
                .border(1.dp, NeonCyan, RoundedCornerShape(8.dp))
                .padding(horizontal = 16.dp, vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(text = instructions[stepIndex], color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text(
                text = if (isTargetingSurface) "APUNTANDO A SUPERFICIE (PRECISIÓN ALTA)" else "APUNTANDO AL ESPACIO (ESTIMACIÓN RAYO ACTIVA)",
                color = if (isTargetingSurface) NeonGreen else NeonGold,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold
            )
        }

        // Simular/Omitir Scan Button (Top Right, shifted down)
        Box(
            modifier = Modifier
                .align(Alignment.TopEnd)
                .padding(top = 152.dp, end = 24.dp)
        ) {
            Button(
                onClick = {
                    triggerHapticFeedback(context)
                    onComplete(VehicleDimensions(4.3, 1.4, 1.72, 22.5, true))
                },
                colors = ButtonDefaults.buttonColors(containerColor = Charcoal.copy(0.9f)),
                border = BorderStroke(1.dp, NeonCyan),
                shape = RoundedCornerShape(16.dp),
                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
            ) {
                Text("SIMULAR", color = NeonCyan, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }

        // Reference selector
        if (stepIndex == 0) {
            Row(
                modifier = Modifier
                    .align(Alignment.Center)
                    .padding(top = 180.dp)
                    .background(Charcoal.copy(0.9f), RoundedCornerShape(16.dp))
                    .border(1.dp, NeonCyan.copy(0.6f), RoundedCornerShape(16.dp))
                    .padding(8.dp),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                listOf(0.01 to "1 cm", 0.1 to "10 cm", 1.0 to "1 m", 2.0 to "2 m").forEach { (valM, label) ->
                    val isSel = calibrationRefMeters == valM
                    Box(
                        modifier = Modifier
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSel) NeonCyan else Color.Transparent)
                            .clickable { calibrationRefMeters = valM }
                            .padding(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text(
                            text = label,
                            color = if (isSel) DeepBlack else Color.White,
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Box(modifier = Modifier.size(120.dp).align(Alignment.Center)) {
            Canvas(modifier = Modifier.fillMaxSize()) {
                val strokePx = 1.dp.toPx()
                drawArc(NeonCyan, 0f, 30f, false, style = Stroke(strokePx))
                drawArc(NeonCyan, 90f, 30f, false, style = Stroke(strokePx))
                drawArc(NeonCyan, 180f, 30f, false, style = Stroke(strokePx))
                drawArc(NeonCyan, 270f, 30f, false, style = Stroke(strokePx))
                drawCircle(NeonGreen, radius = 2.dp.toPx())
            }
        }

        Box(modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 60.dp).padding(horizontal = 32.dp)) {
            Button(
                onClick = {
                    triggerHapticFeedback(context)
                    
                    val point = arManager.hitTestCenter()
                    
                    if (stepIndex == 0) {
                        if (point != null) {
                            arManager.lastFrame?.camera?.pose?.let { cameraPose ->
                                val dist = sqrt(
                                    (point.x - cameraPose.tx()).pow(2f) +
                                    (point.y - cameraPose.ty()).pow(2f) +
                                    (point.z - cameraPose.tz()).pow(2f)
                                )
                                calibratedDistance = dist.coerceIn(0.5f, 5.0f)
                                Log.d("ARScale", "Distancia calibrada: $calibratedDistance metros")
                            }
                        } else {
                            calibratedDistance = 2.0f
                        }
                        stepIndex++
                    } else {
                        val finalPoint = if (point != null) {
                            point
                        } else {
                            arManager.lastFrame?.camera?.pose?.let { cameraPose ->
                                val forward = cameraPose.rotateVector(floatArrayOf(0f, 0f, -1f))
                                RealWorldPoint(
                                    x = cameraPose.tx() + forward[0] * calibratedDistance,
                                    y = cameraPose.ty() + forward[1] * calibratedDistance,
                                    z = cameraPose.tz() + forward[2] * calibratedDistance
                                )
                            } ?: RealWorldPoint(
                                x = when (capturedPoints.size) {
                                    0 -> 0.0f
                                    1 -> 4.3f
                                    2 -> 2.15f
                                    else -> 2.15f
                                },
                                y = when (capturedPoints.size) {
                                    2 -> 1.4f
                                    3 -> 0.0f
                                    else -> 0.5f
                                },
                                z = -3.0f
                            )
                        }

                        capturedPoints.add(finalPoint)
                        if (stepIndex < instructions.size - 1) stepIndex++
                        else onComplete(calculateVehicleDimensions(capturedPoints))
                    }
                },
                modifier = Modifier.fillMaxWidth().height(64.dp).shadow(12.dp, RoundedCornerShape(32.dp), ambientColor = NeonCyan, spotColor = NeonCyan),
                colors = ButtonDefaults.buttonColors(
                    containerColor = if (stepIndex == 0) TextGreen else TextBlue
                ),
                shape = RoundedCornerShape(32.dp)
            ) {
                Text(text = if (stepIndex == 0) "ESCALA CALIBRADA" else "FIJAR VÉRTICE", color = Color.White, fontWeight = FontWeight.Black)
            }
        }
    }
}

@Composable
fun GlassCard(modifier: Modifier = Modifier, borderColor: Color = CardBorderColor, content: @Composable ColumnScope.() -> Unit) {
    Box(modifier = modifier.clip(RoundedCornerShape(24.dp)).background(CardBgColor).border(0.5.dp, borderColor, RoundedCornerShape(24.dp))) {
        Column(modifier = Modifier.padding(24.dp), horizontalAlignment = Alignment.CenterHorizontally, content = content)
    }
}

@Composable
fun HUDCornerLines() {
    Canvas(modifier = Modifier.fillMaxSize()) {
        val lineLength = 40.dp.toPx()
        val strokeWidth = 1.dp.toPx()
        val color = NeonCyan.copy(alpha = 0.5f)
        drawLine(color, Offset(20.dp.toPx(), 60.dp.toPx()), Offset(20.dp.toPx() + lineLength, 60.dp.toPx()), strokeWidth)
        drawLine(color, Offset(20.dp.toPx(), 60.dp.toPx()), Offset(20.dp.toPx(), 60.dp.toPx() + lineLength), strokeWidth)
        drawLine(color, Offset(size.width - 20.dp.toPx(), 60.dp.toPx()), Offset(size.width - 20.dp.toPx() - lineLength, 60.dp.toPx()), strokeWidth)
        drawLine(color, Offset(size.width - 20.dp.toPx(), 60.dp.toPx()), Offset(size.width - 20.dp.toPx(), 60.dp.toPx() + lineLength), strokeWidth)
        drawLine(color, Offset(20.dp.toPx(), size.height - 40.dp.toPx()), Offset(20.dp.toPx() + lineLength, size.height - 40.dp.toPx()), strokeWidth)
        drawLine(color, Offset(20.dp.toPx(), size.height - 40.dp.toPx()), Offset(20.dp.toPx(), size.height - 40.dp.toPx() - lineLength), strokeWidth)
        drawLine(color, Offset(size.width - 20.dp.toPx(), size.height - 40.dp.toPx()), Offset(size.width - 20.dp.toPx() - lineLength, size.height - 40.dp.toPx()), strokeWidth)
        drawLine(color, Offset(size.width - 20.dp.toPx(), size.height - 40.dp.toPx()), Offset(size.width - 20.dp.toPx(), size.height - 40.dp.toPx() - lineLength), strokeWidth)
    }
}

@Composable
fun MeshScanEffect() {
    val infiniteTransition = rememberInfiniteTransition(label = "mesh")
    val scanY by infiniteTransition.animateFloat(initialValue = 0f, targetValue = 1f, animationSpec = infiniteRepeatable(tween(4000, easing = LinearEasing)), label = "y")
    Canvas(modifier = Modifier.fillMaxSize()) {
        val strokeWidth = 0.5.dp.toPx()
        val color = NeonCyan.copy(alpha = 0.2f)
        drawLine(brush = Brush.horizontalGradient(listOf(Color.Transparent, NeonCyan, Color.Transparent)), start = Offset(0f, size.height * scanY), end = Offset(size.width, size.height * scanY), strokeWidth = 2.dp.toPx())
        val gridStep = 50.dp.toPx()
        for (i in 0..(size.width / gridStep).toInt()) drawLine(color, Offset(i * gridStep, 0f), Offset(i * gridStep, size.height), strokeWidth)
        for (i in 0..(size.height / gridStep).toInt()) drawLine(color, Offset(0f, i * gridStep), Offset(size.width, i * gridStep), strokeWidth)
    }
}

@Composable
fun BoundingBoxValidator(step: Int) {
    val infiniteTransition = rememberInfiniteTransition(label = "box")
    val dashOffset by infiniteTransition.animateFloat(initialValue = 0f, targetValue = 40f, animationSpec = infiniteRepeatable(tween(1000, easing = LinearEasing)), label = "dash")
    Canvas(modifier = Modifier.fillMaxSize()) {
        if (step >= 2) {
            val width = size.width * 0.7f
            val height = size.height * 0.3f
            val topLeft = Offset(size.width * 0.15f, size.height * 0.35f)
            val isProportionValid = (width / height) > 1.2 
            val boxColor = if (isProportionValid) NeonGreen else NeonRed
            drawRoundRect(color = boxColor, topLeft = topLeft, size = Size(width, height), cornerRadius = CornerRadius(12.dp.toPx()), style = Stroke(width = 2.dp.toPx(), pathEffect = PathEffect.dashPathEffect(floatArrayOf(20f, 10f), dashOffset)))
        }
    }
}

@Composable
fun GlassCardMini(label: String, value: String) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(CardBgColor)
            .border(0.5.dp, CardBorderColor, RoundedCornerShape(16.dp))
            .padding(vertical = 12.dp, horizontal = 8.dp),
        contentAlignment = Alignment.Center
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text(label, color = SmokeGrey, fontSize = 9.sp, letterSpacing = 1.sp, fontWeight = FontWeight.Bold)
            Spacer(modifier = Modifier.height(4.dp))
            Text(value, color = TextColor, fontSize = 14.sp, fontWeight = FontWeight.Black)
        }
    }
}

@Composable
fun AreaResultScreen(dimensions: VehicleDimensions, onNext: () -> Unit) {
    val carImageRes = when {
        dimensions.length < 4.5 -> R.drawable.sedan_glass
        dimensions.length < 5.2 -> R.drawable.suv_glass
        else -> R.drawable.pickup_glass
    }
    val carLabel = when {
        dimensions.length < 4.5 -> "SEDÁN COMERCIAL"
        dimensions.length < 5.2 -> "SUV FAMILIAR"
        else -> "PICKUP INDUSTRIAL"
    }

    val infiniteTransition = rememberInfiniteTransition(label = "blueprint")
    val scanOffset by infiniteTransition.animateFloat(
        initialValue = -10f,
        targetValue = 250f,
        animationSpec = infiniteRepeatable(
            animation = tween(2000, easing = LinearEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "scan"
    )

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(top = 104.dp, bottom = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        if (dimensions.isSimulated) {
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(8.dp))
                    .background(NeonCyan.copy(0.12f))
                    .border(0.5.dp, NeonCyan.copy(0.5f), RoundedCornerShape(8.dp))
                    .padding(vertical = 8.dp, horizontal = 12.dp),
                contentAlignment = Alignment.Center
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Box(modifier = Modifier.size(6.dp).background(TextBlue, CircleShape))
                    Spacer(modifier = Modifier.width(8.dp))
                    Text(
                        "VALORES SIMULADOS (AUTO COMPACTO ESTÁNDAR)",
                        color = TextBlue,
                        fontSize = 9.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                }
            }
        }

        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            Text("SUPERFICIE TOTAL DE LAVADO", color = SmokeGrey, fontSize = 10.sp, letterSpacing = 2.sp)
            Text(
                text = String.format(java.util.Locale.US, "%.1f m²", dimensions.totalArea),
                color = TextColor,
                fontSize = 48.sp,
                fontWeight = FontWeight.Light,
                letterSpacing = (-1).sp
            )
            Text(carLabel, color = TextBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(modifier = Modifier.height(6.dp))
            val dirtColor = when(dimensions.dirtinessLevel) {
                DirtinessLevel.LOW -> NeonGreen
                DirtinessLevel.HIGH -> NeonRed
                else -> NeonCyan
            }
            val dirtTextColor = when(dimensions.dirtinessLevel) {
                DirtinessLevel.LOW -> TextGreen
                DirtinessLevel.HIGH -> TextRed
                else -> TextBlue
            }
            Box(
                modifier = Modifier
                    .clip(RoundedCornerShape(12.dp))
                    .background(dirtColor.copy(0.15f))
                    .border(0.5.dp, dirtColor.copy(0.5f), RoundedCornerShape(12.dp))
                    .padding(horizontal = 12.dp, vertical = 4.dp)
            ) {
                Text(
                    text = "SUCIEDAD: ${dimensions.dirtinessLevel.label}",
                    color = dirtTextColor,
                    fontSize = 8.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp
                )
            }
        }

        Box(
            modifier = Modifier
                .size(240.dp)
                .clip(RoundedCornerShape(24.dp))
                .background(CardBgColor)
                .border(0.5.dp, CardBorderColor, RoundedCornerShape(24.dp)),
            contentAlignment = Alignment.Center
        ) {
            Image(
                painter = painterResource(id = carImageRes),
                contentDescription = carLabel,
                modifier = Modifier
                    .fillMaxSize()
                    .padding(16.dp)
            )

            Canvas(modifier = Modifier.fillMaxSize()) {
                val width = size.width
                val height = size.height
                val scanY = height * 0.1f + (height * 0.8f) * (scanOffset / 250f)
                
                drawLine(
                    brush = Brush.horizontalGradient(
                        colors = listOf(Color.Transparent, NeonCyan.copy(0.6f), Color.Transparent)
                    ),
                    start = Offset(width * 0.1f, scanY),
                    end = Offset(width * 0.9f, scanY),
                    strokeWidth = 2.dp.toPx()
                )
            }
        }

        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Box(modifier = Modifier.weight(1f)) {
                GlassCardMini(label = "LARGO", value = String.format(java.util.Locale.US, "%.2f m", dimensions.length))
            }
            Box(modifier = Modifier.weight(1f)) {
                GlassCardMini(label = "ALTO", value = String.format(java.util.Locale.US, "%.2f m", dimensions.height))
            }
            Box(modifier = Modifier.weight(1f)) {
                GlassCardMini(label = "ANCHO EST.", value = String.format(java.util.Locale.US, "%.2f m", dimensions.width))
            }
        }

        Button(
            onClick = onNext,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .shadow(16.dp, RoundedCornerShape(28.dp), spotColor = NeonCyan),
            colors = ButtonDefaults.buttonColors(containerColor = TextBlue),
            shape = RoundedCornerShape(28.dp)
        ) {
            Text("CONTINUAR A DOSIFICACIÓN", color = Color.White, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        }
    }
}

@Composable
fun ProductSelectionScreen(
    dimensions: VehicleDimensions,
    isPremium: Boolean,
    onPremiumToggle: (Boolean) -> Unit,
    onSelectionComplete: (WashMethod, ProductType) -> Unit
) {
    var selectedMethod by remember { mutableStateOf(WashMethod.PRESSURE_WASHER) }
    var selectedProd by remember { mutableStateOf(ProductType.JABON) }
    
    val (waterLiters, productMl) = calculateDosage(dimensions, selectedMethod, selectedProd)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 20.dp)
            .padding(top = 104.dp, bottom = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            "AJUSTES DE DOSIFICACIÓN",
            color = TextBlue,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 4.sp
        )

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "MÉTODO DE TRABAJO",
                color = SmokeGrey,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                modifier = Modifier.padding(start = 4.dp)
            )

            WashMethod.entries.forEach { method ->
                val isSelected = method == selectedMethod
                val borderColor = if (isSelected) NeonBlue.copy(0.6f) else CardBorderColor
                val backgroundColor = if (isSelected) NeonCyan.copy(0.12f) else CardBgColor

                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(16.dp))
                        .background(backgroundColor)
                        .border(0.5.dp, borderColor, RoundedCornerShape(16.dp))
                        .clickable { selectedMethod = method }
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            method.label.uppercase(),
                            color = if (isSelected) NeonBlue else TextColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 12.sp
                        )
                        Text(
                            method.description,
                            color = SmokeGrey,
                            fontSize = 9.sp,
                            lineHeight = 12.sp
                        )
                    }
                    Spacer(modifier = Modifier.width(8.dp))
                    Box(
                        modifier = Modifier
                            .size(14.dp)
                            .border(1.dp, if (isSelected) NeonCyan else SmokeGrey.copy(0.5f), CircleShape)
                            .padding(3.dp)
                    ) {
                        if (isSelected) {
                            Box(
                                modifier = Modifier
                                    .fillMaxSize()
                                    .background(NeonCyan, CircleShape)
                            )
                        }
                    }
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                "INSUMO ACTIVO",
                color = SmokeGrey,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 2.sp,
                modifier = Modifier.padding(start = 4.dp)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                ProductType.entries.forEach { prod ->
                    val isSelected = prod == selectedProd
                    val activeColor = if (prod == ProductType.JABON) NeonGreen else NeonCyan
                    val borderColor = if (isSelected) activeColor.copy(0.6f) else CardBorderColor
                    val backgroundColor = if (isSelected) activeColor.copy(0.12f) else CardBgColor

                    Column(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(16.dp))
                            .background(backgroundColor)
                            .border(0.5.dp, borderColor, RoundedCornerShape(16.dp))
                            .clickable { selectedProd = prod }
                            .padding(14.dp),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Box(
                            modifier = Modifier
                                .size(8.dp)
                                .background(if (isSelected) activeColor else SmokeGrey.copy(0.5f), CircleShape)
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            prod.label.uppercase(),
                            color = TextColor,
                            fontWeight = FontWeight.Bold,
                            fontSize = 11.sp
                        )
                        Text(
                            "Ratio: ${prod.ratioPerLiter.toInt()}ml/L",
                            color = SmokeGrey,
                            fontSize = 9.sp
                        )
                    }
                }
            }
        }

        // Tarjeta de Servicio Premium
        val borderPremiumColor = if (isPremium) NeonGold.copy(0.6f) else CardBorderColor
        val backgroundPremiumColor = if (isPremium) NeonGold.copy(0.12f) else CardBgColor
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(backgroundPremiumColor)
                .border(0.5.dp, borderPremiumColor, RoundedCornerShape(16.dp))
                .clickable { onPremiumToggle(!isPremium) }
                .padding(horizontal = 16.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                modifier = Modifier.weight(1f)
            ) {
                Box(
                    modifier = Modifier
                        .size(28.dp)
                        .background(if (isPremium) NeonGold.copy(0.25f) else CardBorderColor, CircleShape)
                        .border(0.5.dp, if (isPremium) NeonGold else SmokeGrey.copy(0.3f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("★", color = if (isPremium) Charcoal else SmokeGrey, fontSize = 12.sp, fontWeight = FontWeight.Bold)
                }
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        "SERVICIO PREMIUM DE ENCERADO",
                        color = if (isPremium) Charcoal else TextColor,
                        fontWeight = FontWeight.Bold,
                        fontSize = 10.sp,
                        letterSpacing = 0.5.sp
                    )
                    Text(
                        "Añade cera hidrofóbica brillante a la receta.",
                        color = SmokeGrey,
                        fontSize = 8.sp
                    )
                }
            }
            Spacer(modifier = Modifier.width(8.dp))
            Switch(
                checked = isPremium,
                onCheckedChange = onPremiumToggle,
                colors = SwitchDefaults.colors(
                    checkedThumbColor = DeepBlack,
                    checkedTrackColor = NeonGold,
                    uncheckedThumbColor = SmokeGrey,
                    uncheckedTrackColor = Color.White.copy(0.1f)
                )
            )
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(CardBgColor)
                .border(0.5.dp, CardBorderColor, RoundedCornerShape(20.dp))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
            ) {
                Column {
                    Text("CONSUMO ESTIMADO", color = SmokeGrey, fontSize = 9.sp, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Row(verticalAlignment = Alignment.Bottom) {
                        Text(
                            text = String.format(java.util.Locale.US, "%.1f", waterLiters),
                            color = NeonBlue,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(" L", color = TextColor, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
                        Spacer(modifier = Modifier.width(16.dp))
                        Text(
                            text = "$productMl",
                            color = NeonBlue,
                            fontSize = 26.sp,
                            fontWeight = FontWeight.Black
                        )
                        Text(" ml", color = TextColor, fontSize = 11.sp, modifier = Modifier.padding(bottom = 4.dp))
                    }
                }

                val pct = ((1.0 - (waterLiters / DosageLimits.TRADITIONAL_WASH_LITERS)) * 100).coerceIn(0.0, 100.0).roundToInt()
                Column(horizontalAlignment = Alignment.End) {
                    Text("AHORRO EST.", color = SmokeGrey, fontSize = 9.sp)
                    Text("$pct%", color = NeonGreen, fontSize = 20.sp, fontWeight = FontWeight.Bold)
                }
            }
        }

        Button(
            onClick = { onSelectionComplete(selectedMethod, selectedProd) },
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .shadow(12.dp, RoundedCornerShape(28.dp), spotColor = NeonCyan),
            colors = ButtonDefaults.buttonColors(containerColor = TextBlue),
            shape = RoundedCornerShape(28.dp)
        ) {
            Text("VER REPORTE DE DOSIS", color = Color.White, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        }
    }
}

@Composable
fun FinalResultScreen(
    dimensions: VehicleDimensions,
    method: WashMethod,
    product: ProductType,
    isPremium: Boolean,
    onReset: () -> Unit
) {
    val (waterLiters, finalProductMl) = calculateDosage(dimensions, method, product)
    val savedLiters = (DosageLimits.TRADITIONAL_WASH_LITERS - waterLiters).coerceAtLeast(0.0)
    val waxMl = if (isPremium) (dimensions.totalArea * 3.0).roundToInt() else 0

    val reportColor = if (isPremium) NeonGold else NeonGreen
    val reportTitle = if (isPremium) "REPORTE PREMIUM DE PREPARACIÓN" else "REPORTE DE PREPARACIÓN"
    val cardBorderColor = if (isPremium) NeonGold.copy(0.4f) else NeonGreen.copy(0.3f)
    val cardSpotColor = if (isPremium) NeonGold.copy(0.15f) else NeonGreen.copy(0.1f)

    Column(
        modifier = Modifier
            .fillMaxSize()
            .padding(horizontal = 24.dp)
            .padding(top = 104.dp, bottom = 80.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.SpaceBetween
    ) {
        Text(
            text = reportTitle,
            color = reportColor,
            fontSize = 11.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(CardBgColor)
                .border(0.5.dp, cardBorderColor, RoundedCornerShape(24.dp))
                .shadow(12.dp, RoundedCornerShape(24.dp), spotColor = cardSpotColor)
                .padding(18.dp)
        ) {
            Column(horizontalAlignment = Alignment.CenterHorizontally) {
                if (isPremium) {
                    Box(
                        modifier = Modifier
                            .align(Alignment.End)
                            .background(NeonGold.copy(0.25f), RoundedCornerShape(12.dp))
                            .border(0.5.dp, NeonGold.copy(0.5f), RoundedCornerShape(12.dp))
                            .padding(horizontal = 8.dp, vertical = 3.dp)
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text("★", color = Charcoal, fontSize = 9.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text("SERVICIO PREMIUM", color = Charcoal, fontSize = 8.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                        }
                    }
                    Spacer(modifier = Modifier.height(12.dp))
                }
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = if (isPremium) Arrangement.SpaceBetween else Arrangement.SpaceAround,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    val valSize = if (isPremium) 30.sp else 42.sp
                    ResultDoseItem(label = "VOL. DE AGUA", value = String.format(java.util.Locale.US, "%.1f", waterLiters), unit = "L", color = NeonBlue, valueSize = valSize)
                    ResultDoseItem(label = product.label.uppercase(), value = "$finalProductMl", unit = "ML", color = NeonBlue, valueSize = valSize)
                    if (isPremium) {
                        ResultDoseItem(label = "CERA PROTECTORA", value = "$waxMl", unit = "ML", color = Charcoal, valueSize = valSize)
                    }
                }

                Spacer(modifier = Modifier.height(16.dp))
                HorizontalDivider(color = CardBorderColor, thickness = 0.5.dp)
                Spacer(modifier = Modifier.height(16.dp))

                Text(
                    "INSTRUCCIONES DE MEZCLA",
                    color = SmokeGrey,
                    fontSize = 9.sp,
                    fontWeight = FontWeight.Bold,
                    letterSpacing = 1.sp,
                    modifier = Modifier.padding(bottom = 10.dp)
                )

                Column(
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    StepItem(stepNum = 1, text = "Mide exactamente ${String.format(java.util.Locale.US, "%.1f", waterLiters)}L de agua limpia.")
                    StepItem(stepNum = 2, text = "Agrega $finalProductMl ml de ${product.label.lowercase()}.")
                    StepItem(stepNum = 3, text = "Mezcla despacio y utiliza tu ${method.label.lowercase()}.")
                    if (isPremium) {
                        StepItem(stepNum = 4, text = "Aplica los $waxMl ml de cera hidrofóbica uniformemente al finalizar para brillo y protección.")
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(CardBgColor)
                .border(0.5.dp, CardBorderColor, RoundedCornerShape(20.dp))
                .padding(16.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Box(
                    modifier = Modifier.size(60.dp),
                    contentAlignment = Alignment.Center
                ) {
                    val savingRatio = (savedLiters / DosageLimits.TRADITIONAL_WASH_LITERS).toFloat()
                    Canvas(modifier = Modifier.fillMaxSize()) {
                        drawCircle(color = CardBorderColor, style = Stroke(4.dp.toPx()))
                        drawArc(
                            color = NeonBlue,
                            startAngle = -90f,
                            sweepAngle = savingRatio * 360f,
                            useCenter = false,
                            style = Stroke(4.dp.toPx())
                        )
                    }
                    Text(
                        text = "${(savingRatio * 100).roundToInt()}%",
                        color = Charcoal,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Black
                    )
                }

                Column(modifier = Modifier.weight(1f)) {
                    Text("AHORRO DE RECURSO HÍDRICO", color = SmokeGrey, fontSize = 9.sp, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(
                        text = "¡Salvaste ${String.format(java.util.Locale.US, "%.1f", savedLiters)} litros de agua!",
                        color = TextColor,
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Bold
                    )
                    Text(
                        text = "Comparado con el lavado tradicional de 150L.",
                        color = SmokeGrey,
                        fontSize = 9.sp
                    )
                }
            }
        }

        Button(
            onClick = onReset,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .shadow(16.dp, RoundedCornerShape(28.dp), spotColor = NeonBlue),
            colors = ButtonDefaults.buttonColors(containerColor = NeonBlue),
            shape = RoundedCornerShape(28.dp)
        ) {
            Text("INICIAR NUEVO ANÁLISIS", color = Color.White, fontWeight = FontWeight.Black, letterSpacing = 2.sp)
        }
    }
}

@Composable
fun ResultDoseItem(
    label: String,
    value: String,
    unit: String,
    color: Color,
    valueSize: androidx.compose.ui.unit.TextUnit = 42.sp
) {
    Column(horizontalAlignment = Alignment.CenterHorizontally) {
        Text(label, color = SmokeGrey, fontSize = 9.sp, letterSpacing = 1.2.sp, maxLines = 1)
        Row(verticalAlignment = Alignment.Bottom) {
            Text(value, color = color, fontSize = valueSize, fontWeight = FontWeight.Black)
            Spacer(modifier = Modifier.width(4.dp))
            Text(unit, color = TextColor.copy(0.6f), fontSize = 11.sp, modifier = Modifier.padding(bottom = 8.dp), fontWeight = FontWeight.Bold)
        }
    }
}

@Composable
fun StepItem(stepNum: Int, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .size(18.dp)
                .background(NeonGreen.copy(0.15f), CircleShape)
                .border(0.5.dp, NeonGreen, CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("$stepNum", color = NeonGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(
            text = text,
            color = TextColor.copy(0.9f),
            fontSize = 11.sp,
            lineHeight = 15.sp
        )
    }
}

fun triggerHapticFeedback(context: Context) {
    val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
        val vibratorManager = context.getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as VibratorManager
        vibratorManager.defaultVibrator
    } else {
        @Suppress("DEPRECATION")
        val v = context.getSystemService(Context.VIBRATOR_SERVICE) as Vibrator
        v
    }
    
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
        vibrator.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
    } else {
        @Suppress("DEPRECATION")
        vibrator.vibrate(50)
    }
}

fun calculateVehicleDimensions(points: List<RealWorldPoint>): VehicleDimensions {
    if (points.size < 2) return VehicleDimensions(0.0, 0.0, 0.0, 0.0, true)
    val p1 = points[0]
    val p2 = points[1]
    var length = sqrt(((p2.x - p1.x).pow(2f) + (p2.y - p1.y).pow(2f) + (p2.z - p1.z).pow(2f)).toDouble())
    
    var height = if (points.size >= 4) {
        val p3 = points[2]
        val p4 = points[3]
        sqrt(((p4.x - p3.x).pow(2f) + (p4.y - p3.y).pow(2f) + (p4.z - p3.z).pow(2f)).toDouble())
    } else 1.4

    val isLengthInvalid = length < 0.1
    val isHeightInvalid = height < 0.1

    if (isLengthInvalid) {
        length = 4.3
    }
    if (isHeightInvalid) {
        height = 1.4
    }

    val isSimulated = isLengthInvalid && isHeightInvalid
    
    val width = (length * 0.4).coerceIn(1.6, 1.9)
    val totalArea = 2 * (length * height * 0.85) + (length * width) + 2 * (width * height)
    return VehicleDimensions(
        length = length,
        height = height,
        width = width,
        totalArea = totalArea.coerceIn(0.5, DosageLimits.MAX_VEHICLE_AREA_M2),
        isSimulated = isSimulated
    )
}

@Composable
fun TopAppBarHeader(tab: AppTab) {
    val title = when (tab) {
        AppTab.GUIDE -> "GUÍA DE DOSIS"
        AppTab.HISTORY -> "HISTORIAL DE LAVADOS"
        AppTab.PROFILE -> "PERFIL DE OPERARIO"
        else -> "CLEARDOSE"
    }

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .padding(top = 48.dp, start = 20.dp, end = 20.dp)
            .clip(RoundedCornerShape(16.dp))
            .background(CardBgColor)
            .border(0.5.dp, CardBorderColor, RoundedCornerShape(16.dp))
            .padding(vertical = 12.dp, horizontal = 16.dp),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = title,
            color = Charcoal,
            fontSize = 10.sp,
            fontWeight = FontWeight.Bold,
            letterSpacing = 2.sp
        )
    }
}

@Composable
fun CameraPermissionRequestScreen(onRequest: () -> Unit) {
    Box(
        modifier = Modifier
            .fillMaxSize()
            .padding(24.dp),
        contentAlignment = Alignment.Center
    ) {
        GlassCard(modifier = Modifier.fillMaxWidth()) {
            Text("PERMISO DE CÁMARA REQUERIDO", color = TextBlue, fontSize = 12.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "ClearDose requiere acceso a la cámara para realizar el escaneo tridimensional y medir las dimensiones de tu vehículo en tiempo real usando Realidad Aumentada.",
                color = SmokeGrey,
                fontSize = 11.sp,
                textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                lineHeight = 16.sp
            )
            Spacer(modifier = Modifier.height(24.dp))
            Button(
                onClick = onRequest,
                modifier = Modifier
                    .fillMaxWidth()
                    .height(48.dp)
                    .shadow(8.dp, RoundedCornerShape(24.dp), spotColor = NeonCyan),
                colors = ButtonDefaults.buttonColors(containerColor = TextBlue),
                shape = RoundedCornerShape(24.dp)
            ) {
                Text("CONCEDER PERMISO", color = Color.White, fontWeight = FontWeight.Bold)
            }
        }
    }
}

@Composable
fun OnboardingSectionCard(
    title: String,
    subtitle: String,
    icon: @Composable () -> Unit,
    color: Color = NeonCyan,
    content: @Composable ColumnScope.() -> Unit
) {
    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(20.dp))
            .background(CardBgColor)
            .border(0.5.dp, color.copy(0.3f), RoundedCornerShape(20.dp))
            .padding(16.dp)
    ) {
        Column {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                icon()
                Column {
                    val titleColor = when(color) {
                        NeonCyan -> TextCyan
                        NeonGreen -> TextGreen
                        NeonGold -> TextGold
                        else -> color
                    }
                    Text(title.uppercase(), color = titleColor, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)
                    Text(subtitle, color = SmokeGrey, fontSize = 9.sp)
                }
            }
            Spacer(modifier = Modifier.height(12.dp))
            content()
        }
    }
}

@Composable
fun BulletItem(title: String, description: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.Top
    ) {
        Box(
            modifier = Modifier
                .padding(top = 4.dp)
                .size(4.dp)
                .background(Charcoal.copy(0.5f), CircleShape)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Column {
            Text(
                text = title,
                color = Charcoal,
                fontSize = 10.sp,
                fontWeight = FontWeight.Bold
            )
            Text(
                text = description,
                color = SmokeGrey,
                fontSize = 9.sp,
                lineHeight = 12.sp
            )
        }
    }
}

@Composable
fun StepRow(num: Int, text: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Box(
            modifier = Modifier
                .size(16.dp)
                .background(NeonCyan.copy(0.1f), CircleShape)
                .border(0.5.dp, NeonCyan.copy(0.5f), CircleShape),
            contentAlignment = Alignment.Center
        ) {
            Text("$num", color = TextCyan, fontSize = 8.sp, fontWeight = FontWeight.Bold)
        }
        Spacer(modifier = Modifier.width(10.dp))
        Text(text, color = SmokeGrey, fontSize = 9.sp, lineHeight = 12.sp)
    }
}

@Composable
fun OnboardingGuideScreen(onStartMeasure: () -> Unit) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp)
            .padding(top = 104.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(
            modifier = Modifier.fillMaxWidth().padding(vertical = 8.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text("CLEARDOSE", color = TextBlue, fontSize = 28.sp, fontWeight = FontWeight.Light, letterSpacing = 6.sp)
            Spacer(modifier = Modifier.height(4.dp))
            Text("DOSIFICACIÓN INTELIGENTE Y PRECISIÓN HÍDRICA", color = SmokeGrey, fontSize = 8.sp, letterSpacing = 2.sp, fontWeight = FontWeight.Bold)
        }

        OnboardingSectionCard(
            title = "Solución Técnica",
            subtitle = "Precisión milimétrica sin hardware costoso",
            icon = {
                Canvas(modifier = Modifier.size(24.dp)) {
                    drawCircle(NeonCyan, radius = size.minDimension / 3f, style = Stroke(1.5.dp.toPx()))
                    drawCircle(NeonCyan, radius = 2.dp.toPx())
                    drawLine(NeonCyan, Offset(0f, size.height/2f), Offset(size.width, size.height/2f), strokeWidth = 1.dp.toPx())
                }
            },
            color = NeonCyan
        ) {
            BulletItem("Visión Computacional", "Usa Google ARCore para escanear y calcular el área (m²) del vehículo en tiempo real.")
            Spacer(modifier = Modifier.height(6.dp))
            BulletItem("Dosificación Inteligente", "Determina agua y detergente con el algoritmo: Volumen = Área × 2.25.")
            Spacer(modifier = Modifier.height(6.dp))
            BulletItem("Cero Hardware Externo", "Opera al 100% desde el smartphone del operario, sin sensores LiDAR ni equipos adicionales.")
        }

        OnboardingSectionCard(
            title = "Propuesta de Valor",
            subtitle = "Sostenibilidad que optimiza tus costos",
            icon = {
                Canvas(modifier = Modifier.size(24.dp)) {
                    drawLine(NeonGreen, Offset(4.dp.toPx(), size.height - 4.dp.toPx()), Offset(size.width - 4.dp.toPx(), size.height - 4.dp.toPx()), strokeWidth = 1.5.dp.toPx())
                    drawLine(NeonGreen, Offset(4.dp.toPx(), size.height - 4.dp.toPx()), Offset(4.dp.toPx(), 4.dp.toPx()), strokeWidth = 1.5.dp.toPx())
                    val path = Path().apply {
                        moveTo(6.dp.toPx(), size.height - 6.dp.toPx())
                        lineTo(size.width * 0.4f, size.height * 0.6f)
                        lineTo(size.width * 0.7f, size.height * 0.4f)
                        lineTo(size.width - 6.dp.toPx(), size.height * 0.15f)
                    }
                    drawPath(path, NeonGreen, style = Stroke(1.5.dp.toPx()))
                }
            },
            color = NeonGreen
        ) {
            BulletItem("Reducción de Costos", "Garantiza hasta 30% de ahorro directo en insumos hídricos y químicos por lavado.")
            Spacer(modifier = Modifier.height(6.dp))
            BulletItem("Modelo SaaS", "Suscripción periódica para autolavados y flotas logísticas, amortizada por los ahorros mensuales.")
            Spacer(modifier = Modifier.height(6.dp))
            BulletItem("Estandarización", "Permite auditar y controlar el gasto de agua y reactivos independientemente del operario.")
        }

        OnboardingSectionCard(
            title = "Impacto y Propósito",
            subtitle = "Tecnología con responsabilidad ambiental",
            icon = {
                Canvas(modifier = Modifier.size(24.dp)) {
                    val path = Path().apply {
                        moveTo(size.width / 2f, 2.dp.toPx())
                        quadraticTo(size.width * 0.85f, size.height * 0.6f, size.width * 0.8f, size.height * 0.75f)
                        quadraticTo(size.width / 2f, size.height - 1.dp.toPx(), size.width * 0.2f, size.height * 0.75f)
                        quadraticTo(size.width * 0.15f, size.height * 0.6f, size.width / 2f, 2.dp.toPx())
                    }
                    drawPath(path, NeonGold, style = Stroke(1.5.dp.toPx()))
                }
            },
            color = NeonGold
        ) {
            BulletItem("Adaptación Local", "Diseñado especialmente para combatir la ineficiencia hídrica en zonas de escasez (Región del Maule).")
            Spacer(modifier = Modifier.height(6.dp))
            BulletItem("Límite de Sostenibilidad", "Tope de seguridad de 25L máximo por vehículo para prevenir malas prácticas.")
            Spacer(modifier = Modifier.height(6.dp))
            BulletItem("Alineación ODS", "Contribuye directamente a los Objetivos Mundiales 6 (Agua limpia), 9 (Innovación) y 12 (Consumo responsable).")
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(20.dp))
                .background(CardBgColor)
                .border(0.5.dp, CardBorderColor, RoundedCornerShape(20.dp))
                .padding(16.dp)
        ) {
            Column {
                Text("PASOS PARA LA DOSIFICACIÓN", color = Charcoal, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                Spacer(modifier = Modifier.height(12.dp))
                StepRow(1, "Calibrar la barra de referencia a la escala deseada.")
                Spacer(modifier = Modifier.height(8.dp))
                StepRow(2, "Fijar los 4 vértices del auto (delantero, trasero, techo, suelo).")
                Spacer(modifier = Modifier.height(8.dp))
                StepRow(3, "Obtener el diagnóstico y render 3D según las medidas.")
                Spacer(modifier = Modifier.height(8.dp))
                StepRow(4, "Seleccionar el insumo y recibir las instrucciones exactas.")
            }
        }

        Spacer(modifier = Modifier.height(4.dp))

        Button(
            onClick = onStartMeasure,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .shadow(16.dp, RoundedCornerShape(28.dp), spotColor = NeonCyan),
            colors = ButtonDefaults.buttonColors(containerColor = TextBlue),
            shape = RoundedCornerShape(28.dp)
        ) {
            Text("COMENZAR ESCANEO ➔", color = Color.White, fontWeight = FontWeight.Black, letterSpacing = 1.sp)
        }
        
        Spacer(modifier = Modifier.height(8.dp))
    }
}

@Composable
fun HistoryScreen(items: List<HistoryRecord>) {
    val scrollState = rememberScrollState()
    val totalSaved = items.sumOf { it.waterSaved }
    val approxWashes = (totalSaved / DosageLimits.TRADITIONAL_WASH_LITERS).toInt()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp)
            .padding(top = 104.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text("HISTORIAL DE DOSIS", color = TextBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Text("Registro de lavados eficientes", color = SmokeGrey, fontSize = 9.sp)
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(CardBgColor)
                .border(0.5.dp, NeonGreen.copy(0.6f), RoundedCornerShape(24.dp))
                .padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column {
                    Text("AHORRO TOTAL ACUMULADO", color = SmokeGrey, fontSize = 8.sp, letterSpacing = 1.sp)
                    Spacer(modifier = Modifier.height(2.dp))
                    Text(String.format(java.util.Locale.US, "%.1f L", totalSaved), color = TextGreen, fontSize = 32.sp, fontWeight = FontWeight.Black)
                    Text("Equivalente a ~$approxWashes lavados tradicionales", color = SmokeGrey, fontSize = 8.sp)
                }
                
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .background(NeonGreen.copy(0.1f), CircleShape)
                        .border(1.dp, NeonGreen.copy(0.4f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Canvas(modifier = Modifier.size(24.dp)) {
                        val path = Path().apply {
                            moveTo(size.width / 2f, 2.dp.toPx())
                            quadraticTo(size.width * 0.85f, size.height * 0.6f, size.width * 0.8f, size.height * 0.75f)
                            quadraticTo(size.width / 2f, size.height - 1.dp.toPx(), size.width * 0.2f, size.height * 0.75f)
                            quadraticTo(size.width * 0.15f, size.height * 0.6f, size.width / 2f, 2.dp.toPx())
                        }
                        drawPath(path, NeonGreen, style = Stroke(1.5.dp.toPx()))
                    }
                }
            }
        }

        Text("ÚLTIMOS LAVADOS", color = SmokeGrey, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)

        items.forEach { record ->
            HistoryRecordCard(record)
        }
    }
}

data class HistoryRecord(
    val id: String,
    val type: String,
    val time: String,
    val dims: String,
    val waterSaved: Double,
    val soapMl: Int,
    val imageRes: Int,
    val isPremium: Boolean = false,
    val waxMl: Int = 0
)

@Composable
fun HistoryRecordCard(record: HistoryRecord) {
    val cardBackground = if (record.isPremium) NeonGold.copy(0.15f) else CardBgColor
    val cardBorder = if (record.isPremium) NeonGold.copy(0.6f) else CardBorderColor

    Box(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .background(cardBackground)
            .border(0.5.dp, cardBorder, RoundedCornerShape(16.dp))
            .padding(14.dp)
    ) {
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Image(
                painter = painterResource(id = record.imageRes),
                contentDescription = record.type,
                modifier = Modifier
                    .size(48.dp)
                    .clip(RoundedCornerShape(8.dp))
                    .background(Color.White)
                    .border(0.5.dp, CardBorderColor, RoundedCornerShape(8.dp))
                    .padding(4.dp)
            )
            Spacer(modifier = Modifier.width(12.dp))
            
            Column(modifier = Modifier.weight(1f)) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Text(record.type, color = Charcoal, fontSize = 11.sp, fontWeight = FontWeight.Bold)
                        if (record.isPremium) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Box(
                                modifier = Modifier
                                    .background(NeonGold.copy(0.15f), RoundedCornerShape(4.dp))
                                    .border(0.5.dp, NeonGold.copy(0.6f), RoundedCornerShape(4.dp))
                                    .padding(horizontal = 4.dp, vertical = 1.dp)
                            ) {
                                Row(verticalAlignment = Alignment.CenterVertically) {
                                    Text("★", color = TextGold, fontSize = 7.sp, fontWeight = FontWeight.Bold)
                                    Spacer(modifier = Modifier.width(2.dp))
                                    Text("PREMIUM", color = TextGold, fontSize = 6.5.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                                }
                            }
                        }
                    }
                    Text(record.time, color = SmokeGrey, fontSize = 8.sp)
                }
                Spacer(modifier = Modifier.height(2.dp))
                Text("Cód: ${record.id}  •  Dimen: ${record.dims}", color = SmokeGrey, fontSize = 8.sp)
                Spacer(modifier = Modifier.height(4.dp))
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.Start
                ) {
                    Text("Agua Ahorrada: ", color = SmokeGrey, fontSize = 9.sp)
                    Text("+${record.waterSaved} L", color = TextGreen, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(modifier = Modifier.width(10.dp))
                    Text("Detergente: ", color = SmokeGrey, fontSize = 9.sp)
                    Text("${record.soapMl} ml", color = TextBlue, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    if (record.isPremium && record.waxMl > 0) {
                        Spacer(modifier = Modifier.width(10.dp))
                        Text("Cera: ", color = SmokeGrey, fontSize = 9.sp)
                        Text("${record.waxMl} ml", color = TextGold, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }
    }
}

@Composable
fun ProfileScreen(
    apiKey: String,
    onApiKeyChanged: (String) -> Unit,
    selectedModel: String,
    onModelChanged: (String) -> Unit
) {
    val scrollState = rememberScrollState()
    
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(horizontal = 20.dp)
            .padding(top = 104.dp, bottom = 88.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        Column(modifier = Modifier.fillMaxWidth()) {
            Text("PERFIL DE OPERARIO", color = TextBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 3.sp)
            Text("Acreditación y parámetros del sistema", color = SmokeGrey, fontSize = 9.sp)
        }

        // Tarjeta de Configuración de API Key
        OnboardingSectionCard(
            title = "Configuración de IA",
            subtitle = "API Key de Gemini Vision",
            icon = {
                Canvas(modifier = Modifier.size(24.dp)) {
                    drawCircle(NeonCyan, radius = size.minDimension / 3f, style = Stroke(1.5.dp.toPx()))
                    drawCircle(NeonCyan, radius = 2.dp.toPx())
                }
            },
            color = NeonCyan
        ) {
            Text(
                "Para escanear vehículos mediante foto, ingresa tu API Key gratuita obtenida en Google AI Studio:",
                color = SmokeGrey,
                fontSize = 9.sp,
                lineHeight = 13.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            OutlinedTextField(
                value = apiKey,
                onValueChange = onApiKeyChanged,
                modifier = Modifier.fillMaxWidth(),
                label = { Text("Gemini API Key", color = SmokeGrey, fontSize = 10.sp) },
                singleLine = true,
                visualTransformation = androidx.compose.ui.text.input.PasswordVisualTransformation(),
                colors = OutlinedTextFieldDefaults.colors(
                    focusedBorderColor = NeonCyan,
                    unfocusedBorderColor = CardBorderColor,
                    focusedLabelColor = NeonCyan,
                    unfocusedLabelColor = SmokeGrey,
                    focusedTextColor = Charcoal,
                    unfocusedTextColor = Charcoal
                )
            )
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                "Clave cifrada localmente. No requiere tarjeta de crédito.",
                color = TextGreen,
                fontSize = 8.sp,
                fontWeight = FontWeight.Bold
            )
            Spacer(modifier = Modifier.height(16.dp))
            Text(
                "MODELO DE INTELIGENCIA ARTIFICIAL",
                color = SmokeGrey,
                fontSize = 9.sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = 1.sp
            )
            Spacer(modifier = Modifier.height(6.dp))
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                val models = listOf(
                    "gemini-2.5-flash" to "2.5 Flash",
                    "gemini-1.5-flash" to "1.5 Flash",
                    "gemini-2.5-pro" to "2.5 Pro",
                    "gemini-1.5-pro" to "1.5 Pro"
                )
                models.forEach { (modelId, label) ->
                    val isSel = selectedModel == modelId
                    Box(
                        modifier = Modifier
                            .weight(1f)
                            .clip(RoundedCornerShape(8.dp))
                            .background(if (isSel) TextBlue else NeonCyan.copy(0.12f))
                            .border(0.5.dp, if (isSel) TextBlue else CardBorderColor, RoundedCornerShape(8.dp))
                            .clickable { onModelChanged(modelId) }
                            .padding(vertical = 8.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = label,
                            color = if (isSel) Color.White else Charcoal,
                            fontSize = 9.sp,
                            fontWeight = FontWeight.Bold
                        )
                    }
                }
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(24.dp))
                .background(CardBgColor)
                .border(0.5.dp, CardBorderColor, RoundedCornerShape(24.dp))
                .padding(18.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(54.dp)
                        .background(NeonCyan.copy(0.15f), CircleShape)
                        .border(1.dp, NeonCyan.copy(0.6f), CircleShape),
                    contentAlignment = Alignment.Center
                ) {
                    Text("OP", color = TextBlue, fontSize = 16.sp, fontWeight = FontWeight.Black)
                }
                Spacer(modifier = Modifier.width(16.dp))
                
                Column {
                    Text("Operador Certificado #08", color = Charcoal, fontSize = 14.sp, fontWeight = FontWeight.Bold)
                    Text("Autolavado Maule Centro, Maule, Chile", color = SmokeGrey, fontSize = 9.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Box(
                        modifier = Modifier
                            .background(NeonGreen.copy(0.15f), RoundedCornerShape(4.dp))
                            .border(0.5.dp, NeonGreen.copy(0.4f), RoundedCornerShape(4.dp))
                            .padding(horizontal = 6.dp, vertical = 2.dp)
                    ) {
                        Text("SaaS Premium Activo", color = TextGreen, fontSize = 8.sp, fontWeight = FontWeight.Bold)
                    }
                }
            }
        }

        Text("MARCO REGULATORIO Y PARÁMETROS", color = SmokeGrey, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.5.sp)

        OnboardingSectionCard(
            title = "Zona de Adaptación Climática",
            subtitle = "Cumplimiento local contra escasez",
            icon = {
                Canvas(modifier = Modifier.size(24.dp)) {
                    drawCircle(NeonGold, radius = size.minDimension / 4f, center = Offset(size.width/2f, size.height/2f), style = Stroke(1.5.dp.toPx()))
                    drawCircle(NeonGold, radius = 2.dp.toPx(), center = Offset(size.width/2f, size.height/2f))
                    drawLine(NeonGold, Offset(0f, size.height/2f), Offset(size.width, size.height/2f), strokeWidth = 1.dp.toPx())
                    drawLine(NeonGold, Offset(size.width/2f, 0f), Offset(size.width/2f, size.height), strokeWidth = 1.dp.toPx())
                }
            },
            color = NeonGold
        ) {
            Text(
                "La Región del Maule se encuentra bajo decreto de escasez hídrica. La aplicación adapta los coeficientes del algoritmo de lavado para optimizar la eficiencia y auditar pérdidas operativas según las normas locales.",
                color = SmokeGrey,
                fontSize = 9.sp,
                lineHeight = 13.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(6.dp).background(TextGreen, CircleShape))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Decreto Vigente de Eficiencia Hídrica", color = TextGreen, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }

        OnboardingSectionCard(
            title = "Límite de Sostenibilidad",
            subtitle = "Protección ambiental activa",
            icon = {
                Canvas(modifier = Modifier.size(24.dp)) {
                    val width = size.width
                    val height = size.height
                    drawArc(NeonRed, 180f, 180f, false, topLeft = Offset(width * 0.25f, height * 0.15f), size = Size(width * 0.5f, height * 0.5f), style = Stroke(1.5.dp.toPx()))
                    drawRoundRect(NeonRed, topLeft = Offset(width * 0.15f, height * 0.45f), size = Size(width * 0.7f, height * 0.4f), cornerRadius = CornerRadius(4.dp.toPx()), style = Stroke(1.5.dp.toPx()))
                }
            },
            color = NeonRed
        ) {
            Text(
                "Para evitar el abuso del recurso hídrico, Cleardose restringe y bloquea cualquier receta de dosificación que supere los 25 litros por lavado (límite ecológico máximo).",
                color = SmokeGrey,
                fontSize = 9.sp,
                lineHeight = 13.sp
            )
            Spacer(modifier = Modifier.height(8.dp))
            Row(verticalAlignment = Alignment.CenterVertically) {
                Box(modifier = Modifier.size(6.dp).background(TextRed, CircleShape))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Límite de 25.0L Activado", color = TextRed, fontSize = 9.sp, fontWeight = FontWeight.Bold)
            }
        }

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .clip(RoundedCornerShape(16.dp))
                .background(CardBgColor)
                .border(0.5.dp, CardBorderColor, RoundedCornerShape(16.dp))
                .padding(14.dp)
        ) {
            Column {
                Text("Licencia Corporativa SaaS", color = Charcoal, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(modifier = Modifier.height(4.dp))
                Text("Empresa: Logística y Autolavado Maule S.A.", color = SmokeGrey, fontSize = 9.sp)
                Text("Próximo pago: 30 de Junio de 2026", color = SmokeGrey, fontSize = 9.sp)
                Spacer(modifier = Modifier.height(6.dp))
                Text("El software se auto-amortiza ahorrando un promedio de $120 USD al mes en insumos por cada bahía de lavado habilitada.", color = TextGreen, fontSize = 9.sp, lineHeight = 13.sp)
            }
        }
    }
}

@Composable
fun CarPhotoScannerScreen(
    apiKey: String,
    selectedModel: String,
    onModelChanged: (String) -> Unit,
    onComplete: (VehicleDimensions) -> Unit,
    onSwitchToAR: () -> Unit,
    onNavigateToProfile: () -> Unit
) {
    val context = LocalContext.current
    val coroutineScope = rememberCoroutineScope()
    
    var capturedBitmap by remember { mutableStateOf<Bitmap?>(null) }
    var isAnalyzing by remember { mutableStateOf(false) }
    var errorMessage by remember { mutableStateOf<String?>(null) }
    
    val tempFile = remember { File(context.cacheDir, "temp_vehicle_photo.jpg") }
    val photoUri = remember {
        FileProvider.getUriForFile(
            context,
            "com.cleardose.app.fileprovider",
            tempFile
        )
    }
    
    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
        onResult = { success ->
            if (success) {
                // Compress and load bitmap
                val base64 = compressImageFile(context, tempFile)
                val bitmap = BitmapFactory.decodeFile(tempFile.absolutePath)
                capturedBitmap = bitmap
                
                if (base64 != null) {
                    isAnalyzing = true
                    errorMessage = null
                    coroutineScope.launch {
                        val client = GeminiClient()
                        val result = client.analyzeVehicleImage(base64, apiKey, selectedModel)
                        isAnalyzing = false
                        when (result) {
                            is GeminiResult.Success -> {
                                val specs = result.specs
                                val parsedDirtiness = when(specs.dirtinessLevel?.uppercase()?.trim()) {
                                    "LOW" -> DirtinessLevel.LOW
                                    "HIGH" -> DirtinessLevel.HIGH
                                    else -> DirtinessLevel.MEDIUM
                                }
                                onComplete(
                                    VehicleDimensions(
                                        length = specs.length,
                                        height = specs.height,
                                        width = specs.width,
                                        totalArea = specs.totalArea,
                                        isSimulated = false,
                                        dirtinessLevel = parsedDirtiness
                                    )
                                )
                            }
                            is GeminiResult.Error -> {
                                errorMessage = result.message
                            }
                        }
                    }
                } else {
                    errorMessage = "Error al procesar la imagen."
                }
            }
        }
    )
    
    val galleryLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri ->
            if (uri != null) {
                try {
                    context.contentResolver.openInputStream(uri)?.use { input ->
                        tempFile.outputStream().use { output ->
                            input.copyTo(output)
                        }
                    }
                    val base64 = compressImageFile(context, tempFile)
                    val bitmap = BitmapFactory.decodeFile(tempFile.absolutePath)
                    capturedBitmap = bitmap
                    
                    if (base64 != null) {
                        isAnalyzing = true
                        errorMessage = null
                        coroutineScope.launch {
                            val client = GeminiClient()
                            val result = client.analyzeVehicleImage(base64, apiKey, selectedModel)
                            isAnalyzing = false
                            when (result) {
                                is GeminiResult.Success -> {
                                    val specs = result.specs
                                    val parsedDirtiness = when(specs.dirtinessLevel?.uppercase()?.trim()) {
                                        "LOW" -> DirtinessLevel.LOW
                                        "HIGH" -> DirtinessLevel.HIGH
                                        else -> DirtinessLevel.MEDIUM
                                    }
                                    onComplete(
                                        VehicleDimensions(
                                            length = specs.length,
                                            height = specs.height,
                                            width = specs.width,
                                            totalArea = specs.totalArea,
                                            isSimulated = false,
                                            dirtinessLevel = parsedDirtiness
                                        )
                                    )
                                }
                                is GeminiResult.Error -> {
                                    errorMessage = result.message
                                }
                            }
                        }
                    } else {
                        errorMessage = "Error al procesar la imagen."
                    }
                } catch (e: Exception) {
                    errorMessage = "Error al cargar imagen: ${e.message}"
                }
            }
        }
    )

    Box(modifier = Modifier.fillMaxSize()) {
        // Mode Switcher Toggle
        Row(
            modifier = Modifier
                .align(Alignment.TopCenter)
                .padding(top = 100.dp)
                .width(240.dp)
                .height(36.dp)
                .clip(RoundedCornerShape(18.dp))
                .background(Charcoal.copy(0.9f))
                .border(1.dp, NeonCyan.copy(0.5f), RoundedCornerShape(18.dp)),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(18.dp))
                    .clickable { onSwitchToAR() },
                contentAlignment = Alignment.Center
            ) {
                Text("CÁMARA AR", color = Color.White.copy(0.8f), fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxHeight()
                    .clip(RoundedCornerShape(18.dp))
                    .background(NeonCyan),
                contentAlignment = Alignment.Center
            ) {
                Text("FOTO IA", color = Charcoal, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
            }
        }

        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 24.dp)
                .padding(top = 160.dp, bottom = 80.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.SpaceBetween
        ) {
            // Instruction Card
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .clip(RoundedCornerShape(16.dp))
                    .background(CardBgColor)
                    .border(0.5.dp, CardBorderColor, RoundedCornerShape(16.dp))
                    .padding(12.dp)
            ) {
                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                    Text("ESCANEADO INTELIGENTE POR FOTO", color = TextBlue, fontSize = 11.sp, fontWeight = FontWeight.Bold, letterSpacing = 2.sp)
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "Toma una foto de perfil del vehículo. La IA identificará la marca, modelo y calculará las medidas oficiales en segundos.",
                        color = SmokeGrey,
                        fontSize = 9.sp,
                        textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                        lineHeight = 12.sp
                    )
                    Spacer(modifier = Modifier.height(8.dp))
                    Box(modifier = Modifier.fillMaxWidth().height(1.dp).background(CardBorderColor))
                    Spacer(modifier = Modifier.height(8.dp))
                    Text(
                        "MODELO ACTIVO (CAMBIAR EN CASO DE ALTA DEMANDA)",
                        color = SmokeGrey,
                        fontSize = 8.sp,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 0.5.sp
                    )
                    Spacer(modifier = Modifier.height(6.dp))
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                        val models = listOf(
                            "gemini-2.5-flash" to "2.5 Flash",
                            "gemini-1.5-flash" to "1.5 Flash",
                            "gemini-2.5-pro" to "2.5 Pro",
                            "gemini-1.5-pro" to "1.5 Pro"
                        )
                        models.forEach { (modelId, label) ->
                            val isSel = selectedModel == modelId
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .clip(RoundedCornerShape(8.dp))
                                    .background(if (isSel) TextBlue else Color.White.copy(0.1f))
                                    .border(0.5.dp, if (isSel) TextBlue else CardBorderColor, RoundedCornerShape(8.dp))
                                    .clickable { onModelChanged(modelId) }
                                    .padding(vertical = 6.dp),
                                contentAlignment = Alignment.Center
                            ) {
                                Text(
                                    text = label,
                                    color = if (isSel) Color.White else Charcoal,
                                    fontSize = 8.sp,
                                    fontWeight = FontWeight.Bold
                                )
                            }
                        }
                    }
                }
            }

            // Image Preview Container
            Box(
                modifier = Modifier
                    .size(260.dp)
                    .clip(RoundedCornerShape(24.dp))
                    .background(CardBgColor)
                    .border(0.5.dp, if (errorMessage != null) NeonRed.copy(0.6f) else CardBorderColor, RoundedCornerShape(24.dp)),
                contentAlignment = Alignment.Center
            ) {
                if (capturedBitmap != null) {
                    Image(
                        bitmap = capturedBitmap!!.asImageBitmap(),
                        contentDescription = "Vehículo capturado",
                        modifier = Modifier
                            .fillMaxSize()
                            .clip(RoundedCornerShape(24.dp)),
                        contentScale = androidx.compose.ui.layout.ContentScale.Crop
                    )
                    
                    if (isAnalyzing) {
                        // Neon Laser Scan Line
                        val infiniteTransition = rememberInfiniteTransition(label = "laser")
                        val scanOffset by infiniteTransition.animateFloat(
                            initialValue = 0f,
                            targetValue = 1f,
                            animationSpec = infiniteRepeatable(
                                animation = tween(2000, easing = LinearEasing),
                                repeatMode = RepeatMode.Reverse
                            ),
                            label = "y"
                        )
                        
                        Canvas(modifier = Modifier.fillMaxSize()) {
                            val scanY = size.height * scanOffset
                            drawLine(
                                brush = Brush.verticalGradient(
                                    colors = listOf(NeonCyan.copy(0.1f), NeonCyan, NeonCyan.copy(0.1f))
                                ),
                                start = Offset(0f, scanY),
                                end = Offset(size.width, scanY),
                                strokeWidth = 3.dp.toPx()
                            )
                        }
                        
                        // Glass loading card
                        Box(
                            modifier = Modifier
                                .clip(RoundedCornerShape(12.dp))
                                .background(CardBgColor)
                                .border(0.5.dp, CardBorderColor, RoundedCornerShape(12.dp))
                                .padding(horizontal = 16.dp, vertical = 10.dp),
                            contentAlignment = Alignment.Center
                        ) {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                CircularProgressIndicator(modifier = Modifier.size(16.dp), color = NeonBlue, strokeWidth = 2.dp)
                                Spacer(modifier = Modifier.width(10.dp))
                                Text("ANALIZANDO VEHÍCULO...", color = TextBlue, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 1.sp)
                            }
                        }
                    }
                } else {
                    // Placeholder when no photo is taken
                    Column(
                        horizontalAlignment = Alignment.CenterHorizontally,
                        modifier = Modifier.padding(24.dp)
                    ) {
                        Canvas(modifier = Modifier.size(48.dp)) {
                            drawCircle(NeonCyan.copy(0.15f))
                            drawCircle(NeonCyan.copy(0.6f), style = Stroke(1.dp.toPx()))
                        }
                        Spacer(modifier = Modifier.height(12.dp))
                        Text("SIN FOTO CAPTURADA", color = SmokeGrey, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                        Text("Presiona Capturar o carga desde la galería", color = SmokeGrey.copy(0.7f), fontSize = 8.sp, textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    }
                }
            }

            // Error / Status Message
            if (apiKey.isEmpty()) {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .clip(RoundedCornerShape(12.dp))
                        .background(NeonRed.copy(0.15f))
                        .border(0.5.dp, NeonRed.copy(0.6f), RoundedCornerShape(12.dp))
                        .padding(10.dp)
                        .clickable { onNavigateToProfile() },
                    contentAlignment = Alignment.Center
                ) {
                    Text("⚠️ FALTA CONFIGURAR GEMINI API KEY (PRESIONA AQUÍ)", color = TextRed, fontSize = 9.sp, fontWeight = FontWeight.Black, letterSpacing = 0.5.sp)
                }
            } else if (errorMessage != null) {
                Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(errorMessage!!, color = TextRed, fontSize = 9.sp, fontWeight = FontWeight.Bold, modifier = Modifier.padding(horizontal = 16.dp), textAlign = androidx.compose.ui.text.style.TextAlign.Center)
                    Button(
                        onClick = {
                            triggerHapticFeedback(context)
                            onComplete(VehicleDimensions(4.5, 1.42, 1.75, 23.5, true))
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = NeonGold.copy(0.15f)),
                        border = BorderStroke(0.5.dp, NeonGold.copy(0.6f)),
                        shape = RoundedCornerShape(12.dp),
                        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp)
                    ) {
                        Text("SIMULAR CON VEHÍCULO ESTÁNDAR", color = TextGold, fontSize = 9.sp, fontWeight = FontWeight.Bold, letterSpacing = 0.5.sp)
                    }
                }
            } else if (isAnalyzing) {
                Text("El procesamiento de la foto puede tardar de 3 a 5 segundos...", color = SmokeGrey, fontSize = 8.sp)
            } else {
                Spacer(modifier = Modifier.height(10.dp))
            }

            // Action Buttons
            Column(
                modifier = Modifier.fillMaxWidth(),
                verticalArrangement = Arrangement.spacedBy(10.dp)
            ) {
                Button(
                    onClick = {
                        if (apiKey.isNotEmpty()) {
                            triggerHapticFeedback(context)
                            cameraLauncher.launch(photoUri)
                        } else {
                            onNavigateToProfile()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(54.dp)
                        .shadow(12.dp, RoundedCornerShape(27.dp), spotColor = NeonCyan),
                    colors = ButtonDefaults.buttonColors(containerColor = TextBlue),
                    shape = RoundedCornerShape(27.dp)
                ) {
                    Text("TOMAR FOTO CON CÁMARA", color = Color.White, fontWeight = FontWeight.Black)
                }

                Button(
                    onClick = {
                        if (apiKey.isNotEmpty()) {
                            triggerHapticFeedback(context)
                            galleryLauncher.launch("image/*")
                        } else {
                            onNavigateToProfile()
                        }
                    },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = CardBgColor),
                    border = BorderStroke(0.5.dp, TextBlue),
                    shape = RoundedCornerShape(24.dp)
                ) {
                    Text("CARGAR DESDE GALERÍA", color = TextBlue, fontWeight = FontWeight.Bold, fontSize = 11.sp)
                }
            }
        }
    }
}

// Helpers para procesamiento de imágenes (Downscaling y Orientación)
fun compressImageFile(context: Context, file: File): String? {
    try {
        val options = BitmapFactory.Options().apply {
            inJustDecodeBounds = true
        }
        BitmapFactory.decodeFile(file.absolutePath, options)
        
        var scale = 1
        val maxDimension = 1024
        if (options.outWidth > maxDimension || options.outHeight > maxDimension) {
            val widthScale = options.outWidth / maxDimension
            val heightScale = options.outHeight / maxDimension
            scale = maxOf(widthScale, heightScale)
        }
        
        val decodeOptions = BitmapFactory.Options().apply {
            inSampleSize = scale
        }
        val bitmap = BitmapFactory.decodeFile(file.absolutePath, decodeOptions) ?: return null
        
        // Rotar si es necesario
        val rotatedBitmap = rotateImageIfRequired(file.absolutePath, bitmap)
        
        val outputStream = ByteArrayOutputStream()
        rotatedBitmap.compress(Bitmap.CompressFormat.JPEG, 80, outputStream)
        val bytes = outputStream.toByteArray()
        return android.util.Base64.encodeToString(bytes, android.util.Base64.NO_WRAP)
    } catch (e: Exception) {
        Log.e("ImageUtils", "Error compressing image: ${e.message}")
        return null
    }
}

fun rotateImageIfRequired(path: String, img: Bitmap): Bitmap {
    try {
        val ei = ExifInterface(path)
        val orientation = ei.getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        return when (orientation) {
            ExifInterface.ORIENTATION_ROTATE_90 -> rotateImage(img, 90f)
            ExifInterface.ORIENTATION_ROTATE_180 -> rotateImage(img, 180f)
            ExifInterface.ORIENTATION_ROTATE_270 -> rotateImage(img, 270f)
            else -> img
        }
    } catch (e: Exception) {
        return img
    }
}

fun rotateImage(img: Bitmap, degree: Float): Bitmap {
    val matrix = Matrix()
    matrix.postRotate(degree)
    val rotatedImg = Bitmap.createBitmap(img, 0, 0, img.width, img.height, matrix, true)
    if (rotatedImg != img) {
        img.recycle()
    }
    return rotatedImg
}
