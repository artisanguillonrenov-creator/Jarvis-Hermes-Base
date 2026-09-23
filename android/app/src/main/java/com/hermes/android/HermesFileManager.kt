package com.hermes.android

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

/**
 * Small, sandboxed file boundary for Android-specific persistence.
 *
 * Hermès itself keeps using filesDir/.hermes. This manager owns only the
 * Android integration workspace (imports/exports/backups/logs) and guarantees
 * that callers cannot escape that root with ../ paths.
 */
class HermesFileManager(private val context: Context) {
    private val root = context.filesDir.resolve("jarvis_workspace")

    val importsDir: File get() = root.resolve("imports")
    val exportsDir: File get() = root.resolve("exports")
    val backupsDir: File get() = root.resolve("backups")
    val logsDir: File get() = root.resolve("logs")
    val hermesHome: File get() = context.filesDir.resolve(".hermes")

    fun ensureLayout() {
        listOf(root, importsDir, exportsDir, backupsDir, logsDir, hermesHome).forEach { it.mkdirs() }
    }

    fun importUri(uri: Uri, preferredName: String? = null): File {
        ensureLayout()
        val safeName = sanitizeFileName(preferredName ?: "import-${System.currentTimeMillis()}")
        val target = uniqueFile(importsDir, safeName)
        val temp = File(target.parentFile, ".${target.name}.part")
        context.contentResolver.openInputStream(uri).use { input ->
            requireNotNull(input) { "Impossible d'ouvrir le fichier sélectionné." }
            FileOutputStream(temp).use { output ->
                input.copyTo(output)
                output.fd.sync()
            }
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        return target
    }

    fun writeAtomic(relativePath: String, bytes: ByteArray): File {
        ensureLayout()
        val target = resolveInsideRoot(relativePath)
        target.parentFile?.mkdirs()
        val temp = File(target.parentFile, ".${target.name}.part")
        FileOutputStream(temp).use { output ->
            output.write(bytes)
            output.fd.sync()
        }
        if (!temp.renameTo(target)) {
            temp.copyTo(target, overwrite = true)
            temp.delete()
        }
        return target
    }

    fun listWorkspaceFiles(): List<File> {
        ensureLayout()
        return root.walkTopDown().filter { it.isFile }.toList()
    }

    private fun resolveInsideRoot(relativePath: String): File {
        require(!File(relativePath).isAbsolute) { "Un chemin absolu est interdit." }
        val canonicalRoot = root.canonicalFile
        val target = File(canonicalRoot, relativePath).canonicalFile
        val prefix = canonicalRoot.path + File.separator
        require(target.path == canonicalRoot.path || target.path.startsWith(prefix)) {
            "Le chemin sort du répertoire de travail Hermès."
        }
        return target
    }

    private fun sanitizeFileName(name: String): String {
        val cleaned = name
            .substringAfterLast('/')
            .substringAfterLast('\\')
            .replace(Regex("[^A-Za-z0-9._ -]"), "_")
            .trim()
            .take(120)
        return cleaned.ifBlank { "import-${System.currentTimeMillis()}" }
    }

    private fun uniqueFile(directory: File, name: String): File {
        var candidate = directory.resolve(name)
        if (!candidate.exists()) return candidate
        val dot = name.lastIndexOf('.')
        val stem = if (dot > 0) name.substring(0, dot) else name
        val extension = if (dot > 0) name.substring(dot) else ""
        var index = 2
        while (candidate.exists()) {
            candidate = directory.resolve("$stem-$index$extension")
            index += 1
        }
        return candidate
    }
}
