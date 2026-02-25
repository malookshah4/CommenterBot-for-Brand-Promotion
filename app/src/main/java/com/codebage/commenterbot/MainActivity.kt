package com.codebage.commenterbot

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Switch
import androidx.compose.material3.SwitchDefaults
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codebage.commenterbot.ui.theme.CommenterBotTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Initialize ReplyManager (seeds from strings.xml on first launch)
        ReplyManager.init(this, resources.getStringArray(R.array.promo_replies).toList())
        enableEdgeToEdge()
        setContent {
            CommenterBotTheme {
                Scaffold(modifier = Modifier.fillMaxSize()) { innerPadding ->
                    AppRoot(modifier = Modifier.padding(innerPadding))
                }
            }
        }
    }

    override fun onResume() {
        super.onResume()
        serviceConnected.value = TikTokReplyService.instance != null
    }

    companion object {
        val serviceConnected = mutableStateOf(false)
    }
}

// ── Navigation ────────────────────────────────────────────────────────

@Composable
fun AppRoot(modifier: Modifier = Modifier) {
    var currentScreen by remember { mutableStateOf("bot") }

    when (currentScreen) {
        "bot" -> MainScreen(
            modifier = modifier,
            onManageReplies = { currentScreen = "replies" }
        )
        "replies" -> ReplyManagerScreen(
            modifier = modifier,
            onBack = { currentScreen = "bot" }
        )
    }
}

// ── Bot Control Screen ────────────────────────────────────────────────

@Composable
fun MainScreen(modifier: Modifier = Modifier, onManageReplies: () -> Unit = {}) {
    val context = LocalContext.current
    var serviceConnected by remember { MainActivity.serviceConnected }
    val botEnabled by remember { TikTokReplyService.botEnabled }
    val status by remember { TikTokReplyService.status }
    val totalReplies by remember { TikTokReplyService.totalReplies }
    val videosProcessed by remember { TikTokReplyService.videosProcessed }
    val logs = TikTokReplyService.logs

    serviceConnected = TikTokReplyService.instance != null

    val activeBotReplies = ReplyManager.replies.count { it.enabledForBot }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        // ── Header ──────────────────────────────────────────────
        Text(
            text = "Commenter Bot",
            fontSize = 28.sp,
            fontWeight = FontWeight.Bold,
            color = MaterialTheme.colorScheme.onSurface
        )
        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "TikTok Auto-Reply",
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(20.dp))

        // ── Service status card ─────────────────────────────────
        Card(
            modifier = Modifier.fillMaxWidth(),
            colors = CardDefaults.cardColors(
                containerColor = if (serviceConnected)
                    Color(0xFF1B5E20).copy(alpha = 0.12f)
                else
                    Color(0xFFB71C1C).copy(alpha = 0.12f)
            )
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .padding(16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Box(
                    modifier = Modifier
                        .size(12.dp)
                        .clip(CircleShape)
                        .background(if (serviceConnected) Color(0xFF4CAF50) else Color(0xFFF44336))
                )
                Spacer(modifier = Modifier.width(12.dp))
                Column {
                    Text(
                        text = if (serviceConnected) "Accessibility Service ON" else "Accessibility Service OFF",
                        fontWeight = FontWeight.SemiBold,
                        fontSize = 15.sp
                    )
                    Text(
                        text = status,
                        fontSize = 13.sp,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Enable accessibility button ─────────────────────────
        if (!serviceConnected) {
            Button(
                onClick = {
                    context.startActivity(Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS))
                },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Open Accessibility Settings")
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // ── Start / Stop button ─────────────────────────────────
        Button(
            onClick = {
                val svc = TikTokReplyService.instance ?: return@Button
                if (botEnabled) svc.stopBot() else svc.startBot()
            },
            modifier = Modifier.fillMaxWidth(),
            enabled = serviceConnected,
            colors = ButtonDefaults.buttonColors(
                containerColor = if (botEnabled) Color(0xFFD32F2F) else Color(0xFF2E7D32)
            ),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text(
                text = if (botEnabled) "Stop Bot" else "Start Bot",
                fontSize = 16.sp,
                fontWeight = FontWeight.SemiBold
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Manage Replies button ───────────────────────────────
        OutlinedButton(
            onClick = onManageReplies,
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Manage Replies ($activeBotReplies active)")
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Stats row ───────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            StatCard(
                label = "Replies",
                value = totalReplies.toString(),
                modifier = Modifier.weight(1f)
            )
            StatCard(
                label = "Videos",
                value = videosProcessed.toString(),
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Log label ───────────────────────────────────────────
        Text(
            text = "Live Log",
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(6.dp))

        // ── Log view ────────────────────────────────────────────
        Surface(
            modifier = Modifier
                .fillMaxWidth()
                .weight(1f),
            shape = RoundedCornerShape(12.dp),
            color = Color(0xFF1A1A2E)
        ) {
            if (logs.isEmpty()) {
                Box(
                    modifier = Modifier.fillMaxSize(),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = "No logs yet",
                        color = Color(0xFF6C6C8A),
                        fontSize = 13.sp
                    )
                }
            } else {
                LazyColumn(
                    modifier = Modifier.padding(12.dp),
                    verticalArrangement = Arrangement.spacedBy(3.dp)
                ) {
                    items(logs.toList()) { entry ->
                        Text(
                            text = entry,
                            fontFamily = FontFamily.Monospace,
                            fontSize = 11.sp,
                            color = Color(0xFFB0B0D0),
                            lineHeight = 15.sp
                        )
                    }
                }
            }
        }
    }
}

// ── Reply Management Screen ───────────────────────────────────────────

@Composable
fun ReplyManagerScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val replies = ReplyManager.replies
    val activeCount = replies.count { it.enabledForBot }

    var showDialog by remember { mutableStateOf(false) }
    var editingReply by remember { mutableStateOf<ReplyItem?>(null) }
    var dialogText by remember { mutableStateOf("") }
    var deleteConfirmId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        // ── Header ──────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) {
                Text("< Back", fontSize = 15.sp)
            }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Manage Replies",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "$activeCount active for bot / ${replies.size} total",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        // ── Add New Reply button ────────────────────────────────
        Button(
            onClick = {
                editingReply = null
                dialogText = ""
                showDialog = true
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("+ Add New Reply")
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Reply list ──────────────────────────────────────────
        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            itemsIndexed(replies.toList(), key = { _, item -> item.id }) { index, reply ->
                ReplyItemCard(
                    reply = reply,
                    index = index + 1,
                    onToggle = { ReplyManager.toggleBotEnabled(reply.id) },
                    onEdit = {
                        editingReply = reply
                        dialogText = reply.text
                        showDialog = true
                    },
                    onDelete = { deleteConfirmId = reply.id }
                )
            }
        }
    }

    // ── Add / Edit dialog ───────────────────────────────────────
    if (showDialog) {
        AlertDialog(
            onDismissRequest = { showDialog = false },
            title = {
                Text(if (editingReply != null) "Edit Reply" else "Add New Reply")
            },
            text = {
                OutlinedTextField(
                    value = dialogText,
                    onValueChange = { dialogText = it },
                    modifier = Modifier
                        .fillMaxWidth()
                        .heightIn(min = 120.dp),
                    placeholder = { Text("Enter reply text...") },
                    maxLines = 8
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (dialogText.isNotBlank()) {
                            if (editingReply != null) {
                                ReplyManager.updateReply(editingReply!!.id, dialogText)
                            } else {
                                ReplyManager.addReply(dialogText)
                            }
                            showDialog = false
                        }
                    },
                    enabled = dialogText.isNotBlank()
                ) {
                    Text("Save")
                }
            },
            dismissButton = {
                TextButton(onClick = { showDialog = false }) {
                    Text("Cancel")
                }
            }
        )
    }

    // ── Delete confirmation dialog ──────────────────────────────
    if (deleteConfirmId != null) {
        AlertDialog(
            onDismissRequest = { deleteConfirmId = null },
            title = { Text("Delete Reply") },
            text = { Text("Are you sure you want to delete this reply?") },
            confirmButton = {
                Button(
                    onClick = {
                        ReplyManager.deleteReply(deleteConfirmId!!)
                        deleteConfirmId = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) {
                    Text("Delete")
                }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmId = null }) {
                    Text("Cancel")
                }
            }
        )
    }
}

// ── Reply Item Card ───────────────────────────────────────────────────

@Composable
fun ReplyItemCard(
    reply: ReplyItem,
    index: Int,
    onToggle: () -> Unit,
    onEdit: () -> Unit,
    onDelete: () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = if (reply.enabledForBot)
                MaterialTheme.colorScheme.surfaceVariant
            else
                MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.4f)
        )
    ) {
        Column(modifier = Modifier.padding(12.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Index number
                Text(
                    text = "#$index",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))

                // Bot toggle switch
                Switch(
                    checked = reply.enabledForBot,
                    onCheckedChange = { onToggle() },
                    colors = SwitchDefaults.colors(
                        checkedTrackColor = Color(0xFF2E7D32)
                    )
                )
                Spacer(modifier = Modifier.width(8.dp))

                Text(
                    text = if (reply.enabledForBot) "Bot ON" else "Bot OFF",
                    fontSize = 11.sp,
                    color = if (reply.enabledForBot) Color(0xFF4CAF50) else Color(0xFF999999)
                )

                Spacer(modifier = Modifier.weight(1f))

                // Edit button
                TextButton(onClick = onEdit) {
                    Text("Edit", fontSize = 12.sp)
                }

                // Delete button
                TextButton(onClick = onDelete) {
                    Text("Del", fontSize = 12.sp, color = Color(0xFFD32F2F))
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            // Reply text
            Text(
                text = reply.text,
                fontSize = 13.sp,
                lineHeight = 18.sp,
                maxLines = 3,
                overflow = TextOverflow.Ellipsis,
                color = if (reply.enabledForBot)
                    MaterialTheme.colorScheme.onSurface
                else
                    MaterialTheme.colorScheme.onSurface.copy(alpha = 0.5f)
            )
        }
    }
}

// ── Stat Card ─────────────────────────────────────────────────────────

@Composable
fun StatCard(label: String, value: String, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surfaceVariant
        )
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(14.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(
                text = value,
                fontSize = 26.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
            Text(
                text = label,
                fontSize = 12.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
    }
}
