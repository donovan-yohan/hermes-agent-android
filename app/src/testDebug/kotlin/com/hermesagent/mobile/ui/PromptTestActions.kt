package com.hermesagent.mobile.ui

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.SemanticsMatcher
import androidx.compose.ui.test.SemanticsNodeInteraction
import androidx.compose.ui.test.hasContentDescription
import androidx.compose.ui.test.hasTestTag

import androidx.compose.ui.test.junit4.ComposeContentTestRule
import androidx.compose.ui.test.performClick
import androidx.compose.ui.test.performCustomAccessibilityActionWithLabel

/** Historical pin journeys now locate the single You said owner of the final prompt. */
internal fun promptContentDescription(label: String): SemanticsMatcher = when {
    label.startsWith("Current prompt: ") -> hasContentDescription(label) or
        (hasContentDescription(label.replaceFirst("Current prompt: ", "You said: ")) and
            hasTestTag("Pinned final prompt"))
    label.startsWith("You said: ") -> hasContentDescription(label) and
        !hasTestTag("Pinned final prompt")
    else -> hasContentDescription(label)
}

internal fun ComposeContentTestRule.promptNodeWithContentDescription(label: String, useUnmergedTree: Boolean = false) =
    onNode(promptContentDescription(label), useUnmergedTree)

/** Return remains a separate accessibility action; disclosure no longer steals its tap. */
@OptIn(androidx.compose.ui.test.ExperimentalTestApi::class)
internal fun SemanticsNodeInteraction.performPromptJourneyClick(): SemanticsNodeInteraction {
    val actions = fetchSemanticsNode().config.getOrElse(SemanticsActions.CustomActions) { emptyList() }
    val returnAction = actions.firstOrNull { it.label == "Return to prompt" }
    if (returnAction == null) performClick() else performCustomAccessibilityActionWithLabel("Return to prompt")
    return this
}
