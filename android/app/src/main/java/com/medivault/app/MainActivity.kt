@file:OptIn(
    androidx.compose.material3.ExperimentalMaterial3Api::class,
    androidx.compose.foundation.layout.ExperimentalLayoutApi::class
)
package com.medivault.app

import android.content.Context
import android.net.Uri
import android.os.Bundle
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
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
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Divider
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.ExposedDropdownMenuBox
import androidx.compose.material3.ExposedDropdownMenuDefaults
import androidx.compose.material3.FilledTonalButton
import androidx.compose.material3.FilterChip
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.NavigationBar
import androidx.compose.material3.NavigationBarItem
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
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
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
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
import java.net.SocketTimeoutException
import java.util.concurrent.TimeUnit

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
    // freshLogin = true means the token was just issued; skip server validation for it
    var freshLogin by rememberSaveable { mutableStateOf(false) }
    var authToken by rememberSaveable { mutableStateOf(loadAuthToken(context)) }
    var authChecking by rememberSaveable { mutableStateOf(true) }
    // null = not yet checked, true = needs onboarding, false = onboarding done
    var needsOnboarding by rememberSaveable { mutableStateOf<Boolean?>(null) }

    // Only re-run when authToken changes. freshLogin is read inside but not a key,
    // so changing it alone never re-triggers this effect.
    LaunchedEffect(authToken) {
        if (authToken.isNullOrBlank()) {
            authChecking = false
            needsOnboarding = null
        } else if (freshLogin) {
            // Brand-new login — isNewUser flag already set needsOnboarding before this runs
            freshLogin = false
            authChecking = false
        } else {
            // Returning user with a saved token — just validate the session, skip onboarding
            val valid = runCatching { verifySession(authToken.orEmpty()) }.getOrDefault(false)
            if (!valid) {
                clearAuthToken(context)
                clearUserInfo(context)
                authToken = null
                needsOnboarding = null
            } else {
                // Already went through onboarding before; go straight to home
                needsOnboarding = false
            }
            authChecking = false
        }
    }

    if (authChecking) {
        Column(
            Modifier
                .fillMaxSize()
                .padding(20.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            CircularProgressIndicator()
            Spacer(Modifier.height(12.dp))
            Text("Verifying session...")
        }
        return
    }

    if (authToken.isNullOrBlank()) {
        LoginScreen(
            cachedName = loadUserName(context),
            cachedEmail = loadUserEmail(context),
            onLoggedIn = { result ->
                saveAuthToken(context, result.token)
                saveUserInfo(context, result.name, result.email)
                // isNewUser drives onboarding; existing users skip it
                needsOnboarding = result.isNewUser
                freshLogin = true
                authToken = result.token
            }
        )
        return
    }

    // Show onboarding if profile is incomplete
    if (needsOnboarding == true) {
        OnboardingScreen(
            authToken = authToken.orEmpty(),
            onComplete = { needsOnboarding = false }
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
            composable("profile") {
                ProfileScreen(
                    authToken = authToken.orEmpty(),
                    onLogout = {
                        clearAuthToken(context)
                        clearUserInfo(context)
                        val gso = GoogleSignInOptions.Builder(GoogleSignInOptions.DEFAULT_SIGN_IN).requestEmail().build()
                        GoogleSignIn.getClient(context, gso).signOut()
                        authToken = null
                        needsOnboarding = null
                    }
                )
            }
        }
    }
}

@Composable
private fun LoginScreen(
    cachedName: String?,
    cachedEmail: String?,
    onLoggedIn: (GoogleAuthResult) -> Unit
) {
    val context = LocalContext.current
    val scope = rememberCoroutineScope()
    var loading by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val isReturningUser = !cachedEmail.isNullOrBlank()

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
                val loginResult = withContext(Dispatchers.IO) { runCatching { loginWithGoogle(idToken) } }
                loginResult.onSuccess { onLoggedIn(it) }
                loginResult.onFailure { error = it.message ?: "Google login failed" }
                loading = false
            }
        } else {
            error = task.exception?.message ?: "Google sign-in cancelled"
            loading = false
        }
    }

    fun signIn() {
        error = null
        if (BuildConfig.GOOGLE_WEB_CLIENT_ID.isBlank()) {
            error = "GOOGLE_WEB_CLIENT_ID is missing. Add it in gradle.properties and rebuild."
            return
        }
        // For returning users keep the Google account pre-selected; for new users force picker
        val signInIntent = if (isReturningUser) googleClient.signInIntent
        else { googleClient.signOut(); googleClient.signInIntent }
        if (isReturningUser) {
            loading = true
            launcher.launch(signInIntent)
        } else {
            googleClient.signOut().addOnCompleteListener {
                loading = true
                launcher.launch(googleClient.signInIntent)
            }
        }
    }

    if (isReturningUser) {
        // ── Returning user: compact "Welcome back" screen ─────────
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(horizontal = 28.dp),
            verticalArrangement = Arrangement.Center,
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            // Avatar with initials
            val initials = (cachedName ?: "?")
                .split(" ").filter { it.isNotBlank() }.take(2)
                .joinToString("") { it.first().uppercaseChar().toString() }
            Box(
                modifier = Modifier
                    .size(72.dp)
                    .clip(CircleShape)
                    .background(MaterialTheme.colorScheme.primaryContainer),
                contentAlignment = Alignment.Center
            ) {
                Text(initials.ifBlank { "?" }, fontSize = 26.sp, fontWeight = FontWeight.Bold,
                    color = MaterialTheme.colorScheme.onPrimaryContainer)
            }
            Spacer(Modifier.height(16.dp))
            Text(
                "Welcome back\u2019",
                style = MaterialTheme.typography.titleMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(4.dp))
            Text(
                cachedName ?: "",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold,
                textAlign = TextAlign.Center
            )
            if (!cachedEmail.isNullOrBlank()) {
                Spacer(Modifier.height(4.dp))
                Text(cachedEmail, style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant)
            }
            Spacer(Modifier.height(32.dp))
            Button(
                enabled = !loading,
                onClick = { signIn() },
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (loading) {
                    CircularProgressIndicator(modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                    Spacer(Modifier.width(10.dp))
                    Text("Signing in...")
                } else {
                    Text("Continue as ${cachedName?.split(" ")?.firstOrNull() ?: "me"}",
                        fontWeight = FontWeight.SemiBold)
                }
            }
            Spacer(Modifier.height(12.dp))
            TextButton(onClick = {
                // Let user switch accounts
                googleClient.signOut().addOnCompleteListener {
                    loading = true
                    launcher.launch(googleClient.signInIntent)
                }
            }) {
                Text("Use a different account", style = MaterialTheme.typography.bodySmall)
            }
            if (error != null) {
                Spacer(Modifier.height(12.dp))
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(error ?: "", color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                }
            }
        }
    } else {
        // ── New user: full branded onboarding screen ──────────────
        val features = listOf(
            "\uD83D\uDD12" to "Encrypted medical records",
            "\uD83E\uDD16" to "AI-powered OCR & analysis",
            "\uD83D\uDC8A" to "Medication & appointment reminders",
            "\uD83D\uDCCA" to "Health timeline & trends"
        )
        LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(horizontal = 28.dp, vertical = 48.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            item {
                Spacer(Modifier.height(24.dp))
                Box(
                    modifier = Modifier.size(80.dp).clip(RoundedCornerShape(20.dp))
                        .background(MaterialTheme.colorScheme.primaryContainer),
                    contentAlignment = Alignment.Center
                ) { Text("\uD83C\uDFE5", fontSize = 36.sp) }
                Spacer(Modifier.height(20.dp))
                Text("MediVault", style = MaterialTheme.typography.headlineLarge,
                    fontWeight = FontWeight.Bold, textAlign = TextAlign.Center)
                Spacer(Modifier.height(6.dp))
                Text("Your personal health companion", style = MaterialTheme.typography.bodyLarge,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
                Spacer(Modifier.height(36.dp))
            }
            item {
                Card(
                    modifier = Modifier.fillMaxWidth(),
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.surfaceVariant),
                    shape = RoundedCornerShape(16.dp)
                ) {
                    Column(modifier = Modifier.padding(20.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                        features.forEach { (icon, label) ->
                            Row(verticalAlignment = Alignment.CenterVertically,
                                horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                                Text(icon, fontSize = 20.sp)
                                Text(label, style = MaterialTheme.typography.bodyMedium)
                            }
                        }
                    }
                }
                Spacer(Modifier.height(32.dp))
            }
            item {
                Button(
                    enabled = !loading,
                    onClick = { signIn() },
                    modifier = Modifier.fillMaxWidth().height(52.dp),
                    shape = RoundedCornerShape(14.dp)
                ) {
                    if (loading) {
                        CircularProgressIndicator(modifier = Modifier.size(20.dp),
                            color = MaterialTheme.colorScheme.onPrimary, strokeWidth = 2.dp)
                        Spacer(Modifier.width(10.dp))
                        Text("Signing in...")
                    } else {
                        Text("Continue with Google", fontWeight = FontWeight.SemiBold)
                    }
                }
                Spacer(Modifier.height(12.dp))
                Text("By continuing, you agree to keep your health data secure.",
                    style = MaterialTheme.typography.labelSmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant, textAlign = TextAlign.Center)
            }
            if (error != null) {
                item {
                    Spacer(Modifier.height(12.dp))
                    Card(
                        colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                        shape = RoundedCornerShape(10.dp)
                    ) {
                        Text(error ?: "", color = MaterialTheme.colorScheme.onErrorContainer,
                            modifier = Modifier.padding(12.dp), style = MaterialTheme.typography.bodySmall)
                    }
                }
            }
        }
    }
}

private val BLOOD_GROUPS = listOf("A+", "A-", "B+", "B-", "AB+", "AB-", "O+", "O-")

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun OnboardingScreen(authToken: String, onComplete: () -> Unit) {
    val scope = rememberCoroutineScope()
    var name by rememberSaveable { mutableStateOf("") }
    var selectedBloodGroup by rememberSaveable { mutableStateOf("") }
    var age by rememberSaveable { mutableStateOf("") }
    // Optional fields
    var allergyInput by rememberSaveable { mutableStateOf("") }
    var allergies by rememberSaveable { mutableStateOf<List<String>>(emptyList()) }
    var emergencyName by rememberSaveable { mutableStateOf("") }
    var emergencyPhone by rememberSaveable { mutableStateOf("") }
    var emergencyRelation by rememberSaveable { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var bloodGroupExpanded by remember { mutableStateOf(false) }

    LaunchedEffect(Unit) {
        val profile = runCatching { fetchProfile(authToken) }.getOrNull()
        if (!profile?.name.isNullOrBlank()) name = profile!!.name
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(horizontal = 24.dp, vertical = 40.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp)
    ) {
        // Header
        item {
            Text(
                "Complete your profile",
                style = MaterialTheme.typography.headlineSmall,
                fontWeight = FontWeight.Bold
            )
            Spacer(Modifier.height(4.dp))
            Text(
                "Tell us a bit about yourself. This helps personalise your experience and is critical in emergencies.",
                style = MaterialTheme.typography.bodyMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.height(8.dp))
        }

        // ── Required ──────────────────────────────────────────────
        item {
            Text("Required", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
        }

        item {
            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text("Full name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
        }

        item {
            ExposedDropdownMenuBox(
                expanded = bloodGroupExpanded,
                onExpandedChange = { bloodGroupExpanded = it }
            ) {
                OutlinedTextField(
                    value = selectedBloodGroup,
                    onValueChange = {},
                    readOnly = true,
                    label = { Text("Blood group") },
                    trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = bloodGroupExpanded) },
                    modifier = Modifier.fillMaxWidth().menuAnchor(),
                    shape = RoundedCornerShape(12.dp)
                )
                ExposedDropdownMenu(
                    expanded = bloodGroupExpanded,
                    onDismissRequest = { bloodGroupExpanded = false }
                ) {
                    BLOOD_GROUPS.forEach { bg ->
                        DropdownMenuItem(
                            text = { Text(bg) },
                            onClick = { selectedBloodGroup = bg; bloodGroupExpanded = false }
                        )
                    }
                }
            }
        }

        item {
            OutlinedTextField(
                value = age,
                onValueChange = { if (it.length <= 3 && it.all(Char::isDigit)) age = it },
                label = { Text("Age (optional)") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
        }

        // ── Optional: Allergies ───────────────────────────────────
        item { Spacer(Modifier.height(4.dp)) }
        item {
            Text("Allergies", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text("Optional \u2014 add any known drug or food allergies.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                OutlinedTextField(
                    value = allergyInput,
                    onValueChange = { allergyInput = it },
                    label = { Text("e.g. Penicillin, Peanuts") },
                    singleLine = true,
                    modifier = Modifier.weight(1f),
                    shape = RoundedCornerShape(12.dp)
                )
                FilledTonalButton(
                    onClick = {
                        val t = allergyInput.trim()
                        if (t.isNotBlank() && !allergies.contains(t)) allergies = allergies + t
                        allergyInput = ""
                    }
                ) { Text("Add") }
            }
        }

        if (allergies.isNotEmpty()) {
            item {
                FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    allergies.forEach { a ->
                        FilterChip(
                            selected = false,
                            onClick = { allergies = allergies - a },
                            label = { Text("$a \u2715") }
                        )
                    }
                }
            }
        }

        // ── Optional: Emergency Contact ───────────────────────────
        item { Spacer(Modifier.height(4.dp)) }
        item {
            Text("Emergency Contact", style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.primary, fontWeight = FontWeight.SemiBold)
            Spacer(Modifier.height(2.dp))
            Text("Optional \u2014 someone we can reference in your medical records.",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        item {
            OutlinedTextField(
                value = emergencyName,
                onValueChange = { emergencyName = it },
                label = { Text("Contact name") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
        }

        item {
            OutlinedTextField(
                value = emergencyPhone,
                onValueChange = { emergencyPhone = it },
                label = { Text("Phone number") },
                singleLine = true,
                keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
        }

        item {
            OutlinedTextField(
                value = emergencyRelation,
                onValueChange = { emergencyRelation = it },
                label = { Text("Relation (e.g. Spouse, Parent)") },
                singleLine = true,
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp)
            )
        }

        // ── Error + Submit ────────────────────────────────────────
        if (error != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(10.dp),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Text(
                        error ?: "",
                        color = MaterialTheme.colorScheme.onErrorContainer,
                        modifier = Modifier.padding(12.dp),
                        style = MaterialTheme.typography.bodySmall
                    )
                }
            }
        }

        item {
            Spacer(Modifier.height(4.dp))
            Button(
                onClick = {
                    if (name.isBlank()) { error = "Please enter your name."; return@Button }
                    if (selectedBloodGroup.isBlank()) { error = "Please select your blood group."; return@Button }
                    error = null
                    saving = true
                    scope.launch {
                        val result = withContext(Dispatchers.IO) {
                            runCatching {
                                updateProfileFull(
                                    authToken,
                                    name = name.trim(),
                                    bloodGroup = selectedBloodGroup,
                                    age = age.toIntOrNull(),
                                    allergies = allergies,
                                    emergencyName = emergencyName.trim(),
                                    emergencyPhone = emergencyPhone.trim(),
                                    emergencyRelation = emergencyRelation.trim()
                                )
                            }
                        }
                        result.onSuccess { onComplete() }
                        result.onFailure { error = it.message ?: "Failed to save profile" }
                        saving = false
                    }
                },
                enabled = !saving,
                modifier = Modifier.fillMaxWidth().height(52.dp),
                shape = RoundedCornerShape(14.dp)
            ) {
                if (saving) {
                    CircularProgressIndicator(
                        modifier = Modifier.size(20.dp),
                        color = MaterialTheme.colorScheme.onPrimary,
                        strokeWidth = 2.dp
                    )
                    Spacer(Modifier.width(10.dp))
                    Text("Saving...")
                } else {
                    Text("Get started", fontWeight = FontWeight.SemiBold)
                }
            }
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
    Column(
        Modifier
            .fillMaxSize()
            .padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        Text("MediVault", style = MaterialTheme.typography.headlineMedium, fontWeight = FontWeight.Bold)
        Text("Your personal medical vault", color = MaterialTheme.colorScheme.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        cards.chunked(2).forEach { row ->
            Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                row.forEach { (title, click) ->
                    Card(
                        Modifier
                            .weight(1f)
                            .height(120.dp)
                            .clickable { click() },
                        colors = CardDefaults.cardColors(
                            containerColor = MaterialTheme.colorScheme.surfaceVariant
                        )
                    ) {
                        Column(Modifier.padding(16.dp), verticalArrangement = Arrangement.Center) { Text(title, fontWeight = FontWeight.SemiBold) }
                    }
                }
            }
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
            color = MaterialTheme.colorScheme.onSurfaceVariant,
            maxLines = 2,
            overflow = TextOverflow.Ellipsis
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

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
private fun ProfileScreen(authToken: String, onLogout: () -> Unit) {
    val scope = rememberCoroutineScope()
    var profile by remember { mutableStateOf<UserProfile?>(null) }
    var loading by remember { mutableStateOf(true) }
    var editing by remember { mutableStateOf(false) }
    var saving by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    var showLogoutDialog by remember { mutableStateOf(false) }

    var editName by remember { mutableStateOf("") }
    var editBloodGroup by remember { mutableStateOf("") }
    var editAge by remember { mutableStateOf("") }
    var editAllergyInput by remember { mutableStateOf("") }
    var editAllergies by remember { mutableStateOf<List<String>>(emptyList()) }
    var editEmergencyName by remember { mutableStateOf("") }
    var editEmergencyPhone by remember { mutableStateOf("") }
    var editEmergencyRelation by remember { mutableStateOf("") }
    var bloodGroupExpanded by remember { mutableStateOf(false) }

    fun loadProfile() {
        scope.launch {
            loading = true
            val result = withContext(Dispatchers.IO) { runCatching { fetchProfile(authToken) } }
            result.onSuccess { p ->
                profile = p
                editName = p.name; editBloodGroup = p.bloodGroup; editAge = p.age
                editAllergies = p.allergies; editEmergencyName = p.emergencyName
                editEmergencyPhone = p.emergencyPhone; editEmergencyRelation = p.emergencyRelation
            }
            result.onFailure { error = it.message }
            loading = false
        }
    }

    LaunchedEffect(Unit) { loadProfile() }

    if (showLogoutDialog) {
        AlertDialog(
            onDismissRequest = { showLogoutDialog = false },
            title = { Text("Sign out?") },
            text = { Text("You'll need to sign in again to access your records.") },
            confirmButton = {
                Button(
                    onClick = onLogout,
                    colors = ButtonDefaults.buttonColors(containerColor = MaterialTheme.colorScheme.error)
                ) { Text("Sign out") }
            },
            dismissButton = {
                TextButton(onClick = { showLogoutDialog = false }) { Text("Cancel") }
            }
        )
    }

    LazyColumn(
        modifier = Modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(12.dp)
    ) {
        item {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                Text("Profile", style = MaterialTheme.typography.headlineSmall, fontWeight = FontWeight.Bold)
                if (!loading) {
                    if (editing) {
                        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                            TextButton(onClick = {
                                editing = false
                                profile?.let {
                                    editName = it.name; editBloodGroup = it.bloodGroup
                                    editAge = it.age; editAllergies = it.allergies
                                    editEmergencyName = it.emergencyName
                                    editEmergencyPhone = it.emergencyPhone
                                    editEmergencyRelation = it.emergencyRelation
                                }
                            }) { Text("Cancel") }
                            Button(
                                onClick = {
                                    saving = true
                                    scope.launch {
                                        val result = withContext(Dispatchers.IO) {
                                            runCatching {
                                                updateProfileFull(
                                                    authToken,
                                                    name = editName.trim(),
                                                    bloodGroup = editBloodGroup,
                                                    age = editAge.toIntOrNull(),
                                                    allergies = editAllergies,
                                                    emergencyName = editEmergencyName.trim(),
                                                    emergencyPhone = editEmergencyPhone.trim(),
                                                    emergencyRelation = editEmergencyRelation.trim()
                                                )
                                            }
                                        }
                                        result.onSuccess { loadProfile(); editing = false }
                                        result.onFailure { error = it.message }
                                        saving = false
                                    }
                                },
                                enabled = !saving
                            ) { Text(if (saving) "Saving\u2026" else "Save") }
                        }
                    } else {
                        FilledTonalButton(onClick = { editing = true }) { Text("Edit") }
                    }
                }
            }
        }

        if (loading) {
            item {
                Box(Modifier.fillMaxWidth().padding(40.dp), contentAlignment = Alignment.Center) {
                    CircularProgressIndicator()
                }
            }
            return@LazyColumn
        }

        if (error != null) {
            item {
                Card(
                    colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.errorContainer),
                    shape = RoundedCornerShape(10.dp)
                ) {
                    Text(error ?: "", color = MaterialTheme.colorScheme.onErrorContainer, modifier = Modifier.padding(12.dp))
                }
            }
        }

        // Avatar + name banner
        item {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = MaterialTheme.colorScheme.primaryContainer)
            ) {
                Row(
                    modifier = Modifier.padding(20.dp),
                    verticalAlignment = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    val initials = (profile?.name ?: "?")
                        .split(" ").filter { it.isNotBlank() }.take(2)
                        .joinToString("") { it.first().uppercaseChar().toString() }
                    Box(
                        modifier = Modifier.size(64.dp).clip(CircleShape)
                            .background(MaterialTheme.colorScheme.primary),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            initials.ifBlank { "?" },
                            color = MaterialTheme.colorScheme.onPrimary,
                            fontWeight = FontWeight.Bold,
                            fontSize = 22.sp
                        )
                    }
                    Column {
                        Text(
                            profile?.name?.ifBlank { "\u2014" } ?: "\u2014",
                            style = MaterialTheme.typography.titleLarge,
                            fontWeight = FontWeight.SemiBold,
                            color = MaterialTheme.colorScheme.onPrimaryContainer
                        )
                        if (!profile?.email.isNullOrBlank()) {
                            Text(
                                profile!!.email,
                                style = MaterialTheme.typography.bodyMedium,
                                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.75f)
                            )
                        }
                    }
                }
            }
        }

        // Medical info
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Medical Info", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                    Divider()
                    if (editing) {
                        OutlinedTextField(
                            value = editName, onValueChange = { editName = it },
                            label = { Text("Full name") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)
                        )
                        ExposedDropdownMenuBox(
                            expanded = bloodGroupExpanded,
                            onExpandedChange = { bloodGroupExpanded = it }
                        ) {
                            OutlinedTextField(
                                value = editBloodGroup, onValueChange = {}, readOnly = true,
                                label = { Text("Blood group") },
                                trailingIcon = { ExposedDropdownMenuDefaults.TrailingIcon(expanded = bloodGroupExpanded) },
                                modifier = Modifier.fillMaxWidth().menuAnchor(),
                                shape = RoundedCornerShape(10.dp)
                            )
                            ExposedDropdownMenu(expanded = bloodGroupExpanded, onDismissRequest = { bloodGroupExpanded = false }) {
                                BLOOD_GROUPS.forEach { bg ->
                                    DropdownMenuItem(text = { Text(bg) }, onClick = { editBloodGroup = bg; bloodGroupExpanded = false })
                                }
                            }
                        }
                        OutlinedTextField(
                            value = editAge,
                            onValueChange = { if (it.length <= 3 && it.all(Char::isDigit)) editAge = it },
                            label = { Text("Age") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Number),
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)
                        )
                    } else {
                        ProfileInfoRow("Blood Group", profile?.bloodGroup?.ifBlank { "\u2014" } ?: "\u2014")
                        ProfileInfoRow("Age", profile?.age?.ifBlank { "\u2014" } ?: "\u2014")
                    }
                }
            }
        }

        // Allergies
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Text("Allergies", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                    Divider()
                    if (editing) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            OutlinedTextField(
                                value = editAllergyInput, onValueChange = { editAllergyInput = it },
                                label = { Text("Add allergy") }, singleLine = true,
                                modifier = Modifier.weight(1f), shape = RoundedCornerShape(10.dp)
                            )
                            FilledTonalButton(onClick = {
                                val t = editAllergyInput.trim()
                                if (t.isNotBlank() && !editAllergies.contains(t)) editAllergies = editAllergies + t
                                editAllergyInput = ""
                            }) { Text("Add") }
                        }
                        if (editAllergies.isNotEmpty()) {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                editAllergies.forEach { a ->
                                    FilterChip(selected = false, onClick = { editAllergies = editAllergies - a }, label = { Text("$a \u2715") })
                                }
                            }
                        }
                    } else {
                        val allergies = profile?.allergies ?: emptyList()
                        if (allergies.isEmpty()) {
                            Text("None recorded", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                                allergies.forEach { a ->
                                    Surface(shape = RoundedCornerShape(20.dp), color = MaterialTheme.colorScheme.secondaryContainer) {
                                        Text(a, modifier = Modifier.padding(horizontal = 12.dp, vertical = 6.dp),
                                            style = MaterialTheme.typography.labelMedium,
                                            color = MaterialTheme.colorScheme.onSecondaryContainer)
                                    }
                                }
                            }
                        }
                    }
                }
            }
        }

        // Emergency contact
        item {
            Card(modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(16.dp)) {
                Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(14.dp)) {
                    Text("Emergency Contact", fontWeight = FontWeight.SemiBold, style = MaterialTheme.typography.titleMedium)
                    Divider()
                    if (editing) {
                        OutlinedTextField(
                            value = editEmergencyName, onValueChange = { editEmergencyName = it },
                            label = { Text("Contact name") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)
                        )
                        OutlinedTextField(
                            value = editEmergencyPhone, onValueChange = { editEmergencyPhone = it },
                            label = { Text("Phone number") }, singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)
                        )
                        OutlinedTextField(
                            value = editEmergencyRelation, onValueChange = { editEmergencyRelation = it },
                            label = { Text("Relation (e.g. Spouse, Parent)") }, singleLine = true,
                            modifier = Modifier.fillMaxWidth(), shape = RoundedCornerShape(10.dp)
                        )
                    } else {
                        if (profile?.emergencyName.isNullOrBlank()) {
                            Text("No emergency contact added", color = MaterialTheme.colorScheme.onSurfaceVariant)
                        } else {
                            ProfileInfoRow("Name", profile?.emergencyName ?: "\u2014")
                            ProfileInfoRow("Phone", profile?.emergencyPhone?.ifBlank { "\u2014" } ?: "\u2014")
                            ProfileInfoRow("Relation", profile?.emergencyRelation?.ifBlank { "\u2014" } ?: "\u2014")
                        }
                    }
                }
            }
        }

        // Sign out
        item {
            Spacer(Modifier.height(4.dp))
            OutlinedButton(
                onClick = { showLogoutDialog = true },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(14.dp),
                colors = ButtonDefaults.outlinedButtonColors(contentColor = MaterialTheme.colorScheme.error),
                border = androidx.compose.foundation.BorderStroke(1.dp, MaterialTheme.colorScheme.error)
            ) {
                Text("Sign out", fontWeight = FontWeight.SemiBold)
            }
            Spacer(Modifier.height(16.dp))
        }
    }
}

@Composable
private fun ProfileInfoRow(label: String, value: String) {
    Row(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.Top
    ) {
        Text(label, style = MaterialTheme.typography.bodyMedium,
            color = MaterialTheme.colorScheme.onSurfaceVariant, modifier = Modifier.weight(0.4f))
        Text(value, style = MaterialTheme.typography.bodyMedium, fontWeight = FontWeight.Medium,
            modifier = Modifier.weight(0.6f), textAlign = TextAlign.End)
    }
}

private data class LabTest(val name: String, val value: String, val referenceRange: String)
private data class Medicine(val name: String, val dosage: String, val frequency: String)
private data class UploadAnalysis(val summary: String, val tests: List<LabTest>, val medicines: List<Medicine>)
private data class ReportRecord(val hospitalName: String, val date: String, val summary: String, val tests: List<LabTest>)
private data class SimpleReminder(val title: String, val type: String, val scheduleAt: String)
private data class SimpleAppointment(val doctorName: String, val hospital: String, val dateTime: String, val notes: String)
private data class UserProfile(
    val name: String,
    val email: String,
    val bloodGroup: String,
    val age: String,
    val allergies: List<String>,
    val emergencyName: String,
    val emergencyPhone: String,
    val emergencyRelation: String
)

private val httpClient: OkHttpClient by lazy {
    OkHttpClient.Builder()
        .connectTimeout(30, TimeUnit.SECONDS)
        .readTimeout(60, TimeUnit.SECONDS)
        .writeTimeout(60, TimeUnit.SECONDS)
        .callTimeout(75, TimeUnit.SECONDS)
        .retryOnConnectionFailure(true)
        .build()
}

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

    httpClient.newCall(request).execute().use { response ->
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

private fun verifySession(token: String): Boolean {
    val request = Request.Builder()
        .url("${BuildConfig.API_BASE_URL}api/profile/me")
        .addHeader("Authorization", "Bearer $token")
        .get()
        .build()
    httpClient.newCall(request).execute().use { response -> return response.isSuccessful }
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

    httpClient.newCall(request).execute().use { response ->
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
    httpClient.newCall(request).execute().use { response ->
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
    httpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IllegalStateException("Create reminder failed (${response.code}) ${response.body?.string().orEmpty()}")
    }
}

private fun fetchReminders(token: String): List<SimpleReminder> {
    val request = Request.Builder()
        .url("${BuildConfig.API_BASE_URL}api/reminders")
        .addHeader("Authorization", "Bearer $token")
        .get()
        .build()
    httpClient.newCall(request).execute().use { response ->
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
    httpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IllegalStateException("Create appointment failed (${response.code})")
    }
}

private fun fetchAppointments(token: String): List<SimpleAppointment> {
    val request = Request.Builder()
        .url("${BuildConfig.API_BASE_URL}api/appointments")
        .addHeader("Authorization", "Bearer $token")
        .get()
        .build()
    httpClient.newCall(request).execute().use { response ->
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

private fun fetchProfile(token: String): UserProfile {
    val request = Request.Builder()
        .url("${BuildConfig.API_BASE_URL}api/profile/me")
        .addHeader("Authorization", "Bearer $token")
        .get()
        .build()
    httpClient.newCall(request).execute().use { response ->
        val body = response.body?.string().orEmpty()
        if (!response.isSuccessful) throw IllegalStateException("Fetch profile failed (${response.code})")
        val json = JSONObject(body)
        val ec = json.optJSONObject("emergencyContact") ?: JSONObject()
        val allergiesArr = json.optJSONArray("allergies")
        val allergies = buildList {
            if (allergiesArr != null) for (i in 0 until allergiesArr.length()) add(allergiesArr.optString(i))
        }
        return UserProfile(
            name = json.optString("name"),
            email = json.optString("email"),
            bloodGroup = json.optString("bloodGroup"),
            age = if (json.has("age") && !json.isNull("age")) json.optInt("age").toString() else "",
            allergies = allergies,
            emergencyName = ec.optString("name"),
            emergencyPhone = ec.optString("phone"),
            emergencyRelation = ec.optString("relation")
        )
    }
}

private fun updateProfile(token: String, name: String, bloodGroup: String, age: Int?) {
    val payload = JSONObject().put("name", name).put("bloodGroup", bloodGroup)
    if (age != null) payload.put("age", age)
    val request = Request.Builder()
        .url("${BuildConfig.API_BASE_URL}api/profile/me")
        .addHeader("Authorization", "Bearer $token")
        .put(payload.toString().toRequestBody("application/json".toMediaType()))
        .build()
    httpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IllegalStateException("Update profile failed (${response.code}): ${response.body?.string().orEmpty()}")
    }
}

private fun updateProfileFull(
    token: String,
    name: String,
    bloodGroup: String,
    age: Int?,
    allergies: List<String>,
    emergencyName: String,
    emergencyPhone: String,
    emergencyRelation: String
) {
    val payload = JSONObject()
        .put("name", name)
        .put("bloodGroup", bloodGroup)
        .put("allergies", JSONArray(allergies))
        .put("emergencyContact", JSONObject()
            .put("name", emergencyName)
            .put("phone", emergencyPhone)
            .put("relation", emergencyRelation)
        )
    if (age != null) payload.put("age", age)
    val request = Request.Builder()
        .url("${BuildConfig.API_BASE_URL}api/profile/me")
        .addHeader("Authorization", "Bearer $token")
        .put(payload.toString().toRequestBody("application/json".toMediaType()))
        .build()
    httpClient.newCall(request).execute().use { response ->
        if (!response.isSuccessful) throw IllegalStateException("Update profile failed (${response.code}): ${response.body?.string().orEmpty()}")
    }
}

private data class GoogleAuthResult(val token: String, val isNewUser: Boolean, val name: String, val email: String)

private fun loginWithGoogle(idToken: String): GoogleAuthResult {
    val payload = JSONObject().put("idToken", idToken)
    val request = Request.Builder()
        .url("${BuildConfig.API_BASE_URL}api/auth/google")
        .post(payload.toString().toRequestBody("application/json".toMediaType()))
        .build()
    try {
        httpClient.newCall(request).execute().use { response ->
            val body = response.body?.string().orEmpty()
            if (!response.isSuccessful) throw IllegalStateException("Google auth failed (${response.code}): $body")
            val json = JSONObject(body)
            val token = json.optString("token")
            if (token.isBlank()) throw IllegalStateException("Auth token missing in response")
            val user = json.optJSONObject("user") ?: JSONObject()
            return GoogleAuthResult(
                token = token,
                isNewUser = json.optBoolean("isNewUser", false),
                name = user.optString("name"),
                email = user.optString("email")
            )
        }
    } catch (_: SocketTimeoutException) {
        throw IllegalStateException("Request timed out. Render backend may be cold-starting; wait 20-40 seconds and try again.")
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

private fun saveUserInfo(context: Context, name: String, email: String) {
    context.getSharedPreferences("medivault_prefs", Context.MODE_PRIVATE)
        .edit()
        .putString("user_name", name)
        .putString("user_email", email)
        .apply()
}

private fun loadUserName(context: Context): String? =
    context.getSharedPreferences("medivault_prefs", Context.MODE_PRIVATE).getString("user_name", null)

private fun loadUserEmail(context: Context): String? =
    context.getSharedPreferences("medivault_prefs", Context.MODE_PRIVATE).getString("user_email", null)

private fun clearUserInfo(context: Context) {
    context.getSharedPreferences("medivault_prefs", Context.MODE_PRIVATE)
        .edit()
        .remove("user_name")
        .remove("user_email")
        .apply()
}
