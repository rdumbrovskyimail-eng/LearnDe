package com.codeextractor.app.presentation.editor

import android.net.Uri
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codeextractor.app.editor.EditableElement
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
import java.io.File

private const val MODEL_PATH = "models/source_named.glb"
private val CAM_POS = Float3(0f, 1.35f, 0.7f)
private val CAM_TGT = Float3(0f, 1.35f, 0f)

// ═══════════════════════════════════════════════
//  Пресеты цветов для разных элементов
// ═══════════════════════════════════════════════
private data class CP(val label: String, val ui: Color, val r: Float, val g: Float, val b: Float)

private val HEAD_COLORS = listOf(
    CP("Светлая",  Color(0xFFF5D6C3), 0.96f, 0.84f, 0.76f),
    CP("Средняя",  Color(0xFFDAB99A), 0.85f, 0.73f, 0.60f),
    CP("Загар",    Color(0xFFC49E7A), 0.77f, 0.62f, 0.48f),
    CP("Тёмная",   Color(0xFF8D6E4C), 0.55f, 0.43f, 0.30f),
    CP("Чёрная",   Color(0xFF2A1B0E), 0.16f, 0.11f, 0.05f),
)
private val EYE_COLORS = listOf(
    CP("Белые",    Color(0xFFF2F2F2), 0.95f, 0.95f, 0.95f),
    CP("Кремовые", Color(0xFFF5F0E8), 0.96f, 0.94f, 0.91f),
    CP("Красные",  Color(0xFFD08080), 0.82f, 0.50f, 0.50f),
)
private val TEETH_COLORS = listOf(
    CP("Белые",    Color(0xFFEBE5DD), 0.92f, 0.90f, 0.87f),
    CP("Естеств.", Color(0xFFE0D8C8), 0.88f, 0.85f, 0.78f),
    CP("Жёлтые",  Color(0xFFD6C8A0), 0.84f, 0.78f, 0.63f),
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelEditorScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val editor = remember { GlbTextureEditor(ctx) }

    // ── SceneView engine/loader — создаются один раз ──
    val engine            = rememberEngine()
    val modelLoader       = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val environment       = rememberEnvironment(environmentLoader)
    val modelInstance     = rememberModelInstance(modelLoader, MODEL_PATH)
    val cameraNode        = rememberCameraNode(engine) { position = CAM_POS }

    // ── Состояния редактора ──
    var elements by remember { mutableStateOf<List<EditableElement>>(emptyList()) }
    var selectedIdx by remember { mutableIntStateOf(0) }
    var scanned by remember { mutableStateOf(false) }

    // RGB state per element
    val colorR = remember { mutableStateMapOf<Int, Float>() }
    val colorG = remember { mutableStateMapOf<Int, Float>() }
    val colorB = remember { mutableStateMapOf<Int, Float>() }
    val metallic = remember { mutableStateMapOf<Int, Float>() }
    val roughness = remember { mutableStateMapOf<Int, Float>() }

    // Сканируем модель когда она загрузится
    LaunchedEffect(modelInstance) {
        val mi = modelInstance ?: return@LaunchedEffect
        if (!scanned) {
            elements = editor.scanModel(engine, mi)
            elements.forEachIndexed { i, _ ->
                colorR[i] = 0.85f; colorG[i] = 0.73f; colorB[i] = 0.60f
                metallic[i] = 0f; roughness[i] = 0.6f
            }
            scanned = true
        }
    }

    // ── Image picker — привязан к выбранному элементу ──
    val imagePicker = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@rememberLauncherForActivityResult
        if (selectedIdx < elements.size) {
            val ok = editor.applyTextureFromUri(engine, elements[selectedIdx], uri, selectedIdx)
            val label = editor.getLabel(elements[selectedIdx])
            Toast.makeText(
                ctx,
                if (ok) "Текстура → $label" else "Ошибка загрузки",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    // ── Cleanup ──
    DisposableEffect(Unit) {
        onDispose { editor.destroy(engine) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Редактор модели") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                },
                actions = {
                    TextButton(onClick = {
                        val dir = File(ctx.getExternalFilesDir(null), "models")
                            .also { it.mkdirs() }
                        val out = File(dir, "edited_model.glb")
                        val src = File(ctx.cacheDir, "source_for_edit.glb")
                        // Копируем оригинал если ещё нет
                        if (!src.exists()) {
                            ctx.assets.open(MODEL_PATH).use { inp ->
                                src.outputStream().use { inp.copyTo(it) }
                            }
                        }
                        val ok = editor.saveToGlb(src.absolutePath, out)
                        Toast.makeText(
                            ctx,
                            if (ok) "Сохранено: ${out.name}" else "Ошибка",
                            Toast.LENGTH_LONG
                        ).show()
                    }) {
                        Text("Сохранить", color = Color(0xFF4CAF50), fontWeight = FontWeight.Bold)
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {

            // ═══════════════════════════════
            //  3D Scene — ЖИВОЙ, без перезагрузки
            // ═══════════════════════════════
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF1A1A2E))
            ) {
                Scene(
                    modifier          = Modifier.fillMaxSize(),
                    engine            = engine,
                    modelLoader       = modelLoader,
                    cameraNode        = cameraNode,
                    cameraManipulator = rememberCameraManipulator(
                        orbitHomePosition = CAM_POS,
                        targetPosition    = CAM_TGT,
                    ),
                    environment = environment,
                ) {
                    modelInstance?.let {
                        ModelNode(
                            modelInstance = it,
                            scaleToUnits  = 0.35f,
                            centerOrigin  = Position(0f, 0f, 0f),
                            autoAnimate   = false,
                        )
                    }
                }

                if (!scanned) {
                    CircularProgressIndicator(
                        Modifier.align(Alignment.Center),
                        color = Color.White
                    )
                }
            }

            // ═══════════════════════════════
            //  Контролы — нижняя часть
            // ═══════════════════════════════
            if (elements.isEmpty()) return@Column

            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(horizontal = 12.dp, vertical = 8.dp)
            ) {
                // ── Выбор элемента ──
                Text(
                    "Элемент модели",
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    elements.forEachIndexed { i, elem ->
                        FilterChip(
                            selected = selectedIdx == i,
                            onClick  = { selectedIdx = i },
                            label    = { Text(editor.getLabel(elem), fontSize = 12.sp) },
                        )
                    }
                }

                val sel = elements.getOrNull(selectedIdx) ?: return@Column

                Spacer(Modifier.height(12.dp))

                // ── ТЕКСТУРА — главная фича ──
                Text("Текстура", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Button(
                    onClick  = { imagePicker.launch("image/*") },
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.buttonColors(containerColor = Color(0xFF1976D2))
                ) {
                    Text("Выбрать изображение из галереи", color = Color.White)
                }

                Spacer(Modifier.height(12.dp))

                // ── Пресеты цветов ──
                val presets = when {
                    sel.meshName.contains("head")  -> HEAD_COLORS
                    sel.meshName.contains("eye")   -> EYE_COLORS
                    sel.meshName.contains("teeth")  -> TEETH_COLORS
                    else -> HEAD_COLORS
                }
                Text("Цвет", style = MaterialTheme.typography.titleSmall, fontWeight = FontWeight.Bold)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    presets.forEach { p ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable {
                                colorR[selectedIdx] = p.r
                                colorG[selectedIdx] = p.g
                                colorB[selectedIdx] = p.b
                                editor.setColor(sel, p.r, p.g, p.b)
                            }
                        ) {
                            Box(
                                Modifier.size(36.dp).clip(CircleShape)
                                    .background(p.ui).border(2.dp, Color.White, CircleShape)
                            )
                            Text(p.label, fontSize = 9.sp)
                        }
                    }
                }

                Spacer(Modifier.height(10.dp))

                // ── RGB слайдеры ──
                RgbRow("R", colorR[selectedIdx] ?: 0.5f, Color.Red) {
                    colorR[selectedIdx] = it
                    editor.setColor(sel, it, colorG[selectedIdx] ?: 0.5f, colorB[selectedIdx] ?: 0.5f)
                }
                RgbRow("G", colorG[selectedIdx] ?: 0.5f, Color.Green) {
                    colorG[selectedIdx] = it
                    editor.setColor(sel, colorR[selectedIdx] ?: 0.5f, it, colorB[selectedIdx] ?: 0.5f)
                }
                RgbRow("B", colorB[selectedIdx] ?: 0.5f, Color.Blue) {
                    colorB[selectedIdx] = it
                    editor.setColor(sel, colorR[selectedIdx] ?: 0.5f, colorG[selectedIdx] ?: 0.5f, it)
                }

                Spacer(Modifier.height(10.dp))

                // ── Metallic / Roughness ──
                Text("Metallic: ${"%.2f".format(metallic[selectedIdx] ?: 0f)}", fontSize = 12.sp)
                Slider(
                    value = metallic[selectedIdx] ?: 0f,
                    onValueChange = {
                        metallic[selectedIdx] = it
                        editor.setMetallic(sel, it)
                    },
                    valueRange = 0f..1f,
                )
                Text("Roughness: ${"%.2f".format(roughness[selectedIdx] ?: 0.6f)}", fontSize = 12.sp)
                Slider(
                    value = roughness[selectedIdx] ?: 0.6f,
                    onValueChange = {
                        roughness[selectedIdx] = it
                        editor.setRoughness(sel, it)
                    },
                    valueRange = 0f..1f,
                )

                Spacer(Modifier.height(24.dp))
            }
        }
    }
}

@Composable
private fun RgbRow(label: String, value: Float, color: Color, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.width(18.dp), fontSize = 12.sp, fontWeight = FontWeight.Bold)
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 0f..1f,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color)
        )
        Box(
            Modifier.size(24.dp).clip(RoundedCornerShape(4.dp))
                .background(Color(value, value, value))
                .border(1.dp, Color.Gray, RoundedCornerShape(4.dp))
        )
    }
}