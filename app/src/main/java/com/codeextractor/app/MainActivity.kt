package com.codeextractor.app

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.Settings
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.codeextractor.app.databinding.ActivityMainBinding
import java.io.File

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val PREFS_NAME = "CodeSaverPrefs"
    private val KEY_COUNTER = "file_counter"

    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) {}

    private val manageStorageLauncher = registerForActivityResult(
        ActivityResultContracts.StartActivityForResult()
    ) {}

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        checkPermissions()
        updateFileLabel()

        binding.btnSave.setOnClickListener { saveCode() }
        binding.btnClear.setOnClickListener {
            binding.etCode.setText("")
            Toast.makeText(this, "Поле очищено", Toast.LENGTH_SHORT).show()
        }
    }

    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                try {
                    val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                        data = Uri.parse("package:$packageName")
                    }
                    manageStorageLauncher.launch(intent)
                } catch (e: Exception) {
                    manageStorageLauncher.launch(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION))
                }
            }
        } else {
            val perms = arrayOf(Manifest.permission.READ_EXTERNAL_STORAGE, Manifest.permission.WRITE_EXTERNAL_STORAGE)
            if (!perms.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
                storagePermissionLauncher.launch(perms)
            }
        }
    }

    private fun saveCode() {
        val code = binding.etCode.text.toString()
        if (code.isBlank()) {
            Toast.makeText(this, "Поле пустое", Toast.LENGTH_SHORT).show()
            return
        }
        val counter = getCounter()
        val dir = File(Environment.getExternalStorageDirectory(), "TestO")
        if (!dir.exists()) dir.mkdirs()
        try {
            File(dir, "$counter.kt").writeText(code, Charsets.UTF_8)
            incrementCounter()
            updateFileLabel()
            Toast.makeText(this, "Сохранено: TestO/$counter.kt", Toast.LENGTH_LONG).show()
        } catch (e: Exception) {
            Toast.makeText(this, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
        }
    }

    private fun getCounter() = getSharedPreferences(PREFS_NAME, MODE_PRIVATE).getInt(KEY_COUNTER, 41)

    private fun incrementCounter() {
        val prefs = getSharedPreferences(PREFS_NAME, MODE_PRIVATE)
        prefs.edit().putInt(KEY_COUNTER, prefs.getInt(KEY_COUNTER, 41) + 1).apply()
    }

    private fun updateFileLabel() {
        binding.tvFileName.text = "Следующий файл: ${getCounter()}.kt"
    }
}
