package com.plugin.person;

/** 얼굴 모델의 입력 정렬·벡터 정규화·판정을 플랫폼과 분리합니다. */
public final class PersonMath {
    /** 상태를 만들지 않는 수학 함수 묶음입니다. */
    private PersonMath() {}

    /** SFace 공식 112×112 전처리의 다섯 랜드마크 순서를 반환합니다. */
    public static float[] template() {
        return new float[]{38.2946f,51.6963f,73.5318f,51.5014f,56.0252f,71.7366f,41.5493f,92.3655f,70.7299f,92.2041f};
    }

    /** 반사를 허용하지 않는 최소제곱 유사변환을 다섯 점 전체로 추정합니다. */
    public static float[] alignment(float[] source) {
        if (source.length != 10) throw new IllegalArgumentException("다섯 랜드마크가 필요합니다.");
        float[] target = template();
        double sx=0, sy=0, tx=0, ty=0;
        for (int i=0; i<5; i++) {
            if (!Float.isFinite(source[2*i]) || !Float.isFinite(source[2*i+1]))
                throw new IllegalArgumentException("랜드마크 좌표가 유효하지 않습니다.");
            sx+=source[2*i]; sy+=source[2*i+1]; tx+=target[2*i]; ty+=target[2*i+1];
        }
        sx/=5; sy/=5; tx/=5; ty/=5;
        double denominator=0, real=0, imaginary=0;
        for (int i=0; i<5; i++) {
            double x=source[2*i]-sx, y=source[2*i+1]-sy;
            double u=target[2*i]-tx, v=target[2*i+1]-ty;
            denominator+=x*x+y*y; real+=x*u+y*v; imaginary+=x*v-y*u;
        }
        if (denominator<=1e-8) throw new IllegalArgumentException("서로 겹친 랜드마크는 정렬할 수 없습니다.");
        double a=real/denominator, b=imaginary/denominator;
        if (a*a+b*b<=1e-12) throw new IllegalArgumentException("랜드마크 변환이 퇴화했습니다.");
        return new float[]{(float)a,(float)-b,(float)(tx-a*sx+b*sy),
            (float)b,(float)a,(float)(ty-b*sx-a*sy)};
    }

    /** 영벡터·비유한 값을 거절하고 모델 출력을 단위 길이로 복사합니다. */
    public static float[] normalize(float[] values) {
        if (values.length==0) throw new IllegalArgumentException("빈 임베딩입니다.");
        double norm=0;
        for (float value: values) {
            if (!Float.isFinite(value)) throw new IllegalArgumentException("임베딩에 비정상 값이 있습니다.");
            norm+=(double)value*value;
        }
        if (norm<=1e-20) throw new IllegalArgumentException("영벡터 임베딩입니다.");
        float[] normalized=values.clone(); norm=Math.sqrt(norm);
        for (int i=0; i<normalized.length; i++) normalized[i]/=norm;
        return normalized;
    }

    /** 같은 모델·전처리의 두 유효 벡터에 대한 코사인 유사도를 계산합니다. */
    public static double cosine(float[] first, float[] second) {
        if (first.length==0 || first.length!=second.length) throw new IllegalArgumentException("임베딩 차원이 다릅니다.");
        double product=0, a=0, b=0;
        for (int i=0; i<first.length; i++) {
            if (!Float.isFinite(first[i]) || !Float.isFinite(second[i]))
                throw new IllegalArgumentException("임베딩에 비정상 값이 있습니다.");
            product+=(double)first[i]*second[i]; a+=(double)first[i]*first[i]; b+=(double)second[i]*second[i];
        }
        if (a<=1e-20 || b<=1e-20) throw new IllegalArgumentException("영벡터 임베딩입니다.");
        // 다중 얼굴 비교에서 매 쌍마다 정규화 배열 두 개를 할당하지 않습니다.
        return Math.max(-1,Math.min(1,product/Math.sqrt(a*b)));
    }

    /** 검증된 거부·승인 경계 사이 또는 품질 부족 입력은 보류합니다. */
    public static String decide(double score, double reject, double accept, boolean quality) {
        if (!Double.isFinite(score) || !Double.isFinite(reject) || !Double.isFinite(accept) ||
                reject < -1 || accept > 1 || reject > accept || score < -1 || score > 1)
            throw new IllegalArgumentException("유사도 또는 검증 임계값이 유효하지 않습니다.");
        if (!quality) return "판정 보류";
        if (score>=accept) return "동일인물";
        if (score<reject) return "다른 인물";
        return "판정 보류";
    }

    /** 대상 예시의 엄격 과반수 점수로 판정하고 주의대상과 보류 구간만큼 떨어지지 않으면 보류합니다. */
    public static String decideReferences(float[][] targets, float[][] cautions, float[] sample,
            double reject, double accept, boolean quality) {
        if (targets.length<1 || targets.length>5 || cautions.length>5)
            throw new IllegalArgumentException("대상 예시는 1~5개, 주의대상은 최대 5개입니다.");
        double[] scores = new double[targets.length];
        for (int i=0; i<targets.length; i++) scores[i]=cosine(targets[i],sample);
        java.util.Arrays.sort(scores);
        double score=scores[(scores.length-1)/2], other=-2;
        for (float[] caution: cautions) other=Math.max(other,cosine(caution,sample));
        String verdict=decide(score,reject,accept,quality);
        // 보정되지 않은 새 상수를 만들지 않고 기존 보류 구간을 보수적인 분리 폭으로 사용합니다.
        if (verdict.equals("동일인물") && cautions.length>0 && other>=score-(accept-reject)) return "판정 보류";
        return verdict;
    }

    /** 같은 트랙의 250ms 이상 떨어진 표본 중 최소 두 개와 엄격 과반수의 승인을 요구합니다. */
    public static String decideVideo(float[][] targets, float[][] cautions, float[][] evidence, long[] times,
            double reject, double accept, boolean quality) {
        if (evidence.length!=times.length || evidence.length>3 || targets.length<1 || targets.length>5 || cautions.length>5)
            throw new IllegalArgumentException("영상 근거는 시각이 대응하는 최대 세 표본입니다.");
        decide(0,reject,accept,quality);
        int count=0, accepted=0, different=0;
        long previous=-1, chosen=-1;
        for (int i=0; i<evidence.length; i++) {
            if (times[i]<0 || times[i]<=previous) throw new IllegalArgumentException("영상 근거 시각은 증가해야 합니다.");
            previous=times[i];
            String verdict=decideReferences(targets,cautions,evidence[i],reject,accept,quality);
            if (chosen>=0 && times[i]-chosen<250) continue;
            chosen=times[i]; count++;
            if (verdict.equals("동일인물")) accepted++;
            else if (verdict.equals("다른 인물")) different++;
        }
        if (count<2) return "판정 보류";
        if (accepted>=count/2+1) return "동일인물";
        return different==count ? "다른 인물" : "판정 보류";
    }
}
