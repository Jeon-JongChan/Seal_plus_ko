package com.plugin.compatibility

import android.net.Uri
import java.util.Locale

/** 사용자가 직접 정한 폴더 이름으로 화질 분류만 제외하며 다른 모듈의 설정을 참조하지 않습니다. */
object QualityFolderExclusions {
    private val preferences get() = com.junkfood.seal.App.context.getSharedPreferences("media-quality-exclusions",0)
    /** 저장된 이름은 호출자에게 독립 사본으로 전달합니다. */
    fun names(): Set<String> = preferences.getStringSet("names",emptySet()).orEmpty().toSet()
    /** 경로 구분자·상대 경로를 거절하고 실제 폴더 이름만 저장합니다. */
    fun save(values: Set<String>) {
        require(values.all { it.isNotBlank() && it !in setOf(".","..") && it.none { c -> c == '/' || c == '\\' || c.isISOControl() } }) {
            "경로 대신 폴더 이름만 입력하세요. /, \\, 제어 문자와 . 또는 ..은 사용할 수 없습니다."
        }
        preferences.edit().putStringSet("names",values).apply()
    }
    /** 일반 경로와 SAF 문서 ID의 상위 폴더 이름을 같은 규칙으로 비교합니다. */
    fun contains(path: String): Boolean {
        val folders = names().map { it.lowercase(Locale.ROOT) }.toSet()
        if (folders.isEmpty()) return false
        val decoded = if (path.startsWith("content://")) Uri.decode(Uri.parse(path).path.orEmpty()) else path
        return decoded.replace('\\','/').split('/').dropLast(1).any { it.substringAfterLast(':').lowercase(Locale.ROOT) in folders }
    }
}
