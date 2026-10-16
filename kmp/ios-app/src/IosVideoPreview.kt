// SPDX-License-Identifier: Apache-2.0
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.interop.UIKitView
import kotlinx.cinterop.*
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.update
import montage.core.VideoAsset
import montage.core.VideoMetadata
import montage.ui.*
import org.jetbrains.skia.Image
import platform.AVFoundation.*
import platform.CoreGraphics.*
import platform.CoreMedia.*
import platform.Foundation.*
import platform.UIKit.*
import platform.posix.memcpy
import kotlin.math.*

@OptIn(ExperimentalForeignApi::class)
class IosVideoPreview : VideoPreviewProvider {
    private val previewDirectory = NSTemporaryDirectory().trimEnd('/') + "/ProjectMontagePreview"
    init {
        // A process termination can leave an alias; only this private derived cache is swept.
        val manager = NSFileManager.defaultManager
        val names = manager.contentsOfDirectoryAtPath(previewDirectory, null) ?: emptyList<Any>()
        names.filterIsInstance<String>().filter { it.matches(Regex("[0-9a-fA-F-]{36}\\.[a-z0-9]{2,4}")) }
            .forEach { manager.removeItemAtPath("$previewDirectory/$it", null) }
    }
    override fun open(asset: VideoAsset): VideoPreviewController = IosPlayer(asset, previewDirectory)
    @Composable override fun Surface(controller: VideoPreviewController, modifier: Modifier) {
        UIKitView(factory = { (controller as IosPlayer).view }, modifier = modifier)
    }
}

@OptIn(ExperimentalForeignApi::class)
private class PlayerView(player: AVPlayer) : UIView(frame = CGRectMake(0.0, 0.0, 0.0, 0.0)) {
    private val videoLayer = AVPlayerLayer.playerLayerWithPlayer(player)
    init {
        backgroundColor = UIColor.blackColor
        videoLayer.videoGravity = AVLayerVideoGravityResizeAspect
        layer.addSublayer(videoLayer)
    }
    override fun layoutSubviews() {
        super.layoutSubviews()
        videoLayer.frame = bounds
    }
    fun detach() { videoLayer.player = null }
}

@OptIn(ExperimentalForeignApi::class)
private class IosPlayer(asset: VideoAsset, private val previewDirectory: String) : VideoPreviewController {
    override val state = MutableStateFlow(VideoPreviewState())
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    private val player = AVPlayer()
    val view = PlayerView(player)
    private var closed = false
    private var previewAlias: String? = null
    private var stage = "file"
    private val inactiveObserver = NSNotificationCenter.defaultCenter.addObserverForName(
        UIApplicationWillResignActiveNotification, null, NSOperationQueue.mainQueue,
    ) { pause() }

    private fun fail() {
        if (!closed) {
            NSLog("Montage video preview: failed at $stage")
            player.pause()
            player.replaceCurrentItemWithPlayerItem(null)
            removeAlias()
            state.update { it.copy(status = PlaybackStatus.Error) }
        }
    }
    private fun removeAlias() {
        previewAlias?.let { NSFileManager.defaultManager.removeItemAtPath(it, null) }
        previewAlias = null
    }
    init {
        scope.launch {
            try {
                val root = (NSFileManager.defaultManager.URLsForDirectory(NSApplicationSupportDirectory, NSUserDomainMask).first() as NSURL).path!!
                val path = "$root/ProjectMontage/media/${asset.id}.media"
                val manager = NSFileManager.defaultManager
                val attributes = manager.attributesOfItemAtPath(path, null)
                check((attributes?.get(NSFileSize) as? NSNumber)?.longLongValue == asset.byteSize)
                val extension = asset.name.substringAfterLast('.', "").lowercase()
                val sourcePath = if (extension in setOf("mp4", "mov", "m4v", "3gp", "3g2", "avi", "mpg", "mpeg", "ts", "m2ts", "webm", "mkv")) {
                    check(manager.createDirectoryAtPath(previewDirectory, true, null, null))
                    val alias = "$previewDirectory/${NSUUID().UUIDString}.$extension"
                    // AVFoundation uses the container suffix. This adds no second copy of the media bytes.
                    check(manager.linkItemAtPath(path, alias, null))
                    previewAlias = alias
                    alias
                } else path
                stage = "metadata"
                NSLog("Montage video preview: reading metadata")
                val result = withContext(Dispatchers.Default) {
                    val source = AVURLAsset(uRL = NSURL.fileURLWithPath(sourcePath), options = null)
                    val track = source.tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack
                        ?: error("No video track")
                    val size = track.naturalSize.useContents { abs(width).toInt() to abs(height).toInt() }
                    val rotation = track.preferredTransform.useContents {
                        val angle = ((atan2(b, a) * 180 / PI).roundToInt() + 360) % 360
                        angle.takeIf { it in setOf(0, 90, 180, 270) }
                    }
                    val duration = CMTimeGetSeconds(source.duration)
                    check(duration.isFinite())
                    val metadata = VideoMetadata((duration * 1000).roundToLong(),
                        size.first, size.second, rotation, source.tracksWithMediaType(AVMediaTypeAudio).isNotEmpty())
                    val generator = AVAssetImageGenerator(asset = source)
                    generator.appliesPreferredTrackTransform = true
                    generator.maximumSize = CGSizeMake(320.0, 180.0)
                    val cgImage = checkNotNull(generator.copyCGImageAtTime(CMTimeMake(0, 1), null, null))
                    val thumbnail = try {
                        val png = checkNotNull(UIImagePNGRepresentation(UIImage.imageWithCGImage(cgImage)))
                        val bytes = ByteArray(png.length.toInt())
                        bytes.usePinned { memcpy(it.addressOf(0), png.bytes, png.length) }
                        Image.makeFromEncoded(bytes).toComposeImageBitmap()
                    } finally { CGImageRelease(cgImage) }
                    Triple(source, metadata, thumbnail)
                }
                stage = "player readiness"
                NSLog("Montage video preview: metadata and thumbnail ready")
                player.replaceCurrentItemWithPlayerItem(AVPlayerItem(asset = result.first))
                withTimeout(15_000) {
                    while (player.currentItem?.status != AVPlayerItemStatusReadyToPlay) {
                        check(player.currentItem?.status != AVPlayerItemStatusFailed)
                        delay(100)
                    }
                }
                state.value = VideoPreviewState(result.second, result.third, PlaybackStatus.Paused)
                stage = "playback"
                NSLog("Montage video preview: player ready")
            } catch (_: TimeoutCancellationException) { fail() }
            catch (cancelled: CancellationException) { throw cancelled }
            catch (_: Exception) { fail() }
        }
        scope.launch {
            while (isActive) {
                val metadata = state.value.metadata
                if (metadata != null && state.value.status != PlaybackStatus.Error) {
                    if (player.currentItem?.status == AVPlayerItemStatusFailed) fail()
                    else {
                        val seconds = CMTimeGetSeconds(player.currentTime())
                        if (seconds.isFinite()) {
                            val position = metadata.constrainPosition((seconds * 1000).roundToLong())
                            state.update { it.copy(positionMillis = position,
                                status = if (position >= metadata.durationMillis - 30 && player.rate == 0f) PlaybackStatus.Ended else it.status) }
                        }
                    }
                }
                delay(100)
            }
        }
    }
    override fun play() {
        if (!closed && state.value.metadata != null && state.value.status != PlaybackStatus.Error) {
            player.play(); state.update { it.copy(status = PlaybackStatus.Playing) }
        }
    }
    override fun pause() {
        if (!closed && state.value.metadata != null && state.value.status != PlaybackStatus.Error) {
            player.pause(); state.update { it.copy(status = PlaybackStatus.Paused) }
        }
    }
    override fun seekTo(positionMillis: Long) {
        val position = state.value.metadata?.constrainPosition(positionMillis) ?: return
        if (!closed && state.value.status != PlaybackStatus.Error) {
            player.seekToTime(CMTimeMake(position, 1000), toleranceBefore = CMTimeMake(0, 1), toleranceAfter = CMTimeMake(0, 1))
            state.update { it.copy(status = if (it.status == PlaybackStatus.Playing) PlaybackStatus.Playing else PlaybackStatus.Paused) }
        }
    }
    override fun close() {
        if (closed) return
        closed = true
        scope.cancel()
        player.pause()
        player.replaceCurrentItemWithPlayerItem(null)
        view.detach()
        removeAlias()
        NSNotificationCenter.defaultCenter.removeObserver(inactiveObserver)
    }
}
