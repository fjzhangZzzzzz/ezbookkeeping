package top.fjzhang233.books

import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.indication
import androidx.compose.foundation.interaction.InteractionSource
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.sizeIn
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material3.ripple
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp

@Composable
internal fun LedgerControl(
    modifier: Modifier = Modifier,
    selected: Boolean? = null,
    enabled: Boolean = true,
    role: Role = if (selected == null) Role.Button else Role.Tab,
    onClick: () -> Unit,
    onLongClick: (() -> Unit)? = null,
    onLongClickLabel: String? = null,
    content: @Composable BoxScope.(InteractionSource) -> Unit,
) {
    val interactions = remember { MutableInteractionSource() }
    val input = if (onLongClick != null) {
        Modifier.combinedClickable(
            interactionSource = interactions, indication = null, enabled = enabled, role = role,
            onLongClick = onLongClick, onLongClickLabel = onLongClickLabel,
            hapticFeedbackEnabled = false, onClick = onClick,
        )
    } else if (selected == null) {
        Modifier.clickable(interactions, indication = null, enabled = enabled, role = role, onClick = onClick)
    } else {
        Modifier.selectable(selected, interactions, indication = null, enabled = enabled, role = role, onClick = onClick)
    }
    Box(modifier.sizeIn(minWidth = 48.dp, minHeight = 48.dp).then(input), contentAlignment = Alignment.Center) {
        content(interactions)
    }
}

// Start at the visual center because the pointer position belongs to the outer touch target.
private val controlRipple = ripple(bounded = false)

@Composable
internal fun Modifier.ledgerControlFocus(interactions: InteractionSource, shape: Shape = CircleShape): Modifier {
    val focused by interactions.collectIsFocusedAsState()
    return if (focused) border(2.dp, ledgerColors.accent, shape) else this
}

@Composable
internal fun Modifier.ledgerControlFeedback(interactions: InteractionSource, shape: Shape = CircleShape): Modifier =
    clip(shape).indication(interactions, controlRipple).ledgerControlFocus(interactions, shape)
