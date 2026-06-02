package com.dschat.app.ui.theme

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp

/** Minimal monochrome brand mark: an ink rounded square with a single "T" glyph. */
@Composable
fun BrandMark(size: Int = 32) {
    Box(
        modifier = Modifier
            .size(size.dp)
            .clip(RoundedCornerShape((size * 0.28f).dp))
            .background(MaterialTheme.colorScheme.onBackground),
        contentAlignment = Alignment.Center
    ) {
        Text(
            text = "T",
            color = MaterialTheme.colorScheme.background,
            fontSize = (size * 0.5f).sp,
            fontWeight = FontWeight.Bold
        )
    }
}

/** Bold monochrome wordmark. */
@Composable
fun BrandWordmark(fontSize: Int = 22) {
    Text(
        text = "Tang",
        fontSize = fontSize.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onBackground
    )
}
