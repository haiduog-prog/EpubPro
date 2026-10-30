package com.epubpro.app.navigation

import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.NavigationBarItemDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.epubpro.core.storage.OnlineComicProgressStore
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import android.net.Uri
import androidx.navigation.NavDestination.Companion.hierarchy
import androidx.navigation.NavGraph.Companion.findStartDestination
import androidx.navigation.NavHostController
import androidx.navigation.NavType
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.navArgument
import androidx.compose.ui.res.stringResource
import com.epubpro.core.designsystem.R
import com.epubpro.core.reader.tts.TtsOpenBookContract
import com.epubpro.core.reader.tts.TtsOpenBookRequest
import com.epubpro.domain.model.BookSourceFormat
import com.epubpro.feature.bookmark.BookmarkScreen
import com.epubpro.feature.browser.BrowserScreen
import com.epubpro.feature.browser.BrowserViewModel
import com.epubpro.feature.comic.ComicReaderScreen
import com.epubpro.feature.comic.ComicReaderViewModel
import com.epubpro.feature.comic.OnlineComicReaderScreen
import com.epubpro.feature.comic.OnlineComicReaderViewModel
import com.epubpro.feature.library.LibraryScreen
import com.epubpro.feature.profile.ProfileScreen
import com.epubpro.feature.profile.sync.SyncScreen
import com.epubpro.feature.profile.ReadingDefaultsScreen
import com.epubpro.feature.profile.PageTurnControlScreen
import com.epubpro.feature.profile.auth.LoginScreen
import com.epubpro.feature.reader.ReaderScreen
import com.epubpro.feature.search.SearchScreen
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.collect
import kotlinx.coroutines.flow.emptyFlow

@Composable
fun AppNavHost(
    navController: NavHostController,
    openBookRequests: Flow<TtsOpenBookRequest> = emptyFlow(),
    openLibraryRequests: Flow<Unit> = emptyFlow(),
    openComicRequests: Flow<String> = emptyFlow(),
    openBrowserRequests: Flow<String> = emptyFlow()
) {
    val navBackStackEntry by navController.currentBackStackEntryAsState()
    val currentDestination = navBackStackEntry?.destination

    LaunchedEffect(navController, openBookRequests) {
        openBookRequests.collect { request ->
            if (navController.currentDestination?.route == Screen.Reader.route) {
                navController.popBackStack()
            }
            navController.navigate(
                Screen.Reader.createRoute(
                    bookId = request.bookId,
                    chapterIndex = request.chapterIndex,
                    openTtsPlayer = request.openTtsPlayer
                )
            ) {
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(navController, openComicRequests) {
        openComicRequests.collect { bookId ->
            if (navController.currentDestination?.route == Screen.ComicReader.route) {
                navController.popBackStack()
            }
            navController.navigate(Screen.ComicReader.createRoute(bookId)) {
                launchSingleTop = true
            }
        }
    }

    LaunchedEffect(navController, openBrowserRequests) {
        openBrowserRequests.collect { url ->
            navController.navigate(Screen.Browser.createRoute(url))
        }
    }

    LaunchedEffect(navController, openLibraryRequests) {
        openLibraryRequests.collect {
            navController.navigate(Screen.Bookshelf.route) {
                popUpTo(navController.graph.findStartDestination().id) {
                    saveState = false
                }
                launchSingleTop = true
            }
        }
    }

    val topLevelDestinations = TopLevelDestination.values().toList()
    val isTopLevelDestination = topLevelDestinations.any { it.route == currentDestination?.route }

    Scaffold(
        bottomBar = {
            if (isTopLevelDestination) {
                NavigationBar(
                    containerColor = MaterialTheme.colorScheme.surface,
                    tonalElevation = 4.dp
                ) {
                    topLevelDestinations.forEach { destination ->
                        val selected =
                            currentDestination?.hierarchy?.any { it.route == destination.route } == true
                        NavigationBarItem(
                            selected = selected,
                            onClick = {
                                navController.navigate(destination.route) {
                                    popUpTo(navController.graph.findStartDestination().id) {
                                        saveState = true
                                    }
                                    launchSingleTop = true
                                    restoreState = true
                                }
                            },
                            icon = {
                                Icon(
                                    imageVector = if (selected) destination.selectedIcon else destination.unselectedIcon,
                                    contentDescription = stringResource(destination.iconTextId)
                                )
                            },
                            label = {
                                Text(
                                    text = stringResource(destination.titleTextId),
                                    fontWeight = if (selected) FontWeight.Bold else FontWeight.Medium,
                                    fontSize = 12.sp
                                )
                            },
                            colors = NavigationBarItemDefaults.colors(
                                selectedIconColor = MaterialTheme.colorScheme.primary,
                                selectedTextColor = MaterialTheme.colorScheme.primary,
                                indicatorColor = MaterialTheme.colorScheme.primaryContainer,
                                unselectedIconColor = MaterialTheme.colorScheme.onSurfaceVariant,
                                unselectedTextColor = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        )
                    }
                }
            }
        }
    ) { innerPadding ->
        NavHost(
            navController = navController,
            startDestination = Screen.Bookshelf.route,
            modifier = Modifier.padding(innerPadding)
        ) {
            composable(Screen.Browse.route) {
                com.epubpro.feature.library.online.OnlineLibraryScreen(
                    onNovelClick = { novelId ->
                        navController.navigate(Screen.OnlineNovelDetail.createRoute(novelId))
                    },
                    onNavigateToServerSettings = { navController.navigate(Screen.Profile.route) }
                )
            }

            composable(
                route = Screen.OnlineNovelDetail.route,
                arguments = listOf(navArgument("novelId") { type = NavType.StringType })
            ) {
                com.epubpro.feature.library.online.OnlineNovelDetailScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onReadChapter = { novelId, chapterIndex ->
                        navController.navigate(Screen.OnlineChapterReader.createRoute(novelId, chapterIndex))
                    }
                )
            }

            composable(
                route = Screen.OnlineChapterReader.route,
                arguments = listOf(
                    navArgument("novelId") { type = NavType.StringType },
                    navArgument("chapterIndex") { type = NavType.StringType }
                )
            ) {
                com.epubpro.feature.library.online.OnlineChapterReaderScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onOpenBookBible = { novelId, chapterNumber ->
                        navController.navigate(Screen.BookBible.createRoute("ONLINE_NOVEL", novelId, chapterNumber))
                    }
                )
            }

            composable(Screen.Bookshelf.route) {
                val context = LocalContext.current
                LibraryScreen(
                    onBookClick = { bookId, sourceFormat ->
                        val route = if (bookId.startsWith(OnlineComicProgressStore.COMIC_BOOK_ID_PREFIX)) {
                            val comicKey = bookId.removePrefix(OnlineComicProgressStore.COMIC_BOOK_ID_PREFIX)
                            val progressStore = OnlineComicProgressStore(context.applicationContext)
                            val targetUrl = progressStore.getProgressByComicKey(comicKey)?.chapterUrl
                            if (!targetUrl.isNullOrBlank()) {
                                Screen.OnlineComicReader.createRoute(targetUrl, resumeProgress = true)
                            } else {
                                null
                            }
                        } else if (sourceFormat == BookSourceFormat.CBZ) {
                            Screen.ComicReader.createRoute(bookId)
                        } else {
                            Screen.Reader.createRoute(bookId)
                        }
                        if (route != null) {
                            navController.navigate(route)
                        }
                    },
                    onNavigateToBookmarks = {
                        navController.navigate(Screen.Bookmark.createRoute("global"))
                    },
                    onNavigateToOnlineLibrary = {
                        navController.navigate(Screen.Browse.route) {
                            launchSingleTop = true
                        }
                    },
                    onNavigateToBrowser = { navController.navigate(Screen.Browser.createRoute(null)) }
                )
            }

            composable(Screen.StoryProgress.route) {
                com.epubpro.feature.bookbible.StoryProgressScreen(
                    onOpenReview = { bookId ->
                        navController.navigate(Screen.StoryReview.createRoute(bookId))
                    },
                    onOpenBookBible = { source, chapterNumber ->
                        navController.navigate(
                            Screen.BookBible.createRoute(
                                sourceType = source.type.name,
                                sourceId = source.sourceId,
                                chapterNumber = chapterNumber
                            )
                        )
                    }
                )
            }

            composable(
                route = Screen.StoryReview.route,
                arguments = listOf(navArgument("bookId") { type = NavType.StringType })
            ) {
                com.epubpro.feature.bookbible.StoryReviewScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Screen.Profile.route) {
                ProfileScreen(
                    onNavigateToLogin = { navController.navigate(Screen.Login.route) },
                    onNavigateToAudioSettings = { navController.navigate(Screen.AudioSettings.route) },
                    onNavigateToReadingDefaults = { navController.navigate(Screen.ReadingDefaults.route) },
                    onNavigateToContentFilter = { navController.navigate(Screen.ContentFilter.route) },
                    onNavigateToSync = { navController.navigate(Screen.Sync.route) },
                    onNavigateToConvertDictionaries = { navController.navigate(Screen.ConvertDictionaries.route) }
                )
            }

            composable(Screen.ConvertDictionaries.route) {
                com.epubpro.feature.profile.convert.ConvertDictionaryScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Screen.Sync.route) {
                SyncScreen(onNavigateBack = { navController.popBackStack() })
            }

            composable(Screen.Login.route) {
                LoginScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onLoginSuccess = { navController.popBackStack() }
                )
            }

            composable(Screen.ContentFilter.route) {
                com.epubpro.feature.profile.filter.ContentFilterSettingsScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Screen.AudioSettings.route) {
                com.epubpro.feature.profile.audio.AudioSettingsScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(Screen.ReadingDefaults.route) {
                ReadingDefaultsScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onNavigateToPageTurnControl = { navController.navigate(Screen.PageTurnControl.route) }
                )
            }

            composable(Screen.PageTurnControl.route) {
                PageTurnControlScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Screen.Reader.route,
                arguments = listOf(
                    navArgument("bookId") { type = NavType.StringType },
                    navArgument(TtsOpenBookContract.NAV_ARGUMENT_CHAPTER_INDEX) {
                        type = NavType.IntType
                        defaultValue = TtsOpenBookContract.NO_CHAPTER_OVERRIDE
                    },
                    navArgument(TtsOpenBookContract.NAV_ARGUMENT_OPEN_TTS_PLAYER) {
                        type = NavType.BoolType
                        defaultValue = false
                    }
                )
            ) {
                ReaderScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onOpenWebChapter = { url -> navController.navigate(Screen.Browser.createRoute(url)) },
                    onOpenBookBible = { bookId, chapterNumber ->
                        navController.navigate(Screen.BookBible.createRoute("LOCAL_EPUB", bookId, chapterNumber))
                    }
                )
            }

            composable(
                route = Screen.Browser.route,
                arguments = listOf(
                    navArgument(BrowserViewModel.ARG_URL) {
                        type = NavType.StringType
                        nullable = true
                        defaultValue = null
                    }
                )
            ) {
                BrowserScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onOpenSavedBook = { bookId, chapterIndex -> navController.navigate(Screen.Reader.createRoute(bookId, chapterIndex)) },
                    onOpenBook = { bookId -> navController.navigate(Screen.Reader.createRoute(bookId)) },
                    onOpenComicChapter = { chapterUrl -> navController.navigate(Screen.OnlineComicReader.createRoute(chapterUrl)) }
                )
            }

            composable(
                route = Screen.ComicReader.route,
                arguments = listOf(navArgument(ComicReaderViewModel.ARG_BOOK_ID) { type = NavType.StringType })
            ) {
                ComicReaderScreen(onNavigateBack = { navController.popBackStack() })
            }

            composable(
                route = Screen.OnlineComicReader.route,
                arguments = listOf(
                    navArgument(OnlineComicReaderViewModel.ARG_CHAPTER_URL) { type = NavType.StringType },
                    navArgument(OnlineComicReaderViewModel.ARG_RESUME_PROGRESS) {
                        type = NavType.BoolType
                        defaultValue = false
                    }
                )
            ) {
                OnlineComicReaderScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onOpenInBrowser = { url -> navController.navigate(Screen.Browser.createRoute(url)) }
                )
            }

            composable(
                route = Screen.BookBible.route,
                arguments = listOf(
                    navArgument("sourceType") { type = NavType.StringType },
                    navArgument("sourceId") { type = NavType.StringType },
                    navArgument("chapterNumber") { type = NavType.StringType }
                )
            ) {
                com.epubpro.feature.bookbible.BookBibleScreen(
                    onNavigateBack = { navController.popBackStack() }
                )
            }

            composable(
                route = Screen.Bookmark.route,
                arguments = listOf(navArgument("bookId") { type = NavType.StringType })
            ) {
                BookmarkScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onJumpToCfi = { chapterIndex, cfi ->
                        navController.popBackStack()
                    }
                )
            }

            composable(
                route = Screen.Search.route,
                arguments = listOf(navArgument("bookId") { type = NavType.StringType })
            ) {
                SearchScreen(
                    onNavigateBack = { navController.popBackStack() },
                    onSearchResultClick = { chapterIndex, snippet ->
                        navController.popBackStack()
                    }
                )
            }
        }
    }
}

sealed class Screen(val route: String) {
    object Reader : Screen(
        "reader/{bookId}?" +
            "${TtsOpenBookContract.NAV_ARGUMENT_CHAPTER_INDEX}=" +
            "{${TtsOpenBookContract.NAV_ARGUMENT_CHAPTER_INDEX}}&" +
            "${TtsOpenBookContract.NAV_ARGUMENT_OPEN_TTS_PLAYER}=" +
            "{${TtsOpenBookContract.NAV_ARGUMENT_OPEN_TTS_PLAYER}}"
    ) {
        fun createRoute(
            bookId: String,
            chapterIndex: Int? = null,
            openTtsPlayer: Boolean = false
        ): String {
            val baseRoute = "reader/${Uri.encode(bookId)}"
            if (chapterIndex == null && !openTtsPlayer) return baseRoute

            val safeChapterIndex = chapterIndex
                ?.takeIf { it >= 0 }
                ?: TtsOpenBookContract.NO_CHAPTER_OVERRIDE
            return "$baseRoute?" +
                "${TtsOpenBookContract.NAV_ARGUMENT_CHAPTER_INDEX}=$safeChapterIndex&" +
                "${TtsOpenBookContract.NAV_ARGUMENT_OPEN_TTS_PLAYER}=$openTtsPlayer"
        }
    }

    object Browser : Screen("browser?${BrowserViewModel.ARG_URL}={${BrowserViewModel.ARG_URL}}") {
        fun createRoute(url: String?): String =
            if (url.isNullOrBlank()) "browser" else "browser?${BrowserViewModel.ARG_URL}=${Uri.encode(url)}"
    }

    object OnlineComicReader : Screen("online_comic_reader?${OnlineComicReaderViewModel.ARG_CHAPTER_URL}={${OnlineComicReaderViewModel.ARG_CHAPTER_URL}}&${OnlineComicReaderViewModel.ARG_RESUME_PROGRESS}={${OnlineComicReaderViewModel.ARG_RESUME_PROGRESS}}") {
        /**
         * Tạo route mở chương truyện tranh trực tiếp hoặc tiếp tục từ tiến độ đã lưu.
         *
         * @param chapterUrl URL chương cần mở.
         * @param resumeProgress true nếu màn hình được mở từ Kệ sách để khôi phục tiến độ.
         * @return Route đã mã hóa tham số chương và chế độ khôi phục.
         */
        fun createRoute(chapterUrl: String, resumeProgress: Boolean = false): String =
            "online_comic_reader?${OnlineComicReaderViewModel.ARG_CHAPTER_URL}=${Uri.encode(chapterUrl)}&${OnlineComicReaderViewModel.ARG_RESUME_PROGRESS}=$resumeProgress"
    }

    object ComicReader : Screen("comic_reader/{${ComicReaderViewModel.ARG_BOOK_ID}}") {
        fun createRoute(bookId: String): String = "comic_reader/${Uri.encode(bookId)}"
    }

    object BookBible : Screen("book_bible/{sourceType}/{sourceId}/{chapterNumber}") {
        fun createRoute(sourceType: String, sourceId: String, chapterNumber: Int): String {
            return "book_bible/${Uri.encode(sourceType)}/${Uri.encode(sourceId)}/$chapterNumber"
        }
    }

    object Bookmark : Screen("bookmark/{bookId}") {
        fun createRoute(bookId: String) = "bookmark/$bookId"
    }

    object Search : Screen("search/{bookId}") {
        fun createRoute(bookId: String) = "search/$bookId"
    }

    object Browse : Screen("browse")
    object OnlineNovelDetail : Screen("online_novel_detail/{novelId}") {
        fun createRoute(novelId: String) = "online_novel_detail/$novelId"
    }
    object OnlineChapterReader : Screen("online_chapter_reader/{novelId}/{chapterIndex}") {
        fun createRoute(novelId: String, chapterIndex: Int) = "online_chapter_reader/$novelId/$chapterIndex"
    }

    object Bookshelf : Screen("bookshelf")
    object StoryProgress : Screen("story_progress")
    object StoryReview : Screen("story_review/{bookId}") {
        fun createRoute(bookId: String): String = "story_review/${Uri.encode(bookId)}"
    }
    object Profile : Screen("profile")
    object Sync : Screen("sync")
    object ConvertDictionaries : Screen("convert_dictionaries")
    object Login : Screen("login")
    object AudioSettings : Screen("audio_settings")
    object ReadingDefaults : Screen("reading_defaults")
    object PageTurnControl : Screen("page_turn_control")
    object ContentFilter : Screen("content_filter")
}
