package com.adex.app.util

import android.content.Context
import android.os.Environment
import android.webkit.MimeTypeMap
import java.io.BufferedInputStream
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.nio.charset.Charset
import java.util.Locale
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.math.max

enum class FileSortBy {
    NAME,
    SIZE,
    MODIFIED,
}

enum class FileTypeFilter {
    ALL,
    FILE,
    DIR,
}

data class FileListOptions(
    val path: String? = null,
    val page: Int = 1,
    val pageSize: Int = 50,
    val sortBy: FileSortBy = FileSortBy.NAME,
    val sortDir: String = "asc",
    val query: String? = null,
    val type: FileTypeFilter = FileTypeFilter.ALL,
)

data class FileEntry(
    val name: String,
    val path: String,
    val isDirectory: Boolean,
    val size: Long,
    val modifiedAt: Long,
    val mimeType: String,
    val isHidden: Boolean,
    val canRead: Boolean,
    val canWrite: Boolean,
)

data class FileListResult(
    val path: String,
    val page: Int,
    val pageSize: Int,
    val totalItems: Int,
    val totalPages: Int,
    val sortBy: String,
    val sortDir: String,
    val query: String?,
    val type: String,
    val items: List<FileEntry>,
    val roots: List<String>,
)

object FileUtils {
    const val MAX_PAGE_SIZE = 200

    fun listFiles(context: Context): List<Map<String, Any>> {
        val result = listDirectory(context, FileListOptions())
        return result.items.map {
            mapOf(
                "name" to it.name,
                "path" to it.path,
                "isDirectory" to it.isDirectory,
                "size" to it.size,
                "modifiedAt" to it.modifiedAt,
                "mimeType" to it.mimeType,
                "isHidden" to it.isHidden,
            )
        }
    }

    fun listDirectory(context: Context, options: FileListOptions): FileListResult {
        val dir = normalizePath(context, options.path)
            ?: throw IllegalArgumentException("Path is outside allowed storage roots")
        if (!dir.exists()) {
            throw IllegalArgumentException("Path does not exist: ${options.path ?: dir.absolutePath}")
        }
        if (!dir.isDirectory) {
            throw IllegalArgumentException("Path is not a directory: ${dir.absolutePath}")
        }

        val pageSize = options.pageSize.coerceIn(1, MAX_PAGE_SIZE)
        val page = max(1, options.page)
        val sortDir = options.sortDir.lowercase(Locale.US).let { if (it == "desc") "desc" else "asc" }
        val query = options.query?.trim()?.takeIf { it.isNotEmpty() }

        val children = (dir.listFiles()?.toList() ?: emptyList())
            .filter { child ->
                when (options.type) {
                    FileTypeFilter.ALL -> true
                    FileTypeFilter.FILE -> child.isFile
                    FileTypeFilter.DIR -> child.isDirectory
                }
            }
            .filter { child ->
                if (query == null) {
                    true
                } else {
                    child.name.contains(query, ignoreCase = true)
                }
            }
            .sortedWith(fileComparator(options.sortBy, sortDir))

        val totalItems = children.size
        val totalPages = if (totalItems == 0) 0 else ((totalItems + pageSize - 1) / pageSize)
        val safePage = if (totalPages == 0) 1 else page.coerceAtMost(totalPages)
        val start = (safePage - 1) * pageSize
        val end = (start + pageSize).coerceAtMost(totalItems)

        val entries = if (start >= totalItems) emptyList() else children.subList(start, end).map { toEntry(it) }

        return FileListResult(
            path = dir.absolutePath,
            page = safePage,
            pageSize = pageSize,
            totalItems = totalItems,
            totalPages = totalPages,
            sortBy = options.sortBy.name.lowercase(Locale.US),
            sortDir = sortDir,
            query = query,
            type = options.type.name.lowercase(Locale.US),
            items = entries,
            roots = allowedRoots(context).map { it.absolutePath }.sorted(),
        )
    }

    fun stat(context: Context, path: String): FileEntry {
        val file = normalizePath(context, path)
            ?: throw IllegalArgumentException("Path is outside allowed storage roots")
        if (!file.exists()) {
            throw IllegalArgumentException("Path does not exist: $path")
        }
        return toEntry(file)
    }

    fun mkdirs(context: Context, path: String): Pair<File, Boolean> {
        val directory = normalizePath(context, path, allowNonExisting = true)
            ?: throw IllegalArgumentException("Path is outside allowed storage roots")

        val alreadyExists = directory.exists()
        if (alreadyExists && !directory.isDirectory) {
            throw IllegalArgumentException("Target exists and is not a directory")
        }

        val created = if (alreadyExists) false else directory.mkdirs()
        if (!directory.exists()) {
            throw IllegalStateException("Failed to create directory")
        }

        return directory to created
    }

    fun rename(context: Context, path: String, newName: String): File {
        val source = normalizePath(context, path)
            ?: throw IllegalArgumentException("Path is outside allowed storage roots")
        if (!source.exists()) {
            throw IllegalArgumentException("Path does not exist: $path")
        }

        val cleanName = newName.trim()
        if (cleanName.isEmpty() || cleanName.contains("/") || cleanName.contains("\\")) {
            throw IllegalArgumentException("newName must be a plain file or folder name")
        }

        val target = File(source.parentFile, cleanName).canonicalFile
        ensureWithinRoots(context, target)

        if (target.exists()) {
            throw IllegalArgumentException("Target already exists: ${target.absolutePath}")
        }
        if (!source.renameTo(target)) {
            throw IllegalStateException("Rename failed")
        }

        return target
    }

    fun move(context: Context, sourcePath: String, targetDirPath: String): File {
        val source = normalizePath(context, sourcePath)
            ?: throw IllegalArgumentException("Source path is outside allowed storage roots")
        if (!source.exists()) {
            throw IllegalArgumentException("Source path does not exist: $sourcePath")
        }

        val targetDir = normalizePath(context, targetDirPath)
            ?: throw IllegalArgumentException("Target directory is outside allowed storage roots")
        if (!targetDir.exists() || !targetDir.isDirectory) {
            throw IllegalArgumentException("Target directory does not exist: $targetDirPath")
        }

        val target = File(targetDir, source.name).canonicalFile
        ensureWithinRoots(context, target)

        if (target.exists()) {
            throw IllegalArgumentException("Target already exists: ${target.absolutePath}")
        }
        if (!source.renameTo(target)) {
            throw IllegalStateException("Move failed")
        }

        return target
    }

    fun delete(context: Context, path: String, recursive: Boolean): Int {
        val target = normalizePath(context, path)
            ?: throw IllegalArgumentException("Path is outside allowed storage roots")
        if (!target.exists()) {
            return 0
        }

        if (target.isDirectory && !recursive && (target.listFiles()?.isNotEmpty() == true)) {
            throw IllegalArgumentException("Directory is not empty; use recursive delete")
        }

        return deleteInternal(target, recursive)
    }

    fun readTextPreview(context: Context, path: String, maxChars: Int): String {
        val target = normalizePath(context, path)
            ?: throw IllegalArgumentException("Path is outside allowed storage roots")
        if (!target.exists() || !target.isFile) {
            throw IllegalArgumentException("File does not exist: $path")
        }

        val safeChars = maxChars.coerceIn(64, 50_000)
        val bytes = ByteArray(safeChars * 4)
        val readBytes = FileInputStream(target).use { stream ->
            stream.read(bytes)
        }.coerceAtLeast(0)
        return bytes.copyOf(readBytes).toString(Charset.forName("UTF-8"))
    }

    fun saveBytesToDir(
        context: Context,
        targetDirPath: String,
        fileName: String,
        bytes: ByteArray,
        overwrite: Boolean = false,
    ): File {
        val targetDir = normalizePath(context, targetDirPath)
            ?: throw IllegalArgumentException("Target directory is outside allowed storage roots")

        if (!targetDir.exists() || !targetDir.isDirectory) {
            throw IllegalArgumentException("Target directory does not exist: $targetDirPath")
        }

        val cleanFileName = fileName.trim().ifEmpty { "upload_${System.currentTimeMillis()}.bin" }
        if (cleanFileName.contains("/") || cleanFileName.contains("\\")) {
            throw IllegalArgumentException("fileName must be a plain file name")
        }

        val target = File(targetDir, cleanFileName).canonicalFile
        ensureWithinRoots(context, target)

        if (target.exists() && !overwrite) {
            throw IllegalArgumentException("Target file already exists: ${target.absolutePath}")
        }

        FileOutputStream(target, false).use { it.write(bytes) }
        return target
    }

    fun resolvePath(path: String): File? {
        val file = runCatching { File(path).canonicalFile }.getOrNull() ?: return null
        return if (file.exists()) file else null
    }

    fun normalizePath(context: Context, path: String?, allowNonExisting: Boolean = false): File? {
        val roots = allowedRoots(context)
        val input = path?.trim().orEmpty()

        val resolved = if (input.isEmpty()) {
            defaultRoot(context)
        } else {
            val raw = File(input)
            val base = if (raw.isAbsolute) raw else File(defaultRoot(context), input)
            runCatching { base.canonicalFile }.getOrNull() ?: return null
        }

        if (!allowNonExisting && !resolved.exists()) {
            return null
        }

        return if (isWithinRoots(resolved, roots)) resolved else null
    }

    fun allowedRoots(context: Context): List<File> {
        val roots = linkedSetOf<File>()

        runCatching { Environment.getExternalStorageDirectory() }
            .getOrNull()
            ?.let { roots.add(it.canonicalFile) }

        roots.add(context.filesDir.canonicalFile)
        roots.add(context.cacheDir.canonicalFile)
        context.getExternalFilesDir(null)?.let { roots.add(it.canonicalFile) }
        context.externalCacheDir?.let { roots.add(it.canonicalFile) }
        context.getExternalFilesDirs(null).filterNotNull().forEach { roots.add(it.canonicalFile) }

        return roots.filter { it.exists() }
    }

    private fun defaultRoot(context: Context): File {
        val external = runCatching { Environment.getExternalStorageDirectory() }.getOrNull()
        if (external != null && external.exists()) {
            return external.canonicalFile
        }
        return context.filesDir.canonicalFile
    }

    private fun ensureWithinRoots(context: Context, file: File) {
        if (!isWithinRoots(file, allowedRoots(context))) {
            throw IllegalArgumentException("Target path is outside allowed storage roots")
        }
    }

    private fun isWithinRoots(file: File, roots: List<File>): Boolean {
        val path = file.absolutePath
        return roots.any { root ->
            val rootPath = root.absolutePath
            path == rootPath || path.startsWith("$rootPath${File.separator}")
        }
    }

    private fun deleteInternal(target: File, recursive: Boolean): Int {
        if (!target.exists()) {
            return 0
        }

        var deleted = 0
        if (target.isDirectory && recursive) {
            target.listFiles()?.forEach { child ->
                deleted += deleteInternal(child, true)
            }
        }

        if (target.delete()) {
            deleted += 1
        }
        return deleted
    }

    private fun fileComparator(sortBy: FileSortBy, sortDir: String): Comparator<File> {
        val base = Comparator<File> { left, right ->
            when {
                left.isDirectory && !right.isDirectory -> -1
                !left.isDirectory && right.isDirectory -> 1
                else -> compareBySort(left, right, sortBy)
            }
        }

        return if (sortDir == "desc") {
            Comparator { a, b ->
                when {
                    a.isDirectory && !b.isDirectory -> -1
                    !a.isDirectory && b.isDirectory -> 1
                    else -> -compareBySort(a, b, sortBy)
                }
            }
        } else {
            base
        }
    }

    private fun compareBySort(left: File, right: File, sortBy: FileSortBy): Int {
        return when (sortBy) {
            FileSortBy.NAME -> left.name.lowercase(Locale.US).compareTo(right.name.lowercase(Locale.US))
            FileSortBy.SIZE -> {
                val sizeCompare = left.length().compareTo(right.length())
                if (sizeCompare != 0) sizeCompare else left.name.lowercase(Locale.US).compareTo(right.name.lowercase(Locale.US))
            }
            FileSortBy.MODIFIED -> {
                val modifiedCompare = left.lastModified().compareTo(right.lastModified())
                if (modifiedCompare != 0) modifiedCompare else left.name.lowercase(Locale.US).compareTo(right.name.lowercase(Locale.US))
            }
        }
    }

    private fun toEntry(file: File): FileEntry {
        return FileEntry(
            name = file.name.ifEmpty { file.absolutePath },
            path = file.absolutePath,
            isDirectory = file.isDirectory,
            size = if (file.isFile) file.length() else 0L,
            modifiedAt = file.lastModified(),
            mimeType = if (file.isDirectory) "inode/directory" else guessMimeType(file),
            isHidden = file.isHidden,
            canRead = file.canRead(),
            canWrite = file.canWrite(),
        )
    }

    private fun guessMimeType(file: File): String {
        val ext = file.extension.lowercase(Locale.US)
        if (ext.isEmpty()) {
            return "application/octet-stream"
        }

        val mime = MimeTypeMap.getSingleton().getMimeTypeFromExtension(ext)
        return mime ?: "application/octet-stream"
    }

    fun zipFiles(files: List<File>, zipFile: File) {
        ZipOutputStream(BufferedOutputStream(FileOutputStream(zipFile))).use { out ->
            for (file in files) {
                if (!file.exists() || !file.isFile) continue
                FileInputStream(file).use { fi ->
                    BufferedInputStream(fi).use { origin ->
                        val entry = ZipEntry(file.name)
                        out.putNextEntry(entry)
                        origin.copyTo(out)
                        out.closeEntry()
                    }
                }
            }
        }
    }
}
