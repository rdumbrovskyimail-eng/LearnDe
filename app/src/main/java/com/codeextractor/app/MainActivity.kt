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
import androidx.lifecycle.lifecycleScope
import com.codeextractor.app.databinding.ActivityMainBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : AppCompatActivity() {
    
    private lateinit var binding: ActivityMainBinding
    private val selectedItems = mutableListOf<FileItem>()
    private val processor = CodeProcessor()
    
    private val pickFolderLauncher = registerForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        uri?.let { addFolder(it) }
    }
    
    private val pickFilesLauncher = registerForActivityResult(
        ActivityResultContracts.OpenMultipleDocuments()
    ) { uris ->
        if (uris.isNotEmpty()) {
            addFiles(uris)
        }
    }
    
    private val saveFileLauncher = registerForActivityResult(
        ActivityResultContracts.CreateDocument("text/plain")
    ) { uri ->
        uri?.let { processAndSave(it) }
    }
    
    private val storagePermissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        if (permissions.values.all { it }) {
            Toast.makeText(this, "Разрешения получены", Toast.LENGTH_SHORT).show()
        } else {
            Toast.makeText(this, "Нужны разрешения для работы", Toast.LENGTH_LONG).show()
        }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)
        
        checkPermissions()
        setupUI()
    }
    
    private fun checkPermissions() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.R) {
            if (!Environment.isExternalStorageManager()) {
                val intent = Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION).apply {
                    data = Uri.parse("package:$packageName")
                }
                startActivity(intent)
            }
        } else {
            val permissions = arrayOf(
                Manifest.permission.READ_EXTERNAL_STORAGE,
                Manifest.permission.WRITE_EXTERNAL_STORAGE
            )
            if (!permissions.all { ContextCompat.checkSelfPermission(this, it) == PackageManager.PERMISSION_GRANTED }) {
                storagePermissionLauncher.launch(permissions)
            }
        }
    }
    
    private fun setupUI() {
        binding.btnAddFolder.setOnClickListener {
            pickFolderLauncher.launch(null)
        }
        
        binding.btnAddFiles.setOnClickListener {
            pickFilesLauncher.launch(arrayOf("*/*"))
        }
        
        binding.btnProcess.setOnClickListener {
            if (selectedItems.isEmpty()) {
                Toast.makeText(this, "Добавьте файлы или папки", Toast.LENGTH_SHORT).show()
            } else {
                saveFileLauncher.launch("extracted_code.txt")
            }
        }
        
        binding.btnClear.setOnClickListener {
            selectedItems.clear()
            updateUI()
        }
        
        updateUI()
    }
    
    private fun addFolder(uri: Uri) {
        lifecycleScope.launch {
            val count = withContext(Dispatchers.IO) {
                processor.collectFilesFromFolder(this@MainActivity, uri, selectedItems)
            }
            Toast.makeText(this@MainActivity, "Добавлено файлов: $count", Toast.LENGTH_SHORT).show()
            updateUI()
        }
    }
    
    private fun addFiles(uris: List<Uri>) {
        lifecycleScope.launch {
            withContext(Dispatchers.IO) {
                uris.forEach { uri ->
                    processor.addFile(this@MainActivity, uri, selectedItems)
                }
            }
            Toast.makeText(this@MainActivity, "Добавлено файлов: ${uris.size}", Toast.LENGTH_SHORT).show()
            updateUI()
        }
    }
    
    private fun processAndSave(outputUri: Uri) {
        binding.btnProcess.isEnabled = false
        binding.progressBar.visibility = android.view.View.VISIBLE
        
        lifecycleScope.launch {
            try {
                val result = withContext(Dispatchers.IO) {
                    processor.processFiles(this@MainActivity, selectedItems, outputUri)
                }
                
                if (result) {
                    Toast.makeText(this@MainActivity, "Файл успешно создан!", Toast.LENGTH_LONG).show()
                } else {
                    Toast.makeText(this@MainActivity, "Ошибка при создании файла", Toast.LENGTH_LONG).show()
                }
            } catch (e: Exception) {
                Toast.makeText(this@MainActivity, "Ошибка: ${e.message}", Toast.LENGTH_LONG).show()
            } finally {
                binding.progressBar.visibility = android.view.View.GONE
                binding.btnProcess.isEnabled = true
            }
        }
    }
    
    private fun updateUI() {
        binding.tvCounter.text = "Выбрано файлов: ${selectedItems.size}"
        binding.btnProcess.isEnabled = selectedItems.isNotEmpty()
    }
}

data class FileItem(
    val uri: Uri,
    val name: String,
    val type: String
)
