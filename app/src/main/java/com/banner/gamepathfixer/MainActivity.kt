package com.banner.gamepathfixer

import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.database.sqlite.SQLiteDatabase
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.Environment
import android.provider.DocumentsContract
import android.provider.MediaStore
import android.provider.Settings
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.FolderOpen
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.darkColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.documentfile.provider.DocumentFile
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.json.JSONObject
import java.io.File
import java.io.InputStream

data class Variant(val pkg: String, val label: String, val authority: String, val version: String)

data class GameRow(
    val id: Long,
    val name: String,
    val path: String,
    val jsonPath: String,
    val iconPath: String,
    val exists: Boolean?
)

object Repo {
    private const val PROVIDER_SUFFIX = ".MTDataFilesProvider"

    fun findVariants(ctx: Context): List<Variant> {
        val pm = ctx.packageManager
        val out = mutableListOf<Variant>()
        @Suppress("DEPRECATION")
        val pkgs = pm.getInstalledPackages(PackageManager.GET_PROVIDERS)
        for (pi in pkgs) {
            val provs = pi.providers ?: continue
            for (p in provs) {
                val auth = p.authority ?: continue
                if (auth.endsWith(PROVIDER_SUFFIX)) {
                    val label = pi.applicationInfo
                        ?.let { pm.getApplicationLabel(it).toString() } ?: pi.packageName
                    out.add(Variant(pi.packageName, label, auth, pi.versionName ?: ""))
                    break
                }
            }
        }
        return out.sortedBy { it.label.lowercase() }
    }

    fun releaseTree(ctx: Context, v: Variant) {
        ctx.contentResolver.persistedUriPermissions
            .filter { it.uri.authority == v.authority }
            .forEach {
                try {
                    ctx.contentResolver.releasePersistableUriPermission(
                        it.uri,
                        Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
                    )
                } catch (_: Exception) {
                }
            }
    }

    fun persistedTree(ctx: Context, v: Variant): Uri? =
        ctx.contentResolver.persistedUriPermissions
            .firstOrNull { it.uri.authority == v.authority && it.isReadPermission && it.isWritePermission }
            ?.uri

    fun canCheckFiles(): Boolean =
        if (Build.VERSION.SDK_INT >= 30) Environment.isExternalStorageManager()
        else File("/storage/emulated/0").canRead()

    fun hasRoot(): Boolean = try {
        val p = ProcessBuilder("su", "-c", "id").redirectErrorStream(true).start()
        val out = p.inputStream.bufferedReader().readText()
        p.waitFor()
        out.contains("uid=0")
    } catch (e: Exception) {
        false
    }

    fun suForceStop(pkg: String): Boolean = try {
        val p = ProcessBuilder("su", "-c", "am force-stop $pkg").start()
        p.waitFor() == 0
    } catch (e: Exception) {
        false
    }

    private fun dbDir(ctx: Context, tree: Uri): DocumentFile? {
        val root = DocumentFile.fromTreeUri(ctx, tree) ?: return null
        if (root.name == "databases") return root
        root.findFile("databases")?.let { return it }
        // grant at the provider's top root: databases lives under data/
        return root.findFile("data")?.findFile("databases")
    }

    private fun workDir(ctx: Context): File = File(ctx.cacheDir, "dbwork")

    private fun copyOut(ctx: Context, dir: DocumentFile, name: String, dest: File): Boolean {
        val f = dir.findFile(name) ?: return false
        return try {
            ctx.contentResolver.openInputStream(f.uri)?.use { ins ->
                File(dest, name).outputStream().use { ins.copyTo(it) }
                true
            } ?: false
        } catch (e: Exception) {
            false
        }
    }

    private fun pullDb(ctx: Context, tree: Uri): Pair<DocumentFile, File>? {
        val dir = dbDir(ctx, tree) ?: return null
        val work = workDir(ctx)
        work.deleteRecursively()
        work.mkdirs()
        if (!copyOut(ctx, dir, "ux_db", work)) return null
        // WAL can hold newer rows than the main file; copy it so SQLite replays it
        copyOut(ctx, dir, "ux_db-wal", work)
        copyOut(ctx, dir, "ux_db-shm", work)
        return dir to File(work, "ux_db")
    }

    fun loadGames(ctx: Context, tree: Uri): Result<List<GameRow>> {
        val pulled = pullDb(ctx, tree)
            ?: return Result.failure(Exception(
                "Couldn't read ux_db. Re-grant access and pick the app's data root " +
                    "(the folder that contains 'databases')."))
        val (_, local) = pulled
        val canCheck = canCheckFiles()
        return try {
            val db = SQLiteDatabase.openDatabase(local.path, null, SQLiteDatabase.OPEN_READWRITE)
            val rows = mutableListOf<GameRow>()
            db.rawQuery("SELECT id, package_name, data FROM t_game_library", null).use { c ->
                while (c.moveToNext()) {
                    val id = c.getLong(0)
                    val path = c.getString(1) ?: ""
                    val data = c.getString(2) ?: ""
                    var name = ""
                    var jsonPath = ""
                    var iconPath = ""
                    try {
                        val j = JSONObject(data)
                        name = j.optString("name")
                        jsonPath = j.optString("filePath")
                        iconPath = j.optString("localGameIconPath")
                    } catch (_: Exception) {
                    }
                    if (name.isBlank()) name = path.substringAfterLast('/')
                    val exists =
                        if (canCheck && path.startsWith("/")) File(path).exists() else null
                    rows.add(GameRow(id, name, path, jsonPath, iconPath, exists))
                }
            }
            db.close()
            Result.success(rows)
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    private fun decodeScaled(maxDim: Int, open: () -> InputStream?): Bitmap? {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        try {
            open()?.use { BitmapFactory.decodeStream(it, null, bounds) } ?: return null
        } catch (e: Exception) {
            return null
        }
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) return null
        var sample = 1
        while (bounds.outWidth / (sample * 2) >= maxDim || bounds.outHeight / (sample * 2) >= maxDim)
            sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        return try {
            open()?.use { BitmapFactory.decodeStream(it, null, opts) }
        } catch (e: Exception) {
            null
        }
    }

    // map an absolute path inside the target app's dirs to the provider's document id
    private fun docIdForPath(pkg: String, absPath: String): String? {
        val map = listOf(
            "/storage/emulated/0/Android/data/$pkg/" to "$pkg/android_data/",
            "/storage/emulated/0/Android/obb/$pkg/" to "$pkg/android_obb/",
            "/data/data/$pkg/" to "$pkg/data/",
            "/data/user/0/$pkg/" to "$pkg/data/"
        )
        for ((prefix, docPrefix) in map)
            if (absPath.startsWith(prefix)) return docPrefix + absPath.removePrefix(prefix)
        return null
    }

    fun loadIcon(ctx: Context, tree: Uri, pkg: String, iconPath: String): Bitmap? {
        if (iconPath.isBlank()) return null
        val f = File(iconPath)
        if (f.canRead()) return decodeScaled(512) { f.inputStream() }
        // Android/data of another app isn't directly readable — go through the SAF grant
        val docId = docIdForPath(pkg, iconPath) ?: return null
        return try {
            val uri = DocumentsContract.buildDocumentUriUsingTree(tree, docId)
            decodeScaled(512) { ctx.contentResolver.openInputStream(uri) }
        } catch (e: Exception) {
            null
        }
    }

    // BitmapFactory chokes on some formats (HEIC/AVIF variants); ImageDecoder doesn't.
    // Software allocator because Bitmap.compress can't read hardware bitmaps.
    private fun decodeFileSmart(f: File, maxDim: Int): Bitmap? {
        if (Build.VERSION.SDK_INT >= 28) {
            try {
                val src = android.graphics.ImageDecoder.createSource(f)
                return android.graphics.ImageDecoder.decodeBitmap(src) { dec, info, _ ->
                    val s = maxOf(info.size.width, info.size.height) / maxDim
                    if (s > 1) dec.setTargetSampleSize(s)
                    dec.allocator = android.graphics.ImageDecoder.ALLOCATOR_SOFTWARE
                }
            } catch (e: Exception) {
            }
        }
        return decodeScaled(maxDim) { f.inputStream() }
    }

    // picker URI grants can go stale (and cloud-backed photos can fail on re-open),
    // so grab the bytes the moment the user picks
    fun cachePickedImage(ctx: Context, uri: Uri): Result<File> {
        return try {
            val f = File(ctx.cacheDir, "picked_art")
            ctx.contentResolver.openInputStream(uri)?.use { ins ->
                f.outputStream().use { ins.copyTo(it) }
            } ?: return Result.failure(Exception("Couldn't open the picked image"))
            val size = f.length()
            if (size == 0L)
                return Result.failure(Exception(
                    "The picker returned an empty file — if it's a cloud photo, " +
                        "download it to the device first"))
            if (decodeFileSmart(f, 64) == null) {
                val mime = ctx.contentResolver.getType(uri) ?: "unknown type"
                return Result.failure(Exception(
                    "Couldn't decode that image ($size bytes, $mime) — try a different one"))
            }
            Result.success(f)
        } catch (e: Exception) {
            Result.failure(Exception("Couldn't read the picked image (${e.message})"))
        }
    }

    fun decodeArtFile(f: File): Bitmap? = decodeFileSmart(f, 1024)

    // the target app reads art straight off disk, so the new PNG must live somewhere
    // both apps can reach by absolute path — a public Pictures subfolder
    private fun writeArtPng(ctx: Context, bmp: Bitmap, gameName: String): Result<String> {
        val safe = gameName.replace(Regex("[^A-Za-z0-9._-]"), "_").take(40).ifBlank { "game" }
        val fileName = "$safe-${System.currentTimeMillis()}.png"
        return try {
            if (Build.VERSION.SDK_INT >= 29) {
                val resolver = ctx.contentResolver
                val values = ContentValues().apply {
                    put(MediaStore.MediaColumns.DISPLAY_NAME, fileName)
                    put(MediaStore.MediaColumns.MIME_TYPE, "image/png")
                    put(MediaStore.MediaColumns.RELATIVE_PATH, "Pictures/GamePathFixer")
                }
                val uri = resolver.insert(
                    MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY),
                    values
                ) ?: return Result.failure(Exception("Couldn't create the art file"))
                resolver.openOutputStream(uri)?.use {
                    bmp.compress(Bitmap.CompressFormat.PNG, 100, it)
                } ?: return Result.failure(Exception("Couldn't write the art file"))
                val path = resolver.query(
                    uri, arrayOf(MediaStore.MediaColumns.DATA), null, null, null
                )?.use { if (it.moveToFirst()) it.getString(0) else null }
                    ?: return Result.failure(Exception("Couldn't resolve the art file path"))
                Result.success(path)
            } else {
                val dir = File(
                    Environment.getExternalStoragePublicDirectory(Environment.DIRECTORY_PICTURES),
                    "GamePathFixer"
                )
                dir.mkdirs()
                val f = File(dir, fileName)
                f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
                Result.success(f.absolutePath)
            }
        } catch (e: Exception) {
            Result.failure(e)
        }
    }

    fun saveChanges(
        ctx: Context,
        tree: Uri,
        game: GameRow,
        newPathRaw: String?,
        newNameRaw: String?,
        artFile: File?
    ): Result<String> {
        val newPath = newPathRaw?.trim()?.takeIf { it.isNotEmpty() && it != game.path }
        val newName = newNameRaw?.trim()?.takeIf { it.isNotEmpty() && it != game.name }
        if (newPath == null && newName == null && artFile == null)
            return Result.failure(Exception("Nothing changed"))

        if (newPath != null) {
            if (!newPath.startsWith("/"))
                return Result.failure(Exception("New path must be absolute (start with /)"))
            if (canCheckFiles() && !File(newPath).exists())
                return Result.failure(Exception("That file doesn't exist:\n$newPath"))
        }

        var artPath: String? = null
        if (artFile != null) {
            val bmp = decodeArtFile(artFile)
                ?: return Result.failure(Exception("Couldn't decode the picked image"))
            artPath = writeArtPng(ctx, bmp, newName ?: game.name)
                .getOrElse { return Result.failure(it) }
        }

        val pulled = pullDb(ctx, tree)
            ?: return Result.failure(Exception("Couldn't read ux_db — re-grant folder access"))
        val (dir, local) = pulled

        try {
            val db = SQLiteDatabase.openDatabase(local.path, null, SQLiteDatabase.OPEN_READWRITE)
            val idArg = arrayOf(game.id.toString())
            if (newPath != null)
                db.execSQL("UPDATE t_game_library SET package_name=? WHERE id=?",
                    arrayOf(newPath, game.id.toString()))
            db.rawQuery("SELECT data FROM t_game_library WHERE id=?", idArg).use { c ->
                if (c.moveToFirst()) {
                    var data = c.getString(0) ?: ""
                    if (newPath != null) {
                        if (game.path.isNotBlank()) data = data.replace(game.path, newPath)
                        if (game.jsonPath.isNotBlank()) data = data.replace(game.jsonPath, newPath)
                    }
                    if (newName != null || artPath != null) {
                        val j = JSONObject(data)
                        newName?.let { j.put("name", it) }
                        artPath?.let { j.put("localGameIconPath", it) }
                        data = j.toString()
                    }
                    db.execSQL("UPDATE t_game_library SET data=? WHERE id=?",
                        arrayOf(data, game.id.toString()))
                }
            }
            // merge everything into the single main file before writing back
            db.rawQuery("PRAGMA wal_checkpoint(TRUNCATE)", null).use { it.moveToFirst() }
            db.close()
        } catch (e: Exception) {
            return Result.failure(e)
        }

        val dbDoc = dir.findFile("ux_db")
            ?: return Result.failure(Exception("ux_db vanished during write-back"))
        try {
            ctx.contentResolver.openOutputStream(dbDoc.uri, "wt")?.use { out ->
                local.inputStream().use { it.copyTo(out) }
            } ?: return Result.failure(Exception("Couldn't open ux_db for writing"))
        } catch (e: Exception) {
            return Result.failure(e)
        }
        // a stale WAL would replay the old values right over our edit on next launch
        dir.findFile("ux_db-wal")?.delete()
        dir.findFile("ux_db-shm")?.delete()

        val ok = try {
            ctx.contentResolver.openInputStream(dbDoc.uri)?.use {
                val bytes = it.readBytes().toString(Charsets.ISO_8859_1)
                (newPath == null || bytes.contains(newPath)) &&
                    (artPath == null || bytes.contains(artPath))
            } ?: false
        } catch (e: Exception) {
            false
        }
        val what = listOfNotNull(
            newPath?.let { "path" }, newName?.let { "name" }, artPath?.let { "art" }
        ).joinToString(" + ")
        return if (ok) Result.success("Saved $what to the game library")
        else Result.failure(Exception("Wrote the DB but couldn't verify the change — check in the app"))
    }
}

fun pathFromDocUri(uri: Uri): String? {
    if (uri.authority != "com.android.externalstorage.documents") return null
    val id = try {
        DocumentsContract.getDocumentId(uri)
    } catch (e: Exception) {
        return null
    }
    val parts = id.split(":", limit = 2)
    if (parts.size != 2) return null
    return if (parts[0] == "primary") "/storage/emulated/0/${parts[1]}"
    else "/storage/${parts[0]}/${parts[1]}"
}

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            MaterialTheme(colorScheme = darkColorScheme()) {
                AppRoot()
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun AppRoot() {
    val ctx = androidx.compose.ui.platform.LocalContext.current
    val scope = rememberCoroutineScope()

    var variants by remember { mutableStateOf<List<Variant>>(emptyList()) }
    var selected by remember { mutableStateOf<Variant?>(null) }
    var treeUri by remember { mutableStateOf<Uri?>(null) }
    var games by remember { mutableStateOf<List<GameRow>>(emptyList()) }
    var editing by remember { mutableStateOf<GameRow?>(null) }
    var newPath by remember { mutableStateOf("") }
    var newName by remember { mutableStateOf("") }
    var artFile by remember { mutableStateOf<File?>(null) }
    var artPreview by remember { mutableStateOf<Bitmap?>(null) }
    var currentArt by remember { mutableStateOf<Bitmap?>(null) }
    var busy by remember { mutableStateOf(false) }
    var status by remember { mutableStateOf<String?>(null) }
    var hasRoot by remember { mutableStateOf(false) }

    fun toast(msg: String) = Toast.makeText(ctx, msg, Toast.LENGTH_LONG).show()

    fun reload() {
        val uri = treeUri ?: return
        scope.launch {
            busy = true
            status = null
            val res = withContext(Dispatchers.IO) { Repo.loadGames(ctx, uri) }
            busy = false
            res.fold(
                onSuccess = { games = it },
                onFailure = { status = it.message }
            )
        }
    }

    LaunchedEffect(Unit) {
        withContext(Dispatchers.IO) {
            val v = Repo.findVariants(ctx)
            val root = Repo.hasRoot()
            withContext(Dispatchers.Main) {
                variants = v
                hasRoot = root
            }
        }
    }

    val treeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri ->
        val v = selected
        if (uri == null || v == null) return@rememberLauncherForActivityResult
        if (uri.authority == v.authority) {
            ctx.contentResolver.takePersistableUriPermission(
                uri,
                Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
            )
            treeUri = uri
            reload()
        } else {
            status = "Wrong location. In the picker tap the ☰ menu, choose \"${v.label}\"" +
                " (its data provider), then tap USE THIS FOLDER at the top level."
        }
    }

    val exeLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocument()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        val p = pathFromDocUri(uri)
        if (p != null) newPath = p
        else toast("Couldn't turn that pick into a real path — type it manually")
    }

    val artLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri ->
        if (uri == null) return@rememberLauncherForActivityResult
        scope.launch {
            val res = withContext(Dispatchers.IO) { Repo.cachePickedImage(ctx, uri) }
            res.fold(
                onSuccess = { f ->
                    artPreview = withContext(Dispatchers.IO) { Repo.decodeArtFile(f) }
                    artFile = f
                },
                onFailure = { toast(it.message ?: "Couldn't read that image") }
            )
        }
    }

    LaunchedEffect(editing, treeUri) {
        val g = editing
        val t = treeUri
        val v = selected
        currentArt =
            if (g == null || t == null || v == null) null
            else withContext(Dispatchers.IO) { Repo.loadIcon(ctx, t, v.pkg, g.iconPath) }
    }

    val editingGame = editing
    val sel = selected

    when {
        editingGame != null && sel != null && treeUri != null -> {
            BackHandler { editing = null }
            Scaffold(topBar = {
                TopAppBar(
                    title = { Text(editingGame.name, maxLines = 1) },
                    navigationIcon = {
                        IconButton(onClick = { editing = null }) {
                            Icon(Icons.AutoMirrored.Filled.ArrowBack, "back")
                        }
                    }
                )
            }) { pad ->
                Column(
                    Modifier.padding(pad).padding(16.dp).fillMaxSize()
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text("Current path", style = MaterialTheme.typography.labelLarge)
                    Text(
                        editingGame.path,
                        fontFamily = FontFamily.Monospace,
                        fontSize = 12.sp,
                        color = if (editingGame.exists == false) Color(0xFFEF5350)
                        else MaterialTheme.colorScheme.onSurface
                    )
                    if (editingGame.exists == false)
                        Text("This file no longer exists.", color = Color(0xFFEF5350), fontSize = 12.sp)

                    Spacer(Modifier.height(4.dp))
                    Text("New path to the game .exe", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = newPath,
                        onValueChange = { newPath = it },
                        modifier = Modifier.fillMaxWidth(),
                        textStyle = MaterialTheme.typography.bodySmall.copy(fontFamily = FontFamily.Monospace),
                        minLines = 2
                    )
                    OutlinedButton(onClick = { exeLauncher.launch(arrayOf("*/*")) }) {
                        Text("Browse…")
                    }

                    Spacer(Modifier.height(4.dp))
                    Text("Display name", style = MaterialTheme.typography.labelLarge)
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        modifier = Modifier.fillMaxWidth(),
                        singleLine = true
                    )

                    Spacer(Modifier.height(4.dp))
                    Text("Game art", style = MaterialTheme.typography.labelLarge)
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(12.dp)
                    ) {
                        currentArt?.let {
                            Image(
                                it.asImageBitmap(), "current art",
                                Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                        artPreview?.let {
                            Text("→", fontSize = 18.sp)
                            Image(
                                it.asImageBitmap(), "new art",
                                Modifier.size(72.dp).clip(RoundedCornerShape(8.dp)),
                                contentScale = ContentScale.Crop
                            )
                        }
                        Column {
                            OutlinedButton(onClick = { artLauncher.launch("image/*") }) {
                                Text(if (artFile == null) "Choose image…" else "Change…")
                            }
                            if (artFile != null) TextButton(onClick = { artFile = null; artPreview = null }) {
                                Text("Keep current art")
                            }
                        }
                    }

                    val dirty = (newPath.isNotBlank() && newPath != editingGame.path) ||
                        (newName.isNotBlank() && newName.trim() != editingGame.name) ||
                        artFile != null
                    Button(
                        enabled = !busy && dirty,
                        onClick = {
                            scope.launch {
                                busy = true
                                status = null
                                if (hasRoot) withContext(Dispatchers.IO) { Repo.suForceStop(sel.pkg) }
                                val res = withContext(Dispatchers.IO) {
                                    Repo.saveChanges(
                                        ctx, treeUri!!, editingGame,
                                        newPath, newName, artFile
                                    )
                                }
                                busy = false
                                res.fold(
                                    onSuccess = {
                                        toast(it)
                                        editing = null
                                        artFile = null; artPreview = null
                                        reload()
                                    },
                                    onFailure = {
                                        status = it.message
                                        toast(it.message ?: "Save failed")
                                    }
                                )
                            }
                        }
                    ) { Text(if (busy) "Saving…" else "Save changes") }

                    Spacer(Modifier.height(8.dp))
                    if (hasRoot) {
                        Text(
                            "Root detected — ${sel.label} will be force-stopped automatically before saving.",
                            fontSize = 12.sp, color = Color(0xFF81C784)
                        )
                    } else {
                        Text(
                            "Important: force-stop ${sel.label} BEFORE saving, or it may write the old path back.",
                            fontSize = 12.sp, color = Color(0xFFFFB74D)
                        )
                        TextButton(onClick = {
                            ctx.startActivity(
                                Intent(
                                    Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
                                    Uri.parse("package:${sel.pkg}")
                                )
                            )
                        }) { Text("Open App info → Force stop") }
                    }
                    status?.let { Text(it, color = Color(0xFFEF5350), fontSize = 13.sp) }
                }
            }
        }

        sel != null && treeUri != null -> {
            val reselectFolder = {
                Repo.releaseTree(ctx, sel)
                treeUri = null
                games = emptyList()
                status = null
                toast("Pick again: tap ☰, choose \"${sel.label}\", then USE THIS FOLDER")
                treeLauncher.launch(null)
            }
            BackHandler { selected = null; treeUri = null; games = emptyList(); status = null }
            Scaffold(topBar = {
                TopAppBar(
                    title = { Text("${sel.label} — games") },
                    navigationIcon = {
                        IconButton(onClick = {
                            selected = null; treeUri = null; games = emptyList(); status = null
                        }) { Icon(Icons.AutoMirrored.Filled.ArrowBack, "back") }
                    },
                    actions = {
                        IconButton(onClick = reselectFolder) {
                            Icon(Icons.Default.FolderOpen, "re-select data folder")
                        }
                        IconButton(onClick = { reload() }) { Icon(Icons.Default.Refresh, "refresh") }
                    }
                )
            }) { pad ->
                Column(Modifier.padding(pad).fillMaxSize()) {
                    if (!Repo.canCheckFiles() && Build.VERSION.SDK_INT >= 30) {
                        TextButton(onClick = {
                            ctx.startActivity(
                                Intent(
                                    Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                                    Uri.parse("package:${ctx.packageName}")
                                )
                            )
                        }) { Text("Grant All-files access to show missing-file badges") }
                    }
                    if (busy) Row(
                        Modifier.fillMaxWidth().padding(24.dp),
                        horizontalArrangement = Arrangement.Center
                    ) { CircularProgressIndicator() }
                    status?.let {
                        Text(it, color = Color(0xFFEF5350), modifier = Modifier.padding(16.dp))
                        TextButton(
                            onClick = reselectFolder,
                            modifier = Modifier.padding(horizontal = 8.dp)
                        ) { Text("Re-select data folder") }
                    }
                    if (!busy && games.isEmpty() && status == null)
                        Text("No games in this app's library.", Modifier.padding(16.dp))
                    LazyColumn(
                        Modifier.fillMaxSize(),
                        verticalArrangement = Arrangement.spacedBy(8.dp),
                        contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp)
                    ) {
                        items(games, key = { it.id }) { g ->
                            Card(onClick = {
                                editing = g; newPath = g.path; newName = g.name
                                artFile = null; artPreview = null; status = null
                            }) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Row(verticalAlignment = Alignment.CenterVertically) {
                                        Text(
                                            g.name,
                                            style = MaterialTheme.typography.titleMedium,
                                            modifier = Modifier.weight(1f)
                                        )
                                        when (g.exists) {
                                            true -> Text("✓ found", color = Color(0xFF81C784), fontSize = 12.sp)
                                            false -> Text("✗ missing", color = Color(0xFFEF5350), fontSize = 12.sp)
                                            null -> {}
                                        }
                                    }
                                    Spacer(Modifier.height(4.dp))
                                    Text(
                                        g.path,
                                        fontFamily = FontFamily.Monospace,
                                        fontSize = 11.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                }
            }
        }

        else -> {
            Scaffold(topBar = { TopAppBar(title = { Text("Game Path Fixer") }) }) { pad ->
                Column(
                    Modifier.padding(pad).padding(16.dp).fillMaxSize(),
                    verticalArrangement = Arrangement.spacedBy(12.dp)
                ) {
                    Text(
                        "Pick your GameHub build. Apps appear here when they expose " +
                            "their data folder (MT data files provider).",
                        style = MaterialTheme.typography.bodyMedium
                    )
                    if (variants.isEmpty())
                        Text(
                            "No compatible apps found.",
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    LazyColumn(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        items(variants, key = { it.pkg }) { v ->
                            Card(onClick = {
                                selected = v
                                status = null
                                val existing = Repo.persistedTree(ctx, v)
                                if (existing != null) {
                                    treeUri = existing
                                    reload()
                                } else {
                                    toast(
                                        "In the picker: tap ☰, choose \"${v.label}\", " +
                                            "then USE THIS FOLDER"
                                    )
                                    treeLauncher.launch(null)
                                }
                            }) {
                                Column(Modifier.fillMaxWidth().padding(12.dp)) {
                                    Text(v.label, style = MaterialTheme.typography.titleMedium)
                                    Text(
                                        "${v.pkg}  ·  v${v.version}",
                                        fontSize = 12.sp,
                                        color = MaterialTheme.colorScheme.onSurfaceVariant
                                    )
                                }
                            }
                        }
                    }
                    status?.let { Text(it, color = Color(0xFFFFB74D), fontSize = 13.sp) }
                }
            }
        }
    }
}
