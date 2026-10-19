package com.mybotrun.observer

import android.app.Activity
import android.content.Intent
import android.database.Cursor
import android.graphics.Color
import android.net.Uri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.OpenableColumns
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import android.widget.Button
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.util.Locale
import java.util.concurrent.atomic.AtomicBoolean

/** Three-step interface, native Android. No accessibility service, overlays, internet or game control. */
class MainActivity : Activity() {
    private val pickFiles=1001
    private val writeZip=1002
    private val selected=ArrayList<VideoEntry>()
    private val cancel=AtomicBoolean(false)
    private val ui=Handler(Looper.getMainLooper())
    @Volatile private var running=false
    @Volatile private var batchFile:File?=null
    private lateinit var fileList:TextView
    private lateinit var status:TextView
    private lateinit var progress:ProgressBar
    private lateinit var startButton:Button
    private lateinit var stopButton:Button
    private lateinit var exportButton:Button
    private val bg=Color.rgb(14,34,48)
    private val boxColor=Color.rgb(23,51,66)
    private val teal=Color.rgb(17,142,130)
    private val foreground=Color.rgb(229,240,245)

    override fun onCreate(savedInstanceState:Bundle?) {
        super.onCreate(savedInstanceState)
        window.statusBarColor=bg
        window.navigationBarColor=bg
        window.decorView.systemUiVisibility=0
        val scroll=ScrollView(this)
        scroll.setBackgroundColor(bg)
        val main=LinearLayout(this).apply {
            orientation=LinearLayout.VERTICAL
            setPadding(dp(20),dp(32),dp(20),dp(24))
        }
        scroll.addView(main)
        main.addView(text("MyBotRun Osservatore",26,true))
        main.addView(text("ANDROID NATIVO · Solo analisi offline",13,false))
        main.addView(text("1. VIDEO    →    2. START    →    3. SCARICA",18,true))
        main.addView(text("Seleziona più registrazioni MP4. Sono analizzate una alla volta, senza Chrome.",14,false))
        val pick=button("+ SELEZIONA VIDEO", boxColor) { openPicker() }
        main.addView(pick)
        fileList=text("Nessun video selezionato",14,false)
        main.addView(fileList)
        startButton=button("▶ START · ANALIZZA TUTTO",teal){startAnalysis()}
        main.addView(startButton)
        stopButton=button("INTERROMPI",boxColor){cancel.set(true); updateStatus("Interruzione richiesta: preparo i dati parziali...")}
        main.addView(stopButton)
        main.addView(text("Edifici e difese: YOLOv5 a 16 classi incluso. Truppe e azioni: rilevazione di cambiamenti da verificare; non ancora riconoscimento completo.",13,false))
        progress=ProgressBar(this,null,android.R.attr.progressBarStyleHorizontal).apply {
            max=100;progress=0;isIndeterminate=false
        }
        main.addView(progress,LinearLayout.LayoutParams(-1,dp(16)).apply {topMargin=dp(18);bottomMargin=dp(18)})
        status=text("Pronto. Video e risultati rimangono sul telefono.",14,false)
        status.setBackgroundColor(boxColor)
        status.setPadding(dp(12),dp(14),dp(12),dp(14))
        main.addView(status)
        exportButton=button("↓ SALVA ZIP RISULTATI",teal){saveBatch()}
        main.addView(exportButton)
        main.addView(text("Per questa prima versione tieni l'app in primo piano e lo schermo acceso. Nessuna connessione richiesta durante l'analisi.",12,false))
        setContentView(scroll)
        updateButtons()
    }
    private fun dp(n:Int)= (n*resources.displayMetrics.density).toInt()
    private fun text(str:String,size:Int,bold:Boolean):TextView=TextView(this).apply {
        text=str; setTextColor(foreground);textSize=size.toFloat()
        setPadding(0,dp(8),0,dp(8))
        if(bold) setTypeface(typeface,android.graphics.Typeface.BOLD)
    }
    private fun button(title:String,color:Int,click:()->Unit):Button=Button(this).apply {
        text=title;isAllCaps=false;textSize=16f
        setTextColor(Color.WHITE)
        setBackgroundTintList(android.content.res.ColorStateList.valueOf(color))
        setOnClickListener{click()}
        val lp=LinearLayout.LayoutParams(-1,dp(62))
        lp.topMargin=dp(12); lp.bottomMargin=dp(7)
        layoutParams=lp
    }
    private fun openPicker() {
        if(running) return
        val intent=Intent(Intent.ACTION_OPEN_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE)
            type="video/*"
            putExtra(Intent.EXTRA_ALLOW_MULTIPLE,true)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_PERSISTABLE_URI_PERMISSION)
        }
        startActivityForResult(intent,pickFiles)
    }
    @Deprecated("Used for compatibility without UI libraries")
    override fun onActivityResult(requestCode:Int,resultCode:Int,data:Intent?) {
        super.onActivityResult(requestCode,resultCode,data)
        if(resultCode!=RESULT_OK || data==null) return
        when(requestCode) {
            pickFiles -> {
                val incoming=ArrayList<Uri>()
                data.clipData?.let { for(i in 0 until it.itemCount) incoming.add(it.getItemAt(i).uri) }
                data.data?.let { if(!incoming.contains(it)) incoming.add(it) }
                selected.clear()
                for(uri in incoming.distinct()) {
                    try {contentResolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)}
                    catch (_:SecurityException) { /* Grant remains valid for this session */ }
                    selected.add(readVideoInfo(uri))
                }
                batchFile?.delete();batchFile=null
                fileList.text=if(selected.isEmpty()) "Nessun video selezionato" else
                    selected.mapIndexed {i,v->"${i+1}. ${v.name} · ${fmtBytes(v.bytes)}"}.joinToString("\n")
                updateStatus("${selected.size} video in coda, pronti per START")
                progress.progress=0
                updateButtons()
            }
            writeZip -> {
                val outputUri=data.data ?: return
                val file=batchFile ?: return
                updateStatus("Copio lo ZIP nei Download...")
                Thread {
                    try {
                        contentResolver.openOutputStream(outputUri)?.use { sink ->
                            file.inputStream().use { it.copyTo(sink,128*1024) }
                        } ?: error("Impossibile aprire il file di destinazione")
                        ui.post {updateStatus("ZIP salvato. ${fmtBytes(file.length())}")}
                    } catch(e:Exception) {
                        ui.post {updateStatus("Errore salvataggio: ${e.message}")}
                    }
                }.start()
            }
        }
    }
    private fun readVideoInfo(uri:Uri):VideoEntry {
        var name=uri.lastPathSegment ?: "video.mp4";var bytes=-1L
        try {
            contentResolver.query(uri,arrayOf(OpenableColumns.DISPLAY_NAME,OpenableColumns.SIZE),null,null,null)?.use {cur->
                if(cur.moveToFirst()) {
                    val n=cur.getColumnIndex(OpenableColumns.DISPLAY_NAME)
                    val b=cur.getColumnIndex(OpenableColumns.SIZE)
                    if(n>=0 && !cur.isNull(n)) name=cur.getString(n)
                    if(b>=0 && !cur.isNull(b)) bytes=cur.getLong(b)
                }
            }
        } catch(_:Exception){}
        return VideoEntry(uri,name,bytes)
    }
    private fun fmtBytes(bytes:Long):String = if(bytes<0)"dimensione ignota" else
        String.format(Locale.ITALY,"%.1f MB",bytes/1048576.0)
    private fun updateStatus(value:String){status.text=value}
    private fun updateButtons(){
        startButton.isEnabled=!running && selected.isNotEmpty()
        stopButton.isEnabled=running
        exportButton.isEnabled=!running && batchFile?.exists()==true
    }
    private fun startAnalysis() {
        if(running || selected.isEmpty()) return
        running=true;cancel.set(false);batchFile?.delete();batchFile=null
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        progress.progress=0;updateButtons()
        val queue=selected.toList()
        Thread {
            val packs=ArrayList<Pair<String,File>>()
            val index=ArrayList<JSONObject>()
            var detector:YoloDetector?=null
            var modelError:String?=null
            try { detector=YoloDetector(this) }
            catch(e:Exception) {modelError="${e.javaClass.simpleName}: ${e.message}"}
            try {
                for((position,video) in queue.withIndex()) {
                    if(cancel.get())break
                    ui.post{updateStatus("${position+1}/${queue.size} · ${video.name}\nAvvio decoder nativo...")}
                    val engine=VideoEngine(this,detector,modelError)
                    val result=engine.analyze(video,cancel) {ms,duration,phase ->
                        val fraction=if(duration>0) (ms.toDouble()/duration).coerceIn(0.0,1.0) else 0.0
                        val overall=((position+fraction)*100.0/queue.size).toInt().coerceIn(0,99)
                        ui.post {
                            progress.progress=overall
                            updateStatus("Video ${position+1}/${queue.size}: ${video.name}\n$phase · ${ms/1000}s" )
                        }
                    }
                    val file=File.createTempFile("observer_${position}_",".zip",cacheDir)
                    try {
                        ReportZip.createSingle(result,file)
                        packs.add("video_${position+1}_${safeName(video.name)}.zip" to file)
                        index.add(JSONObject().put("name",video.name).put("status",if(result.complete)"COMPLETO" else "INCOMPLETO")
                            .put("error",result.failure ?: JSONObject.NULL).put("samples",result.samples.size)
                            .put("detections",result.detections.size).put("yolo_run",result.yoloRun))
                    } catch(e:Exception) {
                        file.delete()
                        index.add(JSONObject().put("name",video.name).put("status","ERRORE ESPORTAZIONE")
                            .put("error",e.message ?: "errore non specificato"))
                    }
                }
                val zip=File(cacheDir,"MyBotRun_Osservazioni_Android_${System.currentTimeMillis()}.zip")
                ReportZip.createBatch(zip,packs,index)
                batchFile=zip
                val success=index.count{it.optString("status")=="COMPLETO"}
                ui.post{
                    progress.progress=100
                    updateStatus("Elaborazione terminata. $success/${index.size} analisi complete con YOLO.\n"+
                        "ZIP pronto: ${fmtBytes(zip.length())}. In caso di errori leggi DIAGNOSI_UMANA.txt.")
                }
            } catch(e:Exception) {
                ui.post{ updateStatus("Errore batch: ${e.javaClass.simpleName}: ${e.message}") }
            } finally {
                packs.forEach{it.second.delete()}
                try {detector?.close()} catch (_:Exception){}
                running=false
                ui.post{window.clearFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON);updateButtons()}
            }
        }.start()
    }
    private fun safeName(str:String)=str.replace(Regex("[^A-Za-z0-9._-]"),"_").take(90)
    private fun saveBatch() {
        if(running || batchFile?.exists()!=true)return
        val intent=Intent(Intent.ACTION_CREATE_DOCUMENT).apply {
            addCategory(Intent.CATEGORY_OPENABLE);type="application/zip"
            putExtra(Intent.EXTRA_TITLE,"MyBotRun_Osservazioni_Android.zip")
        }
        startActivityForResult(intent,writeZip)
    }
}
