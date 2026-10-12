package com.example.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.navigationBarsPadding
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.BarChart
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Deselect
import androidx.compose.material.icons.filled.Label
import androidx.compose.material.icons.filled.SelectAll
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.rememberModalBottomSheetState
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.data.local.entities.TagEntity
import com.example.ui.theme.BorderLavender
import com.example.ui.theme.PrimaryNeon
import com.example.ui.theme.SurfaceDark
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary

/**
 * Bulk Selection Mode UI (`bulk-select-v1`, Worker 2).
 *
 * Pure UI over [MainViewModel]'s frozen selection APIs: the screen hoists
 * `selectionMode`/`selectedIds` from the VM and owns the sheets/dialog below.
 * No repository, DAO, sync, HLC or selection-persistence code lives here.
 */

/**
 * The Add/Remove candidates for the bulk tag sheet, computed per open. CR-KT-008: candidates are
 * tag IDS (what `task.tags` must hold); an orphan token (no tag row) stays as its raw token.
 * Chip labels resolve through [label].
 */
data class BulkTagModel(
    val addCandidates: List<String>,
    val removeCandidates: List<String>,
    val labels: Map<String, String> = emptyMap(),
) {
    fun label(token: String): String = labels[token] ?: token
}

/**
 * Splits the tag vocabulary for the bulk tag sheet from the selected tasks' raw
 * `tags` (each entry may be a tag id or a legacy tag name, as in [TagFilter.kt]):
 * - Add: the id of every known tag NOT present (in either spelling) on every selected task.
 * - Remove: every tag present on at least one selected task -- its id when known, else the
 *   orphan token -- sorted by label.
 */
fun bulkTagModel(
    selectedTags: List<List<String>>,
    knownTags: List<TagEntity>,
): BulkTagModel {
    if (selectedTags.isEmpty()) return BulkTagModel(emptyList(), emptyList())
    val nameById = tagNameIndex(knownTags)
    val idByName = tagIdIndex(knownTags)
    val canon = selectedTags.map { tags -> tags.map { canonicalTagId(it, idByName) } }
    val add = knownTags.map { it.id }.filter { id -> canon.any { id !in it } }
    val remove = canon.flatten().distinct().filter { it.isNotEmpty() }
        .sortedBy { canonicalTagName(it, nameById) }
    return BulkTagModel(add, remove, nameById)
}

/**
 * The contextual bar that replaces the task-list header while `selectionMode` is true:
 * "{n} selected" with a Select-all/Deselect-all toggle and a close X.
 */
@Composable
fun SelectionContextBar(
    selectedCount: Int,
    allVisibleSelected: Boolean,
    onToggleSelectAll: () -> Unit,
    onClose: () -> Unit,
    modifier: Modifier = Modifier,
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .testTag("selection_bar")
            .padding(vertical = 4.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            "$selectedCount selected",
            fontSize = 16.sp,
            fontWeight = FontWeight.Bold,
            color = PrimaryNeon,
            modifier = Modifier.weight(1f).testTag("selection_title"),
        )
        IconButton(
            onClick = onToggleSelectAll,
            modifier = Modifier.testTag("selection_toggle_all"),
        ) {
            Icon(
                if (allVisibleSelected) Icons.Default.Deselect else Icons.Default.SelectAll,
                contentDescription = if (allVisibleSelected) "Deselect all" else "Select all",
                tint = PrimaryNeon,
            )
        }
        IconButton(
            onClick = onClose,
            modifier = Modifier.testTag("selection_close"),
        ) {
            Icon(Icons.Default.Close, contentDescription = "Exit selection mode", tint = TextPrimary)
        }
    }
}

/**
 * The bottom action bar: exactly three actions (Tags, Difficulty, Delete),
 * icon + text label each, disabled while nothing is selected.
 */
@Composable
fun BulkActionBar(
    selectedCount: Int,
    onTags: () -> Unit,
    onDifficulty: () -> Unit,
    onDelete: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val enabled = selectedCount > 0
    Surface(
        tonalElevation = 4.dp,
        shadowElevation = 8.dp,
        color = SurfaceDark,
        shape = RoundedCornerShape(16.dp),
        modifier = modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 8.dp)
            .testTag("bulk_bar"),
    ) {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp),
            horizontalArrangement = Arrangement.SpaceEvenly,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            BulkBarAction(
                icon = { Icon(Icons.Default.Label, contentDescription = null, tint = if (enabled) PrimaryNeon else TextSecondary) },
                label = "Tags",
                enabled = enabled,
                onClick = onTags,
                testTag = "bulk_tags",
            )
            BulkBarAction(
                icon = { Icon(Icons.Default.BarChart, contentDescription = null, tint = if (enabled) PrimaryNeon else TextSecondary) },
                label = "Difficulty",
                enabled = enabled,
                onClick = onDifficulty,
                testTag = "bulk_difficulty",
            )
            BulkBarAction(
                icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = if (enabled) PrimaryNeon else TextSecondary) },
                label = "Delete",
                enabled = enabled,
                onClick = onDelete,
                testTag = "bulk_delete",
            )
        }
    }
}

@Composable
private fun BulkBarAction(
    icon: @Composable () -> Unit,
    label: String,
    enabled: Boolean,
    onClick: () -> Unit,
    testTag: String,
) {
    TextButton(
        onClick = onClick,
        enabled = enabled,
        modifier = Modifier.testTag(testTag),
    ) {
        Column(horizontalAlignment = Alignment.CenterHorizontally) {
            icon()
            Text(label, fontSize = 12.sp, color = if (enabled) TextPrimary else TextSecondary)
        }
    }
}

/**
 * The bulk tag sheet. Sections are computed per open from the selected tasks' current
 * tag lists ([bulkTagModel]); chips toggle in both sections and Apply calls
 * `bulkEditTags(add, remove)` with the toggled sets.
 */
@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun BulkTagSheet(
    knownTags: List<TagEntity>,
    selectedTagLists: List<List<String>>,
    onApply: (add: Set<String>, remove: Set<String>) -> Unit,
    onDismiss: () -> Unit,
) {
    val model = remember(knownTags, selectedTagLists) { bulkTagModel(selectedTagLists, knownTags) }
    var addChosen by remember(knownTags, selectedTagLists) { mutableStateOf(emptySet<String>()) }
    var removeChosen by remember(knownTags, selectedTagLists) { mutableStateOf(emptySet<String>()) }
    val colorById = remember(knownTags) {
        knownTags.associate { it.id to safeTagColor(it.color) }
    }
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)

    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        contentColor = TextPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .verticalScroll(rememberScrollState())
                .testTag("bulk_tag_sheet"),
            verticalArrangement = Arrangement.spacedBy(12.dp),
        ) {
            Text("Bulk edit tags", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PrimaryNeon)
            Text(
                "+${addChosen.size}  \u2212${removeChosen.size}",
                fontSize = 12.sp,
                color = TextSecondary,
                modifier = Modifier.testTag("bulk_tag_summary"),
            )
            Text("Add", fontSize = 13.sp, color = TextSecondary)
            if (model.addCandidates.isEmpty()) {
                Text("Every selected task already has all tags.", fontSize = 12.sp, color = TextSecondary)
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    model.addCandidates.forEach { id ->
                        val name = model.label(id)
                        BulkTagToggleChip(
                            name = name,
                            color = colorById[id] ?: TagColorFallback,
                            checked = id in addChosen,
                            onToggle = { addChosen = if (id in addChosen) addChosen - id else addChosen + id },
                            testTag = "bulk_tag_add_$name",
                        )
                    }
                }
            }
            Text("Remove", fontSize = 13.sp, color = TextSecondary)
            if (model.removeCandidates.isEmpty()) {
                Text("No tags on the selected tasks.", fontSize = 12.sp, color = TextSecondary)
            } else {
                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp),
                ) {
                    model.removeCandidates.forEach { id ->
                        val name = model.label(id)
                        BulkTagToggleChip(
                            name = name,
                            color = colorById[id] ?: TagColorFallback,
                            checked = id in removeChosen,
                            onToggle = { removeChosen = if (id in removeChosen) removeChosen - id else removeChosen + id },
                            testTag = "bulk_tag_remove_$name",
                        )
                    }
                }
            }
            Spacer(Modifier.height(4.dp))
            TextButton(
                onClick = { onApply(addChosen, removeChosen) },
                enabled = addChosen.isNotEmpty() || removeChosen.isNotEmpty(),
                modifier = Modifier.align(Alignment.End).testTag("bulk_tag_apply"),
            ) {
                Text("Apply", fontSize = 14.sp, fontWeight = FontWeight.Bold, color = PrimaryNeon)
            }
        }
    }
}

/** A toggle chip in the bulk tag sheet, themed like the edit sheet's tag rows. */
@Composable
private fun BulkTagToggleChip(
    name: String,
    color: androidx.compose.ui.graphics.Color,
    checked: Boolean,
    onToggle: () -> Unit,
    testTag: String,
) {
    val border = if (checked) color else BorderLavender
    Row(
        modifier = Modifier
            .background(
                if (checked) color.copy(alpha = 0.3f) else SurfaceDark,
                RoundedCornerShape(20.dp),
            )
            .border(1.dp, border, RoundedCornerShape(20.dp))
            .clickable(onClick = onToggle)
            .padding(horizontal = 12.dp, vertical = 6.dp)
            .testTag(testTag),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Text(
            name,
            fontSize = 12.sp,
            fontWeight = if (checked) FontWeight.Bold else FontWeight.Normal,
            color = if (checked) TextPrimary else TextSecondary,
        )
    }
}

/** The bulk difficulty sheet: 4 rows; a tap calls `bulkSetDifficulty(value)` and closes. */
@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun BulkDifficultySheet(
    onPick: (value: String) -> Unit,
    onDismiss: () -> Unit,
) {
    val sheetState = rememberModalBottomSheetState(skipPartiallyExpanded = true)
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        sheetState = sheetState,
        containerColor = SurfaceDark,
        contentColor = TextPrimary,
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .navigationBarsPadding()
                .padding(horizontal = 20.dp, vertical = 10.dp)
                .testTag("bulk_diff_sheet"),
            verticalArrangement = Arrangement.spacedBy(4.dp),
        ) {
            Text("Set difficulty", fontSize = 20.sp, fontWeight = FontWeight.Bold, color = PrimaryNeon)
            listOf("trivial" to "Trivial", "easy" to "Easy", "medium" to "Medium", "hard" to "Hard").forEach { (value, label) ->
                TextButton(
                    onClick = { onPick(value) },
                    modifier = Modifier.fillMaxWidth().testTag("bulk_diff_$value"),
                ) {
                    Text(
                        label,
                        fontSize = 15.sp,
                        color = TextPrimary,
                        modifier = Modifier.fillMaxWidth(),
                    )
                }
            }
            Spacer(Modifier.height(8.dp))
        }
    }
}

/** The bulk delete confirmation: "Delete {n} tasks? This change syncs." */
@Composable
fun BulkDeleteDialog(
    count: Int,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        modifier = Modifier.testTag("bulk_delete_dialog"),
        containerColor = SurfaceDark,
        titleContentColor = TextPrimary,
        textContentColor = TextPrimary,
        title = { Text("Delete $count tasks?") },
        text = { Text("Delete $count tasks? This change syncs.") },
        confirmButton = {
            TextButton(
                onClick = onConfirm,
                modifier = Modifier.testTag("bulk_delete_confirm"),
            ) {
                Text("Delete", color = PrimaryNeon, fontWeight = FontWeight.Bold)
            }
        },
        dismissButton = {
            TextButton(
                onClick = onDismiss,
                modifier = Modifier.testTag("bulk_delete_cancel"),
            ) {
                Text("Cancel", color = TextSecondary)
            }
        },
    )
}
