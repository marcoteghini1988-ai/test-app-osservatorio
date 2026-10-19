package com.mybotrun.observer

import android.content.Context
import android.graphics.ImageFormat
import android.media.Image
import ai.onnxruntime.OnnxTensor
import ai.onnxruntime.OrtEnvironment
import ai.onnxruntime.OrtSession
import java.nio.FloatBuffer
import kotlin.math.*

/** The bundled ONNX is exported from the user's actual YOLOv5 best.pt.
 *  Output [1,6300,21] = cx,cy,w,h,objectness,16 class scores.
 *  This detects the trained STRUCTURE classes, not troop identities/actions.
 */
class YoloDetector(context: Context) : AutoCloseable {
    companion object {
        val CLASSES = listOf(
            "ad", "airsweeper", "bombtower", "canon", "clancastle", "eagle",
            "inferno", "kingpad", "mortar", "queenpad", "rcpad", "scattershot",
            "th13", "wardenpad", "wizztower", "xbow"
        )
    }
    private val env = OrtEnvironment.getEnvironment()
    private val options = OrtSession.SessionOptions().apply { setIntraOpNumThreads(2) }
    private val session: OrtSession
    private val inputName: String

    init {
        val modelFile = java.io.File(context.cacheDir, "best_320.onnx")
        if (!modelFile.exists() || modelFile.length() < 1_000_000L) {
            context.assets.open("best_320.onnx").use { source ->
                modelFile.outputStream().use { target -> source.copyTo(target) }
            }
        }
        session = env.createSession(modelFile.absolutePath, options)
        inputName = session.inputNames.first()
        require(session.inputInfo[inputName] != null) { "Modello YOLO privo di input" }
    }

    fun detect(image: Image, timestampMs: Long): List<Recognition> {
        return detectFrame(FramePixels.fromYuv(image, 320), timestampMs)
    }

    internal fun detectFrame(frame: FramePixels, timestampMs: Long): List<Recognition> {
        val tensor = OnnxTensor.createTensor(env, FloatBuffer.wrap(frame.chw), longArrayOf(1,3,320,320))
        try {
            session.run(mapOf(inputName to tensor)).use { result ->
                val output = result[0] as? OnnxTensor ?: error("Output ONNX non tensor")
                val shape = output.info.shape
                require(shape.size == 3 && shape[0] == 1L && shape[2] == 21L) {
                    "Output YOLO inatteso: ${shape.joinToString()}"
                }
                val vals = output.floatBuffer
                val candidateCount = shape[1].toInt()
                val matches = ArrayList<Recognition>()
                for (i in 0 until candidateCount) {
                    val base = i * 21
                    val obj = vals.get(base + 4)
                    if (obj < 0.26f) continue
                    var topIndex = -1
                    var topScore = 0f
                    for (c in 0 until 16) {
                        val score = obj * vals.get(base + 5 + c)
                        if (score > topScore) { topScore = score; topIndex = c }
                    }
                    if (topIndex < 0 || topScore < 0.32f) continue
                    val cx = vals.get(base); val cy = vals.get(base+1)
                    val w = vals.get(base+2); val h = vals.get(base+3)
                    val sx = ((cx - w/2f) - frame.padX) / frame.scaledWidth
                    val sy = ((cy - h/2f) - frame.padY) / frame.scaledHeight
                    val ex = ((cx + w/2f) - frame.padX) / frame.scaledWidth
                    val ey = ((cy + h/2f) - frame.padY) / frame.scaledHeight
                    val nx = sx.coerceIn(0f,1f); val ny = sy.coerceIn(0f,1f)
                    val nw = ex.coerceIn(0f,1f) - nx; val nh = ey.coerceIn(0f,1f) - ny
                    if (nw <= 0f || nh <= 0f) continue
                    matches.add(Recognition(timestampMs,CLASSES[topIndex],topScore,nx,ny,nw,nh))
                }
                return nms(matches, 0.45f).take(60)
            }
        } finally { tensor.close() }
    }

    private fun nms(boxes: List<Recognition>, limit: Float): List<Recognition> {
        val ordered = boxes.sortedByDescending { it.confidence }
        val kept = ArrayList<Recognition>()
        for (b in ordered) {
            if (kept.any { it.klass == b.klass && iou(it,b) > limit }) continue
            kept.add(b)
            if (kept.size >= 60) break
        }
        return kept
    }
    private fun iou(a: Recognition,b: Recognition):Float {
        val left=max(a.x,b.x); val top=max(a.y,b.y)
        val right=min(a.x+a.w,b.x+b.w); val bottom=min(a.y+a.h,b.y+b.h)
        val intersection=max(0f,right-left)*max(0f,bottom-top)
        val union=a.w*a.h+b.w*b.h-intersection
        return if (union <= 0f) 0f else intersection/union
    }
    override fun close() { session.close(); options.close() }
}

/** Android-native YUV_420_888 reader. Copies a letterboxed image into NCHW floats.
 *  Sampling the source planes directly avoids building full-resolution bitmaps.
 */
internal class FramePixels(
    val chw: FloatArray,
    val padX: Float,
    val padY: Float,
    val scaledWidth: Float,
    val scaledHeight: Float
) {
    companion object {
        fun fromYuv(image: Image, size: Int): FramePixels {
            require(image.format == ImageFormat.YUV_420_888) { "YUV_420_888 richiesto" }
            val crop = image.cropRect
            val sw = crop.width(); val sh = crop.height()
            val scale = min(size.toFloat()/sw, size.toFloat()/sh)
            val w = max(1, (sw*scale).toInt()); val h=max(1,(sh*scale).toInt())
            val px = (size-w)/2; val py=(size-h)/2
            val out=FloatArray(3*size*size){114f/255f}
            val yPlane=image.planes[0]; val uPlane=image.planes[1]; val vPlane=image.planes[2]
            val yData=yPlane.buffer; val uData=uPlane.buffer; val vData=vPlane.buffer
            fun sample(plane: Image.Plane, buff: java.nio.ByteBuffer, x:Int,y:Int):Int {
                val pos=y*plane.rowStride+x*plane.pixelStride
                return if (pos < 0 || pos >= buff.limit()) 128 else buff.get(pos).toInt() and 255
            }
            for (dy in 0 until h) {
                val sy=(crop.top+(dy.toLong()*sh/h).toInt()).coerceIn(0,image.height-1)
                for (dx in 0 until w) {
                    val sx=(crop.left+(dx.toLong()*sw/w).toInt()).coerceIn(0,image.width-1)
                    val yy=sample(yPlane,yData,sx,sy).toFloat()
                    val uu=sample(uPlane,uData,sx/2,sy/2)-128f
                    val vv=sample(vPlane,vData,sx/2,sy/2)-128f
                    val r=(yy+1.402f*vv).coerceIn(0f,255f)/255f
                    val g=(yy-0.344136f*uu-0.714136f*vv).coerceIn(0f,255f)/255f
                    val b=(yy+1.772f*uu).coerceIn(0f,255f)/255f
                    val index=(dy+py)*size+dx+px
                    out[index]=r;out[index+size*size]=g;out[index+2*size*size]=b
                }
            }
            return FramePixels(out,px.toFloat(),py.toFloat(),w.toFloat(),h.toFloat())
        }
    }
}
