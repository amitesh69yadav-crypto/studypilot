package com.example.ui.screens

import androidx.compose.ui.platform.testTag
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.RadioButton
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.BuildConfig
import com.example.data.model.Subject
import com.example.ui.components.SectionHeader
import com.example.ui.theme.CyanAccent
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.PaletteCreamIvory
import com.example.ui.theme.PaletteDarkText
import com.example.ui.theme.PaletteDeepSage
import com.example.ui.theme.PaletteMediumSage
import com.example.ui.theme.PalettePaleCeladon
import com.example.ui.viewmodel.StudyPilotViewModel

@Composable
fun SettingsScreen(
  viewModel: StudyPilotViewModel
) {
  val subjects by viewModel.subjects.collectAsState()
  val currentUser by viewModel.currentUser.collectAsState()
  val savedAccounts by viewModel.savedAccounts.collectAsState()

  val customGeminiKey by viewModel.customGeminiKey.collectAsState()
  val aiProviderMode by viewModel.aiProviderMode.collectAsState()
  val apiTestStatus by viewModel.apiTestStatus.collectAsState()

  val localOllamaUrl by viewModel.localOllamaUrl.collectAsState()
  val localOllamaModel by viewModel.localOllamaModel.collectAsState()
  val ollamaTestStatus by viewModel.ollamaTestStatus.collectAsState()

  var inputOllamaUrl by remember(localOllamaUrl) { mutableStateOf(localOllamaUrl) }
  var inputOllamaModel by remember(localOllamaModel) { mutableStateOf(localOllamaModel) }

  var inputGeminiKey by remember(customGeminiKey) { mutableStateOf(customGeminiKey) }
  var showGeminiKeyPassword by remember { mutableStateOf(false) }

  var showAddSubjectDialog by remember { mutableStateOf(false) }
  var showEditProfileDialog by remember { mutableStateOf(false) }
  var accountToDelete by remember { mutableStateOf<com.example.data.model.UserProfile?>(null) }
  var showDeleteCurrentAccountDialog by remember { mutableStateOf(false) }
  var showDeleteSampleDataDialog by remember { mutableStateOf(false) }

  var editName by remember(currentUser) { mutableStateOf(currentUser.name) }
  var editEmail by remember(currentUser) { mutableStateOf(currentUser.email) }
  var editUniversity by remember(currentUser) { mutableStateOf(currentUser.university) }
  var editRole by remember(currentUser) { mutableStateOf(currentUser.role) }

  val isGeminiConfigured = BuildConfig.GEMINI_API_KEY.isNotBlank() &&
    BuildConfig.GEMINI_API_KEY != "MY_GEMINI_API_KEY"

  val isGuestMode = !currentUser.isLoggedIn || currentUser.id == com.example.ui.viewmodel.StudyPilotViewModel.GUEST_USER_ID

  LazyColumn(
    modifier = Modifier.fillMaxSize(),
    contentPadding = PaddingValues(top = 10.dp, bottom = 32.dp)
  ) {
    // 0. Account Profile & Device Sync Card
    item {
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
      ) {
        Column(modifier = Modifier.padding(16.dp)) {
          // Top Row: Avatar + User Details + Status Badge
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              horizontalArrangement = Arrangement.spacedBy(14.dp),
              modifier = Modifier.weight(1f)
            ) {
              Box(
                modifier = Modifier
                  .size(48.dp)
                  .clip(CircleShape)
                  .background(PaletteDeepSage),
                contentAlignment = Alignment.Center
              ) {
                Text(
                  text = currentUser.avatarInitials,
                  fontWeight = FontWeight.ExtraBold,
                  color = PaletteCreamIvory,
                  fontSize = 16.sp
                )
              }
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  text = currentUser.name,
                  style = MaterialTheme.typography.titleMedium.copy(fontWeight = FontWeight.Bold),
                  color = MaterialTheme.colorScheme.onSurface,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
                Text(
                  text = if (isGuestMode) "Local session • Not signed in" else currentUser.email.ifBlank { currentUser.authProvider },
                  style = MaterialTheme.typography.bodySmall,
                  color = MaterialTheme.colorScheme.onSurfaceVariant,
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis
                )
                if (!isGuestMode) {
                  val subMeta = listOfNotNull(
                    currentUser.university.takeIf { it.isNotBlank() },
                    currentUser.studentId.takeIf { it.isNotBlank() }
                  ).joinToString(" • ")
                  if (subMeta.isNotBlank()) {
                    Text(
                      text = subMeta,
                      style = MaterialTheme.typography.labelSmall,
                      color = PaletteDeepSage,
                      fontWeight = FontWeight.SemiBold,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis
                    )
                  }
                }
              }
            }

            Spacer(modifier = Modifier.width(8.dp))

            val badgeColor = if (isGuestMode) PaletteDeepSage else EmeraldSuccess
            Surface(
              shape = RoundedCornerShape(20.dp),
              color = badgeColor.copy(alpha = 0.12f),
              border = androidx.compose.foundation.BorderStroke(1.dp, badgeColor.copy(alpha = 0.35f))
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 10.dp, vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(5.dp)
              ) {
                Box(
                  modifier = Modifier
                    .size(7.dp)
                    .clip(CircleShape)
                    .background(badgeColor)
                )
                Text(
                  text = if (isGuestMode) "Guest" else "Active",
                  fontSize = 11.sp,
                  fontWeight = FontWeight.Bold,
                  color = badgeColor,
                  maxLines = 1,
                  softWrap = false
                )
              }
            }
          }

          Spacer(modifier = Modifier.height(14.dp))

          if (isGuestMode) {
            Button(
              onClick = { viewModel.navigateTo(com.example.ui.viewmodel.Screen.Login) },
              shape = RoundedCornerShape(12.dp),
              colors = ButtonDefaults.buttonColors(
                containerColor = PaletteDeepSage,
                contentColor = PaletteCreamIvory
              ),
              modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("settings_sign_in_button")
            ) {
              Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = "Sign In / Create Account",
                fontSize = 13.sp,
                fontWeight = FontWeight.Bold,
                maxLines = 1,
                softWrap = false
              )
            }

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
              shape = RoundedCornerShape(12.dp),
              color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
              modifier = Modifier.fillMaxWidth()
            ) {
              Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Icon(
                  Icons.Default.Devices,
                  contentDescription = null,
                  tint = PaletteDeepSage,
                  modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = "Guest Mode (Local Storage Only)",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                  )
                  Text(
                    text = "Sign in or register to store your profile and sync your data.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
              }
            }
          } else {
            // Account Action Buttons for signed-in user
            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              FilledTonalButton(
                onClick = {
                  editName = currentUser.name
                  editEmail = currentUser.email
                  editUniversity = currentUser.university
                  editRole = currentUser.role
                  showEditProfileDialog = true
                },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                  containerColor = PalettePaleCeladon,
                  contentColor = PaletteDeepSage
                ),
                modifier = Modifier
                  .weight(1f)
                  .heightIn(min = 44.dp)
              ) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Edit Profile", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
              }

              OutlinedButton(
                onClick = { viewModel.logout() },
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
                colors = ButtonDefaults.outlinedButtonColors(
                  contentColor = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier
                  .weight(1f)
                  .heightIn(min = 44.dp)
              ) {
                Icon(Icons.AutoMirrored.Filled.Logout, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Sign Out", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
              }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(10.dp)
            ) {
              FilledTonalButton(
                onClick = { viewModel.syncCloudData() },
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.filledTonalButtonColors(
                  containerColor = CyanAccent.copy(alpha = 0.15f),
                  contentColor = MaterialTheme.colorScheme.onSurface
                ),
                modifier = Modifier
                  .weight(1f)
                  .heightIn(min = 44.dp)
              ) {
                Icon(Icons.Default.Devices, contentDescription = null, modifier = Modifier.size(15.dp), tint = PaletteDeepSage)
                Spacer(modifier = Modifier.width(6.dp))
                Text("Cloud Sync", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
              }

              OutlinedButton(
                onClick = { showDeleteCurrentAccountDialog = true },
                shape = RoundedCornerShape(12.dp),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
                colors = ButtonDefaults.outlinedButtonColors(
                  contentColor = MaterialTheme.colorScheme.error
                ),
                modifier = Modifier
                  .weight(1f)
                  .heightIn(min = 44.dp)
              ) {
                Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(15.dp))
                Spacer(modifier = Modifier.width(6.dp))
                Text("Delete Account", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
              }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
              shape = RoundedCornerShape(12.dp),
              color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
              modifier = Modifier.fillMaxWidth()
            ) {
              Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Icon(
                  Icons.Default.Devices,
                  contentDescription = null,
                  tint = PaletteDeepSage,
                  modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = "Synced Profile (${currentUser.authProvider})",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                  )
                  Text(
                    text = "Your account info, coursework, and alarms are saved.",
                    fontSize = 11.sp,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
              }
            }
          }
        }
      }
    }

    // 0.5. Stored Accounts Switcher
    item {
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
      ) {
        Column(modifier = Modifier.padding(16.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Column(
              modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
            ) {
              Text(
                text = "Stored Accounts (${savedAccounts.size})",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
              )
              Text(
                text = "Accounts saved on this device with isolated study data",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
              )
            }
            Surface(
              shape = RoundedCornerShape(8.dp),
              color = MaterialTheme.colorScheme.primaryContainer
            ) {
              Text(
                text = "Saved",
                fontSize = 11.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.primary,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
              )
            }
          }

          Spacer(modifier = Modifier.height(12.dp))

          if (savedAccounts.isEmpty()) {
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
              modifier = Modifier.fillMaxWidth()
            ) {
              Text(
                text = "No stored accounts yet. Sign in or create an account to store your profile here.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(12.dp)
              )
            }
          } else {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
              savedAccounts.forEach { account ->
                val isCurrent = !isGuestMode && account.id == currentUser.id
                Surface(
                  shape = RoundedCornerShape(12.dp),
                  color = if (isCurrent) PalettePaleCeladon.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                  border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isCurrent) PaletteMediumSage else MaterialTheme.colorScheme.outlineVariant
                  ),
                  modifier = Modifier.fillMaxWidth()
                ) {
                  Row(
                    modifier = Modifier
                      .fillMaxWidth()
                      .padding(12.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                  ) {
                    Row(
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(10.dp),
                      modifier = Modifier.weight(1f)
                    ) {
                      Box(
                        modifier = Modifier
                          .size(38.dp)
                          .clip(CircleShape)
                          .background(if (isCurrent) PaletteDeepSage else MaterialTheme.colorScheme.outline),
                        contentAlignment = Alignment.Center
                      ) {
                        Text(
                          text = account.avatarInitials,
                          fontWeight = FontWeight.Bold,
                          color = PaletteCreamIvory,
                          fontSize = 13.sp
                        )
                      }
                      Column(modifier = Modifier.weight(1f)) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                          Text(
                            text = account.name,
                            fontWeight = FontWeight.Bold,
                            fontSize = 13.sp,
                            color = MaterialTheme.colorScheme.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                            modifier = Modifier.weight(1f, fill = false)
                          )
                          if (isCurrent) {
                            Spacer(modifier = Modifier.width(6.dp))
                            Surface(
                              shape = RoundedCornerShape(6.dp),
                              color = EmeraldSuccess.copy(alpha = 0.15f)
                            ) {
                              Text(
                                text = "Active",
                                fontSize = 10.sp,
                                fontWeight = FontWeight.Bold,
                                color = EmeraldSuccess,
                                maxLines = 1,
                                softWrap = false,
                                modifier = Modifier.padding(horizontal = 6.dp, vertical = 2.dp)
                              )
                            }
                          }
                        }
                        Text(
                          text = account.email.ifBlank { "${account.university} • ${account.studentId}" },
                          fontSize = 11.sp,
                          color = MaterialTheme.colorScheme.onSurfaceVariant,
                          maxLines = 1,
                          overflow = TextOverflow.Ellipsis
                        )
                      }
                    }

                    Row(
                      verticalAlignment = Alignment.CenterVertically,
                      horizontalArrangement = Arrangement.spacedBy(4.dp)
                    ) {
                      if (!isCurrent) {
                        FilledTonalButton(
                          onClick = { viewModel.switchAccount(account) },
                          shape = RoundedCornerShape(8.dp),
                          contentPadding = PaddingValues(horizontal = 10.dp, vertical = 6.dp),
                          modifier = Modifier.heightIn(min = 44.dp)
                        ) {
                          Icon(Icons.Default.SwapHoriz, contentDescription = null, modifier = Modifier.size(14.dp))
                          Spacer(modifier = Modifier.width(4.dp))
                          Text("Switch", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                        }
                      }
                      IconButton(
                        onClick = { accountToDelete = account },
                        modifier = Modifier.size(48.dp)
                      ) {
                        Icon(
                          Icons.Default.Delete,
                          contentDescription = "Delete Profile",
                          tint = MaterialTheme.colorScheme.error.copy(alpha = 0.75f),
                          modifier = Modifier.size(18.dp)
                        )
                      }
                    }
                  }
                }
              }
            }
          }

          Spacer(modifier = Modifier.height(10.dp))

          OutlinedButton(
            onClick = { viewModel.navigateTo(com.example.ui.viewmodel.Screen.Login) },
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 48.dp)
          ) {
            Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(6.dp))
            Text(
              text = if (savedAccounts.isEmpty()) "Sign In / Create Account" else "Sign In / Add Another Account",
              fontSize = 12.sp,
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }
      }
    }

    // 1. AI Provider Selection & Custom API Key Configuration
    item {
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 8.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
      ) {
        Column(modifier = Modifier.padding(18.dp)) {
          // Header Row
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Row(
              verticalAlignment = Alignment.CenterVertically,
              modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
            ) {
              Box(
                modifier = Modifier
                  .size(40.dp)
                  .clip(CircleShape)
                  .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
              ) {
                Icon(
                  Icons.Default.AutoAwesome,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(20.dp)
                )
              }
              Spacer(modifier = Modifier.width(12.dp))
              Column(modifier = Modifier.weight(1f)) {
                Text(
                  "AI Engine Settings (Gemini & Local AI)",
                  fontWeight = FontWeight.Bold,
                  style = MaterialTheme.typography.titleMedium
                )
                Text(
                  text = viewModel.repository.geminiService.getActiveProviderDescription(),
                  style = MaterialTheme.typography.bodySmall,
                  color = PaletteDeepSage,
                  fontWeight = FontWeight.SemiBold
                )
              }
            }

            Surface(
              shape = RoundedCornerShape(8.dp),
              color = EmeraldSuccess.copy(alpha = 0.15f)
            ) {
              Text(
                text = "Active",
                style = MaterialTheme.typography.labelSmall.copy(fontWeight = FontWeight.Bold),
                color = EmeraldSuccess,
                maxLines = 1,
                softWrap = false,
                modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
              )
            }
          }

          Spacer(modifier = Modifier.height(16.dp))

          // Provider Mode Selection Header
          Text(
            text = "Preferred AI Engine",
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface
          )
          Spacer(modifier = Modifier.height(6.dp))

          // Strictly Gemini & Local AI Only
          val modes = listOf(
            "AUTO" to "Auto (Google Gemini → Local Academic AI)",
            "GEMINI" to "Google Gemini AI (Primary Cloud)",
            "LOCAL" to "Local Academic AI / Ollama (On-Device)"
          )

          Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
            modes.forEach { (modeKey, modeTitle) ->
              val isSelected = aiProviderMode.equals(modeKey, ignoreCase = true)
              Surface(
                shape = RoundedCornerShape(10.dp),
                color = if (isSelected) PalettePaleCeladon else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                border = androidx.compose.foundation.BorderStroke(
                  1.dp,
                  if (isSelected) PaletteMediumSage else Color.Transparent
                ),
                onClick = { viewModel.setAiProviderMode(modeKey) },
                modifier = Modifier.fillMaxWidth()
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.SpaceBetween
                ) {
                  Row(
                    verticalAlignment = Alignment.CenterVertically,
                    modifier = Modifier
                      .weight(1f)
                      .padding(end = 8.dp)
                  ) {
                    RadioButton(
                      selected = isSelected,
                      onClick = { viewModel.setAiProviderMode(modeKey) }
                    )
                    Spacer(modifier = Modifier.width(6.dp))
                    Text(
                      text = modeTitle,
                      fontSize = 12.sp,
                      fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Normal,
                      color = MaterialTheme.colorScheme.onSurface,
                      modifier = Modifier.weight(1f)
                    )
                  }
                  if (isSelected) {
                    Icon(
                      Icons.Default.CheckCircle,
                      contentDescription = null,
                      tint = PaletteDeepSage,
                      modifier = Modifier.size(16.dp)
                    )
                  }
                }
              }
            }
          }

          Spacer(modifier = Modifier.height(10.dp))

          // Guaranteed Fallback Notice Box
          Surface(
            shape = RoundedCornerShape(10.dp),
            color = PalettePaleCeladon.copy(alpha = 0.7f),
            modifier = Modifier.fillMaxWidth()
          ) {
            Row(
              modifier = Modifier.padding(12.dp),
              verticalAlignment = Alignment.CenterVertically
            ) {
              Icon(
                imageVector = Icons.Default.CheckCircle,
                contentDescription = null,
                tint = PaletteDeepSage,
                modifier = Modifier.size(20.dp)
              )
              Spacer(modifier = Modifier.width(10.dp))
              Column {
                Text(
                  text = "Guaranteed AI Response System",
                  style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                  color = PaletteDeepSage
                )
                Text(
                  text = "Every question is answered. If cloud APIs face quota limits or network outages, the on-device Local Academic AI (symbolic math, code algorithms & university science graph) immediately handles your query without disruption.",
                  style = MaterialTheme.typography.bodySmall,
                  fontSize = 11.sp,
                  color = MaterialTheme.colorScheme.onSurface
                )
              }
            }
          }

          Spacer(modifier = Modifier.height(10.dp))

          // Local Llama / Ollama Runner Card
          Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = PalettePaleCeladon.copy(alpha = 0.45f)),
            border = androidx.compose.foundation.BorderStroke(1.dp, PaletteMediumSage.copy(alpha = 0.6f)),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(modifier = Modifier.padding(14.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Row(
                  verticalAlignment = Alignment.CenterVertically,
                  modifier = Modifier
                    .weight(1f)
                    .padding(end = 8.dp)
                ) {
                  Text("🦙", fontSize = 18.sp)
                  Spacer(modifier = Modifier.width(8.dp))
                  Column(modifier = Modifier.weight(1f)) {
                    Text("Local Llama / Ollama Engine", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = PaletteDarkText)
                    Text("100% on-device neural AI daemon", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
                  }
                }
                Surface(
                  shape = RoundedCornerShape(6.dp),
                  color = PaletteMediumSage.copy(alpha = 0.3f)
                ) {
                  Text(
                    text = inputOllamaModel.ifBlank { "llama3.2" },
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Bold,
                    color = PaletteDeepSage,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                  )
                }
              }

              Spacer(modifier = Modifier.height(10.dp))

              Text("Ollama Daemon URL (10.0.2.2 connects emulator to your PC):", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Spacer(modifier = Modifier.height(4.dp))
              OutlinedTextField(
                value = inputOllamaUrl,
                onValueChange = { inputOllamaUrl = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("http://10.0.2.2:11434", fontSize = 12.sp) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
              )

              Spacer(modifier = Modifier.height(8.dp))

              Text("Llama Model Tag:", fontSize = 11.sp, color = MaterialTheme.colorScheme.onSurfaceVariant)
              Spacer(modifier = Modifier.height(4.dp))
              OutlinedTextField(
                value = inputOllamaModel,
                onValueChange = { inputOllamaModel = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("llama3.2, llama3.3, mistral", fontSize = 12.sp) },
                singleLine = true,
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
              )

              if (ollamaTestStatus != null) {
                Spacer(modifier = Modifier.height(8.dp))
                Surface(
                  shape = RoundedCornerShape(8.dp),
                  color = MaterialTheme.colorScheme.surface.copy(alpha = 0.9f),
                  modifier = Modifier.fillMaxWidth()
                ) {
                  Text(
                    text = ollamaTestStatus ?: "",
                    fontSize = 11.sp,
                    fontWeight = FontWeight.Medium,
                    color = if (ollamaTestStatus?.startsWith("✅") == true) EmeraldSuccess else MaterialTheme.colorScheme.onSurface,
                    modifier = Modifier.padding(8.dp)
                  )
                }
              }

              Spacer(modifier = Modifier.height(10.dp))

              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                OutlinedButton(
                  onClick = { viewModel.testLocalOllamaConnection(inputOllamaUrl) },
                  shape = RoundedCornerShape(8.dp),
                  modifier = Modifier.weight(1f)
                ) {
                  Text("Test Ollama", fontSize = 11.sp)
                }

                Button(
                  onClick = { viewModel.setLocalOllamaConfig(inputOllamaUrl, inputOllamaModel) },
                  colors = ButtonDefaults.buttonColors(containerColor = PaletteDeepSage),
                  shape = RoundedCornerShape(8.dp),
                  modifier = Modifier.weight(1f)
                ) {
                  Text("Save Llama", fontSize = 11.sp)
                }
              }
            }
          }

          Spacer(modifier = Modifier.height(18.dp))

          Text(
            text = "API Key Configuration",
            fontWeight = FontWeight.Bold,
            fontSize = 13.sp,
            color = MaterialTheme.colorScheme.onSurface
          )
          Spacer(modifier = Modifier.height(6.dp))

          // --- 1. Google Gemini Key ---
          Card(
            shape = RoundedCornerShape(12.dp),
            colors = CardDefaults.cardColors(containerColor = PalettePaleCeladon.copy(alpha = 0.3f)),
            border = androidx.compose.foundation.BorderStroke(1.dp, PaletteMediumSage.copy(alpha = 0.4f)),
            modifier = Modifier.fillMaxWidth()
          ) {
            Column(modifier = Modifier.padding(12.dp)) {
              Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
              ) {
                Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                  Icon(Icons.Default.Key, contentDescription = null, tint = PaletteDeepSage, modifier = Modifier.size(18.dp))
                  Spacer(modifier = Modifier.width(6.dp))
                  Text("Google Gemini API Key", fontWeight = FontWeight.Bold, fontSize = 13.sp, maxLines = 1, overflow = TextOverflow.Ellipsis)
                }
                Surface(
                  shape = RoundedCornerShape(6.dp),
                  color = if (customGeminiKey.isNotBlank()) EmeraldSuccess.copy(alpha = 0.15f) else MaterialTheme.colorScheme.surfaceVariant
                ) {
                  Text(
                    text = if (customGeminiKey.isNotBlank()) "Custom Active" else "Default Active",
                    fontSize = 10.sp,
                    fontWeight = FontWeight.Bold,
                    color = if (customGeminiKey.isNotBlank()) EmeraldSuccess else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
                  )
                }
              }
              Spacer(modifier = Modifier.height(8.dp))
              OutlinedTextField(
                value = inputGeminiKey,
                onValueChange = { inputGeminiKey = it },
                modifier = Modifier.fillMaxWidth(),
                placeholder = { Text("AIzaSy...", fontSize = 12.sp) },
                singleLine = true,
                visualTransformation = if (showGeminiKeyPassword) androidx.compose.ui.text.input.VisualTransformation.None else androidx.compose.ui.text.input.PasswordVisualTransformation(),
                trailingIcon = {
                  IconButton(onClick = { showGeminiKeyPassword = !showGeminiKeyPassword }) {
                    Icon(if (showGeminiKeyPassword) Icons.Default.VisibilityOff else Icons.Default.Visibility, contentDescription = "Toggle key", modifier = Modifier.size(18.dp))
                  }
                },
                textStyle = androidx.compose.ui.text.TextStyle(fontSize = 12.sp)
              )
              Spacer(modifier = Modifier.height(8.dp))
              Row(modifier = Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                Button(
                  onClick = { viewModel.saveCustomGeminiKey(inputGeminiKey) },
                  shape = RoundedCornerShape(8.dp),
                  modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                ) {
                  Text("Save Key", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
                OutlinedButton(
                  onClick = { viewModel.testApiConnection("GEMINI", inputGeminiKey.ifBlank { viewModel.repository.geminiService.getGeminiApiKey() }) },
                  shape = RoundedCornerShape(8.dp),
                  modifier = Modifier
                    .weight(1f)
                    .heightIn(min = 44.dp)
                ) {
                  Text("Test Gemini", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
                }
                if (customGeminiKey.isNotBlank()) {
                  TextButton(
                    onClick = {
                      inputGeminiKey = ""
                      viewModel.saveCustomGeminiKey("")
                    },
                    modifier = Modifier.heightIn(min = 44.dp)
                  ) {
                    Text("Clear", fontSize = 11.sp, color = MaterialTheme.colorScheme.error, maxLines = 1, softWrap = false)
                  }
                }
              }
            }
          }

          // Live API Test Feedback Message
          apiTestStatus?.let { status ->
            Spacer(modifier = Modifier.height(12.dp))
            Surface(
              shape = RoundedCornerShape(10.dp),
              color = if (status.contains("successful") || status.contains("verified")) EmeraldSuccess.copy(alpha = 0.15f) else MaterialTheme.colorScheme.errorContainer.copy(alpha = 0.4f),
              border = androidx.compose.foundation.BorderStroke(1.dp, if (status.contains("successful") || status.contains("verified")) EmeraldSuccess else MaterialTheme.colorScheme.error),
              modifier = Modifier.fillMaxWidth()
            ) {
              Row(
                modifier = Modifier.padding(10.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.SpaceBetween
              ) {
                Text(
                  text = status,
                  fontSize = 12.sp,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.onSurface,
                  modifier = Modifier.weight(1f)
                )
                IconButton(
                  onClick = { viewModel.clearApiTestStatus() },
                  modifier = Modifier.size(24.dp)
                ) {
                  Icon(Icons.Default.Delete, contentDescription = "Close status", modifier = Modifier.size(14.dp))
                }
              }
            }
          }
        }
      }
    }

    // 2. Subject & Course Manager
    item {
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
      ) {
        Column(modifier = Modifier.padding(18.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "Enrolled Subjects (${subjects.size})",
              fontWeight = FontWeight.Bold,
              style = MaterialTheme.typography.titleMedium,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis,
              modifier = Modifier
                .weight(1f)
                .padding(end = 8.dp)
            )
            Button(
              onClick = { showAddSubjectDialog = true },
              shape = RoundedCornerShape(10.dp),
              modifier = Modifier.heightIn(min = 44.dp)
            ) {
              Icon(Icons.Default.Add, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(4.dp))
              Text("Add Subject", fontSize = 12.sp, maxLines = 1, softWrap = false)
            }
          }

          Spacer(modifier = Modifier.height(12.dp))

          subjects.forEach { sub ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
              horizontalArrangement = Arrangement.SpaceBetween,
              verticalAlignment = Alignment.CenterVertically
            ) {
              Row(verticalAlignment = Alignment.CenterVertically, modifier = Modifier.weight(1f)) {
                Surface(
                  shape = RoundedCornerShape(6.dp),
                  color = MaterialTheme.colorScheme.primaryContainer,
                  modifier = Modifier.width(82.dp)
                ) {
                  Text(
                    text = sub.code,
                    fontWeight = FontWeight.Bold,
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onPrimaryContainer,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    textAlign = androidx.compose.ui.text.style.TextAlign.Center,
                    modifier = Modifier.padding(horizontal = 6.dp, vertical = 4.dp)
                  )
                }
                Spacer(modifier = Modifier.width(12.dp))
                Text(
                  text = sub.name,
                  style = MaterialTheme.typography.bodyMedium.copy(fontWeight = FontWeight.SemiBold),
                  maxLines = 1,
                  overflow = TextOverflow.Ellipsis,
                  modifier = Modifier.weight(1f)
                )
              }

              IconButton(onClick = { viewModel.deleteSubject(sub) }, modifier = Modifier.size(48.dp)) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.error, modifier = Modifier.size(18.dp))
              }
            }
          }
        }
      }
    }

    // 3. Data Persistence & Sample Data
    item {
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 6.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 2.dp)
      ) {
        Column(modifier = Modifier.padding(18.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Local Database Persistence", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
          }

          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = "All schedules, study topics, quiz attempts, and spaced repetition intervals are stored in on-device SQLite via Room. Your progress is completely preserved across sessions.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )

          Spacer(modifier = Modifier.height(14.dp))
          OutlinedButton(
            onClick = { viewModel.seedSampleData() },
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .fillMaxWidth()
              .height(44.dp)
          ) {
            Text("Reload Sample University Courses & Topics", fontWeight = FontWeight.SemiBold)
          }

          Spacer(modifier = Modifier.height(10.dp))
          OutlinedButton(
            onClick = { showDeleteSampleDataDialog = true },
            shape = RoundedCornerShape(10.dp),
            colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
            border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error.copy(alpha = 0.5f)),
            modifier = Modifier
              .fillMaxWidth()
              .height(44.dp)
          ) {
            Icon(Icons.Default.Delete, contentDescription = null, modifier = Modifier.size(18.dp), tint = MaterialTheme.colorScheme.error)
            Spacer(modifier = Modifier.width(6.dp))
            Text("Delete All Sample Data", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
          }
        }
      }
    }
  }

  if (showAddSubjectDialog) {
    AddSubjectDialog(
      onDismiss = { showAddSubjectDialog = false },
      onConfirm = { name, code ->
        viewModel.addSubject(name, code, "#2563EB")
        showAddSubjectDialog = false
      }
    )
  }

  if (showEditProfileDialog) {
    AlertDialog(
      onDismissRequest = { showEditProfileDialog = false },
      title = { Text("Edit Academic Profile", fontWeight = FontWeight.Bold) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
          OutlinedTextField(
            value = editName,
            onValueChange = { editName = it },
            label = { Text("Full Name") },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
          )
          OutlinedTextField(
            value = editEmail,
            onValueChange = { editEmail = it },
            label = { Text("Academic Email") },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
          )
          OutlinedTextField(
            value = editUniversity,
            onValueChange = { editUniversity = it },
            label = { Text("University / Institution") },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
          )
          OutlinedTextField(
            value = editRole,
            onValueChange = { editRole = it },
            label = { Text("Academic Major / Role") },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
          )
        }
      },
      confirmButton = {
        Button(
          onClick = {
            viewModel.updateProfile(editName, editEmail, editUniversity, editRole)
            showEditProfileDialog = false
          },
          colors = ButtonDefaults.buttonColors(containerColor = com.example.ui.theme.PaletteDeepSage),
          shape = RoundedCornerShape(10.dp)
        ) {
          Text("Save Profile")
        }
      },
      dismissButton = {
        TextButton(onClick = { showEditProfileDialog = false }) {
          Text("Cancel")
        }
      }
    )
  }

  if (showDeleteCurrentAccountDialog) {
    AlertDialog(
      onDismissRequest = { showDeleteCurrentAccountDialog = false },
      title = { Text("Delete Current Account", fontWeight = FontWeight.Bold) },
      text = {
        Text("Are you sure you want to permanently delete the profile for ${currentUser.name} (${currentUser.email})? All local coursework, study records, timetable sessions, and cloud synced data for this student will be permanently removed.")
      },
      confirmButton = {
        Button(
          onClick = {
            showDeleteCurrentAccountDialog = false
            viewModel.deleteUser(currentUser.id)
          },
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
          shape = RoundedCornerShape(10.dp)
        ) {
          Text("Delete Account", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold)
        }
      },
      dismissButton = {
        TextButton(onClick = { showDeleteCurrentAccountDialog = false }) {
          Text("Cancel")
        }
      }
    )
  }

  accountToDelete?.let { target ->
    AlertDialog(
      onDismissRequest = { accountToDelete = null },
      title = { Text("Delete Student Profile", fontWeight = FontWeight.Bold) },
      text = {
        Text("Are you sure you want to delete the profile for ${target.name} (${target.email} • ${target.studentId})? All courses, study tasks, and sessions for this student will be removed.")
      },
      confirmButton = {
        Button(
          onClick = {
            val id = target.id
            accountToDelete = null
            viewModel.deleteUser(id)
          },
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
          shape = RoundedCornerShape(10.dp)
        ) {
          Text("Delete Profile", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold)
        }
      },
      dismissButton = {
        TextButton(onClick = { accountToDelete = null }) {
          Text("Cancel")
        }
      }
    )
  }

  if (showDeleteSampleDataDialog) {
    AlertDialog(
      onDismissRequest = { showDeleteSampleDataDialog = false },
      icon = { Icon(Icons.Default.Delete, contentDescription = null, tint = MaterialTheme.colorScheme.error) },
      title = { Text("Delete All Sample Data?", fontWeight = FontWeight.Bold) },
      text = {
        Text("This will permanently remove all sample subjects, timetable sessions, tasks, and topics from your profile. Once deleted, this sample data will never return automatically.")
      },
      confirmButton = {
        Button(
          onClick = {
            viewModel.deleteAllSampleData()
            showDeleteSampleDataDialog = false
          },
          colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error),
          shape = RoundedCornerShape(10.dp)
        ) {
          Text("Delete Permanently", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold)
        }
      },
      dismissButton = {
        TextButton(onClick = { showDeleteSampleDataDialog = false }) {
          Text("Cancel")
        }
      }
    )
  }
}

@Composable
fun AddSubjectDialog(
  onDismiss: () -> Unit,
  onConfirm: (String, String) -> Unit
) {
  var name by remember { mutableStateOf("") }
  var code by remember { mutableStateOf("") }

  AlertDialog(
    onDismissRequest = onDismiss,
    title = { Text("Add Subject / Course", fontWeight = FontWeight.Bold) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        OutlinedTextField(
          value = code,
          onValueChange = { code = it },
          label = { Text("Course Code (e.g. CS201, BIO101)") },
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.fillMaxWidth(),
          singleLine = true
        )
        OutlinedTextField(
          value = name,
          onValueChange = { name = it },
          label = { Text("Course Name (e.g. Operating Systems)") },
          shape = RoundedCornerShape(12.dp),
          modifier = Modifier.fillMaxWidth(),
          singleLine = true
        )
      }
    },
    confirmButton = {
      Button(
        onClick = {
          if (name.isNotBlank() && code.isNotBlank()) {
            onConfirm(name, code)
          }
        },
        shape = RoundedCornerShape(10.dp)
      ) {
        Text("Add Subject")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    }
  )
}
