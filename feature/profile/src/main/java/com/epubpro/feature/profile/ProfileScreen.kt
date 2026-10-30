package com.epubpro.feature.profile

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.Login
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.automirrored.filled.VolumeUp
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.disabled
import androidx.compose.ui.semantics.heading
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.hilt.navigation.compose.hiltViewModel
import com.epubpro.core.designsystem.R
import com.epubpro.domain.model.AuthState
import com.epubpro.domain.model.User
import java.util.Locale

/**
 * Màn hình Cá nhân (Profile & Settings) tối ưu hiệu năng cuộn:
 * - Sử dụng Column kết hợp verticalScroll giúp loại bỏ hoàn toàn hiện tượng khựng/giật (jank) khi vuốt
 * - Nền màn hình chuẩn F8F9FA / FCF9F8
 * - Avatar tròn 96dp nổi bật với nền ấm đào (FFE8E0) và chữ cái Serif sắc nét
 * - Badge VIP bo tròn viên thuốc đè góc dưới bên phải avatar
 * - Hàng 3 thẻ thống kê bo góc 24dp (Thẻ giữa Terracotta ngọn lửa trắng, 2 thẻ bên nền kem FFF7F2)
 * - Các nhóm cài đặt Card bo góc 24dp với viền 1px tinh tế
 *
 * @param onNavigateToLogin Callback mở màn hình Đăng nhập / Đăng ký.
 * @param onNavigateToAudioSettings Callback mở cài đặt âm thanh TTS.
 * @param onNavigateToReadingDefaults Callback mở cài đặt mặc định đọc.
 * @param onNavigateToContentFilter Callback mở màn hình lọc nội dung.
 * @param onNavigateToSync Callback mở màn hình đồng bộ Google Drive.
 * @param viewModel ViewModel quản lý dữ liệu cấu hình và trạng thái tài khoản.
 */
@Composable
fun ProfileScreen(
    onNavigateToLogin: () -> Unit = {},
    onNavigateToAudioSettings: () -> Unit = {},
    onNavigateToReadingDefaults: () -> Unit = {},
    onNavigateToContentFilter: () -> Unit = {},
    onNavigateToSync: () -> Unit = {},
    onNavigateToConvertDictionaries: () -> Unit = {},
    viewModel: ProfileViewModel = hiltViewModel()
) {
    var showServerSettingsDialog by rememberSaveable { mutableStateOf(false) }
    var showLogoutConfirmDialog by rememberSaveable { mutableStateOf(false) }
    var showAccountDetailDialog by rememberSaveable { mutableStateOf(false) }

    val authState by viewModel.authState.collectAsState()
    val readerSettings by viewModel.readerSettings.collectAsState()
    val scrollState = rememberScrollState()

    Column(
        modifier = Modifier
            .fillMaxSize()
            .background(MaterialTheme.colorScheme.background)
            .verticalScroll(scrollState)
            .padding(top = 28.dp, bottom = 36.dp),
        verticalArrangement = Arrangement.spacedBy(22.dp)
    ) {
        // Phần Hero Profile (Avatar tròn, Badge VIP góc dưới phải, Tên và Email)
        ProfileHeroSection(
            authState = authState,
            onLoginClick = onNavigateToLogin,
            onAccountClick = { showAccountDetailDialog = true },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )

        // Hàng 3 thẻ thống kê đọc sách (Data-Focused Stats Row)
        val user = (authState as? AuthState.Authenticated)?.user
        ProfileStatsRow(
            totalBooks = user?.totalReadBooks ?: 0,
            streakDays = user?.readingStreakDays ?: 0,
            readHours = user?.totalReadHours ?: 0.0,
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        )

        // Nhóm 1: Đồng bộ dữ liệu
        ProfileSectionCard(
            title = stringResource(R.string.profile_section_sync),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            ProfileCardItem(
                title = stringResource(R.string.profile_drive_sync_title),
                subtitle = stringResource(R.string.profile_drive_sync_subtitle),
                icon = Icons.Default.CloudSync,
                onClick = onNavigateToSync
            )
        }

        // Nhóm 2: Cài đặt ứng dụng
        ProfileSectionCard(
            title = stringResource(R.string.profile_section_settings),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            ProfileCardItem(
                title = stringResource(R.string.profile_nav_custom_title),
                subtitle = stringResource(R.string.profile_nav_custom_subtitle),
                icon = Icons.Default.ViewCarousel,
                onClick = { },
                enabled = false
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            ProfileCardItem(
                title = stringResource(R.string.profile_appearance_title),
                subtitle = stringResource(R.string.profile_appearance_subtitle),
                icon = Icons.Default.Palette,
                onClick = { },
                enabled = false
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            ProfileSwitchItem(
                title = stringResource(R.string.profile_auto_resume_last_book_title),
                subtitle = stringResource(R.string.profile_auto_resume_last_book_subtitle),
                icon = Icons.AutoMirrored.Filled.MenuBook,
                checked = readerSettings.autoResumeLastBookOnStartup,
                onCheckedChange = viewModel::setAutoResumeLastBook
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            ProfileCardItem(
                title = stringResource(R.string.profile_reader_defaults_title),
                subtitle = stringResource(R.string.profile_reader_defaults_subtitle),
                icon = Icons.AutoMirrored.Filled.MenuBook,
                onClick = onNavigateToReadingDefaults
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            ProfileCardItem(
                title = stringResource(R.string.profile_page_turn_gesture_title),
                subtitle = stringResource(R.string.profile_page_turn_gesture_subtitle),
                icon = Icons.Default.TouchApp,
                onClick = onNavigateToReadingDefaults
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            ProfileCardItem(
                title = stringResource(R.string.profile_audio_settings_title),
                subtitle = stringResource(R.string.profile_audio_settings_subtitle),
                icon = Icons.AutoMirrored.Filled.VolumeUp,
                onClick = onNavigateToAudioSettings
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            ProfileCardItem(
                title = stringResource(R.string.profile_highlight_tags_title),
                subtitle = stringResource(R.string.profile_highlight_tags_subtitle),
                icon = Icons.AutoMirrored.Filled.Label,
                onClick = { },
                enabled = false
            )
        }

        // Nhóm 3: Nâng cao
        ProfileSectionCard(
            title = stringResource(R.string.profile_section_advanced),
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp)
        ) {
            ProfileCardItem(
                title = stringResource(R.string.profile_content_filter_title),
                subtitle = stringResource(R.string.profile_content_filter_subtitle),
                icon = Icons.Default.FilterAlt,
                onClick = onNavigateToContentFilter
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            ProfileCardItem(
                title = stringResource(R.string.convert_dictionaries_title),
                subtitle = stringResource(R.string.convert_dictionaries_subtitle),
                icon = Icons.Default.Translate,
                onClick = onNavigateToConvertDictionaries
            )
            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
            ProfileCardItem(
                title = stringResource(R.string.server_settings_title),
                subtitle = stringResource(R.string.server_settings_subtitle),
                icon = Icons.Default.Dns,
                onClick = { showServerSettingsDialog = true }
            )
        }

        // Nhóm 4: Tài khoản (khi đã đăng nhập)
        if (authState is AuthState.Authenticated) {
            ProfileSectionCard(
                title = stringResource(R.string.auth_profile_section_account),
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(horizontal = 16.dp)
            ) {
                ProfileCardItem(
                    title = stringResource(R.string.auth_profile_edit_account_title),
                    subtitle = stringResource(R.string.auth_profile_edit_account_subtitle),
                    icon = Icons.Default.Badge,
                    onClick = { showAccountDetailDialog = true }
                )
                HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.35f))
                ProfileCardItem(
                    title = stringResource(R.string.auth_profile_logout_title),
                    subtitle = stringResource(R.string.auth_profile_logout_subtitle),
                    icon = Icons.AutoMirrored.Filled.Logout,
                    onClick = { showLogoutConfirmDialog = true },
                    isDestructive = true
                )
            }
        }

        // Footer chú thích phiên bản ứng dụng
        Text(
            text = stringResource(R.string.profile_footer_version_note),
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f),
            textAlign = TextAlign.Center,
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 8.dp, bottom = 16.dp)
        )
    }

    if (showServerSettingsDialog) {
        ServerSettingsDialog(
            onDismissRequest = { showServerSettingsDialog = false },
            serverPreferencesManager = viewModel.serverPreferencesManager,
            onlineNovelRepository = viewModel.onlineNovelRepository
        )
    }

    if (showAccountDetailDialog) {
        val currentUser = (authState as? AuthState.Authenticated)?.user
        if (currentUser != null) {
            AccountDetailDialog(
                user = currentUser,
                onDismiss = { showAccountDetailDialog = false }
            )
        }
    }

    if (showLogoutConfirmDialog) {
        val currentUser = (authState as? AuthState.Authenticated)?.user
        LogoutConfirmDialog(
            userName = currentUser?.displayName ?: "",
            onDismiss = { showLogoutConfirmDialog = false },
            onConfirm = {
                showLogoutConfirmDialog = false
                viewModel.logout()
            }
        )
    }
}

/**
 * Phần Hero hiển thị thông tin người dùng với Avatar tròn rõ nét và Badge VIP nhỏ gọn ở góc dưới bên phải.
 *
 * @param authState Trạng thái xác thực hiện tại.
 * @param onLoginClick Callback khi nhấn nút Đăng nhập / Đăng ký.
 * @param onAccountClick Callback khi nhấn vào thẻ tài khoản.
 * @param modifier Modifier tùy biến bố cục.
 */
@Composable
fun ProfileHeroSection(
    authState: AuthState,
    onLoginClick: () -> Unit,
    onAccountClick: () -> Unit = {},
    modifier: Modifier = Modifier
) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        when (authState) {
            is AuthState.Authenticated -> {
                AuthenticatedHeroContent(
                    user = authState.user,
                    onAccountClick = onAccountClick
                )
            }
            else -> {
                UnauthenticatedHeroContent(onLoginClick = onLoginClick)
            }
        }
    }
}

/**
 * Nội dung hiển thị trong Hero Section khi người dùng đã đăng nhập:
 * Avatar tròn 96dp có nền ấm đào dịu, viền mỏng thanh lịch và chữ cái Serif nổi bật.
 *
 * @param user Thông tin người dùng hiện tại.
 * @param onAccountClick Callback khi nhấn vào tài khoản.
 */
@Composable
private fun AuthenticatedHeroContent(
    user: User,
    onAccountClick: () -> Unit
) {
    val initial = remember(user.displayName) {
        user.displayName.firstOrNull()?.uppercaseChar()?.toString() ?: "U"
    }

    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        // Avatar tròn với Badge VIP đè lên góc dưới bên phải
        Box(
            modifier = Modifier
                .size(104.dp)
                .clickable(onClick = onAccountClick),
            contentAlignment = Alignment.Center
        ) {
            // Vòng tròn Avatar (Nền ấm đào FFE8DD sắc nét, viền mỏng)
            Box(
                modifier = Modifier
                    .size(96.dp)
                    .clip(CircleShape)
                    .background(Color(0xFFFFE8E0))
                    .align(Alignment.Center),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = initial,
                    color = MaterialTheme.colorScheme.primary,
                    fontSize = 38.sp,
                    fontWeight = FontWeight.Bold,
                    fontFamily = FontFamily.Serif
                )
            }

            // Badge VIP nhỏ gọn màu Terracotta (#D97757) đè lên góc dưới bên phải của Avatar
            Surface(
                shape = CircleShape,
                color = MaterialTheme.colorScheme.primary,
                border = BorderStroke(2.5.dp, MaterialTheme.colorScheme.background),
                shadowElevation = 2.dp,
                modifier = Modifier
                    .align(Alignment.BottomEnd)
                    .offset(x = (-4).dp, y = (-2).dp)
            ) {
                Text(
                    text = stringResource(R.string.profile_vip_badge),
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.5.dp),
                    letterSpacing = 0.8.sp
                )
            }
        }

        Spacer(modifier = Modifier.height(14.dp))

        // Tên người dùng hiển thị đậm với font Serif
        Text(
            text = user.displayName,
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Serif,
            color = MaterialTheme.colorScheme.onBackground,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )

        Spacer(modifier = Modifier.height(4.dp))

        // Email người dùng
        Text(
            text = user.email,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
}

/**
 * Nội dung hiển thị trong Hero Section khi người dùng chưa đăng nhập.
 *
 * @param onLoginClick Callback chuyển đến màn hình Đăng nhập.
 */
@Composable
private fun UnauthenticatedHeroContent(
    onLoginClick: () -> Unit
) {
    Column(
        modifier = Modifier.fillMaxWidth(),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Box(
            modifier = Modifier
                .size(96.dp)
                .clip(CircleShape)
                .background(MaterialTheme.colorScheme.surfaceVariant),
            contentAlignment = Alignment.Center
        ) {
            Icon(
                imageVector = Icons.Default.AccountCircle,
                contentDescription = null,
                modifier = Modifier.size(60.dp),
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }

        Spacer(modifier = Modifier.height(14.dp))

        Text(
            text = stringResource(R.string.auth_profile_guest_title),
            fontSize = 22.sp,
            fontWeight = FontWeight.Bold,
            fontFamily = FontFamily.Serif,
            color = MaterialTheme.colorScheme.onBackground
        )

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = stringResource(R.string.auth_profile_guest_subtitle),
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            textAlign = TextAlign.Center,
            lineHeight = 18.sp
        )

        Spacer(modifier = Modifier.height(16.dp))

        Button(
            onClick = onLoginClick,
            modifier = Modifier
                .fillMaxWidth(0.7f)
                .height(46.dp),
            shape = RoundedCornerShape(24.dp),
            colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.primary)
        ) {
            Icon(
                imageVector = Icons.AutoMirrored.Filled.Login,
                contentDescription = null,
                modifier = Modifier.size(18.dp)
            )
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = stringResource(R.string.auth_profile_btn_login),
                fontSize = 14.sp,
                fontWeight = FontWeight.SemiBold
            )
        }
    }
}

/**
 * Hàng 3 thẻ thống kê chỉ số đọc sách (Data-Focused Stats Row) với bo góc mềm mại 24dp, thẻ Streak ở giữa dùng nền Terracotta nổi bật.
 *
 * @param totalBooks Số lượng sách đã đọc.
 * @param streakDays Số ngày đọc sách liên tục (Streak).
 * @param readHours Tổng số giờ đọc sách.
 * @param modifier Modifier tùy biến bố cục.
 */
@Composable
fun ProfileStatsRow(
    totalBooks: Int,
    streakDays: Int,
    readHours: Double,
    modifier: Modifier = Modifier
) {
    val formattedHours = remember(readHours) {
        String.format(Locale.getDefault(), "%.1f", readHours)
    }

    Row(
        modifier = modifier,
        horizontalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        // Thẻ 1: Sách đã đọc (Nền FFF7F2)
        StatCard(
            modifier = Modifier.weight(1f),
            icon = Icons.AutoMirrored.Filled.MenuBook,
            value = "$totalBooks",
            label = stringResource(R.string.profile_stat_books_title),
            isHighlighted = false
        )

        // Thẻ 2: Streak ngày liên tục (Nền #D97757 nổi bật kèm icon ngọn lửa)
        StatCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Filled.LocalFireDepartment,
            value = "$streakDays",
            label = stringResource(R.string.profile_stat_streak_title),
            isHighlighted = true
        )

        // Thẻ 3: Tổng giờ đọc (Nền FFF7F2)
        StatCard(
            modifier = Modifier.weight(1f),
            icon = Icons.Filled.Schedule,
            value = formattedHours,
            label = stringResource(R.string.profile_stat_hours_title),
            isHighlighted = false
        )
    }
}

/**
 * Một thẻ thống kê đơn lẻ trong hàng chỉ số đọc sách.
 *
 * @param icon Icon biểu trưng cho chỉ số.
 * @param value Giá trị số liệu.
 * @param label Nhãn mô tả số liệu.
 * @param isHighlighted Cờ xác định thẻ có được tô nổi bật với màu Primary (#D97757) hay không.
 * @param modifier Modifier tùy biến bố cục.
 */
@Composable
private fun StatCard(
    icon: ImageVector,
    value: String,
    label: String,
    isHighlighted: Boolean,
    modifier: Modifier = Modifier
) {
    val containerColor = if (isHighlighted) {
        MaterialTheme.colorScheme.primary
    } else {
        Color(0xFFFFF7F2)
    }

    val contentColor = if (isHighlighted) {
        MaterialTheme.colorScheme.onPrimary
    } else {
        MaterialTheme.colorScheme.onSurface
    }

    val labelColor = if (isHighlighted) {
        MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.95f)
    } else {
        MaterialTheme.colorScheme.onSurfaceVariant
    }

    Card(
        modifier = modifier,
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = containerColor),
        elevation = CardDefaults.cardElevation(defaultElevation = if (isHighlighted) 2.dp else 0.dp)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 20.dp, horizontal = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Icon(
                imageVector = icon,
                contentDescription = null,
                tint = if (isHighlighted) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.primary,
                modifier = Modifier.size(28.dp)
            )

            Spacer(modifier = Modifier.height(8.dp))

            Text(
                text = value,
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
                color = contentColor
            )

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = label,
                fontSize = 12.sp,
                fontWeight = FontWeight.Medium,
                color = labelColor,
                textAlign = TextAlign.Center,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
                lineHeight = 16.sp
            )
        }
    }
}

/**
 * Thẻ bao bọc một nhóm mục cài đặt (Card-based Section Container) với tiêu đề nhóm in hoa trang nhã.
 *
 * @param title Tiêu đề của nhóm cài đặt.
 * @param modifier Modifier tùy biến bố cục.
 * @param content Danh sách các mục cài đặt nằm trong nhóm.
 */
@Composable
fun ProfileSectionCard(
    title: String,
    modifier: Modifier = Modifier,
    content: @Composable ColumnScope.() -> Unit
) {
    Column(modifier = modifier) {
        Text(
            text = title.uppercase(Locale.getDefault()),
            fontSize = 12.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            letterSpacing = 1.sp,
            modifier = Modifier
                .padding(start = 16.dp, bottom = 8.dp)
                .semantics { heading() }
        )

        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(24.dp),
            colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
            border = BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant.copy(alpha = 0.4f)),
            elevation = CardDefaults.cardElevation(defaultElevation = 0.5.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                content()
            }
        }
    }
}

/**
 * Một mục cài đặt nằm trong thẻ nhóm (Profile Card Item).
 *
 * @param title Tiêu đề mục cài đặt.
 * @param subtitle Mô tả phụ của mục cài đặt.
 * @param icon Icon biểu diễn.
 * @param onClick Callback khi người dùng nhấn vào mục.
 * @param enabled Trạng thái khả dụng của mục.
 * @param isDestructive Đánh dấu mục có hành động nguy hiểm (ví dụ Đăng xuất) để hiển thị màu đỏ.
 */
@Composable
fun ProfileCardItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    onClick: () -> Unit,
    enabled: Boolean = true,
    isDestructive: Boolean = false
) {
    val titleColor = when {
        isDestructive -> MaterialTheme.colorScheme.error
        enabled -> MaterialTheme.colorScheme.onSurface
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    }

    val iconColor = when {
        isDestructive -> MaterialTheme.colorScheme.error
        enabled -> MaterialTheme.colorScheme.primary
        else -> MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    }

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .semantics(mergeDescendants = true) {
                role = Role.Button
                if (!enabled) disabled()
            }
            .clickable(enabled = enabled, role = Role.Button, onClick = onClick)
            .padding(horizontal = 18.dp, vertical = 15.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = titleColor
            )
            if (subtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        if (enabled) {
            Icon(
                imageVector = Icons.Default.ChevronRight,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.5f),
                modifier = Modifier.size(20.dp)
            )
        } else {
            Surface(
                shape = RoundedCornerShape(12.dp),
                color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.8f)
            ) {
                Text(
                    text = stringResource(R.string.profile_coming_soon),
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                )
            }
        }
    }
}

/**
 * Hộp thoại xác nhận đăng xuất khỏi tài khoản.
 *
 * @param userName Tên người dùng đang đăng nhập.
 * @param onDismiss Callback khi người dùng hủy.
 * @param onConfirm Callback khi người dùng xác nhận đăng xuất.
 */
@Composable
private fun LogoutConfirmDialog(
    userName: String,
    onDismiss: () -> Unit,
    onConfirm: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.auth_logout_title),
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif
            )
        },
        text = {
            Text(
                text = stringResource(R.string.auth_logout_confirm_msg, userName),
                fontSize = 14.sp,
                lineHeight = 20.sp
            )
        },
        confirmButton = {
            Button(
                onClick = onConfirm,
                colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
            ) {
                Text(
                    text = stringResource(R.string.auth_btn_logout),
                    color = MaterialTheme.colorScheme.onError
                )
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(text = stringResource(R.string.action_cancel))
            }
        }
    )
}

/**
 * Hộp thoại hiển thị chi tiết hồ sơ tài khoản người dùng và trạng thái phiên đăng nhập.
 *
 * @param user Thông tin người dùng hiện tại.
 * @param onDismiss Callback đóng hộp thoại.
 */
@Composable
private fun AccountDetailDialog(
    user: User,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text(
                text = stringResource(R.string.auth_profile_edit_account_title),
                fontWeight = FontWeight.Bold,
                fontFamily = FontFamily.Serif,
                fontSize = 18.sp
            )
        },
        text = {
            Column(modifier = Modifier.fillMaxWidth()) {
                AccountInfoRow(
                    label = stringResource(R.string.auth_account_detail_name),
                    value = user.displayName
                )
                Spacer(modifier = Modifier.height(10.dp))
                AccountInfoRow(
                    label = stringResource(R.string.auth_account_detail_email),
                    value = user.email
                )
                Spacer(modifier = Modifier.height(10.dp))
                AccountInfoRow(
                    label = stringResource(R.string.auth_account_detail_id),
                    value = user.id.take(16) + "..."
                )
                Spacer(modifier = Modifier.height(10.dp))
                AccountInfoRow(
                    label = stringResource(R.string.auth_account_detail_provider),
                    value = user.provider.name
                )
                Spacer(modifier = Modifier.height(10.dp))
                AccountInfoRow(
                    label = stringResource(R.string.auth_account_detail_tier),
                    value = user.membershipTier.ifBlank { stringResource(R.string.profile_vip_badge) }
                )
                Spacer(modifier = Modifier.height(10.dp))
                AccountInfoRow(
                    label = stringResource(R.string.auth_account_detail_token_status),
                    value = stringResource(R.string.auth_account_detail_token_valid)
                )
            }
        },
        confirmButton = {
            Button(onClick = onDismiss) {
                Text(stringResource(R.string.action_close))
            }
        }
    )
}

/**
 * Một hàng hiển thị nhãn và giá trị thông tin trong hộp thoại tài khoản.
 *
 * @param label Tên trường thông tin.
 * @param value Giá trị hiển thị.
 */
@Composable
private fun AccountInfoRow(
    label: String,
    value: String
) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            text = label,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Text(
            text = value,
            fontSize = 13.sp,
            fontWeight = FontWeight.Medium,
            color = MaterialTheme.colorScheme.onSurface
        )
    }
}

/**
 * Một mục cài đặt dạng Switch có nhãn, phụ đề, biểu tượng và công tắc bật/tắt.
 *
 * @param title Tiêu đề của mục cài đặt.
 * @param subtitle Mô tả phụ giải thích ý nghĩa tính năng.
 * @param icon Biểu tượng minh họa.
 * @param checked Trạng thái bật/tắt hiện tại của công tắc.
 * @param onCheckedChange Callback khi người dùng thay đổi trạng thái công tắc.
 * @param enabled Trạng thái khả dụng của mục cài đặt.
 */
@Composable
fun ProfileSwitchItem(
    title: String,
    subtitle: String,
    icon: ImageVector,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit,
    enabled: Boolean = true
) {
    val titleColor = if (enabled) MaterialTheme.colorScheme.onSurface else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
    val iconColor = if (enabled) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)

    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(enabled = enabled, onClick = { onCheckedChange(!checked) })
            .padding(horizontal = 18.dp, vertical = 13.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Icon(
            imageVector = icon,
            contentDescription = null,
            tint = iconColor,
            modifier = Modifier.size(24.dp)
        )

        Spacer(modifier = Modifier.width(14.dp))

        Column(modifier = Modifier.weight(1f)) {
            Text(
                text = title,
                fontSize = 15.sp,
                fontWeight = FontWeight.Medium,
                color = titleColor
            )
            if (subtitle.isNotBlank()) {
                Spacer(modifier = Modifier.height(2.dp))
                Text(
                    text = subtitle,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    lineHeight = 16.sp
                )
            }
        }

        Spacer(modifier = Modifier.width(8.dp))

        Switch(
            checked = checked,
            onCheckedChange = onCheckedChange,
            enabled = enabled
        )
    }
}
