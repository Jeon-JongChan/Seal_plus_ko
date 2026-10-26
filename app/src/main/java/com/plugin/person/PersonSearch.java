package com.plugin.person;

import java.util.*;

/** 모델별 벡터를 순차 공급받아 메모리 O(K)로 정확한 코사인 상위 K를 구합니다. */
public final class PersonSearch {
    /** 원본 얼굴 식별자와 재계산된 유사도입니다. */
    public static final class Match {
        private final long id;
        private final double score;
        /** 검색 후보의 식별자와 점수를 함께 보존합니다. */
        public Match(long id, double score) { this.id=id; this.score=score; }
        /** 모델별 저장소의 얼굴 식별자를 반환합니다. */
        public long id() { return id; }
        /** 확률이 아닌 코사인 유사도를 반환합니다. */
        public double score() { return score; }
    }

    private final float[] query;
    private final int count;
    private final Comparator<Match> worstFirst = Comparator.comparingDouble(Match::score)
        .thenComparing(Comparator.comparingLong(Match::id).reversed());
    private final PriorityQueue<Match> heap = new PriorityQueue<>(worstFirst);

    /** 신뢰 경계에서 쿼리 영벡터·차원과 비정상 K를 거절합니다. */
    public PersonSearch(float[] query, int count) {
        if (count<1 || count>1000) throw new IllegalArgumentException("검색 개수는 1~1000이어야 합니다.");
        this.query=PersonMath.normalize(query); this.count=count;
    }

    /** 후보의 원본 벡터로 코사인을 재계산하고 상위 후보만 유지합니다. */
    public void offer(long id, float[] vector) {
        if (vector.length!=query.length) throw new IllegalArgumentException("검색 벡터 차원이 다릅니다.");
        double product=0, norm=0;
        for (int i=0; i<vector.length; i++) {
            if (!Float.isFinite(vector[i])) throw new IllegalArgumentException("비정상 검색 벡터입니다.");
            product+=(double)query[i]*vector[i]; norm+=(double)vector[i]*vector[i];
        }
        if (norm<=1e-20) throw new IllegalArgumentException("영벡터는 검색할 수 없습니다.");
        double score=Math.max(-1,Math.min(1,product/Math.sqrt(norm)));
        Match worst=heap.peek();
        if (heap.size()==count && (score<worst.score() || score==worst.score() && id>=worst.id())) return;
        if (heap.size()==count) heap.poll();
        heap.offer(new Match(id,score));
    }

    /** 동률은 식별자 순으로 정렬하여 반복 검색의 순서를 안정적으로 유지합니다. */
    public List<Match> results() {
        List<Match> result=new ArrayList<>(heap);
        result.sort(Comparator.comparingDouble(Match::score).reversed().thenComparingLong(Match::id));
        return Collections.unmodifiableList(result);
    }
}
