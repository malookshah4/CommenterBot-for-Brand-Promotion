package com.codebage.commenterbot

import android.content.Intent
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
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
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
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
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.codebage.commenterbot.ui.theme.CommenterBotTheme

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
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
    var editingProfileId by remember { mutableStateOf<String?>(null) }

    when (currentScreen) {
        "bot" -> MainScreen(
            modifier = modifier,
            onManageReplies = {
                val activeId = ReplyManager.activeProfileId.value
                if (activeId.isNotEmpty()) {
                    editingProfileId = activeId
                    currentScreen = "profile_edit"
                }
            },
            onProfiles = { currentScreen = "profiles" },
            onSettings = { currentScreen = "settings" }
        )
        "profiles" -> ProfileListScreen(
            modifier = modifier,
            onBack = { currentScreen = "bot" },
            onEditProfile = { id ->
                editingProfileId = id
                currentScreen = "profile_edit"
            }
        )
        "profile_edit" -> {
            val id = editingProfileId
            if (id != null) {
                ProfileEditScreen(
                    modifier = modifier,
                    profileId = id,
                    onBack = { currentScreen = "profiles" }
                )
            } else {
                currentScreen = "profiles"
            }
        }
        "settings" -> SettingsScreen(
            modifier = modifier,
            onBack = { currentScreen = "bot" }
        )
    }
}

// ── Bot Control Screen ────────────────────────────────────────────────

@Composable
fun MainScreen(
    modifier: Modifier = Modifier,
    onManageReplies: () -> Unit = {},
    onProfiles: () -> Unit = {},
    onSettings: () -> Unit = {}
) {
    val context = LocalContext.current
    var serviceConnected by remember { MainActivity.serviceConnected }
    val botEnabled by remember { TikTokReplyService.botEnabled }
    val status by remember { TikTokReplyService.status }
    val totalReplies by remember { TikTokReplyService.totalReplies }
    val videosProcessed by remember { TikTokReplyService.videosProcessed }
    val dailyReplies by remember { ReplyManager.dailyReplies }
    val logs = TikTokReplyService.logs

    serviceConnected = TikTokReplyService.instance != null

    val activeProfile = ReplyManager.getActiveProfile()
    val activeBotReplies = ReplyManager.replies.count { it.enabledForBot }

    // Profile dropdown state
    var profileDropdown by remember { mutableStateOf(false) }

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

        Spacer(modifier = Modifier.height(16.dp))

        // ── Profile selector ────────────────────────────────────
        Box {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { profileDropdown = true },
                colors = CardDefaults.cardColors(
                    containerColor = MaterialTheme.colorScheme.primaryContainer
                )
            ) {
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(14.dp),
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text(
                            text = "Profile",
                            fontSize = 11.sp,
                            color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                        )
                        Text(
                            text = activeProfile?.name ?: "No Profile",
                            fontSize = 16.sp,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                    }
                    Text(
                        text = "$activeBotReplies replies",
                        fontSize = 12.sp,
                        color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
                    )
                }
            }
            DropdownMenu(
                expanded = profileDropdown,
                onDismissRequest = { profileDropdown = false }
            ) {
                ReplyManager.profiles.forEach { profile ->
                    DropdownMenuItem(
                        text = {
                            Row(verticalAlignment = Alignment.CenterVertically) {
                                if (profile.id == ReplyManager.activeProfileId.value) {
                                    Box(
                                        modifier = Modifier
                                            .size(8.dp)
                                            .clip(CircleShape)
                                            .background(Color(0xFF4CAF50))
                                    )
                                    Spacer(modifier = Modifier.width(8.dp))
                                }
                                Text(profile.name)
                            }
                        },
                        onClick = {
                            ReplyManager.setActiveProfile(profile.id)
                            profileDropdown = false
                        }
                    )
                }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

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

        // ── Action buttons row ──────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            OutlinedButton(
                onClick = onManageReplies,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Replies", fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = onProfiles,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Profiles", fontSize = 13.sp)
            }
            OutlinedButton(
                onClick = onSettings,
                modifier = Modifier.weight(1f),
                shape = RoundedCornerShape(12.dp)
            ) {
                Text("Settings", fontSize = 13.sp)
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Stats row ───────────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            StatCard(label = "Session", value = totalReplies.toString(), modifier = Modifier.weight(1f))
            StatCard(label = "Videos", value = videosProcessed.toString(), modifier = Modifier.weight(1f))
            StatCard(label = "Today", value = dailyReplies.toString(), modifier = Modifier.weight(1f))
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

// ── Profile List Screen ──────────────────────────────────────────────

@Composable
fun ProfileListScreen(
    modifier: Modifier = Modifier,
    onBack: () -> Unit,
    onEditProfile: (String) -> Unit
) {
    val profiles = ReplyManager.profiles
    val activeId by remember { ReplyManager.activeProfileId }
    var showAddDialog by remember { mutableStateOf(false) }
    var newProfileName by remember { mutableStateOf("") }
    var deleteConfirmId by remember { mutableStateOf<String?>(null) }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("< Back", fontSize = 15.sp) }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Profiles",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.height(4.dp))
        Text(
            text = "${profiles.size} profiles",
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            modifier = Modifier.padding(start = 12.dp)
        )

        Spacer(modifier = Modifier.height(12.dp))

        Button(
            onClick = { newProfileName = ""; showAddDialog = true },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("+ Add New Profile")
        }

        Spacer(modifier = Modifier.height(12.dp))

        LazyColumn(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            items(profiles.toList(), key = { it.id }) { profile ->
                val isActive = profile.id == activeId
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(
                        containerColor = if (isActive)
                            MaterialTheme.colorScheme.primaryContainer
                        else
                            MaterialTheme.colorScheme.surfaceVariant
                    )
                ) {
                    Column(modifier = Modifier.padding(14.dp)) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            if (isActive) {
                                Box(
                                    modifier = Modifier
                                        .size(10.dp)
                                        .clip(CircleShape)
                                        .background(Color(0xFF4CAF50))
                                )
                                Spacer(modifier = Modifier.width(8.dp))
                            }
                            Text(
                                text = profile.name,
                                fontSize = 17.sp,
                                fontWeight = FontWeight.SemiBold,
                                modifier = Modifier.weight(1f)
                            )
                            if (!isActive) {
                                TextButton(onClick = {
                                    ReplyManager.setActiveProfile(profile.id)
                                }) {
                                    Text("Activate", fontSize = 12.sp)
                                }
                            }
                        }

                        Spacer(modifier = Modifier.height(4.dp))

                        Text(
                            text = "${profile.replies.count { it.enabledForBot }} replies" +
                                    if (profile.keywords.isNotEmpty()) " | ${profile.keywords.size} keywords" else "",
                            fontSize = 12.sp,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )

                        Spacer(modifier = Modifier.height(8.dp))

                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            OutlinedButton(
                                onClick = {
                                    ReplyManager.setActiveProfile(profile.id)
                                    onEditProfile(profile.id)
                                },
                                shape = RoundedCornerShape(8.dp)
                            ) {
                                Text("Edit", fontSize = 12.sp)
                            }
                            if (profiles.size > 1) {
                                OutlinedButton(
                                    onClick = { deleteConfirmId = profile.id },
                                    shape = RoundedCornerShape(8.dp)
                                ) {
                                    Text("Delete", fontSize = 12.sp, color = Color(0xFFD32F2F))
                                }
                            }
                        }
                    }
                }
            }
        }
    }

    // ── Add Profile dialog ──────────────────────────────────────
    if (showAddDialog) {
        AlertDialog(
            onDismissRequest = { showAddDialog = false },
            title = { Text("New Profile") },
            text = {
                OutlinedTextField(
                    value = newProfileName,
                    onValueChange = { newProfileName = it },
                    modifier = Modifier.fillMaxWidth(),
                    placeholder = { Text("Profile name (e.g., GoViral AI)") },
                    singleLine = true
                )
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (newProfileName.isNotBlank()) {
                            ReplyManager.addProfile(newProfileName)
                            showAddDialog = false
                        }
                    },
                    enabled = newProfileName.isNotBlank()
                ) { Text("Create") }
            },
            dismissButton = {
                TextButton(onClick = { showAddDialog = false }) { Text("Cancel") }
            }
        )
    }

    // ── Delete confirmation ─────────────────────────────────────
    if (deleteConfirmId != null) {
        AlertDialog(
            onDismissRequest = { deleteConfirmId = null },
            title = { Text("Delete Profile") },
            text = { Text("Delete this profile and all its replies?") },
            confirmButton = {
                Button(
                    onClick = {
                        ReplyManager.deleteProfile(deleteConfirmId!!)
                        deleteConfirmId = null
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFD32F2F))
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmId = null }) { Text("Cancel") }
            }
        )
    }
}

// ── Profile Edit Screen (Replies + Keywords) ─────────────────────────

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun ProfileEditScreen(
    modifier: Modifier = Modifier,
    profileId: String,
    onBack: () -> Unit
) {
    val profiles = ReplyManager.profiles
    val profile = profiles.find { it.id == profileId } ?: run { onBack(); return }
    val replies = ReplyManager.replies
    val activeCount = replies.count { it.enabledForBot }

    var showReplyDialog by remember { mutableStateOf(false) }
    var editingReply by remember { mutableStateOf<ReplyItem?>(null) }
    var dialogText by remember { mutableStateOf("") }
    var deleteConfirmId by remember { mutableStateOf<String?>(null) }

    // Name editing
    var editingName by remember { mutableStateOf(false) }
    var nameText by remember { mutableStateOf(profile.name) }

    // Keyword adding
    var keywordText by remember { mutableStateOf("") }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        // ── Header ──────────────────────────────────────────────
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text("< Back", fontSize = 15.sp) }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Edit Profile",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Profile name ────────────────────────────────────────
        if (editingName) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = nameText,
                    onValueChange = { nameText = it },
                    modifier = Modifier.weight(1f),
                    label = { Text("Profile Name") },
                    singleLine = true
                )
                Spacer(modifier = Modifier.width(8.dp))
                Button(onClick = {
                    if (nameText.isNotBlank()) {
                        ReplyManager.renameProfile(profileId, nameText)
                        editingName = false
                    }
                }) { Text("Save") }
            }
        } else {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text(
                    text = profile.name,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.SemiBold,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = {
                    nameText = profile.name
                    editingName = true
                }) { Text("Rename") }
            }
        }

        Spacer(modifier = Modifier.height(12.dp))

        // ── Keywords section ────────────────────────────────────
        Text(
            text = "Keywords (optional)",
            fontWeight = FontWeight.SemiBold,
            fontSize = 14.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(2.dp))
        Text(
            text = if (profile.keywords.isEmpty()) "Empty = reply to ALL comments"
            else "Only reply when comment contains these words",
            fontSize = 11.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.7f)
        )
        Spacer(modifier = Modifier.height(8.dp))

        // Keyword chips
        if (profile.keywords.isNotEmpty()) {
            FlowRow(
                horizontalArrangement = Arrangement.spacedBy(6.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                profile.keywords.forEach { kw ->
                    Surface(
                        shape = RoundedCornerShape(16.dp),
                        color = MaterialTheme.colorScheme.secondaryContainer
                    ) {
                        Row(
                            modifier = Modifier.padding(horizontal = 10.dp, vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Text(text = kw, fontSize = 12.sp)
                            Spacer(modifier = Modifier.width(4.dp))
                            Text(
                                text = "x",
                                fontSize = 12.sp,
                                fontWeight = FontWeight.Bold,
                                color = Color(0xFFD32F2F),
                                modifier = Modifier.clickable {
                                    ReplyManager.removeKeyword(kw)
                                }
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }

        // Add keyword
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            OutlinedTextField(
                value = keywordText,
                onValueChange = { keywordText = it },
                modifier = Modifier.weight(1f),
                placeholder = { Text("Add keyword...") },
                singleLine = true
            )
            Spacer(modifier = Modifier.width(8.dp))
            Button(
                onClick = {
                    if (keywordText.isNotBlank()) {
                        ReplyManager.addKeyword(keywordText)
                        keywordText = ""
                    }
                },
                enabled = keywordText.isNotBlank(),
                shape = RoundedCornerShape(8.dp)
            ) { Text("+") }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // ── Replies section ─────────────────────────────────────
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = "Replies ($activeCount active / ${replies.size} total)",
                fontWeight = FontWeight.SemiBold,
                fontSize = 14.sp,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.weight(1f)
            )
        }

        Spacer(modifier = Modifier.height(4.dp))

        Text(
            text = "Templates: {app_name} = profile name, {100-500} = random number",
            fontSize = 10.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant.copy(alpha = 0.6f)
        )

        Spacer(modifier = Modifier.height(8.dp))

        Button(
            onClick = {
                editingReply = null
                dialogText = ""
                showReplyDialog = true
            },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("+ Add New Reply")
        }

        Spacer(modifier = Modifier.height(8.dp))

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
                        showReplyDialog = true
                    },
                    onDelete = { deleteConfirmId = reply.id }
                )
            }
        }
    }

    // ── Add / Edit reply dialog ─────────────────────────────────
    if (showReplyDialog) {
        AlertDialog(
            onDismissRequest = { showReplyDialog = false },
            title = { Text(if (editingReply != null) "Edit Reply" else "Add New Reply") },
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
                            showReplyDialog = false
                        }
                    },
                    enabled = dialogText.isNotBlank()
                ) { Text("Save") }
            },
            dismissButton = {
                TextButton(onClick = { showReplyDialog = false }) { Text("Cancel") }
            }
        )
    }

    // ── Delete reply confirmation ───────────────────────────────
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
                ) { Text("Delete") }
            },
            dismissButton = {
                TextButton(onClick = { deleteConfirmId = null }) { Text("Cancel") }
            }
        )
    }
}

// ── Settings Screen ──────────────────────────────────────────────────

@Composable
fun SettingsScreen(modifier: Modifier = Modifier, onBack: () -> Unit) {
    val settings by remember { ReplyManager.botSettings }

    var minDelay by remember { mutableStateOf(settings.minDelaySeconds.toString()) }
    var maxDelay by remember { mutableStateOf(settings.maxDelaySeconds.toString()) }
    var maxPerHour by remember { mutableStateOf(settings.maxRepliesPerHour.toString()) }
    var dailyLimit by remember { mutableStateOf(settings.dailyReplyLimit.toString()) }
    var autoStopReplies by remember { mutableStateOf(settings.autoStopAfterReplies.toString()) }
    var autoStopMinutes by remember { mutableStateOf(settings.autoStopAfterMinutes.toString()) }

    fun save() {
        ReplyManager.updateSettings(
            BotSettings(
                minDelaySeconds = minDelay.toIntOrNull() ?: 3,
                maxDelaySeconds = maxDelay.toIntOrNull() ?: 8,
                maxRepliesPerHour = maxPerHour.toIntOrNull() ?: 0,
                dailyReplyLimit = dailyLimit.toIntOrNull() ?: 0,
                autoStopAfterReplies = autoStopReplies.toIntOrNull() ?: 0,
                autoStopAfterMinutes = autoStopMinutes.toIntOrNull() ?: 0
            )
        )
    }

    Column(
        modifier = modifier
            .fillMaxSize()
            .padding(20.dp)
    ) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = { save(); onBack() }) { Text("< Back", fontSize = 15.sp) }
            Spacer(modifier = Modifier.width(8.dp))
            Text(
                text = "Settings",
                fontSize = 24.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── Reply Delay ─────────────────────────────────────────
        Text(
            text = "Reply Delay",
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp
        )
        Text(
            text = "Time between each reply (seconds)",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        Row(horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            OutlinedTextField(
                value = minDelay,
                onValueChange = { minDelay = it },
                modifier = Modifier.weight(1f),
                label = { Text("Min") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
            OutlinedTextField(
                value = maxDelay,
                onValueChange = { maxDelay = it },
                modifier = Modifier.weight(1f),
                label = { Text("Max") },
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                singleLine = true
            )
        }

        Spacer(modifier = Modifier.height(20.dp))

        // ── Rate Limits ─────────────────────────────────────────
        Text(
            text = "Rate Limits",
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp
        )
        Text(
            text = "0 = unlimited",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = maxPerHour,
            onValueChange = { maxPerHour = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Max replies per hour") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = dailyLimit,
            onValueChange = { dailyLimit = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Daily reply limit") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(20.dp))

        // ── Auto-Stop ───────────────────────────────────────────
        Text(
            text = "Auto-Stop",
            fontWeight = FontWeight.SemiBold,
            fontSize = 16.sp
        )
        Text(
            text = "Bot stops automatically (0 = never)",
            fontSize = 12.sp,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = autoStopReplies,
            onValueChange = { autoStopReplies = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Stop after N replies") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )
        Spacer(modifier = Modifier.height(8.dp))
        OutlinedTextField(
            value = autoStopMinutes,
            onValueChange = { autoStopMinutes = it },
            modifier = Modifier.fillMaxWidth(),
            label = { Text("Stop after N minutes") },
            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
            singleLine = true
        )

        Spacer(modifier = Modifier.height(24.dp))

        Button(
            onClick = { save() },
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(12.dp)
        ) {
            Text("Save Settings")
        }
    }
}

// ── Reply Item Card ──────────────────────────────────────────────────

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
                Text(
                    text = "#$index",
                    fontSize = 12.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                Spacer(modifier = Modifier.width(8.dp))

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

                TextButton(onClick = onEdit) {
                    Text("Edit", fontSize = 12.sp)
                }
                TextButton(onClick = onDelete) {
                    Text("Del", fontSize = 12.sp, color = Color(0xFFD32F2F))
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

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

// ── Stat Card ────────────────────────────────────────────────────────

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
