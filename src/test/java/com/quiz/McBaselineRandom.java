package com.quiz;

import java.util.ArrayList;
import java.util.List;
import java.util.Random;

/** 테스트 계측 JVM에서만 기본 생성자 Random 대신 사용한다. 난수 알고리즘은 Random 그대로다. */
public final class McBaselineRandom extends Random {
    private static final long serialVersionUID = 1L;
    private static final List<McBaselineRandom> CREATED = new ArrayList<>();

    public McBaselineRandom() { super(7L); CREATED.add(this); }
    static void reset() { CREATED.clear(); }
    static Random latest() {
        if (CREATED.isEmpty()) throw new IllegalStateException("Main의 seed 7 Random 계측이 적용되지 않았습니다.");
        return CREATED.getLast();
    }
}
