package com.mybotrun.observer

import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.io.FileInputStream
import java.io.FileOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

object ReportZip {
    private fun txt(zip:ZipOutputStream,path:String,body:String) {
        zip.putNextEntry(ZipEntry(path))
        zip.write(body.toByteArray(Charsets.UTF_8))
        zip.closeEntry()
    }
    private fun csvField(s:String):String = "\"" + s.replace("\"", "\"\"") + "\""
    fun writeVideo(zip:ZipOutputStream,result:VideoResult) {
        val src=result.source
        val status=JSONObject()
            .put("schema","mybotrun-observer-android-1")
            .put("source_video",src.name)
            .put("source_bytes",src.bytes)
            .put("duration_ms",result.durationMs)
            .put("complete",result.complete)
            .put("decode_mode",result.decodeMode)
            .put("yolo_executed",result.yoloRun)
            .put("yolo_model","best_320.onnx")
            .put("yolo_classes",JSONArray(YoloDetector.CLASSES))
            .put("sampling_base_seconds",0.5)
            .put("sampling_motion_seconds",0.25)
            .put("yolo_period_seconds",2)
            .put("samples",result.samples.size)
            .put("detections",result.detections.size)
            .put("error",result.failure ?: JSONObject.NULL)
            .put("action_identification","NO: bottom-bar changes are hypotheses, not confirmed deployments")
        txt(zip,"manifest.json",status.toString(2))
        val timeline=StringBuilder("timestamp_ms,frame_difference,bottom_bar_difference,possible_action_unverified\n")
        for(s in result.samples)timeline.append("${s.ms},${s.frameDiff},${s.bottomDiff},${s.active}\n")
        txt(zip,"timeline.csv",timeline.toString())
        val objects=StringBuilder("timestamp_ms,class_name,confidence,x_norm,y_norm,width_norm,height_norm,verified\n")
        for(r in result.detections)objects.append("${r.ms},${csvField(r.klass)},${r.confidence},${r.x},${r.y},${r.w},${r.h},false\n")
        txt(zip,"detections_yolo.csv",objects.toString())
        val actions=StringBuilder("timestamp_ms,event_kind,source,verified\n")
        for(s in result.samples) if(s.active) actions.append("${s.ms},army_bar_change,visual_difference,false\n")
        txt(zip,"action_candidates.csv",actions.toString())
        txt(zip,"README.txt","Video analizzato LOCALMENTE su Android.\n"+
            "Le strutture YOLO sono ipotesi visive; gli eventi barra non indicano necessariamente schieramenti.\n"+
            "Azioni ed edifici non vanno inviati al motore tattico come verità accertate senza convalida.\n"+
            "Stato: ${if(result.complete)"COMPLETO" else "INCOMPLETO"}. ${result.failure ?: ""}\n")
        for((ms,bytes) in result.frames) {
            zip.putNextEntry(ZipEntry("frames/frame_${ms}ms.jpg"))
            zip.write(bytes)
            zip.closeEntry()
        }
    }
    fun createSingle(result:VideoResult,output:File) {
        ZipOutputStream(FileOutputStream(output)).use {zip->writeVideo(zip,result)}
    }
    fun createBatch(output:File,packs:List<Pair<String,File>>,indices:List<JSONObject>) {
        ZipOutputStream(FileOutputStream(output)).use {zip->
            val summary=JSONObject().put("schema","mybotrun-android-batch-1")
                .put("created_ms",System.currentTimeMillis()).put("videos",JSONArray(indices))
            txt(zip,"INDICE_BATCH.json",summary.toString(2))
            val messages=indices.joinToString("\n") { j ->
                "${j.optString("name")}: ${j.optString("status")} · ${j.optString("error")}" }
            txt(zip,"DIAGNOSI_UMANA.txt",messages)
            for((name,file) in packs) {
                zip.putNextEntry(ZipEntry(name))
                FileInputStream(file).use {it.copyTo(zip)}
                zip.closeEntry()
            }
        }
    }
}
