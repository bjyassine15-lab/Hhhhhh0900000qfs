package com.example

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Bundle
import android.util.Log
import android.widget.Toast
import androidx.activity.ComponentActivity
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.PickVisualMediaRequest
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.core.*
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.scaleIn
import androidx.compose.animation.scaleOut
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.ExperimentalFoundationApi
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.painterResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.input.KeyboardType
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.core.content.ContextCompat
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import coil.compose.AsyncImage
import com.example.ui.theme.MyApplicationTheme
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream

class MainActivity : ComponentActivity() {
    private val viewModel: VoiceDialerViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()

        setContent {
            MyApplicationTheme {
                val context = LocalContext.current
                val callIntentEvent = viewModel.callIntentEvent

                // Handle call intents triggered from voice matches
                LaunchedEffect(Unit) {
                    callIntentEvent.collectLatest { phoneNumber ->
                        triggerPhoneCall(context, phoneNumber)
                    }
                }

                Surface(
                    modifier = Modifier.fillMaxSize(),
                    color = Color(0xFF101012) // Luxury dark canvas
                ) {
                    AppNavigation(viewModel)
                }
            }
        }
    }

    private fun triggerPhoneCall(context: Context, phoneNumber: String) {
        val hasCallPermission = ContextCompat.checkSelfPermission(
            context,
            Manifest.permission.CALL_PHONE
        ) == PackageManager.PERMISSION_GRANTED

        if (hasCallPermission) {
            try {
                val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:$phoneNumber")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(intent)
            } catch (e: Exception) {
                // Fallback to dialer if direct call fails
                val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber")).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK
                }
                context.startActivity(dialIntent)
            }
        } else {
            // Permission not granted, fall back to dialer immediately
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phoneNumber")).apply {
                flags = Intent.FLAG_ACTIVITY_NEW_TASK
            }
            context.startActivity(dialIntent)
        }
    }
}

/**
 * Main routing state controller. Switch between Home (Elderly) and Admin screens.
 */
@Composable
fun AppNavigation(viewModel: VoiceDialerViewModel) {
    val isAdminMode by viewModel.isAdminMode.collectAsStateWithLifecycle()

    if (isAdminMode) {
        AdminPanelScreen(
            viewModel = viewModel,
            onExitAdmin = { viewModel.setAdminMode(false) }
        )
    } else {
        HomeScreen(
            viewModel = viewModel,
            onOpenAdmin = { viewModel.setAdminMode(true) }
        )
    }
}

/**
 * 1. Home Screen (Elderly & Illiterate Mode)
 * - Completely free of text labels for contacts.
 * - Massive grid of contact pictures.
 * - Gigantic white record button at bottom center that turns red and pulses when active.
 * - Secret PIN dialog to enter Admin Panel.
 */
@OptIn(ExperimentalFoundationApi::class)
@Composable
fun HomeScreen(
    viewModel: VoiceDialerViewModel,
    onOpenAdmin: () -> Unit
) {
    val context = LocalContext.current
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val isRecording by viewModel.isRecording.collectAsStateWithLifecycle()
    val isTtsSpeaking by viewModel.isTtsSpeaking.collectAsStateWithLifecycle()

    var showPinDialog by remember { mutableStateOf(false) }
    var enteredPin by remember { mutableStateOf("") }
    var pinError by remember { mutableStateOf(false) }

    // Request permissions launcher
    val permissionsLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val recordGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val callGranted = permissions[Manifest.permission.CALL_PHONE] ?: false
        if (!recordGranted) {
            Toast.makeText(context, "مطلوب إذن الميكروفون لاستخدام التطبيق صوتياً", Toast.LENGTH_LONG).show()
        }
    }

    LaunchedEffect(Unit) {
        // Pre-request permissions
        permissionsLauncher.launch(
            arrayOf(
                Manifest.permission.RECORD_AUDIO,
                Manifest.permission.CALL_PHONE
            )
        )
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFF101012),
        topBar = {
            // Elegant, silent Top Bar with a subtle hidden lock button
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Header icon / ambient indicator (clicking/long-pressing triggers secret entrance)
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E1E22))
                        .combinedClickable(
                            onLongClick = { showPinDialog = true },
                            onClick = { /* subtle feedback */ }
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = Icons.Default.Hearing,
                        contentDescription = "بصمة صوتية",
                        tint = Color(0xFFFFB300), // Rich gold amber accent
                        modifier = Modifier.size(24.dp)
                    )
                }

                // Invisible title or clean ambient indicator
                Box(
                    modifier = Modifier
                        .height(32.dp)
                        .widthIn(min = 100.dp)
                        .alpha(0.8f)
                        .clip(RoundedCornerShape(16.dp))
                        .background(
                            if (isTtsSpeaking) Brush.linearGradient(
                                listOf(Color(0xFFFFB300), Color(0xFFFF3D00))
                            ) else Brush.linearGradient(
                                listOf(Color(0xFF2C2C35), Color(0xFF1E1E22))
                            )
                        ),
                    contentAlignment = Alignment.Center
                ) {
                    if (isTtsSpeaking) {
                        Text(
                            text = "جاري التحدث...",
                            color = Color.White,
                            fontSize = 12.sp,
                            fontWeight = FontWeight.Medium,
                            modifier = Modifier.padding(horizontal = 16.dp)
                        )
                    } else {
                        Spacer(modifier = Modifier.size(1.dp))
                    }
                }

                // Subtle lock button for the admin
                IconButton(
                    onClick = { showPinDialog = true },
                    modifier = Modifier
                        .size(48.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E1E22))
                        .testTag("admin_lock_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.Lock,
                        contentDescription = "الإعدادات",
                        tint = Color.White.copy(alpha = 0.5f),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }
        },
        bottomBar = {
            // Massive recording button container at bottom center
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .navigationBarsPadding()
                    .padding(bottom = 36.dp),
                contentAlignment = Alignment.Center
            ) {
                // Pulsing Background Effect
                if (isRecording) {
                    val infiniteTransition = rememberInfiniteTransition(label = "pulse")
                    val scale by infiniteTransition.animateFloat(
                        initialValue = 1.0f,
                        targetValue = 1.35f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(850, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "scale"
                    )
                    val alpha by infiniteTransition.animateFloat(
                        initialValue = 0.6f,
                        targetValue = 0.0f,
                        animationSpec = infiniteRepeatable(
                            animation = tween(850, easing = FastOutSlowInEasing),
                            repeatMode = RepeatMode.Reverse
                        ),
                        label = "alpha"
                    )

                    Box(
                        modifier = Modifier
                            .size(100.dp)
                            .scale(scale)
                            .alpha(alpha)
                            .clip(CircleShape)
                            .background(Color(0xFFFF3D00))
                    )
                }

                // Central Active Mic Button
                Box(
                    modifier = Modifier
                        .size(96.dp)
                        .shadow(16.dp, CircleShape)
                        .clip(CircleShape)
                        .background(
                            if (isRecording) Color(0xFFFF3D00) else Color.White
                        )
                        .clickable {
                            // Check permission prior to recording
                            val hasMicPermission = ContextCompat.checkSelfPermission(
                                context,
                                Manifest.permission.RECORD_AUDIO
                            ) == PackageManager.PERMISSION_GRANTED

                            if (hasMicPermission) {
                                viewModel.toggleVoiceMatchRecording()
                            } else {
                                Toast
                                    .makeText(
                                        context,
                                        "يجب تفعيل إذن الميكروفون من إعدادات الهاتف",
                                        Toast.LENGTH_LONG
                                    )
                                    .show()
                                permissionsLauncher.launch(arrayOf(Manifest.permission.RECORD_AUDIO))
                            }
                        }
                        .testTag("main_mic_button"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isRecording) Icons.Default.MicNone else Icons.Default.Mic,
                        contentDescription = "تسجيل وبحث صوتي",
                        tint = if (isRecording) Color.White else Color(0xFF101012),
                        modifier = Modifier.size(44.dp)
                    )
                }
            }
        }
    ) { innerPadding ->
        // Main Screen Grid of Pictures
        Box(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            if (contacts.isEmpty()) {
                // Empty state illustration
                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(32.dp),
                    verticalArrangement = Arrangement.Center,
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .size(120.dp)
                            .clip(RoundedCornerShape(32.dp))
                            .background(Color(0xFF1E1E22)),
                        contentAlignment = Alignment.Center
                    ) {
                        Icon(
                            imageVector = Icons.Default.PeopleOutline,
                            contentDescription = "لا يوجد جهات اتصال",
                            tint = Color.White.copy(alpha = 0.3f),
                            modifier = Modifier.size(64.dp)
                        )
                    }
                    Spacer(modifier = Modifier.height(24.dp))
                    Text(
                        text = "اضغط على القفل بالأعلى لإضافة جهات اتصال وصور",
                        color = Color.White.copy(alpha = 0.4f),
                        fontSize = 16.sp,
                        textAlign = TextAlign.Center,
                        lineHeight = 24.sp,
                        modifier = Modifier.padding(horizontal = 24.dp)
                    )
                }
            } else {
                // Large visual Grid
                LazyVerticalGrid(
                    columns = GridCells.Fixed(2),
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 16.dp),
                    contentPadding = PaddingValues(top = 8.dp, bottom = 120.dp),
                    horizontalArrangement = Arrangement.spacedBy(16.dp),
                    verticalArrangement = Arrangement.spacedBy(16.dp)
                ) {
                    items(contacts) { contact ->
                        ContactPictureCard(
                            contact = contact,
                            onClick = {
                                // Initiate CALL directly
                                val hasCallPermission = ContextCompat.checkSelfPermission(
                                    context,
                                    Manifest.permission.CALL_PHONE
                               ) == PackageManager.PERMISSION_GRANTED

                                if (hasCallPermission) {
                                    try {
                                        val intent = Intent(Intent.ACTION_CALL, Uri.parse("tel:${contact.phoneNumber}"))
                                        context.startActivity(intent)
                                    } catch (e: Exception) {
                                        val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${contact.phoneNumber}"))
                                        context.startActivity(dialIntent)
                                    }
                                } else {
                                    val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:${contact.phoneNumber}"))
                                    context.startActivity(dialIntent)
                                }
                            }
                        )
                    }
                }
            }
        }
    }

    // Passcode protection dialog
    if (showPinDialog) {
        AlertDialog(
            onDismissRequest = {
                showPinDialog = false
                enteredPin = ""
                pinError = false
            },
            containerColor = Color(0xFF1E1E22),
            title = {
                Text(
                    text = "لوحة تحكم المسؤول",
                    color = Color.White,
                    fontSize = 20.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Right
                )
            },
            text = {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = "الرجاء إدخال رمز المرور للدخول للإعدادات (الرمز الافتراضي: 1234)",
                        color = Color.White.copy(alpha = 0.7f),
                        fontSize = 14.sp,
                        textAlign = TextAlign.Right,
                        modifier = Modifier.padding(bottom = 16.dp)
                    )

                    OutlinedTextField(
                        value = enteredPin,
                        onValueChange = {
                            if (it.length <= 4) {
                                enteredPin = it
                                pinError = false
                            }
                        },
                        visualTransformation = PasswordVisualTransformation(),
                        keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.NumberPassword),
                        colors = OutlinedTextFieldDefaults.colors(
                            focusedBorderColor = Color(0xFFFFB300),
                            unfocusedBorderColor = Color.White.copy(alpha = 0.2f),
                            focusedTextColor = Color.White,
                            unfocusedTextColor = Color.White
                        ),
                        singleLine = true,
                        placeholder = { Text("••••", color = Color.White.copy(alpha = 0.3f)) },
                        isError = pinError,
                        modifier = Modifier
                            .fillMaxWidth()
                            .testTag("pin_input_field")
                    )

                    if (pinError) {
                        Text(
                            text = "رمز المرور خاطئ! حاول مجدداً.",
                            color = Color(0xFFFF3D00),
                            fontSize = 12.sp,
                            modifier = Modifier.padding(top = 4.dp),
                            textAlign = TextAlign.Right
                        )
                    }
                }
            },
            confirmButton = {
                Button(
                    onClick = {
                        if (enteredPin == "1234") {
                            showPinDialog = false
                            enteredPin = ""
                            onOpenAdmin()
                        } else {
                            pinError = true
                        }
                    },
                    colors = ButtonDefaults.buttonColors(containerColor = Color(0xFFFFB300))
                ) {
                    Text("دخول", color = Color.Black, fontWeight = FontWeight.Bold)
                }
            },
            dismissButton = {
                TextButton(
                    onClick = {
                        showPinDialog = false
                        enteredPin = ""
                        pinError = false
                    }
                ) {
                    Text("إلغاء", color = Color.White.copy(alpha = 0.6f))
                }
            }
        )
    }
}

/**
 * Large tactile visual photo card for contact. Zero-text, full focus on portrait.
 */
@Composable
fun ContactPictureCard(
    contact: Contact,
    onClick: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(24.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E22)),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
        modifier = Modifier
            .fillMaxWidth()
            .aspectRatio(1f) // perfectly square
            .shadow(8.dp, RoundedCornerShape(24.dp))
            .clickable { onClick() }
            .testTag("contact_card_${contact.id}")
    ) {
        Box(modifier = Modifier.fillMaxSize()) {
            if (contact.photoUri != null) {
                AsyncImage(
                    model = Uri.parse(contact.photoUri),
                    contentDescription = null,
                    contentScale = ContentScale.Crop,
                    modifier = Modifier.fillMaxSize()
                )
            } else {
                // Beautiful gradient fallback with contact's initial letter in Arabic
                val initial = contact.name.trim().firstOrNull()?.toString() ?: "•"
                val gradient = remember {
                    val colors = listOf(
                        Color(0xFF2C3E50), Color(0xFF3498DB),
                        Color(0xFF16A085), Color(0xFF2ECC71),
                        Color(0xFFD35400), Color(0xFFE67E22),
                        Color(0xFF8E44AD), Color(0xFF9B59B6)
                    ).shuffled()
                    Brush.linearGradient(listOf(colors[0], colors[1]))
                }

                Box(
                    modifier = Modifier
                        .fillMaxSize()
                        .background(gradient),
                    contentAlignment = Alignment.Center
                ) {
                    Text(
                        text = initial,
                        color = Color.White,
                        fontSize = 56.sp,
                        fontWeight = FontWeight.Bold
                    )
                }
            }

            // Minimalist decorative calling touch ripple indicator at bottom
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    .align(Alignment.BottomCenter)
                    .background(
                        Brush.verticalGradient(
                            listOf(Color.Transparent, Color.Black.copy(alpha = 0.5f))
                        )
                    )
                    .padding(8.dp),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = Icons.Default.PhoneEnabled,
                    contentDescription = "اتصال مباشر",
                    tint = Color.White.copy(alpha = 0.8f),
                    modifier = Modifier.size(20.dp)
                )
            }
        }
    }
}

/**
 * 4. Admin Panel & Settings Screen
 * - Complete CRUD system for contacts.
 * - Minimum match sensitivity slider (0.50 to 0.95).
 * - Live audio voice template register with visual state and playback verify.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun AdminPanelScreen(
    viewModel: VoiceDialerViewModel,
    onExitAdmin: () -> Unit
) {
    val context = LocalContext.current
    val contacts by viewModel.contacts.collectAsStateWithLifecycle()
    val sensitivityThreshold by viewModel.sensitivityThreshold.collectAsStateWithLifecycle()
    val isRegisterRecording by viewModel.isRegisterRecording.collectAsStateWithLifecycle()
    val registeredEmbedding by viewModel.registeredEmbedding.collectAsStateWithLifecycle()

    var contactName by remember { mutableStateOf("") }
    var contactPhone by remember { mutableStateOf("") }
    var selectedImageUri by remember { mutableStateOf<Uri?>(null) }
    var editingContactId by remember { mutableStateOf(0) }

    // Launcher for modern PhotoPicker
    val photoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.PickVisualMedia()
    ) { uri ->
        if (uri != null) {
            // Save image locally immediately to avoid permission loss!
            val localUri = copyUriToInternalStorage(context, uri)
            selectedImageUri = localUri
        }
    }

    Scaffold(
        modifier = Modifier.fillMaxSize(),
        containerColor = Color(0xFF101012),
        topBar = {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .statusBarsPadding()
                    .padding(horizontal = 24.dp, vertical = 12.dp),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically
            ) {
                // Exit/Back button
                IconButton(
                    onClick = onExitAdmin,
                    modifier = Modifier
                        .size(44.dp)
                        .clip(CircleShape)
                        .background(Color(0xFF1E1E22))
                        .testTag("admin_back_button")
                ) {
                    Icon(
                        imageVector = Icons.Default.ArrowBack,
                        contentDescription = "رجوع",
                        tint = Color.White
                    )
                }

                Text(
                    text = "لوحة التحكم بالإعدادات",
                    color = Color.White,
                    fontSize = 18.sp,
                    fontWeight = FontWeight.Bold
                )

                // Placeholder to balance layout
                Spacer(modifier = Modifier.size(44.dp))
            }
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
                .imePadding(),
            contentPadding = PaddingValues(horizontal = 20.dp, vertical = 16.dp),
            verticalArrangement = Arrangement.spacedBy(24.dp)
        ) {
            // Section 1: Sensitivity Threshold Controller
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E22)),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        Row(
                            verticalAlignment = Alignment.CenterVertically,
                            horizontalArrangement = Arrangement.SpaceBetween,
                            modifier = Modifier.fillMaxWidth()
                        ) {
                            val strictness = when {
                                sensitivityThreshold <= 0.25f -> "صرامة فائقة"
                                sensitivityThreshold <= 0.40f -> "توازن مثالي"
                                else -> "مرونة عالية"
                            }
                            Text(
                                text = "${String.format(java.util.Locale.US, "%.2f", sensitivityThreshold)} ($strictness)",
                                color = Color(0xFFFFB300),
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                            Text(
                                text = "حد مسافة الخطأ المسموح بها (DTW)",
                                color = Color.White,
                                fontSize = 16.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }

                        Spacer(modifier = Modifier.height(12.dp))

                        Slider(
                            value = sensitivityThreshold,
                            onValueChange = { viewModel.updateSensitivityThreshold(it) },
                            valueRange = 0.15f..0.65f,
                            colors = SliderDefaults.colors(
                                thumbColor = Color(0xFFFFB300),
                                activeTrackColor = Color(0xFFFFB300),
                                inactiveTrackColor = Color.White.copy(alpha = 0.1f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .testTag("sensitivity_slider")
                        )

                        Text(
                            text = "يتحكم هذا الشريط في معيار القبول لخوارزمية Dynamic Time Warping. خفض القيمة (مثلاً 0.25) يفرض مطابقة صارمة جداً للبصمة الصوتية، بينما رفعها يزيد المرونة ليناسب النطق السريع أو المدود الصوتية الطويلة لكبار السن.",
                            color = Color.White.copy(alpha = 0.5f),
                            fontSize = 12.sp,
                            lineHeight = 18.sp,
                            textAlign = TextAlign.Right,
                            modifier = Modifier.padding(top = 8.dp)
                        )
                    }
                }
            }

            // Section 2: Contact Creator / Editor Form
            item {
                Card(
                    shape = RoundedCornerShape(24.dp),
                    colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E22)),
                    border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
                    modifier = Modifier.fillMaxWidth()
                ) {
                    Column(
                        modifier = Modifier.padding(20.dp),
                        horizontalAlignment = Alignment.End
                    ) {
                        Text(
                            text = if (editingContactId == 0) "إضافة جهة اتصال جديدة" else "تعديل جهة الاتصال",
                            color = Color.White,
                            fontSize = 16.sp,
                            fontWeight = FontWeight.Bold,
                            modifier = Modifier.padding(bottom = 16.dp)
                        )

                        // Name field
                        OutlinedTextField(
                            value = contactName,
                            onValueChange = { contactName = it },
                            label = { Text("الاسم الكامل (للتأكيد الصوتي)", textAlign = TextAlign.Right) },
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFFFB300),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedLabelColor = Color(0xFFFFB300),
                                unfocusedLabelColor = Color.White.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp)
                                .testTag("add_contact_name")
                        )

                        // Phone field
                        OutlinedTextField(
                            value = contactPhone,
                            onValueChange = { contactPhone = it },
                            label = { Text("رقم الهاتف", textAlign = TextAlign.Right) },
                            singleLine = true,
                            keyboardOptions = KeyboardOptions(keyboardType = KeyboardType.Phone),
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = Color(0xFFFFB300),
                                unfocusedBorderColor = Color.White.copy(alpha = 0.1f),
                                focusedTextColor = Color.White,
                                unfocusedTextColor = Color.White,
                                focusedLabelColor = Color(0xFFFFB300),
                                unfocusedLabelColor = Color.White.copy(alpha = 0.5f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 16.dp)
                                .testTag("add_contact_phone")
                        )

                        // Row of photo pick and voice template match
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .padding(bottom = 20.dp),
                            horizontalArrangement = Arrangement.spacedBy(12.dp)
                        ) {
                            // Voice Template Button (Left)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(100.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(
                                        if (isRegisterRecording) Color(0xFFFF3D00).copy(alpha = 0.1f)
                                        else Color.White.copy(alpha = 0.03f)
                                    )
                                    .clickable { viewModel.toggleRegisterRecording() }
                                    .testTag("register_voice_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                    Icon(
                                        imageVector = if (isRegisterRecording) Icons.Default.MicNone else Icons.Default.KeyboardVoice,
                                        contentDescription = "تسجيل البصمة الصوتية",
                                        tint = if (isRegisterRecording) Color(0xFFFF3D00)
                                        else if (registeredEmbedding != null) Color(0xFF2ECC71)
                                        else Color.White.copy(alpha = 0.8f),
                                        modifier = Modifier.size(32.dp)
                                    )
                                    Spacer(modifier = Modifier.height(8.dp))
                                    Text(
                                        text = if (isRegisterRecording) "جاري التسجيل..."
                                        else if (registeredEmbedding != null) "تم التسجيل بنجاح ✓"
                                        else "بصمة الصوت",
                                        color = if (isRegisterRecording) Color(0xFFFF3D00)
                                        else if (registeredEmbedding != null) Color(0xFF2ECC71)
                                        else Color.White.copy(alpha = 0.6f),
                                        fontSize = 12.sp,
                                        fontWeight = FontWeight.Medium
                                    )
                                }
                            }

                            // Photo Picker button (Right)
                            Box(
                                modifier = Modifier
                                    .weight(1f)
                                    .height(100.dp)
                                    .clip(RoundedCornerShape(16.dp))
                                    .background(Color.White.copy(alpha = 0.03f))
                                    .clickable {
                                        photoPickerLauncher.launch(
                                            PickVisualMediaRequest(ActivityResultContracts.PickVisualMedia.ImageOnly)
                                        )
                                    }
                                    .testTag("select_photo_button"),
                                contentAlignment = Alignment.Center
                            ) {
                                if (selectedImageUri != null) {
                                    AsyncImage(
                                        model = selectedImageUri,
                                        contentDescription = null,
                                        contentScale = ContentScale.Crop,
                                        modifier = Modifier.fillMaxSize()
                                    )
                                    // Visual overlay indicator
                                    Box(
                                        modifier = Modifier
                                            .fillMaxSize()
                                            .background(Color.Black.copy(alpha = 0.4f)),
                                        contentAlignment = Alignment.Center
                                    ) {
                                        Icon(
                                            imageVector = Icons.Default.PhotoLibrary,
                                            contentDescription = "تغيير الصورة",
                                            tint = Color.White
                                        )
                                    }
                                } else {
                                    Column(horizontalAlignment = Alignment.CenterHorizontally) {
                                        Icon(
                                            imageVector = Icons.Default.AddAPhoto,
                                            contentDescription = "إضافة صورة",
                                            tint = Color.White.copy(alpha = 0.8f),
                                            modifier = Modifier.size(32.dp)
                                        )
                                        Spacer(modifier = Modifier.height(8.dp))
                                        Text(
                                            text = "إضافة صورة شخصية",
                                            color = Color.White.copy(alpha = 0.6f),
                                            fontSize = 12.sp,
                                            fontWeight = FontWeight.Medium
                                        )
                                    }
                                }
                            }
                        }

                        // Submit Button
                        Button(
                            onClick = {
                                val embedding = registeredEmbedding
                                if (contactName.isBlank() || contactPhone.isBlank()) {
                                    Toast.makeText(context, "الرجاء إدخال الاسم ورقم الهاتف أولاً", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }
                                if (embedding == null) {
                                    Toast.makeText(context, "الرجاء تسجيل بصمة الصوت لجهة الاتصال", Toast.LENGTH_SHORT).show()
                                    return@Button
                                }

                                viewModel.saveContact(
                                    id = editingContactId,
                                    name = contactName,
                                    phone = contactPhone,
                                    photoUri = selectedImageUri,
                                    embedding = embedding
                                )

                                Toast.makeText(context, "تم حفظ جهة الاتصال بنجاح", Toast.LENGTH_SHORT).show()

                                // Reset local form state
                                contactName = ""
                                contactPhone = ""
                                selectedImageUri = null
                                editingContactId = 0
                            },
                            enabled = contactName.isNotBlank() && contactPhone.isNotBlank() && registeredEmbedding != null,
                            colors = ButtonDefaults.buttonColors(
                                containerColor = Color(0xFFFFB300),
                                disabledContainerColor = Color.White.copy(alpha = 0.05f)
                            ),
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(52.dp)
                                .testTag("save_contact_button")
                        ) {
                            Text(
                                text = if (editingContactId == 0) "حفظ جهة الاتصال" else "تحديث جهة الاتصال",
                                color = if (contactName.isNotBlank() && contactPhone.isNotBlank() && registeredEmbedding != null) Color.Black else Color.White.copy(alpha = 0.3f),
                                fontWeight = FontWeight.Bold,
                                fontSize = 16.sp
                            )
                        }

                        if (editingContactId != 0) {
                            Spacer(modifier = Modifier.height(8.dp))
                            TextButton(
                                onClick = {
                                    contactName = ""
                                    contactPhone = ""
                                    selectedImageUri = null
                                    editingContactId = 0
                                },
                                modifier = Modifier.fillMaxWidth()
                            ) {
                                Text("إلغاء التعديل", color = Color.White.copy(alpha = 0.6f))
                            }
                        }
                    }
                }
            }

            // Section 3: Registered Contacts CRUD list
            item {
                Text(
                    text = "جهات الاتصال المسجلة (${contacts.size})",
                    color = Color.White,
                    fontSize = 16.sp,
                    fontWeight = FontWeight.Bold,
                    modifier = Modifier.fillMaxWidth(),
                    textAlign = TextAlign.Right
                )
            }

            if (contacts.isEmpty()) {
                item {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 32.dp),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = "لا توجد جهات اتصال مسجلة بعد.",
                            color = Color.White.copy(alpha = 0.4f),
                            fontSize = 14.sp
                        )
                    }
                }
            } else {
                items(contacts, key = { it.id }) { contact ->
                    ContactCrudRow(
                        contact = contact,
                        onDelete = { viewModel.deleteContact(contact) },
                        onEdit = {
                            contactName = contact.name
                            contactPhone = contact.phoneNumber
                            selectedImageUri = contact.photoUri?.let { Uri.parse(it) }
                            editingContactId = contact.id
                            // Load saved embedding as editing state target
                            // (We don't need to re-record unless they want to)
                            viewModel.saveContact(
                                id = contact.id,
                                name = contact.name,
                                phone = contact.phoneNumber,
                                photoUri = selectedImageUri,
                                embedding = contact.getMfccSequence()
                            )
                        },
                        onPlayVoice = { viewModel.playContactVoice(contact) }
                    )
                }
            }
        }
    }
}

/**
 * Modern design CRUD card for each contact in the Admin settings list.
 */
@Composable
fun ContactCrudRow(
    contact: Contact,
    onDelete: () -> Unit,
    onEdit: () -> Unit,
    onPlayVoice: () -> Unit
) {
    Card(
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = Color(0xFF1E1E22)),
        border = BorderStroke(1.dp, Color.White.copy(alpha = 0.05f)),
        modifier = Modifier
            .fillMaxWidth()
            .testTag("admin_contact_row_${contact.id}")
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.SpaceBetween
        ) {
            // Actions (Left side)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(4.dp)
            ) {
                // Delete
                IconButton(onClick = onDelete) {
                    Icon(
                        imageVector = Icons.Default.Delete,
                        contentDescription = "حذف",
                        tint = Color(0xFFFF3D00),
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Edit
                IconButton(onClick = onEdit) {
                    Icon(
                        imageVector = Icons.Default.Edit,
                        contentDescription = "تعديل",
                        tint = Color.White.copy(alpha = 0.6f),
                        modifier = Modifier.size(20.dp)
                    )
                }

                // Play Audio signature (TTS check)
                IconButton(onClick = onPlayVoice) {
                    Icon(
                        imageVector = Icons.Default.VolumeUp,
                        contentDescription = "تشغيل الصوت للتجربة",
                        tint = Color(0xFFFFB300),
                        modifier = Modifier.size(20.dp)
                    )
                }
            }

            // Contact details (Right side)
            Row(
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(16.dp)
            ) {
                Column(horizontalAlignment = Alignment.End) {
                    Text(
                        text = contact.name,
                        color = Color.White,
                        fontSize = 15.sp,
                        fontWeight = FontWeight.Bold,
                        maxLines = 1,
                        overflow = TextOverflow.Ellipsis
                    )
                    Text(
                        text = contact.phoneNumber,
                        color = Color.White.copy(alpha = 0.5f),
                        fontSize = 12.sp,
                        fontWeight = FontWeight.Normal,
                        modifier = Modifier.padding(top = 2.dp)
                    )
                }

                // Mini Avatar
                Box(
                    modifier = Modifier
                        .size(48.dp)
                        .clip(RoundedCornerShape(12.dp))
                        .background(Color.White.copy(alpha = 0.05f))
                ) {
                    if (contact.photoUri != null) {
                        AsyncImage(
                            model = Uri.parse(contact.photoUri),
                            contentDescription = null,
                            contentScale = ContentScale.Crop,
                            modifier = Modifier.fillMaxSize()
                        )
                    } else {
                        val initial = contact.name.trim().firstOrNull()?.toString() ?: "•"
                        Box(
                            modifier = Modifier
                                .fillMaxSize()
                                .background(Color(0xFF2C3E50)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                text = initial,
                                color = Color.White,
                                fontSize = 20.sp,
                                fontWeight = FontWeight.Bold
                            )
                        }
                    }
                }
            }
        }
    }
}

/**
 * Helper function to copy selected file URI into local internal app storage folder.
 * Keeps access persistent and safe across app restarts.
 */
private fun copyUriToInternalStorage(context: Context, uri: Uri): Uri? {
    return try {
        val inputStream = context.contentResolver.openInputStream(uri) ?: return null
        val fileName = "contact_photo_${System.currentTimeMillis()}.jpg"
        val file = File(context.filesDir, fileName)
        FileOutputStream(file).use { outputStream ->
            inputStream.use { inputStream ->
                inputStream.copyTo(outputStream)
            }
        }
        Uri.fromFile(file)
    } catch (e: Exception) {
        Log.e("MainActivity", "Error copying image to internal storage", e)
        null
    }
}
