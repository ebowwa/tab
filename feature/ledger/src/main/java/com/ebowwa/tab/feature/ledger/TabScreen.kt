package com.ebowwa.tab.feature.ledger

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.SnackbarHost
import androidx.compose.material3.SnackbarHostState
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import com.ebowwa.tab.core.agent.GeminiRestEngine
import com.ebowwa.tab.core.agent.ModelStatus
import com.ebowwa.tab.core.model.Change
import com.ebowwa.tab.core.model.Expense
import com.ebowwa.tab.core.model.balances
import com.ebowwa.tab.core.model.money
import com.ebowwa.tab.core.model.totalCents

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TabScreen(vm: TabViewModel) {
    val state by vm.state.collectAsStateWithLifecycle()
    val snackbar = remember { SnackbarHostState() }
    var input by remember { mutableStateOf("") }

    Scaffold(
        snackbarHost = { SnackbarHost(snackbar) },
        topBar = {
            TopAppBar(
                title = {
                    Column {
                        Text("Tab", fontWeight = FontWeight.SemiBold)
                        if (state.engineLabel.isNotBlank()) {
                            Text(
                                text = state.engineLabel,
                                style = MaterialTheme.typography.labelSmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant,
                            )
                        }
                    }
                },
                actions = {
                    if (state.canUndo) {
                        TextButton(onClick = { vm.undo() }) { Text("Undo") }
                    }
                },
            )
        },
    ) { padding ->
        Column(
            modifier = Modifier
                .padding(padding)
                .fillMaxSize()
                .padding(horizontal = 16.dp),
        ) {
            TotalRow(total = state.ledger.totalCents(), count = state.ledger.expenses.size)
            Spacer(Modifier.height(12.dp))
            BalanceRow(state.ledger.balances())
            Spacer(Modifier.height(12.dp))

            if (!state.nanoAvailable) {
                ModelPanel(
                    status = state.modelStatus,
                    apiKeyPresent = state.apiKeyPresent,
                    progress = state.downloadProgress,
                    onPrepare = vm::prepareModel,
                    onSaveKey = vm::saveApiKey,
                )
            }

            Box(Modifier.weight(1f).fillMaxWidth()) {
                if (state.ledger.expenses.isEmpty()) {
                    Text(
                        text = "No expenses yet.\nAsk below, or load the sample tab.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        textAlign = TextAlign.Center,
                        modifier = Modifier.align(Alignment.Center),
                    )
                } else {
                    LazyColumn(Modifier.fillMaxSize()) {
                        items(state.ledger.expenses, key = { it.id }) { expense ->
                            ExpenseRow(expense)
                            HorizontalDivider(color = MaterialTheme.colorScheme.outlineVariant)
                        }
                    }
                }
            }

            if (state.pending.isNotEmpty() || state.pendingReply.isNotEmpty() || state.error != null) {
                PendingCard(
                    reply = state.pendingReply,
                    changes = state.pending,
                    notes = state.pendingNotes,
                    error = state.error,
                    busy = state.busy,
                    onApply = vm::applyPending,
                    onDiscard = vm::discardPending,
                )
            }

            if (state.ledger.expenses.isEmpty() && state.pending.isEmpty()) {
                TextButton(onClick = vm::seedDemoData) { Text("Load sample tab") }
            }

            Row(
                modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                OutlinedTextField(
                    value = input,
                    onValueChange = { input = it },
                    modifier = Modifier.weight(1f),
                    placeholder = { Text("Ask Tab…") },
                    singleLine = true,
                    shape = RoundedCornerShape(24.dp),
                    enabled = !state.busy,
                )
                Spacer(Modifier.width(8.dp))
                Button(
                    onClick = {
                        val text = input
                        input = ""
                        vm.ask(text)
                    },
                    enabled = !state.busy && input.isNotBlank(),
                ) {
                    if (state.busy) {
                        CircularProgressIndicator(
                            modifier = Modifier.size(18.dp),
                            strokeWidth = 2.dp,
                            color = MaterialTheme.colorScheme.onPrimary,
                        )
                    } else {
                        Text("Ask")
                    }
                }
            }
        }
    }
}

@Composable
private fun TotalRow(total: Long, count: Int) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(top = 8.dp),
        verticalAlignment = Alignment.Bottom,
    ) {
        Text(
            text = total.money(),
            style = MaterialTheme.typography.headlineMedium,
            fontWeight = FontWeight.Bold,
        )
        Spacer(Modifier.width(8.dp))
        Text(
            text = if (count == 1) "across 1 expense" else "across $count expenses",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(bottom = 4.dp),
        )
    }
}

@Composable
private fun BalanceRow(balances: Map<String, Long>) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        balances.forEach { (name, net) ->
            Column(
                modifier = Modifier
                    .weight(1f)
                    .background(
                        color = MaterialTheme.colorScheme.surfaceVariant,
                        shape = RoundedCornerShape(12.dp),
                    )
                    .padding(vertical = 8.dp, horizontal = 4.dp),
                horizontalAlignment = Alignment.CenterHorizontally,
            ) {
                Text(name, style = MaterialTheme.typography.labelSmall, maxLines = 1)
                Text(
                    text = net.money(),
                    style = MaterialTheme.typography.labelMedium,
                    fontWeight = FontWeight.SemiBold,
                    color = if (net >= 0) Color(0xFF1B7F4B) else Color(0xFFB3261E),
                    maxLines = 1,
                )
            }
        }
    }
}

@Composable
private fun ExpenseRow(expense: Expense) {
    Row(
        modifier = Modifier.fillMaxWidth().padding(vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Text(expense.description, style = MaterialTheme.typography.bodyLarge)
            Text(
                text = "${expense.paidBy} paid · split ${expense.participants.size} ways",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        Text(
            text = expense.amountCents.money(),
            style = MaterialTheme.typography.bodyLarge,
            fontWeight = FontWeight.Medium,
        )
    }
}

@Composable
private fun ModelPanel(
    status: ModelStatus,
    apiKeyPresent: Boolean,
    progress: String?,
    onPrepare: () -> Unit,
    onSaveKey: (String, String) -> Unit,
) {
    var expanded by remember { mutableStateOf(!apiKeyPresent) }
    var key by remember { mutableStateOf("") }
    var model by remember { mutableStateOf(GeminiRestEngine.DEFAULT_MODEL) }

    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.secondaryContainer),
    ) {
        Column(Modifier.padding(12.dp)) {
            Text(
                text = when (status) {
                    ModelStatus.AVAILABLE -> "Gemini Nano is ready"
                    ModelStatus.DOWNLOADABLE -> "Gemini Nano needs to be downloaded once"
                    ModelStatus.DOWNLOADING -> "Gemini Nano is downloading"
                    ModelStatus.UNAVAILABLE ->
                        "This device has no AICore model — Gemini Nano is not available here."
                    ModelStatus.UNKNOWN -> "Checking Gemini Nano…"
                },
                style = MaterialTheme.typography.bodyMedium,
                fontWeight = FontWeight.Medium,
            )
            if (progress != null) {
                Spacer(Modifier.height(4.dp))
                Text(progress, style = MaterialTheme.typography.bodySmall)
            }
            if (status == ModelStatus.DOWNLOADABLE) {
                Spacer(Modifier.height(8.dp))
                Button(onClick = onPrepare) { Text("Download Gemini Nano") }
            }
            if (status == ModelStatus.UNAVAILABLE) {
                Spacer(Modifier.height(6.dp))
                Text(
                    text = if (apiKeyPresent) {
                        "Running in the cloud instead. Same agent, same confirmation step."
                    } else {
                        "Add a Gemini API key to run the same agent in the cloud."
                    },
                    style = MaterialTheme.typography.bodySmall,
                )
                if (expanded) {
                    Spacer(Modifier.height(8.dp))
                    OutlinedTextField(
                        value = key,
                        onValueChange = { key = it },
                        label = { Text("Gemini API key") },
                        singleLine = true,
                        visualTransformation = PasswordVisualTransformation(),
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(6.dp))
                    OutlinedTextField(
                        value = model,
                        onValueChange = { model = it },
                        label = { Text("Model") },
                        singleLine = true,
                        modifier = Modifier.fillMaxWidth(),
                    )
                    Spacer(Modifier.height(8.dp))
                    Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        Button(
                            onClick = {
                                onSaveKey(key, model)
                                expanded = false
                            },
                            enabled = key.isNotBlank(),
                        ) { Text("Save key") }
                        if (apiKeyPresent) {
                            TextButton(onClick = { expanded = false }) { Text("Cancel") }
                        }
                    }
                } else {
                    Spacer(Modifier.height(6.dp))
                    TextButton(onClick = { expanded = true }) { Text("Change key") }
                }
            }
        }
    }
}

@Composable
private fun PendingCard(
    reply: String,
    changes: List<Change>,
    notes: List<String>,
    error: String?,
    busy: Boolean,
    onApply: () -> Unit,
    onDiscard: () -> Unit,
) {
    Card(
        modifier = Modifier.fillMaxWidth().padding(bottom = 8.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer),
    ) {
        Column(Modifier.padding(16.dp)) {
            Text(
                text = if (changes.isEmpty()) "Nothing to apply" else "Proposed changes",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
            )
            if (reply.isNotBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(reply, style = MaterialTheme.typography.bodyMedium)
            }
            changes.forEach { change ->
                Spacer(Modifier.height(6.dp))
                Text("• ${change.label}", style = MaterialTheme.typography.bodyMedium)
            }
            notes.forEach { note ->
                Spacer(Modifier.height(6.dp))
                Text(note, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            if (error != null) {
                Spacer(Modifier.height(6.dp))
                Text(error, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
            Spacer(Modifier.height(12.dp))
            Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                if (changes.isNotEmpty()) {
                    Button(onClick = onApply, enabled = !busy) { Text("Apply ${changes.size}") }
                }
                TextButton(onClick = onDiscard, enabled = !busy) { Text("Discard") }
            }
        }
    }
}
