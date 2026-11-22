package com.example.ui.viewmodel

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.example.data.ai.LocalAiEngine
import com.example.data.local.AppDatabase
import com.example.data.model.ChatMessage
import com.example.data.model.QuizAttempt
import com.example.data.model.QuizConfig
import com.example.data.model.QuizQuestion
import com.example.data.model.StudyTask
import com.example.data.model.StudyTopic
import com.example.data.model.Subject
import com.example.data.model.TimetableSession
import com.example.data.model.TutorMessageEntity
import com.example.data.model.TutorModel
import com.example.data.model.TutorSession
import com.example.data.model.ImportedChat
import com.example.data.model.ExtractedConcept
import com.example.data.ai.ChatKnowledgeExtractor
import com.example.data.remote.ChatLinkFetcher
import com.example.data.remote.EvaluationResult
import com.example.data.repository.StudyPilotRepository
import com.example.ui.components.formatTimeRange
import com.example.ui.components.formatTo12Hour
import org.json.JSONArray
import org.json.JSONObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.example.data.service.AlarmAndCalendarService
import com.example.data.service.CurrentScheduleStatus
import com.example.data.service.ScheduleState
import com.example.data.model.UserProfile
import android.content.Context

sealed class Screen(val title: String) {
  object Login : Screen("Login & Account")
  object Dashboard : Screen("Dashboard")
  object Timetable : Screen("Timetable")
  object MyTopics : Screen("My Topics")
  object WeekendQuizzes : Screen("Weekend Quizzes")
  object ActiveQuiz : Screen("Interactive Quiz")
  object AiTutor : Screen("AI Tutor")
  object Progress : Screen("Progress")
  object Tasks : Screen("Tasks & Exams")
  object Settings : Screen("Settings")
  object AiChatImport : Screen("AI Chat Import & Memory")
}

data class ActiveQuizState(
  val isActive: Boolean = false,
  val title: String = "",
  val questions: List<QuizQuestion> = emptyList(),
  val currentIndex: Int = 0,
  val userAnswers: Map<Long, String> = emptyMap(),
  val flaggedQuestionIds: Set<Long> = emptySet(),
  val evaluations: Map<Long, EvaluationResult> = emptyMap(),
  val isSubmitted: Boolean = false,
  val testedTopics: List<StudyTopic> = emptyList(),
  val queuedUntestedTopics: List<StudyTopic> = emptyList(),
  val isAllCovered: Boolean = true,
  val completedAttempt: QuizAttempt? = null,
  val isEvaluating: Boolean = false
)

class StudyPilotViewModel(application: Application) : AndroidViewModel(application) {

  val repository: StudyPilotRepository =
    StudyPilotRepository(AppDatabase.getDatabase(application).studyPilotDao(), context = application)

  // --- AI API Keys & Provider Settings ---
  private val aiPrefs = application.getSharedPreferences("studypilot_ai_config", Context.MODE_PRIVATE)

  private val _customGeminiKey = MutableStateFlow(aiPrefs.getString("custom_gemini_key", "") ?: "")
  val customGeminiKey: StateFlow<String> = _customGeminiKey.asStateFlow()

  private val _aiProviderMode = MutableStateFlow(
    repository.geminiService.normalizeProviderMode(aiPrefs.getString("ai_provider_mode", "AUTO") ?: "AUTO")
  )
  val aiProviderMode: StateFlow<String> = _aiProviderMode.asStateFlow()

  private val _apiTestStatus = MutableStateFlow<String?>(null)
  val apiTestStatus: StateFlow<String?> = _apiTestStatus.asStateFlow()

  private val _isTestingApi = MutableStateFlow(false)
  val isTestingApi: StateFlow<Boolean> = _isTestingApi.asStateFlow()

  // --- UI notifications ---
  private val _snackbarMessage = MutableSharedFlow<String>()
  val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

  // --- Google Calendar & Alarm Services ---
  val alarmAndCalendarService = AlarmAndCalendarService(application)

  private val _isCalendarConnected = MutableStateFlow(true)
  val isCalendarConnected: StateFlow<Boolean> = _isCalendarConnected.asStateFlow()

  companion object {
    const val GUEST_USER_ID = "guest_user"
    private const val MIGRATION_CLEARED_DEFAULT_LOGIN_V1 = "cleared_default_login_v1"
    private val LEGACY_DEFAULT_ACCOUNT_IDS = setOf(
      "student_amitesh",
      "student_sarah",
      "student_marcus",
      "guest_demo"
    )

    fun createGuestProfile(): UserProfile = UserProfile(
      id = GUEST_USER_ID,
      name = "Guest",
      email = "",
      role = "Guest Mode",
      university = "Local Session",
      studentId = "GUEST",
      avatarInitials = "G",
      isLoggedIn = false,
      authProvider = "Guest Mode",
      rememberMe = false,
      lastLoginTime = System.currentTimeMillis()
    )
  }

  private val _ringingAlarmSession = MutableStateFlow<TimetableSession?>(null)
  val ringingAlarmSession: StateFlow<TimetableSession?> = _ringingAlarmSession.asStateFlow()

  // --- Registered Student Accounts & Multi-Student Account Isolation ---
  private val authPrefs = application.getSharedPreferences("studypilot_auth_prefs", Context.MODE_PRIVATE)

  private val legacyDefaultsCleared: Boolean = clearLegacyDefaultAccountsIfNeeded()

  private fun clearLegacyDefaultAccountsIfNeeded(): Boolean {
    if (authPrefs.getBoolean(MIGRATION_CLEARED_DEFAULT_LOGIN_V1, false)) return true
    val editor = authPrefs.edit()
    val rawAccounts = authPrefs.getString("registered_students_json", null)
    if (!rawAccounts.isNullOrBlank()) {
      try {
        val arr = JSONArray(rawAccounts)
        val filtered = JSONArray()
        for (i in 0 until arr.length()) {
          val obj = arr.getJSONObject(i)
          val id = obj.optString("id", "")
          if (id.isNotBlank() && id !in LEGACY_DEFAULT_ACCOUNT_IDS && id != GUEST_USER_ID) {
            filtered.put(obj)
          }
        }
        editor.putString("registered_students_json", filtered.toString())
      } catch (_: Exception) {
        editor.remove("registered_students_json")
      }
    }
    val activeId = authPrefs.getString("active_user_id", null)
    if (activeId == null || activeId in LEGACY_DEFAULT_ACCOUNT_IDS) {
      editor
        .putString("active_user_id", GUEST_USER_ID)
        .putBoolean("is_logged_in", false)
        .remove("user_name")
        .remove("user_email")
        .remove("user_role")
        .remove("user_university")
        .remove("user_student_id")
        .remove("user_initials")
        .remove("auth_provider")
        .remove("remembered_email")
    }
    editor.putBoolean(MIGRATION_CLEARED_DEFAULT_LOGIN_V1, true).apply()
    return true
  }

  private fun parseRegisteredAccounts(jsonStr: String?): List<UserProfile> {
    if (jsonStr.isNullOrBlank()) return emptyList()
    return try {
      val arr = JSONArray(jsonStr)
      val list = mutableListOf<UserProfile>()
      for (i in 0 until arr.length()) {
        val obj = arr.getJSONObject(i)
        val id = obj.optString("id", "")
        if (id.isBlank() || id == GUEST_USER_ID || id in LEGACY_DEFAULT_ACCOUNT_IDS) continue
        list.add(
          UserProfile(
            id = id,
            name = obj.optString("name", "Student"),
            email = obj.optString("email", ""),
            role = obj.optString("role", "Student"),
            university = obj.optString("university", "Independent Scholar"),
            studentId = obj.optString("studentId", ""),
            avatarInitials = obj.optString("avatarInitials", "ST"),
            isLoggedIn = obj.optBoolean("isLoggedIn", false),
            authProvider = obj.optString("authProvider", "Email & Password"),
            rememberMe = obj.optBoolean("rememberMe", true),
            lastLoginTime = obj.optLong("lastLoginTime", System.currentTimeMillis())
          )
        )
      }
      list
    } catch (_: Exception) {
      emptyList()
    }
  }

  private fun loadRegisteredAccounts(): List<UserProfile> {
    val jsonStr = authPrefs.getString("registered_students_json", null)
    return parseRegisteredAccounts(jsonStr)
  }

  private fun saveRegisteredAccounts(list: List<UserProfile>) {
    try {
      val arr = JSONArray()
      for (u in list) {
        if (u.id.isBlank() || u.id == GUEST_USER_ID) continue
        arr.put(JSONObject().apply {
          put("id", u.id)
          put("name", u.name)
          put("email", u.email)
          put("role", u.role)
          put("university", u.university)
          put("studentId", u.studentId)
          put("avatarInitials", u.avatarInitials)
          put("isLoggedIn", u.isLoggedIn)
          put("authProvider", u.authProvider)
          put("rememberMe", u.rememberMe)
          put("lastLoginTime", u.lastLoginTime)
        })
      }
      authPrefs.edit().putString("registered_students_json", arr.toString()).apply()
    } catch (_: Exception) {}
  }

  private fun loadSavedUser(): UserProfile {
    val isLoggedIn = authPrefs.getBoolean("is_logged_in", false)
    val activeId = authPrefs.getString("active_user_id", GUEST_USER_ID) ?: GUEST_USER_ID
    if (!isLoggedIn || activeId == GUEST_USER_ID || activeId in LEGACY_DEFAULT_ACCOUNT_IDS) {
      return createGuestProfile()
    }

    val accounts = loadRegisteredAccounts()
    val match = accounts.find { it.id == activeId }
    if (match != null) return match.copy(isLoggedIn = true)

    val storedName = authPrefs.getString("user_name", null)
    val storedEmail = authPrefs.getString("user_email", null)
    if (storedName.isNullOrBlank() && storedEmail.isNullOrBlank()) {
      return createGuestProfile()
    }
    return UserProfile(
      id = activeId,
      name = storedName ?: storedEmail?.substringBefore("@") ?: "Student",
      email = storedEmail ?: "",
      role = authPrefs.getString("user_role", "Student") ?: "Student",
      university = authPrefs.getString("user_university", "Independent Scholar") ?: "Independent Scholar",
      studentId = authPrefs.getString("user_student_id", "") ?: "",
      avatarInitials = authPrefs.getString("user_initials", "ST") ?: "ST",
      isLoggedIn = true,
      authProvider = authPrefs.getString("auth_provider", "Email & Password") ?: "Email & Password",
      rememberMe = true
    )
  }

  private fun saveUser(user: UserProfile, isLoggedIn: Boolean) {
    if (user.id == GUEST_USER_ID || !isLoggedIn) {
      authPrefs.edit()
        .putString("active_user_id", GUEST_USER_ID)
        .putBoolean("is_logged_in", false)
        .remove("user_name")
        .remove("user_email")
        .remove("user_role")
        .remove("user_university")
        .remove("user_student_id")
        .remove("user_initials")
        .remove("auth_provider")
        .apply()
      return
    }

    val editor = authPrefs.edit()
      .putString("active_user_id", user.id)
      .putString("user_name", user.name)
      .putString("user_email", user.email)
      .putString("user_role", user.role)
      .putString("user_university", user.university)
      .putString("user_student_id", user.studentId)
      .putString("user_initials", user.avatarInitials)
      .putString("auth_provider", user.authProvider)
      .putBoolean("is_logged_in", user.rememberMe)
    if (user.rememberMe && user.email.isNotBlank()) {
      editor.putString("remembered_email", user.email)
    }
    editor.apply()

    val currentList = _savedAccounts.value.filter { it.id != GUEST_USER_ID }.toMutableList()
    val idx = currentList.indexOfFirst {
      it.id == user.id || (user.email.isNotBlank() && it.email.equals(user.email, ignoreCase = true))
    }
    val storedUser = user.copy(isLoggedIn = true)
    if (idx >= 0) {
      currentList[idx] = storedUser
    } else {
      currentList.add(storedUser)
    }
    _savedAccounts.value = currentList
    saveRegisteredAccounts(currentList)
    repository.saveUserProfile(storedUser)
  }

  fun getRememberedEmail(): String = authPrefs.getString("remembered_email", "") ?: ""

  private val _savedAccounts = MutableStateFlow<List<UserProfile>>(loadRegisteredAccounts())
  val savedAccounts: StateFlow<List<UserProfile>> = _savedAccounts.asStateFlow()

  private val _currentUser = MutableStateFlow<UserProfile>(loadSavedUser())
  val currentUser: StateFlow<UserProfile> = _currentUser.asStateFlow()

  private val _isAuthenticated = MutableStateFlow(
    _currentUser.value.isLoggedIn && _currentUser.value.id != GUEST_USER_ID
  )
  val isAuthenticated: StateFlow<Boolean> = _isAuthenticated.asStateFlow()

  // --- Reactive States (Per-Student Isolated Data Stream) ---
  @OptIn(ExperimentalCoroutinesApi::class)
  val subjects: StateFlow<List<Subject>> = _currentUser
    .flatMapLatest { user -> repository.getSubjectsForUser(user.id) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  @OptIn(ExperimentalCoroutinesApi::class)
  val sessions: StateFlow<List<TimetableSession>> = _currentUser
    .flatMapLatest { user -> repository.getSessionsForUser(user.id) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  @OptIn(ExperimentalCoroutinesApi::class)
  val topics: StateFlow<List<StudyTopic>> = _currentUser
    .flatMapLatest { user -> repository.getTopicsForUser(user.id) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  @OptIn(ExperimentalCoroutinesApi::class)
  val tasks: StateFlow<List<StudyTask>> = _currentUser
    .flatMapLatest { user -> repository.getTasksForUser(user.id) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  @OptIn(ExperimentalCoroutinesApi::class)
  val quizAttempts: StateFlow<List<QuizAttempt>> = _currentUser
    .flatMapLatest { user -> repository.getQuizAttemptsForUser(user.id) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  @OptIn(ExperimentalCoroutinesApi::class)
  val tutorSessions: StateFlow<List<TutorSession>> = _currentUser
    .flatMapLatest { user -> repository.getTutorSessionsForUser(user.id) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  @OptIn(ExperimentalCoroutinesApi::class)
  val scheduleFormats: StateFlow<List<com.example.data.model.ScheduleFormat>> = _currentUser
    .flatMapLatest { user -> repository.getScheduleFormatsForUser(user.id) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  @OptIn(ExperimentalCoroutinesApi::class)
  val importedChats: StateFlow<List<ImportedChat>> = _currentUser
    .flatMapLatest { user -> repository.getImportedChatsForUser(user.id) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  @OptIn(ExperimentalCoroutinesApi::class)
  val extractedConcepts: StateFlow<List<ExtractedConcept>> = _currentUser
    .flatMapLatest { user -> repository.getExtractedConceptsForUser(user.id) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  // --- AI Chat History Import & Intelligent Learning Memory States ---
  private val _isImportProcessing = MutableStateFlow(false)
  val isImportProcessing: StateFlow<Boolean> = _isImportProcessing.asStateFlow()

  private val _importProcessingProgress = MutableStateFlow(0f)
  val importProcessingProgress: StateFlow<Float> = _importProcessingProgress.asStateFlow()

  private val _importProcessingStatusText = MutableStateFlow("")
  val importProcessingStatusText: StateFlow<String> = _importProcessingStatusText.asStateFlow()

  private val _extractionPreview = MutableStateFlow<ChatKnowledgeExtractor.ChatExtractionResult?>(null)
  val extractionPreview: StateFlow<ChatKnowledgeExtractor.ChatExtractionResult?> = _extractionPreview.asStateFlow()

  private val _previewTitle = MutableStateFlow("")
  val previewTitle: StateFlow<String> = _previewTitle.asStateFlow()

  private val _previewSubject = MutableStateFlow<Subject?>(null)
  val previewSubject: StateFlow<Subject?> = _previewSubject.asStateFlow()

  private val _previewSubjectSuggestion = MutableStateFlow<ChatKnowledgeExtractor.SubjectSuggestion?>(null)
  val previewSubjectSuggestion: StateFlow<ChatKnowledgeExtractor.SubjectSuggestion?> = _previewSubjectSuggestion.asStateFlow()

  private val _previewTags = MutableStateFlow("")
  val previewTags: StateFlow<String> = _previewTags.asStateFlow()

  private val _previewRawText = MutableStateFlow("")
  val previewRawText: StateFlow<String> = _previewRawText.asStateFlow()

  private val _previewSourceUrl = MutableStateFlow("")
  val previewSourceUrl: StateFlow<String> = _previewSourceUrl.asStateFlow()

  private val _previewPlatform = MutableStateFlow("Auto-detect")
  val previewPlatform: StateFlow<String> = _previewPlatform.asStateFlow()

  val studyStreak: StateFlow<Int> = kotlinx.coroutines.flow.combine(
    topics,
    quizAttempts,
    sessions,
    tutorSessions
  ) { tList, aList, sList, tsList ->
    calculateRealStudyStreak(tList, aList, sList, tsList)
  }.flowOn(Dispatchers.Default)
    .distinctUntilChanged()
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), 0)

  private fun calculateRealStudyStreak(
    topicsList: List<StudyTopic>,
    attemptsList: List<QuizAttempt>,
    sessionsList: List<TimetableSession>,
    tutorSessionsList: List<TutorSession>
  ): Int {
    val activeDates = mutableSetOf<String>()
    val df = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())

    for (t in topicsList) {
      if (t.dateStudied.isNotBlank()) activeDates.add(t.dateStudied)
    }
    for (a in attemptsList) {
      if (a.dateAttempted.isNotBlank()) {
        activeDates.add(a.dateAttempted.take(10))
      }
    }
    for (s in sessionsList) {
      if (s.date.isNotBlank() && (s.status == "COMPLETED" || s.status == "ATTENDED")) {
        activeDates.add(s.date)
      }
    }
    for (ts in tutorSessionsList) {
      activeDates.add(df.format(java.util.Date(ts.createdAt)))
    }

    if (activeDates.isEmpty()) return 0

    val cal = java.util.Calendar.getInstance()
    var streak = 0
    val todayStr = df.format(cal.time)

    if (activeDates.contains(todayStr)) {
      streak++
      cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
    } else {
      cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
      val yesterdayStr = df.format(cal.time)
      if (!activeDates.contains(yesterdayStr)) {
        return 0
      }
    }

    while (true) {
      val dateStr = df.format(cal.time)
      if (activeDates.contains(dateStr)) {
        streak++
        cal.add(java.util.Calendar.DAY_OF_YEAR, -1)
      } else {
        break
      }
    }
    return streak
  }

  private val dismissedSessionIdsToday = java.util.Collections.synchronizedSet(mutableSetOf<Long>())

  private val currentTimeFlow = kotlinx.coroutines.flow.flow {
    while (true) {
      val now = System.currentTimeMillis()
      emit(java.util.Date(now))
      val millisIntoMinute = now % 60_000L
      val delayMs = (60_000L - millisIntoMinute + 50L).coerceIn(1000L, 60_050L)
      kotlinx.coroutines.delay(delayMs)
    }
  }

  val scheduleStatus: StateFlow<CurrentScheduleStatus> = kotlinx.coroutines.flow.combine(
    sessions,
    currentTimeFlow
  ) { list, now ->
    alarmAndCalendarService.evaluateCurrentStatus(list, now)
  }.flowOn(Dispatchers.Default)
    .distinctUntilChanged()
    .stateIn(
      viewModelScope,
      SharingStarted.WhileSubscribed(5000),
      CurrentScheduleStatus(ScheduleState.FREE_TIME, formattedStatusText = "Checking mobile schedule status...")
    )

  init {
    // Sync initial saved AI keys and provider mode (Gemini & Local AI only)
    repository.geminiService.customGeminiKey = _customGeminiKey.value
    repository.geminiService.preferredProviderMode = _aiProviderMode.value

    viewModelScope.launch {
      repository.seedSampleUniversityData(_currentUser.value.id)
    }

    // 1. Listen for background & broadcast alarm events from AlarmReceiver
    viewModelScope.launch {
      com.example.data.service.AlarmEventBus.events.collect { event ->
        when (event) {
          is com.example.data.service.AlarmEvent.Triggered -> {
            val session = sessions.value.find { it.id == event.sessionId }
              ?: TimetableSession(
                id = event.sessionId,
                subjectId = 0L,
                subjectName = event.subjectName,
                topic = event.topic,
                date = "",
                startTime = event.startTime,
                endTime = event.endTime,
                location = event.location,
                notes = event.notes,
                isAlarmEnabled = true
              )
            _ringingAlarmSession.value = session
          }
          is com.example.data.service.AlarmEvent.Dismissed -> {
            dismissedSessionIdsToday.add(event.sessionId)
            com.example.data.service.AlarmRingingService.stopRinging(getApplication())
            com.example.data.service.AlarmSoundManager.stopSoundAndVibration(getApplication())
            alarmAndCalendarService.stopAlarmSound()
            _ringingAlarmSession.value = null
          }
          is com.example.data.service.AlarmEvent.Snoozed -> {
            dismissedSessionIdsToday.add(event.sessionId)
            com.example.data.service.AlarmRingingService.stopRinging(getApplication())
            com.example.data.service.AlarmSoundManager.stopSoundAndVibration(getApplication())
            alarmAndCalendarService.stopAlarmSound()
            _ringingAlarmSession.value = null
          }
        }
      }
    }

    // 2. Real-time in-app alarm trigger: checks upcoming sessions every 1 second based on mobile clock
    viewModelScope.launch(Dispatchers.Default) {
      val triggeredSessionIdsToday = mutableSetOf<Long>()
      val dateFormat = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault())
      while (true) {
        kotlinx.coroutines.delay(1000)
        try {
          val currentSessions = sessions.value
          if (currentSessions.none { it.isAlarmEnabled }) continue
          val now = System.currentTimeMillis()
          val todayStr = dateFormat.format(java.util.Date(now))
          for (session in currentSessions) {
            if (!session.isAlarmEnabled) continue
            val isForToday = session.date.isBlank() || session.date == todayStr
            if (!isForToday) continue
            if (triggeredSessionIdsToday.contains(session.id) || dismissedSessionIdsToday.contains(session.id)) continue

            val (startH, startM) = parseQuickTime(session.startTime)
            val cal = java.util.Calendar.getInstance().apply {
              set(java.util.Calendar.HOUR_OF_DAY, startH)
              set(java.util.Calendar.MINUTE, startM)
              set(java.util.Calendar.SECOND, 0)
              set(java.util.Calendar.MILLISECOND, 0)
            }
            val exactStartMs = cal.timeInMillis

            // Trigger ONLY when the event is starting exactly (at exactStartMs, within 5-minute window)
            if (now >= exactStartMs && now <= exactStartMs + 300_000) {
              triggeredSessionIdsToday.add(session.id)
              triggerTestAlarm(session)
              break
            }
          }
        } catch (_: Exception) {
          // Ignore
        }
      }
    }

    // 3. Automatically schedule exact alarms for all enabled sessions (only when enabled set changes)
    viewModelScope.launch(Dispatchers.Default) {
      sessions
        .map { list -> list.filter { it.isAlarmEnabled } }
        .distinctUntilChanged()
        .collect { enabledList ->
          enabledList.forEach { s ->
            alarmAndCalendarService.scheduleSessionAlarm(s)
          }
        }
    }
  }

  // --- AI API Configuration Actions ---
  fun saveCustomGeminiKey(key: String) {
    val trimmed = key.trim()
    aiPrefs.edit().putString("custom_gemini_key", trimmed).apply()
    _customGeminiKey.value = trimmed
    repository.geminiService.customGeminiKey = trimmed
    viewModelScope.launch {
      _snackbarMessage.emit(if (trimmed.isNotBlank()) "Saved Custom Gemini API Key!" else "Cleared Custom Gemini Key (Using Default Build Key)")
    }
  }

  fun setAiProviderMode(mode: String) {
    val normalized = repository.geminiService.normalizeProviderMode(mode)
    aiPrefs.edit().putString("ai_provider_mode", normalized).apply()
    _aiProviderMode.value = normalized
    repository.geminiService.preferredProviderMode = normalized
    viewModelScope.launch {
      _snackbarMessage.emit("AI Provider Mode set to '$normalized'")
    }
  }

  fun testApiConnection(provider: String, keyToTest: String) {
    viewModelScope.launch {
      _isTestingApi.value = true
      _apiTestStatus.value = "Connecting to $provider API..."
      val (success, message) = repository.geminiService.testConnection(provider, keyToTest)
      _isTestingApi.value = false
      _apiTestStatus.value = message
      _snackbarMessage.emit(message)
    }
  }

  fun clearApiTestStatus() {
    _apiTestStatus.value = null
  }

  val localOllamaUrl: StateFlow<String> = MutableStateFlow(
    aiPrefs.getString("local_ollama_url", "http://10.0.2.2:11434") ?: "http://10.0.2.2:11434"
  ).asStateFlow()

  val localOllamaModel: StateFlow<String> = MutableStateFlow(
    aiPrefs.getString("local_ollama_model", "llama3.2") ?: "llama3.2"
  ).asStateFlow()

  private val _ollamaTestStatus = MutableStateFlow<String?>(null)
  val ollamaTestStatus: StateFlow<String?> = _ollamaTestStatus.asStateFlow()

  fun setLocalOllamaConfig(url: String, model: String) {
    val cleanUrl = url.trim().ifBlank { "http://10.0.2.2:11434" }
    val cleanModel = model.trim().ifBlank { "llama3.2" }
    aiPrefs.edit()
      .putString("local_ollama_url", cleanUrl)
      .putString("local_ollama_model", cleanModel)
      .apply()
    LocalAiEngine.localOllamaUrl = cleanUrl
    LocalAiEngine.localOllamaModel = cleanModel
    viewModelScope.launch {
      _snackbarMessage.emit("Updated Local Llama configuration ($cleanModel @ $cleanUrl)")
    }
  }

  fun testLocalOllamaConnection(url: String) {
    viewModelScope.launch {
      _ollamaTestStatus.value = "Connecting to Local Ollama/Llama daemon at $url..."
      val (_, msg) = LocalAiEngine.testOllamaConnection(url)
      _ollamaTestStatus.value = msg
      _snackbarMessage.emit(msg)
    }
  }

  fun clearOllamaTestStatus() {
    _ollamaTestStatus.value = null
  }

  // --- Account Actions ---
  fun deleteUser(userId: String) {
    viewModelScope.launch {
      val userToDelete = _savedAccounts.value.find { it.id == userId }
      val userName = userToDelete?.name ?: "Student"
      val remainingAccounts = _savedAccounts.value.filter { it.id != userId }
      _savedAccounts.value = remainingAccounts
      saveRegisteredAccounts(remainingAccounts)

      // Clean up repository records & Firestore
      repository.deleteUserData(userId)

      // If deleted account is the active account
      if (_currentUser.value.id == userId) {
        if (remainingAccounts.isNotEmpty()) {
          val nextUser = remainingAccounts.first()
          switchAccount(nextUser)
          _snackbarMessage.emit("Deleted profile for $userName. Switched to ${nextUser.name}.")
        } else {
          val guest = createGuestProfile()
          saveUser(guest, isLoggedIn = false)
          authPrefs.edit().remove("remembered_email").apply()
          _currentUser.value = guest
          _isAuthenticated.value = false
          _snackbarMessage.emit("Profile for $userName removed. Switched to Guest Mode.")
        }
      } else {
        _snackbarMessage.emit("Profile for $userName deleted.")
      }
    }
  }

  fun syncCloudData() {
    viewModelScope.launch {
      val user = _currentUser.value
      if (user.id == GUEST_USER_ID || !user.isLoggedIn) {
        _snackbarMessage.emit("Sign in to sync your profile and coursework with the cloud.")
        return@launch
      }
      _snackbarMessage.emit("Syncing coursework with cloud...")
      repository.saveUserProfile(user)
      repository.pullUserDataFromFirestore(user.id)
      _snackbarMessage.emit("Cloud sync complete! Devices are in sync.")
    }
  }

  fun switchAccount(user: UserProfile) {
    if (user.id == GUEST_USER_ID) {
      loginAsGuest()
      return
    }
    val updated = user.copy(isLoggedIn = true, lastLoginTime = System.currentTimeMillis())
    saveUser(updated, isLoggedIn = true)
    _currentUser.value = updated
    _isAuthenticated.value = true
    _currentScreen.value = Screen.Dashboard
    viewModelScope.launch {
      repository.seedSampleUniversityData(updated.id)
      _snackbarMessage.emit("Switched to ${updated.name}")
    }
  }

  fun login(email: String, pass: String, rememberMe: Boolean = true): Boolean {
    val cleanEmail = email.trim()
    if (cleanEmail.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) {
      viewModelScope.launch { _snackbarMessage.emit("Please enter a valid academic email address.") }
      return false
    }
    if (pass.trim().length < 6) {
      viewModelScope.launch { _snackbarMessage.emit("Password must be at least 6 characters long.") }
      return false
    }

    val existing = _savedAccounts.value.find { it.email.equals(cleanEmail, ignoreCase = true) }
    val userToActivate = if (existing != null) {
      existing.copy(isLoggedIn = true, rememberMe = rememberMe, lastLoginTime = System.currentTimeMillis())
    } else {
      val namePart = cleanEmail.substringBefore("@").replace(".", " ").replace("_", " ").split(" ")
        .filter { it.isNotBlank() }
        .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
        .ifBlank { "Student" }
      val initials = namePart.split(" ").mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString("")
        .ifBlank { "ST" }
      val generatedId = "student_" + cleanEmail.lowercase().replace(Regex("[^a-z0-9]"), "_")
      UserProfile(
        id = generatedId,
        name = namePart,
        email = cleanEmail,
        role = "Student",
        university = "Independent Scholar",
        studentId = "STU-${(1000..9999).random()}",
        avatarInitials = initials,
        isLoggedIn = true,
        authProvider = "Email & Password",
        rememberMe = rememberMe,
        lastLoginTime = System.currentTimeMillis()
      )
    }

    saveUser(userToActivate, isLoggedIn = true)
    _currentUser.value = userToActivate
    _isAuthenticated.value = true
    _currentScreen.value = Screen.Dashboard
    viewModelScope.launch {
      repository.seedSampleUniversityData(userToActivate.id)
      _snackbarMessage.emit("Signed in as ${userToActivate.name}. Profile saved.")
    }
    return true
  }

  fun register(
    name: String,
    email: String,
    pass: String,
    university: String,
    studentId: String = "",
    rememberMe: Boolean = true
  ): Boolean {
    if (name.isBlank()) {
      viewModelScope.launch { _snackbarMessage.emit("Please enter your full name.") }
      return false
    }
    val cleanEmail = email.trim()
    if (cleanEmail.isBlank() || !android.util.Patterns.EMAIL_ADDRESS.matcher(cleanEmail).matches()) {
      viewModelScope.launch { _snackbarMessage.emit("Please enter a valid email address.") }
      return false
    }
    if (pass.trim().length < 6) {
      viewModelScope.launch { _snackbarMessage.emit("Password must be at least 6 characters.") }
      return false
    }
    val initials = name.trim().split(" ").mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString("")
      .ifBlank { "ST" }
    val generatedId = "student_" + cleanEmail.lowercase().replace(Regex("[^a-z0-9]"), "_")
    val newUser = UserProfile(
      id = generatedId,
      name = name.trim(),
      email = cleanEmail,
      role = "Student",
      university = university.trim().ifBlank { "Independent Scholar" },
      studentId = studentId.trim().ifBlank { "ID-${(1000..9999).random()}" },
      avatarInitials = initials,
      isLoggedIn = true,
      authProvider = "Email & Password",
      rememberMe = rememberMe,
      lastLoginTime = System.currentTimeMillis()
    )
    saveUser(newUser, isLoggedIn = true)
    _currentUser.value = newUser
    _isAuthenticated.value = true
    _currentScreen.value = Screen.Dashboard
    viewModelScope.launch {
      repository.seedSampleUniversityData(newUser.id)
      _snackbarMessage.emit("Welcome, ${newUser.name}! Account info saved.")
    }
    return true
  }

  fun signInWithGoogle(
    idToken: String,
    displayName: String?,
    email: String?,
    onComplete: (Boolean, String?) -> Unit
  ) {
    try {
      val credential = com.google.firebase.auth.GoogleAuthProvider.getCredential(idToken, null)
      com.google.firebase.auth.FirebaseAuth.getInstance().signInWithCredential(credential)
        .addOnSuccessListener { authResult ->
          val fbUser = authResult.user
          val studentEmail = email?.takeIf { it.isNotBlank() } ?: fbUser?.email?.takeIf { it.isNotBlank() } ?: ""
          val rawName = displayName?.takeIf { it.isNotBlank() } ?: fbUser?.displayName?.takeIf { it.isNotBlank() }
          val studentName = rawName ?: studentEmail.substringBefore("@").ifBlank { "Google User" }
          val userId = fbUser?.uid ?: if (studentEmail.isNotBlank()) {
            "google_" + studentEmail.lowercase().replace(Regex("[^a-z0-9]"), "_")
          } else {
            onComplete(false, "Google account information could not be retrieved.")
            return@addOnSuccessListener
          }
          val initials = studentName.trim().split(" ")
            .mapNotNull { it.firstOrNull()?.uppercase() }
            .take(2)
            .joinToString("")
            .ifBlank { "GU" }
          val existing = _savedAccounts.value.find {
            it.id == userId || (studentEmail.isNotBlank() && it.email.equals(studentEmail, ignoreCase = true))
          }
          val user = UserProfile(
            id = userId,
            name = studentName,
            email = studentEmail,
            role = existing?.role ?: "Student",
            university = existing?.university?.ifBlank { "Google Account" } ?: "Google Account",
            studentId = existing?.studentId?.ifBlank { "G-${userId.take(6).uppercase()}" } ?: "G-${userId.take(6).uppercase()}",
            avatarInitials = initials,
            isLoggedIn = true,
            authProvider = "Google Sign-In",
            rememberMe = true,
            lastLoginTime = System.currentTimeMillis()
          )
          saveUser(user, isLoggedIn = true)
          _currentUser.value = user
          _isAuthenticated.value = true
          _currentScreen.value = Screen.Dashboard
          viewModelScope.launch {
            repository.seedSampleUniversityData(user.id)
            _snackbarMessage.emit("Signed in as ${user.name}. Profile saved.")
          }
          onComplete(true, null)
        }
        .addOnFailureListener { exception ->
          android.util.Log.e("StudyPilotVM", "Firebase signInWithCredential failed", exception)
          onComplete(false, exception.localizedMessage)
        }
    } catch (e: Exception) {
      android.util.Log.e("StudyPilotVM", "Firebase Auth error", e)
      onComplete(false, e.localizedMessage)
    }
  }

  fun loginWithGoogle() {
    val fbUser = try { com.google.firebase.auth.FirebaseAuth.getInstance().currentUser } catch (_: Exception) { null }
    if (fbUser == null) {
      viewModelScope.launch {
        _snackbarMessage.emit("Google Sign-In failed. Please sign in with email or continue in Guest Mode.")
      }
      return
    }
    val studentEmail = fbUser.email ?: ""
    val studentName = fbUser.displayName?.takeIf { it.isNotBlank() }
      ?: studentEmail.substringBefore("@").ifBlank { "Google User" }
    val userId = fbUser.uid
    val initials = studentName.trim().split(" ")
      .mapNotNull { it.firstOrNull()?.uppercase() }
      .take(2)
      .joinToString("")
      .ifBlank { "GU" }
    val existing = _savedAccounts.value.find { it.id == userId }
    val user = UserProfile(
      id = userId,
      name = studentName,
      email = studentEmail,
      role = existing?.role ?: "Student",
      university = existing?.university?.ifBlank { "Google Account" } ?: "Google Account",
      studentId = existing?.studentId?.ifBlank { "G-${userId.take(6).uppercase()}" } ?: "G-${userId.take(6).uppercase()}",
      avatarInitials = initials,
      isLoggedIn = true,
      authProvider = "Google Sign-In",
      rememberMe = true,
      lastLoginTime = System.currentTimeMillis()
    )
    saveUser(user, isLoggedIn = true)
    _currentUser.value = user
    _isAuthenticated.value = true
    _currentScreen.value = Screen.Dashboard
    viewModelScope.launch {
      repository.seedSampleUniversityData(user.id)
      _snackbarMessage.emit("Signed in with Google: ${user.email.ifBlank { user.name }}")
    }
  }

  fun loginAsGuest() {
    val guestUser = createGuestProfile()
    saveUser(guestUser, isLoggedIn = false)
    _currentUser.value = guestUser
    _isAuthenticated.value = false
    _currentScreen.value = Screen.Dashboard
    viewModelScope.launch {
      repository.seedSampleUniversityData(guestUser.id)
      _snackbarMessage.emit("Browsing in Guest Mode")
    }
  }

  fun logout() {
    try {
      com.google.firebase.auth.FirebaseAuth.getInstance().signOut()
    } catch (_: Exception) {}
    val previousId = _currentUser.value.id
    if (previousId != GUEST_USER_ID) {
      val updatedAccounts = _savedAccounts.value.map {
        if (it.id == previousId) it.copy(isLoggedIn = false) else it
      }
      _savedAccounts.value = updatedAccounts
      saveRegisteredAccounts(updatedAccounts)
    }
    val guestUser = createGuestProfile()
    saveUser(guestUser, isLoggedIn = false)
    _currentUser.value = guestUser
    _isAuthenticated.value = false
    _currentScreen.value = Screen.Dashboard
    viewModelScope.launch {
      repository.seedSampleUniversityData(guestUser.id)
      _snackbarMessage.emit("Signed out. Switched to Guest Mode.")
    }
  }

  fun updateProfile(name: String, email: String, university: String, role: String) {
    if (_currentUser.value.id == GUEST_USER_ID || !_isAuthenticated.value) {
      viewModelScope.launch {
        _snackbarMessage.emit("Sign in to save your profile.")
      }
      return
    }
    val initials = name.trim().split(" ").mapNotNull { it.firstOrNull()?.uppercase() }.take(2).joinToString("")
      .ifBlank { _currentUser.value.avatarInitials }
    val updated = _currentUser.value.copy(
      name = name.trim().ifBlank { _currentUser.value.name },
      email = email.trim().ifBlank { _currentUser.value.email },
      university = university.trim().ifBlank { _currentUser.value.university },
      role = role.trim().ifBlank { _currentUser.value.role },
      avatarInitials = initials
    )
    saveUser(updated, isLoggedIn = true)
    _currentUser.value = updated
    viewModelScope.launch {
      _snackbarMessage.emit("Profile updated and saved.")
    }
  }

  // Navigation — always opens cleanly on Dashboard (Guest Mode or stored active account)
  private val _currentScreen = MutableStateFlow<Screen>(Screen.Dashboard)
  val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

  fun navigateTo(screen: Screen) {
    _currentScreen.value = screen
  }

  // Quiz Settings
  private val _quizConfig = MutableStateFlow(QuizConfig())
  val quizConfig: StateFlow<QuizConfig> = _quizConfig.asStateFlow()

  fun updateQuizConfig(config: QuizConfig) {
    _quizConfig.value = config
  }

  // Active Quiz
  private val _activeQuizState = MutableStateFlow(ActiveQuizState())
  val activeQuizState: StateFlow<ActiveQuizState> = _activeQuizState.asStateFlow()


  // Loading indicator for AI tasks
  private val _isAiLoading = MutableStateFlow(false)
  val isAiLoading: StateFlow<Boolean> = _isAiLoading.asStateFlow()

  // --- AI Chatbot States & Subject Scoping ---
  private val _selectedTutorSubject = MutableStateFlow<Subject?>(null)
  val selectedTutorSubject: StateFlow<Subject?> = _selectedTutorSubject.asStateFlow()

  private val _activeSessionId = MutableStateFlow<Long?>(null)
  val activeSessionId: StateFlow<Long?> = _activeSessionId.asStateFlow()

  private val _chatMessages = MutableStateFlow<List<ChatMessage>>(
    listOf(
      ChatMessage(
        role = "model",
        text = "Hello! I am your StudyPilot AI Tutor. Select a subject above or ask any conceptual question, request step-by-step math or algorithm proofs, debug code, or toggle Google Search for up-to-date research citations.",
        modelName = TutorModel.FLASH.modelId
      )
    )
  )
  val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

  private val _selectedModel = MutableStateFlow(TutorModel.FLASH)
  val selectedModel: StateFlow<TutorModel> = _selectedModel.asStateFlow()

  private val _useGoogleSearch = MutableStateFlow(false)
  val useGoogleSearch: StateFlow<Boolean> = _useGoogleSearch.asStateFlow()

  private val _systemInstruction = MutableStateFlow(
    "You are StudyPilot AI Tutor. CRITICAL ANTI-HALLUCINATION & DIRECTNESS RULES: Directly answer ONLY what the user just asked first—never fabricate facts, never substitute another topic/algorithm if you are unsure, and never add filler intros. When the user asks a follow-up or clicks an action button ('Another Example', 'Example in Code', 'Another Example in Code', 'Summarize', 'Explain Example'): answer ONLY what was clicked! For 'Example in Code' and 'Another Example in Code', output ONLY the code implementation and a brief 3-bullet walkthrough. For 'Summarize', output ONLY a concise bulleted summary and key takeaways. For 'Another Example', output ONLY the new alternative scenario and its walkthrough. For 'Explain Example', output ONLY the step-by-step breakdown of the example. At the very end of your response, output related subtopics in this exact single-line tag: [RELATED_TOPICS: Subtopic 1, Subtopic 2, Subtopic 3, Subtopic 4, Subtopic 5]."
  )
  val systemInstruction: StateFlow<String> = _systemInstruction.asStateFlow()

  private val _isChatLoading = MutableStateFlow(false)
  val isChatLoading: StateFlow<Boolean> = _isChatLoading.asStateFlow()

  fun selectTutorModel(model: TutorModel) {
    _selectedModel.value = model
  }

  fun toggleGoogleSearch(enabled: Boolean) {
    _useGoogleSearch.value = enabled
    if (enabled) {
      _selectedModel.value = TutorModel.FLASH
    }
  }

  fun updateSystemInstruction(instruction: String) {
    _systemInstruction.value = instruction
  }

  fun selectTutorSubject(subject: Subject?) {
    _selectedTutorSubject.value = subject
    val newInstruction = if (subject != null) {
      "You are StudyPilot AI Tutor specializing in ${subject.name} (${subject.code}). CRITICAL ANTI-HALLUCINATION & DIRECTNESS RULES: Directly answer ONLY what the user just asked first—never fabricate facts or substitute another topic. When the user clicks or asks for an action ('Another Example', 'Example in Code', 'Another Example in Code', 'Summarize', 'Explain Example'): answer ONLY what was clicked! At the very end of your response, output related subtopics in this exact tag: [RELATED_TOPICS: Subtopic 1, Subtopic 2, Subtopic 3, Subtopic 4, Subtopic 5]."
    } else {
      "You are StudyPilot AI Tutor. CRITICAL ANTI-HALLUCINATION & DIRECTNESS RULES: Directly answer ONLY what the user just asked first—never fabricate facts, never substitute another topic/algorithm if you are unsure, and never add filler intros. When the user asks a follow-up or clicks an action button ('Another Example', 'Example in Code', 'Another Example in Code', 'Summarize', 'Explain Example'): answer ONLY what was clicked! For 'Example in Code' and 'Another Example in Code', output ONLY the code implementation and a brief 3-bullet walkthrough. For 'Summarize', output ONLY a concise bulleted summary and key takeaways. For 'Another Example', output ONLY the new alternative scenario and its walkthrough. For 'Explain Example', output ONLY the step-by-step breakdown of the example. At the very end of your response, output related subtopics in this exact single-line tag: [RELATED_TOPICS: Subtopic 1, Subtopic 2, Subtopic 3, Subtopic 4, Subtopic 5]."
    }
    _systemInstruction.value = newInstruction

    if (_activeSessionId.value == null && _chatMessages.value.size <= 1) {
      val initialLinkTopics = com.example.data.ai.ChatHistoryAssistant.getMiniTopicsForLinkedScope(
        queryOrText = "",
        groundingUrls = emptyList(),
        importedChats = importedChats.value,
        extractedConcepts = extractedConcepts.value,
        selectedSubject = subject
      )
      _chatMessages.value = listOf(
        ChatMessage(
          role = "model",
          text = if (subject != null) {
            "Hello! I am your AI Tutor for **${subject.name}** (${subject.code}). What concept, homework problem, or theorem would you like to explore?"
          } else {
            "Hello! I am your StudyPilot AI Tutor. Ask me any conceptual question, request step-by-step math or algorithm proofs, debug code, or toggle Google Search for live citations."
          },
          modelName = _selectedModel.value.modelId,
          relatedTopics = initialLinkTopics
        )
      )
    }
  }

  fun openTutorForSubject(subjectId: Long?, prompt: String? = null) {
    val sub = subjects.value.firstOrNull { it.id == subjectId }
    selectTutorSubject(sub)
    _activeSessionId.value = null
    _currentScreen.value = Screen.AiTutor
    if (!prompt.isNullOrBlank()) {
      sendChatMessage(prompt)
    }
  }

  fun startNewTutorChat(subject: Subject? = _selectedTutorSubject.value) {
    _activeSessionId.value = null
    selectTutorSubject(subject)
    val initialLinkTopics = com.example.data.ai.ChatHistoryAssistant.getMiniTopicsForLinkedScope(
      queryOrText = "",
      groundingUrls = emptyList(),
      importedChats = importedChats.value,
      extractedConcepts = extractedConcepts.value,
      selectedSubject = subject
    )
    _chatMessages.value = listOf(
      ChatMessage(
        role = "model",
        text = if (subject != null) {
          "New session started for **${subject.name}** (${subject.code}). Ask a question or pick a suggested topic below!"
        } else {
          "New general study session started. What lecture topic or theorem would you like to explore?"
        },
        modelName = _selectedModel.value.modelId,
        relatedTopics = initialLinkTopics
      )
    )
  }

  fun loadTutorSession(session: TutorSession) {
    viewModelScope.launch {
      _activeSessionId.value = session.id
      val matchedSubject = subjects.value.firstOrNull { it.id == session.subjectId }
      _selectedTutorSubject.value = matchedSubject
      selectTutorSubject(matchedSubject)

      val dbMessages = repository.getMessagesForSessionSync(session.id)
      if (dbMessages.isNotEmpty()) {
        val chatsSnapshot = importedChats.value
        val conceptsSnapshot = extractedConcepts.value
        val mapped = withContext(Dispatchers.Default) {
          dbMessages.map { entity ->
            val sources = try {
              if (entity.groundingSourcesJson.isNotBlank()) {
                val arr = JSONArray(entity.groundingSourcesJson)
                (0 until arr.length()).map { idx ->
                  val obj = arr.getJSONObject(idx)
                  com.example.data.model.GroundingSource(
                    title = obj.optString("title", "Source"),
                    url = obj.optString("url", "")
                  )
                }
              } else emptyList()
            } catch (_: Exception) {
              emptyList()
            }

            val linkTopics = if (entity.role == "model") {
              com.example.data.ai.ChatHistoryAssistant.getMiniTopicsForLinkedScope(
                queryOrText = entity.text,
                groundingUrls = sources.map { it.url },
                importedChats = chatsSnapshot,
                extractedConcepts = conceptsSnapshot,
                selectedSubject = matchedSubject
              )
            } else emptyList()

            ChatMessage(
              id = entity.id.toString(),
              role = entity.role,
              text = entity.text,
              timestamp = entity.timestamp,
              modelName = entity.modelName,
              searchGroundingUsed = entity.searchGroundingUsed,
              groundingSources = sources,
              isError = entity.isError,
              relatedTopics = linkTopics
            )
          }
        }
        _chatMessages.value = mapped
      }
    }
  }

  fun deleteTutorSession(sessionId: Long) {
    viewModelScope.launch {
      repository.deleteTutorSession(sessionId)
      if (_activeSessionId.value == sessionId) {
        startNewTutorChat()
      }
      _snackbarMessage.emit("Chat session deleted")
    }
  }

  fun clearAllTutorHistory() {
    viewModelScope.launch {
      repository.clearAllTutorHistory()
      startNewTutorChat()
      _snackbarMessage.emit("All tutor chat history cleared")
    }
  }

  fun clearChat() {
    startNewTutorChat(_selectedTutorSubject.value)
  }

  fun sendChatMessage(text: String) {
    val trimmed = text.trim()
    if (trimmed.isBlank() || _isChatLoading.value) return

    val currentHistory = _chatMessages.value.toMutableList()
    val userMsg = ChatMessage(
      role = "user",
      text = trimmed,
      modelName = _selectedModel.value.modelId,
      searchGroundingUsed = _useGoogleSearch.value
    )
    currentHistory.add(userMsg)
    _chatMessages.value = currentHistory

    viewModelScope.launch {
      _isChatLoading.value = true
      var sessionId = _activeSessionId.value
      try {
        val activeSub = _selectedTutorSubject.value
        if (sessionId == null) {
          val title = trimmed.take(45).let { if (trimmed.length > 45) "$it..." else it }
          sessionId = repository.createTutorSession(
            subjectId = activeSub?.id,
            subjectName = activeSub?.name ?: "General",
            title = title,
            previewSnippet = trimmed,
            modelUsed = _selectedModel.value.modelId,
            searchGroundingUsed = _useGoogleSearch.value,
            userId = _currentUser.value.id
          )
          _activeSessionId.value = sessionId
        }

        repository.saveTutorMessage(
          sessionId = sessionId,
          role = "user",
          text = trimmed,
          modelName = _selectedModel.value.modelId,
          searchGroundingUsed = _useGoogleSearch.value
        )

        // 1. Prioritize link-containing imported chats on a background CPU dispatcher
        val chatsSnapshot = importedChats.value
        val conceptsSnapshot = extractedConcepts.value
        val linkedChatResponse = withContext(Dispatchers.Default) {
          com.example.data.ai.ChatHistoryAssistant.answerFromLinkedChats(
            query = trimmed,
            history = currentHistory,
            importedChats = chatsSnapshot,
            extractedConcepts = conceptsSnapshot,
            selectedSubject = activeSub
          )
        }

        // 2. Fall back to Gemini AI or Local AI when no linked chat contains the answer
        val subjectHint = activeSub?.let { "${it.name} (${it.code})" }
        val response = linkedChatResponse ?: repository.chatWithTutor(
          history = currentHistory,
          model = _selectedModel.value,
          useGoogleSearch = _useGoogleSearch.value,
          systemInstruction = _systemInstruction.value,
          subjectContext = subjectHint
        )

        val (cleanText, finalTopics) = withContext(Dispatchers.Default) {
          val (cleaned, extractedTopics) = com.example.data.ai.AcademicPedagogySynthesizer.extractAndCleanRelatedTopics(
            rawContent = response.text,
            fallbackQuery = trimmed
          )
          val linkScopedTopics = com.example.data.ai.ChatHistoryAssistant.getMiniTopicsForLinkedScope(
            queryOrText = "$trimmed $cleaned",
            groundingUrls = response.groundingSources.map { it.url },
            importedChats = chatsSnapshot,
            extractedConcepts = conceptsSnapshot,
            selectedSubject = activeSub
          )
          val resolvedTopics = if (linkedChatResponse != null) {
            linkedChatResponse.relatedTopics.ifEmpty { linkScopedTopics }
          } else {
            linkScopedTopics.ifEmpty { response.relatedTopics.ifEmpty { extractedTopics } }
          }
          Pair(cleaned, resolvedTopics)
        }

        val modelMsg = ChatMessage(
          role = "model",
          text = cleanText,
          modelName = response.modelName,
          searchGroundingUsed = _useGoogleSearch.value,
          groundingSources = response.groundingSources,
          searchQueries = response.searchQueries,
          isError = response.isError,
          relatedTopics = finalTopics
        )
        _chatMessages.value = _chatMessages.value + modelMsg

        val sourcesJson = if (response.groundingSources.isNotEmpty()) {
          val arr = JSONArray()
          response.groundingSources.forEach { s ->
            arr.put(JSONObject().apply {
              put("title", s.title)
              put("url", s.url)
            })
          }
          arr.toString()
        } else ""

        repository.saveTutorMessage(
          sessionId = sessionId,
          role = "model",
          text = cleanText,
          modelName = response.modelName,
          searchGroundingUsed = _useGoogleSearch.value,
          groundingSourcesJson = sourcesJson,
          isError = response.isError
        )
      } catch (e: Exception) {
        // High-order safety net: If anything failed in network or repository, run LocalAiEngine
        val fallbackResponse = LocalAiEngine.answerQuestion(
          userQuery = trimmed,
          history = currentHistory,
          model = _selectedModel.value,
          useGoogleSearch = _useGoogleSearch.value
        )
        val (cleanFallbackText, fallbackTopics) = com.example.data.ai.AcademicPedagogySynthesizer.extractAndCleanRelatedTopics(
          rawContent = fallbackResponse.text,
          fallbackQuery = trimmed
        )
        val activeSubFallback = _selectedTutorSubject.value
        val linkFallbackTopics = com.example.data.ai.ChatHistoryAssistant.getMiniTopicsForLinkedScope(
          queryOrText = "$trimmed $cleanFallbackText",
          groundingUrls = fallbackResponse.groundingSources.map { it.url },
          importedChats = importedChats.value,
          extractedConcepts = extractedConcepts.value,
          selectedSubject = activeSubFallback
        )
        val finalFallbackTopics = linkFallbackTopics.ifEmpty {
          fallbackResponse.relatedTopics.ifEmpty { fallbackTopics }
        }

        val fallbackMsg = ChatMessage(
          role = "model",
          text = cleanFallbackText,
          modelName = fallbackResponse.modelName,
          searchGroundingUsed = _useGoogleSearch.value,
          groundingSources = fallbackResponse.groundingSources,
          searchQueries = fallbackResponse.searchQueries,
          isError = false,
          relatedTopics = finalFallbackTopics
        )
        _chatMessages.value = _chatMessages.value + fallbackMsg

        try {
          val targetSid = sessionId ?: _activeSessionId.value
          if (targetSid != null) {
            repository.saveTutorMessage(
              sessionId = targetSid,
              role = "model",
              text = cleanFallbackText,
              modelName = fallbackResponse.modelName,
              searchGroundingUsed = _useGoogleSearch.value,
              groundingSourcesJson = "",
              isError = false
            )
          }
        } catch (_: Exception) {}
      } finally {
        _isChatLoading.value = false
      }
    }
  }

  // --- Subject Actions ---
  fun addSubject(name: String, code: String, colorHex: String) {
    viewModelScope.launch {
      repository.addSubject(name, code, colorHex, userId = _currentUser.value.id)
      _snackbarMessage.emit("Subject '$name' added")
    }
  }

  fun deleteSubject(subject: Subject) {
    viewModelScope.launch {
      repository.deleteSubject(subject)
      _snackbarMessage.emit("Subject removed")
    }
  }

  // --- Session Actions ---
  fun addSession(session: TimetableSession) {
    viewModelScope.launch {
      val sessionWithUser = if (session.userId.isBlank()) session.copy(userId = _currentUser.value.id) else session
      val insertedId = repository.addSession(sessionWithUser)
      val savedSession = if (insertedId > 0L && sessionWithUser.id == 0L) {
        sessionWithUser.copy(id = insertedId)
      } else {
        sessionWithUser
      }
      if (savedSession.isAlarmEnabled) {
        alarmAndCalendarService.scheduleSessionAlarm(savedSession)
      }
      val msg = if (savedSession.endTime.isBlank() && savedSession.isAlarmEnabled) {
        "⏰ Alarm set for '${savedSession.topic}' at ${formatTo12Hour(savedSession.startTime)}"
      } else {
        "Scheduled '${savedSession.topic}' (${formatTimeRange(savedSession.startTime, savedSession.endTime)})"
      }
      _snackbarMessage.emit(msg)
    }
  }

  fun updateSessionStatus(session: TimetableSession, newStatus: String) {
    viewModelScope.launch {
      val sessionWithUser = if (session.userId.isBlank()) session.copy(userId = _currentUser.value.id) else session
      repository.updateSession(sessionWithUser.copy(status = newStatus))
      _snackbarMessage.emit("Session marked as $newStatus")
    }
  }

  fun deleteSession(id: Long) {
    viewModelScope.launch {
      repository.deleteSession(id, userId = _currentUser.value.id)
      _snackbarMessage.emit("Class schedule deleted")
    }
  }

  fun updateSession(session: TimetableSession) {
    viewModelScope.launch {
      val sessionWithUser = if (session.userId.isBlank()) session.copy(userId = _currentUser.value.id) else session
      repository.updateSession(sessionWithUser)
      if (sessionWithUser.isAlarmEnabled) {
        alarmAndCalendarService.scheduleSessionAlarm(sessionWithUser)
      } else {
        alarmAndCalendarService.cancelSessionAlarm(sessionWithUser)
      }
      _snackbarMessage.emit("Updated '${sessionWithUser.topic}' (${formatTimeRange(sessionWithUser.startTime, sessionWithUser.endTime)})")
    }
  }

  fun toggleSessionAlarm(session: TimetableSession) {
    viewModelScope.launch {
      val updated = session.copy(isAlarmEnabled = !session.isAlarmEnabled)
      repository.updateSession(updated)
      if (updated.isAlarmEnabled) {
        alarmAndCalendarService.scheduleSessionAlarm(updated)
        _snackbarMessage.emit("⏰ Alarm enabled for '${session.topic}' (${formatTo12Hour(session.startTime)})")
      } else {
        alarmAndCalendarService.cancelSessionAlarm(updated)
        _snackbarMessage.emit("Alarm turned off for '${session.topic}'")
      }
    }
  }

  fun triggerTestAlarm(session: TimetableSession) {
    _ringingAlarmSession.value = session
    alarmAndCalendarService.triggerAlarmAlert(session)
    viewModelScope.launch {
      _snackbarMessage.emit("⏰ Alarm ringing for '${session.topic}'!")
    }
  }

  fun scheduleTestAlarm(session: TimetableSession, delaySeconds: Int = 5) {
    alarmAndCalendarService.scheduleTestAlarm(session, delaySeconds)
    viewModelScope.launch {
      _snackbarMessage.emit("⏰ Alarm set for $delaySeconds seconds from now! Testing ringtone & notification...")
    }
  }

  fun setQuickMobileAlarm(
    subjectName: String,
    topic: String,
    delayMinutes: Int = 1,
    customTime: String? = null
  ) {
    viewModelScope.launch {
      val dateStr = repository.getTodayDateString()
      val targetCal = java.util.Calendar.getInstance()
      
      if (!customTime.isNullOrBlank()) {
        val (h, m) = parseQuickTime(customTime)
        targetCal.set(java.util.Calendar.HOUR_OF_DAY, h)
        targetCal.set(java.util.Calendar.MINUTE, m)
        targetCal.set(java.util.Calendar.SECOND, 0)
        targetCal.set(java.util.Calendar.MILLISECOND, 0)
        if (targetCal.timeInMillis <= System.currentTimeMillis()) {
          targetCal.add(java.util.Calendar.DAY_OF_YEAR, 1)
        }
      } else {
        targetCal.add(java.util.Calendar.MINUTE, delayMinutes)
      }

      val timeFormat = java.text.SimpleDateFormat("HH:mm", java.util.Locale.getDefault())
      val startTimeStr = timeFormat.format(targetCal.time)

      val newSession = TimetableSession(
        userId = _currentUser.value.id,
        subjectId = 0L,
        subjectName = subjectName.ifBlank { "Alarm" },
        topic = topic.ifBlank { "Mobile Time Reminder" },
        date = dateStr,
        startTime = startTimeStr,
        endTime = "",
        location = "",
        notes = "Set using mobile device clock (${targetCal.timeZone.id})",
        recurringDays = "",
        reminderMinutes = 0,
        isAlarmEnabled = true,
        status = "SCHEDULED"
      )

      val id = repository.addSession(newSession)
      val savedSession = newSession.copy(id = id)
      alarmAndCalendarService.scheduleSessionAlarm(savedSession)

      val diffSeconds = ((targetCal.timeInMillis - System.currentTimeMillis()) / 1000).coerceAtLeast(1)
      val diffMins = diffSeconds / 60
      val remSecs = diffSeconds % 60
      val timeText = if (diffMins > 0) "${diffMins}m ${remSecs}s" else "${diffSeconds}s"

      _snackbarMessage.emit("⏰ Mobile Alarm set for ${formatTo12Hour(startTimeStr)} (in $timeText) based on mobile time!")
    }
  }

  private fun parseQuickTime(timeStr: String): Pair<Int, Int> {
    return try {
      val trimmed = timeStr.trim().uppercase(java.util.Locale.getDefault())
      val isPm = trimmed.contains("PM")
      val isAm = trimmed.contains("AM")
      val cleanTime = trimmed.replace("AM", "").replace("PM", "").trim()
      val delimiter = if (cleanTime.contains(":")) ":" else if (cleanTime.contains(".")) "." else " "
      val parts = cleanTime.split(delimiter).filter { it.isNotBlank() }
      var hour = parts.getOrNull(0)?.toIntOrNull() ?: 12
      val minute = parts.getOrNull(1)?.toIntOrNull() ?: 0
      if (isPm && hour < 12) hour += 12
      if (isAm && hour == 12) hour = 0
      Pair(hour.coerceIn(0, 23), minute.coerceIn(0, 59))
    } catch (e: Exception) {
      Pair(12, 0)
    }
  }

  fun triggerSessionAlarmById(sessionId: Long) {
    val session = sessions.value.find { it.id == sessionId }
    if (session != null) {
      triggerTestAlarm(session)
    }
  }

  fun dismissAlarm() {
    val session = _ringingAlarmSession.value
    if (session != null) {
      dismissedSessionIdsToday.add(session.id)
    }
    com.example.data.service.AlarmRingingService.stopRinging(getApplication())
    com.example.data.service.AlarmSoundManager.stopSoundAndVibration(getApplication())
    alarmAndCalendarService.stopAlarmSound()
    _ringingAlarmSession.value = null
    viewModelScope.launch {
      _snackbarMessage.emit("Alarm turned off")
    }
  }

  fun turnOffAlarm() {
    dismissAlarm()
  }

  fun snoozeAlarm(session: TimetableSession, minutes: Int = 5) {
    dismissedSessionIdsToday.add(session.id)
    com.example.data.service.AlarmRingingService.stopRinging(getApplication())
    com.example.data.service.AlarmSoundManager.stopSoundAndVibration(getApplication())
    alarmAndCalendarService.stopAlarmSound()
    _ringingAlarmSession.value = null
    alarmAndCalendarService.scheduleSnoozeAlarm(session.id, session.subjectName, session.topic, session.startTime, minutes)
    viewModelScope.launch {
      _snackbarMessage.emit("⏰ Snoozed for $minutes minutes for '${session.topic}'")
    }
  }

  fun syncSessionToGoogleCalendar(session: TimetableSession) {
    viewModelScope.launch {
      val updated = session.copy(googleCalendarEventId = "gcal_${session.id}_${System.currentTimeMillis()}")
      repository.updateSession(updated)
      val calendarTarget = _currentUser.value.email.ifBlank { "device calendar" }
      _snackbarMessage.emit("Synced '${session.topic}' to Google Calendar ($calendarTarget)")
    }
  }

  fun syncAllTodayToGoogleCalendar() {
    viewModelScope.launch {
      val today = repository.getTodayDateString()
      val todayList = sessions.value.filter { it.date == today }
      if (todayList.isEmpty()) {
        _snackbarMessage.emit("No classes or study sessions scheduled today to sync.")
      } else {
        todayList.forEach { s ->
          repository.updateSession(s.copy(googleCalendarEventId = "gcal_${s.id}_${System.currentTimeMillis()}"))
        }
        val calendarTarget = _currentUser.value.email.ifBlank { "device calendar" }
        _snackbarMessage.emit("Synced ${todayList.size} session(s) to Google Calendar ($calendarTarget)")
      }
    }
  }

  fun createDayScheduleBatch(newSessions: List<TimetableSession>) {
    viewModelScope.launch {
      val uid = _currentUser.value.id
      repository.addSessions(newSessions.map { it.copy(userId = uid) })
      _snackbarMessage.emit("Created ${newSessions.size} schedule session(s)!")
    }
  }

  fun replaceDayScheduleWithSessions(targetDate: String, newSessions: List<TimetableSession>) {
    viewModelScope.launch {
      val uid = _currentUser.value.id
      repository.replaceSessionsForDate(
        date = targetDate,
        sessions = newSessions.map { it.copy(id = 0L, date = targetDate, userId = uid) },
        userId = uid
      )
      _snackbarMessage.emit("Applied ${newSessions.size} session(s) to $targetDate!")
    }
  }

  fun addFoodAndSleepRoutineBatch(targetDate: String) {
    viewModelScope.launch {
      val uid = _currentUser.value.id
      val routines = listOf(
        TimetableSession(
          userId = uid,
          subjectId = -1L,
          subjectName = "Food & Meals",
          topic = "Breakfast & Morning Refreshment",
          date = targetDate,
          startTime = "08:00",
          endTime = "08:30",
          location = "Dining",
          notes = "Healthy breakfast meal time",
          isAlarmEnabled = true,
          sessionCategory = "FOOD",
          isRoutineOnly = true
        ),
        TimetableSession(
          userId = uid,
          subjectId = -1L,
          subjectName = "Food & Meals",
          topic = "Lunch Break & Energy Reset",
          date = targetDate,
          startTime = "13:00",
          endTime = "14:00",
          location = "Dining",
          notes = "Lunch break time",
          isAlarmEnabled = true,
          sessionCategory = "FOOD",
          isRoutineOnly = true
        ),
        TimetableSession(
          userId = uid,
          subjectId = -1L,
          subjectName = "Food & Meals",
          topic = "Dinner & Evening Rest",
          date = targetDate,
          startTime = "19:30",
          endTime = "20:30",
          location = "Dining",
          notes = "Dinner meal time",
          isAlarmEnabled = true,
          sessionCategory = "FOOD",
          isRoutineOnly = true
        ),
        TimetableSession(
          userId = uid,
          subjectId = -1L,
          subjectName = "Sleep & Rest",
          topic = "Night Sleep & Recovery (8h)",
          date = targetDate,
          startTime = "22:30",
          endTime = "06:30",
          location = "Bedroom",
          notes = "Unwind and full night sleep for optimal brain recovery",
          isAlarmEnabled = true,
          sessionCategory = "SLEEP",
          isRoutineOnly = true
        )
      )

      repository.addSessions(routines)
      _snackbarMessage.emit("🍱 Added Food & Sleep routine slots (with alarms) to $targetDate!")
    }
  }

  fun copyDaySchedule(sourceDate: String, targetDate: String, overwriteExisting: Boolean = false) {
    viewModelScope.launch {
      val uid = _currentUser.value.id
      val sourceSessions = sessions.value.filter { it.date == sourceDate }
      if (sourceSessions.isEmpty()) {
        _snackbarMessage.emit("No sessions found on $sourceDate to copy.")
        return@launch
      }

      val copied = sourceSessions.map { s ->
        s.copy(
          id = 0L,
          userId = uid,
          date = targetDate,
          status = "SCHEDULED",
          googleCalendarEventId = null
        )
      }
      if (overwriteExisting) {
        repository.replaceSessionsForDate(targetDate, copied, userId = uid)
      } else {
        repository.addSessions(copied)
      }
      _snackbarMessage.emit("Copied ${sourceSessions.size} session(s) from $sourceDate to $targetDate!")
    }
  }

  fun saveScheduleAsFormat(
    formatName: String,
    description: String,
    sourceSessions: List<TimetableSession>
  ) {
    viewModelScope.launch {
      if (sourceSessions.isEmpty()) {
        _snackbarMessage.emit("Cannot save an empty format.")
        return@launch
      }

      val jsonArray = org.json.JSONArray()
      sourceSessions.forEach { s ->
        val obj = org.json.JSONObject().apply {
          put("subjectId", s.subjectId)
          put("subjectName", s.subjectName)
          put("topic", s.topic)
          put("startTime", s.startTime)
          put("endTime", s.endTime)
          put("location", s.location)
          put("notes", s.notes)
          put("isAlarmEnabled", s.isAlarmEnabled)
        }
        jsonArray.put(obj)
      }

      val format = com.example.data.model.ScheduleFormat(
        userId = _currentUser.value.id,
        name = formatName.trim(),
        description = description.trim(),
        formatSessionsJson = jsonArray.toString()
      )
      repository.addScheduleFormat(format)
      _snackbarMessage.emit("Saved format '$formatName' with ${sourceSessions.size} session blocks!")
    }
  }

  fun applyFormatToDate(
    format: com.example.data.model.ScheduleFormat,
    targetDate: String,
    overwriteExisting: Boolean = false
  ) {
    viewModelScope.launch {
      val uid = _currentUser.value.id
      val parsedSessions = mutableListOf<TimetableSession>()
      try {
        val jsonArray = org.json.JSONArray(format.formatSessionsJson)
        for (i in 0 until jsonArray.length()) {
          val obj = jsonArray.getJSONObject(i)
          parsedSessions.add(
            TimetableSession(
              userId = uid,
              subjectId = obj.optLong("subjectId", 0L),
              subjectName = obj.optString("subjectName", "General"),
              topic = obj.optString("topic", "Study Block"),
              date = targetDate,
              startTime = obj.optString("startTime", "09:00"),
              endTime = obj.optString("endTime", ""),
              location = obj.optString("location", ""),
              notes = obj.optString("notes", ""),
              status = "SCHEDULED",
              isAlarmEnabled = obj.optBoolean("isAlarmEnabled", true)
            )
          )
        }
      } catch (e: Exception) {
        _snackbarMessage.emit("Error applying format: ${e.message}")
        return@launch
      }

      if (overwriteExisting) {
        repository.replaceSessionsForDate(targetDate, parsedSessions, userId = uid)
      } else {
        repository.addSessions(parsedSessions)
      }
      _snackbarMessage.emit("Applied format '${format.name}' (${parsedSessions.size} sessions) to $targetDate!")
    }
  }

  fun deleteScheduleFormat(formatId: Long) {
    viewModelScope.launch {
      repository.deleteScheduleFormat(formatId)
      _snackbarMessage.emit("Schedule format deleted")
    }
  }

  fun clearDaySchedule(targetDate: String) {
    viewModelScope.launch {
      repository.clearSessionsForDate(targetDate, userId = _currentUser.value.id)
      _snackbarMessage.emit("Cleared all sessions for $targetDate")
    }
  }


  // --- Topic Actions ---
  fun addTopic(
    subjectId: Long,
    subjectName: String,
    title: String,
    descriptionNotes: String,
    sourceType: String,
    uploadedFileName: String? = null,
    generateQuestionsImmediately: Boolean = false
  ) {
    viewModelScope.launch {
      _isAiLoading.value = true
      try {
        val today = repository.getTodayDateString()
        val uid = _currentUser.value.id
        val topic = StudyTopic(
          userId = uid,
          subjectId = subjectId,
          subjectName = subjectName,
          title = title,
          dateStudied = today,
          descriptionNotes = descriptionNotes,
          sourceType = sourceType,
          uploadedFileName = uploadedFileName,
          masteryLevel = "UNTESTED",
          repetitionIntervalDays = 1,
          nextRevisionDate = today
        )
        val newId = repository.addTopic(topic)

        if (generateQuestionsImmediately) {
          // Pre-generate practice questions
          repository.assembleWeekendQuiz(
            config = QuizConfig(questionCount = 3),
            filterSubjectId = subjectId,
            userId = uid
          )
        }

        _snackbarMessage.emit("Topic '$title' saved successfully")
      } finally {
        _isAiLoading.value = false
      }
    }
  }

  fun updateTopicMastery(topic: StudyTopic, newMastery: String) {
    viewModelScope.launch {
      val newInterval = when (newMastery) {
        "MASTERED" -> 14
        "FAMILIAR" -> 7
        "STRUGGLING" -> 1
        else -> 1
      }
      repository.updateTopic(
        topic.copy(
          masteryLevel = newMastery,
          repetitionIntervalDays = newInterval
        )
      )
      _snackbarMessage.emit("Topic updated to $newMastery")
    }
  }

  fun summarizeTopicWithAi(title: String, rawContent: String, onComplete: (String) -> Unit) {
    viewModelScope.launch {
      _isAiLoading.value = true
      try {
        val summary = repository.summarizeMaterial(title, rawContent)
        onComplete(summary)
      } finally {
        _isAiLoading.value = false
      }
    }
  }

  fun deleteTopic(id: Long) {
    viewModelScope.launch {
      repository.deleteTopic(id, userId = _currentUser.value.id)
      _snackbarMessage.emit("Study topic deleted")
    }
  }

  // --- Task Actions ---
  fun addTask(title: String, subjectName: String, dueDate: String, type: String, priority: String) {
    viewModelScope.launch {
      repository.addTask(
        StudyTask(
          userId = _currentUser.value.id,
          title = title,
          subjectName = subjectName,
          dueDate = dueDate,
          type = type,
          priority = priority
        )
      )
      _snackbarMessage.emit("Task added")
    }
  }

  fun toggleTaskCompletion(task: StudyTask) {
    viewModelScope.launch {
      repository.updateTask(task.copy(isCompleted = !task.isCompleted))
    }
  }

  fun deleteTask(id: Long) {
    viewModelScope.launch {
      repository.deleteTask(id, userId = _currentUser.value.id)
      _snackbarMessage.emit("Task deleted")
    }
  }

  // --- Quiz Execution & Interactive Experience ---
  fun startWeekendQuiz(forceWeakOnly: Boolean = false, filterSubjectId: Long? = null) {
    viewModelScope.launch {
      _isAiLoading.value = true
      try {
        val generated = repository.assembleWeekendQuiz(
          config = _quizConfig.value,
          filterSubjectId = filterSubjectId,
          forceWeakOnly = forceWeakOnly,
          userId = _currentUser.value.id
        )

        if (generated.questions.isEmpty()) {
          _snackbarMessage.emit("No questions available for this configuration. Add more study topics first!")
          return@launch
        }

        val quizTitle = if (forceWeakOnly) "Quick Revision: Struggling Topics" else "Weekend Revision Quiz"

        _activeQuizState.value = ActiveQuizState(
          isActive = true,
          title = quizTitle,
          questions = generated.questions,
          currentIndex = 0,
          userAnswers = emptyMap(),
          flaggedQuestionIds = emptySet(),
          evaluations = emptyMap(),
          isSubmitted = false,
          testedTopics = generated.testedTopics,
          queuedUntestedTopics = generated.queuedUntestedTopics,
          isAllCovered = generated.isAllCovered,
          completedAttempt = null,
          isEvaluating = false
        )
        _currentScreen.value = Screen.ActiveQuiz
      } finally {
        _isAiLoading.value = false
      }
    }
  }

  fun selectQuestionIndex(index: Int) {
    val current = _activeQuizState.value
    if (index in current.questions.indices) {
      _activeQuizState.value = current.copy(currentIndex = index)
    }
  }

  fun nextQuestion() {
    val current = _activeQuizState.value
    if (current.currentIndex + 1 < current.questions.size) {
      _activeQuizState.value = current.copy(currentIndex = current.currentIndex + 1)
    }
  }

  fun previousQuestion() {
    val current = _activeQuizState.value
    if (current.currentIndex > 0) {
      _activeQuizState.value = current.copy(currentIndex = current.currentIndex - 1)
    }
  }

  fun answerCurrentQuestion(answer: String) {
    val current = _activeQuizState.value
    val currentQ = current.questions.getOrNull(current.currentIndex) ?: return
    val newAnswers = current.userAnswers.toMutableMap()
    newAnswers[currentQ.id] = answer
    _activeQuizState.value = current.copy(userAnswers = newAnswers)
  }

  fun toggleFlagCurrentQuestion() {
    val current = _activeQuizState.value
    val currentQ = current.questions.getOrNull(current.currentIndex) ?: return
    val newFlags = current.flaggedQuestionIds.toMutableSet()
    if (newFlags.contains(currentQ.id)) {
      newFlags.remove(currentQ.id)
    } else {
      newFlags.add(currentQ.id)
    }
    _activeQuizState.value = current.copy(flaggedQuestionIds = newFlags)
  }

  fun submitActiveQuiz() {
    val current = _activeQuizState.value
    if (current.questions.isEmpty() || current.isSubmitted) return

    viewModelScope.launch {
      _activeQuizState.value = current.copy(isEvaluating = true)
      try {
        val evalMap = coroutineScope {
          current.questions.map { q ->
            async(Dispatchers.IO) {
              val ans = current.userAnswers[q.id] ?: ""
              q.id to repository.evaluateUserAnswer(q, ans)
            }
          }.awaitAll().toMap()
        }

        val attempt = repository.recordQuizResults(
          title = current.title,
          questions = current.questions,
          userAnswers = current.userAnswers,
          evaluations = evalMap,
          topicsCoveredCount = current.testedTopics.size,
          topicsSkippedCount = current.queuedUntestedTopics.size,
          userId = _currentUser.value.id
        )

        _activeQuizState.value = current.copy(
          evaluations = evalMap,
          isSubmitted = true,
          completedAttempt = attempt,
          isEvaluating = false
        )
        _snackbarMessage.emit("Quiz completed! Scored ${attempt.scorePercentage}%")
      } catch (e: Exception) {
        _activeQuizState.value = _activeQuizState.value.copy(isEvaluating = false)
        _snackbarMessage.emit("Error scoring quiz: ${e.message}")
      }
    }
  }

  fun retryIncorrectQuestions() {
    val current = _activeQuizState.value
    val incorrectQuestions = current.questions.filter { q ->
      current.evaluations[q.id]?.isCorrect != true
    }

    if (incorrectQuestions.isEmpty()) {
      viewModelScope.launch {
        _snackbarMessage.emit("No mistakes to retry! Perfect score!")
      }
      return
    }

    _activeQuizState.value = ActiveQuizState(
      isActive = true,
      title = "Retry Mistakes: ${current.title}",
      questions = incorrectQuestions,
      currentIndex = 0,
      userAnswers = emptyMap(),
      flaggedQuestionIds = emptySet(),
      evaluations = emptyMap(),
      isSubmitted = false,
      testedTopics = current.testedTopics,
      queuedUntestedTopics = emptyList(),
      isAllCovered = true,
      completedAttempt = null,
      isEvaluating = false
    )
  }

  fun exitActiveQuiz() {
    _activeQuizState.value = ActiveQuizState(isActive = false)
    _currentScreen.value = Screen.WeekendQuizzes
  }

  // Pre-seed helper for demo
  fun seedSampleData() {
    viewModelScope.launch {
      repository.seedSampleUniversityData(_currentUser.value.id, force = true)
      _snackbarMessage.emit("Sample university data loaded")
    }
  }

  fun deleteAllSampleData() {
    viewModelScope.launch {
      val userId = _currentUser.value.id
      for (s in sessions.value) {
        alarmAndCalendarService.cancelSessionAlarm(s)
      }
      dismissAlarm()
      repository.deleteAllSampleData(userId)
      _snackbarMessage.emit("All sample data cleared permanently.")
    }
  }

  // =========================================================================
  // AI CHAT HISTORY IMPORT & INTELLIGENT LEARNING MEMORY METHODS
  // =========================================================================

  fun setQuestionSource(source: String) {
    _quizConfig.value = _quizConfig.value.copy(questionSource = source)
  }

  fun fetchAndAnalyzeChatFromLink(
    chatUrl: String,
    customTitle: String = "",
    subject: Subject? = null,
    tags: String = ""
  ) {
    val cleanUrl = chatUrl.trim()
    if (cleanUrl.isBlank() || !ChatLinkFetcher.isUrl(cleanUrl)) {
      viewModelScope.launch {
        _snackbarMessage.emit("Please paste a valid shared chat link (https://...).")
      }
      return
    }

    viewModelScope.launch {
      _isImportProcessing.value = true
      _importProcessingProgress.value = 0.05f
      _importProcessingStatusText.value = "Connecting to shared chat link..."
      _previewSubject.value = subject
      _previewSubjectSuggestion.value = null
      _previewTags.value = tags
      _previewSourceUrl.value = ChatLinkFetcher.normalizeUrl(cleanUrl)

      try {
        val fetched = ChatLinkFetcher.fetchChatFromUrl(
          rawUrl = cleanUrl,
          onProgress = { p, status ->
            _importProcessingProgress.value = p * 0.45f
            _importProcessingStatusText.value = status
          }
        )

        if (!fetched.isSuccess || fetched.conversationText.isBlank()) {
          _isImportProcessing.value = false
          _importProcessingStatusText.value = ""
          _snackbarMessage.emit(fetched.errorMessage ?: "Could not retrieve conversation from link. Please ensure link is public.")
          return@launch
        }

        val effectiveTitle = customTitle.ifBlank { fetched.title }
        _previewTitle.value = effectiveTitle
        _previewRawText.value = fetched.conversationText
        _previewPlatform.value = fetched.platform
        _previewSourceUrl.value = fetched.url

        // Auto-group Subject when user chose Auto-Group (subject == null)
        val suggestion = if (subject == null) {
          ChatKnowledgeExtractor.detectBestSubject(
            title = effectiveTitle,
            rawText = fetched.conversationText,
            sourceUrl = fetched.url,
            discoveredTopics = emptyList(),
            existingSubjects = subjects.value
          )
        } else {
          ChatKnowledgeExtractor.SubjectSuggestion(
            matchedSubject = subject,
            suggestedName = subject.name,
            suggestedCode = subject.code,
            suggestedColorHex = subject.colorHex
          )
        }
        _previewSubjectSuggestion.value = suggestion
        if (subject == null && suggestion.matchedSubject != null) {
          _previewSubject.value = suggestion.matchedSubject
        }

        _importProcessingStatusText.value = "Extracting academic knowledge (${suggestion.suggestedName})..."

        val result = ChatKnowledgeExtractor.processAndExtract(
          title = effectiveTitle,
          subjectName = suggestion.suggestedName,
          rawText = fetched.conversationText,
          tags = tags,
          existingConcepts = extractedConcepts.value,
          aiDispatcher = { prompt ->
            repository.geminiService.executeAiPrompt(prompt, "You extract university academic curriculum concepts.", 0.2)
          },
          onProgress = { p, status ->
            _importProcessingProgress.value = 0.45f + (p * 0.55f)
            _importProcessingStatusText.value = status
          }
        )

        // Re-refine auto-detected subject with discovered topics if auto-grouping
        if (subject == null) {
          val refined = ChatKnowledgeExtractor.detectBestSubject(
            title = effectiveTitle,
            rawText = fetched.conversationText,
            sourceUrl = fetched.url,
            discoveredTopics = result.discoveredTopics,
            existingSubjects = subjects.value
          )
          _previewSubjectSuggestion.value = refined
          _previewSubject.value = refined.matchedSubject
        }

        _extractionPreview.value = result
        _importProcessingProgress.value = 1.0f
        _importProcessingStatusText.value = "Knowledge extracted successfully! Review before saving."
        _snackbarMessage.emit("Retrieved ${fetched.wordCount} words from ${fetched.platform} & extracted ${result.concepts.size} concepts!")
      } catch (e: Exception) {
        _importProcessingStatusText.value = ""
        _snackbarMessage.emit("Link extraction notice: ${e.localizedMessage}")
      } finally {
        _isImportProcessing.value = false
      }
    }
  }

  fun confirmSaveExtractedChat(
    customTitle: String? = null,
    customSubject: Subject? = null,
    customTags: String? = null,
    manualNewSubjectName: String? = null,
    manualNewSubjectCode: String? = null
  ) {
    val preview = _extractionPreview.value ?: return
    val sourceUrl = _previewSourceUrl.value.trim()
    if (sourceUrl.isBlank()) {
      viewModelScope.launch {
        _snackbarMessage.emit("A valid chat link is required to save into Learning Memory.")
      }
      return
    }
    val user = _currentUser.value

    viewModelScope.launch {
      _isImportProcessing.value = true
      _importProcessingStatusText.value = "Grouping into subject and saving learning memory..."
      try {
        val effectiveTitle = (customTitle ?: _previewTitle.value).ifBlank {
          "AI Discussion on ${preview.discoveredTopics.firstOrNull() ?: preview.discoveredSubject}"
        }
        val effectiveTags = customTags ?: _previewTags.value
        val effectivePlatform = if (_previewPlatform.value != "Auto-detect") _previewPlatform.value else preview.platform

        val resolvedSubject: Subject = when {
          !manualNewSubjectName.isNullOrBlank() -> {
            val cleanName = manualNewSubjectName.trim()
            val cleanCode = manualNewSubjectCode?.trim().orEmpty().ifBlank {
              cleanName.split(Regex("\\s+")).mapNotNull { it.firstOrNull()?.uppercaseChar() }.take(3).joinToString("").ifBlank { "SUB" } + "101"
            }
            val newId = repository.addSubject(cleanName, cleanCode, "#8C9F85", userId = user.id)
            Subject(id = newId, userId = user.id, name = cleanName, code = cleanCode, colorHex = "#8C9F85")
          }
          customSubject != null -> customSubject
          _previewSubject.value != null -> _previewSubject.value!!
          else -> repository.resolveOrCreateSubjectForImport(
            userId = user.id,
            title = effectiveTitle,
            rawText = _previewRawText.value,
            sourceUrl = sourceUrl,
            discoveredTopics = preview.discoveredTopics,
            manualSubject = null
          )
        }

        val importedChat = ImportedChat(
          userId = user.id,
          title = effectiveTitle,
          subjectId = resolvedSubject.id,
          subjectName = resolvedSubject.name,
          sourceUrl = sourceUrl,
          aiPlatform = effectivePlatform,
          rawConversation = _previewRawText.value,
          tags = effectiveTags,
          wordCount = preview.wordCount,
          messageCount = preview.messageCount,
          extractedConceptsCount = preview.concepts.size,
          chunkCount = preview.chunkCount,
          notes = preview.discoveredTopics.joinToString(", ")
        )

        repository.saveImportedChatWithConcepts(
          userId = user.id,
          chat = importedChat,
          drafts = preview.concepts
        )

        _extractionPreview.value = null
        _previewRawText.value = ""
        _previewSourceUrl.value = ""
        _previewTitle.value = ""
        _previewTags.value = ""
        _previewSubjectSuggestion.value = null
        _snackbarMessage.emit("Saved under '${resolvedSubject.name}' with ${preview.concepts.size} concepts!")
      } catch (e: Exception) {
        _snackbarMessage.emit("Failed to save conversation: ${e.localizedMessage}")
      } finally {
        _isImportProcessing.value = false
        _importProcessingStatusText.value = ""
      }
    }
  }

  fun moveChatToSubject(chat: ImportedChat, targetSubject: Subject) {
    viewModelScope.launch {
      repository.moveImportedChatToSubject(_currentUser.value.id, chat, targetSubject)
      _snackbarMessage.emit("Moved '${chat.title}' to ${targetSubject.name}")
    }
  }

  fun createSubjectAndMoveChat(chat: ImportedChat, newName: String, newCode: String) {
    val cleanName = newName.trim()
    if (cleanName.isBlank()) return
    val cleanCode = newCode.trim().ifBlank {
      cleanName.split(Regex("\\s+")).mapNotNull { it.firstOrNull()?.uppercaseChar() }.take(3).joinToString("").ifBlank { "SUB" } + "101"
    }
    viewModelScope.launch {
      val uid = _currentUser.value.id
      val newId = repository.addSubject(cleanName, cleanCode, "#8C9F85", userId = uid)
      val sub = Subject(id = newId, userId = uid, name = cleanName, code = cleanCode, colorHex = "#8C9F85")
      repository.moveImportedChatToSubject(uid, chat, sub)
      _snackbarMessage.emit("Created '$cleanName' and grouped '${chat.title}'")
    }
  }

  fun discardExtractionPreview() {
    _extractionPreview.value = null
    _previewSourceUrl.value = ""
    _previewSubjectSuggestion.value = null
    _importProcessingStatusText.value = ""
    _importProcessingProgress.value = 0f
  }

  fun updateImportedChat(chat: ImportedChat) {
    viewModelScope.launch {
      repository.updateImportedChat(chat)
      _snackbarMessage.emit("Conversation details updated")
    }
  }

  fun deleteImportedChat(chatId: Long) {
    viewModelScope.launch {
      repository.deleteImportedChat(_currentUser.value.id, chatId)
      _snackbarMessage.emit("Conversation & associated concepts removed")
    }
  }

  fun reprocessImportedChat(chat: ImportedChat) {
    viewModelScope.launch {
      _isImportProcessing.value = true
      _importProcessingStatusText.value = "Reprocessing '${chat.title}'..."
      _importProcessingProgress.value = 0.2f
      try {
        val result = ChatKnowledgeExtractor.processAndExtract(
          title = chat.title,
          subjectName = chat.subjectName,
          rawText = chat.rawConversation,
          tags = chat.tags,
          existingConcepts = extractedConcepts.value,
          aiDispatcher = { prompt ->
            repository.geminiService.executeAiPrompt(prompt, "You extract university academic curriculum concepts.", 0.2)
          },
          onProgress = { p, status ->
            _importProcessingProgress.value = p
            _importProcessingStatusText.value = status
          }
        )

        repository.deleteImportedChat(_currentUser.value.id, chat.id)
        val updatedChat = chat.copy(
          id = 0L,
          wordCount = result.wordCount,
          messageCount = result.messageCount,
          extractedConceptsCount = result.concepts.size,
          chunkCount = result.chunkCount,
          notes = result.discoveredTopics.joinToString(", ")
        )
        repository.saveImportedChatWithConcepts(
          userId = _currentUser.value.id,
          chat = updatedChat,
          drafts = result.concepts
        )
        _snackbarMessage.emit("Reprocessed '${chat.title}': ${result.concepts.size} concepts updated!")
      } catch (e: Exception) {
        _snackbarMessage.emit("Reprocessing notice: ${e.localizedMessage}")
      } finally {
        _isImportProcessing.value = false
        _importProcessingStatusText.value = ""
      }
    }
  }

  fun updateExtractedConcept(concept: ExtractedConcept) {
    viewModelScope.launch {
      repository.updateExtractedConcept(concept)
      _snackbarMessage.emit("Concept updated")
    }
  }

  fun deleteExtractedConcept(conceptId: Long) {
    viewModelScope.launch {
      repository.deleteExtractedConcept(conceptId)
      _snackbarMessage.emit("Concept deleted")
    }
  }

  fun startQuizForConcept(concept: ExtractedConcept) {
    viewModelScope.launch {
      _isAiLoading.value = true
      try {
        val questions = repository.geminiService.generateQuestionsForConcept(
          concept = concept,
          desiredCount = 5,
          difficulty = _quizConfig.value.difficulty
        )

        if (questions.isEmpty()) {
          _snackbarMessage.emit("Could not assemble questions for this concept right now.")
          return@launch
        }

        val matchingTopic = StudyTopic(
          id = 0L,
          userId = _currentUser.value.id,
          subjectId = concept.subjectId ?: 0L,
          subjectName = concept.subjectName,
          title = concept.conceptName,
          dateStudied = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(java.util.Date()),
          sourceType = "IMPORTED_CHAT",
          descriptionNotes = "${concept.topic} - ${concept.subtopic}: ${concept.definition}\n${concept.technicalDetails}"
        )

        _activeQuizState.value = ActiveQuizState(
          isActive = true,
          title = "Concept Drill: ${concept.conceptName}",
          questions = questions,
          currentIndex = 0,
          userAnswers = emptyMap(),
          flaggedQuestionIds = emptySet(),
          evaluations = emptyMap(),
          isSubmitted = false,
          testedTopics = listOf(matchingTopic),
          queuedUntestedTopics = emptyList(),
          isAllCovered = true,
          completedAttempt = null,
          isEvaluating = false
        )
        _currentScreen.value = Screen.ActiveQuiz
      } finally {
        _isAiLoading.value = false
      }
    }
  }
}
