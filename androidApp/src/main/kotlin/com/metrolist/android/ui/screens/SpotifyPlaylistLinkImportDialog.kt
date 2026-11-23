package com.metrolist.android.ui.screens

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.metrolist.shared.db.MusicDatabase
import com.metrolist.shared.db.entities.PlaylistEntity
import com.metrolist.shared.generated.resources.Res
import com.metrolist.shared.generated.resources.cancel
import com.metrolist.shared.generated.resources.done
import com.metrolist.shared.generated.resources.import_paste_spotify_hint
import com.metrolist.shared.generated.resources.spotify_link_import
import com.metrolist.shared.generated.resources.spotify_link_import_error
import com.metrolist.shared.generated.resources.spotify_link_import_leave_placeholder
import com.metrolist.shared.generated.resources.spotify_link_import_loading
import com.metrolist.shared.generated.resources.spotify_link_import_match_prompt
import com.metrolist.shared.generated.resources.spotify_link_import_progress
import com.metrolist.shared.generated.resources.spotify_link_import_prompt
import com.metrolist.shared.generated.resources.spotify_link_import_search_results
import com.metrolist.shared.generated.resources.spotify_link_import_summary
import com.metrolist.shared.generated.resources.spotify_link_import_title
import com.metrolist.shared.generated.resources.spotify_link_import_unknown_track
import com.metrolist.shared.generated.resources.spotify_link_import_unmatched
import com.metrolist.shared.model.SearchFilter
import com.metrolist.shared.model.SongItem
import com.metrolist.shared.playlistimport.PlaylistImportManager
import com.metrolist.shared.playlistimport.SpotifyPlaylistTrack
import com.metrolist.shared.playlistimport.SpotifyUrlPlaylistImporter
import com.metrolist.shared.state.GlobalYouTubeRepository
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import org.jetbrains.compose.resources.stringResource
import org.koin.compose.koinInject

private sealed interface SpotifyLinkImportState {
    data object Input : SpotifyLinkImportState

    data object Loading : SpotifyLinkImportState

    data class Matching(
        val track: SpotifyPlaylistTrack,
        val candidates: List<SongItem>,
    ) : SpotifyLinkImportState

    data class Progress(
        val current: Int,
        val total: Int,
    ) : SpotifyLinkImportState

    data class Completed(
        val matched: Int,
        val total: Int,
        val playlist: PlaylistEntity,
    ) : SpotifyLinkImportState

    data object Failed : SpotifyLinkImportState
}

@Composable
fun SpotifyPlaylistLinkImportDialog(
    onDismiss: () -> Unit,
    onPlaylistCreated: (String, String?) -> Unit,
) {
    val database: MusicDatabase = koinInject()
    val repository = remember { GlobalYouTubeRepository.instance }
    val manager = remember(database, repository) { PlaylistImportManager(database, repository) }
    val scope = rememberCoroutineScope()
    val unmatchedLabel = stringResource(Res.string.spotify_link_import_unmatched)
    val unknownTrack = stringResource(Res.string.spotify_link_import_unknown_track)
    var url by remember { mutableStateOf("") }
    var state by remember { mutableStateOf<SpotifyLinkImportState>(SpotifyLinkImportState.Input) }
    var importJob by remember { mutableStateOf<Job?>(null) }
    var pendingChoice by remember { mutableStateOf<CompletableDeferred<SongItem?>?>(null) }

    fun dismiss() {
        importJob?.cancel()
        pendingChoice?.cancel()
        onDismiss()
    }

    fun chooseTrack(song: SongItem?) {
        pendingChoice?.complete(song)
    }

    fun startImport() {
        if (!SpotifyUrlPlaylistImporter.isPublicPlaylistUrl(url)) {
            state = SpotifyLinkImportState.Failed
            return
        }
        state = SpotifyLinkImportState.Loading
        importJob =
            scope.launch {
                try {
                    val data = SpotifyUrlPlaylistImporter.loadPlaylist(url).getOrThrow()
                    val tracks = requireNotNull(data.spotifyTracks) { "Spotify track details are missing" }
                    require(tracks.isNotEmpty()) { "Spotify playlist is empty" }
                    require(tracks.map(SpotifyPlaylistTrack::index) == tracks.indices.toList()) {
                        "Spotify playlist track order is incomplete"
                    }

                    val importedSongs = ArrayList<SongItem>(tracks.size)
                    var matchedCount = 0
                    tracks.forEachIndexed { index, track ->
                        state = SpotifyLinkImportState.Progress(index + 1, tracks.size)
                        val query =
                            listOf(track.title, track.artistLabel)
                                .filter(String::isNotBlank)
                                .joinToString(" - ")
                        val candidates =
                            if (track.title.isBlank()) {
                                emptyList()
                            } else {
                                repository
                                    .searchFiltered(query, SearchFilter.SONG, allowOfflineFallback = false)
                                    .items
                                    .filterIsInstance<SongItem>()
                            }
                        val matched = PlaylistImportManager.bestSpotifyTrackMatch(track, candidates)
                        val selected =
                            if (matched != null) {
                                matched
                            } else {
                                val decision = CompletableDeferred<SongItem?>()
                                pendingChoice = decision
                                state = SpotifyLinkImportState.Matching(track, candidates.take(5))
                                try {
                                    decision.await()
                                } finally {
                                    pendingChoice = null
                                }
                            }

                        if (selected == null) {
                            importedSongs += PlaylistImportManager.spotifyPlaceholder(track, unmatchedLabel)
                        } else {
                            importedSongs += selected
                            matchedCount++
                        }
                    }

                    val playlist =
                        manager
                            .importVerbose(
                                name = data.title,
                                songs = importedSongs,
                                destination = PlaylistImportManager.Destination.NewLocal,
                                coverUrl = data.coverUrl,
                            ).getOrThrow()
                            .playlist
                    state = SpotifyLinkImportState.Completed(matchedCount, tracks.size, playlist)
                } catch (error: CancellationException) {
                    throw error
                } catch (_: Exception) {
                    state = SpotifyLinkImportState.Failed
                }
            }
    }

    DisposableEffect(Unit) {
        onDispose {
            importJob?.cancel()
            pendingChoice?.cancel()
        }
    }

    val title =
        when (val current = state) {
            is SpotifyLinkImportState.Matching -> {
                val trackName =
                    listOf(current.track.title.ifBlank { unknownTrack }, current.track.artistLabel)
                        .filter(String::isNotBlank)
                        .joinToString(" - ")
                stringResource(Res.string.spotify_link_import_match_prompt, trackName)
            }

            else -> {
                stringResource(Res.string.spotify_link_import_title)
            }
        }

    AlertDialog(
        onDismissRequest = ::dismiss,
        title = { Text(title) },
        text = {
            when (val current = state) {
                SpotifyLinkImportState.Input -> {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        Text(stringResource(Res.string.spotify_link_import_prompt))
                        OutlinedTextField(
                            value = url,
                            onValueChange = { url = it },
                            modifier = Modifier.fillMaxWidth(),
                            placeholder = { Text(stringResource(Res.string.import_paste_spotify_hint)) },
                            singleLine = true,
                        )
                    }
                }

                SpotifyLinkImportState.Loading -> {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(Res.string.spotify_link_import_loading))
                    }
                }

                is SpotifyLinkImportState.Progress -> {
                    Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
                        CircularProgressIndicator()
                        Text(stringResource(Res.string.spotify_link_import_progress, current.current, current.total))
                    }
                }

                is SpotifyLinkImportState.Matching -> {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(stringResource(Res.string.spotify_link_import_search_results))
                        LazyColumn(
                            modifier = Modifier.heightIn(max = 320.dp),
                            verticalArrangement = Arrangement.spacedBy(4.dp),
                        ) {
                            itemsIndexed(current.candidates, key = { index, song -> "${song.id}_$index" }) { _, song ->
                                TextButton(
                                    onClick = { chooseTrack(song) },
                                    modifier = Modifier.fillMaxWidth(),
                                ) {
                                    Column(modifier = Modifier.fillMaxWidth()) {
                                        Text(song.title, maxLines = 1)
                                        Text(
                                            song.artists.joinToString { it.name },
                                            style = MaterialTheme.typography.bodySmall,
                                            maxLines = 1,
                                        )
                                    }
                                }
                            }
                        }
                    }
                }

                is SpotifyLinkImportState.Completed -> {
                    Text(stringResource(Res.string.spotify_link_import_summary, current.matched, current.total))
                }

                SpotifyLinkImportState.Failed -> {
                    Text(stringResource(Res.string.spotify_link_import_error), color = MaterialTheme.colorScheme.error)
                }
            }
        },
        confirmButton = {
            when (val current = state) {
                SpotifyLinkImportState.Input -> {
                    TextButton(onClick = ::startImport, enabled = url.isNotBlank()) {
                        Text(stringResource(Res.string.spotify_link_import))
                    }
                }

                is SpotifyLinkImportState.Matching -> {
                    TextButton(onClick = { chooseTrack(null) }) {
                        Text(stringResource(Res.string.spotify_link_import_leave_placeholder))
                    }
                }

                is SpotifyLinkImportState.Completed -> {
                    TextButton(
                        onClick = {
                            onPlaylistCreated(current.playlist.id, current.playlist.thumbnailUrl)
                            onDismiss()
                        },
                    ) {
                        Text(stringResource(Res.string.done))
                    }
                }

                else -> {
                    Unit
                }
            }
        },
        dismissButton = {
            if (state !is SpotifyLinkImportState.Completed) {
                TextButton(onClick = ::dismiss) { Text(stringResource(Res.string.cancel)) }
            }
        },
    )
}
