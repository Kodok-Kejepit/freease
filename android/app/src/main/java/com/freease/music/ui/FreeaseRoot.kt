package com.freease.music.ui

import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.tween
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.slideInVertically
import androidx.compose.animation.slideOutVertically
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBars
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Home
import androidx.compose.material.icons.outlined.LibraryMusic
import androidx.compose.material.icons.outlined.Search
import androidx.compose.material.icons.rounded.Home
import androidx.compose.material.icons.rounded.LibraryMusic
import androidx.compose.material.icons.rounded.Search
import androidx.compose.material3.Icon
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Snackbar
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.media3.common.util.UnstableApi
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import androidx.navigation.navArgument
import com.freease.music.AppContainer
import com.freease.music.ui.components.AddToPlaylistSheet
import com.freease.music.ui.components.CrashReportDialog
import com.freease.music.util.CrashReporter
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.freease.music.ui.components.SongMenuSheet
import com.freease.music.ui.components.rememberArtworkColor
import com.freease.music.ui.navigation.AppActions
import com.freease.music.ui.navigation.LocalActions
import com.freease.music.ui.navigation.LocalBottomInset
import com.freease.music.ui.navigation.Routes
import com.freease.music.ui.player.LyricsScreen
import com.freease.music.ui.player.MiniPlayer
import com.freease.music.ui.player.PlayerScreen
import com.freease.music.ui.player.QueueSheet
import com.freease.music.ui.screens.ArtistScreen
import com.freease.music.ui.screens.CategoryScreen
import com.freease.music.ui.screens.DownloadsScreen
import com.freease.music.ui.screens.EqualizerScreen
import com.freease.music.ui.screens.HistoryScreen
import com.freease.music.ui.screens.HomeScreen
import com.freease.music.ui.screens.LibraryScreen
import com.freease.music.ui.screens.LikedScreen
import com.freease.music.ui.screens.LocalPlaylistScreen
import com.freease.music.ui.screens.MonthlyScreen
import com.freease.music.ui.screens.OnlinePlaylistScreen
import com.freease.music.ui.screens.SearchScreen
import com.freease.music.ui.screens.SettingsScreen
import com.freease.music.ui.screens.StatsScreen
import com.freease.music.ui.theme.Freease
import com.freease.music.ui.theme.FreeaseTheme

private data class Tab(val route: String, val label: String, val icon: ImageVector, val selectedIcon: ImageVector)

private val tabs = listOf(
    Tab(Routes.HOME, "Beranda", Icons.Outlined.Home, Icons.Rounded.Home),
    Tab(Routes.SEARCH, "Cari", Icons.Outlined.Search, Icons.Rounded.Search),
    Tab(Routes.LIBRARY, "Koleksi", Icons.Outlined.LibraryMusic, Icons.Rounded.LibraryMusic),
)

@androidx.annotation.OptIn(UnstableApi::class)
@Composable
fun FreeaseRoot(container: AppContainer, onActionsReady: (AppActions) -> Unit) {
    val settings by container.settings.flow.collectAsState()
    FreeaseTheme(accentIndex = settings.accent, amoled = settings.amoled) {
        val nav = rememberNavController()
        val context = LocalContext.current
        val actions = remember(nav) { AppActions(container, nav, context) }
        LaunchedEffect(actions) { onActionsReady(actions) }
        val state by container.player.state.collectAsState()
        val hasSong = state.song != null
        val navInset = WindowInsets.navigationBars.asPaddingValues().calculateBottomPadding()
        val bottomInset = 64.dp + navInset + if (hasSong) 68.dp else 0.dp
        val snackbar = remember { SnackbarHostState() }
        LaunchedEffect(Unit) {
            container.messages.collect { snackbar.currentSnackbarData?.dismiss(); snackbar.showSnackbar(it) }
        }

        CompositionLocalProvider(
            LocalActions provides actions,
            LocalBottomInset provides bottomInset,
            LocalContentColor provides MaterialTheme.colorScheme.onBackground,
        ) {
            Box(Modifier.fillMaxSize().background(MaterialTheme.colorScheme.background)) {
                NavHost(nav, startDestination = Routes.HOME, modifier = Modifier.fillMaxSize()) {
                    composable(Routes.HOME) { HomeScreen() }
                    composable(Routes.SEARCH) { SearchScreen() }
                    composable(Routes.LIBRARY) { LibraryScreen() }
                    composable(Routes.LIKED) { LikedScreen() }
                    composable(Routes.DOWNLOADS) { DownloadsScreen() }
                    composable(Routes.HISTORY) { HistoryScreen() }
                    composable(Routes.STATS) { StatsScreen() }
                    composable(Routes.MONTHLY) { MonthlyScreen() }
                    composable(Routes.SETTINGS) { SettingsScreen() }
                    composable(Routes.EQUALIZER) { EqualizerScreen() }
                    composable(Routes.MIX, arguments = listOf(navArgument("index") { type = NavType.IntType })) {
                        com.freease.music.ui.screens.DailyMixScreen(it.arguments?.getInt("index") ?: 0)
                    }
                    composable(Routes.RECOGNIZE, arguments = listOf(navArgument("auto") { type = NavType.BoolType; defaultValue = false })) {
                        com.freease.music.ui.screens.RecognizeScreen(it.arguments?.getBoolean("auto") ?: false)
                    }
                    composable(Routes.WRAPPED, arguments = listOf(navArgument("year") { type = NavType.IntType; defaultValue = 0 })) {
                        val y = it.arguments?.getInt("year") ?: 0
                        com.freease.music.ui.screens.WrappedScreen(if (y > 0) y else com.freease.music.ui.screens.defaultWrappedYear())
                    }
                    composable(Routes.ALARMS) { com.freease.music.ui.screens.AlarmScreen() }
                    composable(Routes.CAR) { com.freease.music.ui.screens.CarModeScreen() }
                    composable(Routes.LOCAL_MUSIC) { com.freease.music.ui.screens.LocalMusicScreen() }
                    composable(Routes.IMPORT, arguments = listOf(navArgument("link") { type = NavType.StringType; defaultValue = "" })) {
                        com.freease.music.ui.screens.ImportScreen(it.arguments?.getString("link") ?: "")
                    }
                    composable(Routes.ARTIST, arguments = listOf(navArgument("url") { type = NavType.StringType })) {
                        ArtistScreen(it.arguments?.getString("url") ?: "")
                    }
                    composable(Routes.PLAYLIST, arguments = listOf(navArgument("url") { type = NavType.StringType })) {
                        OnlinePlaylistScreen(it.arguments?.getString("url") ?: "")
                    }
                    composable(Routes.LOCAL, arguments = listOf(navArgument("id") { type = NavType.LongType })) {
                        LocalPlaylistScreen(it.arguments?.getLong("id") ?: 0)
                    }
                    composable(
                        Routes.CATEGORY,
                        arguments = listOf(
                            navArgument("title") { type = NavType.StringType },
                            navArgument("query") { type = NavType.StringType },
                        ),
                    ) {
                        CategoryScreen(it.arguments?.getString("title") ?: "", it.arguments?.getString("query") ?: "")
                    }
                }

                val route = nav.currentBackStackEntryAsState().value?.destination?.route
                // Mode Mobil memakai seluruh layar (tanpa mini player & navigasi bawah).
                if (route != Routes.CAR && route != Routes.WRAPPED) Column(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) {
                    AnimatedVisibility(hasSong, enter = slideInVertically { it } + fadeIn(), exit = slideOutVertically { it } + fadeOut()) {
                        MiniPlayer(Modifier.padding(bottom = 4.dp))
                    }
                    BottomBar(route) { route ->
                        if (route == Routes.HOME) nav.popBackStack(Routes.HOME, inclusive = false)
                        else nav.navigate(route) {
                            popUpTo(Routes.HOME) { saveState = true }
                            launchSingleTop = true
                            restoreState = true
                        }
                    }
                }

                SnackbarHost(
                    snackbar,
                    Modifier.align(Alignment.BottomCenter).padding(bottom = bottomInset + 8.dp),
                ) { data ->
                    Snackbar(data, containerColor = Color(0xFFEDEDF3), contentColor = Color(0xFF111118))
                }

                AnimatedVisibility(
                    actions.playerExpanded && hasSong,
                    enter = slideInVertically(tween(320)) { it },
                    exit = slideOutVertically(tween(280)) { it },
                ) {
                    PlayerScreen(onCollapse = { actions.playerExpanded = false })
                }

                val song = state.song
                AnimatedVisibility(
                    actions.showLyrics && song != null,
                    enter = slideInVertically(tween(300)) { it } + fadeIn(),
                    exit = slideOutVertically(tween(260)) { it } + fadeOut(),
                ) {
                    if (song != null) {
                        val color by rememberArtworkColor(song.artworkSmall, Freease.accent.primary)
                        LyricsScreen(song, color, onClose = { actions.showLyrics = false })
                    }
                }

                var crashReport by remember { mutableStateOf<String?>(null) }
                LaunchedEffect(Unit) {
                    crashReport = withContext(Dispatchers.IO) { CrashReporter.pendingReport(context) }
                }
                crashReport?.let { report ->
                    CrashReportDialog(report, "Freease sempat berhenti tiba-tiba") {
                        CrashReporter.acknowledge(context)
                        crashReport = null
                    }
                }

                // Pengingat izin saat app dibuka (setelah laporan crash, kalau ada).
                LaunchedEffect(Unit) {
                    kotlinx.coroutines.delay(1_200)
                    if (crashReport == null && com.freease.music.ui.components.AppPermissions.shouldPrompt(context)) actions.permissions = false
                }
                actions.permissions?.let { all ->
                    com.freease.music.ui.components.PermissionSheet(showAll = all) { actions.permissions = null }
                }

                if (actions.showQueue) QueueSheet { actions.showQueue = false }
                actions.menu?.let { SongMenuSheet(it) { actions.menu = null } }
                actions.addToPlaylist?.let { AddToPlaylistSheet(it) { actions.addToPlaylist = null } }
            }
        }
    }
}

@Composable
private fun BottomBar(current: String?, onSelect: (String) -> Unit) {
    val bg = MaterialTheme.colorScheme.background
    Row(
        Modifier
            .fillMaxWidth()
            .background(Brush.verticalGradient(listOf(bg.copy(alpha = 0.0f), bg.copy(alpha = 0.92f), bg), startY = 0f, endY = 60f))
            .navigationBarsPadding()
            .height(64.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        tabs.forEach { tab ->
            val selected = current == tab.route
            Column(
                Modifier
                    .weight(1f)
                    .clickable(interactionSource = remember { MutableInteractionSource() }, indication = null) { onSelect(tab.route) }
                    .padding(vertical = 6.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Icon(
                    if (selected) tab.selectedIcon else tab.icon,
                    tab.label,
                    tint = if (selected) Color.White else Freease.colors.muted,
                    modifier = Modifier.size(26.dp),
                )
                Spacer(Modifier.height(3.dp))
                Text(
                    tab.label,
                    style = MaterialTheme.typography.labelSmall,
                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                    color = if (selected) Color.White else Freease.colors.muted,
                )
            }
        }
    }
}
