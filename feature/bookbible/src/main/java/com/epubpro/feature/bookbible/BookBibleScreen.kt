package com.epubpro.feature.bookbible

import androidx.activity.compose.BackHandler
import androidx.compose.animation.*
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.filled.*
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.epubpro.core.designsystem.R
import com.epubpro.domain.model.*

/** Danh sách các cặp màu gradient chuẩn cho avatar nhân vật */
private val AVATAR_GRADIENTS = listOf(
    Pair(Color(0xFFEF5350), Color(0xFFC62828)), // Warm Red
    Pair(Color(0xFF42A5F5), Color(0xFF1565C0)), // Ocean Blue
    Pair(Color(0xFF66BB6A), Color(0xFF2E7D32)), // Forest Green
    Pair(Color(0xFFFFA726), Color(0xFFE65100)), // Amber Orange
    Pair(Color(0xFFAB47BC), Color(0xFF6A1B9A)), // Royal Purple
    Pair(Color(0xFF26A69A), Color(0xFF004D40)), // Deep Teal
    Pair(Color(0xFFFF7043), Color(0xFFBF360C)), // Coral
    Pair(Color(0xFF26C6DA), Color(0xFF006064))  // Cyan
)

/**
 * Lấy dải màu gradient đại diện cho Avatar nhân vật dựa theo mã băm của tên.
 *
 * @param name Tên nhân vật.
 * @return Cặp màu (Start Color, End Color) dùng vẽ Brush gradient.
 */
private fun getAvatarGradient(name: String): Pair<Color, Color> {
    val index = kotlin.math.abs(name.hashCode()) % AVATAR_GRADIENTS.size
    return AVATAR_GRADIENTS[index]
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BookBibleScreen(
    onNavigateBack: () -> Unit,
    viewModel: BookBibleViewModel = hiltViewModel()
) {
    val uiState by viewModel.uiState.collectAsState()

    // Xử lý nút Back hệ thống: nếu đang xem chi tiết nhân vật thì quay lại danh sách
    BackHandler(enabled = uiState.selectedCharacterId != null) {
        viewModel.selectCharacter(null)
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text(
                            text = uiState.selectedCharacter?.name ?: stringResource(R.string.book_bible_title),
                            fontWeight = FontWeight.Bold,
                            style = MaterialTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        Text(
                            text = stringResource(R.string.book_bible_current_chapter_pill, uiState.chapterNumber),
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.primary,
                            fontWeight = FontWeight.SemiBold
                        )
                    }
                },
                navigationIcon = {
                    IconButton(
                        onClick = {
                            if (uiState.selectedCharacterId != null) {
                                viewModel.selectCharacter(null)
                            } else {
                                onNavigateBack()
                            }
                        }
                    ) {
                        Icon(
                            Icons.AutoMirrored.Filled.ArrowBack,
                            contentDescription = stringResource(R.string.action_back)
                        )
                    }
                },
                actions = {
                    if (uiState.isSubmissionEnabled == true) {
                        IconButton(onClick = viewModel::refreshSnapshot) {
                            Icon(
                                Icons.Default.Refresh,
                                contentDescription = stringResource(R.string.book_bible_refresh)
                            )
                        }
                    }
                    if (uiState.canDisableSubmission) {
                        var showMenu by remember { mutableStateOf(false) }
                        Box {
                            IconButton(onClick = { showMenu = true }) {
                                Icon(
                                    Icons.Default.MoreVert,
                                    contentDescription = stringResource(R.string.book_bible_more_actions)
                                )
                            }
                            DropdownMenu(
                                expanded = showMenu,
                                onDismissRequest = { showMenu = false }
                            ) {
                                DropdownMenuItem(
                                    text = { Text(stringResource(R.string.book_bible_consent_disable)) },
                                    onClick = {
                                        showMenu = false
                                        viewModel.setSubmissionEnabled(false)
                                    }
                                )
                            }
                        }
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = MaterialTheme.colorScheme.background
                )
            )
        }
    ) { paddingValues ->
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(paddingValues)
        ) {
            when {
                uiState.isSubmissionEnabled == false -> {
                    SubmissionConsentContent(onEnable = { viewModel.setSubmissionEnabled(true) })
                }
                uiState.isLoading && uiState.snapshot == null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
                    }
                }
                uiState.errorMessage != null && uiState.snapshot == null -> {
                    Box(
                        modifier = Modifier.fillMaxSize(),
                        contentAlignment = Alignment.Center
                    ) {
                        Column(
                            horizontalAlignment = Alignment.CenterHorizontally,
                            modifier = Modifier.padding(24.dp)
                        ) {
                            Text(
                                text = uiState.errorMessage ?: stringResource(R.string.book_bible_error_loading),
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.error
                            )
                            Spacer(modifier = Modifier.height(16.dp))
                            Button(onClick = viewModel::refreshSnapshot) {
                                Text(stringResource(R.string.book_bible_retry))
                            }
                        }
                    }
                }
                uiState.selectedCharacterId != null -> {
                    val character = uiState.selectedCharacter
                    if (character != null) {
                        CharacterDetailContent(
                            character = character,
                            selectedTab = uiState.selectedTab,
                            onTabSelected = viewModel::selectTab,
                            timeline = uiState.selectedTimeline,
                            isLoadingTimeline = uiState.isLoadingTimeline
                        )
                    }
                }
                else -> {
                    CharacterListContent(
                        uiState = uiState,
                        onCharacterClick = { viewModel.selectCharacter(it.id) }
                    )
                }
            }
        }
    }
}

/**
 * Màn giải thích và xin đồng ý trước khi bật Book Bible cho một sách EPUB cá nhân: nói rõ nội dung chương sẽ
 * được gửi lên máy chủ, và chỉ gửi sau khi người dùng bấm bật.
 *
 * @param onEnable Callback khi người dùng đồng ý bật Book Bible cho sách.
 */
@Composable
private fun SubmissionConsentContent(onEnable: () -> Unit) {
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(rememberScrollState())
            .padding(horizontal = 24.dp, vertical = 32.dp),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center
    ) {
        Icon(
            imageVector = Icons.Outlined.CloudUpload,
            contentDescription = null,
            tint = MaterialTheme.colorScheme.primary,
            modifier = Modifier.size(48.dp)
        )
        Spacer(modifier = Modifier.height(16.dp))
        Text(
            text = stringResource(R.string.book_bible_consent_title),
            style = MaterialTheme.typography.titleMedium,
            fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.height(12.dp))
        Text(
            text = stringResource(R.string.book_bible_consent_body),
            style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(24.dp))
        Button(onClick = onEnable) {
            Text(stringResource(R.string.book_bible_consent_enable))
        }
    }
}

/**
 * Giao diện danh sách hồ sơ nhân vật chia theo phân cấp trực quan, hỗ trợ tìm kiếm nhanh và Hero Card cho nhân vật chính.
 *
 * @param uiState Trạng thái UI hiện tại của màn hình Book Bible.
 * @param onCharacterClick Callback khi người dùng nhấn vào thẻ nhân vật.
 */
@Composable
private fun CharacterListContent(
    uiState: BookBibleUiState,
    onCharacterClick: (CharacterProfile) -> Unit
) {
    val snapshot = uiState.snapshot
    var searchQuery by rememberSaveable { mutableStateOf("") }

    val filteredCharacters = remember(uiState.sortedCharacters, searchQuery) {
        if (searchQuery.isBlank()) {
            uiState.sortedCharacters
        } else {
            val q = searchQuery.trim().lowercase()
            uiState.sortedCharacters.filter { char ->
                char.name.lowercase().contains(q) ||
                    (char.originalName?.lowercase()?.contains(q) == true) ||
                    (char.cultivationRealm?.lowercase()?.contains(q) == true) ||
                    (char.role?.lowercase()?.contains(q) == true) ||
                    char.affiliations.any { it.lowercase().contains(q) } ||
                    char.aliases.any { it.lowercase().contains(q) }
            }
        }
    }

    val mainCharacter = filteredCharacters.firstOrNull { it.isProtagonist }
    val otherCharacters = filteredCharacters.filter { it != mainCharacter }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(bottom = 24.dp)
    ) {
        // 1. Status & Coverage Banner
        if (snapshot != null) {
            item(key = "status_banner") {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 6.dp)) {
                    StatusCoverageBanner(snapshot = snapshot, isPolling = uiState.isPolling)
                }
            }
        }

        // 2. Search Bar
        if (uiState.sortedCharacters.size > 3) {
            item(key = "search_bar") {
                OutlinedTextField(
                    value = searchQuery,
                    onValueChange = { searchQuery = it },
                    placeholder = {
                        Text(
                            text = stringResource(R.string.book_bible_search_placeholder),
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
                        )
                    },
                    leadingIcon = {
                        Icon(
                            imageVector = Icons.Outlined.Search,
                            contentDescription = null,
                            tint = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    },
                    trailingIcon = {
                        if (searchQuery.isNotBlank()) {
                            IconButton(onClick = { searchQuery = "" }) {
                                Icon(
                                    imageVector = Icons.Default.Close,
                                    contentDescription = stringResource(R.string.book_bible_clear_search),
                                    modifier = Modifier.size(18.dp)
                                )
                            }
                        }
                    },
                    singleLine = true,
                    shape = RoundedCornerShape(12.dp),
                    colors = OutlinedTextFieldDefaults.colors(
                        focusedContainerColor = MaterialTheme.colorScheme.surface,
                        unfocusedContainerColor = MaterialTheme.colorScheme.surface,
                        focusedBorderColor = MaterialTheme.colorScheme.primary,
                        unfocusedBorderColor = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.5f)
                    ),
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp)
                )
            }
        }

        // 3. Main Character Section
        if (mainCharacter != null) {
            item(key = "main_character_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 6.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.book_bible_section_main_character),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Icon(
                        imageVector = Icons.Filled.Star,
                        contentDescription = null,
                        tint = Color(0xFFFFB300),
                        modifier = Modifier.size(16.dp)
                    )
                }
            }

            item(key = "hero_main_character") {
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    MainCharacterHeroCard(
                        character = mainCharacter,
                        onClick = { onCharacterClick(mainCharacter) }
                    )
                }
            }
        }

        // 4. Other Characters Section
        if (otherCharacters.isNotEmpty()) {
            item(key = "other_characters_header") {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 16.dp, vertical = 10.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stringResource(R.string.book_bible_section_other_characters),
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.6f),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(
                            text = "${otherCharacters.size}",
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                            modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
                        )
                    }
                }
            }

            items(otherCharacters, key = { it.id }) { character ->
                Box(modifier = Modifier.padding(horizontal = 16.dp, vertical = 4.dp)) {
                    CharacterListItem(
                        character = character,
                        onClick = { onCharacterClick(character) }
                    )
                }
            }
        }

        // 5. Empty State
        if (filteredCharacters.isEmpty()) {
            item(key = "empty_state") {
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(32.dp),
                    contentAlignment = Alignment.Center
                ) {
                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                        Icon(
                            Icons.Outlined.PersonSearch,
                            contentDescription = null,
                            modifier = Modifier.size(56.dp),
                            tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f)
                        )
                        Spacer(modifier = Modifier.height(12.dp))
                        Text(
                            text = if (searchQuery.isNotBlank()) {
                                stringResource(R.string.book_bible_no_search_results)
                            } else {
                                stringResource(R.string.book_bible_status_empty)
                            },
                            style = MaterialTheme.typography.titleSmall,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }
            }
        }
    }
}

/**
 * Thanh hiển thị trạng thái dữ liệu và độ bao phủ chương của Book Bible một cách tinh gọn.
 *
 * @param snapshot Bản snapshot dữ liệu hiện tại.
 * @param isPolling Cờ đang thăm dò dữ liệu AI phân tích.
 */
@Composable
private fun StatusCoverageBanner(
    snapshot: BookBibleSnapshot,
    isPolling: Boolean
) {
    if (snapshot.status == SnapshotStatus.COMPLETE && !isPolling && snapshot.coverage.missingRanges.isEmpty()) {
        return
    }

    Surface(
        color = when {
            isPolling -> MaterialTheme.colorScheme.secondaryContainer.copy(alpha = 0.4f)
            snapshot.status == SnapshotStatus.PARTIAL -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.6f)
            else -> MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        },
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(horizontal = 14.dp, vertical = 8.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = when {
                        isPolling -> Icons.Default.Sync
                        snapshot.status == SnapshotStatus.PARTIAL -> Icons.Default.Info
                        else -> Icons.Default.CheckCircle
                    },
                    contentDescription = null,
                    modifier = Modifier.size(15.dp),
                    tint = if (isPolling) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(6.dp))
                Text(
                    text = when {
                        isPolling -> stringResource(R.string.book_bible_status_processing)
                        snapshot.status == SnapshotStatus.PARTIAL -> stringResource(R.string.book_bible_status_partial)
                        else -> stringResource(R.string.book_bible_status_complete)
                    },
                    fontWeight = FontWeight.SemiBold,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                )
            }

            if (snapshot.coverage.processedRanges.isNotEmpty()) {
                val processedStr = snapshot.coverage.processedRanges.joinToString(", ") { "c.${it.start}-${it.end}" }
                val missingStr = if (snapshot.coverage.missingRanges.isNotEmpty()) {
                    snapshot.coverage.missingRanges.joinToString(", ") { "c.${it.start}-${it.end}" }
                } else null

                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = if (missingStr != null) {
                        stringResource(R.string.book_bible_coverage_format, processedStr, missingStr)
                    } else {
                        "Đã phân tích: $processedStr"
                    },
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }

            if (isPolling) {
                Spacer(modifier = Modifier.height(4.dp))
                LinearProgressIndicator(
                    modifier = Modifier
                        .fillMaxWidth()
                        .height(3.dp)
                        .clip(CircleShape)
                )
            }
        }
    }
}

/**
 * Thẻ hiển thị Nhân vật chính theo phong cách hiện đại, đồng bộ với danh sách.
 *
 * @param character Dữ liệu hồ sơ nhân vật chính.
 * @param onClick Callback khi người dùng nhấn xem chi tiết.
 */
@Composable
private fun MainCharacterHeroCard(
    character: CharacterProfile,
    onClick: () -> Unit
) {
    val primaryColor = MaterialTheme.colorScheme.primary

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, primaryColor.copy(alpha = 0.5f)),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar Circle với Gold Amber Gradient & Star Badge
            Box(contentAlignment = Alignment.Center) {
                Box(
                    modifier = Modifier
                        .size(46.dp)
                        .clip(CircleShape)
                        .background(
                            Brush.linearGradient(
                                listOf(
                                    Color(0xFFFFB300), // Amber Gold
                                    Color(0xFFE65100)  // Deep Orange
                                )
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = character.name.firstOrNull()?.uppercase() ?: "?",
                        fontWeight = FontWeight.Bold,
                        fontSize = 18.sp,
                        color = Color.White
                    )
                }
                Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.surface,
                    modifier = Modifier
                        .size(16.dp)
                        .align(Alignment.BottomEnd)
                ) {
                    Box(contentAlignment = Alignment.Center) {
                        Icon(
                            imageVector = Icons.Filled.Star,
                            contentDescription = null,
                            tint = primaryColor,
                            modifier = Modifier.size(11.dp)
                        )
                    }
                }
            }

            Spacer(modifier = Modifier.width(14.dp))

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Hàng 1: Tên nhân vật + Huy hiệu Nhân vật chính
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = character.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 16.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    val origName = character.originalName
                    if (!origName.isNullOrBlank() && !origName.equals(character.name, ignoreCase = true)) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "($origName)",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    Spacer(modifier = Modifier.width(8.dp))
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        ) {
                            Icon(
                                imageVector = Icons.Filled.Star,
                                contentDescription = null,
                                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                                modifier = Modifier.size(10.dp)
                            )
                            Spacer(modifier = Modifier.width(3.dp))
                            Text(
                                text = stringResource(R.string.book_bible_role_protagonist),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onPrimaryContainer
                            )
                        }
                    }
                }

                // Hàng 2: Vai trò / Thân phận (nếu có mô tả thêm)
                val role = character.role
                if (!role.isNullOrBlank() &&
                    !role.contains("chính", ignoreCase = true) &&
                    !role.contains("protagonist", ignoreCase = true) &&
                    !role.contains("main", ignoreCase = true)
                ) {
                    Text(
                        text = role,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 17.sp
                    )
                }

                // Hàng 3: Cảnh giới tu vi & Thế lực (nếu có)
                val metaItems = listOfNotNull(
                    character.cultivationRealm?.takeIf { it.isNotBlank() },
                    character.affiliations.firstOrNull()?.takeIf { it.isNotBlank() }
                )
                if (metaItems.isNotEmpty()) {
                    Text(
                        text = metaItems.joinToString(" • "),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = primaryColor,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Chevron Icon
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = primaryColor,
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

/**
 * Thẻ hiển thị một nhân vật trong danh sách nhân vật của tác phẩm theo bố cục chuẩn dọc chống tràn chữ.
 *
 * @param character Dữ liệu hồ sơ nhân vật cần hiển thị.
 * @param onClick Callback khi người dùng nhấn vào thẻ để xem chi tiết.
 */
@Composable
private fun CharacterListItem(
    character: CharacterProfile,
    onClick: () -> Unit
) {
    val (gradStart, gradEnd) = getAvatarGradient(character.name)

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .clip(RoundedCornerShape(16.dp))
            .clickable(onClick = onClick),
        shape = RoundedCornerShape(16.dp),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            // Avatar Circle với Gradient sắc nét
            Box(
                modifier = Modifier
                    .size(44.dp)
                    .clip(CircleShape)
                    .background(Brush.linearGradient(listOf(gradStart, gradEnd))),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = character.name.firstOrNull()?.uppercase() ?: "?",
                    fontWeight = FontWeight.Bold,
                    fontSize = 17.sp,
                    color = Color.White
                )
            }

            Spacer(modifier = Modifier.width(14.dp))

            // Cột thông tin nhân vật chính: Tên ở dòng 1, mô tả/vai trò ở dòng 2 (không bị bóp nghẹt ngang)
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                // Hàng 1: Tên nhân vật + Tên gốc + Phản diện (nếu có)
                Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        text = character.name,
                        fontWeight = FontWeight.Bold,
                        fontSize = 15.sp,
                        color = MaterialTheme.colorScheme.onSurface,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis,
                        modifier = Modifier.weight(1f, fill = false)
                    )

                    val origName = character.originalName
                    if (!origName.isNullOrBlank() && !origName.equals(character.name, ignoreCase = true)) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(
                            text = "($origName)",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }

                    if (character.isAntagonist) {
                        Spacer(modifier = Modifier.width(6.dp))
                        Surface(
                            color = MaterialTheme.colorScheme.errorContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = stringResource(R.string.book_bible_role_antagonist),
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = MaterialTheme.colorScheme.onErrorContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                // Hàng 2: Vai trò / Mô tả nhân vật (Hiển thị văn bản mượt mà, không dùng bubble tím thô cứng)
                val role = character.role
                if (!role.isNullOrBlank() &&
                    !role.contains("phụ", ignoreCase = true) &&
                    !role.contains("supporting", ignoreCase = true) &&
                    !role.contains("chính", ignoreCase = true)
                ) {
                    Text(
                        text = role,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        lineHeight = 17.sp
                    )
                }

                // Hàng 3: Cảnh giới tu vi & Thế lực (nếu có)
                val metaItems = listOfNotNull(
                    character.cultivationRealm?.takeIf { it.isNotBlank() },
                    character.affiliations.firstOrNull()?.takeIf { it.isNotBlank() }
                )
                if (metaItems.isNotEmpty()) {
                    Text(
                        text = metaItems.joinToString(" • "),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Medium,
                        color = MaterialTheme.colorScheme.primary,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                }
            }

            Spacer(modifier = Modifier.width(8.dp))

            // Chevron Icon
            Icon(
                imageVector = Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.35f),
                modifier = Modifier.size(20.dp)
            )
        }
    }
}

@Composable
private fun CharacterDetailContent(
    character: CharacterProfile,
    selectedTab: Int,
    onTabSelected: (Int) -> Unit,
    timeline: CharacterTimeline?,
    isLoadingTimeline: Boolean
) {
    Column(modifier = Modifier.fillMaxSize()) {
        TabRow(
            selectedTabIndex = selectedTab,
            containerColor = MaterialTheme.colorScheme.background
        ) {
            Tab(
                selected = selectedTab == 0,
                onClick = { onTabSelected(0) },
                text = { Text(stringResource(R.string.book_bible_tab_profile), fontWeight = FontWeight.SemiBold) }
            )
            Tab(
                selected = selectedTab == 1,
                onClick = { onTabSelected(1) },
                text = { Text(stringResource(R.string.book_bible_tab_timeline), fontWeight = FontWeight.SemiBold) }
            )
        }

        if (selectedTab == 0) {
            CharacterProfileTab(character = character)
        } else {
            CharacterTimelineTab(
                timeline = timeline,
                isLoading = isLoadingTimeline
            )
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun CharacterProfileTab(character: CharacterProfile) {
    val scrollState = rememberScrollState()
    Column(
        modifier = Modifier
            .fillMaxSize()
            .verticalScroll(scrollState)
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp)
    ) {
        // Tính cách & Ngữ điệu
        val voiceNotes = character.voiceNotes
        if (!voiceNotes.isNullOrBlank()) {
            ProfileSectionCard(
                title = stringResource(R.string.book_bible_section_voice_notes),
                icon = Icons.Outlined.Psychology
            ) {
                Text(
                    text = voiceNotes,
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 18.sp
                )
            }
        }

        // Cảnh giới tu vi
        val realm = character.cultivationRealm
        if (!realm.isNullOrBlank()) {
            ProfileSectionCard(
                title = stringResource(R.string.book_bible_section_cultivation),
                icon = Icons.Outlined.Bolt
            ) {
                Text(
                    text = realm,
                    fontSize = 15.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.primary
                )
            }
        }

        // Công pháp
        if (character.techniques.isNotEmpty()) {
            ProfileSectionCard(
                title = stringResource(R.string.book_bible_section_techniques),
                icon = Icons.Outlined.AutoStories
            ) {
                FlowChips(items = character.techniques)
            }
        }

        // Kỹ năng
        if (character.skills.isNotEmpty()) {
            ProfileSectionCard(
                title = stringResource(R.string.book_bible_section_skills),
                icon = Icons.Outlined.Psychology
            ) {
                FlowChips(items = character.skills)
            }
        }

        // Trang bị & Pháp bảo
        if (character.items.isNotEmpty()) {
            ProfileSectionCard(
                title = stringResource(R.string.book_bible_section_items),
                icon = Icons.Outlined.Shield
            ) {
                FlowChips(items = character.items)
            }
        }

        // Linh thú & Thú cưng (Pets)
        if (character.pets.isNotEmpty()) {
            ProfileSectionCard(
                title = stringResource(R.string.book_bible_section_pets),
                icon = Icons.Outlined.Pets
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    character.pets.forEach { pet ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(8.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(10.dp)
                        ) {
                            Row(
                                verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.SpaceBetween,
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text(
                                    text = pet.name,
                                    fontWeight = FontWeight.Bold,
                                    fontSize = 14.sp,
                                    color = MaterialTheme.colorScheme.primary
                                )
                                val status = pet.status
                                if (!status.isNullOrBlank()) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.tertiaryContainer,
                                        shape = RoundedCornerShape(4.dp)
                                    ) {
                                        Text(
                                            text = status,
                                            fontSize = 10.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onTertiaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }

                            val speciesOrRealm = listOfNotNull(
                                pet.species,
                                pet.realm
                            ).joinToString(" • ")

                            if (speciesOrRealm.isNotBlank()) {
                                Spacer(modifier = Modifier.height(4.dp))
                                Text(
                                    text = speciesOrRealm,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // Quan hệ nhân vật
        if (character.relationships.isNotEmpty()) {
            ProfileSectionCard(
                title = stringResource(R.string.book_bible_section_relationships),
                icon = Icons.Outlined.People
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    character.relationships.forEach { rel ->
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Text(
                                text = rel.targetName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp
                            )
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                                color = MaterialTheme.colorScheme.primaryContainer,
                                shape = RoundedCornerShape(6.dp)
                            ) {
                                Text(
                                    text = rel.relationType,
                                    fontSize = 11.sp,
                                    fontWeight = FontWeight.SemiBold,
                                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                )
                            }
                            val relDesc = rel.description
                            if (!relDesc.isNullOrBlank()) {
                                Spacer(modifier = Modifier.width(6.dp))
                                Text(
                                    text = relDesc,
                                    fontSize = 12.sp,
                                    color = MaterialTheme.colorScheme.onSurfaceVariant
                                )
                            }
                        }
                    }
                }
            }
        }

        // Thế lực
        if (character.affiliations.isNotEmpty()) {
            ProfileSectionCard(
                title = stringResource(R.string.book_bible_section_affiliations),
                icon = Icons.Outlined.AccountBalance
            ) {
                FlowChips(items = character.affiliations)
            }
        }

        // Danh hiệu
        if (character.titles.isNotEmpty()) {
            ProfileSectionCard(
                title = stringResource(R.string.book_bible_section_titles),
                icon = Icons.Outlined.MilitaryTech
            ) {
                FlowChips(items = character.titles)
            }
        }

        // Khác (Extra Attributes)
        if (character.extraAttributes.isNotEmpty()) {
            ProfileSectionCard(
                title = stringResource(R.string.book_bible_section_other),
                icon = Icons.Outlined.Category
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                    character.extraAttributes.forEach { attr ->
                        Row(modifier = Modifier.fillMaxWidth()) {
                            Text(
                                text = "${attr.label}: ",
                                fontWeight = FontWeight.Bold,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                            Text(
                                text = attr.value,
                                fontSize = 13.sp,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                    }
                }
            }
        }

        // Xưng hô & Giao tiếp (Address Terms - Ở CUỐI CÙNG)
        if (character.addressTerms.isNotEmpty()) {
            ProfileSectionCard(
                title = stringResource(R.string.book_bible_section_address_terms),
                icon = Icons.Outlined.RecordVoiceOver
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    character.addressTerms.forEach { term ->
                        Column(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clip(RoundedCornerShape(10.dp))
                                .background(MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f))
                                .padding(12.dp)
                        ) {
                            Text(
                                text = term.targetName,
                                fontWeight = FontWeight.Bold,
                                fontSize = 14.sp,
                                color = MaterialTheme.colorScheme.primary
                            )

                            Spacer(modifier = Modifier.height(6.dp))

                            FlowRow(
                                horizontalArrangement = Arrangement.spacedBy(8.dp),
                                verticalArrangement = Arrangement.spacedBy(6.dp)
                            ) {
                                val self = term.selfTerm
                                if (!self.isNullOrBlank()) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.primaryContainer,
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = "${stringResource(R.string.book_bible_address_self_label)} $self",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }

                                val other = term.otherTerm
                                if (!other.isNullOrBlank()) {
                                    Surface(
                                        color = MaterialTheme.colorScheme.secondaryContainer,
                                        shape = RoundedCornerShape(6.dp)
                                    ) {
                                        Text(
                                            text = "${stringResource(R.string.book_bible_address_other_label)} $other",
                                            fontSize = 11.sp,
                                            fontWeight = FontWeight.Medium,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer,
                                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                                        )
                                    }
                                }
                            }

                            val contextsToDisplay = if (term.contexts.isNotEmpty()) {
                                term.contexts
                            } else {
                                listOfNotNull(term.context)
                            }

                            if (contextsToDisplay.isNotEmpty()) {
                                Spacer(modifier = Modifier.height(6.dp))
                                Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
                                    contextsToDisplay.forEach { ctx ->
                                        Text(
                                            text = if (contextsToDisplay.size > 1) "• $ctx" else ctx,
                                            fontSize = 12.sp,
                                            color = MaterialTheme.colorScheme.onSurfaceVariant,
                                            lineHeight = 16.sp
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun ProfileSectionCard(
    title: String,
    icon: ImageVector,
    content: @Composable () -> Unit
) {
    Card(
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 1.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = title,
                    style = MaterialTheme.typography.titleSmall,
                    fontWeight = FontWeight.Bold
                )
            }
            Spacer(modifier = Modifier.height(10.dp))
            content()
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun FlowChips(items: List<String>) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        items.forEach { item ->
            Surface(
                shape = RoundedCornerShape(8.dp),
                color = MaterialTheme.colorScheme.surfaceVariant
            ) {
                Text(
                    text = item,
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Medium,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
                )
            }
        }
    }
}

@Composable
private fun CharacterTimelineTab(
    timeline: CharacterTimeline?,
    isLoading: Boolean
) {
    when {
        isLoading -> {
            Box(
                modifier = Modifier.fillMaxSize(),
                contentAlignment = Alignment.Center
            ) {
                CircularProgressIndicator(color = MaterialTheme.colorScheme.primary)
            }
        }
        timeline == null || timeline.events.isEmpty() -> {
            Box(
                modifier = Modifier
                    .fillMaxSize()
                    .padding(32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = stringResource(R.string.book_bible_timeline_empty),
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
        else -> {
            LazyColumn(
                contentPadding = PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(14.dp),
                modifier = Modifier.fillMaxSize()
            ) {
                items(timeline.events) { event ->
                    TimelineEventCard(event = event)
                }
            }
        }
    }
}

@Composable
private fun TimelineEventCard(event: CharacterTimelineEvent) {
    Card(
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp),
        modifier = Modifier.fillMaxWidth()
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween,
                modifier = Modifier.fillMaxWidth()
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Surface(
                        color = MaterialTheme.colorScheme.primaryContainer,
                        shape = RoundedCornerShape(6.dp)
                    ) {
                        Text(
                            text = stringResource(R.string.book_bible_timeline_chapter_badge, event.chapter),
                            fontSize = 11.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer,
                            modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                        )
                    }

                    val certainty = event.certainty
                    if (!certainty.isNullOrBlank()) {
                        Spacer(modifier = Modifier.width(6.dp))
                        val certaintyText = when (certainty.lowercase(java.util.Locale.ROOT)) {
                            "observed" -> stringResource(R.string.book_bible_certainty_observed)
                            "stated" -> stringResource(R.string.book_bible_certainty_stated)
                            "rumor" -> stringResource(R.string.book_bible_certainty_rumor)
                            "inferred" -> stringResource(R.string.book_bible_certainty_inferred)
                            "contradicted" -> stringResource(R.string.book_bible_certainty_contradicted)
                            else -> certainty
                        }
                        Surface(
                            color = MaterialTheme.colorScheme.tertiaryContainer,
                            shape = RoundedCornerShape(6.dp)
                        ) {
                            Text(
                                text = certaintyText,
                                fontSize = 10.sp,
                                fontWeight = FontWeight.SemiBold,
                                color = MaterialTheme.colorScheme.onTertiaryContainer,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                            )
                        }
                    }
                }

                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant,
                    shape = RoundedCornerShape(6.dp)
                ) {
                    val categoryVi = when (event.category.lowercase(java.util.Locale.ROOT)) {
                        "skill" -> "Kỹ năng"
                        "technique" -> "Công pháp"
                        "item" -> "Trang bị"
                        "cultivation", "realm" -> "Cảnh giới"
                        "relationship" -> "Quan hệ"
                        "pet" -> "Linh thú"
                        "affiliation" -> "Thế lực"
                        "title" -> "Danh hiệu"
                        else -> event.category
                    }
                    val opVi = when (event.operation.lowercase(java.util.Locale.ROOT)) {
                        "add" -> "Thêm mới"
                        "set" -> "Cập nhật"
                        "advance" -> "Đột phá"
                        "remove" -> "Rời đi / Mất"
                        else -> event.operation
                    }
                    Text(
                        text = "$categoryVi • $opVi",
                        fontSize = 11.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                    )
                }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = event.displayValue,
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp
            )

            if (!event.evidence.isNullOrBlank()) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                    color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.5f),
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(modifier = Modifier.padding(8.dp)) {
                        Text(
                            text = stringResource(R.string.book_bible_timeline_evidence_label),
                            fontSize = 10.sp,
                            fontWeight = FontWeight.Bold,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                        Spacer(modifier = Modifier.height(2.dp))
                        Text(
                            text = "\"${event.evidence}\"",
                            fontSize = 12.sp,
                            fontStyle = androidx.compose.ui.text.font.FontStyle.Italic,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                    }
                }
            }
        }
    }
}
