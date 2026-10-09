package me.ash.reader.ui.page.home.reading

import android.Manifest
import android.content.pm.PackageManager
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.MutableTransitionState
import androidx.compose.animation.core.rememberTransition
import androidx.compose.animation.core.animateFloat
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ExperimentalMaterial3ExpressiveApi
import androidx.compose.foundation.background
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.foundation.Image
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.safeDrawingPadding
import androidx.compose.foundation.layout.wrapContentSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Close
import androidx.compose.material.icons.outlined.MoreHoriz
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.IconButtonDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalView
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import androidx.compose.ui.window.DialogWindowProvider
import androidx.core.content.ContextCompat
import androidx.core.view.HapticFeedbackConstantsCompat
import coil.compose.rememberAsyncImagePainter
import coil.request.ImageRequest
import me.ash.reader.R
import me.ash.reader.ui.ext.extractDomain
import me.ash.reader.ui.ext.showToast
import me.saket.telephoto.zoomable.ZoomSpec
import me.saket.telephoto.zoomable.ZoomableContentLocation
import me.saket.telephoto.zoomable.rememberZoomableState
import me.saket.telephoto.zoomable.zoomable

data class ImageData(val imageUrl: String = "", val altText: String = "")

@OptIn(ExperimentalMaterial3ExpressiveApi::class)
@Composable
fun ReaderImageViewer(
    imageData: ImageData,
    onDownloadImage: (String) -> Unit,
    onDismissRequest: () -> Unit = {},
) {
    val visibleState = remember { MutableTransitionState(false) }
    val latestDismiss by rememberUpdatedState(onDismissRequest)
    var dismissRequested by remember { mutableStateOf(false) }
    var dismissDelivered by remember { mutableStateOf(false) }
    val requestDismiss: () -> Unit = {
        dismissRequested = true
        visibleState.targetState = false
    }
    LaunchedEffect(Unit) { if (!dismissRequested) visibleState.targetState = true }
    LaunchedEffect(visibleState.isIdle, visibleState.currentState, dismissRequested) {
        if (dismissRequested && !dismissDelivered && visibleState.isIdle && !visibleState.currentState) {
            dismissDelivered = true
            latestDismiss()
        }
    }
    val transition = rememberTransition(visibleState, label = "reader-image")
    val shade by transition.animateFloat(
        transitionSpec = { MaterialTheme.motionScheme.fastEffectsSpec() }, label = "image-shade",
    ) { if (it) 1f else 0f }
    val scale by transition.animateFloat(
        transitionSpec = { MaterialTheme.motionScheme.defaultSpatialSpec() }, label = "image-scale",
    ) { if (it) 1f else 0.97f }
    Dialog(
        onDismissRequest = requestDismiss,
        properties =
            DialogProperties(usePlatformDefaultWidth = false, decorFitsSystemWindows = false),
    ) {
        Box(modifier = Modifier.fillMaxSize().background(Color.Black.copy(alpha = shade.coerceIn(0f, 1f)))) {
            val view = LocalView.current
            val context = LocalContext.current

            val dialogWindowProvider = view.parent as? DialogWindowProvider
            dialogWindowProvider?.window?.setDimAmount(0f)
            dialogWindowProvider?.window?.setWindowAnimations(0)

            val zoomableState = rememberZoomableState(zoomSpec = ZoomSpec(maxZoomFactor = 4f))

            val painter =
                rememberAsyncImagePainter(
                    ImageRequest.Builder(LocalContext.current)
                        .addHeader("Referer", imageData.imageUrl.extractDomain() ?: "")
                        .data(data = imageData.imageUrl)
                        .build()
                )

            var expanded by remember { mutableStateOf(false) }

            LaunchedEffect(painter.intrinsicSize) {
                zoomableState.setContentLocation(
                    ZoomableContentLocation.scaledInsideAndCenterAligned(painter.intrinsicSize)
                )
            }

            Image(
                painter = painter,
                contentDescription = imageData.altText,
                modifier =
                    Modifier.zoomable(
                            state = zoomableState,
                            clipToBounds = true,
                            onClick = { requestDismiss() },
                            onLongClick = { expanded = true },
                        )
                        .fillMaxSize()
                        .graphicsLayer { alpha = shade; scaleX = scale; scaleY = scale },
                alignment = Alignment.Center,
                contentScale = ContentScale.Inside,
            )

            val launcher =
                rememberLauncherForActivityResult(
                    contract = ActivityResultContracts.RequestPermission(),
                    onResult = { result ->
                        if (result) {
                            onDownloadImage(imageData.imageUrl)
                        } else {
                            context.showToast(context.getString(R.string.permission_denied))
                        }
                    },
                )

            Row(
                modifier = Modifier.fillMaxWidth().safeDrawingPadding().align(Alignment.TopCenter).graphicsLayer { alpha = shade },
                verticalAlignment = Alignment.CenterVertically,
            ) {
                IconButton(
                    onClick = {
                        view.performHapticFeedback(HapticFeedbackConstantsCompat.KEYBOARD_TAP)
                        requestDismiss()
                    },
                    colors =
                        IconButtonDefaults.iconButtonColors(
                            containerColor = Color.Black.copy(alpha = 0.5f),
                            contentColor = Color.White,
                        ),
                    modifier = Modifier.padding(4.dp),
                ) {
                    Icon(
                        imageVector = Icons.Outlined.Close,
                        contentDescription = stringResource(id = R.string.close),
                    )
                }

                Spacer(Modifier.weight(1f))

                Box(modifier = Modifier.wrapContentSize(Alignment.TopEnd)) {
                    DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
                        DropdownMenuItem(
                            text = { Text(text = stringResource(id = R.string.save)) },
                            onClick = {
                                val isStoragePermissionGranted =
                                    ContextCompat.checkSelfPermission(
                                        context,
                                        Manifest.permission.WRITE_EXTERNAL_STORAGE,
                                    ) == PackageManager.PERMISSION_GRANTED

                                if (Build.VERSION.SDK_INT > 28 || isStoragePermissionGranted) {
                                    onDownloadImage(imageData.imageUrl)
                                } else {
                                    launcher.launch(Manifest.permission.WRITE_EXTERNAL_STORAGE)
                                }
                                expanded = false
                            },
                        )
                    }
                    IconButton(
                        onClick = { expanded = true },
                        colors =
                            IconButtonDefaults.iconButtonColors(
                                containerColor = Color.Black.copy(alpha = 0.5f),
                                contentColor = Color.White,
                            ),
                        modifier = Modifier.padding(4.dp),
                    ) {
                        Icon(
                            imageVector = Icons.Outlined.MoreHoriz,
                            contentDescription = stringResource(id = R.string.more),
                        )
                    }
                }
            }
        }
    }
}
