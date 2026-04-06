package com.codeextractor.app.presentation.editor

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codeextractor.app.editor.EditableElement
import com.codeextractor.app.editor.ElementType
import com.codeextractor.app.editor.GlbTextureEditor
import dev.romainguy.kotlin.math.Float3
import io.github.sceneview.Scene
import io.github.sceneview.math.Position
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberModelInstance
import io.github.sceneview.rememberModelLoader

private const val MODEL_PATH = "models/source_named.glb"
private val CAM_POS = Float3(0f, 1.35f, 0.7f)
private val CAM_TGT = Float3(0f, 1.35f, 0f)
private const val SCALE = 0.35f

// ═══════════════════════════════════════════════
//  Цветовые пресеты
// ═══════════════════════════════════════════════
private data class ColorPreset(
    val label: String,
    val ui: Color,
    val r: Float,
    val g: Float,
    val b: Float,
)

private val SKIN_COLORS = listOf(
    ColorPreset("Фарфор", Color(0xFFFAE7D8), 0.98f, 0.91f, 0.85f),
    ColorPreset("Светлая", Color(0xFFF5D6C3), 0.96f, 0.84f, 0.76f),
    ColorPreset("Средняя", Color(0xFFDAB99A), 0.85f, 0.73f, 0.60f),
    ColorPreset("Загар", Color(0xFFC49E7A), 0.77f, 0.62f, 0.48f),
    ColorPreset("Тёмная", Color(0xFF8D6E4C), 0.55f, 0.43f, 0.30f),
    ColorPreset("Эбеновая", Color(0xFF2A1B0E), 0.16f, 0.11f, 0.05f),
)
private val EYE_COLORS = listOf(
    ColorPreset("Белые", Color(0xFFF2F2F2), 0.95f, 0.95f, 0.95f),
    ColorPreset("Кремовые", Color(0xFFF5F0E8), 0.96f, 0.94f, 0.91f),
    ColorPreset("Розовые", Color(0xFFD08080), 0.82f, 0.50f, 0.50f),
)
private val TEETH_COLORS = listOf(
    ColorPreset("Белоснежные", Color(0xFFF5F5F0), 0.96f, 0.96f, 0.94f),
    ColorPreset("Натуральные", Color(0xFFEBE5DD), 0.92f, 0.90f, 0.87f),
    ColorPreset("Слоновая к.", Color(0xFFE0D8C8), 0.88f, 0.85f, 0.78f),
    ColorPreset("Жёлтые", Color(0xFFD6C8A0), 0.84f, 0.78f, 0.63f),
)

// ═══════════════════════════════════════════════
//  ГЛАВНЫЙ ЭКРАН РЕДАКТОРА
// ═══════════════════════════════════════════════
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelEditorScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val editor = remember { GlbTextureEditor(ctx) }

    // SceneView
    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val environment = rememberEnvironment(environmentLoader)
    val modelInstance = rememberModelInstance(modelLoader, MODEL_PATH)
    val cameraNode = rememberCameraNode(engine) { position = CAM_POS }

    // Editor state
    var elements by remember { mutableStateOf<List<EditableElement>>(emptyList()) }
    var selectedIdx by remember { mutableIntStateOf(0) }
    var scanned by remember { mutableStateOf(false) }
    var isDirectTouchMode by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    // ── HOISTED UV Transform State (общий для 3D Touch и нижней панели) ──
    val activeElem = elements.getOrNull(selectedIdx)
    var uiScaleX by remember(selectedIdx) { mutableFloatStateOf(activeElem?.uvScaleX ?: 1f) }
    var uiScaleY by remember(selectedIdx) { mutableFloatStateOf(activeElem?.uvScaleY ?: 1f) }
    var uiOffsetX by remember(selectedIdx) { mutableFloatStateOf(activeElem?.uvOffsetX ?: 0f) }
    var uiOffsetY by remember(selectedIdx) { mutableFloatStateOf(activeElem?.uvOffsetY ?: 0f) }
    var uiRot by remember(selectedIdx) { mutableFloatStateOf(activeElem?.uvRotationDeg ?: 0f) }
    var uiMetallic by remember(selectedIdx) { mutableFloatStateOf(activeElem?.currentMetallic ?: 0f) }
    var uiRoughness by remember(selectedIdx) { mutableFloatStateOf(activeElem?.currentRoughness ?: 0.5f) }

    // ── Сканируем модель при загрузке ──
    LaunchedEffect(modelInstance) {
        val mi = modelInstance ?: return@LaunchedEffect
        if (!scanned) {
            elements = editor.scanModel(engine, mi)
            scanned = true
        }
    }

    // ── Image picker ──
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val elem = elements.getOrNull(selectedIdx) ?: return@rememberLauncherForActivityResult
        val ok = editor.loadTextureFromUri(engine, elem, uri)
        val label = editor.getLabel(elem)
        Toast.makeText(
            ctx,
            if (ok) "Текстура → $label" else "Ошибка загрузки",
            Toast.LENGTH_SHORT
        ).show()
    }

    // ── SAF: выбор места сохранения ──
    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        isSaving = true
        try {
            val sourceFile = editor.ensureSourceInCache(MODEL_PATH)
            val ok = ctx.contentResolver.openOutputStream(uri)?.use { stream ->
                editor.saveToStream(sourceFile.absolutePath, stream)
            } ?: false
            Toast.makeText(
                ctx,
                if (ok) "Модель сохранена!" else "Ошибка сохранения",
                Toast.LENGTH_LONG
            ).show()
        } catch (e: Exception) {
            e.printStackTrace()
            Toast.makeText(ctx, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
        isSaving = false
    }

    // ── Cleanup ──
    DisposableEffect(Unit) {
        onDispose { editor.destroy(engine) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Редактор модели", fontWeight = FontWeight.Bold) },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
                actions = {
                    TextButton(
                        onClick = { saveLauncher.launch("edited_model.glb") },
                        enabled = !isSaving
                    ) {
                        Text(
                            if (isSaving) "Сохраняю..." else "Сохранить",
                            color = if (isSaving) Color.Gray else Color(0xFF4CAF50),
                            fontWeight = FontWeight.Bold
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = Color(0xFF12121A)
                )
            )
        },
        containerColor = Color(0xFF12121A)
    ) { pad ->
        Column(
            Modifier
                .fillMaxSize()
                .padding(pad)
        ) {
            // ═══════════════════════════════════════
            //  3D VIEWPORT
            // ═══════════════════════════════════════
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF0F0F15))
            ) {
                val camManipulator = rememberCameraManipulator(
                    orbitHomePosition = CAM_POS,
                    targetPosition = CAM_TGT,
                )

                Scene(
                    modifier = Modifier.fillMaxSize(),
                    engine = engine,
                    modelLoader = modelLoader,
                    cameraNode = cameraNode,
                    cameraManipulator = camManipulator,
                    environment = environment,
                ) {
                    modelInstance?.let {
                        ModelNode(
                            modelInstance = it,
                            scaleToUnits = SCALE,
                            centerOrigin = Position(0f, 0f, 0f),
                            autoAnimate = false,
                        )
                    }
                }

                // ── 3D Touch Overlay ──
                if (isDirectTouchMode && activeElem != null) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(Color(0x226C63FF))
                            .pointerInput(selectedIdx) {
                                detectDragGestures { change, dragAmount ->
                                    change.consume()
                                    uiOffsetX += dragAmount.x / 900f
                                    uiOffsetY += dragAmount.y / 900f
                                    editor.applyTransform(
                                        engine, activeElem,
                                        scaleX = uiScaleX, scaleY = uiScaleY,
                                        offsetX = uiOffsetX, offsetY = uiOffsetY,
                                        rotDeg = uiRot
                                    )
                                }
                            }
                    )
                }

                // Loading
                if (!scanned) {
                    CircularProgressIndicator(
                        Modifier.align(Alignment.Center),
                        color = Color.White
                    )
                }

                // Toggle button
                SmallFloatingActionButton(
                    onClick = { isDirectTouchMode = !isDirectTouchMode },
                    modifier = Modifier
                        .align(Alignment.TopEnd)
                        .padding(12.dp),
                    containerColor = if (isDirectTouchMode) Color(0xFFE94560) else Color(0xFF2A2A3D),
                    contentColor = Color.White,
                ) {
                    Icon(
                        imageVector = if (isDirectTouchMode) Icons.Filled.Edit
                        else Icons.Filled.Visibility,
                        contentDescription = "Toggle mode"
                    )
                }

                if (isDirectTouchMode) {
                    Text(
                        "РЕЖИМ ТЕКСТУРЫ: скользите пальцем",
                        modifier = Modifier
                            .align(Alignment.BottomCenter)
                            .padding(bottom = 8.dp)
                            .background(Color(0xAA000000), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        color = Color.White,
                        fontSize = 10.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // ═══════════════════════════════════════
            //  CONTROL PANEL
            // ═══════════════════════════════════════
            if (elements.isEmpty()) return@Column

            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1.15f)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color(0xFF1E1E2E), Color(0xFF12121A))
                        )
                    )
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                // ── Выбор элемента ──
                Text(
                    "СЛОЙ (Mesh)",
                    color = Color(0xFF8888AA),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    elements.forEachIndexed { i, elem ->
                        FilterChip(
                            selected = selectedIdx == i,
                            onClick = { selectedIdx = i },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = Color(0xFF6C63FF),
                                selectedLabelColor = Color.White,
                                containerColor = Color(0xFF2A2A3D),
                                labelColor = Color(0xFFCCCCCC),
                            ),
                            label = {
                                Text(editor.getLabel(elem), fontSize = 11.sp)
                            },
                            shape = RoundedCornerShape(10.dp),
                        )
                    }
                }

                val sel = activeElem ?: return@Column
                Spacer(Modifier.height(12.dp))

                // ── Текстура ──
                Button(
                    onClick = { imagePicker.launch("image/*") },
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(
                        containerColor = Color(0xFF00C896)
                    )
                ) {
                    Text(
                        "Загрузить текстуру",
                        color = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                }

                Spacer(Modifier.height(14.dp))

                // ── Цветовые пресеты ──
                val presets = when (sel.type) {
                    ElementType.EYE -> EYE_COLORS
                    ElementType.TEETH -> TEETH_COLORS
                    ElementType.SKIN -> SKIN_COLORS
                    else -> SKIN_COLORS
                }
                Text("ЦВЕТ", color = Color(0xFF8888AA), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    presets.forEach { p ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable {
                                editor.setColor(sel, p.r, p.g, p.b)
                            }
                        ) {
                            Box(
                                Modifier
                                    .size(32.dp)
                                    .clip(CircleShape)
                                    .background(p.ui)
                                    .border(1.5.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                            )
                            Text(p.label, fontSize = 8.sp, color = Color.Gray)
                        }
                    }
                }

                Spacer(Modifier.height(14.dp))

                // ── Вкладки трансформации ──
                var editTab by remember { mutableIntStateOf(0) }
                Text(
                    "ТРАНСФОРМАЦИЯ ТЕКСТУРЫ",
                    color = Color(0xFF8888AA),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(6.dp))

                TabRow(
                    selectedTabIndex = editTab,
                    containerColor = Color.Transparent,
                    contentColor = Color.White,
                    divider = {},
                    indicator = {},
                ) {
                    listOf("Позиция", "Масштаб", "Поворот", "PBR").forEachIndexed { i, title ->
                        Tab(
                            selected = editTab == i,
                            onClick = { editTab = i },
                            text = {
                                Text(
                                    title,
                                    fontSize = 11.sp,
                                    fontWeight = if (editTab == i) FontWeight.Bold else FontWeight.Normal,
                                    color = if (editTab == i) Color(0xFF6C63FF) else Color.Gray
                                )
                            }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── TAB 0: Позиция (2D Trackpad) ──
                AnimatedVisibility(visible = editTab == 0, enter = fadeIn(), exit = fadeOut()) {
                    Column(
                        Modifier.fillMaxWidth(),
                        horizontalAlignment = Alignment.CenterHorizontally
                    ) {
                        Text(
                            "Тяните пальцем для сдвига текстуры",
                            color = Color.Gray,
                            fontSize = 11.sp
                        )
                        Spacer(Modifier.height(10.dp))
                        Box(
                            Modifier
                                .size(200.dp)
                                .clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF2A2A3D))
                                .border(
                                    1.5.dp,
                                    Color(0xFF6C63FF).copy(alpha = 0.4f),
                                    RoundedCornerShape(14.dp)
                                )
                                .pointerInput(selectedIdx) {
                                    detectDragGestures { change, dragAmount ->
                                        change.consume()
                                        uiOffsetX += dragAmount.x / 1200f
                                        uiOffsetY += dragAmount.y / 1200f
                                        if (sel.hasCustomTexture) {
                                            editor.applyTransform(
                                                engine, sel,
                                                scaleX = uiScaleX, scaleY = uiScaleY,
                                                offsetX = uiOffsetX, offsetY = uiOffsetY,
                                                rotDeg = uiRot
                                            )
                                        }
                                    }
                                }
                        ) {
                            // Визуальный курсор
                            Box(
                                Modifier
                                    .align(Alignment.Center)
                                    .offset(
                                        x = (uiOffsetX * 500f).dp.coerceIn((-90).dp, 90.dp),
                                        y = (uiOffsetY * 500f).dp.coerceIn((-90).dp, 90.dp)
                                    )
                                    .size(16.dp)
                                    .clip(CircleShape)
                                    .background(Color(0xFF00C896))
                            )
                        }
                        Spacer(Modifier.height(6.dp))
                        Text(
                            "X: ${"%.3f".format(uiOffsetX)}  Y: ${"%.3f".format(uiOffsetY)}",
                            color = Color(0xFF666688),
                            fontSize = 10.sp
                        )
                    }
                }

                // ── TAB 1: Масштаб ──
                AnimatedVisibility(visible = editTab == 1, enter = fadeIn(), exit = fadeOut()) {
                    Column {
                        ProSlider(
                            label = "Универсальный зум",
                            value = (uiScaleX + uiScaleY) / 2f,
                            range = 0.1f..5f,
                            accent = Color(0xFF6C63FF)
                        ) { v ->
                            uiScaleX = v; uiScaleY = v
                            if (sel.hasCustomTexture) {
                                editor.applyTransform(
                                    engine, sel,
                                    scaleX = v, scaleY = v,
                                    offsetX = uiOffsetX, offsetY = uiOffsetY,
                                    rotDeg = uiRot
                                )
                            }
                        }
                        ProSlider("Ширина (X)", uiScaleX, 0.05f..4f) { v ->
                            uiScaleX = v
                            if (sel.hasCustomTexture) {
                                editor.applyTransform(
                                    engine, sel,
                                    scaleX = v, scaleY = uiScaleY,
                                    offsetX = uiOffsetX, offsetY = uiOffsetY,
                                    rotDeg = uiRot
                                )
                            }
                        }
                        ProSlider("Высота (Y)", uiScaleY, 0.05f..4f) { v ->
                            uiScaleY = v
                            if (sel.hasCustomTexture) {
                                editor.applyTransform(
                                    engine, sel,
                                    scaleX = uiScaleX, scaleY = v,
                                    offsetX = uiOffsetX, offsetY = uiOffsetY,
                                    rotDeg = uiRot
                                )
                            }
                        }
                    }
                }

                // ── TAB 2: Поворот ──
                AnimatedVisibility(visible = editTab == 2, enter = fadeIn(), exit = fadeOut()) {
                    ProSlider(
                        label = "Угол: ${uiRot.toInt()}°",
                        value = uiRot,
                        range = -180f..180f,
                        accent = Color(0xFFE94560)
                    ) { v ->
                        uiRot = v
                        if (sel.hasCustomTexture) {
                            editor.applyTransform(
                                engine, sel,
                                scaleX = uiScaleX, scaleY = uiScaleY,
                                offsetX = uiOffsetX, offsetY = uiOffsetY,
                                rotDeg = v
                            )
                        }
                    }
                }

                // ── TAB 3: PBR ──
                AnimatedVisibility(visible = editTab == 3, enter = fadeIn(), exit = fadeOut()) {
                    Column {
                        ProSlider(
                            label = "Metallic: ${"%.2f".format(uiMetallic)}",
                            value = uiMetallic,
                            range = 0f..1f,
                            accent = Color(0xFFBBBBDD)
                        ) { v ->
                            uiMetallic = v
                            editor.setMetallic(sel, v)
                        }
                        ProSlider(
                            label = "Roughness: ${"%.2f".format(uiRoughness)}",
                            value = uiRoughness,
                            range = 0f..1f,
                            accent = Color(0xFF88AA88)
                        ) { v ->
                            uiRoughness = v
                            editor.setRoughness(sel, v)
                        }
                    }
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

// ═══════════════════════════════════════════════
//  Переиспользуемый Pro-Slider
// ═══════════════════════════════════════════════
@Composable
private fun ProSlider(
    label: String,
    value: Float,
    range: ClosedFloatingPointRange<Float>,
    accent: Color = Color(0xFF6C63FF),
    onChange: (Float) -> Unit,
) {
    Spacer(Modifier.height(8.dp))
    Text(label, fontSize = 11.sp, color = Color.LightGray, fontWeight = FontWeight.Medium)
    Slider(
        value = value,
        onValueChange = onChange,
        valueRange = range,
        modifier = Modifier.fillMaxWidth(),
        colors = SliderDefaults.colors(
            thumbColor = accent,
            activeTrackColor = accent,
            inactiveTrackColor = Color(0xFF333344)
        )
    )
}