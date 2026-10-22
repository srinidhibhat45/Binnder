@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.binnder.app

import android.Manifest
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.provider.Settings
import androidx.activity.ComponentActivity
import androidx.activity.compose.BackHandler
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.animation.core.animate
import androidx.compose.animation.core.spring
import androidx.compose.animation.Crossfade
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.animation.core.tween
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.LinearEasing
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectDragGestures
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.text.selection.SelectionContainer
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.automirrored.outlined.InsertDriveFile
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.viewmodel.compose.viewModel
import kotlinx.coroutines.launch
import kotlinx.coroutines.Job
import java.io.File
import java.text.DateFormat
import java.util.Date
import java.util.Locale
import kotlin.math.abs
import kotlin.math.ceil

class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent { BinnderTheme { BinnderApp(intent.getBooleanExtra("open_settings",false)) } }
    }
}

@Composable fun BinnderTheme(content: @Composable () -> Unit) {
    val context = LocalContext.current
    val dark = isSystemInDarkTheme()
    val colors = when {
        Build.VERSION.SDK_INT >= 31 && dark -> dynamicDarkColorScheme(context)
        Build.VERSION.SDK_INT >= 31 -> dynamicLightColorScheme(context)
        dark -> darkColorScheme(primary=Color(0xFF80D5C6),secondary=Color(0xFFB1CCC6),tertiary=Color(0xFFA9CBE3))
        else -> lightColorScheme(primary=Color(0xFF006B60),secondary=Color(0xFF4B635E),tertiary=Color(0xFF426277))
    }
    MaterialTheme(colorScheme=colors,content=content)
}

fun formatSize(bytes: Long): String {
    if(bytes < 1024) return "$bytes B"
    val units = arrayOf("KB","MB","GB","TB")
    var value = bytes.toDouble()/1024; var index = 0
    while(value >= 1024 && index < units.lastIndex) { value /= 1024; index++ }
    return String.format(Locale.getDefault(),"%.1f %s",value,units[index])
}
fun kindIcon(kind: FileKind): ImageVector = when(kind) {
    FileKind.ALL -> Icons.Outlined.FolderOpen
    FileKind.IMAGE -> Icons.Outlined.Image
    FileKind.VIDEO -> Icons.Outlined.Movie
    FileKind.AUDIO -> Icons.Outlined.AudioFile
    FileKind.PDF -> Icons.Outlined.PictureAsPdf
    FileKind.OTHER -> Icons.AutoMirrored.Outlined.InsertDriveFile
}

@Composable fun BinnderApp(openSettings: Boolean, vm: MainViewModel = viewModel()) {
    val state by vm.state.collectAsStateWithLifecycle()
    var tab by rememberSaveable { mutableIntStateOf(if(openSettings) 2 else 0) }
    var preview by remember { mutableStateOf<PhoneFile?>(null) }
    var confirm by remember { mutableStateOf<PhoneFile?>(null) }
    var purge by remember { mutableStateOf<Operation?>(null) }
    var details by remember { mutableStateOf<PhoneFile?>(null) }
    val lifecycle = LocalLifecycleOwner.current.lifecycle
    DisposableEffect(lifecycle) {
        val observer = LifecycleEventObserver { _,event -> if(event == Lifecycle.Event.ON_RESUME) vm.resume() }
        lifecycle.addObserver(observer)
        vm.resume()
        onDispose { lifecycle.removeObserver(observer) }
    }
    val pending = state.operations.filter { it.state in listOf("pending","purge_pending") }
    BackHandler(enabled=preview != null || tab != 0) { if(preview != null) preview=null else tab=0 }
    if(preview != null) {
        PreviewScreen(preview!!,onBack={ preview=null },
            pending={ UndoStrip(pending,state.now,state.actionBusy,vm::undo) })
    } else {
        Scaffold(
            topBar={ TopAppBar(title={ Text(when(tab) { 0 -> "Binnder"; 1 -> "Recycle bin"; else -> "Settings" }) },navigationIcon={
                if(tab != 0) IconButton(onClick={tab=0}) { Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back to files") }
            },actions={
                if(tab == 0) {
                    if(state.access) IconButton(onClick=vm::scan,enabled=!state.scanning && !state.actionBusy) { Icon(Icons.Outlined.Refresh,"Scan storage again") }
                    IconButton(onClick={tab=1}) { Icon(Icons.Outlined.DeleteOutline,"Open recycle bin") }
                    IconButton(onClick={tab=2}) { Icon(Icons.Outlined.Settings,"Open settings") }
                }
            }) },
            bottomBar={ Column(Modifier.navigationBarsPadding()) {
                UndoStrip(pending,state.now,state.actionBusy,vm::undo)
            } }
        ) { padding ->
                Column(Modifier.fillMaxSize().padding(padding)) {
                    state.message?.let { message ->
                        Surface(color=MaterialTheme.colorScheme.secondaryContainer) {
                            Row(Modifier.fillMaxWidth().padding(start=16.dp),verticalAlignment=Alignment.CenterVertically) {
                                Text(message,Modifier.weight(1f).padding(vertical=12.dp),style=MaterialTheme.typography.bodyMedium)
                                IconButton(onClick={ vm.message(null) }) { Icon(Icons.Outlined.Close,"Dismiss message") }
                            }
                        }
                    }
                    when(tab) {
                        0 -> ReviewScreen(state,vm,preview={ preview=it },permanent={ confirm=it },details={details=it})
                        1 -> BinScreen(state,vm,onPreview={ preview=it },onPurge={ purge=it })
                        2 -> SettingsScreen(state,vm)
                    }
                }
        }
    }
    confirm?.let { file -> DeleteDialog(file,onDismiss={confirm=null},onConfirm={confirm=null;vm.delete(file,true)}) }
    purge?.let { op -> DeleteDialog(op.file,onDismiss={purge=null},onConfirm={purge=null;vm.purge(op)}) }
    details?.let { file ->
        ModalBottomSheet(onDismissRequest={details=null}) {
            Column(Modifier.fillMaxWidth().verticalScroll(rememberScrollState()).padding(horizontal=24.dp).padding(bottom=32.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
                Text(file.name,style=MaterialTheme.typography.titleLarge)
                Text("${formatSize(file.size)} · ${file.kind.label}",style=MaterialTheme.typography.bodyLarge)
                SelectionContainer { Text(file.path,style=MaterialTheme.typography.bodyMedium) }
                Text("Modified ${DateFormat.getDateTimeInstance(DateFormat.MEDIUM,DateFormat.SHORT).format(Date(file.modified))}",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                FilledTonalButton(onClick={details=null;preview=file},Modifier.fillMaxWidth()) { Text("Open preview") }
            }
        }
    }
}

@Composable private fun ReviewScreen(state: AppState, vm: MainViewModel, preview:(PhoneFile)->Unit, permanent:(PhoneFile)->Unit,details:(PhoneFile)->Unit) {
    var filterName by rememberSaveable { mutableStateOf(FileKind.ALL.name) }
    val filter = FileKind.valueOf(filterName)
    val context = LocalContext.current
    if(!state.access) {
        Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.Center) {
            Icon(Icons.Outlined.FolderOpen,null,Modifier.size(64.dp),tint=MaterialTheme.colorScheme.primary)
            Spacer(Modifier.height(24.dp))
            Text("Make room.\nOne file at a time.",style=MaterialTheme.typography.headlineLarge)
            Spacer(Modifier.height(16.dp))
            Text("Review your largest files, see what’s inside, and decide what stays.",style=MaterialTheme.typography.bodyLarge)
            Spacer(Modifier.height(24.dp))
            GestureGuide()
            Spacer(Modifier.height(24.dp))
            Button(onClick={
                runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,Uri.parse("package:${context.packageName}"))) }
                    .onFailure { runCatching { context.startActivity(Intent(Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)) }.onFailure { vm.message("Open Android Settings → Special app access → All files access → Binnder.") } }
            },Modifier.fillMaxWidth()) { Text("Allow file access") }
            Spacer(Modifier.height(12.dp))
            Text("Files stay on your phone. Android keeps other apps’ private data protected. Binned files remain for 30 days and still use storage.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        return
    }
    Column(Modifier.fillMaxSize()) {
        Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()).padding(horizontal=20.dp),horizontalArrangement=Arrangement.spacedBy(8.dp)) {
            FileKind.entries.forEach { kind -> FilterChip(selected=filter==kind,onClick={filterName=kind.name},label={Text(kind.label)}) }
        }
        if(state.scanning) {
            LinearProgressIndicator(Modifier.fillMaxWidth().padding(top=8.dp))
            Box(Modifier.fillMaxSize(),contentAlignment=Alignment.Center) { Text("Finding your largest files…") }
            return
        }
        val files = remember(state.files,filter) { state.files.filter { filter == FileKind.ALL || it.kind == filter } }
        val file = files.firstOrNull()
        LaunchedEffect(file?.key,filter) { preloadFilePreviews(files.take(3)) }
        if(file == null) {
            Column(Modifier.fillMaxSize().padding(24.dp).verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
                Icon(Icons.Outlined.DoneAll,null,Modifier.size(64.dp),tint=MaterialTheme.colorScheme.primary)
                Spacer(Modifier.height(20.dp))
                Text(if(state.scanned) "You’re all caught up" else "Ready when you are",style=MaterialTheme.typography.headlineSmall)
                Spacer(Modifier.height(12.dp))
                Text("Kept files return after seven days. New or changed files appear on your next scan.",style=MaterialTheme.typography.bodyLarge)
                if(state.unreadable > 0) Text("${state.unreadable} folders could not be read. Protected app data is excluded.",Modifier.padding(top=12.dp),style=MaterialTheme.typography.bodySmall)
                OutlinedButton(onClick=vm::scan,Modifier.padding(top=20.dp)) { Text("Scan again") }
            }
            return
        }
            Column(Modifier.weight(1f).fillMaxWidth().padding(horizontal=12.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Row(Modifier.widthIn(max=760.dp).fillMaxWidth().padding(horizontal=4.dp,vertical=10.dp),horizontalArrangement=Arrangement.SpaceBetween) {
                    Text("Largest first",style=MaterialTheme.typography.titleSmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                    Text("${files.size} to review",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
                }
                BoxWithConstraints(Modifier.weight(1f).fillMaxWidth()) {
                    val previewHeight = (maxHeight - 168.dp).coerceIn(180.dp,760.dp)
                    val reviewScroll = rememberScrollState()
                    LaunchedEffect(file.key) { reviewScroll.scrollTo(0) }
                    Column(Modifier.fillMaxSize().verticalScroll(reviewScroll),horizontalAlignment=Alignment.CenterHorizontally) {
                        Crossfade(targetState=file,animationSpec=tween(180),label="Next file") { current ->
                            SwipeCard(current,previewHeight,state.actionBusy || current.key != file.key,onKeep={vm.keep(current)},onBin={vm.delete(current,false)},onPermanent={permanent(current)},onPreview={preview(current)},onDetails={details(current)})
                        }
                    }
                }
                Row(Modifier.widthIn(max=760.dp).fillMaxWidth().padding(top=12.dp,bottom=4.dp),horizontalArrangement=Arrangement.SpaceEvenly,verticalAlignment=Alignment.CenterVertically) {
                    ActionButton("Keep",Icons.AutoMirrored.Outlined.ArrowBack,false,!state.actionBusy) { vm.keep(file) }
                    ActionButton("Delete forever",Icons.Outlined.DeleteForever,true,!state.actionBusy,small=true) { permanent(file) }
                    ActionButton("Bin",Icons.Outlined.DeleteOutline,false,!state.actionBusy,filled=true) { vm.delete(file,false) }
                }
                Text("Left: Keep · Up: Delete forever · Right: Bin",Modifier.padding(vertical=8.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,textAlign=androidx.compose.ui.text.style.TextAlign.Center)
                if(state.unreadable > 0) Text("${state.unreadable} folders could not be read.",Modifier.padding(bottom=8.dp),style=MaterialTheme.typography.bodySmall)
            }
    }
}

@Composable private fun SwipeCard(file: PhoneFile, height: androidx.compose.ui.unit.Dp, busy: Boolean,
    onKeep:()->Unit,onBin:()->Unit,onPermanent:()->Unit,onPreview:()->Unit,onDetails:()->Unit) {
    var x by remember(file.key) { mutableFloatStateOf(0f) }
    var y by remember(file.key) { mutableFloatStateOf(0f) }
    val scope = rememberCoroutineScope()
    val haptics = LocalHapticFeedback.current
    var decisionSent by remember(file.key) { mutableStateOf(false) }
    var hapticSent by remember(file.key) { mutableStateOf(false) }
    var settling by remember(file.key) { mutableStateOf<Job?>(null) }
    fun settleCard() {
        settling?.cancel()
        settling=scope.launch {
            val startX=x;val startY=y
            animate(1f,0f,animationSpec=spring()) { value,_ -> x=startX*value;y=startY*value }
            decisionSent=false
        }
    }
    val threshold = with(LocalDensity.current) { 96.dp.toPx() }
    val currentBusy by rememberUpdatedState(busy)
    val latestKeep by rememberUpdatedState(onKeep)
    val latestBin by rememberUpdatedState(onBin)
    val latestPermanent by rememberUpdatedState(onPermanent)
    val crossedThreshold = if(abs(x) > threshold && abs(x)>abs(y)) if(x>0) "bin" else "keep" else if(y < -threshold && abs(y)>abs(x)*1.2f) "permanent" else null
    LaunchedEffect(crossedThreshold) {
        if(crossedThreshold != null && !busy && !decisionSent && !hapticSent) {
            hapticSent=true
            haptics.performHapticFeedback(HapticFeedbackType.LongPress)
        }
    }
    LaunchedEffect(busy) {
        if(!busy && decisionSent) {
            settleCard()
        }
    }
    val direction = if(y < -threshold/3 && abs(y) > abs(x)*1.2f) "Delete forever" else if(x > threshold/3) "Move to bin" else if(x < -threshold/3) "Keep for 7 days" else ""
    Card(Modifier.widthIn(max=760.dp).fillMaxWidth().graphicsLayer { translationX=x; translationY=y; rotationZ=(x/50f).coerceIn(-7f,7f) }
        .semantics { customActions=listOf(CustomAccessibilityAction("Keep for seven days") { if(!currentBusy) latestKeep(); true },CustomAccessibilityAction("Move to recycle bin") { if(!currentBusy) latestBin(); true },CustomAccessibilityAction("Request permanent deletion") { if(!currentBusy) latestPermanent(); true }) }
        .pointerInput(file.key) {
            detectDragGestures(onDragStart={settling?.cancel();hapticSent=false},onDragCancel={settleCard()},onDragEnd={
                var committed=false
                if(!currentBusy && !decisionSent) when {
                    y < -threshold && abs(y) > abs(x)*1.2f -> latestPermanent()
                    x > threshold && abs(x) > abs(y) -> { decisionSent=true;committed=true;latestBin() }
                    x < -threshold && abs(x) > abs(y) -> { decisionSent=true;committed=true;latestKeep() }
                }
                if(!committed) settleCard()
            }) { change,drag -> if(!currentBusy && !decisionSent) { change.consume();x+=drag.x;y+=drag.y } }
        },shape=RoundedCornerShape(16.dp),colors=CardDefaults.cardColors(containerColor=MaterialTheme.colorScheme.surfaceContainer)) {
        Box(Modifier.fillMaxWidth().height(height).clickable(enabled=!busy && !decisionSent,onClick=onPreview)) {
            FileThumbnail(file,Modifier.fillMaxSize())
            if(direction.isNotEmpty()) Surface(Modifier.align(Alignment.TopCenter).padding(16.dp),color=if(direction=="Delete forever") MaterialTheme.colorScheme.errorContainer else MaterialTheme.colorScheme.primaryContainer,shape=MaterialTheme.shapes.small) {
                Text(direction,Modifier.padding(12.dp),style=MaterialTheme.typography.titleMedium)
            }
            FilledTonalIconButton(onClick=onPreview,Modifier.align(Alignment.TopEnd).padding(12.dp).size(48.dp),enabled=!busy) {
                Icon(if(file.kind in listOf(FileKind.VIDEO,FileKind.AUDIO)) Icons.Outlined.PlayArrow else Icons.Outlined.OpenInFull,if(file.kind in listOf(FileKind.VIDEO,FileKind.AUDIO)) "Play preview" else "Open preview")
            }
        }
        Column(Modifier.padding(16.dp)) {
            Text(file.name,style=MaterialTheme.typography.titleMedium,maxLines=2,overflow=TextOverflow.Ellipsis)
            Text(file.path,Modifier.padding(top=6.dp),style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant,maxLines=2,overflow=TextOverflow.Ellipsis)
            Row(Modifier.fillMaxWidth(),verticalAlignment=Alignment.CenterVertically) {
                Text(formatSize(file.size),style=MaterialTheme.typography.titleSmall,fontWeight=FontWeight.SemiBold)
                Text(" · ${file.kind.label}",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
                Spacer(Modifier.weight(1f))
                TextButton(onClick=onDetails,enabled=!busy) { Text("Details") }
            }
        }
    }
}

@Composable private fun RowScope.ActionButton(label: String, icon: ImageVector, danger: Boolean, enabled: Boolean, small:Boolean=false,filled:Boolean=false,onClick:()->Unit) {
    Column(horizontalAlignment=Alignment.CenterHorizontally,modifier=Modifier.weight(1f)) {
        val color=if(danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.primary
        if(filled) FilledIconButton(onClick,Modifier.size(if(small) 52.dp else 64.dp),enabled=enabled,shape=CircleShape) { Icon(icon,label,Modifier.size(28.dp)) }
        else OutlinedIconButton(onClick,Modifier.size(if(small) 52.dp else 64.dp),enabled=enabled,shape=CircleShape) { Icon(icon,label,Modifier.size(if(small)24.dp else 28.dp),tint=color) }
        Text(label,Modifier.padding(top=8.dp),style=MaterialTheme.typography.labelMedium,textAlign=androidx.compose.ui.text.style.TextAlign.Center,color=if(danger) MaterialTheme.colorScheme.error else MaterialTheme.colorScheme.onSurface)
    }
}

@Composable private fun GestureGuide() {
    Column(verticalArrangement=Arrangement.spacedBy(16.dp)) {
        listOf(Triple(Icons.AutoMirrored.Outlined.ArrowBack,"Swipe left","Keep. Review again in 7 days."),
            Triple(Icons.Outlined.East,"Swipe right","Move to bin. Restore for 30 days."),
            Triple(Icons.Outlined.North,"Swipe up","Delete forever, after confirmation.")).forEach { (icon,title,body) ->
            Row(verticalAlignment=Alignment.CenterVertically) {
                Icon(icon,null,Modifier.size(32.dp),tint=MaterialTheme.colorScheme.primary)
                Column(Modifier.padding(start=16.dp)) { Text(title,style=MaterialTheme.typography.titleSmall); Text(body,style=MaterialTheme.typography.bodyMedium) }
            }
        }
    }
}

@Composable private fun UndoStrip(operations: List<Operation>, now: Long, busy: Boolean, undo: (Operation)->Unit) {
    val retained=remember { mutableStateOf(operations) }
    SideEffect { if(operations.isNotEmpty()) retained.value=operations }
    val shown=if(operations.isNotEmpty()) operations else retained.value
    val scroll=rememberLazyListState()
    LaunchedEffect(operations.firstOrNull()?.id) { if(operations.isNotEmpty()) scroll.animateScrollToItem(0) }
    AnimatedVisibility(visible=operations.isNotEmpty(),enter=expandVertically(animationSpec=tween(160))+fadeIn(tween(120)),exit=shrinkVertically(animationSpec=tween(160))+fadeOut(tween(120))) {
    Surface(color=MaterialTheme.colorScheme.inverseSurface,contentColor=MaterialTheme.colorScheme.inverseOnSurface) {
        BoxWithConstraints(Modifier.fillMaxWidth()) {
        val itemWidth=(maxWidth-if(shown.size>1) 36.dp else 16.dp).coerceAtLeast(220.dp)
        LazyRow(state=scroll,modifier=Modifier.fillMaxWidth().heightIn(max=128.dp),contentPadding=PaddingValues(horizontal=8.dp),horizontalArrangement=Arrangement.spacedBy(12.dp)) {
            items(shown,key={it.id}) { op ->
                val remaining = remember(op,now) { op.undoRemainingMs() }
                val seconds = ceil(remaining/1000.0).toInt()
                val progress by animateFloatAsState(targetValue=(remaining.toFloat()/ReviewPolicy.UNDO_MS).coerceIn(0f,1f),animationSpec=tween(200,easing=LinearEasing),label="Undo countdown")
                Column(Modifier.width(itemWidth)) {
                Row(Modifier.fillMaxWidth().padding(start=8.dp,end=4.dp,top=4.dp,bottom=4.dp),verticalAlignment=Alignment.CenterVertically) {
                    Column(Modifier.weight(1f)) {
                        Text(if(seconds==0) "Finishing…" else if(op.permanent) "Delete forever in ${seconds}s" else "Move to bin in ${seconds}s",style=MaterialTheme.typography.labelMedium)
                        Text(op.file.name,maxLines=1,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodyMedium)
                    }
                    TextButton(onClick={undo(op)},enabled=seconds > 0 && !busy,colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.inversePrimary)) { Text("Undo") }
                }
                LinearProgressIndicator(progress={progress},modifier=Modifier.fillMaxWidth().height(2.dp),color=MaterialTheme.colorScheme.inversePrimary,trackColor=MaterialTheme.colorScheme.inverseSurface)
                }
            }
        }
        }
    }
    }
}

@Composable private fun DeleteDialog(file: PhoneFile,onDismiss:()->Unit,onConfirm:()->Unit) {
    AlertDialog(onDismissRequest=onDismiss,icon={Icon(Icons.Outlined.DeleteForever,null,tint=MaterialTheme.colorScheme.error)},
        title={Text("Delete permanently?")},text={Column {
            Text(file.name,style=MaterialTheme.typography.titleMedium)
            Text("${formatSize(file.size)} · ${file.path}",Modifier.padding(top=8.dp),style=MaterialTheme.typography.bodySmall)
            Text("This skips the recycle bin. You have 5 seconds to undo, then this file cannot be restored by Binnder.",Modifier.padding(top=16.dp))
        }},confirmButton={TextButton(onClick=onConfirm,colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)) { Text("Delete forever") }},
        dismissButton={TextButton(onClick=onDismiss) { Text("Cancel") }})
}

@Composable private fun BinScreen(state: AppState,vm:MainViewModel,onPreview:(PhoneFile)->Unit,onPurge:(Operation)->Unit) {
    val operations=state.operations.filter { it.state !in listOf("pending") }
    if(operations.isEmpty()) {
        Column(Modifier.fillMaxSize().padding(24.dp),verticalArrangement=Arrangement.Center,horizontalAlignment=Alignment.CenterHorizontally) {
            Icon(Icons.Outlined.DeleteOutline,null,Modifier.size(64.dp),tint=MaterialTheme.colorScheme.primary)
            Text("Your bin is empty",Modifier.padding(top=20.dp),style=MaterialTheme.typography.headlineSmall)
            Text("Files you swipe right stay here for 30 days. You can restore them before they expire.",Modifier.padding(top=12.dp),style=MaterialTheme.typography.bodyLarge)
        }; return
    }
    LazyColumn(Modifier.fillMaxSize(),contentPadding=PaddingValues(20.dp),verticalArrangement=Arrangement.spacedBy(12.dp)) {
        item {
            Text("${formatSize(operations.sumOf { it.file.size })} in the bin",style=MaterialTheme.typography.titleLarge)
            Text("Still using storage. Expired files are deleted automatically when Android allows background work, or when you open Binnder.",Modifier.padding(top=8.dp,bottom=12.dp),style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        }
        items(operations,key={it.id}) { op ->
            ListItem(headlineContent={Text(op.file.name,maxLines=2,overflow=TextOverflow.Ellipsis)},
                supportingContent={Column {
                    val days=ceil(((op.trashedAt+ReviewPolicy.TRASH_MS-state.now).coerceAtLeast(0))/86400000.0).toInt()
                    Text("${formatSize(op.file.size)} · ${if(op.state=="trashed") "${days}d until deletion" else op.state.replace('_',' ')}")
                    Text(op.file.path,maxLines=2,overflow=TextOverflow.Ellipsis,style=MaterialTheme.typography.bodySmall)
                    Row(Modifier.horizontalScroll(rememberScrollState())) {
                        TextButton(onClick={ onPreview(op.file.copy(path=op.trashPath)) },enabled=op.state=="trashed") { Text("Preview") }
                        TextButton(onClick={vm.restore(op)},enabled=op.state=="trashed" && !state.actionBusy) { Text("Restore") }
                        TextButton(onClick={onPurge(op)},enabled=op.state=="trashed" && !state.actionBusy,colors=ButtonDefaults.textButtonColors(contentColor=MaterialTheme.colorScheme.error)) { Text("Delete forever") }
                    }
                }},leadingContent={Icon(kindIcon(op.file.kind),null)})
            HorizontalDivider()
        }
    }
}

@Composable private fun SettingsScreen(state:AppState,vm:MainViewModel) {
    val context=LocalContext.current
    var repo by rememberSaveable { mutableStateOf(vm.updates.repository) }
    var auto by remember { mutableStateOf(vm.updates.automatic) }
    val permissions=rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }
    var pendingInstall by remember { mutableStateOf<ReadyUpdate?>(null) }
    val installPermission=rememberLauncherForActivityResult(ActivityResultContracts.StartActivityForResult()) {
        val update=pendingInstall; pendingInstall=null
        if(update != null && context.packageManager.canRequestPackageInstalls()) runCatching { context.startActivity(vm.updates.installIntent(update)) }.onFailure { vm.message(it.message) }
    }
    Column(Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        Text("Storage",style=MaterialTheme.typography.titleLarge)
        Text("${formatSize(state.freed)} permanently deleted by Binnder",style=MaterialTheme.typography.bodyLarge)
        Text("Moving a file to the bin does not free space. Files are kept on their original storage volume.",style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        HorizontalDivider(Modifier.padding(vertical=8.dp))
        Text("GitHub updates",style=MaterialTheme.typography.titleLarge)
        Text("Use a public repository with Binnder’s signed release workflow. Automatic checks download verified updates; Android asks you to approve installation.",style=MaterialTheme.typography.bodyMedium)
        OutlinedTextField(value=repo,onValueChange={repo=it.trim()},label={Text("owner/repository")},singleLine=true,modifier=Modifier.fillMaxWidth(),isError=repo.isNotEmpty() && !ReviewPolicy.validRepository(repo))
        OutlinedButton(onClick={vm.saveRepository(repo)},enabled=repo.isEmpty() || ReviewPolicy.validRepository(repo)) { Text("Save repository") }
        Row(verticalAlignment=Alignment.CenterVertically) {
            Column(Modifier.weight(1f)) { Text("Automatic updates",style=MaterialTheme.typography.titleMedium); Text("Checks on return and about every 15 minutes in the background. Android may delay checks.",style=MaterialTheme.typography.bodySmall) }
            Switch(checked=auto,onCheckedChange={auto=it;vm.updates.automatic=it})
        }
        Text(state.updateStatus,style=MaterialTheme.typography.bodyMedium,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Button(onClick=vm::checkUpdates,enabled=!state.checkingUpdate && ReviewPolicy.validRepository(vm.updates.repository)) { Text(if(state.checkingUpdate) "Checking and downloading…" else "Check for updates") }
        if(Build.VERSION.SDK_INT >= 33) TextButton(onClick={permissions.launch(Manifest.permission.POST_NOTIFICATIONS)}) { Text("Allow update notifications") }
        state.ready?.let { update ->
            FilledTonalButton(onClick={
                runCatching {
                    if(context.packageManager.canRequestPackageInstalls()) context.startActivity(vm.updates.installIntent(update))
                    else { pendingInstall=update;installPermission.launch(Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES,Uri.parse("package:${context.packageName}"))) }
                }.onFailure { vm.message(it.message ?: "Could not open the Android installer.") }
            }) { Text("Install ${update.version}") }
        }
        HorizontalDivider(Modifier.padding(vertical=8.dp))
        Text("Swipe guide",style=MaterialTheme.typography.titleLarge)
        GestureGuide()
        Text("All deletions wait five seconds. If you leave the app, pending actions still finish when Android schedules them. Uninstalling Binnder removes its bin index; restore binned files first. Files in .Binnder-trash remain on shared storage if the app is uninstalled.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
        Text("Binnder ${BuildConfig.VERSION_NAME} · Android 11+\nNo accounts. No analytics. No file uploads.",style=MaterialTheme.typography.bodySmall,color=MaterialTheme.colorScheme.onSurfaceVariant)
    }
}
