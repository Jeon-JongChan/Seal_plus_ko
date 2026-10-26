package com.plugin.person

import android.graphics.Bitmap
import kotlin.math.abs
import kotlin.math.atan2

/** 품질 기준은 동일인물 승인 임계값과 별개인 재선택 안내로만 사용합니다. */
object PersonQuality {
    /** 정렬된 얼굴의 해상도·노출·흐림·랜드마크 배치를 확인합니다. */
    fun issues(face: PersonFace, aligned: Bitmap): List<String> {
        val issues = mutableListOf<String>()
        if (minOf(face.bounds.width(),face.bounds.height()) < 40) issues += "얼굴 해상도 부족"
        if (face.confidence < .8f) issues += "검출 신뢰도 부족"
        val p = face.landmarks
        val eyeDistance = kotlin.math.hypot(p[2]-p[0],p[3]-p[1])
        if (eyeDistance < 12) issues += "눈 사이 해상도 부족"
        val roll = abs(Math.toDegrees(atan2((p[3]-p[1]).toDouble(),(p[2]-p[0]).toDouble())))
        if (roll > 30) issues += "얼굴 기울기 큼"
        // 눈 축으로 투영해 정면 얼굴의 기울기를 측면 회전으로 잘못 판단하지 않습니다.
        val noseAlongEyes = (p[4]-(p[0]+p[2])/2)*(p[2]-p[0]) + (p[5]-(p[1]+p[3])/2)*(p[3]-p[1])
        if (eyeDistance > 0 && abs(noseAlongEyes)/(eyeDistance*eyeDistance) > .3) issues += "얼굴 측면 또는 랜드마크 불안정"
        if (p.indices.step(2).any { p[it] !in face.bounds.left..face.bounds.right || p[it+1] !in face.bounds.top..face.bounds.bottom })
            issues += "얼굴 경계 또는 부분 가림 확인 필요"
        val pixels = IntArray(112*112); aligned.getPixels(pixels,0,112,0,0,112,112)
        val gray = DoubleArray(pixels.size) { i ->
            ((pixels[i] ushr 16 and 255)*299+(pixels[i] ushr 8 and 255)*587+(pixels[i] and 255)*114)/1000.0
        }
        val light = gray.average()
        if (light < 35 || light > 220) issues += "조명 부족 또는 과다 노출"
        var sum = 0.0; var squared = 0.0; var count = 0
        for (y in 1..110) for (x in 1..110) {
            val i = y*112+x
            val laplacian = gray[i-1]+gray[i+1]+gray[i-112]+gray[i+112]-4*gray[i]
            sum += laplacian; squared += laplacian*laplacian; count++
        }
        if (squared/count-(sum/count)*(sum/count) < 40) issues += "흐림 또는 세부 정보 부족"
        return issues
    }
}
