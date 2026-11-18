package com.chunsik.study

import android.content.ContentValues
import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import org.json.JSONArray
import org.json.JSONObject
import java.io.File
import java.time.Instant
import java.time.LocalDate
import java.time.ZoneId
import java.time.temporal.ChronoUnit

private const val DB_NAME = "study.db"

data class Subject(val id: Long, val name: String)
data class StudyRecord(val id: Long, val subjectId: Long, val subject: String, val start: Long, val end: Long, val seconds: Long)
data class Plan(val id: Long, val weekday: Int, val date: String, val subjectId: Long, val subject: String, val startMinute: Int, val endMinute: Int, val mode: String)
data class CalendarEvent(val id: Long, val date: String, val name: String, val type: String, val clock: String, val repeat: String)

class Store(context: Context): SQLiteOpenHelper(context, DB_NAME, null, 1) {
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE subject(id INTEGER PRIMARY KEY AUTOINCREMENT,name TEXT NOT NULL UNIQUE)")
        db.execSQL("CREATE TABLE record(id INTEGER PRIMARY KEY AUTOINCREMENT,subject_id INTEGER NOT NULL,start_ms INTEGER NOT NULL,end_ms INTEGER NOT NULL,seconds INTEGER NOT NULL)")
        db.execSQL("CREATE TABLE plan(id INTEGER PRIMARY KEY AUTOINCREMENT,weekday INTEGER NOT NULL,date TEXT NOT NULL DEFAULT '',subject_id INTEGER NOT NULL,start_min INTEGER NOT NULL,end_min INTEGER NOT NULL,mode TEXT NOT NULL DEFAULT 'weekly')")
        db.execSQL("CREATE TABLE event(id INTEGER PRIMARY KEY AUTOINCREMENT,date TEXT NOT NULL,name TEXT NOT NULL,type TEXT NOT NULL,clock TEXT NOT NULL,repeat_rule TEXT NOT NULL DEFAULT 'none')")
    }
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) {}
    fun subjects():List<Subject> = readableDatabase.rawQuery("SELECT id,name FROM subject ORDER BY id", null).use { c -> buildList { while(c.moveToNext()) add(Subject(c.getLong(0),c.getString(1))) } }
    fun addSubject(name:String) { if(name.isNotBlank()) writableDatabase.insertWithOnConflict("subject", null, ContentValues().apply {put("name",name.trim())},SQLiteDatabase.CONFLICT_IGNORE) }
    fun renameSubject(id:Long,name:String) { if(name.isNotBlank()) writableDatabase.update("subject",ContentValues().apply{put("name",name.trim())},"id=?", arrayOf(id.toString())) }
    // Soft-delete is deliberately avoided: deletion is blocked if the subject is referenced.
    fun deleteSubject(id:Long):Boolean { val referenced=readableDatabase.rawQuery("SELECT (SELECT COUNT(*) FROM record WHERE subject_id=?) + (SELECT COUNT(*) FROM plan WHERE subject_id=?)",arrayOf(id.toString(),id.toString())).use { it.moveToFirst();it.getInt(0)>0 }; if(referenced)return false;return writableDatabase.delete("subject","id=?",arrayOf(id.toString()))>0 }
    fun records():List<StudyRecord> = readableDatabase.rawQuery("SELECT r.id,r.subject_id,COALESCE(s.name,'삭제된 과목'),r.start_ms,r.end_ms,r.seconds FROM record r LEFT JOIN subject s ON r.subject_id=s.id ORDER BY r.start_ms DESC",null).use { c -> buildList {while(c.moveToNext())add(StudyRecord(c.getLong(0),c.getLong(1),c.getString(2),c.getLong(3),c.getLong(4),c.getLong(5)))} }
    fun addRecord(subjectId:Long,start:Long,end:Long,seconds:Long) { if(seconds<1)return;writableDatabase.insert("record",null,ContentValues().apply{put("subject_id",subjectId);put("start_ms",start);put("end_ms",end);put("seconds",seconds)}) }
    fun editRecord(id:Long,seconds:Long) { if(seconds>0) writableDatabase.update("record",ContentValues().apply{put("seconds",seconds)},"id=?",arrayOf(id.toString())) }
    fun deleteRecord(id:Long) { writableDatabase.delete("record","id=?",arrayOf(id.toString())) }
    fun plans():List<Plan> = readableDatabase.rawQuery("SELECT p.id,p.weekday,p.date,p.subject_id,COALESCE(s.name,'삭제된 과목'),p.start_min,p.end_min,p.mode FROM plan p LEFT JOIN subject s ON s.id=p.subject_id ORDER BY p.start_min",null).use { c -> buildList{while(c.moveToNext())add(Plan(c.getLong(0),c.getInt(1),c.getString(2),c.getLong(3),c.getString(4),c.getInt(5),c.getInt(6),c.getString(7))) } }
    fun addPlan(weekday:Int,date:String,subjectId:Long,start:Int,end:Int,mode:String):Boolean {
        if(start<0||end>1440||end<=start)return false
        val dateKey = if(mode=="weekly") "" else date
        val candidates=plans().filter { (it.mode=="weekly"&&it.weekday==weekday)||(it.mode!="weekly"&&it.date==date) }
        // Date-only replacement overrides matching subject weekly block; other overlaps still forbidden.
        val effective=candidates.filterNot { mode=="replace"&&it.mode=="weekly"&&it.subjectId==subjectId }
        if(effective.any { start<it.endMinute&&end>it.startMinute })return false
        writableDatabase.insert("plan",null,ContentValues().apply {put("weekday",weekday);put("date",dateKey);put("subject_id",subjectId);put("start_min",start);put("end_min",end);put("mode",mode)})
        return true
    }

    /** Multiple weekday schedule creation is atomic: no partial save on conflicts. */
    fun addWeeklyPlans(days:Set<Int>,subjectId:Long,start:Int,end:Int):Boolean {
        if(days.isEmpty()||days.any{it !in 1..7}||start !in 0..1439||end>1440||end<=start||subjects().none{it.id==subjectId}) return false
        val existing=plans()
        if(existing.any{it.mode=="weekly"&&it.weekday in days&&start<it.endMinute&&end>it.startMinute})return false
        val db=writableDatabase
        db.beginTransaction()
        return try {
            for(day in days) {
                val row=ContentValues().apply{put("weekday",day);put("date","");put("subject_id",subjectId);put("start_min",start);put("end_min",end);put("mode","weekly")}
                if(db.insertOrThrow("plan",null,row)<0)error("insert failed")
            }
            db.setTransactionSuccessful()
            true
        }catch(_:Exception){false}finally{db.endTransaction()}
    }
    fun deletePlan(id:Long) { writableDatabase.delete("plan","id=?",arrayOf(id.toString())) }
    fun plansFor(date:LocalDate):List<Plan> {
        val all=plans();val daily=all.filter{it.date==date.toString()&&it.mode!="weekly"}
        val overridden=daily.filter{it.mode=="replace"}.map{it.subjectId}.toSet()
        return (all.filter{it.mode=="weekly"&&it.weekday==date.dayOfWeek.value&&it.subjectId !in overridden}+daily).sortedBy{it.startMinute}
    }
    fun events():List<CalendarEvent> = readableDatabase.rawQuery("SELECT id,date,name,type,clock,repeat_rule FROM event ORDER BY date",null).use { c -> buildList {while(c.moveToNext())add(CalendarEvent(c.getLong(0),c.getString(1),c.getString(2),c.getString(3),c.getString(4),c.getString(5)))} }
    fun addEvent(date:String,name:String,type:String,clock:String,repeat:String) { writableDatabase.insert("event",null,ContentValues().apply{put("date",date);put("name",name);put("type",type);put("clock",clock);put("repeat_rule",repeat)}) }
    fun deleteEvent(id:Long) { writableDatabase.delete("event","id=?",arrayOf(id.toString())) }
    fun eventsFor(date:LocalDate):List<CalendarEvent> = events().filter { e -> val start=runCatching{LocalDate.parse(e.date)}.getOrNull()?:return@filter false; if(date<start) false else when(e.repeat) {"daily"->true;"weekly"->date.dayOfWeek==start.dayOfWeek;"monthly"->date.dayOfMonth==start.dayOfMonth;else->date==start} }
    fun isRest(date:LocalDate)=eventsFor(date).any { it.type=="rest" }
    fun targetFor(date:LocalDate):Long = if(isRest(date)) 0L else plansFor(date).sumOf { (it.endMinute-it.startMinute).toLong()*60 }
    fun totalsFor(date:LocalDate):Map<Long,Long> = records().filter { Instant.ofEpochMilli(it.start).atZone(ZoneId.systemDefault()).toLocalDate()==date }.groupBy{it.subjectId}.mapValues{(_,list)->list.sumOf{it.seconds}}
    fun totalsIn(start:LocalDate,end:LocalDate):Map<Long,Long> = records().filter { val d=Instant.ofEpochMilli(it.start).atZone(ZoneId.systemDefault()).toLocalDate();!d.isBefore(start)&&!d.isAfter(end) }.groupBy{it.subjectId}.mapValues{(_,list)->list.sumOf{it.seconds}}
    fun recordActiveSpan(subjectId:Long,fromMs:Long,toMs:Long) {
        if(toMs<=fromMs)return
        val zone=ZoneId.systemDefault();var cursor=fromMs
        writableDatabase.beginTransaction()
        try {
            while(cursor<toMs){
                val day=Instant.ofEpochMilli(cursor).atZone(zone).toLocalDate()
                val boundary=day.plusDays(1).atStartOfDay(zone).toInstant().toEpochMilli()
                val end=minOf(toMs,boundary)
                val duration=(end-cursor)/1000
                if(duration>0) addRecord(subjectId,cursor,end,duration)
                cursor=end
            }
            writableDatabase.setTransactionSuccessful()
        } finally {writableDatabase.endTransaction()}
    }
    fun snapshot():String {
        val root=JSONObject().put("schema",1).put("exported",System.currentTimeMillis())
        for(table in listOf("subject","record","plan","event")) {
            val arr=JSONArray();readableDatabase.rawQuery("SELECT * FROM $table",null).use{c->while(c.moveToNext()) {val row=JSONObject(); for(i in 0 until c.columnCount){ if(c.isNull(i)) row.put(c.getColumnName(i),JSONObject.NULL) else when(c.getType(i)){ android.database.Cursor.FIELD_TYPE_INTEGER->row.put(c.getColumnName(i),c.getLong(i));else->row.put(c.getColumnName(i),c.getString(i)) }};arr.put(row)}}
            root.put(table,arr)
        }
        return root.toString(2)
    }
    fun restore(json:String) {
        val root=JSONObject(json);require(root.getInt("schema")==1)
        val names=listOf("subject","record","plan","event")
        // Parse all rows first; transaction ensures failures don't leave partially restored data.
        val parsed=names.associateWith {name->val arr=root.getJSONArray(name);List(arr.length()){arr.getJSONObject(it)} }
        writableDatabase.beginTransaction()
        try {
            listOf("record","plan","event","subject").forEach{writableDatabase.delete(it,null,null)}
            names.forEach { table ->parsed.getValue(table).forEach { row ->
                val values=ContentValues();row.keys().forEach { k->if(!row.isNull(k)) {val v=row.get(k);if(v is Number)values.put(k,v.toLong()) else values.put(k,v.toString())} }
                require(writableDatabase.insertOrThrow(table,null,values)>0)
            }}
            writableDatabase.setTransactionSuccessful()
        }finally{writableDatabase.endTransaction()}
    }
}

class Backups(private val context:Context,private val store:Store) {
    private val dir get()=File(context.filesDir,"snapshots").apply{mkdirs()}
    fun checkMonthly() {
        val now=LocalDate.now();val prefs=context.getSharedPreferences("app",0)
        val month="${now.year}-${now.monthValue}"
        if(prefs.getString("backupMonth","")==month)return
        try {
            val target=File(dir,"backup-${now}.json");val tmp=File(dir,".pending")
            tmp.writeText(store.snapshot());require(tmp.length()>10)
            if(!tmp.renameTo(target)) {tmp.copyTo(target,true);tmp.delete()}
            prefs.edit().putString("backupMonth",month).apply()
            dir.listFiles()?.filter{it.name.startsWith("backup-")&&it.name.endsWith(".json") }?.forEach { file ->
                val date=runCatching{LocalDate.parse(file.name.removePrefix("backup-").removeSuffix(".json"))}.getOrNull()
                if(date!=null&&date.isBefore(now.minusMonths(6))) file.delete()
            }
        }catch(_:Exception){ /* next app launch retries */ }
    }
    fun snapshots():List<File> = dir.listFiles()?.filter{it.name.endsWith(".json") }?.sortedByDescending{it.name}?: emptyList()
}