package com.example.ui.screens

import com.example.shared.resources.Res
import com.example.shared.resources.studypilot_logo
import com.example.platform.LocalPlatformActions
import com.example.platform.PlatformBackHandler
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Badge
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Devices
import androidx.compose.material.icons.filled.Email
import androidx.compose.material.icons.filled.Laptop
import androidx.compose.material.icons.filled.Lock
import androidx.compose.material.icons.filled.Person
import androidx.compose.material.icons.filled.School
import androidx.compose.material.icons.filled.Smartphone
import androidx.compose.material.icons.filled.Speed
import androidx.compose.material.icons.filled.Visibility
import androidx.compose.material.icons.filled.VisibilityOff
import androidx.compose.material3.AlertDialog
import com.example.ui.theme.Button
import androidx.compose.material3.ButtonDefaults
import com.example.ui.theme.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CheckboxDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import com.example.ui.theme.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Surface
import androidx.compose.material3.Tab
import com.example.ui.theme.TabRow
import androidx.compose.material3.TabRowDefaults
import androidx.compose.material3.TabRowDefaults.tabIndicatorOffset
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import kotlinx.coroutines.launch
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.testTag
import org.jetbrains.compose.resources.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.input.VisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.ui.theme.EmeraldSuccess
import com.example.ui.theme.PaletteCreamIvory
import com.example.ui.theme.PaletteDarkText
import com.example.ui.theme.PaletteDeepSage
import com.example.ui.theme.PaletteMediumSage
import com.example.ui.theme.PaletteMintFoam
import com.example.ui.theme.PalettePaleCeladon
import com.example.ui.viewmodel.StudyPilotViewModel

enum class AuthMode {
  SIGN_IN,
  SIGN_UP
}

@Composable
fun LoginScreen(
  viewModel: StudyPilotViewModel,
  onLoginSuccess: () -> Unit
) {
  var authMode by remember { mutableStateOf(AuthMode.SIGN_IN) }

  // Input states (blank by default unless a logged-in user previously opted to remember their email)
  var email by remember { mutableStateOf(viewModel.getRememberedEmail()) }
  var password by remember { mutableStateOf("") }
  var name by remember { mutableStateOf("") }
  var university by remember { mutableStateOf("") }
  var studentId by remember { mutableStateOf("") }
  var confirmPassword by remember { mutableStateOf("") }
  var rememberMe by remember { mutableStateOf(true) }

  var isPasswordVisible by remember { mutableStateOf(false) }
  var isConfirmPasswordVisible by remember { mutableStateOf(false) }

  var errorMessage by remember { mutableStateOf<String?>(null) }
  var showForgotPasswordDialog by remember { mutableStateOf(false) }

  val actions = LocalPlatformActions.current
  val coroutineScope = rememberCoroutineScope()
  var isSigningInGoogle by remember { mutableStateOf(false) }

  val handleGoogleSignIn: () -> Unit = {
    if (!isSigningInGoogle) {
      isSigningInGoogle = true
      coroutineScope.launch {
        try {
          val signIn = actions.googleSignIn
          val account = if (signIn == null) {
            errorMessage = "Google sign-in is only available in the phone app. Use email or guest mode here."
            null
          } else {
            signIn()
          }
          if (account == null) {
            isSigningInGoogle = false
          } else {
            viewModel.signInWithGoogle(
              idToken = account.idToken,
              displayName = account.displayName,
              email = account.email,
              onComplete = { success, error ->
                isSigningInGoogle = false
                if (success) {
                  onLoginSuccess()
                } else {
                  errorMessage = error ?: "Google Sign-In failed"
                }
              }
            )
          }
        } catch (e: Exception) {
          isSigningInGoogle = false
          com.example.platform.Log.w("LoginScreen", "Google Sign-In unavailable: ${e.message}")
          errorMessage = e.message ?: "Google sign-in failed. Use email or guest mode instead."
        }
      }
    }
  }

  BoxWithConstraints(
    modifier = Modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
  ) {
    val isWideScreen = maxWidth >= 768.dp

    if (isWideScreen) {
      // Laptop / Desktop dual-pane layout
      Row(
        modifier = Modifier
          .fillMaxSize()
          .padding(24.dp),
        horizontalArrangement = Arrangement.spacedBy(24.dp),
        verticalAlignment = Alignment.CenterVertically
      ) {
        // Left Column: Branding, Academic Command Center perks & Laptop+Mobile sync
        Card(
          modifier = Modifier
            .weight(1f)
            .fillMaxHeight(),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primary),
          shape = RoundedCornerShape(24.dp),
          elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
          Column(
            modifier = Modifier
              .fillMaxSize()
              .padding(32.dp)
              .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.SpaceBetween
          ) {
            Column {
              // App Logo & Brand Header
              Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
              ) {
                Surface(
                  shape = RoundedCornerShape(14.dp),
                  color = Color(0xFF17253B),
                  modifier = Modifier
                    .size(56.dp)
                    .testTag("wide_login_logo")
                ) {
                  Image(
                    painter = painterResource(Res.drawable.studypilot_logo),
                    contentDescription = "StudyPilot Logo",
                    contentScale = ContentScale.Fit,
                    modifier = Modifier.fillMaxSize()
                  )
                }
                Column {
                  Text(
                    text = "StudyPilot",
                    fontSize = 26.sp,
                    fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimary
                  )
                  Text(
                    text = "Study planner and AI tutor",
                    fontSize = 13.sp,
                    color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f)
                  )
                }
              }

              Spacer(modifier = Modifier.height(28.dp))

              Surface(
                shape = RoundedCornerShape(16.dp),
                color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.12f),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.25f)),
                modifier = Modifier.fillMaxWidth()
              ) {
                Row(
                  modifier = Modifier.padding(16.dp),
                  verticalAlignment = Alignment.CenterVertically,
                  horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                  Surface(
                    shape = CircleShape,
                    color = MaterialTheme.colorScheme.primaryContainer,
                    modifier = Modifier.size(42.dp)
                  ) {
                    Box(contentAlignment = Alignment.Center) {
                      Icon(
                        imageVector = Icons.Default.Devices,
                        contentDescription = null,
                        tint = MaterialTheme.colorScheme.primary,
                        modifier = Modifier.size(22.dp)
                      )
                    }
                  }
                  Column {
                    Text(
                      text = "Works on phones and tablets",
                      fontSize = 14.sp,
                      fontWeight = FontWeight.Bold,
                      color = MaterialTheme.colorScheme.onPrimary
                    )
                    Text(
                      text = "Wider layouts on tablets and foldables, with alarms for every class on your phone.",
                      fontSize = 12.sp,
                      color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.85f),
                      lineHeight = 16.sp
                    )
                  }
                }
              }

              Spacer(modifier = Modifier.height(24.dp))

              Text(
                text = "What it does",
                fontSize = 15.sp,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onPrimary
              )
              Spacer(modifier = Modifier.height(12.dp))

              AcademicPerkItem(
                icon = Icons.Default.AutoAwesome,
                title = "AI tutor",
                desc = "Runs on your phone or your own PC. No account or API key needed."
              )
              Spacer(modifier = Modifier.height(12.dp))
              AcademicPerkItem(
                icon = Icons.Default.Speed,
                title = "Spaced repetition",
                desc = "Quizzes put the topics you got wrong first."
              )
              Spacer(modifier = Modifier.height(12.dp))
              AcademicPerkItem(
                icon = Icons.Default.Laptop,
                title = "Timetable and alarms",
                desc = "Weekly schedules, reusable day templates and exact alarms."
              )
            }

            // Bottom trust guarantee
            Surface(
              shape = RoundedCornerShape(12.dp),
              color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.15f),
              modifier = Modifier
                .fillMaxWidth()
                .padding(top = 20.dp)
            ) {
              Row(
                modifier = Modifier.padding(12.dp),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(8.dp)
              ) {
                Icon(
                  imageVector = Icons.Default.CheckCircle,
                  contentDescription = null,
                  tint = EmeraldSuccess,
                  modifier = Modifier.size(18.dp)
                )
                Text(
                  text = "Email profiles stay on this device. Google sign-in adds cloud backup.",
                  fontSize = 11.sp,
                  color = MaterialTheme.colorScheme.onPrimary
                )
              }
            }
          }
        }

        // Right Column: Auth Form Card
        Card(
          modifier = Modifier
            .weight(1f)
            .fillMaxHeight(),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          shape = RoundedCornerShape(24.dp),
          elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
          AuthFormContent(
            authMode = authMode,
            onAuthModeChange = {
              authMode = it
              errorMessage = null
            },
            email = email,
            onEmailChange = { email = it; errorMessage = null },
            password = password,
            onPasswordChange = { password = it; errorMessage = null },
            name = name,
            onNameChange = { name = it; errorMessage = null },
            university = university,
            onUniversityChange = { university = it; errorMessage = null },
            studentId = studentId,
            onStudentIdChange = { studentId = it; errorMessage = null },
            confirmPassword = confirmPassword,
            onConfirmPasswordChange = { confirmPassword = it; errorMessage = null },
            rememberMe = rememberMe,
            onRememberMeChange = { rememberMe = it },
            isPasswordVisible = isPasswordVisible,
            onTogglePasswordVisibility = { isPasswordVisible = !isPasswordVisible },
            isConfirmPasswordVisible = isConfirmPasswordVisible,
            onToggleConfirmPasswordVisibility = { isConfirmPasswordVisible = !isConfirmPasswordVisible },
            errorMessage = errorMessage,
            onSignInClick = {
              val success = viewModel.login(email, password, rememberMe)
              if (success) {
                onLoginSuccess()
              } else {
                errorMessage = viewModel.lastAuthError ?: "Couldn't sign in."
              }
            },
            onSignUpClick = {
              if (password != confirmPassword) {
                errorMessage = "Passwords do not match."
              } else {
                val success = viewModel.register(name, email, password, university, studentId, rememberMe)
                if (success) {
                  onLoginSuccess()
                } else {
                  errorMessage = viewModel.lastAuthError ?: "Couldn't create the profile."
                }
              }
            },
            onGoogleSignInClick = handleGoogleSignIn,
            onGuestSignInClick = {
              viewModel.loginAsGuest()
              onLoginSuccess()
            },
            onForgotPasswordClick = {
              showForgotPasswordDialog = true
            },
            modifier = Modifier.verticalScroll(rememberScrollState())
          )
        }
      }
    } else {
      // Mobile Single Column Layout
      Column(
        modifier = Modifier
          .fillMaxSize()
          .verticalScroll(rememberScrollState())
          .padding(horizontal = 16.dp, vertical = 20.dp),
        horizontalAlignment = Alignment.CenterHorizontally
      ) {
        Spacer(modifier = Modifier.height(12.dp))

        // Mobile Header Brand
        Surface(
          shape = RoundedCornerShape(16.dp),
          color = Color(0xFF17253B),
          modifier = Modifier
            .size(64.dp)
            .testTag("mobile_login_logo")
        ) {
          Image(
            painter = painterResource(Res.drawable.studypilot_logo),
            contentDescription = "StudyPilot Logo",
            contentScale = ContentScale.Fit,
            modifier = Modifier.fillMaxSize()
          )
        }

        Spacer(modifier = Modifier.height(12.dp))

        Text(
          text = "StudyPilot",
          fontSize = 24.sp,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.onBackground
        )
        Text(
          text = "Study planner and AI tutor",
          fontSize = 12.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Spacer(modifier = Modifier.height(16.dp))

        Surface(
          shape = RoundedCornerShape(12.dp),
          color = MaterialTheme.colorScheme.surfaceVariant,
          border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 480.dp)
        ) {
          Row(
            modifier = Modifier.padding(horizontal = 12.dp, vertical = 8.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.Center
          ) {
            Icon(Icons.Default.Smartphone, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = "Phone", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
            Text(text = " + ", fontSize = 11.sp, color = MaterialTheme.colorScheme.primary)
            Icon(Icons.Default.Laptop, contentDescription = null, tint = MaterialTheme.colorScheme.primary, modifier = Modifier.size(16.dp))
            Spacer(modifier = Modifier.width(4.dp))
            Text(text = "Tablet", fontSize = 11.sp, fontWeight = FontWeight.Bold, color = MaterialTheme.colorScheme.primary)
          }
        }

        Spacer(modifier = Modifier.height(16.dp))

        // Auth Form Card
        Card(
          modifier = Modifier
            .fillMaxWidth()
            .widthIn(max = 500.dp),
          colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surface),
          border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
          shape = RoundedCornerShape(20.dp),
          elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
        ) {
          AuthFormContent(
            authMode = authMode,
            onAuthModeChange = {
              authMode = it
              errorMessage = null
            },
            email = email,
            onEmailChange = { email = it; errorMessage = null },
            password = password,
            onPasswordChange = { password = it; errorMessage = null },
            name = name,
            onNameChange = { name = it; errorMessage = null },
            university = university,
            onUniversityChange = { university = it; errorMessage = null },
            studentId = studentId,
            onStudentIdChange = { studentId = it; errorMessage = null },
            confirmPassword = confirmPassword,
            onConfirmPasswordChange = { confirmPassword = it; errorMessage = null },
            rememberMe = rememberMe,
            onRememberMeChange = { rememberMe = it },
            isPasswordVisible = isPasswordVisible,
            onTogglePasswordVisibility = { isPasswordVisible = !isPasswordVisible },
            isConfirmPasswordVisible = isConfirmPasswordVisible,
            onToggleConfirmPasswordVisibility = { isConfirmPasswordVisible = !isConfirmPasswordVisible },
            errorMessage = errorMessage,
            onSignInClick = {
              val success = viewModel.login(email, password, rememberMe)
              if (success) {
                onLoginSuccess()
              } else {
                errorMessage = "Invalid email or password (min 6 characters)."
              }
            },
            onSignUpClick = {
              if (password != confirmPassword) {
                errorMessage = "Passwords do not match."
              } else {
                val success = viewModel.register(name, email, password, university, studentId, rememberMe)
                if (success) {
                  onLoginSuccess()
                } else {
                  errorMessage = "Please enter valid profile details."
                }
              }
            },
            onGoogleSignInClick = handleGoogleSignIn,
            onGuestSignInClick = {
              viewModel.loginAsGuest()
              onLoginSuccess()
            },
            onForgotPasswordClick = {
              showForgotPasswordDialog = true
            }
          )
        }

        Spacer(modifier = Modifier.height(24.dp))
      }
    }
  }

  // Email profiles have no server behind them, so there is no reset email to send.
  if (showForgotPasswordDialog) {
    AlertDialog(
      onDismissRequest = { showForgotPasswordDialog = false },
      title = { Text(text = "Forgot your password?", fontWeight = FontWeight.Bold, fontSize = 18.sp) },
      text = {
        Text(
          text = "Email profiles are stored only on this device, so a password can't be reset by email. " +
            "You can delete the profile from Settings (this deletes its data) and create it again, " +
            "or use Google sign-in instead.",
          fontSize = 13.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant
        )
      },
      confirmButton = {
        TextButton(onClick = { showForgotPasswordDialog = false }) { Text("OK") }
      }
    )
  }
}

@Composable
private fun AcademicPerkItem(
  icon: androidx.compose.ui.graphics.vector.ImageVector,
  title: String,
  desc: String
) {
  Row(
    verticalAlignment = Alignment.Top,
    horizontalArrangement = Arrangement.spacedBy(12.dp)
  ) {
    Surface(
      shape = CircleShape,
      color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.2f),
      modifier = Modifier.size(34.dp)
    ) {
      Box(contentAlignment = Alignment.Center) {
        Icon(icon, contentDescription = null, tint = MaterialTheme.colorScheme.onPrimary, modifier = Modifier.size(18.dp))
      }
    }
    Column {
      Text(
        text = title,
        fontSize = 13.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onPrimary
      )
      Text(
        text = desc,
        fontSize = 11.sp,
        color = MaterialTheme.colorScheme.onPrimary.copy(alpha = 0.8f),
        lineHeight = 15.sp
      )
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun AuthFormContent(
  authMode: AuthMode,
  onAuthModeChange: (AuthMode) -> Unit,
  email: String,
  onEmailChange: (String) -> Unit,
  password: String,
  onPasswordChange: (String) -> Unit,
  name: String,
  onNameChange: (String) -> Unit,
  university: String,
  onUniversityChange: (String) -> Unit,
  studentId: String,
  onStudentIdChange: (String) -> Unit,
  confirmPassword: String,
  onConfirmPasswordChange: (String) -> Unit,
  rememberMe: Boolean,
  onRememberMeChange: (Boolean) -> Unit,
  isPasswordVisible: Boolean,
  onTogglePasswordVisibility: () -> Unit,
  isConfirmPasswordVisible: Boolean,
  onToggleConfirmPasswordVisibility: () -> Unit,
  errorMessage: String?,
  onSignInClick: () -> Unit,
  onSignUpClick: () -> Unit,
  onGoogleSignInClick: () -> Unit,
  onGuestSignInClick: () -> Unit,
  onForgotPasswordClick: () -> Unit,
  modifier: Modifier = Modifier
) {
  Column(
    modifier = modifier
      .fillMaxWidth()
      .padding(24.dp),
    horizontalAlignment = Alignment.CenterHorizontally
  ) {
    // Mode Switcher Tabs
    Surface(
      shape = RoundedCornerShape(12.dp),
      color = MaterialTheme.colorScheme.surfaceVariant,
      modifier = Modifier.fillMaxWidth()
    ) {
      Row(
        modifier = Modifier.padding(4.dp),
        horizontalArrangement = Arrangement.spacedBy(4.dp)
      ) {
        Surface(
          modifier = Modifier
            .weight(1f)
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onAuthModeChange(AuthMode.SIGN_IN) }
            .testTag("tab_sign_in"),
          color = if (authMode == AuthMode.SIGN_IN) MaterialTheme.colorScheme.primary else Color.Transparent,
          shape = RoundedCornerShape(8.dp)
        ) {
          Box(contentAlignment = Alignment.Center) {
            Text(
              text = "Sign in",
              fontSize = 13.sp,
              fontWeight = if (authMode == AuthMode.SIGN_IN) FontWeight.Bold else FontWeight.Medium,
              color = if (authMode == AuthMode.SIGN_IN) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }

        Surface(
          modifier = Modifier
            .weight(1f)
            .height(40.dp)
            .clip(RoundedCornerShape(8.dp))
            .clickable { onAuthModeChange(AuthMode.SIGN_UP) }
            .testTag("tab_create_account"),
          color = if (authMode == AuthMode.SIGN_UP) MaterialTheme.colorScheme.primary else Color.Transparent,
          shape = RoundedCornerShape(8.dp)
        ) {
          Box(contentAlignment = Alignment.Center) {
            Text(
              text = "Create account",
              fontSize = 13.sp,
              fontWeight = if (authMode == AuthMode.SIGN_UP) FontWeight.Bold else FontWeight.Medium,
              color = if (authMode == AuthMode.SIGN_UP) MaterialTheme.colorScheme.onPrimary else MaterialTheme.colorScheme.onSurfaceVariant
            )
          }
        }
      }
    }

    Spacer(modifier = Modifier.height(20.dp))

    // Form Subheading
    Text(
      text = if (authMode == AuthMode.SIGN_IN) "Sign in to your profile on this device" else "Create a profile on this device",
      fontSize = 12.sp,
      color = MaterialTheme.colorScheme.onSurfaceVariant,
      textAlign = TextAlign.Center
    )

    Spacer(modifier = Modifier.height(16.dp))

    // Error Alert Box
    if (errorMessage != null) {
      Surface(
        color = MaterialTheme.colorScheme.errorContainer,
        shape = RoundedCornerShape(10.dp),
        modifier = Modifier
          .fillMaxWidth()
          .padding(bottom = 12.dp)
      ) {
        Row(
          modifier = Modifier.padding(12.dp),
          verticalAlignment = Alignment.CenterVertically
        ) {
          Text(
            text = errorMessage,
            color = MaterialTheme.colorScheme.onErrorContainer,
            fontSize = 12.sp
          )
        }
      }
    }

    // Input fields for Sign Up
    if (authMode == AuthMode.SIGN_UP) {
      OutlinedTextField(
        value = name,
        onValueChange = onNameChange,
        label = { Text("Full Name *") },
        leadingIcon = { Icon(Icons.Default.Person, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("signup_name_input"),
        colors = OutlinedTextFieldDefaults.colors(
          focusedBorderColor = MaterialTheme.colorScheme.primary,
          focusedLabelColor = MaterialTheme.colorScheme.primary
        )
      )

      Spacer(modifier = Modifier.height(12.dp))

      OutlinedTextField(
        value = university,
        onValueChange = onUniversityChange,
        label = { Text("University / College *") },
        leadingIcon = { Icon(Icons.Default.School, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("signup_university_input"),
        colors = OutlinedTextFieldDefaults.colors(
          focusedBorderColor = MaterialTheme.colorScheme.primary,
          focusedLabelColor = MaterialTheme.colorScheme.primary
        )
      )

      Spacer(modifier = Modifier.height(12.dp))

      OutlinedTextField(
        value = studentId,
        onValueChange = onStudentIdChange,
        label = { Text("Student ID (Optional)") },
        leadingIcon = { Icon(Icons.Default.Badge, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        singleLine = true,
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("signup_studentid_input"),
        colors = OutlinedTextFieldDefaults.colors(
          focusedBorderColor = MaterialTheme.colorScheme.primary,
          focusedLabelColor = MaterialTheme.colorScheme.primary
        )
      )

      Spacer(modifier = Modifier.height(12.dp))
    }

    // Common: Academic Email
    OutlinedTextField(
      value = email,
      onValueChange = onEmailChange,
      label = { Text("Email Address *") },
      placeholder = { Text("name@university.edu") },
      leadingIcon = { Icon(Icons.Default.Email, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Email, imeAction = ImeAction.Next),
      shape = RoundedCornerShape(12.dp),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("login_email_input"),
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        focusedLabelColor = MaterialTheme.colorScheme.primary
      )
    )

    Spacer(modifier = Modifier.height(12.dp))

    // Common: Password
    OutlinedTextField(
      value = password,
      onValueChange = onPasswordChange,
      label = { Text("Password *") },
      leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
      trailingIcon = {
        IconButton(onClick = onTogglePasswordVisibility) {
          Icon(
            imageVector = if (isPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
            contentDescription = if (isPasswordVisible) "Hide password" else "Show password"
          )
        }
      },
      visualTransformation = if (isPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
      singleLine = true,
      keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = if (authMode == AuthMode.SIGN_IN) ImeAction.Done else ImeAction.Next),
      shape = RoundedCornerShape(12.dp),
      modifier = Modifier
        .fillMaxWidth()
        .testTag("login_password_input"),
      colors = OutlinedTextFieldDefaults.colors(
        focusedBorderColor = MaterialTheme.colorScheme.primary,
        focusedLabelColor = MaterialTheme.colorScheme.primary
      )
    )

    // Sign Up: Confirm Password
    if (authMode == AuthMode.SIGN_UP) {
      Spacer(modifier = Modifier.height(12.dp))

      OutlinedTextField(
        value = confirmPassword,
        onValueChange = onConfirmPasswordChange,
        label = { Text("Confirm Password *") },
        leadingIcon = { Icon(Icons.Default.Lock, contentDescription = null, tint = MaterialTheme.colorScheme.primary) },
        trailingIcon = {
          IconButton(onClick = onToggleConfirmPasswordVisibility) {
            Icon(
              imageVector = if (isConfirmPasswordVisible) Icons.Default.VisibilityOff else Icons.Default.Visibility,
              contentDescription = if (isConfirmPasswordVisible) "Hide password" else "Show password"
            )
          }
        },
        visualTransformation = if (isConfirmPasswordVisible) VisualTransformation.None else PasswordVisualTransformation(),
        singleLine = true,
        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Password, imeAction = ImeAction.Done),
        shape = RoundedCornerShape(12.dp),
        modifier = Modifier
          .fillMaxWidth()
          .testTag("signup_confirm_password_input"),
        colors = OutlinedTextFieldDefaults.colors(
          focusedBorderColor = MaterialTheme.colorScheme.primary,
          focusedLabelColor = MaterialTheme.colorScheme.primary
        )
      )
    }

    Spacer(modifier = Modifier.height(8.dp))

    // Remember Me & Forgot Password Row
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
          .clickable { onRememberMeChange(!rememberMe) }
      ) {
        Checkbox(
          checked = rememberMe,
          onCheckedChange = onRememberMeChange,
          colors = CheckboxDefaults.colors(checkedColor = MaterialTheme.colorScheme.primary),
          modifier = Modifier.testTag("remember_me_checkbox")
        )
        Text(
          text = "Remember me",
          fontSize = 12.sp,
          color = MaterialTheme.colorScheme.onSurfaceVariant,
          maxLines = 1,
          overflow = TextOverflow.Ellipsis
        )
      }

      if (authMode == AuthMode.SIGN_IN) {
        TextButton(
          onClick = onForgotPasswordClick,
          contentPadding = PaddingValues(horizontal = 6.dp, vertical = 4.dp),
          modifier = Modifier.heightIn(min = 40.dp)
        ) {
          Text(
            text = "Forgot password?",
            fontSize = 12.sp,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.primary,
            maxLines = 1,
            softWrap = false
          )
        }
      }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Primary Action Button
    Button(
      onClick = {
        if (authMode == AuthMode.SIGN_IN) onSignInClick() else onSignUpClick()
      },
      colors = ButtonDefaults.buttonColors(
        containerColor = MaterialTheme.colorScheme.primary,
        contentColor = MaterialTheme.colorScheme.onPrimary
      ),
      shape = RoundedCornerShape(12.dp),
      modifier = Modifier
        .fillMaxWidth()
        .height(48.dp)
        .testTag("auth_submit_button")
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
      ) {
        Text(
          text = if (authMode == AuthMode.SIGN_IN) "Sign in" else "Create profile",
          fontSize = 14.sp,
          fontWeight = FontWeight.Bold
        )
        Spacer(modifier = Modifier.width(8.dp))
        Icon(
          imageVector = Icons.AutoMirrored.Filled.ArrowForward,
          contentDescription = null,
          modifier = Modifier.size(16.dp)
        )
      }
    }

    Spacer(modifier = Modifier.height(20.dp))

    // Divider
    Row(
      modifier = Modifier.fillMaxWidth(),
      verticalAlignment = Alignment.CenterVertically
    ) {
      HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
      Text(
        text = "  OR INSTANT ACCESS  ",
        fontSize = 10.sp,
        fontWeight = FontWeight.Bold,
        color = MaterialTheme.colorScheme.onSurfaceVariant
      )
      HorizontalDivider(modifier = Modifier.weight(1f), color = MaterialTheme.colorScheme.outlineVariant)
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Quick Google Student Account
    OutlinedButton(
      onClick = onGoogleSignInClick,
      shape = RoundedCornerShape(12.dp),
      border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 48.dp)
        .testTag("login_google_button")
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
      ) {
        Surface(
          shape = CircleShape,
          color = MaterialTheme.colorScheme.primaryContainer,
          modifier = Modifier.size(24.dp)
        ) {
          Box(contentAlignment = Alignment.Center) {
            Text(text = "G", fontWeight = FontWeight.Bold, fontSize = 13.sp, color = MaterialTheme.colorScheme.primary)
          }
        }
        Spacer(modifier = Modifier.width(12.dp))
        Text(
          text = "Continue with Google Student SSO",
          fontSize = 12.sp,
          fontWeight = FontWeight.SemiBold,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          softWrap = false,
          overflow = TextOverflow.Ellipsis
        )
      }
    }

    Spacer(modifier = Modifier.height(12.dp))

    // Guest / Demo Scholar Access
    OutlinedButton(
      onClick = onGuestSignInClick,
      shape = RoundedCornerShape(12.dp),
      border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.outlineVariant),
      modifier = Modifier
        .fillMaxWidth()
        .heightIn(min = 48.dp)
        .testTag("login_guest_button")
    ) {
      Row(
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.Center
      ) {
        Icon(
          imageVector = Icons.Default.AutoAwesome,
          contentDescription = null,
          tint = MaterialTheme.colorScheme.primary,
          modifier = Modifier.size(16.dp)
        )
        Spacer(modifier = Modifier.width(8.dp))
        Text(
          text = "Continue as guest",
          fontSize = 12.sp,
          fontWeight = FontWeight.Medium,
          color = MaterialTheme.colorScheme.onSurface,
          maxLines = 1,
          softWrap = false
        )
      }
    }

    Spacer(modifier = Modifier.height(16.dp))

    // Mode Toggle footer
    FlowRow(
      modifier = Modifier.fillMaxWidth(),
      horizontalArrangement = Arrangement.Center
    ) {
      Text(
        text = if (authMode == AuthMode.SIGN_IN) "Don't have an academic account yet?" else "Already registered?",
        fontSize = 12.sp,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = Modifier.align(Alignment.CenterVertically)
      )
      TextButton(
        onClick = {
          onAuthModeChange(if (authMode == AuthMode.SIGN_IN) AuthMode.SIGN_UP else AuthMode.SIGN_IN)
        },
        contentPadding = PaddingValues(horizontal = 6.dp),
        modifier = Modifier.align(Alignment.CenterVertically)
      ) {
        Text(
          text = if (authMode == AuthMode.SIGN_IN) "Create one now" else "Sign in",
          fontSize = 12.sp,
          fontWeight = FontWeight.Bold,
          color = MaterialTheme.colorScheme.primary,
          maxLines = 1,
          softWrap = false
        )
      }
    }
  }
}
