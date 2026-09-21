package com.alananasss.kittytune.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.rounded.GraphicEq
import androidx.compose.material.icons.rounded.PlayArrow
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.FilledIconButton
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.unit.dp
import com.alananasss.kittytune.R

/**
 * Asks the listener what they want to hear and turns the answer into a continuous mix.
 *
 * Sits at the top of the home screen because it replaces browsing rather than supplementing it:
 * someone who knows they want techno right now should not have to find techno first and then
 * decide how to play it. What comes back is a mix, not a playlist - the tracks are ordered so
 * the automix engine can beat-match between them, and it runs for the set.
 */
@Composable
fun RemixPromptCard(
    prompt: String,
    onPromptChange: (String) -> Unit,
    loading: Boolean,
    empty: Boolean,
    understoodAs: String? = null,
    onStart: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val keyboard = LocalSoftwareKeyboardController.current

    // Offered because a blank field asks the listener to invent a vocabulary. These are genres
    // SoundCloud tags heavily, so they are also the ones most likely to return a full set.
    val suggestions = listOf("Techno", "House", "Drum & Bass", "Hip Hop", "Lo-Fi", "Trance")

    Surface(
        shape = RoundedCornerShape(24.dp),
        color = MaterialTheme.colorScheme.surfaceContainer,
        modifier = modifier.fillMaxWidth(),
    ) {
        Column(modifier = Modifier.padding(20.dp)) {
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                Icon(
                    imageVector = Icons.Rounded.GraphicEq,
                    contentDescription = null,
                    tint = MaterialTheme.colorScheme.primary,
                    modifier = Modifier.size(20.dp),
                )
                Text(
                    text = stringResource(R.string.remix_prompt_title),
                    style = MaterialTheme.typography.titleMedium,
                    fontWeight = FontWeight.Bold,
                )
            }

            Text(
                text = stringResource(R.string.remix_prompt_subtitle),
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = 4.dp),
            )

            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(top = 14.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(10.dp),
            ) {
                OutlinedTextField(
                    value = prompt,
                    onValueChange = onPromptChange,
                    singleLine = true,
                    placeholder = { Text(stringResource(R.string.remix_prompt_hint)) },
                    shape = RoundedCornerShape(16.dp),
                    keyboardOptions = KeyboardOptions(imeAction = ImeAction.Go),
                    keyboardActions = KeyboardActions(onGo = {
                        keyboard?.hide()
                        onStart()
                    }),
                    modifier = Modifier.weight(1f),
                )
                FilledIconButton(
                    onClick = {
                        keyboard?.hide()
                        onStart()
                    },
                    enabled = prompt.isNotBlank() && !loading,
                ) {
                    if (loading) {
                        CircularProgressIndicator(
                            strokeWidth = 2.dp,
                            modifier = Modifier.size(18.dp),
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Icon(Icons.Rounded.PlayArrow, contentDescription = null)
                    }
                }
            }

            // Shown because a reading can be wrong, and a listener who can see what the app
            // understood can correct it in one word instead of wondering why techno is playing.
            if (understoodAs != null && !loading && !empty) {
                Text(
                    text = stringResource(R.string.remix_prompt_understood, understoodAs),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            if (empty) {
                Text(
                    text = stringResource(R.string.remix_prompt_empty),
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                    modifier = Modifier.padding(top = 8.dp),
                )
            }

            LazyRow(
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                modifier = Modifier.padding(top = 12.dp),
            ) {
                items(count = suggestions.size) { index ->
                    val label = suggestions[index]
                    SuggestionChip(
                        onClick = {
                            onPromptChange(label)
                            keyboard?.hide()
                            onStart()
                        },
                        label = { Text(label) },
                    )
                }
            }
        }
    }
}
