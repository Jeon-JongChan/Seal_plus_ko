package com.plugin.duplicates

import android.content.Context
import android.database.sqlite.SQLiteDatabase
import android.database.sqlite.SQLiteOpenHelper
import android.util.AtomicFile
import com.plugin.compatibility.TemporarySession
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File

/** 원본 DB를 건드리지 않고 활성 저장소별 분석 캐시·제외 기록·검토 결과를 보존합니다. */
class DuplicateStore(context: Context) : SQLiteOpenHelper(context,
    TemporarySession.root?.let { File(it, ".data/duplicates.db").apply { parentFile?.mkdirs() }.path } ?: "duplicates.db", null, 1) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    private val reportRoot = TemporarySession.root?.let { File(it, ".data") } ?: context.filesDir

    /** 전체의 기존 파일명은 유지하고 유형별 결과를 독립적으로 보존합니다. */
    private fun reportFile(target: DuplicateTarget) = AtomicFile(File(reportRoot,
        if (target == DuplicateTarget.ALL) "duplicate-report.json" else "duplicate-report-${target.name.lowercase()}.json"))

    /** 특징 캐시와 사용자가 남긴 제외 기록은 별도 테이블에 저장합니다. */
    override fun onCreate(db: SQLiteDatabase) {
        db.execSQL("CREATE TABLE features (path TEXT NOT NULL, model TEXT NOT NULL, stamp TEXT NOT NULL, data TEXT NOT NULL, PRIMARY KEY(path, model))")
        db.execSQL("CREATE TABLE exclusions (identity TEXT PRIMARY KEY NOT NULL)")
    }

    /** 알 수 없는 새 스키마를 임의로 초기화하지 않습니다. */
    override fun onUpgrade(db: SQLiteDatabase, oldVersion: Int, newVersion: Int) { error("지원하지 않는 중복 분석 DB 버전입니다.") }

    /** 파일의 크기·수정시각·모델이 같은 경우만 계산을 재사용합니다. */
    fun cached(path: String, model: String, stamp: String): DuplicateFeature? = readableDatabase.rawQuery(
        "SELECT data FROM features WHERE path = ? AND model = ? AND stamp = ?", arrayOf(path, model, stamp)).use {
        if (it.moveToFirst()) runCatching { json.decodeFromString<DuplicateFeature>(it.getString(0)) }.getOrNull() else null
    }

    /** 한 파일 처리마다 저장해 중단 후 재실행에서도 완료된 추론을 재사용합니다. */
    fun cache(feature: DuplicateFeature, model: String, stamp: String) {
        writableDatabase.execSQL("INSERT OR REPLACE INTO features(path, model, stamp, data) VALUES(?, ?, ?, ?)",
            arrayOf(feature.media.path, model, stamp, json.encodeToString(feature)))
    }

    /** SHA와 경로가 함께 일치할 때만 제외하므로 수정된 파일은 다시 검사합니다. */
    fun exclusions(): Set<String> = readableDatabase.rawQuery("SELECT identity FROM exclusions", null).use { cursor ->
        buildSet { while (cursor.moveToNext()) add(cursor.getString(0)) }
    }

    /** 사용자가 제외를 누른 항목만 기록하며 기록 끔은 현재 검토 목록에만 적용됩니다. */
    fun exclude(media: DuplicateMedia) {
        writableDatabase.execSQL("INSERT OR IGNORE INTO exclusions(identity) VALUES(?)", arrayOf(media.identity))
    }

    /** 재검사를 원하는 사용자의 명시적 버튼으로 제외 기록을 초기화합니다. */
    fun clearExclusions() { writableDatabase.execSQL("DELETE FROM exclusions") }

    /** 후보 목록 갱신은 원자적으로 확정해 프로세스 종료 시 이전 목록을 보존합니다. */
    fun save(report: DuplicateReport) {
        val reportFile = reportFile(report.target)
        val output = reportFile.startWrite()
        try { output.write(json.encodeToString(report).toByteArray(Charsets.UTF_8)); reportFile.finishWrite(output) }
        catch (failure: Throwable) { reportFile.failWrite(output); throw failure }
    }

    /** 저장 결과가 없으면 빈 목록을 제공하고 손상된 결과는 오류로 알립니다. */
    fun load(target: DuplicateTarget = DuplicateTarget.ALL): DuplicateReport {
        val reportFile = reportFile(target)
        if (!reportFile.baseFile.exists() && !File(reportFile.baseFile.path + ".bak").exists()) return DuplicateReport(target = target)
        return reportFile.openRead().use { json.decodeFromString<DuplicateReport>(it.readBytes().toString(Charsets.UTF_8)) }.also {
            check(it.target == target && it.groups.all { group -> group.members.size >= 2 &&
                group.members.all { media -> target.accepts(if (media.video) 1 else 2) } && group.members.map { media -> media.video }.distinct().size == 1 }) {
                "저장된 분석 범위가 올바르지 않습니다. 다시 분석하세요."
            }
        }
    }
}
