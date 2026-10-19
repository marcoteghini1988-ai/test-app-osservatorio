package com.mybotrun.observer

import android.content.Context
import android.graphics.Bitmap
import android.graphics.ImageFormat
import android.media.Image
import android.media.ImageReader
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.SystemClock
import java.io.ByteArrayOutputStream
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.*

/** Decode the video SEQUENTIALLY with native Android MediaExtractor and MediaCodec.
 *  Never uses content:// as an HTML page, JS, browser video tags or seeks through 600MB MP4s.
 *  Memory: one ImageReader frame + a limited number of 320px JPEGs per video.
 */
class VideoEngine(private val context: Context, private val detector: YoloDetector?, private val modelError: String? = null) {
    fun analyze(source: VideoEntry, cancel: AtomicBoolean, progress:(Long,Long,String)->Unit): VideoResult {
        val out=VideoResult(source,0L)
        if (detector == null) out.failure="YOLO non avviato: ${modelError ?: "runtime o modello non disponibile"}"
        val extractor=MediaExtractor()
        var codec:MediaCodec?=null
        var reader:ImageReader?=null
        try {
            extractor.setDataSource(context, source.uri, null)
            val track=(0 until extractor.trackCount).firstOrNull { i ->
                extractor.getTrackFormat(i).getString(MediaFormat.KEY_MIME)?.startsWith("video/")==true
            } ?: error("Nessuna traccia video decodificabile")
            extractor.selectTrack(track)
            val fmt=extractor.getTrackFormat(track)
            val mime=fmt.getString(MediaFormat.KEY_MIME) ?: error("Codec sconosciuto")
            val width=fmt.getInteger(MediaFormat.KEY_WIDTH)
            val height=fmt.getInteger(MediaFormat.KEY_HEIGHT)
            require(width>0 && height>0 && width<=8192 && height<=8192) {"Risoluzione non supportata: $width x $height"}
            val duration=if(fmt.containsKey(MediaFormat.KEY_DURATION))fmt.getLong(MediaFormat.KEY_DURATION)/1000 else 0L
            val result=out
            result.durationMs=duration
            progress(0,duration,"Apro $mime · $width×$height")
            reader=ImageReader.newInstance(width,height,ImageFormat.YUV_420_888,3)
            codec=MediaCodec.createDecoderByType(mime)
            codec.configure(fmt,reader.surface,null,0)
            codec.start()
            val bufferInfo=MediaCodec.BufferInfo()
            var inputEos=false
            var outputEos=false
            var nextSampleUs=0L
            var nextYoloUs=0L
            var nextJpegUs=0L
            var dynamicUntilUs=0L
            var samplesProcessed=0
            var yoloAttempts=0
            var consecutiveIdle=0
            var lastFrame:ByteArray?=null
            var lastBottom:ByteArray?=null
            while(!outputEos) {
                if(cancel.get()) {result.failure="Interrotto dall'utente";break}
                var didWork=false
                if(!inputEos) {
                    val inputIndex=codec.dequeueInputBuffer(10_000L)
                    if(inputIndex>=0) {
                        val input=codec.getInputBuffer(inputIndex) ?: error("Buffer codec nullo")
                        input.clear()
                        val bytes=extractor.readSampleData(input,0)
                        if(bytes<0) {
                            codec.queueInputBuffer(inputIndex,0,0,0L,MediaCodec.BUFFER_FLAG_END_OF_STREAM)
                            inputEos=true
                        } else {
                            codec.queueInputBuffer(inputIndex,0,bytes,extractor.sampleTime.coerceAtLeast(0L),0)
                            extractor.advance()
                        }
                        didWork=true
                    }
                }
                val index=codec.dequeueOutputBuffer(bufferInfo,15_000L)
                when {
                    index>=0 -> {
                        didWork=true
                        val pts=bufferInfo.presentationTimeUs.coerceAtLeast(0L)
                        outputEos=(bufferInfo.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM)!=0
                        val shouldSample=!(outputEos && bufferInfo.size==0) && pts>=nextSampleUs
                        codec.releaseOutputBuffer(index,shouldSample)
                        if(shouldSample) {
                            val image=waitImage(reader, 1800L)
                                ?: error("Android MediaCodec non ha consegnato il fotogramma a ${pts/1_000_000.0}s")
                            try {
                                val small=smallLuma(image)
                                val full=diff(lastFrame,small,false)
                                val bottom=diff(lastBottom,small,true)
                                val action=bottom>=0.09 && full<0.34
                                result.samples.add(Sample(pts/1000L,full,bottom,action))
                                lastFrame=small
                                lastBottom=small
                                if(full>0.17 || bottom>0.09) dynamicUntilUs=pts+2_000_000L
                                nextSampleUs=pts+(if(pts<dynamicUntilUs)250_000L else 500_000L)
                                if(detector!=null && (pts>=nextYoloUs || (full>0.28 && pts+800_000L>=nextYoloUs))) {
                                    // ONNX is expensive: sample structures approximately every 2s.
                                    nextYoloUs=pts+2_000_000L
                                    val modelFrame=FramePixels.fromYuv(image,320)
                                    result.detections.addAll(detector.detectFrame(modelFrame,pts/1000L))
                                    yoloAttempts++
                                    if(result.frames.size<36 && pts>=nextJpegUs) {
                                        saveJpeg(modelFrame,pts/1000L)?.let { result.frames.add(it) }
                                        nextJpegUs=pts+15_000_000L
                                    }
                                }
                                samplesProcessed++
                                if(samplesProcessed%6==0) progress(pts/1000L,duration,
                                    "${source.name}: $samplesProcessed campioni, $yoloAttempts analisi YOLO")
                            } finally {image.close()}
                        }
                    }
                    index==MediaCodec.INFO_OUTPUT_FORMAT_CHANGED -> didWork=true
                }
                if(didWork) consecutiveIdle=0 else consecutiveIdle++
                if(consecutiveIdle>600) error("Decoder Android bloccato senza fotogrammi")
            }
            result.yoloRun=detector!=null && yoloAttempts>0
            if(!result.yoloRun && result.failure==null) result.failure="YOLO non eseguito sul filmato"
            result.complete=outputEos && !cancel.get() && result.yoloRun && result.failure==null
            progress(duration,duration,if(result.complete) "Analisi completata" else "Analisi parziale: ${result.failure}")
            return result
        } catch(e:Exception) {
            // Keep the samples collected so far; never claim an incomplete video is complete.
            out.failure="${e.javaClass.simpleName}: ${e.message}"
            out.complete=false
            progress(out.samples.lastOrNull()?.ms ?: 0L,out.durationMs,"Errore decoder: ${out.failure}")
            return out
        } finally {
            try { codec?.stop() } catch (_:Exception) {}
            try { codec?.release() } catch (_:Exception) {}
            try { reader?.close() } catch (_:Exception) {}
            try { extractor.release() } catch (_:Exception) {}
        }
    }

    private fun waitImage(reader:ImageReader, maxMs:Long):Image? {
        val deadline=SystemClock.uptimeMillis()+maxMs
        do {
            val image=reader.acquireNextImage()
            if(image!=null) return image
            SystemClock.sleep(8)
        } while(SystemClock.uptimeMillis()<deadline)
        return null
    }

    private fun smallLuma(image:Image):ByteArray {
        val plane=image.planes[0]
        val buf=plane.buffer
        val crop=image.cropRect
        val w=crop.width();val h=crop.height()
        val out=ByteArray(64*36)
        for(y in 0 until 36) for(x in 0 until 64) {
            val sx=crop.left+(x*w/64)
            val sy=crop.top+(y*h/36)
            val index=sy*plane.rowStride+sx*plane.pixelStride
            out[y*64+x]=if(index in 0 until buf.limit())buf.get(index) else 0
        }
        return out
    }
    private fun diff(previous:ByteArray?,now:ByteArray,bottomOnly:Boolean):Double {
        if(previous==null) return 0.0
        var sum=0L;var n=0
        val start=if(bottomOnly)28 else 0
        for(y in start until 36) for(x in 0 until 64) {
            val k=y*64+x
            sum+=abs((previous[k].toInt() and 255)-(now[k].toInt() and 255))
            n++
        }
        return sum.toDouble()/(n*255.0)
    }
    private fun saveJpeg(frame:FramePixels, ms:Long):Pair<Long,ByteArray>? {
        return try {
            val size=320; val plane=size*size
            val pixels=IntArray(plane)
            for(i in 0 until plane) {
                val r=(frame.chw[i]*255).toInt().coerceIn(0,255)
                val g=(frame.chw[i+plane]*255).toInt().coerceIn(0,255)
                val b=(frame.chw[i+2*plane]*255).toInt().coerceIn(0,255)
                pixels[i]=0xff000000.toInt() or (r shl 16) or (g shl 8) or b
            }
            val bitmap=Bitmap.createBitmap(pixels,size,size,Bitmap.Config.ARGB_8888)
            val stream=ByteArrayOutputStream()
            bitmap.compress(Bitmap.CompressFormat.JPEG,65,stream)
            bitmap.recycle()
            ms to stream.toByteArray()
        } catch (_:OutOfMemoryError) {null}
    }
}
