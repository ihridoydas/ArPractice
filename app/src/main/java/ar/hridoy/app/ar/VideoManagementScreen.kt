package ar.hridoy.app.ar

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.CameraAlt
import androidx.compose.material.icons.filled.VideoLibrary
import androidx.compose.material.icons.filled.PhotoLibrary
import java.io.FileOutputStream
import java.io.InputStream
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Edit
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.core.content.FileProvider
import androidx.hilt.navigation.compose.hiltViewModel
import ar.hridoy.app.common.model.AugmentedVideo
import coil.compose.AsyncImage
import coil.compose.SubcomposeAsyncImage
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun VideoManagementScreen(
    onBack: () -> Unit,
    viewModel: VideoManagementViewModel = hiltViewModel()
) {
    val videos by viewModel.videos.collectAsState()
    val isLoading by viewModel.isLoading.collectAsState()
    val error by viewModel.error.collectAsState()

    var showAddDialog by remember { mutableStateOf(false) }
    var editingVideo by remember { mutableStateOf<AugmentedVideo?>(null) }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Manage Videos") },
                navigationIcon = {
                    IconButton(onClick = onBack) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                    }
                }
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { showAddDialog = true }) {
                Icon(Icons.Default.Add, contentDescription = "Add Video")
            }
        }
    ) { padding ->
        Box(modifier = Modifier.padding(padding).fillMaxSize()) {
            if (isLoading && videos.isEmpty()) {
                CircularProgressIndicator(modifier = Modifier.align(Alignment.Center))
            } else {
                LazyColumn(modifier = Modifier.fillMaxSize()) {
                    items(videos) { video ->
                        VideoItem(
                            video = video,
                            onEdit = { editingVideo = it },
                            onDelete = { viewModel.deleteVideo(it) }
                        )
                    }
                }
            }

            error?.let {
                Snackbar(
                    modifier = Modifier.padding(16.dp).align(Alignment.BottomCenter),
                    containerColor = MaterialTheme.colorScheme.errorContainer,
                    contentColor = MaterialTheme.colorScheme.error
                ) {
                    Text(it)
                }
            }
        }
    }

    if (showAddDialog) {
        VideoDialog(
            onDismiss = { showAddDialog = false },
            onConfirm = { 
                viewModel.addVideo(it)
                showAddDialog = false
            }
        )
    }

    editingVideo?.let { video ->
        VideoDialog(
            video = video,
            onDismiss = { editingVideo = null },
            onConfirm = { 
                viewModel.updateVideo(it)
                editingVideo = null
            }
        )
    }
}

@Composable
fun VideoItem(
    video: AugmentedVideo,
    onEdit: (AugmentedVideo) -> Unit,
    onDelete: (AugmentedVideo) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(8.dp),
        elevation = CardDefaults.cardElevation(4.dp)
    ) {
        Row(
            modifier = Modifier
                .padding(8.dp)
                .fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            val imageModel = remember(video.imageAssetPath) {
                when {
                    video.imageAssetPath.startsWith("http") -> video.imageAssetPath
                    video.imageAssetPath.startsWith("/") -> video.imageAssetPath
                    else -> "file:///android_asset/${video.imageAssetPath}"
                }
            }
            SubcomposeAsyncImage(
                model = imageModel,
                contentDescription = video.name,
                modifier = Modifier.size(80.dp),
                contentScale = ContentScale.Crop,
                loading = {
                    Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
                        CircularProgressIndicator(Modifier.size(24.dp))
                    }
                },
                error = {
                    Icon(
                        imageVector = Icons.Default.Edit, // Fallback icon
                        contentDescription = "Error loading image",
                        modifier = Modifier.padding(16.dp),
                        tint = Color.Gray
                    )
                }
            )
            Spacer(modifier = Modifier.width(16.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(video.name, style = MaterialTheme.typography.titleMedium)
                Text("Active: ${video.active}", style = MaterialTheme.typography.bodySmall)
                Text(video.videoUrl, style = MaterialTheme.typography.bodySmall, maxLines = 1)
            }
            IconButton(onClick = { onEdit(video) }) {
                Icon(Icons.Default.Edit, contentDescription = "Edit")
            }
            IconButton(onClick = { onDelete(video) }) {
                Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red)
            }
        }
    }
}

@Composable
fun VideoDialog(
    video: AugmentedVideo? = null,
    onDismiss: () -> Unit,
    onConfirm: (AugmentedVideo) -> Unit
) {
    val context = LocalContext.current
    var name by remember { mutableStateOf(video?.name ?: "") }
    var imagePath by remember { mutableStateOf(video?.imageAssetPath ?: "") }
    var videoUrl by remember { mutableStateOf(video?.videoUrl ?: "") }
    var isActive by remember { mutableStateOf(video?.active ?: true) }

    var tempPhotoFile by remember { mutableStateOf<File?>(null) }

    val cameraLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.TakePicture(),
        onResult = { success ->
            if (success && tempPhotoFile != null) {
                imagePath = tempPhotoFile!!.absolutePath
            }
        }
    )

    val imagePickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? ->
            uri?.let {
                val storageDir = context.getExternalFilesDir("Images")
                if (storageDir?.exists() == false) storageDir.mkdirs()
                val fileName = "IMAGE_${System.currentTimeMillis()}.jpg"
                val file = File(storageDir, fileName)
                
                try {
                    context.contentResolver.openInputStream(it)?.use { input ->
                        FileOutputStream(file).use { output ->
                            input.copyTo(output)
                        }
                    }
                    imagePath = file.absolutePath
                } catch (e: Exception) {
                    // Handle error
                }
            }
        }
    )

    val videoPickerLauncher = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent(),
        onResult = { uri: Uri? ->
            uri?.let {
                // Copy the video to local storage to have a persistent path
                val storageDir = context.getExternalFilesDir("Videos")
                if (storageDir?.exists() == false) storageDir.mkdirs()
                val fileName = "VIDEO_${System.currentTimeMillis()}.mp4"
                val file = File(storageDir, fileName)
                
                try {
                    context.contentResolver.openInputStream(it)?.use { input ->
                        FileOutputStream(file).use { output ->
                            input.copyTo(output)
                        }
                    }
                    videoUrl = file.absolutePath
                } catch (e: Exception) {
                    // Handle error
                }
            }
        }
    )

    fun takePhoto() {
        val timeStamp = SimpleDateFormat("yyyyMMdd_HHmmss", Locale.getDefault()).format(Date())
        val storageDir = context.getExternalFilesDir("Images")
        if (storageDir?.exists() == false) storageDir.mkdirs()
        
        val file = File.createTempFile("JPEG_${timeStamp}_", ".jpg", storageDir)
        tempPhotoFile = file
        val uri = FileProvider.getUriForFile(
            context,
            "${context.packageName}.fileprovider",
            file
        )
        cameraLauncher.launch(uri)
    }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(if (video == null) "Add Video" else "Edit Video") },
        text = {
            Column {
                TextField(value = name, onValueChange = { name = it }, label = { Text("Name") })
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = imagePath, 
                        onValueChange = { imagePath = it }, 
                        label = { Text("Image URL/Path") },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { takePhoto() }) {
                        Icon(Icons.Default.CameraAlt, contentDescription = "Take Photo")
                    }
                    IconButton(onClick = { imagePickerLauncher.launch("image/*") }) {
                        Icon(Icons.Default.PhotoLibrary, contentDescription = "Pick Image")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    TextField(
                        value = videoUrl, 
                        onValueChange = { videoUrl = it }, 
                        label = { Text("Video URL/Path") },
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(onClick = { videoPickerLauncher.launch("video/*") }) {
                        Icon(Icons.Default.VideoLibrary, contentDescription = "Pick Video")
                    }
                }
                Spacer(modifier = Modifier.height(8.dp))
                Row(verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(checked = isActive, onCheckedChange = { isActive = it })
                    Text("Active")
                }
            }
        },
        confirmButton = {
            Button(onClick = {
                onConfirm(
                    AugmentedVideo(
                        id = video?.id ?: (System.currentTimeMillis() % 10000).toInt(),
                        name = name,
                        imageAssetPath = imagePath,
                        videoUrl = videoUrl,
                        active = isActive,
                        rowIndex = video?.rowIndex
                    )
                )
            }) {
                Text("Confirm")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
