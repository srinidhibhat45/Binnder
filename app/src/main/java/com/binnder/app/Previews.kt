@file:OptIn(androidx.compose.material3.ExperimentalMaterial3Api::class)

package com.binnder.app

import android.content.Intent
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ImageDecoder
import android.graphics.Color as AndroidColor
import android.graphics.pdf.PdfRenderer
import android.media.MediaPlayer
import android.util.LruCache
import android.media.ThumbnailUtils
import android.os.ParcelFileDescriptor
import android.util.Size
import android.widget.MediaController
import android.widget.VideoView
import androidx.compose.foundation.Image
import androidx.compose.animation.Crossfade
import androidx.compose.animation.core.tween
import androidx.compose.foundation.gestures.detectTransformGestures
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.outlined.ArrowBack
import androidx.compose.material.icons.outlined.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import androidx.core.content.FileProvider
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.lifecycle.compose.LocalLifecycleOwner
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.withContext
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import java.io.File

private data class Rendered(val bitmap: Bitmap? = null,val pages: Int = 1,val error: String? = null)

private object ThumbnailCache {
    private val cache=object : LruCache<String,Rendered>(24*1024*1024) {
        override fun sizeOf(key:String,value:Rendered)=value.bitmap?.allocationByteCount ?: 1024
    }
    private val decoding=Mutex()
    fun peek(file:PhoneFile):Rendered?=cache.get(file.key)
    suspend fun load(file:PhoneFile):Rendered = withContext(Dispatchers.IO) {
        cache.get(file.key) ?: decoding.withLock {
            cache.get(file.key) ?: render(file,edge=1200).also { if(it.bitmap != null) cache.put(file.key,it) }
        }
    }
}

suspend fun preloadFilePreviews(files:List<PhoneFile>) {
    for(file in files) if(file.kind in listOf(FileKind.IMAGE,FileKind.VIDEO,FileKind.PDF)) ThumbnailCache.load(file)
}

private fun render(file: PhoneFile, page: Int = 0, edge: Int = 1000): Rendered = try {
    val source=File(file.path)
    when(file.kind) {
        FileKind.IMAGE -> {
            val bitmap=ImageDecoder.decodeBitmap(ImageDecoder.createSource(source)) { decoder,info,_ ->
                val scale=minOf(1f,edge.toFloat()/maxOf(info.size.width,info.size.height))
                decoder.setTargetSize((info.size.width*scale).toInt().coerceAtLeast(1),(info.size.height*scale).toInt().coerceAtLeast(1))
                decoder.allocator=ImageDecoder.ALLOCATOR_SOFTWARE
            }
            Rendered(bitmap=bitmap)
        }
        FileKind.VIDEO -> Rendered(ThumbnailUtils.createVideoThumbnail(source,Size(edge,edge),null))
        FileKind.PDF -> ParcelFileDescriptor.open(source,ParcelFileDescriptor.MODE_READ_ONLY).use { descriptor ->
            PdfRenderer(descriptor).use { pdf ->
                val pages=pdf.pageCount
                check(pages > 0) { "This PDF has no pages." }
                pdf.openPage(page.coerceIn(0,pages-1)).use { p ->
                    val scale=minOf(edge.toFloat()/p.width,edge.toFloat()/p.height)
                    val bitmap=Bitmap.createBitmap((p.width*scale).toInt().coerceAtLeast(1),(p.height*scale).toInt().coerceAtLeast(1),Bitmap.Config.ARGB_8888)
                    bitmap.eraseColor(AndroidColor.WHITE)
                    p.render(bitmap,null,null,PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                    Rendered(bitmap,pages)
                }
            }
        }
        else -> Rendered()
    }
} catch(_: Exception) { Rendered(error="Preview unavailable. Open this file in another app to check it.") }

@Composable fun FileThumbnail(file:PhoneFile,modifier:Modifier=Modifier) {
    val initial=remember(file.key) { ThumbnailCache.peek(file) }
    val rendered by produceState(initial,file.key) {
        value=ThumbnailCache.load(file)
    }
    Box(modifier,contentAlignment=Alignment.Center) {
        Crossfade(targetState=rendered,animationSpec=tween(140),label="Preview loading") { result ->
        when {
            result?.bitmap != null -> Image(result.bitmap.asImageBitmap(),"Preview of ${file.name}",Modifier.fillMaxSize(),contentScale=ContentScale.Fit)
            else -> Column(Modifier.padding(20.dp),horizontalAlignment=Alignment.CenterHorizontally) {
                Icon(kindIcon(file.kind),null,Modifier.size(72.dp),tint=MaterialTheme.colorScheme.primary)
                Text(if(result?.error!=null) "Open to inspect this file" else if(result==null) "Loading preview…" else file.kind.label,Modifier.padding(top=16.dp),style=MaterialTheme.typography.titleMedium)
            }
        }
        }
    }
}

@Composable fun PreviewScreen(file:PhoneFile,onBack:()->Unit,pending:@Composable ()->Unit) {
    val context=LocalContext.current
    var viewerError by remember(file.path) { mutableStateOf<String?>(null) }
    Scaffold(topBar={TopAppBar(title={Text(file.name,maxLines=1)},navigationIcon={IconButton(onClick=onBack) {Icon(Icons.AutoMirrored.Outlined.ArrowBack,"Back to files")}})},bottomBar={Column {
        pending()
        Surface { Row(Modifier.fillMaxWidth().navigationBarsPadding().padding(12.dp),verticalAlignment=Alignment.CenterVertically) {
            Text(formatSize(file.size),Modifier.weight(1f),style=MaterialTheme.typography.labelLarge)
            OutlinedButton(onClick={
                runCatching {
                    val uri=FileProvider.getUriForFile(context,"${context.packageName}.files",File(file.path))
                    context.startActivity(Intent.createChooser(Intent(Intent.ACTION_VIEW).setDataAndType(uri,file.mime).addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION),"Open with"))
                }.onFailure { viewerError="No viewer could open this file. Install an app that supports ${file.mime}." }
            }) { Text("Open with…") }
        } }
    }}) { padding ->
        Column(Modifier.fillMaxSize().padding(padding)) {
        viewerError?.let { Text(it,Modifier.padding(16.dp),color=MaterialTheme.colorScheme.error) }
        Box(Modifier.fillMaxWidth().weight(1f),contentAlignment=Alignment.Center) {
            when(file.kind) {
                FileKind.IMAGE,FileKind.PDF -> DocumentPreview(file)
                FileKind.VIDEO -> VideoPreview(file)
                FileKind.AUDIO -> AudioPreview(file)
                else -> OtherPreview(file)
            }
        }
        }
    }
}

@Composable private fun DocumentPreview(file:PhoneFile) {
    var page by rememberSaveableFile(file.path) { mutableIntStateOf(0) }
    var loading by remember(file.path,page) { mutableStateOf(true) }
    var zoom by remember(file.path,page) { mutableFloatStateOf(1f) }
    var panX by remember(file.path,page) { mutableFloatStateOf(0f) }
    var panY by remember(file.path,page) { mutableFloatStateOf(0f) }
    val rendered by produceState(Rendered(),file.key,page) {
        value=withContext(Dispatchers.IO) { render(file,page,edge=2400) }; loading=false
    }
    Column(Modifier.fillMaxSize()) {
        Box(Modifier.weight(1f).fillMaxWidth(),contentAlignment=Alignment.Center) {
            if(loading) CircularProgressIndicator()
            else rendered.bitmap?.let { bitmap ->
                Image(bitmap.asImageBitmap(),"${file.name}, page ${page+1}",Modifier.fillMaxSize()
                    .pointerInput(file.path,page) { detectTransformGestures { _,pan,scale,_ ->
                        zoom=(zoom*scale).coerceIn(1f,5f)
                        panX=if(zoom==1f) 0f else (panX+pan.x).coerceIn(-size.width*(zoom-1),size.width*(zoom-1))
                        panY=if(zoom==1f) 0f else (panY+pan.y).coerceIn(-size.height*(zoom-1),size.height*(zoom-1))
                    } }.graphicsLayer { scaleX=zoom;scaleY=zoom;translationX=panX;translationY=panY },contentScale=ContentScale.Fit)
            } ?: Text(rendered.error ?: "Preview unavailable.",Modifier.padding(24.dp))
        }
        if(file.kind==FileKind.PDF) Row(Modifier.fillMaxWidth().padding(12.dp),horizontalArrangement=Arrangement.SpaceBetween,verticalAlignment=Alignment.CenterVertically) {
            TextButton(onClick={page--},enabled=page>0 && !loading) {Text("Previous")}
            Text("${page+1} / ${rendered.pages}",style=MaterialTheme.typography.labelLarge)
            TextButton(onClick={page++},enabled=page+1<rendered.pages && !loading) {Text("Next")}
        }
        Text("Pinch to zoom",Modifier.align(Alignment.CenterHorizontally).padding(8.dp),style=MaterialTheme.typography.bodySmall)
    }
}

// Page position lives for the lifetime of this file preview only.
@Composable private fun <T> rememberSaveableFile(key:String,init:()->T):T = remember(key,calculation=init)

@Composable private fun VideoPreview(file:PhoneFile) {
    val context=LocalContext.current
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var error by remember(file.path) { mutableStateOf(false) }
    val video=remember(file.path) { VideoView(context).apply {
        setVideoPath(file.path)
        setMediaController(MediaController(context).also { it.setAnchorView(this) })
        setOnPreparedListener { seekTo(1) }
        setOnErrorListener { _,_,_ -> error=true;true }
    } }
    DisposableEffect(video,lifecycle) {
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_STOP) video.pause() }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer);video.stopPlayback() }
    }
    if(error) Text("This video needs another player. Use Open with…",Modifier.padding(24.dp))
    else Column(Modifier.fillMaxSize(),verticalArrangement=Arrangement.Center) {
        AndroidView(factory={video},modifier=Modifier.fillMaxWidth().weight(1f))
        Text("Tap the video for playback controls.",Modifier.padding(16.dp),style=MaterialTheme.typography.bodySmall)
    }
}

@Composable private fun AudioPreview(file:PhoneFile) {
    val lifecycle=LocalLifecycleOwner.current.lifecycle
    var ready by remember(file.path) { mutableStateOf(false) }
    var error by remember(file.path) { mutableStateOf(false) }
    var playing by remember(file.path) { mutableStateOf(false) }
    var position by remember(file.path) { mutableFloatStateOf(0f) }
    var duration by remember(file.path) { mutableFloatStateOf(1f) }
    val player=remember(file.path) { MediaPlayer() }
    DisposableEffect(player,lifecycle) {
        runCatching {
            player.setDataSource(file.path)
            player.setOnPreparedListener { ready=true;duration=it.duration.toFloat().coerceAtLeast(1f) }
            player.setOnCompletionListener { playing=false }
            player.setOnErrorListener { _,_,_ -> error=true;playing=false;true }
            player.prepareAsync()
        }.onFailure { error=true }
        val observer=LifecycleEventObserver { _,event -> if(event==Lifecycle.Event.ON_STOP && ready) { runCatching { player.pause() };playing=false } }
        lifecycle.addObserver(observer)
        onDispose { lifecycle.removeObserver(observer);player.release() }
    }
    LaunchedEffect(player,ready) {
        while(ready) { runCatching { position=player.currentPosition.toFloat() };delay(250) }
    }
    Column(Modifier.fillMaxWidth().padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Icon(Icons.Outlined.AudioFile,null,Modifier.size(96.dp),tint=MaterialTheme.colorScheme.primary)
        Text(file.name,Modifier.padding(top=24.dp),style=MaterialTheme.typography.titleLarge)
        if(error) Text("This audio needs another player. Use Open with…",Modifier.padding(top=16.dp))
        else if(!ready) CircularProgressIndicator(Modifier.padding(24.dp))
        else {
            Slider(value=position.coerceIn(0f,duration),onValueChange={position=it;player.seekTo(it.toInt())},valueRange=0f..duration)
            Text("${timeLabel(position)} / ${timeLabel(duration)}",style=MaterialTheme.typography.labelLarge)
            FilledIconButton(onClick={if(playing)player.pause() else player.start();playing=!playing},Modifier.padding(top=20.dp).size(64.dp)) {
                Icon(if(playing)Icons.Outlined.Pause else Icons.Outlined.PlayArrow,if(playing)"Pause audio" else "Play audio")
            }
        }
    }
}
private fun timeLabel(ms:Float):String { val seconds=(ms/1000).toInt();return "%d:%02d".format(seconds/60,seconds%60) }

@Composable private fun OtherPreview(file:PhoneFile) {
    val text by produceState<String?>(null,file.key) {
        value=withContext(Dispatchers.IO) {
            if(file.mime.startsWith("text/") || File(file.path).extension.lowercase() in listOf("txt","md","csv","json","log","xml"))
                runCatching { File(file.path).bufferedReader().use { reader -> val chars=CharArray(100_000);val count=reader.read(chars);if(count < 0) "Empty file" else String(chars,0,count)+if(count==chars.size) "\n[Preview limited to 100,000 characters]" else "" } }.getOrNull()
            else null
        }
    }
    if(text!=null) Text(text!!,Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(24.dp),style=MaterialTheme.typography.bodyMedium)
    else Column(Modifier.padding(24.dp),horizontalAlignment=Alignment.CenterHorizontally) {
        Icon(kindIcon(file.kind),null,Modifier.size(72.dp),tint=MaterialTheme.colorScheme.primary)
        Text(file.name,Modifier.padding(top=16.dp),style=MaterialTheme.typography.titleLarge)
        Text("Use Open with… to inspect this file in a compatible app before deleting it.",Modifier.padding(top=16.dp))
    }
}
