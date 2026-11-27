package com.example.ui.screens

import com.example.platform.LocalDeviceSyncCard
import androidx.compose.ui.platform.testTag
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
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
import androidx.compose.material.icons.filled.BrightnessAuto
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.DarkMode
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Key
import androidx.compose.material.icons.filled.LightMode
import androidx.compose.material.icons.filled.PersonAdd
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.SwapHoriz
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material.icons.automirrored.filled.Logout
import androidx.compose.material3.RadioButton
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.AlertDialog
import com.example.ui.theme.Button
import androidx.compose.material3.ButtonDefaults
import com.example.ui.theme.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.example.ui.theme.OutlinedButton
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
  val deviceSyncCard = LocalDeviceSyncCard.current

  var showAddSubjectDialog by remember { mutableStateOf(false) }
  var showEditProfileDialog by remember { mutableStateOf(false) }
  var accountToDelete by remember { mutableStateOf<com.example.data.model.UserProfile?>(null) }
  var showDeleteCurrentAccountDialog by remember { mutableStateOf(false) }
  var showDeleteSampleDataDialog by remember { mutableStateOf(false) }

  var editName by remember(currentUser) { mutableStateOf(currentUser.name) }
  var editEmail by remember(currentUser) { mutableStateOf(currentUser.email) }
  var editUniversity by remember(currentUser) { mutableStateOf(currentUser.university) }
  var editRole by remember(currentUser) { mutableStateOf(currentUser.role) }

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
          .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
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
              horizontalArrangement = Arrangement.spacedBy(16.dp),
              modifier = Modifier.weight(1f)
            ) {
              Box(
                modifier = Modifier
                  .size(48.dp)
                  .clip(CircleShape)
                  .background(MaterialTheme.colorScheme.primary),
                contentAlignment = Alignment.Center
              ) {
                Text(
                  text = currentUser.avatarInitials,
                  fontWeight = FontWeight.Bold,
                  color = MaterialTheme.colorScheme.onPrimary,
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
                      color = MaterialTheme.colorScheme.primary,
                      fontWeight = FontWeight.SemiBold,
                      maxLines = 1,
                      overflow = TextOverflow.Ellipsis
                    )
                  }
                }
              }
            }

            Spacer(modifier = Modifier.width(8.dp))

            val badgeColor = if (isGuestMode) MaterialTheme.colorScheme.primary else EmeraldSuccess
            Surface(
              shape = RoundedCornerShape(20.dp),
              color = badgeColor.copy(alpha = 0.12f),
              border = androidx.compose.foundation.BorderStroke(1.dp, badgeColor.copy(alpha = 0.35f))
            ) {
              Row(
                modifier = Modifier.padding(horizontal = 12.dp, vertical = 4.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
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

          Spacer(modifier = Modifier.height(16.dp))

          if (isGuestMode) {
            Button(
              onClick = { viewModel.navigateTo(com.example.ui.viewmodel.Screen.Login) },
              shape = RoundedCornerShape(12.dp),
              colors = ButtonDefaults.buttonColors(
                containerColor = MaterialTheme.colorScheme.primary,
                contentColor = MaterialTheme.colorScheme.onPrimary
              ),
              modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .testTag("settings_sign_in_button")
            ) {
              Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
              Spacer(modifier = Modifier.width(8.dp))
              Text(
                text = "Sign in or create a profile",
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
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Icon(
                  Icons.Default.Devices,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = "Guest mode",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                  )
                  Text(
                    text = "Sign in to keep a profile on this device.",
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
              horizontalArrangement = Arrangement.spacedBy(12.dp)
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
                  containerColor = MaterialTheme.colorScheme.primaryContainer,
                  contentColor = MaterialTheme.colorScheme.onPrimaryContainer
                ),
                modifier = Modifier
                  .weight(1f)
                  .heightIn(min = 44.dp)
              ) {
                Icon(Icons.Default.Edit, contentDescription = null, modifier = Modifier.size(16.dp))
                Spacer(modifier = Modifier.width(8.dp))
                Text("Edit profile", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
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
                Spacer(modifier = Modifier.width(8.dp))
                Text("Sign out", fontSize = 12.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
              }
            }

            Spacer(modifier = Modifier.height(8.dp))

            Row(
              modifier = Modifier.fillMaxWidth(),
              horizontalArrangement = Arrangement.spacedBy(12.dp)
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
                Icon(Icons.Default.Devices, contentDescription = null, modifier = Modifier.size(15.dp), tint = MaterialTheme.colorScheme.primary)
                Spacer(modifier = Modifier.width(8.dp))
                Text("Cloud sync", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
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
                Spacer(modifier = Modifier.width(8.dp))
                Text("Delete account", fontSize = 11.sp, fontWeight = FontWeight.Bold, maxLines = 1, softWrap = false)
              }
            }

            Spacer(modifier = Modifier.height(12.dp))

            Surface(
              shape = RoundedCornerShape(12.dp),
              color = MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.45f),
              modifier = Modifier.fillMaxWidth()
            ) {
              Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Icon(
                  Icons.Default.Devices,
                  contentDescription = null,
                  tint = MaterialTheme.colorScheme.primary,
                  modifier = Modifier.size(20.dp)
                )
                Column(modifier = Modifier.weight(1f)) {
                  Text(
                    text = if (currentUser.authProvider == "Google Sign-In") "Google profile" else "Email profile",
                    fontWeight = FontWeight.Bold,
                    fontSize = 12.sp,
                    color = MaterialTheme.colorScheme.onSurface
                  )
                  Text(
                    text = if (currentUser.authProvider == "Google Sign-In") "Saved on this device and backed up to the cloud." else "Saved on this device only.",
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

    // 0.2. Appearance & Theme (Dark Mode)
    item {
      val currentThemeMode by viewModel.themeMode.collectAsState()
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
      ) {
        Column(modifier = Modifier.padding(16.dp)) {
          Text(
            text = "Appearance",
            fontWeight = FontWeight.Bold,
            style = MaterialTheme.typography.titleMedium,
            color = MaterialTheme.colorScheme.onSurface
          )
          Text(
            text = "Theme",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )
          Spacer(modifier = Modifier.height(16.dp))
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(8.dp)
          ) {
            listOf(
              Triple("system", "System", Icons.Default.BrightnessAuto),
              Triple("light", "Light", Icons.Default.LightMode),
              Triple("dark", "Dark", Icons.Default.DarkMode)
            ).forEach { (mode, label, icon) ->
              val isSelected = currentThemeMode == mode
              Surface(
                shape = RoundedCornerShape(12.dp),
                color = if (isSelected) MaterialTheme.colorScheme.primaryContainer else MaterialTheme.colorScheme.surfaceVariant,
                border = androidx.compose.foundation.BorderStroke(
                  1.dp,
                  if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
                ),
                modifier = Modifier
                  .weight(1f)
                  .heightIn(min = 44.dp)
                  .clip(RoundedCornerShape(12.dp)).clickable { viewModel.setThemeMode(mode) }
              ) {
                Row(
                  modifier = Modifier.padding(horizontal = 8.dp, vertical = 12.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.Center
                ) {
                  Icon(
                    imageVector = icon,
                    contentDescription = label,
                    tint = if (isSelected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                    modifier = Modifier.size(16.dp)
                  )
                  Spacer(modifier = Modifier.width(8.dp))
                  Text(
                    text = label,
                    fontSize = 12.sp,
                    fontWeight = if (isSelected) FontWeight.Bold else FontWeight.Medium,
                    color = if (isSelected) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant
                  )
                }
              }
            }
          }
          // The desktop adds its backdrop choices here.
          com.example.platform.LocalAppearanceExtras.current?.invoke()
        }
      }
    }

    // 0.5. Stored Accounts Switcher
    item {
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
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
                text = "Profiles (${savedAccounts.size})",
                fontWeight = FontWeight.Bold,
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurface
              )
              Text(
                text = "Profiles on this device",
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
                text = "No saved profiles yet.",
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
                  color = if (isCurrent) MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.5f) else MaterialTheme.colorScheme.surfaceVariant.copy(alpha = 0.35f),
                  border = androidx.compose.foundation.BorderStroke(
                    1.dp,
                    if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outlineVariant
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
                      horizontalArrangement = Arrangement.spacedBy(12.dp),
                      modifier = Modifier.weight(1f)
                    ) {
                      Box(
                        modifier = Modifier
                          .size(38.dp)
                          .clip(CircleShape)
                          .background(if (isCurrent) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.outline),
                        contentAlignment = Alignment.Center
                      ) {
                        Text(
                          text = account.avatarInitials,
                          fontWeight = FontWeight.Bold,
                          color = if (isCurrent) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurface,
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
                            Spacer(modifier = Modifier.width(8.dp))
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
                                modifier = Modifier.padding(horizontal = 8.dp, vertical = 2.dp)
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
                          tint = MaterialTheme.colorScheme.onSurfaceVariant,
                          modifier = Modifier.size(18.dp)
                        )
                      }
                    }
                  }
                }
              }
            }
          }

          Spacer(modifier = Modifier.height(12.dp))

          OutlinedButton(
            onClick = { viewModel.navigateTo(com.example.ui.viewmodel.Screen.Login) },
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .fillMaxWidth()
              .heightIn(min = 48.dp)
          ) {
            Icon(Icons.Default.PersonAdd, contentDescription = null, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text(
              text = if (savedAccounts.isEmpty()) "Sign in or create a profile" else "Add another profile",
              fontSize = 12.sp,
              fontWeight = FontWeight.SemiBold,
              maxLines = 1,
              overflow = TextOverflow.Ellipsis
            )
          }
        }
      }
    }

    deviceSyncCard?.let { card -> item { card() } }
    item { AiSettingsCard(viewModel) }

    // 2. Subject & Course Manager
    item {
      Card(
        modifier = Modifier
          .fillMaxWidth()
          .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
      ) {
        Column(modifier = Modifier.padding(20.dp)) {
          Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
          ) {
            Text(
              text = "Subjects (${subjects.size})",
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
              Text("Add subject", fontSize = 12.sp, maxLines = 1, softWrap = false)
            }
          }

          Spacer(modifier = Modifier.height(12.dp))

          subjects.forEach { sub ->
            Row(
              modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 8.dp),
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
                    modifier = Modifier.padding(horizontal = 8.dp, vertical = 4.dp)
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
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.size(18.dp))
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
          .padding(horizontal = 16.dp, vertical = 4.dp),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
      ) {
        Column(modifier = Modifier.padding(20.dp)) {
          Row(verticalAlignment = Alignment.CenterVertically) {
            Icon(Icons.Default.Storage, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(20.dp))
            Spacer(modifier = Modifier.width(8.dp))
            Text("Data", fontWeight = FontWeight.Bold, style = MaterialTheme.typography.titleSmall)
          }

          Spacer(modifier = Modifier.height(8.dp))
          Text(
            text = "Everything is stored on this device.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
          )

          Spacer(modifier = Modifier.height(16.dp))
          OutlinedButton(
            onClick = { viewModel.seedSampleData() },
            shape = RoundedCornerShape(10.dp),
            modifier = Modifier
              .fillMaxWidth()
              .height(44.dp)
          ) {
            Text("Load sample data", fontWeight = FontWeight.SemiBold)
          }

          Spacer(modifier = Modifier.height(12.dp))
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
            Spacer(modifier = Modifier.width(8.dp))
            Text("Delete all data", fontWeight = FontWeight.SemiBold, color = MaterialTheme.colorScheme.error)
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
      title = { Text("Edit profile", fontWeight = FontWeight.Bold) },
      text = {
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
          OutlinedTextField(
            value = editName,
            onValueChange = { editName = it },
            label = { Text("Full name") },
            shape = RoundedCornerShape(12.dp),
            modifier = Modifier.fillMaxWidth(),
            singleLine = true
          )
          OutlinedTextField(
            value = editEmail,
            onValueChange = { editEmail = it },
            label = { Text("Email") },
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
          colors = ButtonDefaults.buttonColors(
            containerColor = MaterialTheme.colorScheme.primary,
            contentColor = MaterialTheme.colorScheme.onPrimary
          ),
          shape = RoundedCornerShape(10.dp)
        ) {
          Text("Save profile")
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
      title = { Text("Delete this profile", fontWeight = FontWeight.Bold) },
      text = {
        Text("Delete ${currentUser.name} (${currentUser.email}) and all of its data? This can't be undone.")
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
          Text("Delete account", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold)
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
      title = { Text("Delete profile", fontWeight = FontWeight.Bold) },
      text = {
        Text("Delete ${target.name} (${target.email}) and all of its data? This can't be undone.")
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
          Text("Delete profile", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold)
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
      title = { Text("Delete all data?", fontWeight = FontWeight.Bold) },
      text = {
        Text("Delete all subjects, sessions, tasks and topics in this profile? This can't be undone, and sample data won't come back on its own.")
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
          Text("Delete permanently", color = MaterialTheme.colorScheme.onError, fontWeight = FontWeight.Bold)
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
    title = { Text("Add subject", fontWeight = FontWeight.Bold) },
    text = {
      Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
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
        Text("Add subject")
      }
    },
    dismissButton = {
      TextButton(onClick = onDismiss) {
        Text("Cancel")
      }
    }
  )
}
