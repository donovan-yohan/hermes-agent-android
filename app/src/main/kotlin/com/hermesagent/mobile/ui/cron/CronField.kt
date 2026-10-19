package com.hermesagent.mobile.ui.cron

import androidx.compose.foundation.border
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.input.KeyboardCapitalization
import androidx.compose.ui.unit.dp
import com.hermesagent.mobile.ui.common.SectionLabel
import com.hermesagent.mobile.ui.theme.HermesTheme

/** Raw value belongs only to the secure, non-saveable editor; labels are redacted separately. */
@Composable internal fun CronField(label: String, value: String, change: (String) -> Unit, tag: String = "", multiline: Boolean = false, enabled: Boolean = true) {
    Column(verticalArrangement = Arrangement.spacedBy(4.dp)) {
        SectionLabel(safe(label))
        BasicTextField(value, change,
            enabled = enabled, singleLine = !multiline,
            textStyle = HermesTheme.type.body.copy(color = HermesTheme.tokens.textPrimary),
            cursorBrush = SolidColor(HermesTheme.tokens.accent),
            keyboardOptions = KeyboardOptions(capitalization = KeyboardCapitalization.None, autoCorrectEnabled = false),
            modifier = Modifier.fillMaxWidth().heightIn(min = if (multiline) 96.dp else 48.dp).testTag(tag).semantics { contentDescription = safe(label) },
            decorationBox = { inner -> Box(Modifier.fillMaxWidth().border(1.dp, HermesTheme.tokens.strokeSecondary, RoundedCornerShape(8.dp)).padding(12.dp), contentAlignment = Alignment.TopStart) { inner() } },
        )
    }
}
