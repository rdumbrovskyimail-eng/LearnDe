package com.codeextractor.app

import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import java.io.BufferedReader
import java.io.InputStreamReader

class CodeProcessor {
    
    private val supportedExtensions = setOf(
        // Kotlin/Java
        "kt", "kts", "java",
        // XML
        "xml",
        // Gradle
        "gradle", "properties",
        // Android
        "aidl", "rs",
        // Web
        "html", "css", "js", "json",
        // Config
        "yml", "yaml", "toml", "ini", "conf", "config",
        // Text
        "txt", "md",
        // C/C++
        "c", "cpp", "h", "hpp",
        // Python
        "py",
        // Other
        "sh", "bat", "cmake", "pro"
    )
    
    fun collectFilesFromFolder(context: Context, folderUri: Uri, selectedItems: MutableList<FileItem>): Int {
        return collectFilesRecursive(context, folderUri, selectedItems, "")
    }
    
    private fun collectFilesRecursive(
        context: Context, 
        folderUri: Uri, 
        selectedItems: MutableList<FileItem>,
        currentPath: String
    ): Int {
        var count = 0
        try {
            val treeDocumentId = DocumentsContract.getTreeDocumentId(folderUri)
            val documentId = DocumentsContract.getDocumentId(folderUri)
            
            val childrenUri = DocumentsContract.buildChildDocumentsUriUsingTree(
                folderUri,
                documentId
            )
            
            context.contentResolver.query(
                childrenUri,
                arrayOf(
                    DocumentsContract.Document.COLUMN_DOCUMENT_ID,
                    DocumentsContract.Document.COLUMN_DISPLAY_NAME,
                    DocumentsContract.Document.COLUMN_MIME_TYPE
                ),
                null,
                null,
                null
            )?.use { cursor ->
                while (cursor.moveToNext()) {
                    val childDocumentId = cursor.getString(0)
                    val displayName = cursor.getString(1)
                    val mimeType = cursor.getString(2)
                    
                    val childUri = DocumentsContract.buildDocumentUriUsingTree(folderUri, childDocumentId)
                    val fullPath = if (currentPath.isEmpty()) displayName else "$currentPath/$displayName"
                    
                    if (mimeType == DocumentsContract.Document.MIME_TYPE_DIR) {
                        // Рекурсивно обходим подпапку
                        count += collectFilesRecursive(context, childUri, selectedItems, fullPath)
                    } else if (isSupported(displayName)) {
                        selectedItems.add(FileItem(childUri, fullPath, mimeType ?: "unknown"))
                        count++
                    }
                }
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
        return count
    }
    
    fun addFile(context: Context, uri: Uri, selectedItems: MutableList<FileItem>) {
        try {
            val fileName = getFileName(context, uri) ?: "unknown"
            if (isSupported(fileName)) {
                selectedItems.add(FileItem(uri, fileName, "file"))
            }
        } catch (e: Exception) {
            e.printStackTrace()
        }
    }
    
    fun processFiles(context: Context, items: List<FileItem>, outputUri: Uri): Boolean {
        return try {
            context.contentResolver.openOutputStream(outputUri)?.use { output ->
                val writer = output.bufferedWriter()
                
                items.forEachIndexed { index, item ->
                    writer.write("=".repeat(80))
                    writer.newLine()
                    writer.write("ФАЙЛ: ${item.name}")
                    writer.newLine()
                    writer.write("=".repeat(80))
                    writer.newLine()
                    writer.newLine()
                    
                    try {
                        val content = readFileContent(context, item.uri)
                        writer.write(content)
                    } catch (e: Exception) {
                        writer.write("// ОШИБКА ЧТЕНИЯ ФАЙЛА: ${e.message}")
                    }
                    
                    writer.newLine()
                    writer.newLine()
                    writer.newLine()
                }
                
                writer.flush()
            }
            true
        } catch (e: Exception) {
            e.printStackTrace()
            false
        }
    }
    
    private fun readFileContent(context: Context, uri: Uri): String {
        return context.contentResolver.openInputStream(uri)?.use { input ->
            BufferedReader(InputStreamReader(input, Charsets.UTF_8)).use { reader ->
                reader.readText()
            }
        } ?: ""
    }
    
    private fun getFileName(context: Context, uri: Uri): String? {
        return context.contentResolver.query(uri, null, null, null, null)?.use { cursor ->
            if (cursor.moveToFirst()) {
                val nameIndex = cursor.getColumnIndex(DocumentsContract.Document.COLUMN_DISPLAY_NAME)
                if (nameIndex >= 0) cursor.getString(nameIndex) else null
            } else null
        }
    }
    
    private fun isSupported(fileName: String): Boolean {
        val extension = fileName.substringAfterLast('.', "").lowercase()
        return extension in supportedExtensions && !fileName.endsWith(".jar")
    }
}