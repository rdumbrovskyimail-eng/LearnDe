package com.codeextractor.app.presentation.editor

import android.net.Uri
import android.util.Log
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
import io.github.sceneview.model.ModelInstance
import io.github.sceneview.rememberCameraManipulator
import io.github.sceneview.rememberCameraNode
import io.github.sceneview.rememberEngine
import io.github.sceneview.rememberEnvironment
import io.github.sceneview.rememberEnvironmentLoader
import io.github.sceneview.rememberModelLoader
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

private const val MODEL_ASSET_PATH = "models/source_named.glb"
private val CAM_POS = Float3(0f, 1.35f, 0.7f)
private val CAM_TGT = Float3(0f, 1.35f, 0f)
private const val SCALE = 0.35f

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
private val BG_FILL_COLORS = listOf(
    ColorPreset("Прозрачный", Color(0xFF222222), 0f, 0f, 0f),
    ColorPreset("Фарфор", Color(0xFFFAE7D8), 0.98f, 0.91f, 0.85f),
    ColorPreset("Светлая", Color(0xFFF5D6C3), 0.96f, 0.84f, 0.76f),
    ColorPreset("Средняя", Color(0xFFDAB99A), 0.85f, 0.73f, 0.60f),
    ColorPreset("Загар", Color(0xFFC49E7A), 0.77f, 0.62f, 0.48f),
    ColorPreset("Тёмная", Color(0xFF8D6E4C), 0.55f, 0.43f, 0.30f),
    ColorPreset("Эбеновая", Color(0xFF2A1B0E), 0.16f, 0.11f, 0.05f),
    ColorPreset("Красный", Color(0xFFC04040), 0.75f, 0.25f, 0.25f),
    ColorPreset("Серый", Color(0xFF888888), 0.53f, 0.53f, 0.53f),
    ColorPreset("Белый", Color(0xFFF0F0F0), 0.94f, 0.94f, 0.94f),
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
private val HAIR_COLORS = listOf(
    ColorPreset("Чёрный", Color(0xFF1A1A1A), 0.10f, 0.10f, 0.10f),
    ColorPreset("Каштан", Color(0xFF5C3317), 0.36f, 0.20f, 0.09f),
    ColorPreset("Русый", Color(0xFF8B7355), 0.55f, 0.45f, 0.33f),
    ColorPreset("Блонд", Color(0xFFD4B896), 0.83f, 0.72f, 0.59f),
    ColorPreset("Рыжий", Color(0xFFA0522D), 0.63f, 0.32f, 0.18f),
    ColorPreset("Платина", Color(0xFFE8E0D0), 0.91f, 0.88f, 0.82f),
)

// Группы зон для чипов
private enum class ZoneGroup(val label: String, val icon: String) {
    FACE("Лицо", "👤"),
    SIDES("Бока", "👂"),
    HAIR("Волосы", "💇"),
    NECK("Шея", "🔽"),
    MOUTH("Рот", "👄"),
    EYES("Глаза", "👁"),
    TEETH("Зубы", "🦷"),
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelEditorScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val editor = remember { GlbTextureEditor(ctx) }

    val engine = rememberEngine()
    val modelLoader = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val environment = rememberEnvironment(environmentLoader)
    val cameraNode = rememberCameraNode(engine) { position = CAM_POS }

    var modelInstance by remember { mutableStateOf<ModelInstance?>(null) }

    LaunchedEffect(modelLoader) {
        try {
            val buffer = withContext(Dispatchers.IO) {
                val path = editor.preparePatchedModel(MODEL_ASSET_PATH)
                val bytes = java.io.File(path).readBytes()
                java.nio.ByteBuffer.allocateDirect(bytes.size).also {
                    it.put(bytes); it.rewind()
                }
            }
            modelInstance = modelLoader.createModelInstance(buffer)
            Log.d("GLB_EDITOR", "Model loaded: ${modelInstance != null}")
        } catch (e: Exception) {
            Log.e("GLB_EDITOR", "Model load failed", e)
        }
    }

    var elements by remember { mutableStateOf<List<EditableElement>>(emptyList()) }
    var selectedIdx by remember { mutableIntStateOf(0) }
    var scanned by remember { mutableStateOf(false) }
    var isDirectTouchMode by remember { mutableStateOf(false) }
    var isSaving by remember { mutableStateOf(false) }

    val activeElem = elements.getOrNull(selectedIdx)
    var uiScaleX by remember(selectedIdx) { mutableFloatStateOf(activeElem?.uvScaleX ?: 1f) }
    var uiScaleY by remember(selectedIdx) { mutableFloatStateOf(activeElem?.uvScaleY ?: 1f) }
    var uiOffsetX by remember(selectedIdx) { mutableFloatStateOf(activeElem?.uvOffsetX ?: 0f) }
    var uiOffsetY by remember(selectedIdx) { mutableFloatStateOf(activeElem?.uvOffsetY ?: 0f) }
    var uiRot by remember(selectedIdx) { mutableFloatStateOf(activeElem?.uvRotationDeg ?: 0f) }
    var uiMetallic by remember(selectedIdx) { mutableFloatStateOf(activeElem?.currentMetallic ?: 0f) }
    var uiRoughness by remember(selectedIdx) { mutableFloatStateOf(activeElem?.currentRoughness ?: 0.5f) }

    var pendingTransformApply by remember { mutableStateOf(false) }

    LaunchedEffect(uiScaleX, uiScaleY, uiOffsetX, uiOffsetY, uiRot) {
        if (!pendingTransformApply) return@LaunchedEffect
        kotlinx.coroutines.delay(80)
        activeElem?.let { elem ->
            if (elem.hasCustomTexture) {
                editor.applyTransform(engine, elem,
                    scaleX = uiScaleX, scaleY = uiScaleY,
                    offsetX = uiOffsetX, offsetY = uiOffsetY, rotDeg = uiRot)
            }
        }
        pendingTransformApply = false
    }

    LaunchedEffect(modelInstance) {
        val mi = modelInstance ?: return@LaunchedEffect
        if (!scanned) {
            try {
                elements = editor.scanModel(engine, mi)
                scanned = true
                // Явно применить цвет кожи как фон головы (на случай гонки GPU-очереди)
                editor.setHeadBackgroundColor(
                    engine,
                    GlbTextureEditor.SKIN_COLOR_SRGB
                )
                Log.d("GLB_EDITOR", "Scan: ${elements.size} elements")
            } catch (e: Exception) {
                Log.e("GLB_EDITOR", "Scan failed", e)
            }
        }
    }

    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        val elem = elements.getOrNull(selectedIdx) ?: return@rememberLauncherForActivityResult
        val ok = editor.loadTextureForZone(engine, elem, uri)
        val label = editor.getLabel(elem)
        Toast.makeText(ctx, if (ok) "✓ $label" else "Ошибка загрузки", Toast.LENGTH_SHORT).show()
    }

    val scope = rememberCoroutineScope()

    val saveLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.CreateDocument("application/octet-stream")
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        isSaving = true
        scope.launch(Dispatchers.IO) {
            try {
                val srcFile = editor.ensureSourceInCache(MODEL_ASSET_PATH)
                val ok = ctx.contentResolver.openOutputStream(uri)?.use { editor.saveToStream(srcFile.absolutePath, it) } ?: false
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, if (ok) "Сохранено!" else "Ошибка", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            isSaving = false
        }
    }

    val saveAllTexturesLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { treeUri: Uri? ->
        treeUri ?: return@rememberLauncherForActivityResult
        isSaving = true
        scope.launch(Dispatchers.IO) {
            try {
                val docUri = androidx.documentfile.provider.DocumentFile.fromTreeUri(ctx, treeUri)
                    ?: throw Exception("Cannot open folder")

                val textures = listOf(
                    "head_texture.png" to editor.getHeadCompositeBitmap(),
                    "eyes_texture.png" to editor.getEyesBitmap(),
                    "mouth_texture.png" to editor.getMouthBitmap(),
                    "teeth_texture.png" to editor.getTeethBitmap(),
                )

                var saved = 0
                for ((name, bmp) in textures) {
                    if (bmp == null || bmp.isRecycled) continue

                    // Удалить старый файл если есть
                    docUri.findFile(name)?.delete()

                    val file = docUri.createFile("image/png", name) ?: continue
                    ctx.contentResolver.openOutputStream(file.uri)?.use { out ->
                        bmp.compress(android.graphics.Bitmap.CompressFormat.PNG, 100, out)
                        saved++
                    }
                }

                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "Сохранено $saved из 4 текстур", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                withContext(Dispatchers.Main) {
                    Toast.makeText(ctx, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
                }
            }
            isSaving = false
        }
    }

    DisposableEffect(engine) { onDispose { editor.destroy(engine) } }

    LaunchedEffect(Unit) {
        while (true) { kotlinx.coroutines.delay(16); editor.flushPendingGpuOps(engine) }
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
                    TextButton(onClick = { saveLauncher.launch("edited_model.glb") }, enabled = !isSaving) {
                        Text(if (isSaving) "..." else "GLB", color = if (isSaving) Color.Gray else Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                    }
                    TextButton(onClick = { saveAllTexturesLauncher.launch(null) }, enabled = !isSaving) {
                        Text(if (isSaving) "..." else "TEX", color = if (isSaving) Color.Gray else Color(0xFF2196F3), fontWeight = FontWeight.Bold)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFF12121A))
            )
        },
        containerColor = Color(0xFF12121A)
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {
            // ── 3D Viewport ──
            Box(Modifier.fillMaxWidth().weight(1f).background(Color(0xFF0F0F15))) {
                val camManipulator = rememberCameraManipulator(
                    orbitHomePosition = CAM_POS, targetPosition = CAM_TGT)
                Scene(
                    modifier = Modifier.fillMaxSize(),
                    engine = engine, modelLoader = modelLoader,
                    cameraNode = cameraNode, cameraManipulator = camManipulator,
                    environment = environment,
                ) {
                    modelInstance?.let {
                        ModelNode(modelInstance = it, scaleToUnits = SCALE,
                            centerOrigin = Position(0f, 0f, 0f), autoAnimate = false)
                    }
                }

                if (isDirectTouchMode && activeElem != null) {
                    Box(Modifier.fillMaxSize()
                        .background(Color(0x226C63FF))
                        .pointerInput(selectedIdx) {
                            detectDragGestures { change, drag ->
                                change.consume()
                                uiOffsetX += drag.x / 900f
                                uiOffsetY += drag.y / 900f
                                pendingTransformApply = true
                            }
                        })
                }

                if (modelInstance == null) {
                    CircularProgressIndicator(Modifier.align(Alignment.Center), color = Color.White)
                }

                SmallFloatingActionButton(
                    onClick = { isDirectTouchMode = !isDirectTouchMode },
                    modifier = Modifier.align(Alignment.TopEnd).padding(12.dp),
                    containerColor = if (isDirectTouchMode) Color(0xFFE94560) else Color(0xFF2A2A3D),
                    contentColor = Color.White,
                ) {
                    Icon(if (isDirectTouchMode) Icons.Filled.Edit else Icons.Filled.Visibility, "mode")
                }

                if (isDirectTouchMode) {
                    Text("ТЕКСТУРА: скользите пальцем",
                        modifier = Modifier.align(Alignment.BottomCenter).padding(bottom = 8.dp)
                            .background(Color(0xAA000000), RoundedCornerShape(8.dp))
                            .padding(horizontal = 12.dp, vertical = 4.dp),
                        color = Color.White, fontSize = 10.sp, fontWeight = FontWeight.Bold)
                }
            }

            if (elements.isEmpty()) return@Column

            // ── Control Panel ──
            Column(
                Modifier.fillMaxWidth().weight(1.15f)
                    .background(Brush.verticalGradient(listOf(Color(0xFF1E1E2E), Color(0xFF12121A))))
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 14.dp, vertical = 10.dp)
            ) {
                // ── Zone Selector ──
                Text("ЗОНА", color = Color(0xFF8888AA), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))

                // Группируем элементы
                val grouped = remember(elements) { groupElements(elements) }
                var expandedGroup by remember { mutableStateOf<ZoneGroup?>(ZoneGroup.FACE) }

                // Группы зон
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    grouped.keys.forEach { group ->
                        val isExpanded = expandedGroup == group
                        val hasSelection = grouped[group]?.any { elements.indexOf(it) == selectedIdx } == true
                        FilterChip(
                            selected = isExpanded || hasSelection,
                            onClick = { expandedGroup = if (isExpanded) null else group },
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = if (hasSelection) Color(0xFF6C63FF) else Color(0xFF3A3A5D),
                                selectedLabelColor = Color.White,
                                containerColor = Color(0xFF2A2A3D),
                                labelColor = Color(0xFFCCCCCC),
                            ),
                            label = { Text("${group.icon} ${group.label}", fontSize = 11.sp) },
                            shape = RoundedCornerShape(10.dp),
                        )
                    }
                }

                // Элементы выбранной группы
                AnimatedVisibility(visible = expandedGroup != null) {
                    val groupElems = grouped[expandedGroup] ?: emptyList()
                    if (groupElems.isNotEmpty()) {
                        Row(
                            Modifier.padding(top = 6.dp).horizontalScroll(rememberScrollState()),
                            horizontalArrangement = Arrangement.spacedBy(6.dp)
                        ) {
                            groupElems.forEach { elem ->
                                val idx = elements.indexOf(elem)
                                val hasTexture = elem.hasCustomTexture
                                FilterChip(
                                    selected = selectedIdx == idx,
                                    onClick = { selectedIdx = idx },
                                    colors = FilterChipDefaults.filterChipColors(
                                        selectedContainerColor = Color(0xFF6C63FF),
                                        selectedLabelColor = Color.White,
                                        containerColor = if (hasTexture) Color(0xFF2D4A3D) else Color(0xFF2A2A3D),
                                        labelColor = if (hasTexture) Color(0xFF80E0A0) else Color(0xFFCCCCCC),
                                    ),
                                    label = {
                                        Text(
                                            "${if (hasTexture) "✓ " else ""}${editor.getLabel(elem)}",
                                            fontSize = 10.sp
                                        )
                                    },
                                    shape = RoundedCornerShape(8.dp),
                                )
                            }
                        }
                    }
                }

                val sel = activeElem ?: return@Column
                Spacer(Modifier.height(12.dp))

                // ── Background Fill (для головы) ──
                if (sel.type == ElementType.HEAD_ZONE) {
                    Text("ЗАЛИВКА ФОНА", color = Color(0xFF8888AA), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        BG_FILL_COLORS.forEach { p ->
                            val isTransparent = p.r == 0f && p.g == 0f && p.b == 0f
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable {
                                    val color = if (isTransparent) {
                                        android.graphics.Color.TRANSPARENT
                                    } else {
                                        android.graphics.Color.rgb(
                                            (p.r * 255).toInt(),
                                            (p.g * 255).toInt(),
                                            (p.b * 255).toInt()
                                        )
                                    }
                                    editor.setHeadBackgroundColor(engine, color)
                                }
                            ) {
                                Box(
                                    Modifier.size(32.dp).clip(CircleShape)
                                        .background(if (isTransparent) Color(0xFF222222) else p.ui)
                                        .border(1.5.dp, Color.White.copy(alpha = 0.6f), CircleShape)
                                ) {
                                    if (isTransparent) {
                                        Text("✕", color = Color.Red, fontSize = 14.sp,
                                            modifier = Modifier.align(Alignment.Center))
                                    }
                                }
                                Text(p.label, fontSize = 8.sp, color = Color.Gray)
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }

                // ── Load Texture Button ──
                Button(
                    onClick = { imagePicker.launch("image/*") },
                    modifier = Modifier.fillMaxWidth().height(48.dp),
                    shape = RoundedCornerShape(12.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF00C896))
                ) {
                    Text("Загрузить текстуру → ${editor.getLabel(sel)}",
                        color = Color.White, fontWeight = FontWeight.Bold, fontSize = 13.sp)
                }

                if (sel.hasCustomTexture) {
                    Text("✓ Текстура загружена", color = Color(0xFF80E0A0),
                        fontSize = 10.sp, modifier = Modifier.padding(top = 4.dp))
                }

                Spacer(Modifier.height(14.dp))

                // ── Color Presets ──
                val presets = getColorPresets(sel)
                if (presets.isNotEmpty()) {
                    Text("ЦВЕТ", color = Color(0xFF8888AA), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                    Spacer(Modifier.height(6.dp))
                    Row(
                        Modifier.horizontalScroll(rememberScrollState()),
                        horizontalArrangement = Arrangement.spacedBy(10.dp)
                    ) {
                        presets.forEach { p ->
                            Column(
                                horizontalAlignment = Alignment.CenterHorizontally,
                                modifier = Modifier.clickable { editor.setColor(sel, p.r, p.g, p.b) }
                            ) {
                                Box(Modifier.size(32.dp).clip(CircleShape).background(p.ui)
                                    .border(1.5.dp, Color.White.copy(alpha = 0.6f), CircleShape))
                                Text(p.label, fontSize = 8.sp, color = Color.Gray)
                            }
                        }
                    }
                    Spacer(Modifier.height(14.dp))
                }

                // ── Transform Tabs ──
                var editTab by remember { mutableIntStateOf(1) } // Default: Scale
                Text("ТРАНСФОРМАЦИЯ", color = Color(0xFF8888AA), fontSize = 10.sp, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(6.dp))
                TabRow(
                    selectedTabIndex = editTab,
                    containerColor = Color.Transparent, contentColor = Color.White,
                    divider = {}, indicator = {},
                ) {
                    listOf("Позиция", "Масштаб", "Поворот", "PBR").forEachIndexed { i, title ->
                        Tab(selected = editTab == i, onClick = { editTab = i },
                            text = {
                                Text(title, fontSize = 11.sp,
                                    fontWeight = if (editTab == i) FontWeight.Bold else FontWeight.Normal,
                                    color = if (editTab == i) Color(0xFF6C63FF) else Color.Gray)
                            })
                    }
                }
                Spacer(Modifier.height(12.dp))

                AnimatedVisibility(visible = editTab == 0, enter = fadeIn(), exit = fadeOut()) {
                    Column(Modifier.fillMaxWidth(), horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Тяните пальцем для сдвига", color = Color.Gray, fontSize = 11.sp)
                        Spacer(Modifier.height(10.dp))
                        Box(
                            Modifier.size(200.dp).clip(RoundedCornerShape(14.dp))
                                .background(Color(0xFF2A2A3D))
                                .border(1.5.dp, Color(0xFF6C63FF).copy(alpha = 0.4f), RoundedCornerShape(14.dp))
                                .pointerInput(selectedIdx) {
                                    detectDragGestures { change, drag ->
                                        change.consume()
                                        uiOffsetX += drag.x / 1200f
                                        uiOffsetY += drag.y / 1200f
                                        pendingTransformApply = true
                                    }
                                }
                        ) {
                            Box(Modifier.align(Alignment.Center)
                                .offset(
                                    x = (uiOffsetX * 500f).dp.coerceIn((-90).dp, 90.dp),
                                    y = (uiOffsetY * 500f).dp.coerceIn((-90).dp, 90.dp))
                                .size(16.dp).clip(CircleShape).background(Color(0xFF00C896)))
                        }
                        Text("X: ${"%.3f".format(uiOffsetX)}  Y: ${"%.3f".format(uiOffsetY)}",
                            color = Color(0xFF666688), fontSize = 10.sp)
                    }
                }

                AnimatedVisibility(visible = editTab == 1, enter = fadeIn(), exit = fadeOut()) {
                    Column {
                        ProSlider("Универсальный зум", (uiScaleX + uiScaleY) / 2f, 0.1f..5f,
                            accent = Color(0xFF6C63FF)) { v -> uiScaleX = v; uiScaleY = v; pendingTransformApply = true }
                        ProSlider("Ширина (X)", uiScaleX, 0.05f..4f) { v -> uiScaleX = v; pendingTransformApply = true }
                        ProSlider("Высота (Y)", uiScaleY, 0.05f..4f) { v -> uiScaleY = v; pendingTransformApply = true }
                    }
                }

                AnimatedVisibility(visible = editTab == 2, enter = fadeIn(), exit = fadeOut()) {
                    ProSlider("Угол: ${uiRot.toInt()}°", uiRot, -180f..180f,
                        accent = Color(0xFFE94560)) { v -> uiRot = v; pendingTransformApply = true }
                }

                AnimatedVisibility(visible = editTab == 3, enter = fadeIn(), exit = fadeOut()) {
                    Column {
                        ProSlider("Metallic: ${"%.2f".format(uiMetallic)}", uiMetallic, 0f..1f,
                            accent = Color(0xFFBBBBDD)) { v -> uiMetallic = v; editor.setMetallic(sel, v) }
                        ProSlider("Roughness: ${"%.2f".format(uiRoughness)}", uiRoughness, 0f..1f,
                            accent = Color(0xFF88AA88)) { v -> uiRoughness = v; editor.setRoughness(sel, v) }
                    }
                }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

private fun groupElements(elements: List<EditableElement>): Map<ZoneGroup, List<EditableElement>> {
    val map = linkedMapOf<ZoneGroup, MutableList<EditableElement>>()
    for (elem in elements) {
        val group = when (elem.type) {
            ElementType.HEAD_ZONE -> when (elem.headZone) {
                com.codeextractor.app.editor.HeadZone.FACE_FRONT -> ZoneGroup.FACE
                com.codeextractor.app.editor.HeadZone.SIDE_LEFT,
                com.codeextractor.app.editor.HeadZone.SIDE_RIGHT -> ZoneGroup.SIDES
                com.codeextractor.app.editor.HeadZone.HAIR_TOP,
                com.codeextractor.app.editor.HeadZone.HAIR_BACK -> ZoneGroup.HAIR
                com.codeextractor.app.editor.HeadZone.NECK_FRONT,
                com.codeextractor.app.editor.HeadZone.NECK_BACK -> ZoneGroup.NECK
                com.codeextractor.app.editor.HeadZone.MOUTH_INNER -> ZoneGroup.MOUTH
                null -> ZoneGroup.FACE
            }
            ElementType.EYE_LEFT, ElementType.EYE_RIGHT -> ZoneGroup.EYES
            ElementType.TEETH -> ZoneGroup.TEETH
            else -> ZoneGroup.FACE
        }
        map.getOrPut(group) { mutableListOf() }.add(elem)
    }
    return map
}

private fun getColorPresets(elem: EditableElement): List<ColorPreset> = when (elem.type) {
    ElementType.EYE_LEFT, ElementType.EYE_RIGHT -> EYE_COLORS
    ElementType.TEETH -> TEETH_COLORS
    ElementType.HEAD_ZONE -> when (elem.headZone) {
        com.codeextractor.app.editor.HeadZone.HAIR_TOP,
        com.codeextractor.app.editor.HeadZone.HAIR_BACK -> HAIR_COLORS
        com.codeextractor.app.editor.HeadZone.FACE_FRONT,
        com.codeextractor.app.editor.HeadZone.SIDE_LEFT,
        com.codeextractor.app.editor.HeadZone.SIDE_RIGHT,
        com.codeextractor.app.editor.HeadZone.NECK_FRONT,
        com.codeextractor.app.editor.HeadZone.NECK_BACK -> SKIN_COLORS
        com.codeextractor.app.editor.HeadZone.MOUTH_INNER -> SKIN_COLORS
        null -> SKIN_COLORS
    }
    else -> SKIN_COLORS
}

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
        value = value, onValueChange = onChange, valueRange = range,
        modifier = Modifier.fillMaxWidth(),
        colors = SliderDefaults.colors(
            thumbColor = accent, activeTrackColor = accent,
            inactiveTrackColor = Color(0xFF333344))
    )
}

