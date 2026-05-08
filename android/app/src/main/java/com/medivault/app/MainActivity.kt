package com.medivault.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalContext
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.currentBackStackEntryAsState
import androidx.navigation.compose.rememberNavController
import com.medivault.app.ui.theme.MediVaultTheme
import dagger.hilt.android.AndroidEntryPoint
import com.google.android.gms.auth.api.signin.GoogleSignIn
import com.google.android.gms.auth.api.signin.GoogleSignInOptions
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import okhttp3.MediaType.Companion.toMediaType
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.MultipartBody
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.RequestBody.Companion.asRequestBody
import org.json.JSONArray
import org.json.JSONObject
import java.io.File

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent { MediVaultTheme { MediVaultRoot() } }
    }
}

private data class NavItem(val route: String, val label: String)

@Composable
fun MediVaultRoot() {
    val context = LocalContext.current
    var authToken by rememberSaveable { mutableStateOf(loadAuthToken(context)) }

    if (authToken.isNullOrBlank()) {
        LoginScreen(
            onLoggedIn = { token ->
                saveAuthToken(context, token)
                authToken = token
            }
        )
        return
    }

    val navController = rememberNavController()
    val items = listOf(
        NavItem("dashboard", "Home"),
        NavItem("timeline", "Timeline"),
        NavItem("reminders", "Reminders"),
        NavItem("profile", "Profile")
    )
    Scaffold(
        bottomBar = {
            NavigationBar {
                val backStack by navController.currentBackStackEntryAsState()
                val route = backStack?.destination?.route
                items.forEach { item ->
                    NavigationBarItem(
                        selected = route == item.route,
                        onClick = { navController.navigate(item.route) },
                        icon = {},
                        label = { Text(item.label) }
                    )
                }
            }
        }
    ) { padding ->
        NavHost(
            navController = navController,
            startDestination = "dashboard",
            modifier = Modifier.padding(padding)
        ) {
            composable("dashboard") {
                DashboardScreen(
                    onUpload = { navController.navigate("upload") },
                    onAnalysis = { navController.navigate("timeline") },
                    onAppointments = { navController.navigate("appointments") },
                    onReminders = { navController.navigate("reminders") }
                )
            }
            composable("upload") { UploadScreen(authToken = authToken.orEmpty()) }
            composable("timeline") { TimelineScreen(authToken = authToken.orEmpty()) }
            composable("reminders") { ReminderScreen(authToken = authToken.orEmpty()) }
            composable("appointments") { AppointmentScreen(authToken = authToken.orEmpty()) }
            composable("profile") { ProfileScreen(onLogout = {
                clearAuthToken(context)
                authToken = null
            }) }
        }
    }
}

@Composable
private fun LoginScreen(onLoggedIn: (String) -> Unit) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }

    val gso = remember {
        GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN)
            .requestEmail()
            .requestIdToken(BuildConfig.GOOGLE_WEB_CLIENT_ID)
            .build()
    }
    val googleClient = remember { GoogleSignIn.getClient(context, gso) }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) { result ->
        val task = GoogleSignIn.getSignedInAccountFromIntent(result.data)
        if (task.isSuccessful) {
            val account = task.result
            val idToken = account.idToken
            if (idToken.isNullOrBlank()) {
                error = "Google ID token not received. Set GOOGLE_WEB_CLIENT_ID."
                loading = false
                return@rememberLauncherForActivityResult
            }
            scope.launch {
                loading = true
                val loginResult = withContext(Dispatchers.IO) { runCatching { loginWithGoogle(idToken) } }
                loginResult.onSuccess { onLoggedIn(it) }
                loginResult.onFailure { error = it.message ?: "Google login failed" }
                loading = false
            }
        } else {
            error = task.exception?.message ?: "Google sign-in cancelled"
        }
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(20.dp),
        verticalArrangement = Arrangement.Center,
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        Text("MediVault", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Spacer(Modifier.height(8.dp))
        Text("Secure medical vault with OCR + AI", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(20.dp))
        Button(
            enabled = !loading,
            onClick = {
                error = null
                loading = true
                launcher.launch(googleClient.signInIntent)
            }
        ) { Text(if (loading) "Signing in..." else "Continue with Google") }
        if (error != null) {
            Spacer(Modifier.height(10.dp))
            Text(error ?: "", color = MaterialTheme.colorScheme.error)
        }
    }
}

@Composable
private fun DashboardScreen(
    onUpload: () -> Unit,
    onAnalysis: () -> Unit,
    onAppointments: () -> Unit,
    onReminders: () -> Unit
) {
    val cards = listOf(
        "Upload Report" to onUpload,
        "AI Analysis" to onAnalysis,
        "Medication/Appointment" to onAppointments,
        "Appointment Calendar" to onAppointments
    )
    Column(Modifier.fillMaxSize().padding(16.dp)) {
        Text("MediVault", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Your personal medical vault", color = MaterialTheme.colorScheme.onSurfaceVariant)
        Spacer(Modifier.height(16.dp))
        cards.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (title, click) ->
                    Card(Modifier.weight(1f).height(120.dp).clickable { click() }) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.Center) { Text(title, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
            Spacer(Modifier.height(12.dp))
        }
        Button(onClick = onReminders, modifier = Modifier.fillMaxWidth()) {
            Text("Open Reminders")
        }
    }
}

@Composable
private fun UploadScreen(authToken: String) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var selectedFileUri by remember { mutableStateOf<Uri?>(null) }
    var isUploading by remember { mutableStateOf(false) }
    var uploadError by remember { mutableStateOf<String?>(null) }
    var analysis by remember { mutableStateOf<UploadAnalysis?>(null) }

    val pickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.OpenDocument()
    ) { uri ->
        selectedFileUri = uri
        uploadError = null
    }

    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        Text("Upload Reports", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("Supports PDF, images, prescriptions, and scan via camera.")
        Button(
            onClick = {
                pickerLauncher.launch(
                    arrayOf(
                        "application/pdf",
                        "image/*"
                    )
                )
            },
            enabled = !isUploading
        ) { Text("Select File") }

        Text(
            text = selectedFileUri?.toString() ?: "No file selected",
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )

        Button(
            onClick = {
                val uri = selectedFileUri ?: return@Button
                isUploading = true
                uploadError = null
                analysis = null

                scope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { uploadAndAnalyze(context, uri, authToken) }
                    }

                    result.onSuccess { analysis = it }
                    result.onFailure { uploadError = it.message ?: "Upload failed" }
                    isUploading = false
                }
            },
            enabled = selectedFileUri != null && !isUploading
        ) {
            Text(if (isUploading) "Processing..." else "Process & Analyze")
        }

        if (isUploading) {
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                CircularProgressIndicator(modifier = Modifier.height(20.dp))
                Text("Extracting report data...")
            }
        }

        if (uploadError != null) {
            Text(uploadError ?: "", color = MaterialTheme.colorScheme.error)
        }

        if (analysis != null) {
            AnalysisCard(analysis = analysis!!)
        }
    }
}

@Composable
private fun AnalysisCard(analysis: UploadAnalysis) {
    Card(Modifier.fillMaxWidth()) {
        Column(
            Modifier
                .fillMaxWidth()
                .padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text("AI Analysis", style = MaterialTheme.typography.titleMedium, fontWeight = FontWeight.SemiBold)
            Text(
                text = analysis.summary.ifBlank { "No summary generated." },
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )

            if (analysis.tests.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Extracted Tests", fontWeight = FontWeight.SemiBold)
                analysis.tests.forEach { t ->
                    Text("- ${t.name}: ${t.value}  (${t.referenceRange})")
                }
            }

            if (analysis.medicines.isNotEmpty()) {
                Spacer(Modifier.height(4.dp))
                Text("Medicines", fontWeight = FontWeight.SemiBold)
                analysis.medicines.forEach { m ->
                    Text("- ${m.name}: ${m.dosage} ${m.frequency}".trim())
                }
            }
        }
    }
}

@Composable
private fun TimelineScreen(authToken: String) {
    var reports by remember { mutableStateOf<List<ReportRecord>>(emptyList()) }
    var summary by remember { mutableStateOf("") }
    var loading by remember { mutableStateOf(true) }
    var error by remember { mutableStateOf<String?>(null) }

    LaunchedEffect(Unit) {
        loading = true
        val result = runCatching {
            Pair(fetchReports(authToken), fetchHistorySummary(authToken))
        }
        result.onSuccess {
            reports = it.first
            summary = it.second
            error = null
        }
        result.onFailure {
            error = it.message ?: "Unable to load reports"
        }
        loading = false
    }

    LazyColumn(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        item { Text("Medical History Timeline", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        if (summary.isNotBlank()) {
            item {
                Card(Modifier.fillMaxWidth()) {
                    Column(Modifier.padding(16.dp)) {
                        Text("AI Summary", fontWeight = FontWeight.SemiBold)
                        Spacer(Modifier.height(6.dp))
                        Text(summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }

        if (loading) {
            item {
                Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    CircularProgressIndicator(modifier = Modifier.height(20.dp))
                    Text("Loading reports...")
                }
            }
        }

        if (error != null) {
            item { Text(error ?: "", color = MaterialTheme.colorScheme.error) }
        }

        if (!loading && reports.isEmpty() && error == null) {
            item { Text("No reports found. Upload one from the Upload screen.") }
        }

        items(reports) { report ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(6.dp)) {
                    Text(report.hospitalName.ifBlank { "Unknown hospital" }, fontWeight = FontWeight.SemiBold)
                    if (report.date.isNotBlank()) Text("Date: ${report.date}")
                    if (report.summary.isNotBlank()) {
                        Text(report.summary, color = MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                    report.tests.take(3).forEach { test ->
                        Text("- ${test.name}: ${test.value} (${test.referenceRange})")
                    }
                }
            }
        }
    }
}

@Composable
private fun ReminderScreen(authToken: String) {
    val scope = rememberCoroutineScope()
    var reminders by remember { mutableStateOf<List<SimpleReminder>>(emptyList()) }
    var title by remember { mutableStateOf("") }
    var type by remember { mutableStateOf("tablet") }
    var scheduleAt by remember { mutableStateOf("") }
    var status by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            val result = withContext(Dispatchers.IO) { runCatching { fetchReminders(authToken) } }
            result.onSuccess { reminders = it }
            result.onFailure { status = it.message }
        }
    }

    LaunchedEffect(Unit) { refresh() }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Text("Reminders", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        item { Text("Use ISO datetime: 2026-05-10T08:30:00.000Z") }
        item { OutlinedTextField(value = title, onValueChange = { title = it }, label = { Text("Reminder title") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(value = type, onValueChange = { type = it }, label = { Text("Type (tablet/appointment/test)") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(value = scheduleAt, onValueChange = { scheduleAt = it }, label = { Text("Schedule datetime (ISO)") }, modifier = Modifier.fillMaxWidth()) }
        item {
            Button(
                onClick = {
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching { createReminder(authToken, title, type, scheduleAt) }
                        }
                        result.onSuccess {
                            title = ""
                            scheduleAt = ""
                            status = "Reminder created"
                            refresh()
                        }
                        result.onFailure { status = it.message }
                    }
                },
                enabled = title.isNotBlank() && scheduleAt.isNotBlank()
            ) { Text("Add Reminder") }
        }
        if (status != null) item { Text(status ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(reminders) { r ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(r.title, fontWeight = FontWeight.SemiBold)
                    Text("${r.type} - ${r.scheduleAt}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                }
            }
        }
    }
}

@Composable
private fun AppointmentScreen(authToken: String) {
    val scope = rememberCoroutineScope()
    var doctor by remember { mutableStateOf("") }
    var hospital by remember { mutableStateOf("") }
    var dateTime by remember { mutableStateOf("") }
    var notes by remember { mutableStateOf("") }
    var appointments by remember { mutableStateOf<List<SimpleAppointment>>(emptyList()) }
    var status by remember { mutableStateOf<String?>(null) }

    fun refresh() {
        scope.launch {
            withContext(Dispatchers.IO) {
                runCatching { fetchAppointments(authToken) }
            }.onSuccess { appointments = it }
                .onFailure { status = it.message }
        }
    }
    LaunchedEffect(Unit) { refresh() }

    LazyColumn(
        Modifier.fillMaxSize().padding(16.dp),
        contentPadding = PaddingValues(bottom = 20.dp),
        verticalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        item { Text("Appointments", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold) }
        item { OutlinedTextField(value = doctor, onValueChange = { doctor = it }, label = { Text("Doctor name") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(value = hospital, onValueChange = { hospital = it }, label = { Text("Hospital") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(value = dateTime, onValueChange = { dateTime = it }, label = { Text("DateTime ISO") }, modifier = Modifier.fillMaxWidth()) }
        item { OutlinedTextField(value = notes, onValueChange = { notes = it }, label = { Text("Notes") }, modifier = Modifier.fillMaxWidth()) }
        item {
            Button(
                onClick = {
                    scope.launch {
                        withContext(Dispatchers.IO) {
                            runCatching { createAppointment(authToken, doctor, hospital, dateTime, notes) }
                        }.onSuccess {
                            doctor = ""; hospital = ""; dateTime = ""; notes = ""
                            status = "Appointment created"
                            refresh()
                        }.onFailure { status = it.message }
                    }
                },
                enabled = doctor.isNotBlank() && dateTime.isNotBlank()
            ) { Text("Add Appointment") }
        }
        if (status != null) item { Text(status ?: "", color = MaterialTheme.colorScheme.onSurfaceVariant) }
        items(appointments) { appt ->
            Card(Modifier.fillMaxWidth()) {
                Column(Modifier.padding(14.dp)) {
                    Text(appt.doctorName, fontWeight = FontWeight.SemiBold)
                    Text("${appt.hospital} - ${appt.dateTime}", color = MaterialTheme.colorScheme.onSurfaceVariant)
                    if (appt.notes.isNotBlank()) Text(appt.notes)
                }
            }
        }
    }
}

@Composable
private fun ProfileScreen(onLogout: () -> Unit) {
    Column(Modifier.fillMaxSize().padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text("Profile", style = MaterialTheme.typography.titleLarge, fontWeight = FontWeight.SemiBold)
        Text("Google authenticated account")
        Button(onClick = onLogout) { Text("Logout") }
    }
}

private data class LabTest(val name: String, val value: String, val referenceRange: String)
private data class Medicine(val name: String, val dosage: String, val frequency: String)
private data class UploadAnalysis(val summary: String, val tests: List<LabTest>, val medicines: List<Medicine>)
private data class ReportRecord(val hospitalName: String, val date: String, val summary: String, val tests: List<LabTest>)
private data class SimpleReminder(val title: String, val type: String, val scheduleAt: String)
private data class SimpleAppointment(val doctorName: String, val hospital: String, val dateTime: String, val notes: String)

private fun uploadAndAnalyze(context: Context, uri: Uri, token: String): UploadAnalysis {
    val resolver = context.contentResolver
    val mimeType = resolver.getType(uri) ?: "application/octet-stream"
    val extension = when {
        mimeType.contains("pdf") -> ".pdf"
        mimeType.contains("image") -> ".jpg"
        else -> ".bin"
    }

    val tempFile = File.createTempFile("report_upload_", extension, context.cacheDir)
    resolver.openInputStream(uri)?.use { input ->
        tempFile.outputStream().use { output -> input.copyTo(output) }
    } ?: throw IllegalStateException("Unable to read selected file")

    val fileBody = tempFile.asRequestBody(mimeType.toMediaTypeOrNull())
    val multipart = MultipartBody.Builder()
        .setType(MultipartBody.FORM)
        .addFormDataPart("fileType", if (mimeType.contains("pdf")) "pdf" else "image")
        .addFormDataPart("file", tempFile.name, fileBody)
        .build()

    val request = Request.Builder()
        .url("${BuildConfig.API_BASE_URL}api/reports/upload")
        .addHeader("Authorization", "Bearer $token")
        .post(multipart)
        .build()

    val client = OkHttpClient()
    client.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw IllegalStateException("Upload failed (${response.code}): $body")
        }

        val json = JSONObject(body)
        val extracted = json.optJSONObject("extractedData") ?: JSONObject()
        val summary = extracted.optString("summary")
        val tests = extracted.optJSONArray("tests").toLabTests()
        val medicines = extracted.optJSONArray("medicines").toMedicines()
        return UploadAnalysis(summary = summary, tests = tests, medicines = medicines)
    }
}

private fun JSONArray?.toLabTests(): List<LabTest> {
    if (this == null) return emptyList()
    val out = mutableListOf<LabTest>()
    for (i in 0 until length()) {
        val obj = optJSONObject(i) ?: continue
        out.add(
            LabTest(
                name = obj.optString("name"),
                value = obj.optString("value"),
                referenceRange = obj.optString("referenceRange")
            )
        )
    }
    return out
}

private fun JSONArray?.toMedicines(): List<Medicine> {
    if (this == null) return emptyList()
    val out = mutableListOf<Medicine>()
    for (i in 0 until length()) {
        val obj = optJSONObject(i) ?: continue
        out.add(
            Medicine(
                name = obj.optString("name"),
                dosage = obj.optString("dosage"),
                frequency = obj.optString("frequency")
            )
        )
    }
    return out
}

private fun fetchReports(token: String): List<ReportRecord> {
    val request = Request.Builder()
        .url("${BuildConfig.API_BASE_URL}api/reports")
        .addHeader("Authorization", "Bearer $token")
        .get()
        .build()

    val client = OkHttpClient()
    client.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) {
            throw IllegalStateException("Fetch reports failed (${response.code}): $body")
        }

        val arr = JSONArray(body)
        val out = mutableListOf<ReportRecord>()
        for (i in 0 until arr.length()) {
            val report = arr.optJSONObject(i) ?: continue
            val extracted = report.optJSONObject("extractedData") ?: JSONObject()
            out.add(
                ReportRecord(
                    hospitalName = report.optString("hospitalName"),
                    date = report.optString("reportDate"),
                    summary = extracted.optString("summary"),
                    tests = extracted.optJSONArray("tests").toLabTests()
                )
            )
        }
        return out
    }
}

private fun fetchHistorySummary(token: String): String {
    val request = Request.Builder()
        .url("${BuildConfig.API_BASE_URL}api/reports/timeline")
        .addHeader("Authorization", "Bearer $token")
        .get()
        .build()
    val client = OkHttpClient()
    client.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw IllegalStateException("Summary request failed (${response.code})")
        return JSONObject(body).optString("summary")
    }
}

private fun createReminder(token: String, title: String, type: String, scheduleAt: String) {
    val payload = JSONObject()
        .put("title", title)
        .put("type", type)
        .put("scheduleAt", scheduleAt)
    val request = Request.Builder()
        .url("${BuildConfig.API_BASE_URL}api/reminders")
        .addHeader("Authorization", "Bearer $token")
        .post(payload.toString().toRequestBody("application/json".toMediaType()))
        .build()
    OkHttpClient().newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IllegalStateException("Create reminder failed (${response.code}) ${response.body?.string().orEmpty()}")
    }
}

private fun fetchReminders(token: String): List<SimpleReminder> {
    val request = Request.Builder()
        .url("${BuildConfig.API_BASE_URL}api/reminders")
        .addHeader("Authorization", "Bearer $token")
        .get()
        .build()
    OkHttpClient().newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw IllegalStateException("Fetch reminders failed (${response.code})")
        val arr = JSONArray(body)
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                add(SimpleReminder(obj.optString("title"), obj.optString("type"), obj.optString("scheduleAt")))
            }
        }
    }
}

private fun createAppointment(token: String, doctor: String, hospital: String, dateTime: String, notes: String) {
    val payload = JSONObject()
        .put("doctorName", doctor)
        .put("hospital", hospital)
        .put("dateTime", dateTime)
        .put("notes", notes)
    val request = Request.Builder()
        .url("${BuildConfig.API_BASE_URL}api/appointments")
        .addHeader("Authorization", "Bearer $token")
        .post(payload.toString().toRequestBody("application/json".toMediaType()))
        .build()
    OkHttpClient().newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IllegalStateException("Create appointment failed (${response.code})")
    }
}

private fun fetchAppointments(token: String): List<SimpleAppointment> {
    val request = Request.Builder()
        .url("${BuildConfig.API_BASE_URL}api/appointments")
        .addHeader("Authorization", "Bearer $token")
        .get()
        .build()
    OkHttpClient().newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw IllegalStateException("Fetch appointments failed (${response.code})")
        val arr = JSONArray(body)
        return buildList {
            for (i in 0 until arr.length()) {
                val obj = arr.optJSONObject(i) ?: continue
                add(
                    SimpleAppointment(
                        doctorName = obj.optString("doctorName"),
                        hospital = obj.optString("hospital"),
                        dateTime = obj.optString("dateTime"),
                        notes = obj.optString("notes")
                    )
                )
            }
        }
    }
}

private fun loginWithGoogle(idToken: String): String {
    val payload = JSONObject().put("idToken", idToken)
    val request = Request.Builder()
        .url("${BuildConfig.API_BASE_URL}api/auth/google")
        .post(payload.toString().toRequestBody("application/json".toMediaType()))
        .build()
    OkHttpClient().newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw IllegalStateException("Google auth failed (${response.code}): $body")
        val token = JSONObject(body).optString("token")
        if (token.isBlank()) throw IllegalStateException("Auth token missing in response")
        return token
    }
}

private fun loadAuthToken(context: Context): String? =
    context.getSharedPreferences("medivault_prefs", Context.MODE_PRIVATE).getString("auth_token", null)

private fun saveAuthToken(context: Context, token: String) {
    context.getSharedPreferences("medivault_prefs", Context.MODE_PRIVATE)
        .edit()
        .putString("auth_token", token)
        .apply()
}

private fun clearAuthToken(context: Context) {
    context.getSharedPreferences("medivault_prefs", Context.MODE_PRIVATE)
        .edit()
        .remove("auth_token")
        .apply()
}
