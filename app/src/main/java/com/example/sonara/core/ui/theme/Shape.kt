package com.example.sonara.core.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Restrained shape scale for Sonara Android as frozen in DESIGN_SYSTEM_ANDROID.md §5.
 */
@Immutable
data class SonaraShapes(
    val small: Shape = RoundedCornerShape(8.dp),
    val medium: Shape = RoundedCornerShape(12.dp),
    val large: Shape = RoundedCornerShape(16.dp),
    val sheet: Shape = RoundedCornerShape(topStart = 24.dp, topEnd = 24.dp),
    val circular: Shape = CircleShape
) {
    /**
     * Maps SonaraShapes to Material 3 Shapes.
     */
    fun toMaterialShapes(): Shapes {
        return Shapes(
            small = small as RoundedCornerShape,
            medium = medium as RoundedCornerShape,
            large = large as RoundedCornerShape,
            extraLarge = sheet as RoundedCornerShape
        )
    }
}

val DefaultSonaraShapes = SonaraShapes()
val LocalSonaraShapes = staticCompositionLocalOf { DefaultSonaraShapes }
