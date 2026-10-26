package com.plugin.person

import android.content.Context
import org.json.JSONObject
import java.io.FileNotFoundException

/** 모델·전처리 해시에 묶인 검증 임계값과 별도 검증 세트의 측정값입니다. */
data class PersonThreshold(val reject: Double, val accept: Double, val far: Double,
    val frr: Double, val tar: Double, val hold: Double)

/** 출처가 없는 숫자를 기본값으로 만들지 않고 검증 산출물만 판정에 사용합니다. */
object PersonCalibration {
    /** 누락된 검증은 보류로 처리하되 해시 불일치·손상된 검증은 명시적으로 거절합니다. */
    fun load(context: Context, model: PersonModels.Model): List<PersonThreshold>? {
        val source = try { context.assets.open("person-ai/${model.id}-calibration.json") }
            catch (_: FileNotFoundException) { return null }
        val json = source.use { JSONObject(it.bufferedReader().readText()) }
        require(json.getString("model_sha256") == model.recommendation.sha256 &&
            json.getString("preprocessing") == "sface-five-landmark-similarity-v1") { "임계값과 현재 모델의 검증 버전이 다릅니다." }
        require(json.getInt("train_pairs") > 0 && json.getInt("test_pairs") > 0 && json.getString("dataset").isNotBlank())
        val levels = json.getJSONArray("levels")
        require(levels.length() == 5) { "검증된 5단계 임계값이 필요합니다." }
        val thresholds = (0 until 5).map { index ->
            val value = levels.getJSONObject(index)
            PersonThreshold(value.getDouble("reject"),value.getDouble("accept"),value.getDouble("far"),
                value.getDouble("frr"),value.getDouble("tar"),value.getDouble("hold")).also {
                require(it.reject.isFinite() && it.accept.isFinite() && it.reject >= -1 && it.accept <= 1 && it.reject <= it.accept)
                require(listOf(it.far,it.frr,it.tar,it.hold).all { metric -> metric.isFinite() && metric in 0.0..1.0 })
            }
        }
        require(thresholds.zipWithNext().all { (a,b) -> a.accept <= b.accept }) { "판별 강도별 승인 기준의 순서가 잘못되었습니다." }
        return thresholds
    }
}
