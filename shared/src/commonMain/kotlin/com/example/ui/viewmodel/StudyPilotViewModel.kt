package com.example.ui.viewmodel

import kotlinx.coroutines.sync.withLock
import com.example.data.ai.llm.CloudProvider
import kotlinx.coroutines.flow.first
import com.example.data.ai.AiTask
import kotlinx.coroutines.Job
import com.example.data.sync.SyncEvents
import androidx.lifecycle.ViewModel
import com.example.data.ai.StudyAi
import com.example.data.schedule.CurrentScheduleStatus
import com.example.data.schedule.ScheduleLogic
import com.example.data.schedule.ScheduleState
import com.example.platform.AlarmEvent
import com.example.platform.AlarmEventBus
import com.example.platform.Platform
import com.example.platform.isValidEmail
import androidx.lifecycle.viewModelScope
import com.example.data.local.AppDatabase
import com.example.data.local.LocalPasswordStore
import com.example.data.model.ChatMessage
import com.example.data.model.QuizAttempt
import com.example.data.model.QuizConfig
import com.example.data.model.QuizQuestion
import com.example.data.model.StudyTask
import com.example.data.model.StudyTopic
import com.example.data.model.Subject
import com.example.data.model.TimetableSession
import com.example.data.model.TutorMessageEntity
import com.example.data.ai.AiSource
import com.example.data.ai.llm.DownloadState
import com.example.data.ai.llm.PhoneModel
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
import com.example.data.model.UserProfile

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
  object Notes : Screen("Notes")
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

/** When this device's cloud keys last changed, for sharing with the paired device. */
private const val KEYS_UPDATED_AT = "keys_updated_at"

class StudyPilotViewModel(private val platform: Platform) : ViewModel() {

  val repository: StudyPilotRepository = StudyPilotRepository(
    dao = platform.database.studyPilotDao(),
    prefs = platform.prefs("studypilot_seeding_prefs"),
    cloud = platform.cloud,
    ai = StudyAi(platform.onDeviceModels),
    stableTopicIds = { ids -> platform.syncEngine.stableIds("study_topics", ids) }
  )

  private val alarms get() = platform.alarms

  // --- Alarm sound ---
  private val soundPrefs by lazy { platform.prefs(com.example.platform.AlarmSound.STORE) }
  private val _alarmSound = MutableStateFlow(com.example.platform.AlarmSound.byName(soundPrefs.getString(com.example.platform.AlarmSound.KEY, null)))
  /** The sound alarms play, chosen in Settings. */
  val alarmSound: StateFlow<com.example.platform.AlarmSound> = _alarmSound.asStateFlow()

  /** Saves [sound] for every alarm from now on and plays a few seconds of it. */
  fun setAlarmSound(sound: com.example.platform.AlarmSound) {
    _alarmSound.value = sound
    uiPrefs.edit { putString(com.example.platform.AlarmSound.KEY, sound.name) }
    alarms.previewSound(sound)
  }

  // --- AI API Keys & Provider Settings ---
  private val aiPrefs = platform.prefs("studypilot_ai_config")

  private val _aiSource = MutableStateFlow(
    aiPrefs.getString("ai_source", null)?.let { name -> AiSource.entries.firstOrNull { it.name == name } } ?: AiSource.AUTO
  )
  val aiSource: StateFlow<AiSource> = _aiSource.asStateFlow()

  private val _pcUrl = MutableStateFlow(aiPrefs.getString("pc_ollama_url", "") ?: "")
  val pcUrl: StateFlow<String> = _pcUrl.asStateFlow()

  private val _pcModel = MutableStateFlow(aiPrefs.getString("pc_ollama_model", "") ?: "")
  val pcModel: StateFlow<String> = _pcModel.asStateFlow()

  // Cloud chatbot keys. Each provider keeps its own key and model in a separate store that isn't
  // backed up or transferred to a new phone. It is shared only with the paired PC or phone, encrypted
  // with the pairing (KeyShare), so a key saved on one device works on both.
  private val keyPrefs = platform.prefs("studypilot_keys").also { keys ->
    // Keys used to live with the other AI settings.
    aiPrefs.getString("cloud_api_key", null)?.takeIf { it.isNotBlank() }?.let { old ->
      keys.edit { putString("key_${CloudProvider.GROQ.name}", old) }
      aiPrefs.edit().remove("cloud_api_key").remove("cloud_model").remove("cloud_base_url").apply()
    }
  }
  private val _cloudProvider = MutableStateFlow(CloudProvider.byName(keyPrefs.getString("provider", null)))
  val cloudProvider: StateFlow<CloudProvider> = _cloudProvider.asStateFlow()
  private val _cloudKey = MutableStateFlow(keyPrefs.getString("key_${_cloudProvider.value.name}", "") ?: "")
  val cloudKey: StateFlow<String> = _cloudKey.asStateFlow()
  private val _cloudModel = MutableStateFlow(keyPrefs.getString("model_${_cloudProvider.value.name}", "") ?: "")
  val cloudModel: StateFlow<String> = _cloudModel.asStateFlow()
  private val _cloudBaseUrl = MutableStateFlow(keyPrefs.getString("custom_base_url", "") ?: "")
  val cloudBaseUrl: StateFlow<String> = _cloudBaseUrl.asStateFlow()
  /** Providers that have a key saved on this device. */
  private val _providersWithKeys = MutableStateFlow(CloudProvider.entries.filter { !keyPrefs.getString("key_${it.name}", null).isNullOrBlank() }.toSet())
  val providersWithKeys: StateFlow<Set<CloudProvider>> = _providersWithKeys.asStateFlow()
  private val _cloudStatus = MutableStateFlow<String?>(null)
  val cloudStatus: StateFlow<String?> = _cloudStatus.asStateFlow()

  /** A key picked for a job in Settings (missing = Auto: the app picks the best saved key). */
  private val _jobProviders = MutableStateFlow(
    com.example.data.ai.AiJob.entries.mapNotNull { job ->
      aiPrefs.getString("job_${job.name}", null)?.takeIf { it.isNotBlank() }?.let { name -> CloudProvider.entries.firstOrNull { it.name == name }?.let { job to it } }
    }.toMap()
  )
  val jobProviders: StateFlow<Map<com.example.data.ai.AiJob, CloudProvider>> = _jobProviders.asStateFlow()

  /** Which job each saved key does first ("Groq: Quizzes", "Gemini: Reading documents"). */
  private val _keyJobs = MutableStateFlow<Map<CloudProvider, List<com.example.data.ai.AiJob>>>(emptyMap())
  val keyJobs: StateFlow<Map<CloudProvider, List<com.example.data.ai.AiJob>>> = _keyJobs.asStateFlow()

  /** Uses [provider]'s key for [job] first, or null for Auto. */
  fun setJobProvider(job: com.example.data.ai.AiJob, provider: CloudProvider?) {
    _jobProviders.value = if (provider == null) _jobProviders.value - job else _jobProviders.value + (job to provider)
    aiPrefs.edit { putString("job_${job.name}", provider?.name ?: "") }
    applyCloudConfig()
  }
  private val _cloudModels = MutableStateFlow<List<String>>(emptyList())
  val cloudModels: StateFlow<List<String>> = _cloudModels.asStateFlow()
  private val _isCheckingCloud = MutableStateFlow(false)
  val isCheckingCloud: StateFlow<Boolean> = _isCheckingCloud.asStateFlow()

  /** Result of the last PC check, e.g. "Connected. Models: qwen3:8b". Null until checked. */
  private val _pcStatus = MutableStateFlow<String?>(null)
  val pcStatus: StateFlow<String?> = _pcStatus.asStateFlow()

  private val _pcModels = MutableStateFlow<List<String>>(emptyList())
  val pcModels: StateFlow<List<String>> = _pcModels.asStateFlow()

  private val _isCheckingPc = MutableStateFlow(false)
  val isCheckingPc: StateFlow<Boolean> = _isCheckingPc.asStateFlow()

  /** Which model the next tutor answer will come from, e.g. "Phone · Gemma 4 E2B". */
  private val _activeAiLabel = MutableStateFlow("Checking...")
  val activeAiLabel: StateFlow<String> = _activeAiLabel.asStateFlow()

  val phoneDownload: StateFlow<DownloadState> = repository.ai.phone?.download ?: MutableStateFlow(DownloadState.Idle)
  val phoneInstalled: StateFlow<Set<PhoneModel>> = repository.ai.phone?.installed ?: MutableStateFlow(emptySet())
  val phoneSelected: StateFlow<PhoneModel?> = repository.ai.phone?.selected ?: MutableStateFlow(null)

  fun deviceRamGb(): Int = repository.ai.phone?.deviceRamGb() ?: 0

  /** False on the desktop, which has no on-device model. */
  val hasPhoneModels: Boolean get() = repository.ai.phone != null

  // --- UI notifications & Theme ---
  // Buffered, so posting a message never waits for the previous one to finish showing.
  private val _snackbarMessage = MutableSharedFlow<String>(extraBufferCapacity = 8, onBufferOverflow = kotlinx.coroutines.channels.BufferOverflow.DROP_OLDEST)
  val snackbarMessage: SharedFlow<String> = _snackbarMessage.asSharedFlow()

  private val uiPrefs = platform.prefs("studypilot_ui_prefs")
  private val _themeMode = MutableStateFlow(uiPrefs.getString("theme_mode", "system") ?: "system")
  val themeMode: StateFlow<String> = _themeMode.asStateFlow()

  fun setThemeMode(mode: String) {
    _themeMode.value = mode
    uiPrefs.edit().putString("theme_mode", mode).apply()
  }



  companion object {
    const val GUEST_USER_ID = "guest_user"
    const val MIN_PASSWORD_LENGTH = 6
    private const val EMAIL_PROVIDER = "Email & Password"
    private const val GOOGLE_PROVIDER = "Google Sign-In"
    private const val DEFAULT_OLLAMA_URL = "http://10.0.2.2:11434"
    private const val DEFAULT_OLLAMA_MODEL = "llama3.2"
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
  private val authPrefs = platform.prefs("studypilot_auth_prefs")
  private val passwords = LocalPasswordStore(authPrefs)

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

  fun getRememberedEmail(): String =
    _loginPrefillEmail.value ?: authPrefs.getString("remembered_email", "") ?: ""

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

  /** The user's own notes, pinned first, then most recently edited. */
  @OptIn(ExperimentalCoroutinesApi::class)
  val notes: StateFlow<List<com.example.data.model.StudyNote>> = _currentUser
    .flatMapLatest { repository.notesFor(it.id) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  /** Adds or updates a note; a blank one is not saved. */
  fun saveNote(note: com.example.data.model.StudyNote) {
    if (note.title.isBlank() && note.heading.isBlank() && note.body.isBlank()) return
    viewModelScope.launch {
      repository.saveNote(
        note.copy(
          userId = note.userId.ifBlank { _currentUser.value.id },
          title = note.title.trim(),
          heading = note.heading.trim().ifBlank { note.body.trim().lineSequence().firstOrNull().orEmpty().take(60) },
          updatedAt = System.currentTimeMillis()
        )
      )
      _snackbarMessage.emit(if (note.id == 0L) "Note saved" else "Note updated")
    }
  }

  fun deleteNote(id: Long) {
    viewModelScope.launch {
      repository.deleteNote(id)
      _snackbarMessage.emit("Note deleted")
    }
  }

  /** Saved tutor chats as question-and-answer threads, newest first. */
  suspend fun tutorQaThreads(): List<com.example.data.notes.QaThread> =
    repository.tutorChatsWithMessages(_currentUser.value.id).mapNotNull { (session, messages) ->
      val pairs = com.example.data.notes.QaPairs.fromTurns(messages.filterNot { it.isError }.map { (it.role == "user") to it.text })
      if (pairs.isEmpty()) null
      else com.example.data.notes.QaThread(
        session.title.trim().ifBlank { pairs.first().question.lineSequence().first().take(80) },
        session.subjectName, "Tutor", session.lastUpdatedAt, pairs
      )
    }

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
    ScheduleLogic.evaluateCurrentStatus(list, now)
  }.flowOn(Dispatchers.Default)
    .distinctUntilChanged()
    .stateIn(
      viewModelScope,
      SharingStarted.WhileSubscribed(5000),
      CurrentScheduleStatus(ScheduleState.FREE_TIME, formattedStatusText = "Checking mobile schedule status...")
    )

  init {
    // Lets the phone-PC sync carry cloud keys (see KeyShare).
    com.example.data.sync.KeyShare.export = ::exportCloudKeys
    com.example.data.sync.KeyShare.import = ::importCloudKeys
    repository.ai.quizCloudFirst = aiPrefs.getBoolean("quiz_cloud_first", true)
    viewModelScope.launch {
      kotlinx.coroutines.delay(5_000) // after sign-in and the first sync settle
      refreshConnectionsIfDue()
      runCatching { repository.purgeOrphanQuestions() }
      resumeUnfinishedReads()
    }
  }

  init {
    repository.ai.source = _aiSource.value
    repository.ai.pc.baseUrl = _pcUrl.value
    repository.ai.pc.preferredModel = _pcModel.value
    applyCloudConfig()
    refreshActiveAi()
    // The "answering with" label must follow downloads, deletions and model switches.
    repository.ai.phone?.let { phone ->
      viewModelScope.launch {
        kotlinx.coroutines.flow.combine(phone.installed, phone.selected) { _, _ -> }.collect { refreshActiveAi() }
      }
    }

    // Sample data is only added automatically for the first guest launch; signed-in accounts start empty.
    if (_currentUser.value.id == GUEST_USER_ID) {
      viewModelScope.launch { repository.seedSampleUniversityData(GUEST_USER_ID) }
    }

    // Start tracking changes for phone-PC sync before anything is edited.
    viewModelScope.launch { runCatching { platform.syncEngine.prepare() } }
    refreshQuizSources()

    // Data from the other device: new profiles, and on the first sync open the same account as there.
    viewModelScope.launch {
      SyncEvents.results.collect { result ->
        if (result.accountsChanged) _savedAccounts.value = loadRegisteredAccounts()
        if (result.applied > 0) refreshQuizSources()
        val peerUser = result.peerActiveUserId?.let { id -> _savedAccounts.value.find { it.id == id } }
        if (result.firstSync && peerUser != null && _currentUser.value.id == GUEST_USER_ID) {
          activateUser(peerUser.copy(isLoggedIn = true, lastLoginTime = System.currentTimeMillis()), "Showing ${peerUser.name}'s data from ${result.peerName}")
        } else if (result.applied > 0) {
          _snackbarMessage.emit("Synced ${result.applied} change${if (result.applied == 1) "" else "s"} from ${result.peerName}")
        }
      }
    }

    // An alarm may already be ringing when the app opens (launched from its notification).
    alarms.ringingSessionId?.let { showRingingAlarm(it) }

    // Alarms that ring, or get dismissed/snoozed from the notification, while the app is open.
    viewModelScope.launch {
      AlarmEventBus.events.collect { event ->
        when (event) {
          is AlarmEvent.Triggered -> {
            _ringingAlarmSession.value = sessions.value.find { it.id == event.sessionId }
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
          }
          is AlarmEvent.Dismissed, is AlarmEvent.Snoozed -> _ringingAlarmSession.value = null
        }
      }
    }

    // Keep AlarmManager in sync with the active account's sessions: schedule enabled ones and
    // cancel alarms for sessions that were deleted, disabled, or belong to a different account.
    viewModelScope.launch(Dispatchers.Default) {
      var scheduledIds = emptySet<Long>()
      sessions
        .map { list -> list.filter { it.isAlarmEnabled } }
        .distinctUntilChanged()
        .collect { enabled ->
          val enabledIds = enabled.mapTo(HashSet()) { it.id }
          (scheduledIds - enabledIds).forEach { alarms.cancel(it) }
          enabled.forEach { alarms.schedule(it) }
          scheduledIds = enabledIds
        }
    }
  }

  // --- AI settings ---

  fun setAiSource(source: AiSource) {
    _aiSource.value = source
    repository.ai.source = source
    aiPrefs.edit().putString("ai_source", source.name).apply()
    refreshActiveAi()
  }

  /** Saves the PC address (e.g. "192.168.1.60") and model, then checks the connection. */
  fun setPcConfig(url: String, model: String) {
    repository.ai.pc.baseUrl = url
    repository.ai.pc.preferredModel = model.trim()
    _pcUrl.value = repository.ai.pc.baseUrl
    _pcModel.value = model.trim()
    aiPrefs.edit().putString("pc_ollama_url", _pcUrl.value).putString("pc_ollama_model", _pcModel.value).apply()
    checkPc()
  }

  fun checkPc() {
    viewModelScope.launch {
      _isCheckingPc.value = true
      val status = repository.ai.pc.status(forceRefresh = true)
      _pcModels.value = status.models
      _pcStatus.value = when {
        repository.ai.pc.baseUrl.isBlank() -> "Enter your PC's address, or tap Find."
        !status.reachable -> "Can't reach ${repository.ai.pc.baseUrl} (${status.error}). Is Ollama running with OLLAMA_HOST=0.0.0.0, and is the phone on the same Wi-Fi?"
        status.models.isEmpty() -> "Connected, but no models are installed. On the PC run: ollama pull qwen3:8b"
        else -> "Connected. Using ${repository.ai.pc.chosenModel()}."
      }
      _isCheckingPc.value = false
      refreshActiveAi()
    }
  }

  /** Scans the Wi-Fi network for a PC running Ollama. */
  fun findPc() {
    viewModelScope.launch {
      _isCheckingPc.value = true
      _pcStatus.value = "Looking for Ollama on your Wi-Fi..."
      val found = repository.ai.pc.findOnNetwork(platform.localSubnetPrefix())
      _isCheckingPc.value = false
      if (found == null) {
        _pcStatus.value = "No PC found. Start Ollama with OLLAMA_HOST=0.0.0.0 and allow it through the firewall, or type the PC's address."
      } else {
        setPcConfig(found, _pcModel.value)
      }
    }
  }

  fun downloadPhoneModel(model: PhoneModel) {
    val phone = repository.ai.phone ?: return
    val ram = phone.deviceRamGb()
    if (ram in 1 until model.minRamGb) {
      snackbar("${model.displayName} needs about ${model.minRamGb} GB of RAM; this phone has $ram GB. It may not load.")
    }
    phone.startDownload(model)
  }

  fun cancelPhoneDownload() = repository.ai.phone?.cancelDownload()

  fun selectPhoneModel(model: PhoneModel) {
    repository.ai.phone?.select(model)
    refreshActiveAi()
  }

  fun deletePhoneModel(model: PhoneModel) {
    viewModelScope.launch {
      repository.ai.phone?.delete(model)
      snackbar("Deleted ${model.displayName}")
      refreshActiveAi()
    }
  }

  private fun applyCloudConfig() {
    repository.ai.cloud.provider = _cloudProvider.value
    repository.ai.cloud.customBaseUrl = _cloudBaseUrl.value
    repository.ai.cloud.apiKey = _cloudKey.value
    repository.ai.cloud.model = _cloudModel.value
    // Every other saved key too: each job uses the key best at it (see StudyAi.cloudsFor).
    repository.ai.setOtherKeys(CloudProvider.entries.filter { it != _cloudProvider.value }.mapNotNull { p ->
      val key = keyPrefs.getString("key_${p.name}", null)?.takeIf { it.isNotBlank() } ?: return@mapNotNull null
      StudyAi.CloudKey(p, key, keyPrefs.getString("model_${p.name}", "") ?: "", if (p == CloudProvider.CUSTOM) keyPrefs.getString("custom_base_url", "") ?: "" else "")
    })
    repository.ai.jobProvider = _jobProviders.value
    _keyJobs.value = repository.ai.jobsByKey()
  }

  /** Switches to [provider], with the key and model saved for it on this device (if any). */
  fun selectCloudProvider(provider: CloudProvider) {
    _cloudProvider.value = provider
    _cloudKey.value = keyPrefs.getString("key_${provider.name}", "") ?: ""
    _cloudModel.value = keyPrefs.getString("model_${provider.name}", "") ?: ""
    _cloudModels.value = emptyList()
    _cloudStatus.value = null
    keyPrefs.edit { putString("provider", provider.name); putLong(KEYS_UPDATED_AT, System.currentTimeMillis()) }
    applyCloudConfig()
    checkCloud()
  }

  /** This device's cloud keys for the paired device, or null when none were ever set here. */
  private fun exportCloudKeys(): JSONObject? {
    val keys = JSONObject(); val models = JSONObject()
    CloudProvider.entries.forEach { p ->
      keyPrefs.getString("key_${p.name}", null)?.takeIf { it.isNotBlank() }?.let { keys.put(p.name, it) }
      keyPrefs.getString("model_${p.name}", null)?.takeIf { it.isNotBlank() }?.let { models.put(p.name, it) }
    }
    // Keys saved before sharing existed have no time; they count as the oldest change.
    val updatedAt = keyPrefs.getLong(KEYS_UPDATED_AT, if (keys.length() > 0) 1L else 0L)
    if (updatedAt == 0L) return null
    return JSONObject()
      .put("updatedAt", updatedAt)
      .put("provider", _cloudProvider.value.name)
      .put("keys", keys)
      .put("models", models)
      .put("customBaseUrl", keyPrefs.getString("custom_base_url", "") ?: "")
  }

  /** Takes the paired device's keys when they changed more recently than this device's. */
  private fun importCloudKeys(incoming: JSONObject): Boolean {
    val theirs = incoming.optLong("updatedAt", 0L)
    val mine = keyPrefs.getLong(KEYS_UPDATED_AT, if (_providersWithKeys.value.isNotEmpty()) 1L else 0L)
    if (theirs <= mine) return false
    val keys = incoming.optJSONObject("keys") ?: JSONObject()
    val models = incoming.optJSONObject("models") ?: JSONObject()
    val provider = CloudProvider.byName(incoming.optString("provider"))
    keyPrefs.edit {
      CloudProvider.entries.forEach { p ->
        putString("key_${p.name}", keys.optString(p.name, ""))
        putString("model_${p.name}", models.optString(p.name, ""))
      }
      putString("provider", provider.name)
      putString("custom_base_url", incoming.optString("customBaseUrl", ""))
      putLong(KEYS_UPDATED_AT, theirs)
    }
    _cloudProvider.value = provider
    _cloudKey.value = keys.optString(provider.name, "")
    _cloudModel.value = models.optString(provider.name, "")
    _cloudBaseUrl.value = incoming.optString("customBaseUrl", "")
    _providersWithKeys.value = CloudProvider.entries.filter { keys.optString(it.name, "").isNotBlank() }.toSet()
    applyCloudConfig()
    checkCloud()
    return true
  }

  /** Saves the key and model for the chosen provider (and its address, for "Other"), then checks the key. */
  fun setCloudConfig(apiKey: String, model: String, baseUrl: String) {
    val provider = _cloudProvider.value
    _cloudKey.value = apiKey.trim()
    _cloudModel.value = model.trim()
    if (provider == CloudProvider.CUSTOM) _cloudBaseUrl.value = baseUrl.trim()
    keyPrefs.edit {
      putString("provider", provider.name)
      putString("key_${provider.name}", _cloudKey.value)
      putString("model_${provider.name}", _cloudModel.value)
      if (provider == CloudProvider.CUSTOM) putString("custom_base_url", _cloudBaseUrl.value)
      putLong(KEYS_UPDATED_AT, System.currentTimeMillis())
    }
    _providersWithKeys.value = if (_cloudKey.value.isBlank()) _providersWithKeys.value - provider else _providersWithKeys.value + provider
    applyCloudConfig()
    checkCloud()
  }

  /** Deletes the chosen provider's key from this device. */
  fun removeCloudKey() = setCloudConfig("", _cloudModel.value, _cloudBaseUrl.value)

  fun checkCloud() {
    viewModelScope.launch {
      if (_cloudKey.value.isBlank()) {
        _cloudStatus.value = null
        _cloudModels.value = emptyList()
        refreshActiveAi()
        return@launch
      }
      _isCheckingCloud.value = true
      val status = repository.ai.cloud.status()
      _cloudModels.value = status.models
      _cloudStatus.value = if (status.ok) {
        "Connected to ${_cloudProvider.value.label}. Using ${repository.ai.cloud.chosenModel()} · ${repository.ai.cloud.requestsToday} requests today"
      } else {
        "Can't use the cloud model: ${status.error}"
      }
      _isCheckingCloud.value = false
      refreshActiveAi()
    }
  }

  fun refreshActiveAi() {
    viewModelScope.launch {
      _activeAiLabel.value = repository.ai.backend()?.label ?: "Built-in answers"
    }
  }

  // --- Account Actions ---
  fun deleteUser(userId: String) {
    viewModelScope.launch {
      val userToDelete = _savedAccounts.value.find { it.id == userId }
      val userName = userToDelete?.name ?: "Student"
      val remainingAccounts = _savedAccounts.value.filter { it.id != userId }
      _savedAccounts.value = remainingAccounts
      saveRegisteredAccounts(remainingAccounts)

      passwords.remove(userId)
      repository.deleteUserData(userId)

      // If deleted account is the active account
      if (_currentUser.value.id == userId) {
        if (remainingAccounts.isNotEmpty()) {
          val nextUser = remainingAccounts.first()
          switchAccount(nextUser)
          _snackbarMessage.emit("Deleted $userName")
        } else {
          val guest = createGuestProfile()
          saveUser(guest, isLoggedIn = false)
          authPrefs.edit().remove("remembered_email").apply()
          _currentUser.value = guest
          _isAuthenticated.value = false
          _snackbarMessage.emit("Deleted $userName. You're now in guest mode.")
        }
      } else {
        _snackbarMessage.emit("Deleted $userName")
      }
    }
  }

  fun syncCloudData() {
    viewModelScope.launch {
      val user = _currentUser.value
      if (!repository.isCloudUser(user.id)) {
        _snackbarMessage.emit("Cloud sync needs Google sign-in. Email profiles are stored on this device only.")
        return@launch
      }
      _snackbarMessage.emit("Syncing with the cloud...")
      repository.saveUserProfile(user)
      val ok = repository.pullUserDataFromFirestore(user.id)
      _snackbarMessage.emit(if (ok) "Cloud sync complete." else "Couldn't reach the cloud. Your data is still saved on this device.")
    }
  }

  /**
   * Switches to a saved profile. Google profiles switch directly while their Firebase session is
   * still signed in; everything else goes to the sign-in screen with the email filled in.
   */
  fun switchAccount(user: UserProfile) {
    if (user.id == GUEST_USER_ID) {
      loginAsGuest()
      return
    }
    if (!repository.isCloudUser(user.id)) {
      _loginPrefillEmail.value = user.email
      _currentScreen.value = Screen.Login
      snackbar("Enter the password for ${user.email}")
      return
    }
    activateUser(user.copy(isLoggedIn = true, lastLoginTime = System.currentTimeMillis()), "Switched to ${user.name}")
  }

  private fun activateUser(user: UserProfile, message: String) {
    saveUser(user, isLoggedIn = true)
    _currentUser.value = user
    _isAuthenticated.value = true
    _currentScreen.value = Screen.Dashboard
    _loginPrefillEmail.value = null
    viewModelScope.launch {
      if (repository.isCloudUser(user.id)) repository.pullUserDataFromFirestore(user.id)
      _snackbarMessage.emit(message)
    }
  }

  private val _loginPrefillEmail = MutableStateFlow<String?>(null)

  private fun snackbar(message: String) {
    viewModelScope.launch { _snackbarMessage.emit(message) }
  }

  /** Why the last [login] or [register] call failed, for showing next to the form. */
  var lastAuthError: String? = null
    private set

  private fun authFailed(message: String): Boolean {
    lastAuthError = message
    return false
  }

  /**
   * Signs in to an on-device email profile. A new email creates the profile and stores its
   * password; afterwards the same password is required.
   */
  fun login(email: String, pass: String, rememberMe: Boolean = true): Boolean {
    lastAuthError = null
    val cleanEmail = email.trim()
    if (!isValidEmail(cleanEmail)) {
      return authFailed("Enter a valid email address.")
    }
    if (pass.length < MIN_PASSWORD_LENGTH) {
      return authFailed("Password must be at least $MIN_PASSWORD_LENGTH characters.")
    }

    val existing = _savedAccounts.value.find { it.email.equals(cleanEmail, ignoreCase = true) }
    if (existing != null) {
      if (existing.authProvider == GOOGLE_PROVIDER && !passwords.hasPassword(existing.id)) {
        return authFailed("This profile uses Google sign-in.")
      }
      if (passwords.hasPassword(existing.id)) {
        if (!passwords.verify(existing.id, pass)) {
          return authFailed("Incorrect password for $cleanEmail.")
        }
      } else {
        // Profile created before passwords were stored: the first sign-in sets it.
        passwords.setPassword(existing.id, pass)
      }
      activateUser(
        existing.copy(isLoggedIn = true, rememberMe = rememberMe, lastLoginTime = System.currentTimeMillis()),
        "Signed in as ${existing.name}"
      )
      return true
    }

    val name = cleanEmail.substringBefore("@").split('.', '_', '-', ' ')
      .filter { it.isNotBlank() }
      .joinToString(" ") { it.replaceFirstChar { c -> c.uppercase() } }
      .ifBlank { "Student" }
    val newUser = UserProfile(
      id = localUserId(cleanEmail),
      name = name,
      email = cleanEmail,
      role = "Student",
      university = "",
      studentId = "",
      avatarInitials = initialsOf(name),
      isLoggedIn = true,
      authProvider = EMAIL_PROVIDER,
      rememberMe = rememberMe,
      lastLoginTime = System.currentTimeMillis()
    )
    passwords.setPassword(newUser.id, pass)
    activateUser(newUser, "Created a profile for $cleanEmail")
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
    lastAuthError = null
    if (name.isBlank()) {
      return authFailed("Enter your name.")
    }
    val cleanEmail = email.trim()
    if (!isValidEmail(cleanEmail)) {
      return authFailed("Enter a valid email address.")
    }
    if (pass.length < MIN_PASSWORD_LENGTH) {
      return authFailed("Password must be at least $MIN_PASSWORD_LENGTH characters.")
    }
    if (_savedAccounts.value.any { it.email.equals(cleanEmail, ignoreCase = true) }) {
      return authFailed("A profile for $cleanEmail already exists. Sign in instead.")
    }
    val newUser = UserProfile(
      id = localUserId(cleanEmail),
      name = name.trim(),
      email = cleanEmail,
      role = "Student",
      university = university.trim(),
      studentId = studentId.trim(),
      avatarInitials = initialsOf(name),
      isLoggedIn = true,
      authProvider = EMAIL_PROVIDER,
      rememberMe = rememberMe,
      lastLoginTime = System.currentTimeMillis()
    )
    passwords.setPassword(newUser.id, pass)
    activateUser(newUser, "Welcome, ${newUser.name}")
    return true
  }

  private fun localUserId(email: String) = "student_" + email.lowercase().replace(Regex("[^a-z0-9]"), "_")

  private fun initialsOf(name: String): String =
    name.trim().split(Regex("\\s+")).mapNotNull { it.firstOrNull()?.uppercaseChar() }.take(2).joinToString("").ifBlank { "ST" }

  fun signInWithGoogle(
    idToken: String,
    displayName: String?,
    email: String?,
    onComplete: (Boolean, String?) -> Unit
  ) {
    viewModelScope.launch {
      val cloudUser = try {
        platform.cloud.signInWithGoogleIdToken(idToken)
      } catch (e: Exception) {
        com.example.platform.Log.e("StudyPilotVM", "Cloud sign-in failed", e)
        onComplete(false, e.message)
        return@launch
      }
      val studentEmail = email?.takeIf { it.isNotBlank() } ?: cloudUser.email?.takeIf { it.isNotBlank() } ?: ""
      val rawName = displayName?.takeIf { it.isNotBlank() } ?: cloudUser.displayName?.takeIf { it.isNotBlank() }
      val studentName = rawName ?: studentEmail.substringBefore("@").ifBlank { "Google User" }
      val userId = cloudUser.id
      val existing = _savedAccounts.value.find {
        it.id == userId || (studentEmail.isNotBlank() && it.email.equals(studentEmail, ignoreCase = true))
      }
      val user = UserProfile(
        id = userId,
        name = studentName,
        email = studentEmail,
        role = existing?.role ?: "Student",
        university = existing?.university.orEmpty(),
        studentId = existing?.studentId.orEmpty(),
        avatarInitials = initialsOf(studentName),
        isLoggedIn = true,
        authProvider = GOOGLE_PROVIDER,
        rememberMe = true,
        lastLoginTime = System.currentTimeMillis()
      )
      activateUser(user, "Signed in as ${user.name}")
      onComplete(true, null)
    }
  }

  fun loginAsGuest() {
    val guestUser = createGuestProfile()
    saveUser(guestUser, isLoggedIn = false)
    _currentUser.value = guestUser
    _isAuthenticated.value = false
    _currentScreen.value = Screen.Dashboard
    snackbar("Using guest mode")
  }

  fun logout() {
    try {
      platform.cloud.signOut()
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
    snackbar("Signed out")
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
      _snackbarMessage.emit("Profile saved")
    }
  }

  // Navigation — always opens cleanly on Dashboard (Guest Mode or stored active account)
  private val _currentScreen = MutableStateFlow<Screen>(Screen.Dashboard)
  val currentScreen: StateFlow<Screen> = _currentScreen.asStateFlow()

  fun navigateTo(screen: Screen) {
    _currentScreen.value = screen
    // Keep quiz material current: tutor chats and imports since last time become topics.
    if (screen == Screen.WeekendQuizzes || screen == Screen.MyTopics || screen == Screen.Dashboard) refreshQuizSources()
  }

  private fun refreshQuizSources() {
    val userId = _currentUser.value.id
    viewModelScope.launch { runCatching { repository.quizSources.refresh(userId) } }
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
        text = "Ask a question about any topic, or pick a subject above to focus the tutor.",
        modelName = "",
        isWelcome = true
      )
    )
  )
  val chatMessages: StateFlow<List<ChatMessage>> = _chatMessages.asStateFlow()

  private fun tutorInstruction(subject: Subject?): String = buildString {
    append("You are StudyPilot, a tutor for university students")
    if (subject != null) append(", helping with ${subject.name} (${subject.code})")
    append(". Teach the topic the student asked about. By default give a complete explanation: ")
    append("start with a one or two sentence direct answer, then explain how it works, give a concrete worked example, ")
    append("and note common mistakes or exam points. Use short headings, bullet points and code blocks where they help. ")
    append("Even a one or two word message such as 'data structures' deserves this full explanation. ")
    append("Only be brief when the latest message asks for it (for example 'in short', 'one sentence', 'briefly'); ")
    append("a length request applies to that message only, never to later questions. ")
    append("Stay accurate: if you are unsure, say so instead of guessing. ")
    append("For follow-up buttons, do only what they ask: 'Example in Code' and 'Another Example in Code' give code plus a 3-bullet walkthrough; ")
    append("'Summarize' gives a short bulleted summary; 'Another Example' gives one new worked example; 'Explain Example' walks through the previous example step by step. ")
    append("End every answer with one line listing related subtopics: [RELATED_TOPICS: topic 1, topic 2, topic 3, topic 4, topic 5]")
  }

  private val _systemInstruction = MutableStateFlow(tutorInstruction(null))
  val systemInstruction: StateFlow<String> = _systemInstruction.asStateFlow()

  private val _isChatLoading = MutableStateFlow(false)
  val isChatLoading: StateFlow<Boolean> = _isChatLoading.asStateFlow()

  fun updateSystemInstruction(instruction: String) {
    _systemInstruction.value = instruction
  }

  fun selectTutorSubject(subject: Subject?) {
    _selectedTutorSubject.value = subject
    val newInstruction = tutorInstruction(subject)
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
            "Tutoring for **${subject.name}** (${subject.code}). What are you working on?"
          } else {
            "Ask a question about any topic, or pick a subject above to focus the tutor."
          },
          modelName = "",
          relatedTopics = initialLinkTopics,
          isWelcome = true
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
          "New chat for **${subject.name}** (${subject.code}). Ask a question or pick a suggested topic."
        } else {
          "New chat. What are you studying?"
        },
        modelName = "",
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
      _snackbarMessage.emit("Chat deleted")
    }
  }

  fun clearAllTutorHistory() {
    viewModelScope.launch {
      repository.clearTutorHistory(_currentUser.value.id)
      startNewTutorChat()
      _snackbarMessage.emit("Tutor history cleared")
    }
  }

  fun clearChat() {
    startNewTutorChat(_selectedTutorSubject.value)
  }

  /**
   * Sends a message to the tutor. [replyingTo] is the id of an earlier answer when the user tapped a
   * follow-up button on it ("Explain example", "Summarize", ...): the reply is then generated from the
   * conversation up to that answer only, so it is about that question rather than the latest one.
   */
  /**
   * "College notes": the tutor answers only from the student's course files and course pages, citing
   * file and page, and says so when they don't cover the question.
   */
  private val _collegeNotes = MutableStateFlow(aiPrefs.getBoolean("college_notes", false))
  val collegeNotes: StateFlow<Boolean> = _collegeNotes.asStateFlow()

  fun setCollegeNotes(on: Boolean) {
    _collegeNotes.value = on
    aiPrefs.edit { putBoolean("college_notes", on) }
  }

  /** The answer when "College notes" can't ask a model: the best passages, quoted with their sources. */
  private fun collegeNotesWithoutModel(query: String, passages: List<com.example.data.ai.rag.KnowledgePassage>, subject: String?): com.example.data.remote.ChatResponse {
    if (passages.isEmpty()) {
      return com.example.data.remote.ChatResponse(
        text = "Your course material doesn't cover this" + (subject?.let { " for $it" } ?: "") +
          ". Try other words, or import your course files in Settings > Connections.",
        modelName = "College notes"
      )
    }
    val text = buildString {
      append("From your course material:\n\n")
      passages.take(3).forEachIndexed { i, p ->
        append("**[${i + 1}] ${p.sourceTitle}**\n")
        append("> ").append(p.text.take(700).replace("\n", "\n> ")).append("\n\n")
      }
    }
    return com.example.data.remote.ChatResponse(
      text = text.trim(),
      modelName = "College notes",
      groundingSources = passages.take(3).map { com.example.data.model.GroundingSource(it.sourceTitle, it.url) }
    )
  }

  fun sendChatMessage(text: String, replyingTo: String? = null) {
    val trimmed = text.trim()
    if (trimmed.isBlank() || _isChatLoading.value) return

    val userMessage = ChatMessage(role = "user", text = trimmed)
    _chatMessages.value = _chatMessages.value + userMessage
    val currentHistory = followUpContext(_chatMessages.value.dropLast(1), replyingTo) + userMessage

    viewModelScope.launch {
      _isChatLoading.value = true
      val replyId = java.util.UUID.randomUUID().toString()
      try {
        val activeSub = _selectedTutorSubject.value
        val sessionId = _activeSessionId.value ?: repository.createTutorSession(
          subjectId = activeSub?.id,
          subjectName = activeSub?.name ?: "General",
          title = trimmed.take(45).let { if (trimmed.length > 45) "$it..." else it },
          previewSnippet = trimmed,
          modelUsed = _activeAiLabel.value,
          searchGroundingUsed = false,
          userId = _currentUser.value.id
        ).also { _activeSessionId.value = it }

        repository.saveTutorMessage(sessionId = sessionId, role = "user", text = trimmed, modelName = "", searchGroundingUsed = false)

        val chatsSnapshot = importedChats.value
        val conceptsSnapshot = extractedConcepts.value
        // With a model, answers come from it, grounded in the student's own material (RAG).
        // Without one, the rule-based answer from imported chats is used when it covers the question.
        val hasModel = repository.ai.backend() != null
        val collegeMode = _collegeNotes.value
        val knowledge = if (collegeMode) {
          runCatching {
            val q = followUpContext(_chatMessages.value, replyingTo).takeLast(2).joinToString(" ") { it.text.take(300) } + " " + trimmed
            repository.searchCourseMaterial(_currentUser.value.id, q, activeSub?.name, limit = 6)
          }.getOrDefault(emptyList())
        } else if (hasModel) {
          runCatching {
            val sessionTitle = repository.getTutorSessionsForUser(_currentUser.value.id).first().firstOrNull { it.id == sessionId }?.title
            repository.searchKnowledge(_currentUser.value.id, followUpContext(_chatMessages.value, replyingTo).takeLast(4).joinToString(" ") { it.text.take(300) } + " " + trimmed, sessionTitle)
          }.getOrDefault(emptyList())
        } else emptyList()
        val linkedChatResponse = if (collegeMode) {
          // No model, or nothing in the course material: answer here instead of guessing.
          if (!hasModel || knowledge.isEmpty()) collegeNotesWithoutModel(trimmed, knowledge, activeSub?.name) else null
        } else if (hasModel) null else withContext(Dispatchers.Default) {
          com.example.data.ai.ChatHistoryAssistant.answerFromLinkedChats(
            query = trimmed,
            history = currentHistory,
            importedChats = chatsSnapshot,
            extractedConcepts = conceptsSnapshot,
            selectedSubject = activeSub
          )
        }

        // Show the reply as it's generated; local models can take a while to finish.
        var lastPaint = 0L
        val onPartial: (String) -> Unit = { partial ->
          val now = System.currentTimeMillis()
          if (now - lastPaint > 80) {
            lastPaint = now
            showReply(ChatMessage(id = replyId, role = "model", text = partial, modelName = _activeAiLabel.value))
          }
        }

        val response = linkedChatResponse ?: repository.chatWithTutor(
          history = currentHistory,
          systemInstruction = _systemInstruction.value,
          subjectContext = activeSub?.let { "${it.name} (${it.code})" },
          onPartial = onPartial,
          knowledge = knowledge,
          strict = collegeMode
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
          cleaned to resolvedTopics
        }

        showReply(
          ChatMessage(
            id = replyId,
            role = "model",
            text = cleanText,
            modelName = response.modelName,
            groundingSources = response.groundingSources,
            searchQueries = response.searchQueries,
            isError = response.isError,
            relatedTopics = finalTopics
          )
        )

        val sourcesJson = if (response.groundingSources.isEmpty()) "" else JSONArray().apply {
          response.groundingSources.forEach { put(JSONObject().put("title", it.title).put("url", it.url)) }
        }.toString()
        repository.saveTutorMessage(
          sessionId = sessionId,
          role = "model",
          text = cleanText,
          modelName = response.modelName,
          searchGroundingUsed = false,
          groundingSourcesJson = sourcesJson,
          isError = response.isError
        )
      } catch (e: Exception) {
        com.example.platform.Log.e("StudyPilotVM", "Tutor reply failed", e)
        showReply(
          ChatMessage(
            id = replyId,
            role = "model",
            text = "Something went wrong answering that (${e.message ?: e.javaClass.simpleName}). Try again.",
            isError = true
          )
        )
      } finally {
        _isChatLoading.value = false
        refreshActiveAi()
      }
    }
  }

  /** The conversation up to and including [answerId], or all of it when there's no such answer. */
  fun followUpContext(history: List<ChatMessage>, answerId: String?): List<ChatMessage> {
    if (answerId == null) return history
    val index = history.indexOfFirst { it.id == answerId }
    return if (index < 0) history else history.subList(0, index + 1)
  }

  /** Adds the reply bubble, or updates it in place while it streams. */
  private fun showReply(message: ChatMessage) {
    val current = _chatMessages.value
    val index = current.indexOfFirst { it.id == message.id }
    _chatMessages.value = if (index < 0) current + message else current.toMutableList().also { it[index] = message }
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
      // Connected course sites won't add it back on their next import.
      val skipped = skippedCourses() + subject.name.lowercase()
      connectPrefs.edit { putString("skipped_courses", skipped.joinToString("\n")) }
      repository.deleteSubject(subject)
      _snackbarMessage.emit("Subject removed")
    }
  }

  // --- Session Actions ---
  /**
   * Session saves run one after another, in the order they were made. Two quick edits (dragging one
   * edge, then the other) could otherwise reach the database out of order and leave the older one.
   */
  private val sessionWrites = kotlinx.coroutines.sync.Mutex()

  fun addSession(session: TimetableSession) {
    viewModelScope.launch { sessionWrites.withLock {
      val sessionWithUser = if (session.userId.isBlank()) session.copy(userId = _currentUser.value.id) else session
      val insertedId = repository.addSession(sessionWithUser)
      val savedSession = if (insertedId > 0L && sessionWithUser.id == 0L) {
        sessionWithUser.copy(id = insertedId)
      } else {
        sessionWithUser
      }
      if (savedSession.isAlarmEnabled) {
        alarms.schedule(savedSession)
      }
      val msg = if (savedSession.endTime.isBlank() && savedSession.isAlarmEnabled) {
        "Alarm set for ${formatTo12Hour(savedSession.startTime)}"
      } else {
        "Added ${formatTimeRange(savedSession.startTime, savedSession.endTime)}"
      }
      _snackbarMessage.emit(msg)
    } }
  }

  fun updateSessionStatus(session: TimetableSession, newStatus: String) {
    viewModelScope.launch { sessionWrites.withLock {
      val sessionWithUser = if (session.userId.isBlank()) session.copy(userId = _currentUser.value.id) else session
      repository.updateSession(sessionWithUser.copy(status = newStatus))
      _snackbarMessage.emit("Marked ${newStatus.lowercase()}")
    } }
  }

  fun deleteSession(id: Long) {
    viewModelScope.launch {
      alarms.cancel(id)
      repository.deleteSession(id, userId = _currentUser.value.id)
      _snackbarMessage.emit("Session deleted")
    }
  }

  private fun cancelAlarmsOn(date: String) {
    sessions.value.filter { it.date == date }.forEach { alarms.cancel(it.id) }
  }

  fun updateSession(session: TimetableSession) {
    viewModelScope.launch { sessionWrites.withLock {
      val sessionWithUser = if (session.userId.isBlank()) session.copy(userId = _currentUser.value.id) else session
      repository.updateSession(sessionWithUser)
      if (sessionWithUser.isAlarmEnabled) {
        alarms.schedule(sessionWithUser)
      } else {
        alarms.cancel(sessionWithUser.id)
      }
      _snackbarMessage.emit("Session updated")
    } }
  }

  fun toggleSessionAlarm(session: TimetableSession) {
    viewModelScope.launch { sessionWrites.withLock {
      val updated = session.copy(isAlarmEnabled = !session.isAlarmEnabled)
      repository.updateSession(updated)
      if (updated.isAlarmEnabled) {
        alarms.schedule(updated)
        _snackbarMessage.emit("Alarm on for ${formatTo12Hour(session.startTime)}")
      } else {
        alarms.cancel(updated.id)
        _snackbarMessage.emit("Alarm off")
      }
    } }
  }

  /** Rings the alarm for [session] right now, through the same service a scheduled alarm uses. */
  fun triggerTestAlarm(session: TimetableSession) {
    _ringingAlarmSession.value = session
    alarms.startRinging(session)
  }

  fun setQuickMobileAlarm(
    subjectName: String,
    topic: String,
    delayMinutes: Int = 1,
    customTime: String? = null
  ) {
    viewModelScope.launch {
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
        topic = topic.ifBlank { "Reminder" },
        date = java.text.SimpleDateFormat("yyyy-MM-dd", java.util.Locale.getDefault()).format(targetCal.time),
        startTime = startTimeStr,
        endTime = "",
        location = "",
        notes = "",
        recurringDays = "",
        reminderMinutes = 0,
        isAlarmEnabled = true,
        status = "SCHEDULED"
      )

      val id = repository.addSession(newSession)
      alarms.schedule(newSession.copy(id = id))

      val minutesAway = ((targetCal.timeInMillis - System.currentTimeMillis()) / 60_000L).coerceAtLeast(0)
      val inText = when {
        minutesAway < 1 -> "in under a minute"
        minutesAway < 60 -> "in $minutesAway min"
        else -> "in ${minutesAway / 60} h ${minutesAway % 60} min"
      }
      _snackbarMessage.emit("Alarm set for ${formatTo12Hour(startTimeStr)} ($inText)")
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

  /** Shows the in-app banner for an alarm that is already ringing (e.g. opened from its notification). */
  fun showRingingAlarm(sessionId: Long) {
    if (alarms.ringingSessionId != sessionId) return
    viewModelScope.launch {
      _ringingAlarmSession.value = repository.getSessionById(sessionId)
        ?: _ringingAlarmSession.value
        ?: TimetableSession(id = sessionId, subjectId = 0L, subjectName = "Alarm", topic = "Scheduled alarm", date = "", startTime = "", endTime = "")
    }
  }

  // --- Connected services ---

  /** Saved connections and the result of the last import from each. Tokens stay on this device. */
  data class Connections(
    val calendarFeeds: List<String> = emptyList(),
    val canvasUrl: String = "",
    val canvasConnected: Boolean = false,
    val moodleUrl: String = "",
    val moodleConnected: Boolean = false,
    val busy: Boolean = false,
    /** Source label -> what the last import did, or why it failed. */
    val results: Map<String, String> = emptyMap(),
    val lastImportAt: Long = 0L,
    /** "Reading files 12 of 140" while course files are being read. */
    val progress: String? = null,
    val classroomEnabled: Boolean = false
  ) {
    val hasAny get() = calendarFeeds.isNotEmpty() || canvasConnected || moodleConnected
  }

  private val connectPrefs = platform.prefs("studypilot_connections")

  /**
   * The PC connects to course sites, fetches, and keeps the original files; the phone gets the
   * results (subjects, topics, tasks, file lists, chosen material) through sync.
   */
  val connectsHere: Boolean get() = platform.courseFilesDir != null
  private val _connections = MutableStateFlow(loadConnections())
  val connections: StateFlow<Connections> = _connections.asStateFlow()

  private fun skippedCourses(): Set<String> =
    connectPrefs.getString("skipped_courses", "").orEmpty().lines().filter { it.isNotBlank() }.toSet()

  private fun loadConnections() = Connections(
    calendarFeeds = connectPrefs.getString("calendar_feeds", "").orEmpty().lines().filter { it.isNotBlank() },
    canvasUrl = connectPrefs.getString("canvas_url", "").orEmpty().let { if (it.isBlank()) it else com.example.data.connect.LmsHttp.siteRoot(it) },
    canvasConnected = !connectPrefs.getString("canvas_token", null).isNullOrBlank(),
    moodleUrl = connectPrefs.getString("moodle_url", "").orEmpty(),
    moodleConnected = !connectPrefs.getString("moodle_token", null).isNullOrBlank(),
    lastImportAt = connectPrefs.getLong("last_import_at", 0L),
    classroomEnabled = connectPrefs.getBoolean("classroom_enabled", false)
  )

  // --- Uploaded documents: read into knowledge, quizzed on demand ---

  /**
   * Adds a document (PDF, PowerPoint or Word) under [subjectName] and has the AI read it into topics
   * with study notes, which the tutor and quizzes use. Runs on this device; progress shows on the
   * document (Notes > subject > Files, and Quizzes).
   */
  fun uploadDocument(fileName: String, bytes: ByteArray, subjectName: String) {
    viewModelScope.launch {
      val uid = _currentUser.value.id
      val id = runCatching { repository.addUploadedDocument(uid, fileName, bytes, subjectName, platform.courseFilesDir?.let { java.io.File(it, "Uploads") }) }
        .getOrElse { _snackbarMessage.emit(it.message ?: "Couldn't add that file"); return@launch }
      _snackbarMessage.emit("Reading $fileName…")
      val ok = scanWithChosenReader(uid, id)
      _snackbarMessage.emit(if (ok) "$fileName added to ${subjectName.ifBlank { "General" }}. Make a quiz from it any time" else "Couldn't read $fileName; see its status in Notes")
    }
  }

  /** Reads a document again (after a failure, or to use a better AI). */
  fun rescanDocument(doc: com.example.data.model.CourseDocument) {
    viewModelScope.launch {
      repository.setUseForStudy(listOf(doc.id), true)
      scanWithChosenReader(_currentUser.value.id, doc.id)
    }
  }

  fun removeDocument(doc: com.example.data.model.CourseDocument) {
    viewModelScope.launch {
      repository.removeDocument(_currentUser.value.id, doc.id)
      _snackbarMessage.emit("${doc.title} removed")
    }
  }

  /** Reads a document into topics with the API keys best suited to it (see StudyAi.cloudsFor). */
  private suspend fun scanWithChosenReader(uid: String, id: Long): Boolean = repository.scanDocument(uid, id)

  /**
   * Documents whose reading was cut off (the app was closed part way) would show "reading" for ever:
   * the device that has the file starts them again.
   */
  private suspend fun resumeUnfinishedReads() {
    val uid = _currentUser.value.id
    val unfinished = runCatching { repository.courseDocuments(uid).first() }.getOrDefault(emptyList())
      .filter { it.quizStatus in listOf("queued", "reading", "topics") && it.localPath.isNotBlank() && java.io.File(it.localPath).isFile }
    for (doc in unfinished) runCatching { scanWithChosenReader(uid, doc.id) }
  }

  /** A quiz on one document's topics. */
  fun startDocumentQuiz(doc: com.example.data.model.CourseDocument) {
    viewModelScope.launch {
      val topics = repository.topicsOfDocument(_currentUser.value.id, doc.externalId)
      if (topics.isEmpty()) {
        _snackbarMessage.emit(if (doc.quizStatus.startsWith("failed")) "This file couldn't be read. Try reading it again." else "Still reading ${doc.title}. Try again in a moment.")
        return@launch
      }
      startWeekendQuiz(title = "${doc.title.substringBeforeLast('.')} quiz", onlyTopicIds = topics.map { it.id }.toSet())
    }
  }

  /** While a quiz is being made: what it's for and how far it got, for the loading screen. */
  data class QuizPrep(val title: String, val done: Int = 0, val total: Int = 0, val current: String = "")

  private val _quizPrep = MutableStateFlow<QuizPrep?>(null)
  val quizPrep: StateFlow<QuizPrep?> = _quizPrep.asStateFlow()
  private var quizPrepJob: Job? = null

  fun cancelQuizPrep() {
    quizPrepJob?.cancel()
    _quizPrep.value = null
  }

  /** Quizzes use the API key's model first (faster, better questions); the student can turn this off. */
  private val _quizCloudFirst = MutableStateFlow(aiPrefs.getBoolean("quiz_cloud_first", true))
  val quizCloudFirst: StateFlow<Boolean> = _quizCloudFirst.asStateFlow()

  fun setQuizCloudFirst(on: Boolean) {
    _quizCloudFirst.value = on
    aiPrefs.edit { putBoolean("quiz_cloud_first", on) }
    repository.ai.quizCloudFirst = on
  }

  // --- Storage: on the devices, or also in Google Drive (managed on the PC) ---

  /** "device" (phone and PC only) or "drive" (also backed up to the student's Google Drive). */
  private val _storageMode = MutableStateFlow(connectPrefs.getString("storage_mode", "device") ?: "device")
  val storageMode: StateFlow<String> = _storageMode.asStateFlow()

  private val _googleEmail = MutableStateFlow(platform.google?.email)
  val googleEmail: StateFlow<String?> = _googleEmail.asStateFlow()

  val canUseGoogle: Boolean get() = platform.google != null

  private val _lastDriveBackup = MutableStateFlow(connectPrefs.getLong("drive_last_backup", 0L))
  val lastDriveBackup: StateFlow<Long> = _lastDriveBackup.asStateFlow()

  /** Opens Google's sign-in in the browser (PC). The client comes from the student's Google Cloud project. */
  fun signInGoogle(clientId: String, clientSecret: String) {
    val google = platform.google ?: return
    viewModelScope.launch {
      _connections.value = _connections.value.copy(busy = true, progress = "Finish signing in in your browser…")
      val result = runCatching { google.signIn(clientId.trim(), clientSecret.trim()) }
      _connections.value = _connections.value.copy(busy = false, progress = null)
      result.onSuccess { mail ->
        _googleEmail.value = mail
        _snackbarMessage.emit("Signed in to Google as $mail")
        if (_storageMode.value == "drive") backupToDrive()
      }.onFailure { _snackbarMessage.emit(it.message ?: "Google sign-in failed") }
    }
  }

  fun signOutGoogle() {
    platform.google?.signOut()
    _googleEmail.value = null
  }

  fun setStorageMode(mode: String) {
    _storageMode.value = mode
    connectPrefs.edit { putString("storage_mode", mode) }
    if (mode == "drive" && _googleEmail.value != null) backupToDrive()
  }

  fun setClassroomEnabled(on: Boolean) {
    connectPrefs.edit { putBoolean("classroom_enabled", on) }
    _connections.value = _connections.value.copy(classroomEnabled = on)
    if (on) importConnections()
  }

  /**
   * Copies everything to the student's Google Drive: all app data as "StudyPilot data.json" (the same
   * format the phone-PC sync uses, so it restores exactly) and each course file into a folder per
   * subject. Only files that are new or changed since the last backup are uploaded.
   */
  fun backupToDrive(quiet: Boolean = false) {
    val google = platform.google ?: return
    if (_googleEmail.value == null) return
    viewModelScope.launch {
      val before = _connections.value.busy
      _connections.value = _connections.value.copy(busy = true, progress = "Backing up to Google Drive…")
      val result = runCatching {
        val drive = com.example.data.connect.DriveClient(token = { google.accessToken() })
        val root = drive.folder("StudyPilot", null)
        // 1. The data.
        val engine = platform.syncEngine
        engine.prepare()
        val seq = engine.currentSeq()
        val payload = org.json.JSONObject()
          .put("app", "StudyPilot").put("version", 1).put("savedAt", System.currentTimeMillis())
          .put("changes", engine.changesSince(0L, seq, excludeDevice = null))
          .put("accounts", engine.accountsPayload())
        val bytes = payload.toString().toByteArray()
        val known = connectPrefs.getString("drive_backup_id", null)
        val id = runCatching { drive.put("StudyPilot data.json", root, bytes, "application/json", known) }
          .getOrElse { drive.put("StudyPilot data.json", root, bytes, "application/json", null) } // the old copy was deleted
        connectPrefs.edit { putString("drive_backup_id", id) }
        // 2. The course files, in a folder per subject.
        val uploaded = org.json.JSONObject(connectPrefs.getString("drive_files", "{}") ?: "{}")
        val docs = courseDocuments.value.filter { it.localPath.isNotBlank() && java.io.File(it.localPath).isFile }
        val todo = docs.filter { uploaded.optJSONObject(it.externalId)?.optString("v") != it.remoteUpdatedAt }
        val folders = HashMap<String, String>()
        var sent = 0
        val subjectsRoot = drive.folder("Course files", root)
        todo.forEachIndexed { i, doc ->
          _connections.value = _connections.value.copy(progress = "Copying course files to Google Drive ${i + 1} of ${todo.size}")
          val folder = folders.getOrPut(doc.subjectName) { drive.folder(doc.subjectName, subjectsRoot) }
          val prev = uploaded.optJSONObject(doc.externalId)?.optString("id")?.takeIf { it.isNotBlank() }
          val fileBytes = java.io.File(doc.localPath).readBytes()
          val mime = com.example.data.connect.DriveClient.mimeFor(doc.fileType)
          val fileId = runCatching { drive.put(doc.title, folder, fileBytes, mime, prev) }.getOrElse { drive.put(doc.title, folder, fileBytes, mime, null) }
          uploaded.put(doc.externalId, org.json.JSONObject().put("id", fileId).put("v", doc.remoteUpdatedAt))
          connectPrefs.edit { putString("drive_files", uploaded.toString()) }
          sent++
        }
        sent
      }
      val now = System.currentTimeMillis()
      result.onSuccess {
        connectPrefs.edit { putLong("drive_last_backup", now) }
        _lastDriveBackup.value = now
      }
      _connections.value = _connections.value.copy(
        busy = before, progress = null,
        results = _connections.value.results + ("Google Drive" to result.fold(
          { n -> "Backed up your data" + if (n > 0) " and $n course file${if (n == 1) "" else "s"}" else "" },
          { "Couldn't back up: ${it.message}" }
        ))
      )
      if (!quiet) _snackbarMessage.emit(if (result.isSuccess) "Backed up to Google Drive" else "Google Drive backup failed; see Settings")
    }
  }

  /** Brings back everything from the Drive backup, merged with what's on this device. */
  fun restoreFromDrive() {
    val google = platform.google ?: return
    viewModelScope.launch {
      _connections.value = _connections.value.copy(busy = true, progress = "Restoring from Google Drive…")
      val result = runCatching {
        val drive = com.example.data.connect.DriveClient(token = { google.accessToken() })
        val root = drive.find("StudyPilot", null, folder = true) ?: throw com.example.data.connect.LmsException("There's no StudyPilot backup in this Google Drive yet.")
        val id = drive.find("StudyPilot data.json", root, folder = false) ?: throw com.example.data.connect.LmsException("There's no StudyPilot backup in this Google Drive yet.")
        val payload = org.json.JSONObject(String(drive.download(id)))
        val engine = platform.syncEngine
        engine.prepare()
        payload.optJSONObject("accounts")?.let { engine.mergeAccounts(it) }
        engine.apply(payload.optJSONArray("changes") ?: org.json.JSONArray(), fromDevice = "google-drive-backup")
      }
      _connections.value = _connections.value.copy(busy = false, progress = null)
      _snackbarMessage.emit(result.fold({ "Restored from Google Drive ($it changes)" }, { it.message ?: "Restore failed" }))
    }
  }

  /** Course files read into the app, for Notes and the tutor's "College notes". */
  @OptIn(ExperimentalCoroutinesApi::class)
  val courseDocuments: StateFlow<List<com.example.data.model.CourseDocument>> = _currentUser
    .flatMapLatest { user -> repository.courseDocuments(user.id) }
    .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

  /** Adds a calendar subscription link ("webcal://", "https://...ics") and imports it. */
  fun addCalendarFeed(url: String) {
    val clean = url.trim().replace(Regex("^webcals?://", RegexOption.IGNORE_CASE), "https://")
    if (clean.isBlank()) return
    val feeds = (_connections.value.calendarFeeds + clean).distinct()
    connectPrefs.edit { putString("calendar_feeds", feeds.joinToString("\n")) }
    _connections.value = _connections.value.copy(calendarFeeds = feeds)
    importConnections()
  }

  fun removeCalendarFeed(url: String) {
    val feeds = _connections.value.calendarFeeds - url
    connectPrefs.edit { putString("calendar_feeds", feeds.joinToString("\n")) }
    _connections.value = _connections.value.copy(calendarFeeds = feeds, results = _connections.value.results - feedLabel(url))
  }

  /** Saves a Canvas or Moodle address and token ("canvas" / "moodle") and imports; a blank token disconnects. */
  fun setLms(kind: String, url: String, token: String) {
    connectPrefs.edit {
      val root = if (kind == "canvas") com.example.data.connect.LmsHttp.siteRoot(url) else com.example.data.connect.LmsHttp.moodleRoot(url)
      putString("${kind}_url", if (url.isBlank()) "" else root)
      putString("${kind}_token", token.trim())
    }
    _connections.value = loadConnections().copy(results = _connections.value.results - kind.replaceFirstChar { it.uppercase() })
    if (token.isNotBlank()) importConnections()
  }

  private fun feedLabel(url: String) = "Calendar · " + (url.substringAfter("://").substringBefore('/').ifBlank { url }).take(40)

  /** Imports from every saved connection. [quiet] skips the snackbar (the automatic refresh). */
  fun importConnections(quiet: Boolean = false) {
    if (_connections.value.busy || !connectsHere) return
    viewModelScope.launch {
      _connections.value = _connections.value.copy(busy = true)
      val uid = _currentUser.value.id
      val today = com.example.ui.orbit.todayDate()
      val results = _connections.value.results.toMutableMap()
      val now = System.currentTimeMillis()
      for (url in _connections.value.calendarFeeds) {
        results[feedLabel(url)] = runCatching {
          val text = com.example.data.connect.LmsHttp.get(url)
          if (!text.contains("BEGIN:VCALENDAR")) throw com.example.data.connect.LmsException("That link isn't a calendar feed (.ics).")
          val events = com.example.data.connect.Ical.parse(text, now - 7 * 86_400_000L, now + 120 * 86_400_000L)
          repository.importCalendar(uid, url, events, today).toString()
        }.getOrElse { "Couldn't import: ${it.message}" }
      }
      val lms = listOfNotNull(
        connectPrefs.getString("canvas_token", null)?.takeIf { it.isNotBlank() }?.let { com.example.data.connect.CanvasSource(connectPrefs.getString("canvas_url", "").orEmpty(), it) },
        connectPrefs.getString("moodle_token", null)?.takeIf { it.isNotBlank() }?.let { com.example.data.connect.MoodleSource(connectPrefs.getString("moodle_url", "").orEmpty(), it) }
      )
      for (source in lms) {
        results[source.label] = runCatching { repository.importCourses(uid, source.courses(), today, skippedCourses()).toString() }
          .getOrElse { "Couldn't import: ${it.message}" }
      }
      val google = platform.google
      if (google != null && google.email != null && connectPrefs.getBoolean("classroom_enabled", false)) {
        results["Google Classroom"] = runCatching {
          repository.importCourses(uid, com.example.data.connect.ClassroomSource(google.accessToken()).courses(), today, skippedCourses()).toString()
        }.getOrElse { "Couldn't import: ${it.message}" }
      }
      connectPrefs.edit { putLong("last_import_at", now) }
      _connections.value = _connections.value.copy(busy = false, results = results, lastImportAt = now)
      if (_storageMode.value == "drive") backupToDrive(quiet = true)
      if (!quiet) _snackbarMessage.emit(if (results.values.any { it.startsWith("Couldn't") }) "Import finished with problems; see Settings > Connections" else "Imported from your connected services")
    }
  }

  /** Imports Google Classroom with an access token from Google sign-in (phone only). */
  fun importClassroom(accessToken: String) {
    viewModelScope.launch {
      _connections.value = _connections.value.copy(busy = true)
      val result = runCatching {
        repository.importCourses(_currentUser.value.id, com.example.data.connect.ClassroomSource(accessToken).courses(), com.example.ui.orbit.todayDate(), skippedCourses()).toString()
      }.getOrElse { "Couldn't import: ${it.message}" }
      _connections.value = _connections.value.copy(busy = false, results = _connections.value.results + ("Google Classroom" to result))
      _snackbarMessage.emit("Google Classroom: $result")
    }
  }

  /** Called when the app opens: refreshes connected services at most every 12 hours. */
  private fun refreshConnectionsIfDue() {
    val c = _connections.value
    if (connectsHere && (c.hasAny || c.classroomEnabled) && System.currentTimeMillis() - c.lastImportAt > 12 * 3_600_000L) importConnections(quiet = true)
    else if (connectsHere && _storageMode.value == "drive" && System.currentTimeMillis() - _lastDriveBackup.value > 12 * 3_600_000L) backupToDrive(quiet = true)
  }

  fun dismissAlarm() {
    _ringingAlarmSession.value?.let { alarms.announceStop(it, 0) }
    alarms.stopRinging()
    _ringingAlarmSession.value = null
  }

  fun snoozeAlarm(session: TimetableSession, minutes: Int = 5) {
    alarms.announceStop(session, minutes)
    alarms.stopRinging()
    _ringingAlarmSession.value = null
    alarms.snooze(session, minutes)
    snackbar("Snoozed for $minutes minutes")
  }

  /** The other device stopped or snoozed the alarm named [key]; do the same here if it's ringing. */
  fun stopAlarmFromOtherDevice(key: String, snoozeMinutes: Int) {
    val session = _ringingAlarmSession.value ?: return
    if (com.example.data.sync.alarmKey(session.subjectName, session.topic, session.startTime) != key) return
    alarms.stopRinging()
    _ringingAlarmSession.value = null
    if (snoozeMinutes > 0) {
      alarms.snooze(session, snoozeMinutes)
      snackbar("Snoozed on your other device for $snoozeMinutes minutes")
    } else snackbar("Alarm stopped on your other device")
  }

  fun createDayScheduleBatch(newSessions: List<TimetableSession>) {
    viewModelScope.launch {
      val uid = _currentUser.value.id
      repository.addSessions(newSessions.map { it.copy(userId = uid) })
      _snackbarMessage.emit("Added ${plural(newSessions.size, "session")}")
    }
  }

  fun replaceDayScheduleWithSessions(targetDate: String, newSessions: List<TimetableSession>) {
    viewModelScope.launch {
      val uid = _currentUser.value.id
      cancelAlarmsOn(targetDate)
      repository.replaceSessionsForDate(
        date = targetDate,
        sessions = newSessions.map { it.copy(id = 0L, date = targetDate, userId = uid) },
        userId = uid
      )
      _snackbarMessage.emit("Added ${plural(newSessions.size, "session")} to $targetDate")
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
      _snackbarMessage.emit("Added meal and sleep blocks to $targetDate")
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
        cancelAlarmsOn(targetDate)
        repository.replaceSessionsForDate(targetDate, copied, userId = uid)
      } else {
        repository.addSessions(copied)
      }
      _snackbarMessage.emit("Copied ${plural(sourceSessions.size, "session")} to $targetDate")
    }
  }

  fun saveScheduleAsFormat(
    formatName: String,
    description: String,
    sourceSessions: List<TimetableSession>
  ) {
    viewModelScope.launch {
      if (sourceSessions.isEmpty()) {
        _snackbarMessage.emit("There are no sessions to save as a template.")
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
      _snackbarMessage.emit("Saved template \"$formatName\"")
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
        _snackbarMessage.emit("This template is damaged and can't be applied.")
        return@launch
      }

      if (overwriteExisting) {
        cancelAlarmsOn(targetDate)
        repository.replaceSessionsForDate(targetDate, parsedSessions, userId = uid)
      } else {
        repository.addSessions(parsedSessions)
      }
      _snackbarMessage.emit("Applied \"${format.name}\" to $targetDate")
    }
  }

  fun deleteScheduleFormat(formatId: Long) {
    viewModelScope.launch {
      repository.deleteScheduleFormat(formatId)
      _snackbarMessage.emit("Template deleted")
    }
  }

  fun clearDaySchedule(targetDate: String) {
    viewModelScope.launch {
      cancelAlarmsOn(targetDate)
      repository.clearSessionsForDate(targetDate, userId = _currentUser.value.id)
      _snackbarMessage.emit("Cleared $targetDate")
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

        _snackbarMessage.emit("Saved \"$title\"")
      } finally {
        _isAiLoading.value = false
      }
    }
  }

  /** Files [topic] under [subject], or under "General" when null. */
  fun moveTopicToSubject(topic: StudyTopic, subject: Subject?) {
    viewModelScope.launch {
      repository.updateTopic(topic.copy(subjectId = subject?.id ?: 0L, subjectName = subject?.name ?: "General"))
      _snackbarMessage.emit("Moved \"${topic.title}\" to ${subject?.name ?: "General"}")
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
      _snackbarMessage.emit("Marked as ${newMastery.lowercase()}")
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

  /** Summarises a topic's notes (with a model when one is set up) and keeps the summary on the topic. */
  fun summarizeAndSaveTopic(topic: StudyTopic, onComplete: (StudyTopic) -> Unit) {
    viewModelScope.launch {
      _isAiLoading.value = true
      try {
        // The model gets clean sentences rather than "Q:/A:" labels and Markdown marks.
        val notes = com.example.data.ai.LocalAiEngine.plainNotes(topic.descriptionNotes).joinToString("\n")
        val summary = repository.summarizeMaterial(topic.title, notes)
        val updated = topic.copy(aiSummary = summary)
        repository.updateTopic(updated)
        onComplete(updated)
      } finally {
        _isAiLoading.value = false
      }
    }
  }

  fun deleteTopic(id: Long) {
    viewModelScope.launch {
      repository.deleteTopic(id, userId = _currentUser.value.id)
      _snackbarMessage.emit("Topic deleted")
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
  /** A quiz on one subject's topics; [subjectId] 0 is topics without a subject. */
  fun startSubjectQuiz(subjectId: Long, subjectName: String) =
    startWeekendQuiz(filterSubjectId = subjectId, title = "$subjectName quiz")

  /** Writing the next quiz's questions in the background, after a quiz is submitted. */
  private var lookAhead: Job? = null

  fun startWeekendQuiz(forceWeakOnly: Boolean = false, filterSubjectId: Long? = null, title: String? = null, onlyTopicIds: Set<Long>? = null) {
    lookAhead?.cancel() // the quiz being made now writes what it needs
    quizPrepJob?.cancel()
    quizPrepJob = viewModelScope.launch {
      _isAiLoading.value = true
      _quizPrep.value = QuizPrep(title ?: "Your quiz")
      try {
        // The quiz card's subject choice applies unless a subject was picked directly.
        val subjectId = filterSubjectId ?: _quizConfig.value.subjectId
        val generated = repository.assembleWeekendQuiz(
          config = _quizConfig.value,
          filterSubjectId = if (onlyTopicIds != null) null else subjectId,
          forceWeakOnly = forceWeakOnly,
          userId = _currentUser.value.id,
          onlyTopicIds = onlyTopicIds,
          onProgress = { done, total, topic -> _quizPrep.value = _quizPrep.value?.copy(done = done, total = total, current = topic) }
        )

        if (generated.questions.isEmpty()) {
          _snackbarMessage.emit(
            if (subjectId != null) "No topics in this subject match the quiz settings. Add a topic or change the filters."
            else "No topics match these quiz settings. Add a topic or change the filters."
          )
          return@launch
        }

        val subjectName = subjectId?.let { id -> subjects.value.firstOrNull { it.id == id }?.name ?: "General" }
        val quizTitle = title ?: when {
          forceWeakOnly && subjectName != null -> "$subjectName: struggling topics"
          forceWeakOnly -> "Quick Revision: Struggling Topics"
          subjectName != null -> "$subjectName quiz"
          else -> "Weekend Revision Quiz"
        }

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
        _quizPrep.value = null
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
        _snackbarMessage.emit("Score: ${attempt.scorePercentage}%")

        // Next time these topics should have new questions waiting.
        val perTopic = current.questions.groupingBy { it.topicId }.eachCount().values.maxOrNull()?.coerceIn(1, 5) ?: 2
        val config = _quizConfig.value
        val userId = _currentUser.value.id
        lookAhead?.cancel()
        lookAhead = viewModelScope.launch {
          runCatching { repository.prepareNextQuestions(current.testedTopics, perTopic, config, userId) }
        }
      } catch (e: Exception) {
        _activeQuizState.value = _activeQuizState.value.copy(isEvaluating = false)
        _snackbarMessage.emit("Couldn't score the quiz. Try submitting again.")
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
        _snackbarMessage.emit("Nothing to retry, every answer was right.")
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

  fun seedSampleData() {
    viewModelScope.launch {
      repository.seedSampleUniversityData(_currentUser.value.id, force = true)
      _snackbarMessage.emit("Sample data added")
    }
  }

  fun deleteAllSampleData() {
    viewModelScope.launch {
      val userId = _currentUser.value.id
      for (s in sessions.value) {
        alarms.cancel(s.id)
      }
      dismissAlarm()
      repository.deleteAllSampleData(userId)
      _snackbarMessage.emit("All data for this profile was deleted.")
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
        _snackbarMessage.emit("Paste a public share link (https://...).")
      }
      return
    }

    viewModelScope.launch {
      _isImportProcessing.value = true
      _importProcessingProgress.value = 0.05f
      _importProcessingStatusText.value = "Opening link..."
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
          _snackbarMessage.emit(fetched.errorMessage ?: "Couldn't read that conversation. Make sure the link is public.")
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

        _importProcessingStatusText.value = "Extracting concepts (${suggestion.suggestedName})..."

        val result = ChatKnowledgeExtractor.processAndExtract(
          title = effectiveTitle,
          subjectName = suggestion.suggestedName,
          rawText = fetched.conversationText,
          tags = tags,
          existingConcepts = extractedConcepts.value,
          aiDispatcher = { prompt ->
            repository.ai.prompt(prompt, "You extract study concepts from conversations.", 0.2, task = AiTask.BACKGROUND)
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
        _importProcessingStatusText.value = "Done. Review before saving."
        _snackbarMessage.emit("Found ${plural(result.concepts.size, "concept")} in ${fetched.platform} chat. Review before saving.")
      } catch (e: Exception) {
        _importProcessingStatusText.value = ""
        _snackbarMessage.emit("Import failed: ${e.localizedMessage ?: "unknown error"}")
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
        _snackbarMessage.emit("Import a chat from a link before saving.")
      }
      return
    }
    val user = _currentUser.value

    viewModelScope.launch {
      _isImportProcessing.value = true
      _importProcessingStatusText.value = "Saving..."
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
        _snackbarMessage.emit("Saved to ${resolvedSubject.name} (${plural(preview.concepts.size, "concept")})")
      } catch (e: Exception) {
        _snackbarMessage.emit("Couldn't save: ${e.localizedMessage ?: "unknown error"}")
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
      _snackbarMessage.emit("Moved \"${chat.title}\" to new subject $cleanName")
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
      _snackbarMessage.emit("Saved")
    }
  }

  fun deleteImportedChat(chatId: Long) {
    viewModelScope.launch {
      repository.deleteImportedChat(_currentUser.value.id, chatId)
      _snackbarMessage.emit("Chat and its concepts deleted")
    }
  }

  private val _checkingChatIds = MutableStateFlow<Set<Long>>(emptySet())
  /** Imported chats whose link is being checked for new messages right now. */
  val checkingChatIds: StateFlow<Set<Long>> = _checkingChatIds.asStateFlow()

  /**
   * Opens [chat]'s share link again and adds any messages that weren't there at import: they are
   * stored, their concepts extracted, and they become quiz material. [quiet] skips the "nothing new"
   * message (automatic checks).
   */
  fun checkChatForUpdates(chat: ImportedChat, quiet: Boolean = false) {
    if (chat.sourceUrl.isBlank() || chat.id in _checkingChatIds.value) return
    _checkingChatIds.value = _checkingChatIds.value + chat.id
    viewModelScope.launch {
      try {
        when (val check = repository.checkChatLink(chat)) {
          is StudyPilotRepository.ChatLinkCheck.Failed -> if (!quiet) _snackbarMessage.emit(check.message)
          StudyPilotRepository.ChatLinkCheck.NoChange -> if (!quiet) {
            _snackbarMessage.emit("No new messages in \"${chat.title}\". If you kept chatting, update the share link in ${chat.aiPlatform} first.")
          }
          is StudyPilotRepository.ChatLinkCheck.Updated -> {
            val result = ChatKnowledgeExtractor.processAndExtract(
              title = chat.title,
              subjectName = chat.subjectName,
              rawText = check.added,
              tags = chat.tags,
              existingConcepts = extractedConcepts.value,
              aiDispatcher = { prompt -> repository.ai.prompt(prompt, "You extract study concepts from conversations.", 0.2, task = AiTask.BACKGROUND) }
            )
            repository.applyChatUpdate(_currentUser.value.id, chat, check.fullText, result.concepts, result.messageCount)
            refreshQuizSources()
            _snackbarMessage.emit(
              "\"${chat.title}\" has ${plural(result.messageCount.coerceAtLeast(1), "new message")}" +
                if (result.concepts.isNotEmpty()) " and ${plural(result.concepts.size, "new concept")}" else ""
            )
          }
        }
      } catch (e: Exception) {
        if (!quiet) _snackbarMessage.emit("Couldn't check \"${chat.title}\": ${e.message ?: "unknown error"}")
      } finally {
        _checkingChatIds.value = _checkingChatIds.value - chat.id
      }
    }
  }

  /** When the chat library opens: check links not checked in the last few hours, one at a time. */
  fun checkStaleChatLinks() {
    viewModelScope.launch {
      val cutoff = System.currentTimeMillis() - 6 * 3_600_000L
      importedChats.value
        .filter { it.sourceUrl.isNotBlank() && (it.lastProcessedAt ?: it.importedAt) < cutoff }
        .forEach { chat ->
          checkChatForUpdates(chat, quiet = true)
          while (chat.id in _checkingChatIds.value) kotlinx.coroutines.delay(300)
        }
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
            repository.ai.prompt(prompt, "You extract study concepts from conversations.", 0.2, task = AiTask.BACKGROUND)
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
        _snackbarMessage.emit("Reprocessed: ${plural(result.concepts.size, "concept")}")
      } catch (e: Exception) {
        _snackbarMessage.emit("Reprocessing failed: ${e.localizedMessage ?: "unknown error"}")
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
        val questions = repository.ai.generateQuestionsForConcept(
          concept = concept,
          desiredCount = 5,
          difficulty = _quizConfig.value.difficulty
        )

        if (questions.isEmpty()) {
          _snackbarMessage.emit("Couldn't make questions for this concept.")
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

  private fun plural(count: Int, noun: String) = if (count == 1) "1 $noun" else "$count ${noun}s"
}
