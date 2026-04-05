package com.codeextractor.app.presentation.editor

import android.graphics.BitmapFactory
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
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codeextractor.app.editor.GlbEditor
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

// ═══════════════════════════════════════════════════════
//  Пресеты цветов
// ═══════════════════════════════════════════════════════
private data class CP(val label: String, val color: Color, val rgb: FloatArray)

private val SKIN = listOf(
    CP("Светлая",  Color(0xFFF5D6C3), floatArrayOf(0.96f, 0.84f, 0.76f)),
    CP("Средняя",  Color(0xFFDAB99A), floatArrayOf(0.85f, 0.73f, 0.60f)),
    CP("Загар",    Color(0xFFC49E7A), floatArrayOf(0.77f, 0.62f, 0.48f)),
    CP("Тёмная",   Color(0xFF8D6E4C), floatArrayOf(0.55f, 0.43f, 0.30f)),
)
private val EYES = listOf(
    CP("Белые",    Color(0xFFF0F0F0), floatArrayOf(0.94f, 0.94f, 0.94f)),
    CP("Кремовые", Color(0xFFF5F0E8), floatArrayOf(0.96f, 0.94f, 0.91f)),
)
private val TEETH = listOf(
    CP("Белые",      Color(0xFFEBE5DD), floatArrayOf(0.92f, 0.90f, 0.87f)),
    CP("Натуральные",Color(0xFFE0D8C8), floatArrayOf(0.88f, 0.85f, 0.78f)),
)

private const val ASSETS_MODEL = "models/source_named.glb"
private val CAM_POS = Float3(0f, 1.35f, 0.7f)
private val CAM_TGT = Float3(0f, 1.35f, 0f)

// Мэппинг material index → понятное имя
private val MAT_LABELS = mapOf(0 to "Голова / Кожа", 1 to "Глаза", 2 to "Зубы")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ModelEditorScreen(onBack: () -> Unit) {
    val ctx = LocalContext.current
    val editor = remember { GlbEditor(ctx) }

    // ── Состояния ──
    var ready       by remember { mutableStateOf(false) }
    var selMat      by remember { mutableIntStateOf(0) }
    var glbVer      by remember { mutableIntStateOf(0) }
    var tempFile    by remember { mutableStateOf<File?>(null) }

    val colors    = remember { mutableStateMapOf<Int, FloatArray>() }
    val metallics = remember { mutableStateMapOf<Int, Float>() }
    val roughs    = remember { mutableStateMapOf<Int, Float>() }

    // ── Инициализация: копируем GLB из assets → cache ──
    LaunchedEffect(Unit) {
        val cache = File(ctx.cacheDir, "editor_model.glb")
        ctx.assets.open(ASSETS_MODEL).use { inp -> cache.outputStream().use { inp.copyTo(it) } }
        if (editor.loadFromFile(cache)) {
            editor.getMaterials().forEach { m ->
                colors[m.index]    = m.baseColor.copyOf()
                metallics[m.index] = m.metallic
                roughs[m.index]    = m.roughness
            }
            editor.save(cache)
            tempFile = cache
            ready = true
        }
    }

    // ── Image picker ──
    val imagePicker = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri: Uri? ->
        uri?.let {
            val bmp = ctx.contentResolver.openInputStream(it)?.use { s -> BitmapFactory.decodeStream(s) }
            if (bmp != null) {
                editor.setTexture(selMat, bmp)
                rebuild(ctx, editor) { f -> tempFile = f; glbVer++ }
                Toast.makeText(ctx, "Текстура установлена", Toast.LENGTH_SHORT).show()
            }
        }
    }

    if (!ready) {
        Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) { CircularProgressIndicator() }
        return
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Редактор модели") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, "Назад")
                    }
                }
            )
        }
    ) { pad ->
        Column(Modifier.fillMaxSize().padding(pad)) {

            // ═════════════════════════════════════════
            //  3D-превью (верхняя часть)
            // ═════════════════════════════════════════
            Box(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .background(Color(0xFF1A1A2E))
            ) {
                tempFile?.let { file ->
                    key(glbVer) { EditorPreview(file) }
                }
            }

            // ═════════════════════════════════════════
            //  Контролы (нижняя часть)
            // ═════════════════════════════════════════
            Column(
                Modifier
                    .fillMaxWidth()
                    .weight(1f)
                    .verticalScroll(rememberScrollState())
                    .padding(12.dp)
            ) {
                // ── Выбор элемента ──
                Text("Элемент:", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Row(
                    Modifier.horizontalScroll(rememberScrollState()),
                    horizontalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    MAT_LABELS.forEach { (idx, label) ->
                        FilterChip(
                            selected = selMat == idx,
                            onClick  = { selMat = idx },
                            label    = { Text(label) }
                        )
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── Пресеты цветов ──
                val presets = when (selMat) { 1 -> EYES; 2 -> TEETH; else -> SKIN }
                Text("Пресеты:", style = MaterialTheme.typography.titleSmall)
                Spacer(Modifier.height(4.dp))
                Row(horizontalArrangement = Arrangement.spacedBy(10.dp)) {
                    presets.forEach { p ->
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.clickable {
                                colors[selMat] = floatArrayOf(p.rgb[0], p.rgb[1], p.rgb[2], 1f)
                                editor.setColor(selMat, p.rgb[0], p.rgb[1], p.rgb[2])
                                rebuild(ctx, editor) { f -> tempFile = f; glbVer++ }
                            }
                        ) {
                            Box(
                                Modifier
                                    .size(38.dp)
                                    .clip(CircleShape)
                                    .background(p.color)
                                    .border(2.dp, Color.White, CircleShape)
                            )
                            Text(p.label, fontSize = 10.sp)
                        }
                    }
                }

                Spacer(Modifier.height(12.dp))

                // ── RGB-слайдеры ──
                val c = colors[selMat] ?: floatArrayOf(1f, 1f, 1f, 1f)
                RgbSlider("R", c[0], Color.Red)   { v -> colors[selMat] = c.copyOf().also { it[0] = v } }
                RgbSlider("G", c[1], Color.Green)  { v -> colors[selMat] = c.copyOf().also { it[1] = v } }
                RgbSlider("B", c[2], Color.Blue)   { v -> colors[selMat] = c.copyOf().also { it[2] = v } }

                // Кнопка "Применить цвет"
                Button(
                    onClick = {
                        val cc = colors[selMat] ?: return@Button
                        editor.setColor(selMat, cc[0], cc[1], cc[2])
                        rebuild(ctx, editor) { f -> tempFile = f; glbVer++ }
                    },
                    modifier = Modifier.fillMaxWidth()
                ) { Text("Применить цвет") }

                Spacer(Modifier.height(8.dp))

                // ── Metallic / Roughness ──
                Text("Metallic: ${"%.2f".format(metallics[selMat] ?: 0f)}")
                Slider(
                    value = metallics[selMat] ?: 0f,
                    onValueChange = { metallics[selMat] = it },
                    valueRange = 0f..1f,
                    onValueChangeFinished = {
                        editor.setMetallicRoughness(selMat, metallics[selMat] ?: 0f, roughs[selMat] ?: 0.5f)
                        rebuild(ctx, editor) { f -> tempFile = f; glbVer++ }
                    }
                )
                Text("Roughness: ${"%.2f".format(roughs[selMat] ?: 0.5f)}")
                Slider(
                    value = roughs[selMat] ?: 0.5f,
                    onValueChange = { roughs[selMat] = it },
                    valueRange = 0f..1f,
                    onValueChangeFinished = {
                        editor.setMetallicRoughness(selMat, metallics[selMat] ?: 0f, roughs[selMat] ?: 0.5f)
                        rebuild(ctx, editor) { f -> tempFile = f; glbVer++ }
                    }
                )

                Spacer(Modifier.height(8.dp))

                // ── Текстура ──
                Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    OutlinedButton(
                        onClick = { imagePicker.launch("image/*") },
                        modifier = Modifier.weight(1f)
                    ) { Text("Загрузить текстуру") }
                    IconButton(onClick = {
                        editor.removeTexture(selMat)
                        rebuild(ctx, editor) { f -> tempFile = f; glbVer++ }
                    }) { Icon(Icons.Default.Delete, "Удалить") }
                }

                Spacer(Modifier.height(16.dp))

                // ── Сохранить ──
                Button(
                    onClick = {
                        val dir = File(ctx.getExternalFilesDir(null), "models").also { it.mkdirs() }
                        val out = File(dir, "edited_model.glb")
                        if (editor.save(out)) {
                            // Также обновляем source в assets cache для AvatarScene
                            val assetCache = File(ctx.filesDir, "source_named_edited.glb")
                            editor.save(assetCache)
                            Toast.makeText(ctx, "Сохранено:\n${out.absolutePath}", Toast.LENGTH_LONG).show()
                        } else {
                            Toast.makeText(ctx, "Ошибка сохранения", Toast.LENGTH_SHORT).show()
                        }
                    },
                    modifier = Modifier.fillMaxWidth(),
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFF4CAF50))
                ) { Text("Сохранить GLB", color = Color.White) }

                Spacer(Modifier.height(32.dp))
            }
        }
    }
}

// ═══════════════════════════════════════════════════════
//  3D-превью (полный пересоздаётся через key(version))
// ═══════════════════════════════════════════════════════
@Composable
private fun EditorPreview(glbFile: File) {
    val engine            = rememberEngine()
    val modelLoader       = rememberModelLoader(engine)
    val environmentLoader = rememberEnvironmentLoader(engine)
    val environment       = rememberEnvironment(environmentLoader)

    // Загрузка модели из файла через ByteBuffer
    val modelInstance = remember {
        try {
            val bytes = glbFile.readBytes()
            val buf = java.nio.ByteBuffer.allocateDirect(bytes.size)
            buf.put(bytes); buf.flip()
            modelLoader.createModel(buf).instance
        } catch (e: Exception) {
            e.printStackTrace(); null
        }
    }

    val cameraNode = rememberCameraNode(engine) { position = CAM_POS }

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
}

// ═══════════════════════════════════════════════════════
//  Хелперы
// ═══════════════════════════════════════════════════════
@Composable
private fun RgbSlider(label: String, value: Float, color: Color, onChange: (Float) -> Unit) {
    Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.fillMaxWidth()) {
        Text(label, Modifier.width(20.dp))
        Slider(
            value = value,
            onValueChange = onChange,
            valueRange = 0f..1f,
            modifier = Modifier.weight(1f),
            colors = SliderDefaults.colors(thumbColor = color, activeTrackColor = color)
        )
        Text("${(value * 255).toInt()}", Modifier.width(34.dp), fontSize = 11.sp)
    }
}

private fun rebuild(ctx: android.content.Context, editor: GlbEditor, onDone: (File) -> Unit) {
    val f = File(ctx.cacheDir, "editor_model.glb")
    if (editor.save(f)) onDone(f)
}