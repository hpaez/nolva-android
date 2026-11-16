package app.nolva.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Shapes
import androidx.compose.ui.unit.dp

/** Brand radii: 6 (controls) / 14 (cards) / 24 (sheets) / pill. */
object NolvaRadius {
    val small = 6.dp
    val medium = 14.dp
    val large = 24.dp
}

internal val NolvaShapes = Shapes(
    extraSmall = RoundedCornerShape(NolvaRadius.small),
    small = RoundedCornerShape(NolvaRadius.small),
    medium = RoundedCornerShape(NolvaRadius.medium),
    large = RoundedCornerShape(NolvaRadius.large),
    extraLarge = RoundedCornerShape(NolvaRadius.large),
)
