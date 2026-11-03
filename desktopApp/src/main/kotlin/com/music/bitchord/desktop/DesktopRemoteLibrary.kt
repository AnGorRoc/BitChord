package com.music.bitchord.desktop

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Dialog
import com.music.bitchord.data.model.Song
import com.music.bitchord.data.remote.RemoteArtwork
import com.music.bitchord.data.smb.SmbClient
import com.music.bitchord.data.smb.SmbConfig
import com.music.bitchord.data.webdav.WebDavClient
import com.music.bitchord.data.webdav.WebDavConfig
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.net.URLDecoder

object DesktopWebDav {
    fun isConfigured(): Boolean =
        WebDavConfig.isConfigured(DesktopPersistence().webdavUrl())

    suspend fun getSongs(): List<Song> = withContext(Dispatchers.IO) {
        val persistence = DesktopPersistence()
        val url = persistence.webdavUrl()
        if (!WebDavConfig.isConfigured(url)) return@withContext emptyList()
        val username = persistence.webdavUsername()
        val password = persistence.webdavPassword()
        val listing = WebDavClient.listLibrary(
            baseUrl = url,
            username = username,
            password = password,
        ).getOrNull() ?: return@withContext emptyList()
        val artByDir = listing.images.groupBy { dirKey(it.url) }
        listing.audio.map { entry ->
            toSong(entry, RemoteArtwork.pick(artByDir[dirKey(entry.url)].orEmpty().map { it.url }))
        }
    }

    suspend fun testConnection(url: String, user: String, pass: String): Result<Unit> =
        WebDavClient.testConnection(url, user, pass)

    private fun toSong(entry: WebDavClient.Entry, artworkUrl: String?): Song {
        val album = parentFolderName(entry.url)
        val defaultSong = WebDavConfig.songFor(entry.url, albumName = album)
        val baseName = entry.displayName.substringBeforeLast('.').trim()
        val (splitArtist, splitTitle) = splitArtistAndTitle(baseName)
        return defaultSong.copy(
            title = splitTitle ?: defaultSong.title,
            artist = splitArtist ?: defaultSong.artist,
            thumbnailUrl = artworkUrl ?: "embedded:${entry.url}",
        )
    }

    private fun dirKey(fileUrl: String): String = runCatching {
        val uri = java.net.URI(fileUrl)
        "${uri.host.orEmpty()}${uri.path.orEmpty().trimEnd('/').substringBeforeLast('/', "")}"
    }.getOrDefault(fileUrl.substringBeforeLast('/'))

    private fun splitArtistAndTitle(base: String): Pair<String?, String?> {
        return if (" - " in base) {
            val parts = base.split(" - ", limit = 2)
            parts[0].trim().takeIf { it.isNotBlank() } to parts[1].trim().takeIf { it.isNotBlank() }
        } else {
            null to base.trim().takeIf { it.isNotBlank() }
        }
    }

    private fun parentFolderName(fileUrl: String): String? = runCatching {
        val path = java.net.URI(fileUrl).path.orEmpty().trimEnd('/')
        val parent = path.substringBeforeLast('/', "").substringAfterLast('/').trim()
        URLDecoder.decode(parent, "UTF-8").takeIf { it.isNotBlank() }
    }.getOrNull()
}

object DesktopSmb {
    fun isConfigured(): Boolean {
        val p = DesktopPersistence()
        return SmbConfig.isConfigured(p.smbHost(), p.smbShare())
    }

    suspend fun getSongs(): List<Song> = withContext(Dispatchers.IO) {
        val p = DesktopPersistence()
        val host = p.smbHost()
        val share = p.smbShare()
        if (!SmbConfig.isConfigured(host, share)) return@withContext emptyList()
        val listing = SmbClient.listLibrary().getOrNull() ?: return@withContext emptyList()
        val artByDir = listing.images.groupBy { dirKey(it.path) }
        listing.audio.map { entry ->
            val album = entry.path.substringBeforeLast('/', "").substringAfterLast('/').trim().takeIf { it.isNotBlank() }
            val coverUrls = artByDir[dirKey(entry.path)].orEmpty().map { streamUrl(host, share, it.path) }
            val directStreamUrl = streamUrl(host, share, entry.path)
            SmbConfig.songFor(host, share, entry.path, albumName = album).copy(
                thumbnailUrl = RemoteArtwork.pick(coverUrls) ?: "embedded:$directStreamUrl",
            )
        }
    }

    suspend fun testConnection(host: String, share: String, basePath: String, user: String, pass: String): Result<Unit> =
        SmbClient.testConnection(host, share, basePath, user, pass)

    private fun streamUrl(host: String, share: String, path: String): String =
        "smb://${SmbConfig.normalizeHost(host)}/${share.trim().trim('/')}/${path.trim('/')}"

    private fun dirKey(path: String): String =
        path.trim('/').substringBeforeLast('/', "").lowercase(java.util.Locale.ROOT)
}

@Composable
fun DesktopWebDavDialog(
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val persistence = remember { DesktopPersistence() }
    val scope = rememberCoroutineScope()
    var url by remember { mutableStateOf(persistence.webdavUrl()) }
    var user by remember { mutableStateOf(persistence.webdavUsername()) }
    var pass by remember { mutableStateOf(persistence.webdavPassword()) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.widthIn(min = 400.dp, max = 500.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("WebDAV Server Setup", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = url,
                    onValueChange = { url = it },
                    label = { Text("Server URL (e.g. https://dav.box.com/dav)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("Password") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (statusText != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(statusText!!, fontSize = 12.sp, color = if (statusText!!.startsWith("Success")) Color.Green else Color(0xFFFF6B6B))
                }

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            testing = true
                            statusText = "Testing connection..."
                            scope.launch {
                                val result = DesktopWebDav.testConnection(url, user, pass)
                                testing = false
                                statusText = result.fold(
                                    onSuccess = { "Success! Connected to WebDAV server." },
                                    onFailure = { "Error: ${it.message ?: "Failed to connect"}" },
                                )
                            }
                        },
                        enabled = !testing && url.isNotBlank(),
                    ) {
                        Text("Test Connection")
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Button(
                            onClick = {
                                persistence.saveWebdavUrl(url)
                                persistence.saveWebdavUsername(user)
                                persistence.saveWebdavPassword(pass)
                                onSaved()
                                onDismiss()
                            },
                        ) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}

@Composable
fun DesktopSmbDialog(
    onDismiss: () -> Unit,
    onSaved: () -> Unit,
) {
    val persistence = remember { DesktopPersistence() }
    val scope = rememberCoroutineScope()
    var host by remember { mutableStateOf(persistence.smbHost()) }
    var share by remember { mutableStateOf(persistence.smbShare()) }
    var basePath by remember { mutableStateOf(persistence.smbBasePath()) }
    var user by remember { mutableStateOf(persistence.smbUsername()) }
    var pass by remember { mutableStateOf(persistence.smbPassword()) }
    var statusText by remember { mutableStateOf<String?>(null) }
    var testing by remember { mutableStateOf(false) }

    Dialog(onDismissRequest = onDismiss) {
        Surface(
            shape = RoundedCornerShape(16.dp),
            color = MaterialTheme.colorScheme.surface,
            modifier = Modifier.widthIn(min = 400.dp, max = 500.dp),
        ) {
            Column(modifier = Modifier.padding(24.dp)) {
                Text("SMB / Windows Share Setup", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = Color.White)
                Spacer(Modifier.height(16.dp))

                OutlinedTextField(
                    value = host,
                    onValueChange = { host = it },
                    label = { Text("Server / Host (e.g. 192.168.1.50 or nas.local)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = share,
                    onValueChange = { share = it },
                    label = { Text("Share Name (e.g. Music)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = basePath,
                    onValueChange = { basePath = it },
                    label = { Text("Subfolder (optional, e.g. FLAC)") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = user,
                    onValueChange = { user = it },
                    label = { Text("Username") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )
                Spacer(Modifier.height(8.dp))
                OutlinedTextField(
                    value = pass,
                    onValueChange = { pass = it },
                    label = { Text("Password") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth(),
                )

                if (statusText != null) {
                    Spacer(Modifier.height(8.dp))
                    Text(statusText!!, fontSize = 12.sp, color = if (statusText!!.startsWith("Success")) Color.Green else Color(0xFFFF6B6B))
                }

                Spacer(Modifier.height(20.dp))

                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    TextButton(
                        onClick = {
                            testing = true
                            statusText = "Testing connection..."
                            scope.launch {
                                val result = DesktopSmb.testConnection(host, share, basePath, user, pass)
                                testing = false
                                statusText = result.fold(
                                    onSuccess = { "Success! Connected to SMB share." },
                                    onFailure = { "Error: ${it.message ?: "Failed to connect"}" },
                                )
                            }
                        },
                        enabled = !testing && host.isNotBlank() && share.isNotBlank(),
                    ) {
                        Text("Test Connection")
                    }

                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        TextButton(onClick = onDismiss) { Text("Cancel") }
                        Button(
                            onClick = {
                                persistence.saveSmbHost(host)
                                persistence.saveSmbShare(share)
                                persistence.saveSmbBasePath(basePath)
                                persistence.saveSmbUsername(user)
                                persistence.saveSmbPassword(pass)
                                onSaved()
                                onDismiss()
                            },
                        ) {
                            Text("Save")
                        }
                    }
                }
            }
        }
    }
}
