package com.epubpro.feature.library.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Bookmark
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.semantics.clearAndSetSemantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.epubpro.core.designsystem.cover.GeneratedCoverPalette
import java.util.Locale

@Composable
fun GeneratedBookCover(
    title: String,
    author: String,
    modifier: Modifier = Modifier
) {
    val gradientColors = remember(title) {
        getCoverGradient(title)
    }

    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(180.dp)
            .background(Brush.verticalGradient(gradientColors))
            .clearAndSetSemantics { },
        contentAlignment = Alignment.Center
    ) {
        Icon(
            imageVector = Icons.Default.Bookmark,
            contentDescription = null,
            modifier = Modifier
                .align(Alignment.TopStart)
                .padding(12.dp)
                .size(20.dp),
            tint = Color.White.copy(alpha = 0.7f)
        )

        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleMedium.copy(
                    fontWeight = FontWeight.ExtraBold,
                    fontSize = 16.sp,
                    letterSpacing = (-0.3).sp
                ),
                color = Color.White,
                textAlign = TextAlign.Center,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis
            )

            if (author.isNotBlank() && !author.equals("Unknown Author", ignoreCase = true)) {
                Spacer(modifier = Modifier.height(8.dp))
                Text(
                    text = author.uppercase(Locale.getDefault()),
                    style = MaterialTheme.typography.labelSmall.copy(
                        fontWeight = FontWeight.Medium,
                        letterSpacing = 1.2.sp,
                        fontSize = 10.sp
                    ),
                    color = Color.White.copy(alpha = 0.85f),
                    textAlign = TextAlign.Center,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis
                )
            }
        }
    }
}

/**
 * Lấy gradient bìa tự sinh theo tiêu đề từ bảng màu dùng chung.
 *
 * @param title Tiêu đề sách.
 * @return Danh sách hai màu (trên, dưới) cho `Brush.verticalGradient`.
 */
private fun getCoverGradient(title: String): List<Color> {
    val (top, bottom) = GeneratedCoverPalette.gradientFor(title)
    return listOf(Color(top), Color(bottom))
}
